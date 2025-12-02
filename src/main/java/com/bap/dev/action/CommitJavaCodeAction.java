package com.bap.dev.action;

import bap.java.CJavaCode;
import bap.java.CJavaConst;
import bap.java.CJavaFolderDto;
import bap.java.CommitPackage;
import com.bap.dev.BapRpcClient;
import com.bap.dev.listener.BapChangesNotifier;
import com.bap.dev.service.BapFileStatus;
import com.bap.dev.service.BapFileStatusService;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vcs.FileStatusManager;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.kwaidoo.ms.tool.CmnUtil;
import cplugin.ms.dto.CResFileDto;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CommitJavaCodeAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile[] selectedFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);

        if (project == null || selectedFiles == null || selectedFiles.length == 0) return;

        FileDocumentManager.getInstance().saveAllDocuments();

        VirtualFile moduleRoot = findModuleRoot(selectedFiles[0]);
        if (moduleRoot == null) {
            Messages.showWarningDialog("未找到 .develop 配置文件。", "错误");
            return;
        }

        String comments = Messages.showInputDialog(project,
                "请输入提交注释 (Comments):",
                "Commit Code",
                Messages.getQuestionIcon(),
                "", null);

        if (comments == null) return;

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Committing Files...", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                try {
                    commitWithPackage(project, moduleRoot, selectedFiles, comments);
                } catch (Exception ex) {
                    ex.printStackTrace();
                    showError("提交失败: " + ex.getMessage());
                }
            }
        });
    }

    private void commitWithPackage(Project project, VirtualFile moduleRoot, VirtualFile[] files, String comments) throws Exception {
        BapRpcClient client = prepareClient(moduleRoot);
        String projectUuid = getProjectUuid(moduleRoot);

        try {
            List<CJavaFolderDto> folders = client.getService().getFolders(projectUuid);

            CommitPackage pkg = new CommitPackage();
            pkg.setComments(comments);

            Map<String, List<CJavaCode>> mapFolder2Codes = new HashMap<>();
            Map<String, Set<String>> deleteCodeMap = new HashMap<>();
            Map<String, List<CResFileDto>> mapFolder2Files = new HashMap<>();
            Map<String, Set<String>> deleteFileMap = new HashMap<>();

            for (VirtualFile file : files) {
                VirtualFile currentRoot = findModuleRoot(file);
                if (currentRoot == null || !currentRoot.equals(moduleRoot)) continue;

                if (isResourceFile(currentRoot, file)) {
                    prepareResource(project, client, projectUuid, currentRoot, file, folders, mapFolder2Files, deleteFileMap);
                } else {
                    prepareJava(project, client, projectUuid, currentRoot, file, folders, mapFolder2Codes, deleteCodeMap);
                }
            }

            pkg.setMapFolder2Codes(mapFolder2Codes);
            pkg.setDeleteCodeMap(deleteCodeMap);
            pkg.setMapFolder2Files(mapFolder2Files);
            pkg.setDeleteFileMap(deleteFileMap);

            client.getService().commitCode(projectUuid, pkg);

            onSuccess(project, files);

        } finally {
            client.shutdown();
        }
    }

    // --- 资源文件准备 ---
    private void prepareResource(Project project, BapRpcClient client, String projectUuid, VirtualFile moduleRoot, VirtualFile file,
                                 List<CJavaFolderDto> folders,
                                 Map<String, List<CResFileDto>> updateMap,
                                 Map<String, Set<String>> deleteMap) throws Exception {

        BapFileStatus status = BapFileStatusService.getInstance(project).getStatus(file);
        String relativePath = getResourceRelativePath(moduleRoot, file); // pt/index.html
        if (relativePath == null) return;

        String folderName = "res";

        // 1. 删除逻辑
        if (status == BapFileStatus.DELETED_LOCALLY) {
            deleteMap.computeIfAbsent(folderName, k -> new HashSet<>()).add(relativePath);

            // --- 🔴 修复：标记该文件需要在 onSuccess 中被物理删除 ---
            // 注意：我们在 CommitPackage 模式下，这里只负责收集数据到 deleteMap
            // 物理删除本地文件的操作，应该放在 client.commitCode 成功之后统一做！
            // 否则你现在删了，万一提交失败了咋办？
            return;
        }

        // 2. 新增/修改逻辑
        // 注意：不再将 MODIFIED 加入 deleteMap，避免逻辑冲突

        byte[] content = file.contentsToByteArray();
        CResFileDto dto = new CResFileDto();
        dto.setFilePackage(relativePath);
        dto.setFileName(file.getName());

        int lastSlash = relativePath.lastIndexOf('/');
        if (lastSlash >= 0) {
            dto.setFilePackage(relativePath.substring(0, lastSlash).replace('/', '.'));
        }

        dto.setFileBin(content);
        dto.setSize((long) content.length);

        String ownerUuid = findFolderUuid(folders, folderName);
        if (ownerUuid != null) dto.setOwner(ownerUuid);

        // --- 🔴 关键：设置 UUID 以触发 Update ---
        // 无论本地状态如何，都先查一下云端。如果云端有，就填入 UUID，服务端会执行 Update。
        // 如果云端没有，UUID 为空，服务端会执行 Insert。
        CResFileDto existing = client.getService().getResFile(projectUuid, relativePath, false);
        if (existing != null) {
            dto.setUuid(existing.getUuid());
        }
        // --------------------------------------

        updateMap.computeIfAbsent(folderName, k -> new ArrayList<>()).add(dto);
    }

    // --- Java 文件准备 ---
    private void prepareJava(Project project, BapRpcClient client, String projectUuid, VirtualFile moduleRoot, VirtualFile file,
                             List<CJavaFolderDto> folders,
                             Map<String, List<CJavaCode>> updateMap,
                             Map<String, Set<String>> deleteMap) throws Exception {

        BapFileStatus status = BapFileStatusService.getInstance(project).getStatus(file);
        String fullClassName = resolveClassName(project, file);
        if (fullClassName == null) return;

        String folderName = getOwnerFolderName(moduleRoot, file);
        if (folderName == null) return;

        if (status == BapFileStatus.DELETED_LOCALLY) {
            deleteMap.computeIfAbsent(folderName, k -> new HashSet<>()).add(fullClassName);
            return;
        }

        CJavaCode code = new CJavaCode();
        code.setProjectUuid(projectUuid);
        code.setMainClass(file.getNameWithoutExtension());

        int lastDot = fullClassName.lastIndexOf('.');
        code.setJavaPackage((lastDot > 0) ? fullClassName.substring(0, lastDot) : "");

        String content = new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
        code.setCode(content);

        String ownerUuid = findFolderUuid(folders, folderName);
        if (ownerUuid != null) code.setOwner(ownerUuid);

        // 查找并复用 UUID
        Object remoteObj = client.getService().getJavaCode(projectUuid, fullClassName);
        if (remoteObj != null && remoteObj instanceof CJavaCode) {
            code.setUuid(((CJavaCode) remoteObj).getUuid());
        } else {
            code.setUuid(CmnUtil.allocUUIDWithUnderline());
        }

        updateMap.computeIfAbsent(folderName, k -> new ArrayList<>()).add(code);
    }

    // ... 辅助方法 (保持不变，请复制之前的实现) ...
    // onSuccess, getOwnerFolderName, findFolderUuid, prepareClient, getProjectUuid, isResourceFile, getResourceRelativePath, resolveClassName, findModuleRoot, extractAttr, showError, sendNotification, update, getActionUpdateThread 等

    private void onSuccess(Project project, VirtualFile[] files) {
        ApplicationManager.getApplication().invokeLater(() -> {
            List<VirtualFile> toDelete = new ArrayList<>();

            for (VirtualFile file : files) {
                // 1. 先获取当前状态
                BapFileStatus status = BapFileStatusService.getInstance(project).getStatus(file);

                // 2. 如果是 DELETED_LOCALLY (红D)，加入待删除列表，暂时不改状态
                if (status == BapFileStatus.DELETED_LOCALLY) {
                    toDelete.add(file);
                }
                // 3. 只有非删除状态的文件，才立即重置为 NORMAL
                else if (file.isValid()) {
                    BapFileStatusService.getInstance(project).setStatus(file, BapFileStatus.NORMAL);
                    file.refresh(false, false);
                }
            }

            // 4. 统一处理物理删除
            if (!toDelete.isEmpty()) {
                try {
                    WriteAction.run(() -> {
                        for(VirtualFile f : toDelete) {
                            // 为了状态服务的数据一致性，删除前置为 Normal (逻辑删除变为物理删除)
                            BapFileStatusService.getInstance(project).setStatus(f, BapFileStatus.NORMAL);

                            // 执行物理删除
                            if(f.isValid()) {
                                try {
                                    f.delete(this);
                                } catch (java.io.IOException e) {
                                    e.printStackTrace();
                                    // 可以在这里提示删除失败
                                }
                            }
                        }
                    });
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }

            PsiManager.getInstance(project).dropPsiCaches();
            FileStatusManager.getInstance(project).fileStatusesChanged();

            sendNotification(project, "提交成功", "已提交 " + files.length + " 个文件。");
            project.getMessageBus().syncPublisher(BapChangesNotifier.TOPIC).onChangesUpdated();
        });
    }

    private String getOwnerFolderName(VirtualFile moduleRoot, VirtualFile file) {
        VirtualFile srcDir = moduleRoot.findChild("src");
        if (srcDir == null) return null;
        String path = VfsUtilCore.getRelativePath(file, srcDir);
        if (path == null) return null;
        int idx = path.indexOf('/');
        return (idx > 0) ? path.substring(0, idx) : path;
    }

    private String findFolderUuid(List<CJavaFolderDto> folders, String name) {
        return folders.stream().filter(f -> f.getName().equals(name)).map(CJavaFolderDto::getUuid).findFirst().orElse(null);
    }

    private BapRpcClient prepareClient(VirtualFile moduleRoot) throws Exception {
        File confFile = new File(moduleRoot.getPath(), CJavaConst.PROJECT_DEVELOP_CONF_FILE);
        String content = Files.readString(confFile.toPath());
        String uri = extractAttr(content, "Uri");
        String user = extractAttr(content, "User");
        String pwd = extractAttr(content, "Password");
        BapRpcClient client = new BapRpcClient();
        client.connect(uri, user, pwd);
        return client;
    }

    private String getProjectUuid(VirtualFile moduleRoot) throws Exception {
        File confFile = new File(moduleRoot.getPath(), CJavaConst.PROJECT_DEVELOP_CONF_FILE);
        String content = Files.readString(confFile.toPath());
        return extractAttr(content, "Project");
    }

    private boolean isResourceFile(VirtualFile moduleRoot, VirtualFile file) {
        VirtualFile resDir = moduleRoot.findFileByRelativePath("src/res");
        return resDir != null && VfsUtilCore.isAncestor(resDir, file, true);
    }

    private String getResourceRelativePath(VirtualFile moduleRoot, VirtualFile file) {
        VirtualFile resDir = moduleRoot.findFileByRelativePath("src/res");
        return resDir != null ? VfsUtilCore.getRelativePath(file, resDir) : null;
    }

    private String resolveClassName(Project project, VirtualFile file) {
        return ReadAction.compute(() -> {
            if (file.getLength() > 0) {
                PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
                if (psiFile instanceof PsiJavaFile) {
                    PsiJavaFile javaFile = (PsiJavaFile) psiFile;
                    String pkg = javaFile.getPackageName();
                    String cls = file.getNameWithoutExtension();
                    return pkg.isEmpty() ? cls : pkg + "." + cls;
                }
            }
            VirtualFile parent = file.getParent();
            VirtualFile srcDir = null;
            while (parent != null) {
                if ("src".equals(parent.getName())) { srcDir = parent; break; }
                parent = parent.getParent();
            }
            if (srcDir != null) {
                String path = VfsUtilCore.getRelativePath(file, srcDir);
                if (path != null) {
                    int slash = path.indexOf('/');
                    if (slash > 0) {
                        String pkgPath = path.substring(slash + 1);
                        if (pkgPath.endsWith(".java")) pkgPath = pkgPath.substring(0, pkgPath.length() - 5);
                        return pkgPath.replace('/', '.');
                    }
                }
            }
            return null;
        });
    }

    private VirtualFile findModuleRoot(VirtualFile current) {
        VirtualFile dir = current.isDirectory() ? current : current.getParent();
        while (dir != null) {
            VirtualFile configFile = dir.findChild(CJavaConst.PROJECT_DEVELOP_CONF_FILE);
            if (configFile != null && configFile.exists()) return dir;
            dir = dir.getParent();
        }
        return null;
    }

    private String extractAttr(String xml, String attr) {
        Pattern p = Pattern.compile(attr + "=\"([^\"]*)\"");
        Matcher m = p.matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    private void showError(String msg) {
        ApplicationManager.getApplication().invokeLater(() -> Messages.showErrorDialog(msg, "Commit Error"));
    }

    private void sendNotification(Project project, String title, String content) {
        Notification notification = new Notification("Cloud Project Download", title, content, NotificationType.INFORMATION);
        Notifications.Bus.notify(notification, project);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        VirtualFile[] files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        e.getPresentation().setEnabledAndVisible(files != null && files.length > 0);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}