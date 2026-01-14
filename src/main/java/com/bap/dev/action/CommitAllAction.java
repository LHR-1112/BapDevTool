package com.bap.dev.action;

import bap.java.*;
import com.bap.dev.BapRpcClient;
import com.bap.dev.handler.ProjectRefresher;
import com.bap.dev.i18n.BapBundle;
import com.bap.dev.listener.BapChangesNotifier;
import com.bap.dev.service.BapConnectionManager;
import com.bap.dev.service.BapFileStatus;
import com.bap.dev.service.BapFileStatusService;
import com.bap.dev.settings.BapSettingsState;
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
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vcs.FileStatusManager;
import com.intellij.openapi.vfs.LocalFileSystem;
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
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.testFramework.LightVirtualFile;

public class CommitAllAction extends AnAction {

    private static final Logger LOG = Logger.getInstance(CommitAllAction.class);

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile selectedFile = e.getData(CommonDataKeys.VIRTUAL_FILE);

        if (project == null || selectedFile == null) return;

        VirtualFile moduleRoot = findModuleRoot(selectedFile);
        if (moduleRoot == null) {
            Messages.showWarningDialog(
                    BapBundle.message("warning.no_develop_config"),
                    BapBundle.message("notification.error_title")
            );
            return;
        }

        ProgressManager.getInstance().run(new Task.Backgroundable(project, BapBundle.message("action.CommitAllAction.progress.prepare"), true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    indicator.setIndeterminate(true);
                    indicator.setText(BapBundle.message("progress.refresh_module"));
                    ProjectRefresher refresher = new ProjectRefresher(project);
                    refresher.refreshModule(moduleRoot);

                    indicator.setText(BapBundle.message("progress.collect_changes"));
                    List<VirtualFile> changedFiles = collectChangedFiles(project, moduleRoot);

                    if (changedFiles.isEmpty()) {
                        showInfo(BapBundle.message("action.CommitAllAction.warning.no_changes"));
                        return;
                    }

                    // --- 🔴 新增：获取工程名称逻辑 (后台线程执行，避免卡顿) ---
                    String[] targetInfo = new String[]{"Unknown", "Unknown"}; // [0]=Uri, [1]=ProjectName
                    try {
                        File confFile = new File(moduleRoot.getPath(), CJavaConst.PROJECT_DEVELOP_CONF_FILE);
                        String content = Files.readString(confFile.toPath());
                        String uri = extractAttr(content, "Uri");
                        String user = extractAttr(content, "User");
                        String pwd = extractAttr(content, "Password");
                        String projectUuid = extractAttr(content, "Project");

                        if (uri != null) targetInfo[0] = uri;
                        if (projectUuid != null) targetInfo[1] = projectUuid; // 默认显示 UUID

                        // 尝试通过 RPC 获取工程名称
                        if (uri != null && user != null && pwd != null && projectUuid != null) {
                            indicator.setText(BapBundle.message("action.CommitAllAction.progress.fetch_project"));
                            BapRpcClient client = BapConnectionManager.getInstance(project).getSharedClient(uri, user, pwd);
                            try {
                                client.connect(uri, user, pwd);
                                CJavaProjectDto javaProject = client.getService().getProject(projectUuid);
                                if (javaProject != null) {
                                    String name = javaProject.getName();
                                    if (name != null && !name.isEmpty()) {
                                        targetInfo[1] = name; // 替换为工程名
                                    }
                                }
                            } catch (Exception ignore) {
                                // 网络错误忽略，保持显示 UUID
                            }
                        }
                    } catch (Exception ignore) {}
                    // --------------------------------------------------

                    ApplicationManager.getApplication().invokeLater(() -> {
                        // --- 🔴 修改：根据配置决定是否弹窗 ---
                        boolean needConfirm = BapSettingsState.getInstance().confirmBeforeCommit;
                        String comments = "";

                        if (needConfirm) {
                            CommitDialog dialog = new CommitDialog(project, changedFiles, targetInfo[0], targetInfo[1]);
                            if (dialog.showAndGet()) {
                                comments = dialog.getComment();
                                startBatchCommit(project, moduleRoot, changedFiles, comments);
                            }
                        } else {
                            // 直接提交，无注释
                            startBatchCommit(project, moduleRoot, changedFiles, "");
                        }
                        // ---------------------------------
                    });

                } catch (Exception ex) {
                    ex.printStackTrace();
                    showError(BapBundle.message("action.CommitAllAction.error.prepare_failed_prefix") + ex.getMessage());
                }
            }
        });
    }

    private void startBatchCommit(Project project, VirtualFile moduleRoot, List<VirtualFile> files, String comments) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, BapBundle.message("progress.committing"), true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                BapRpcClient client = null;
                try {
                    File confFile = new File(moduleRoot.getPath(), CJavaConst.PROJECT_DEVELOP_CONF_FILE);
                    String content = Files.readString(confFile.toPath());
                    String uri = extractAttr(content, "Uri");
                    String user = extractAttr(content, "User");
                    String pwd = extractAttr(content, "Password");
                    String projectUuid = extractAttr(content, "Project");

                    indicator.setText(BapBundle.message("progress.connecting"));
                    client = BapConnectionManager.getInstance(project).getSharedClient(uri, user, pwd);

                    List<CJavaFolderDto> folders = client.getService().getFolders(projectUuid);

                    CommitPackage pkg = new CommitPackage();
                    pkg.setComments(comments);
                    Map<String, List<CJavaCode>> mapFolder2Codes = new HashMap<>();
                    Map<String, Set<String>> deleteCodeMap = new HashMap<>();
                    Map<String, List<CResFileDto>> mapFolder2Files = new HashMap<>();
                    Map<String, Set<String>> deleteFileMap = new HashMap<>();

                    int count = 0;
                    for (VirtualFile file : files) {
                        if (indicator.isCanceled()) break;
                        indicator.setFraction((double) ++count / files.size());
                        indicator.setText(BapBundle.message("action.CommitAllAction.progress.processing") + file.getName() + "...");

                        if (isResourceFile(moduleRoot, file)) {
                            prepareResource(project, client, projectUuid, moduleRoot, file, folders, mapFolder2Files, deleteFileMap);
                        } else {
                            prepareJava(project, client, projectUuid, moduleRoot, file, folders, mapFolder2Codes, deleteCodeMap);
                        }
                    }

                    pkg.setMapFolder2Codes(mapFolder2Codes);
                    pkg.setDeleteCodeMap(deleteCodeMap);
                    pkg.setMapFolder2Files(mapFolder2Files);
                    pkg.setDeleteFileMap(deleteFileMap);

                    client.getService().commitCode(projectUuid, pkg);
                    // 🔴 修改：传入 moduleRoot
                    CommitAllAction.this.onSuccess(project, files, moduleRoot);

                } catch (Exception ex) {
                    ex.printStackTrace();
                    showError(BapBundle.message("action.CommitAllAction.error.commit_error") + ex.getMessage());
                }
            }
        });
    }

    // --- 资源文件准备 ---
    private void prepareResource(Project project, BapRpcClient client, String projectUuid, VirtualFile moduleRoot, VirtualFile file,
                                 List<CJavaFolderDto> folders,
                                 Map<String, List<CResFileDto>> updateMap,
                                 Map<String, Set<String>> deleteMap) throws Exception {

        BapFileStatus status = getStatusByPath(project, file);
        String relativePath = getResourceRelativePath(moduleRoot, file);
        if (relativePath == null) return;

        String folderName = "res";

        // 🔴 调试日志：确认是否进入删除逻辑
        if (status == BapFileStatus.DELETED_LOCALLY || !file.exists()) {
            Set<String> deleteSet = deleteMap.computeIfAbsent(folderName, k -> new HashSet<>());
            // 🔴 同样加上 "/" 前缀
            String pathToDelete = relativePath.startsWith("/") ? relativePath : "/" + relativePath;
            deleteSet.add(pathToDelete);
            return;
        }


        // 新增/修改逻辑
        byte[] content = file.contentsToByteArray();
        CResFileDto dto = new CResFileDto();
        dto.setFileName(file.getName());
        int lastSlash = relativePath.lastIndexOf('/');
        if (lastSlash >= 0) {
            dto.setFilePackage(relativePath.substring(0, lastSlash).replace('/', '.'));
        }
        dto.setFilePackage(lastSlash >= 0 ? relativePath.substring(0, lastSlash).replace('/', '.') : "");

        dto.setFileBin(content);
        dto.setSize((long) content.length);

        String ownerUuid = findFolderUuid(folders, folderName);
        if (ownerUuid != null) dto.setOwner(ownerUuid);

        // 查询并复用 UUID
        CResFileDto existing = client.getService().getResFile(projectUuid, relativePath, false);
        if (existing != null) {
            dto.setUuid(existing.getUuid());
        }

        updateMap.computeIfAbsent(folderName, k -> new ArrayList<>()).add(dto);
    }

    // --- Java文件准备 ---
    private void prepareJava(Project project, BapRpcClient client, String projectUuid, VirtualFile moduleRoot, VirtualFile file,
                             List<CJavaFolderDto> folders,
                             Map<String, List<CJavaCode>> updateMap,
                             Map<String, Set<String>> deleteMap) throws Exception {

        BapFileStatus status = getStatusByPath(project, file);
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

        if (status == BapFileStatus.MODIFIED) {
            Object remoteObj = client.getService().getJavaCode(projectUuid, fullClassName);
            if (remoteObj != null && remoteObj instanceof CJavaCode) {
                code.setUuid(((CJavaCode) remoteObj).getUuid());
            }
        } else if (status == BapFileStatus.ADDED) {
            code.setUuid(CmnUtil.allocUUIDWithUnderline());
        }

        updateMap.computeIfAbsent(folderName, k -> new ArrayList<>()).add(code);
    }

    private String getOwnerFolderName(VirtualFile moduleRoot, VirtualFile file) {
        VirtualFile srcDir = moduleRoot.findChild("src");
        if (srcDir == null) return null;

        String rel = getPathRelativeTo(srcDir, file);
        if (rel == null) return null;

        int idx = rel.indexOf('/');
        return (idx > 0) ? rel.substring(0, idx) : rel;
    }

    private String findFolderUuid(List<CJavaFolderDto> folders, String name) {
        return folders.stream().filter(f -> f.getName().equals(name)).map(CJavaFolderDto::getUuid).findFirst().orElse(null);
    }

    // 🔴 修改：增加 moduleRoot 参数
    protected void onSuccess(Project project, List<VirtualFile> files, VirtualFile moduleRoot) {
        ApplicationManager.getApplication().invokeLater(() -> {
            List<VirtualFile> toDeleteLocalFiles = new ArrayList<>();

            for (VirtualFile file : files) {
                BapFileStatusService ss = BapFileStatusService.getInstance(project);
                BapFileStatus status = getStatusForCommit(project, file);

                if (status == BapFileStatus.DELETED_LOCALLY) {
                    // ✅ 纯内存占位：不要 delete()，只需要把 path 的状态清掉
                    if (file.isInLocalFileSystem() && file.exists()) {
                        // 真正存在的本地文件（你现在理论上不应该有，但保留兼容）
                        toDeleteLocalFiles.add(file);
                    } else {
                        ss.setStatus(file.getPath(), BapFileStatus.NORMAL);  // ✅ 关键
                    }
                } else {
                    // 其他状态（MODIFIED/ADDED等）提交成功后同样清掉状态
                    ss.setStatus(file.getPath(), BapFileStatus.NORMAL);      // ✅ 建议统一按 path
                    if (file.isValid() && file.isInLocalFileSystem()) {
                        file.refresh(false, false);
                    }
                }
            }

            if (!toDeleteLocalFiles.isEmpty()) {
                try {
                    WriteAction.run(() -> {
                        for (VirtualFile f : toDeleteLocalFiles) {
                            BapFileStatusService.getInstance(project).setStatus(f, BapFileStatus.NORMAL);
                            if (f.isValid()) {
                                try {
                                    f.delete(this); // ✅ 这里只会对本地真实文件执行
                                } catch (java.io.IOException e) {
                                    LOG.warn("[CommitAllAction] delete local file failed: " + f.getPath(), e);
                                }
                            }
                        }
                    });
                } catch (Exception ex) {
                    LOG.warn("[CommitAllAction] WriteAction delete local files failed", ex);
                }
            }

            PsiManager.getInstance(project).dropPsiCaches();
            FileStatusManager.getInstance(project).fileStatusesChanged();

            // 🔴 新增：设置自动聚焦
            project.putUserData(BapChangesTreePanel.LAST_BAP_MODULE_ROOT, moduleRoot);

            sendNotification(
                    project,
                    BapBundle.message("action.CommitAllAction.notification.commit_success_tittle"),
                    BapBundle.message("action.CommitAllAction.notification.commit_success_prefix") +
                            files.size() +
                            BapBundle.message("action.CommitAllAction.notification.commit_success_suffix")
            );

            project.getMessageBus().syncPublisher(BapChangesNotifier.TOPIC).onChangesUpdated();
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
        ApplicationManager.getApplication().invokeLater(() -> Messages.showErrorDialog(msg, BapBundle.message("title.commit_error")));
    }

    private void showInfo(String msg) {
        ApplicationManager.getApplication().invokeLater(() -> Messages.showInfoMessage(msg, BapBundle.message("action.CommitAllAction.action.short_name")));
    }

    private void sendNotification(Project project, String title, String content) {
        Notification notification = new Notification(BapBundle.message("notification.group.bap"), title, content, NotificationType.INFORMATION);
        Notifications.Bus.notify(notification, project);
    }

    private VirtualFile findResDir(VirtualFile moduleRoot) {
        VirtualFile resDir = moduleRoot.findFileByRelativePath("res");
        if (resDir != null) return resDir;
        return moduleRoot.findFileByRelativePath("src/res");
    }

    // --- 🔴 修改 1: 改为返回 String 路径，不依赖物理目录存在 ---
    @Nullable
    private String getResDirPath(VirtualFile moduleRoot) {
        // 优先检查 src/res
        String modulePath = moduleRoot.getPath().replace('\\', '/');

        // 即使物理目录不存在，只要路径匹配，我们也能算出相对路径
        // 这里假设标准结构是 src/res
        return modulePath + "/src/res";
    }

    // --- 🔴 修改 2: 基于路径字符串判断 ---
    private boolean isResourceFile(VirtualFile moduleRoot, VirtualFile file) {
        String resPath = getResDirPath(moduleRoot);
        if (resPath == null) return false;

        String filePath = file.getPath().replace('\\', '/');
        // 检查文件是否在 res 目录下 (包含子目录)
        return filePath.startsWith(resPath + "/");
    }

    // --- 🔴 修改 3: 基于路径字符串计算相对路径 ---
    private String getResourceRelativePath(VirtualFile moduleRoot, VirtualFile file) {
        String resPath = getResDirPath(moduleRoot);
        if (resPath == null) return null;

        String filePath = file.getPath().replace('\\', '/');
        if (!filePath.startsWith(resPath)) return null;

        String relative = filePath.substring(resPath.length());
        // 去掉开头的 /
        if (relative.startsWith("/")) relative = relative.substring(1);

        return relative.isEmpty() ? null : relative;
    }

    @Nullable
    private String getPathRelativeTo(@NotNull VirtualFile baseDir, @NotNull VirtualFile file) {
        String base = baseDir.getPath().replace('\\', '/');
        String path = file.getPath().replace('\\', '/');
        if (!path.startsWith(base)) return null;

        int start = base.length();
        if (path.length() > start && path.charAt(start) == '/') start++;
        if (start >= path.length()) return "";

        return path.substring(start);
    }

    @Nullable
    private VirtualFile createDeletedVirtualFile(@NotNull String absolutePath) {
        String normalized = absolutePath.replace('\\', '/');
        int idx = normalized.lastIndexOf('/');
        String name = (idx >= 0) ? normalized.substring(idx + 1) : normalized;
        String parentPath = (idx >= 0) ? normalized.substring(0, idx) : null;

        if (parentPath == null) return null;

        VirtualFile parent = LocalFileSystem.getInstance().findFileByPath(parentPath);
        if (parent == null) return null;

        FileType fileType = FileTypeManager.getInstance().getFileTypeByFileName(name);
        return new DeletedPlaceholderFile(name, fileType, normalized, parent);
    }

    /**
     * 🔴 红D：用于让 Action 能通过 getParent()/getPath() 正常推导 src 目录、folderName、className
     */
    private static class DeletedPlaceholderFile extends LightVirtualFile {
        private final VirtualFile physicalParent;
        private final String absolutePath;

        DeletedPlaceholderFile(String name, FileType fileType, String absolutePath, VirtualFile physicalParent) {
            super(name, fileType, "");
            this.absolutePath = absolutePath;
            this.physicalParent = physicalParent;
            setWritable(false);
        }

        @Override
        public VirtualFile getParent() {
            return physicalParent;
        }

        @Override
        public String getPath() {
            return absolutePath;
        }

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        public boolean exists() {
            return false;
        }
    }


    private List<VirtualFile> collectChangedFiles(Project project, VirtualFile moduleRoot) {
        BapFileStatusService statusService = BapFileStatusService.getInstance(project);
        List<VirtualFile> result = new ArrayList<>();
        Map<String, BapFileStatus> allStatuses = statusService.getAllStatuses();

        for (Map.Entry<String, BapFileStatus> entry : allStatuses.entrySet()) {
            String path = entry.getKey();
            BapFileStatus status = entry.getValue();
            if (status == BapFileStatus.NORMAL) continue;
            if (!path.startsWith(moduleRoot.getPath())) continue;

            VirtualFile file = LocalFileSystem.getInstance().findFileByPath(path);
            if (file != null) {
                result.add(file);
                continue;
            }

            // 🔴 红D：文件本地不存在，需要用“带父级”的 LightVirtualFile 参与后续提交删除逻辑
            if (status == BapFileStatus.DELETED_LOCALLY) {
                VirtualFile deleted = createDeletedVirtualFile(path);
                if (deleted != null) result.add(deleted);
            }
        }
        return result;
    }

    private String resolveClassName(Project project, VirtualFile file) {
        return ReadAction.compute(() -> {
            // 1) 优先 PSI 解析 (保持不变)
            if (file.getLength() > 0) {
                PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
                if (psiFile instanceof PsiJavaFile) {
                    PsiJavaFile javaFile = (PsiJavaFile) psiFile;
                    String pkg = javaFile.getPackageName();
                    String cls = file.getNameWithoutExtension();
                    return pkg.isEmpty() ? cls : pkg + "." + cls;
                }
            }

            // 2) 兜底：路径计算
            // 🔴 修复：不再向上遍历找 src，而是先找模块根，再找下面的 src
            VirtualFile moduleRoot = findModuleRoot(file);
            if (moduleRoot == null) return null;

            VirtualFile srcDir = moduleRoot.findChild("src");
            if (srcDir == null) return null;

            String srcPath = srcDir.getPath().replace('\\', '/');
            String filePath = file.getPath().replace('\\', '/');

            if (!filePath.startsWith(srcPath)) return null;

            String rel = getPathRelativeTo(srcDir, file);
            if (rel == null) return null;

            // rel: "<folderName>/com/foo/Bar.java"
            int slash = rel.indexOf('/');
            if (slash <= 0) return null;

            String pkgPath = rel.substring(slash + 1);
            if (pkgPath.endsWith(".java")) pkgPath = pkgPath.substring(0, pkgPath.length() - 5);
            return pkgPath.replace('/', '.');
        });
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        e.getPresentation().setEnabledAndVisible(file != null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    /**
     * 自定义合并提交弹窗：包含文件列表预览和注释输入
     */
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
            setTitle(BapBundle.message("action.CommitAllAction.action.full_name")); // 标题略有不同
            setOKButtonText(BapBundle.message("action.CommitAllAction.action.short_name"));
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel dialogPanel = new JPanel(new BorderLayout(0, 10));
            dialogPanel.setPreferredSize(new Dimension(600, 500));

            // 0. 顶部：服务器和工程信息
            JPanel infoPanel = new JPanel(new GridLayout(2, 1, 0, 5));
            infoPanel.setBorder(BorderFactory.createTitledBorder(BapBundle.message("label.target_env")));

            JLabel uriLabel = new JLabel(BapBundle.message("label.server") + targetUri);
            JLabel projLabel = new JLabel(BapBundle.message("label.project") + targetProject);

            infoPanel.add(uriLabel);
            infoPanel.add(projLabel);

            // 1. 上半部分：文件列表预览
            String fileListText = buildFileListText();
            JTextArea fileListArea = new JTextArea(fileListText);
            fileListArea.setEditable(false);
            fileListArea.setBackground(null); // 使用默认背景
            // 简单美化字体
            fileListArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

            JLabel fileLabel = new JLabel(
                    BapBundle.message("action.CommitAllAction.summary.changes_prefix") +
                            files.size() +
                            BapBundle.message("action.CommitAllAction.summary.changes_suffix")
            );
            JPanel filePanel = new JPanel(new BorderLayout(0, 5));
            filePanel.add(fileLabel, BorderLayout.NORTH);
            filePanel.add(new JBScrollPane(fileListArea), BorderLayout.CENTER);

            // 2. 下半部分：注释输入
            JLabel commentLabel = new JLabel(BapBundle.message("label.commit_message"));
            commentArea = new JBTextArea(4, 50);
            commentArea.setLineWrap(true);
            commentArea.setWrapStyleWord(true);

            JPanel commentPanel = new JPanel(new BorderLayout(0, 5));
            commentPanel.add(commentLabel, BorderLayout.NORTH);
            commentPanel.add(new JBScrollPane(commentArea), BorderLayout.CENTER);

            // 布局组装
            dialogPanel.add(infoPanel, BorderLayout.NORTH);
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
                BapFileStatus status = BapFileStatusService.getInstance(project).getStatus(f);
                String symbol = "[?]";
                if (status == BapFileStatus.MODIFIED) symbol = "[M]";
                if (status == BapFileStatus.ADDED)    symbol = "[A]";
                if (status == BapFileStatus.DELETED_LOCALLY) symbol = "[D]";
                sb.append(symbol).append(" ").append(f.getName()).append("\n");
            }
            return sb.toString();
        }
    }

    private BapFileStatus getStatusByPath(Project project, VirtualFile file) {
        BapFileStatusService ss = BapFileStatusService.getInstance(project);

        // 先走原来的
        BapFileStatus st = ss.getStatus(file);

        // 对 placeholder / LightVirtualFile / 被删文件，兜底走 path map
        if (st == null || st == BapFileStatus.NORMAL) {
            Map<String, BapFileStatus> all = ss.getAllStatuses();
            if (all != null) {
                BapFileStatus st2 = all.get(file.getPath());
                if (st2 != null) st = st2;
            }
        }
        return st == null ? BapFileStatus.NORMAL : st;
    }

    private BapFileStatus getStatusForCommit(Project project, VirtualFile file) {
        BapFileStatusService ss = BapFileStatusService.getInstance(project);

        // 1) 先尝试原有方式
        BapFileStatus st = ss.getStatus(file);

        // 2) 对 LightVirtualFile / deleted placeholder，兜底走 path->status
        if (st == null || st == BapFileStatus.NORMAL) {
            Map<String, BapFileStatus> all = ss.getAllStatuses();
            if (all != null) {
                BapFileStatus st2 = all.get(file.getPath());
                if (st2 != null) st = st2;
            }
        }
        return st == null ? BapFileStatus.NORMAL : st;
    }
}