package com.bap.dev.action;

import bap.java.CJavaCode;
import bap.java.CJavaConst;
import bap.java.CJavaFolderDto;
import bap.md.java.CJavaProject;
import bap.md.java.CResFileDo;
import com.bap.dev.BapRpcClient;
import com.bap.dev.listener.BapChangesNotifier;
import com.bap.dev.service.BapFileStatus;
import com.bap.dev.service.BapFileStatusService;
import com.cdao.impl.entity.field.GID;
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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CommitJavaCodeAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();

        // --- 🔴 核心修改：获取多选文件数组 ---
        VirtualFile[] selectedFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);

        if (project == null || selectedFiles == null || selectedFiles.length == 0) return;

        FileDocumentManager.getInstance().saveAllDocuments();

        // 启动后台任务 (改为批量处理)
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Committing Files...", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(false);
                int total = selectedFiles.length;

                for (int i = 0; i < total; i++) {
                    VirtualFile file = selectedFiles[i];
                    if (indicator.isCanceled()) break;

                    indicator.setFraction((double) (i + 1) / total);
                    indicator.setText("Committing " + file.getName() + " (" + (i + 1) + "/" + total + ")...");

                    if (file.isDirectory()) continue; // 跳过文件夹

                    VirtualFile moduleRoot = findModuleRoot(file);
                    if (moduleRoot == null) continue; // 找不到配置就跳过

                    try {
                        if (isResourceFile(moduleRoot, file)) {
                            commitResourceFile(project, moduleRoot, file);
                        } else {
                            commitJavaFile(project, moduleRoot, file);
                        }
                    } catch (Exception ex) {
                        ex.printStackTrace();
                        // 可以选择收集错误最后统一报，或者直接弹窗（不推荐在循环中弹窗）
                        System.err.println("Failed to commit " + file.getName() + ": " + ex.getMessage());
                    }
                }

                // 刷新 UI
                ApplicationManager.getApplication().invokeLater(() -> {
                    project.getMessageBus().syncPublisher(BapChangesNotifier.TOPIC).onChangesUpdated();
                });
            }
        });
    }

    // --- 资源文件提交逻辑 (修复版) ---
// --- 资源文件提交逻辑 (修复版) ---
    private void commitResourceFile(Project project, VirtualFile moduleRoot, VirtualFile file) throws Exception {
        String relativePath = getResourceRelativePath(moduleRoot, file);
        if (relativePath == null) throw new Exception("无法计算资源路径");

        // 获取文件状态
        BapFileStatus status = BapFileStatusService.getInstance(project).getStatus(file);

        BapRpcClient client = prepareClient(moduleRoot);
        String projectUuid = getProjectUuid(moduleRoot);

        try {
            // === 🔴 修复点 1: 红 D (Deleted) 处理 ===
            if (status == BapFileStatus.DELETED_LOCALLY) {
                // 1. 查询 ID
                CResFileDto existingDto = client.getService().getResFile(projectUuid, relativePath, false);
                if (existingDto != null) {
                    // 2. 删除云端
                    client.getService().deleteResFile(new GID("bap.md.java.CResFileDo", existingDto.getUuid()));
                }
                // 3. 删除本地占位符并刷新状态
                deleteLocalPlaceholder(project, file);
                return; // 🚨 必须 return，不再执行上传逻辑
            }
            // --------------------------------------------


            // 1. 获取 res 文件夹
            List<CJavaFolderDto> folders = client.getService().getFolders(projectUuid);
            CJavaFolderDto resFolder = folders.stream()
                    .filter(item -> "res".equals(item.getName()))
                    .findFirst()
                    .orElse(null);
            if (resFolder == null) throw new Exception("云端 res 文件夹不存在");

            // 2. 先删旧的
            CResFileDto existingDto = client.getService().getResFile(projectUuid, relativePath, false);
            if (existingDto != null) {
                client.getService().deleteResFile(new GID("bap.md.java.CResFileDo", existingDto.getUuid()));
            }

            // 3. 上传新的
            byte[] content = file.contentsToByteArray();
            CResFileDto uploadDto = new CResFileDto();
            String fileName = file.getName();
            String filePackage = "";
            int lastSlash = relativePath.lastIndexOf('/');
            if (lastSlash >= 0) {
                filePackage = relativePath.substring(0, lastSlash).replace('/', '.');
            }

            uploadDto.setFileName(fileName);
            uploadDto.setFilePackage(filePackage);
            uploadDto.setOwner(resFolder.getUuid());
            uploadDto.setFileBin(content);
            uploadDto.setSize((long) content.length);

            client.getService().importResFile(new GID("bap.md.java.CJavaProject", projectUuid), uploadDto);

            onSuccess(project, file);

        } finally {
            client.shutdown();
        }
    }

    // --- Java 文件处理 (保持不变) ---
    private void commitJavaFile(Project project, VirtualFile moduleRoot, VirtualFile file) throws Exception {
        String fullClassName = resolveClassName(project, file);
        if (fullClassName == null) throw new Exception("无法解析类名");

        BapFileStatus status = BapFileStatusService.getInstance(project).getStatus(file);
        BapRpcClient client = prepareClient(moduleRoot);
        String projectUuid = getProjectUuid(moduleRoot);

        try {

            // === 🔴 修复点 2: 红 D (Deleted) 处理 ===
            if (status == BapFileStatus.DELETED_LOCALLY) {
                // 1. 删除云端
                client.getService().deleteCode(projectUuid, fullClassName, true);
                // 2. 删除本地占位符
                deleteLocalPlaceholder(project, file);
                return; // 🚨 必须 return
            }

            Object remoteObj = client.getService().getJavaCode(projectUuid, fullClassName);
            CJavaCode cJavaCode;

            if (remoteObj != null) {
                // 修改 (M)
                if (remoteObj instanceof CJavaCode) {
                    cJavaCode = (CJavaCode) remoteObj;
                } else {
                    cJavaCode = new CJavaCode();
                    try {
                        java.lang.reflect.Field fUuid = remoteObj.getClass().getField("uuid");
                        cJavaCode.setUuid((String) fUuid.get(remoteObj));
                    } catch (Exception ignore) {}
                }
            } else {
                // 新增 (A)
                cJavaCode = new CJavaCode();

                // --- 🔴 核心修复：设置 Project UUID ---
                // 假设 CJavaCode 的字段是 public 的，如果不是请用 setProjectUuid(projectUuid)
                cJavaCode.setProjectUuid(projectUuid);
                cJavaCode.setUuid(CmnUtil.allocUUIDWithUnderline());
                // -------------------------------------

                cJavaCode.setMainClass(file.getNameWithoutExtension());
                int lastDot = fullClassName.lastIndexOf('.');
                cJavaCode.setJavaPackage((lastDot > 0) ? fullClassName.substring(0, lastDot) : "");

                // --- 🔴 修复：计算并设置 Owner ---
                String ownerUuid = getOwnerFolderUuid(client, projectUuid, moduleRoot, file);
                if (ownerUuid == null) {
                    throw new Exception("无法确定代码所属的源码目录(Owner)，请检查 src 下的目录结构");
                }
                cJavaCode.setOwner(ownerUuid);
                // ------------------------------
            }

            String content = new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
            cJavaCode.setCode(content);

            client.getService().saveJavaCode(cJavaCode, true);

            onSuccess(project, file);

        } finally {
            client.shutdown();
        }
    }

    // --- 辅助方法：删除本地占位符并刷新状态 ---
    private void deleteLocalPlaceholder(Project project, VirtualFile file) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                WriteAction.run(() -> {
                    // 先改状态为 Normal，防止删除后某些监听器报错
                    BapFileStatusService.getInstance(project).setStatus(file, BapFileStatus.NORMAL);
                    file.delete(this);
                });
                sendNotification(project, "删除成功", "文件 " + file.getName() + " 已从云端删除。");
            } catch (Exception ignore) {}
        });
    }

    // --- 新增辅助方法：获取文件所属的第一级文件夹 UUID ---
    private String getOwnerFolderUuid(BapRpcClient client, String projectUuid, VirtualFile moduleRoot, VirtualFile file) throws Exception {
        VirtualFile srcDir = moduleRoot.findChild("src");
        if (srcDir == null) return null;

        // 获取相对 src 的路径，例如 "core/com/pkg/A.java"
        String path = VfsUtilCore.getRelativePath(file, srcDir);
        if (path == null) return null;

        int idx = path.indexOf('/');
        if (idx <= 0) return null; // 文件直接在 src 下？这种情况可能不被支持

        // 提取第一级目录名，例如 "core"
        String folderName = path.substring(0, idx);

        // 从云端获取文件夹列表并匹配
        List<CJavaFolderDto> folders = client.getService().getFolders(projectUuid);
        return folders.stream()
                .filter(f -> f.getName().equals(folderName))
                .map(CJavaFolderDto::getUuid)
                .findFirst()
                .orElse(null);
    }

    // ... (onSuccess, prepareClient, getProjectUuid, isResourceFile, getResourceRelativePath, resolveClassName, findModuleRoot, extractAttr, showError, sendNotification, update, getActionUpdateThread 等方法完全保持不变，请直接复用原文件中的代码) ...

    // 为了完整性，这里补充 onSuccess 方法
    private void onSuccess(Project project, VirtualFile file) {
        ApplicationManager.getApplication().invokeLater(() -> {
            BapFileStatusService.getInstance(project).setStatus(file, BapFileStatus.NORMAL);
            file.refresh(false, false);
            PsiManager.getInstance(project).dropPsiCaches();
            FileStatusManager.getInstance(project).fileStatusesChanged();

            sendNotification(project, "提交成功", "文件 " + file.getName() + " 已同步至云端。");
            project.getMessageBus().syncPublisher(BapChangesNotifier.TOPIC).onChangesUpdated();
        });
    }

    // 下面的辅助方法请确保在你的文件中存在 (与之前版本一致)
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
        if (resDir == null) return null;
        // 返回的路径不以 / 开头，例如 "pt/index.html"
        return VfsUtilCore.getRelativePath(file, resDir);
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
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        e.getPresentation().setEnabledAndVisible(file != null && !file.isDirectory());
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}