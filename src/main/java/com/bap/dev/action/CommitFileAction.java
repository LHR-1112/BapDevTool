package com.bap.dev.action;

import bap.java.*;
import com.bap.dev.BapRpcClient;
import com.bap.dev.i18n.BapBundle;
import com.bap.dev.listener.BapChangesNotifier;
import com.bap.dev.service.BapConnectionManager;
import com.bap.dev.service.BapFileStatus;
import com.bap.dev.service.BapFileStatusService;
import com.bap.dev.ui.BapChangesTreePanel;
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
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vcs.FileStatusManager;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.kwaidoo.ms.tool.CmnUtil;
import cplugin.ms.dto.CResFileDto;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CommitFileAction extends AnAction {

    private static final Logger LOG = Logger.getInstance(CommitFileAction.class);

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile[] selectedFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);

        if (project == null || selectedFiles == null || selectedFiles.length == 0) return;

        FileDocumentManager.getInstance().saveAllDocuments();

        VirtualFile moduleRoot = findModuleRoot(selectedFiles[0]);
        if (moduleRoot == null) {
            Messages.showWarningDialog(
                    BapBundle.message("warning.no_develop_config"),
                    BapBundle.message("notification.error_title")
            );
            return;
        }

        // --- 🔴 新增：预先读取配置信息 ---
        String targetUri = "Unknown";
        String targetProject = "Unknown";
        try {
            File confFile = new File(moduleRoot.getPath(), CJavaConst.PROJECT_DEVELOP_CONF_FILE);
            if (confFile.exists()) {
                String content = Files.readString(confFile.toPath());
                String uri = extractAttr(content, "Uri");
                String projectUuid = extractAttr(content, "Project");
                String user = extractAttr(content, "User");
                String pwd = extractAttr(content, "Password");

                if (uri != null) targetUri = uri;

                BapRpcClient client = BapConnectionManager.getInstance(project).getSharedClient(uri, user, pwd);
                CJavaProjectDto javaProject = client.getService().getProject(projectUuid);
                if (javaProject != null) {
                    String name = javaProject.getName();
                    if (name != null) targetProject = name;
                }
            }
        } catch (Exception ignore) {}
        // -----------------------------

        // --- 修改开始：使用自定义合并弹窗 ---
        CommitDialog dialog = new CommitDialog(project, Arrays.asList(selectedFiles), targetUri, targetProject);
        if (dialog.showAndGet()) {
            String comments = dialog.getComment();

            ProgressManager.getInstance().run(new Task.Backgroundable(project, BapBundle.message("progress.committing"), true) {
                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    indicator.setIndeterminate(true);
                    try {
                        commitWithPackage(project, moduleRoot, selectedFiles, comments);
                    } catch (Exception ex) {
                        ex.printStackTrace();
                        showError(BapBundle.message("action.CommitFileAction.error.failed", ex.getMessage()));
                    }
                }
            });
        }
        // --- 修改结束 ---
    }

    private void commitWithPackage(Project project, VirtualFile moduleRoot, VirtualFile[] files, String comments) throws Exception {

        // --- 🔴 修改开始：使用 BapConnectionManager 获取连接 ---
        // 1. 手动读取配置获取连接信息
        File confFile = new File(moduleRoot.getPath(), CJavaConst.PROJECT_DEVELOP_CONF_FILE);
        String content = Files.readString(confFile.toPath());
        String uri = extractAttr(content, "Uri");
        String user = extractAttr(content, "User");
        String pwd = extractAttr(content, "Password");

        // 2. 获取共享的长连接客户端
        BapRpcClient client = BapConnectionManager.getInstance(project).getSharedClient(uri, user, pwd);
        // --- 🔴 修改结束 ---

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

            // 🔴 修改：传入 moduleRoot
            onSuccess(project, files, moduleRoot);

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
        String relativePath = getResourceRelativePath(moduleRoot, file);
        if (relativePath == null) return;

        String folderName = "res";

        if (status == BapFileStatus.DELETED_LOCALLY || !file.exists() || !file.isInLocalFileSystem()) {
            Set<String> deleteSet = deleteMap.computeIfAbsent(folderName, k -> new HashSet<>());

            // 🔴 修正：根据您的指示，所有 value 前加上 "/"
            String pathToDelete = relativePath.startsWith("/") ? relativePath : "/" + relativePath;

            deleteSet.add(pathToDelete);

            return;
        }

        // 2) 兜底：placeholder / 本地不存在的 file，一律不要走“上传空文件”，而是按删除处理
        // （尤其是 LightVirtualFile / exists()==false 的情况）
        if (!file.exists() || !file.isInLocalFileSystem()) {
            deleteMap.computeIfAbsent(folderName, k -> new HashSet<>()).add(relativePath);
            return;
        }

        // 2. 新增/修改逻辑
        byte[] content = file.contentsToByteArray();
        CResFileDto dto = new CResFileDto();
        dto.setFilePackage(relativePath);
        dto.setFileName(file.getName());

        int lastSlash = relativePath.lastIndexOf('/');
        if (lastSlash >= 0) {
            dto.setFilePackage(relativePath.substring(0, lastSlash).replace('/', '.'));
        } else {
            dto.setFilePackage(""); // 或者不 set（但建议显式置空，避免后端把 null 当成别的含义）
        }

        dto.setFileBin(content);
        dto.setSize((long) content.length);

        String ownerUuid = findFolderUuid(folders, folderName);
        if (ownerUuid != null) dto.setOwner(ownerUuid);

        // 关键：设置 UUID 以触发 Update
        CResFileDto existing = client.getService().getResFile(projectUuid, relativePath, false);
        if (existing != null) {
            dto.setUuid(existing.getUuid());
        }

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

    // 🔴 修改：增加 moduleRoot 参数 (注意：原参数类型是 VirtualFile[]，这里统一一下，或者转为 List)
    private void onSuccess(Project project, VirtualFile[] files, VirtualFile moduleRoot) {
        ApplicationManager.getApplication().invokeLater(() -> {
            List<VirtualFile> toDelete = new ArrayList<>();
            BapFileStatusService statusService = BapFileStatusService.getInstance(project);

            for (VirtualFile file : files) {
                // 1. 先获取当前状态
                BapFileStatus status = BapFileStatusService.getInstance(project).getStatus(file);

                if (status == BapFileStatus.DELETED_LOCALLY) {
                    // 对于红D文件，直接清除状态即可，不需要物理删除
                    // 🔴 关键：使用 getPath() 确保清除的是 Map 中的 Key
                    statusService.setStatus(file.getPath(), BapFileStatus.NORMAL);
                } else if (file.isValid()) {
                    // 对于存在的物理文件，先设为 Normal
                    statusService.setStatus(file, BapFileStatus.NORMAL);
                    file.refresh(false, false);
                }
            }

            // 4. 统一处理物理删除
            if (!toDelete.isEmpty()) {
                try {
                    WriteAction.run(() -> {
                        for(VirtualFile f : toDelete) {
                            // 1. 清除状态
                            BapFileStatusService.getInstance(project).setStatus(f, BapFileStatus.NORMAL);

                            // 2. 🔴 关键修复：只有当文件在本地文件系统时才执行物理删除
                            // LightVirtualFile (我们创建的红D文件) 不在本地文件系统，且本来就是“不存在”的，所以不需要删
                            if (f.isValid() && f.isInLocalFileSystem()) {
                                try {
                                    f.delete(this);
                                } catch (java.io.IOException e) {
                                    e.printStackTrace();
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

            // 🔴 新增：设置自动聚焦
            project.putUserData(BapChangesTreePanel.LAST_BAP_MODULE_ROOT, moduleRoot);

            sendNotification(project, BapBundle.message("action.CommitFileAction.notification.success_title"), BapBundle.message("action.CommitFileAction.notification.success_message", files.length));
            project.getMessageBus().syncPublisher(BapChangesNotifier.TOPIC).onChangesUpdated();
        });
    }

    private String getOwnerFolderName(VirtualFile moduleRoot, VirtualFile file) {
        VirtualFile srcDir = moduleRoot.findChild("src");
        if (srcDir == null) return null;

        // 兼容 BapDeletedVirtualFile (LightFileSystem) vs LocalFileSystem
        String srcPath = srcDir.getPath().replace('\\', '/');
        String filePath = file.getPath().replace('\\', '/');

        if (!filePath.startsWith(srcPath)) return null;

        String relative = filePath.substring(srcPath.length());
        if (relative.startsWith("/")) relative = relative.substring(1);
        if (relative.isEmpty()) return null;

        int idx = relative.indexOf('/');
        return (idx > 0) ? relative.substring(0, idx) : relative;
    }

    private String findFolderUuid(List<CJavaFolderDto> folders, String name) {
        return folders.stream().filter(f -> f.getName().equals(name)).map(CJavaFolderDto::getUuid).findFirst().orElse(null);
    }

    private String getProjectUuid(VirtualFile moduleRoot) throws Exception {
        File confFile = new File(moduleRoot.getPath(), CJavaConst.PROJECT_DEVELOP_CONF_FILE);
        String content = Files.readString(confFile.toPath());
        return extractAttr(content, "Project");
    }

    private VirtualFile findResDir(VirtualFile moduleRoot) {
        VirtualFile resDir = moduleRoot.findFileByRelativePath("res");
        if (resDir != null) return resDir;
        return moduleRoot.findFileByRelativePath("src/res");
    }

    // --- 🔴 修改：使用 String 路径计算 (不依赖物理文件夹存在) ---
    private String getResDirPath(VirtualFile moduleRoot) {
        return moduleRoot.getPath().replace('\\', '/') + "/src/res";
    }

    // --- 🔴 修改：基于路径字符串判断 ---
    private boolean isResourceFile(VirtualFile moduleRoot, VirtualFile file) {
        String resPath = getResDirPath(moduleRoot);
        String filePath = file.getPath().replace('\\', '/');
        // 兼容: 直接是 src/res 本身，或是其子文件
        return filePath.equals(resPath) || filePath.startsWith(resPath + "/");
    }

    // --- 🔴 修改：基于路径字符串计算相对路径 ---
    private String getResourceRelativePath(VirtualFile moduleRoot, VirtualFile file) {
        String resPath = getResDirPath(moduleRoot);
        String filePath = file.getPath().replace('\\', '/');

        if (!filePath.startsWith(resPath)) return null;

        String relative = filePath.substring(resPath.length());
        if (relative.startsWith("/")) relative = relative.substring(1);

        return relative.isEmpty() ? null : relative;
    }


    private String resolveClassName(Project project, VirtualFile file) {
        return ReadAction.compute(() -> {
            // 1) 有内容时优先走 PSI（最准确）
            if (file.getLength() > 0) {
                PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
                if (psiFile instanceof PsiJavaFile) {
                    PsiJavaFile javaFile = (PsiJavaFile) psiFile;
                    String pkg = javaFile.getPackageName();
                    String cls = file.getNameWithoutExtension();
                    return pkg.isEmpty() ? cls : pkg + "." + cls;
                }
            }

            // 2) 红D / 无 PSI 时：用“路径字符串”计算，避免 LightFileSystem vs LocalFileSystem 导致的 relativePath=null
            VirtualFile srcDir = null;

            // 2.1 先尝试从 parent 链找到 src
            VirtualFile parent = file.getParent();
            while (parent != null) {
                if ("src".equals(parent.getName())) { srcDir = parent; break; }
                parent = parent.getParent();
            }

            // 2.2 如果 parent 链不可靠（例如 parent 被兜底成 moduleRoot），退化为从模块根目录找 src
            if (srcDir == null) {
                VirtualFile moduleRoot = findModuleRoot(file);
                if (moduleRoot != null) {
                    srcDir = moduleRoot.findChild("src");
                }
            }

            if (srcDir == null) return null;

            String srcPath = srcDir.getPath().replace('\\', '/');
            String filePath = file.getPath().replace('\\', '/');

            if (!filePath.startsWith(srcPath)) return null;

            String relative = filePath.substring(srcPath.length());
            if (relative.startsWith("/")) relative = relative.substring(1);
            if (relative.isEmpty()) return null;

            // 3) 关键：去掉 src 下的第一段目录（例如 src/java/... -> 去掉 "java"）
            int slash = relative.indexOf('/');
            if (slash > 0) {
                relative = relative.substring(slash + 1);
            }

            if (relative.toLowerCase().endsWith(".java")) {
                relative = relative.substring(0, relative.length() - 5);
            }

            return relative.replace('/', '.').replace('\\', '.');
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
        ApplicationManager.getApplication().invokeLater(() -> Messages.showErrorDialog(msg, BapBundle.message("notification.error_title")));
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

    // --- 新增：CommitDialog 内部类 ---
    private static class CommitDialog extends DialogWrapper {
        private final List<VirtualFile> files;
        private final Project project;
        private final String targetUri;
        private final String targetProject;
        private JBTextArea commentArea;

        protected CommitDialog(Project project, List<VirtualFile> files, String targetUri, String targetProject) {
            super(project);
            this.project = project;
            this.files = files;
            this.targetUri = targetUri;
            this.targetProject = targetProject;
            setTitle(BapBundle.message("action.CommitFileAction.dialog.title"));
            setOKButtonText(BapBundle.message("button.commit"));
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel dialogPanel = new JPanel(new BorderLayout(0, 10));
            dialogPanel.setPreferredSize(new Dimension(600, 500));

            // 0. 顶部：服务器和工程信息 (新增)
            JPanel infoPanel = new JPanel(new GridLayout(2, 1, 0, 5));
            infoPanel.setBorder(BorderFactory.createTitledBorder(BapBundle.message("label.target_env")));

            JLabel uriLabel = new JLabel(BapBundle.message("action.CommitFileAction.info.server", targetUri));
            JLabel projLabel = new JLabel(BapBundle.message("action.CommitFileAction.info.project", targetProject));

            infoPanel.add(uriLabel);
            infoPanel.add(projLabel);

            // 1. 中部：文件列表
            String fileListText = buildFileListText();
            JTextArea fileListArea = new JTextArea(fileListText);
            fileListArea.setEditable(false);
            fileListArea.setBackground(null);
            fileListArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

            JLabel fileLabel = new JLabel(BapBundle.message("label.files_to_commit", files.size()));
            JPanel filePanel = new JPanel(new BorderLayout(0, 5));
            filePanel.add(fileLabel, BorderLayout.NORTH);
            filePanel.add(new JBScrollPane(fileListArea), BorderLayout.CENTER);

            // 2. 底部：注释输入
            JLabel commentLabel = new JLabel(BapBundle.message("label.commit_message"));
            commentArea = new JBTextArea(4, 50);
            commentArea.setLineWrap(true);
            commentArea.setWrapStyleWord(true);

            JPanel commentPanel = new JPanel(new BorderLayout(0, 5));
            commentPanel.add(commentLabel, BorderLayout.NORTH);
            commentPanel.add(new JBScrollPane(commentArea), BorderLayout.CENTER);

            // 布局组装
            dialogPanel.add(infoPanel, BorderLayout.NORTH); // 加到顶部
            dialogPanel.add(filePanel, BorderLayout.CENTER);
            dialogPanel.add(commentPanel, BorderLayout.SOUTH);

            return dialogPanel;
        }

        // --- 🔴 修改部分开始 ---
        @Override
        public @Nullable JComponent getPreferredFocusedComponent() {
            // 原代码：return commentArea;
            // 修改后：获取 OK (Commit) 按钮并设为默认焦点
            return getButton(getOKAction());
        }
        // --- 🔴 修改部分结束 ---

        public String getComment() {
            return commentArea.getText().trim();
        }

        private String buildFileListText() {
            StringBuilder sb = new StringBuilder();
            for (VirtualFile f : files) {
                // 读取文件状态并显示标记
                BapFileStatus status = BapFileStatusService.getInstance(project).getStatus(f);
                String symbol = "[?]";
                if (status == BapFileStatus.MODIFIED) symbol = "[M]";
                else if (status == BapFileStatus.ADDED)    symbol = "[A]";
                else if (status == BapFileStatus.DELETED_LOCALLY) symbol = "[D]";
                else if (status == BapFileStatus.NORMAL) symbol = "[N]";

                sb.append(symbol).append(" ").append(f.getName()).append("\n");
            }
            return sb.toString();
        }
    }
}
