package com.bap.dev.action;

import bap.java.CJavaCode;
import bap.java.CJavaConst;
import com.bap.dev.BapRpcClient;
import com.bap.dev.handler.ProjectRefresher;
import com.bap.dev.i18n.BapBundle;
import com.bap.dev.listener.BapChangesNotifier;
import com.bap.dev.service.BapConnectionManager;
import com.bap.dev.service.BapFileStatus;
import com.bap.dev.service.BapFileStatusService;
import com.bap.dev.settings.BapSettingsState;
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
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import cplugin.ms.dto.CResFileDto;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.testFramework.LightVirtualFile;
import org.jetbrains.annotations.Nullable;

public class UpdateAllAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile selectedFile = e.getData(CommonDataKeys.VIRTUAL_FILE);

        if (project == null || selectedFile == null) return;

        VirtualFile moduleRoot = findModuleRoot(selectedFile);
        if (moduleRoot == null) {
            Messages.showWarningDialog(
                    BapBundle.message("warning.no_develop_config"), // "未找到 .develop 配置文件。"
                    BapBundle.message("notification.error_title")                   // "错误"
            );
            return;
        }

        ProgressManager.getInstance().run(new Task.Backgroundable(project, BapBundle.message("action.UpdateAllAction.progress.preparing"), true) { // "Preparing Update..."
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    indicator.setIndeterminate(true);
                    indicator.setText(BapBundle.message("progress.refresh_module")); // "Refreshing module status..."
                    ProjectRefresher refresher = new ProjectRefresher(project);
                    refresher.refreshModule(moduleRoot);

                    indicator.setText(BapBundle.message("progress.collect_changes")); // "Collecting changes..."
                    List<VirtualFile> changedFiles = collectChangedFiles(project, moduleRoot);

                    if (changedFiles.isEmpty()) {
                        showInfo(BapBundle.message("action.UpdateAllAction.info.no_changes")); // "没有检测到需要更新的文件 (M/A/D)。"
                        return;
                    }

                    ApplicationManager.getApplication().invokeLater(() -> {
                        // 🔴 修改：根据配置决定是否弹窗 (与其他更新/提交入口保持一致的二次确认)
                        boolean needConfirm = BapSettingsState.getInstance().confirmBeforeUpdate;
                        if (!needConfirm || showConfirmDialog(project, changedFiles)) {
                            startBatchUpdate(project, moduleRoot, changedFiles);
                        }
                    });

                } catch (Exception ex) {
                    ex.printStackTrace();
                    showError(BapBundle.message("action.UpdateAllAction.error.prepare_failed", ex.getMessage())); // "准备更新失败: " + ex.getMessage()
                }
            }
        });
    }

    private void startBatchUpdate(Project project, VirtualFile moduleRoot, List<VirtualFile> files) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, BapBundle.message("action.UpdateAllAction.progress.updating_title"), true) { // "Updating Files..."
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                // --- 🔴 修改：client 初始化为 null ---
                BapRpcClient client = null;
                int successCount = 0;
                int failCount = 0;

                try {
                    File confFile = new File(moduleRoot.getPath(), CJavaConst.PROJECT_DEVELOP_CONF_FILE);
                    String content = Files.readString(confFile.toPath());
                    String uri = extractAttr(content, "Uri");
                    String user = extractAttr(content, "User");
                    String pwd = extractAttr(content, "Password");
                    String projectUuid = extractAttr(content, "Project");

                    indicator.setText(BapBundle.message("progress.connecting")); // "Connecting..."
                    client = BapConnectionManager.getInstance(project).getSharedClient(uri, user, pwd);

                    int count = 0;
                    for (VirtualFile file : files) {
                        if (indicator.isCanceled()) break;
                        indicator.setFraction((double) ++count / files.size());
                        indicator.setText(BapBundle.message("action.UpdateAllAction.progress.updating_file", file.getName())); // "Updating " + file.getName() + "..."

                        try {
                            boolean result;
                            if (isResourceFile(moduleRoot, file)) {
                                result = updateSingleResource(project, client, projectUuid, moduleRoot, file);
                            } else {
                                result = updateSingleJavaFile(project, client, projectUuid, file);
                            }

                            if (result) successCount++;
                            else failCount++;

                        } catch (Exception ex) {
                            ex.printStackTrace();
                            failCount++;
                            System.err.println("Failed to update " + file.getName() + ": " + ex.getMessage());
                        }
                    }

                    String msg = BapBundle.message("action.UpdateAllAction.notification.result_msg", successCount, failCount); // String.format("更新完成。\n成功: %d\n失败/跳过: %d", ...)
                    NotificationType type = failCount > 0 ? NotificationType.WARNING : NotificationType.INFORMATION;
                    sendNotification(project,
                            BapBundle.message("action.UpdateAllAction.notification.title"), // "Update All Result"
                            msg, type);

                } catch (Exception ex) {
                    showError(BapBundle.message("action.UpdateAllAction.error.batch_interrupt", ex.getMessage())); // "批量更新中断: " + ex.getMessage()
                } finally {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        new ProjectRefresher(project).refreshModule(moduleRoot);
                        project.getMessageBus().syncPublisher(BapChangesNotifier.TOPIC).onChangesUpdated();
                    });
                }
            }
        });
    }

    // --- 资源文件更新逻辑 (修复版) ---
    private boolean updateSingleResource(Project project, BapRpcClient client, String projectUuid, VirtualFile moduleRoot, VirtualFile file) throws Exception {
        String relativePath = getResourceRelativePath(moduleRoot, file);
        if (relativePath == null) return false;

        // 🔴 修复：确保查询路径以 "/" 开头
        String queryPath = relativePath.startsWith("/") ? relativePath : "/" + relativePath;

        CResFileDto resDto = client.getService().getResFile(projectUuid, queryPath, false);

        if (resDto != null) {
            byte[] content = resDto.getFileBin();
            if (content == null) content = new byte[0];
            overwriteFile(project, file, content);
            return true;
        } else {
            deleteLocalFile(project, file);
            return true;
        }
    }

    // --- Java 文件更新逻辑 (保持一致) ---
    private boolean updateSingleJavaFile(Project project, BapRpcClient client, String projectUuid, VirtualFile file) throws Exception {
        String fullClassName = resolveClassName(project, file);
        if (fullClassName == null) return false;

        Object remoteObj = client.getService().getJavaCode(projectUuid, fullClassName);

        if (remoteObj != null) {
            String codeContent = null;
            if (remoteObj instanceof CJavaCode) {
                codeContent = ((CJavaCode) remoteObj).code;
            } else {
                try {
                    java.lang.reflect.Field f = remoteObj.getClass().getField("code");
                    codeContent = (String) f.get(remoteObj);
                } catch (Exception ignore) {}
            }

            if (codeContent == null) codeContent = "";

            // Case: 黄M 或 红D -> 覆盖
            overwriteFile(project, file, codeContent.getBytes(StandardCharsets.UTF_8));
            return true;
        } else {
            // Case: 蓝A -> 删除
            deleteLocalFile(project, file);
            return true;
        }
    }

    // --- 文件操作 ---

    private void overwriteFile(Project project, VirtualFile file, byte[] content) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                String absPath = file.getPath();
                File ioFile = new File(absPath);

                // LightVirtualFile(红D占位符) 必须落盘生成真实文件；否则刷新后仍然是红D
                if (!file.isInLocalFileSystem() || !ioFile.exists()) {
                    File parent = ioFile.getParentFile();
                    if (parent != null && !parent.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        parent.mkdirs();
                    }
                    Files.write(ioFile.toPath(), content);

                    VirtualFile physical = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile);
                    if (physical != null) {
                        WriteAction.run(() -> {
                            com.intellij.openapi.editor.Document doc = FileDocumentManager.getInstance().getDocument(physical);
                            if (doc != null) FileDocumentManager.getInstance().reloadFromDisk(doc);
                            BapFileStatusService.getInstance(project).setStatus(physical, BapFileStatus.NORMAL);
                            physical.refresh(false, false);
                        });
                    } else {
                        // 兜底：至少把状态清掉，避免一直卡红D
                        BapFileStatusService.getInstance(project).setStatus(file, BapFileStatus.NORMAL);
                    }
                    return;
                }

                // 物理文件：走 VFS 写入，保证 PSI/VFS 一致性
                WriteAction.run(() -> {
                    file.setBinaryContent(content);
                    com.intellij.openapi.editor.Document doc = FileDocumentManager.getInstance().getDocument(file);
                    if (doc != null) FileDocumentManager.getInstance().reloadFromDisk(doc);
                    BapFileStatusService.getInstance(project).setStatus(file, BapFileStatus.NORMAL);
                    file.refresh(false, false);
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void deleteLocalFile(Project project, VirtualFile file) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                WriteAction.run(() -> {
                    // 先清状态
                    BapFileStatusService.getInstance(project).setStatus(file, BapFileStatus.NORMAL);

                    // LightVirtualFile(红D占位符) 通常没有物理文件可删，直接返回即可
                    if (!file.isInLocalFileSystem()) {
                        File ioFile = new File(file.getPath());
                        if (ioFile.exists()) {
                            // 极端情况：占位符路径下真的存在文件，尝试删掉
                            VirtualFile physical = LocalFileSystem.getInstance().findFileByIoFile(ioFile);
                            if (physical != null && physical.exists()) physical.delete(this);
                            else //noinspection ResultOfMethodCallIgnored
                                ioFile.delete();
                        }
                        return;
                    }

                    if (file.exists()) file.delete(this);
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    // ... 辅助方法 (保持不变，请务必复制) ...
    // collectChangedFiles, showConfirmDialog, findModuleRoot, extractAttr, showError, showInfo, sendNotification, update, getActionUpdateThread, isResourceFile, getResourceRelativePath, resolveClassName

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

            // 🔴 红D：本地不存在的文件也需要参与 Update（用于从云端还原）
            if (status == BapFileStatus.DELETED_LOCALLY) {
                VirtualFile deleted = createDeletedVirtualFile(path);
                if (deleted != null) result.add(deleted);
            }
        }
        return result;
    }

    private boolean showConfirmDialog(Project project, List<VirtualFile> files) {
        StringBuilder sb = new StringBuilder(BapBundle.message("action.UpdateAllAction.dialog.confirm_msg")); // "即将执行【Update All】操作..."
        int count = 0;
        for (VirtualFile f : files) {
            BapFileStatus status = BapFileStatusService.getInstance(project).getStatus(f);
            String symbol = "[?]";
            if (status == BapFileStatus.MODIFIED) symbol = BapBundle.message("action.StartDebugAction.symbol.modify");
            if (status == BapFileStatus.ADDED)    symbol = BapBundle.message("action.StartDebugAction.symbol.add");
            if (status == BapFileStatus.DELETED_LOCALLY) symbol = BapBundle.message("action.StartDebugAction.symbol.delete");
            sb.append(symbol).append(" ").append(f.getName()).append("\n");
            if (++count > 15) {
                sb.append(BapBundle.message("action.UpdateAllAction.dialog.confirm_more", files.size())); // "... 等 " + files.size() + " 个文件"
                break;
            }
        }
        return Messages.showOkCancelDialog(project, sb.toString(),
                BapBundle.message("action.UpdateAllAction.dialog.confirm_title"),   // "Confirm Update All"
                BapBundle.message("action.UpdateAllAction.button.update_overwrite"),// "Update (Overwrite)"
                BapBundle.message("button.cancel"),                                 // "Cancel" (common)
                Messages.getWarningIcon()) == Messages.OK;
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
        ApplicationManager.getApplication().invokeLater(() -> Messages.showErrorDialog(msg, BapBundle.message("title.update_error")));
    }

    private void showInfo(String msg) {
        ApplicationManager.getApplication().invokeLater(() -> Messages.showInfoMessage(msg, BapBundle.message("action.UpdateAllAction.title.info")));
    }

    private void sendNotification(Project project, String title, String content, NotificationType type) {
        Notification notification = new Notification(BapBundle.message("notification.group.bap"), title, content, type);
        Notifications.Bus.notify(notification, project);
    }

    // --- 🔴 新增：字符串路径辅助方法 ---
    private String getResDirPath(VirtualFile moduleRoot) {
        return moduleRoot.getPath().replace('\\', '/') + "/src/res";
    }

    // --- 🔴 修复：改用字符串判断 ---
    private boolean isResourceFile(VirtualFile moduleRoot, VirtualFile file) {
        String resPath = getResDirPath(moduleRoot);
        String filePath = file.getPath().replace('\\', '/');
        // 兼容: 直接是 src/res 本身，或是其子文件
        return filePath.equals(resPath) || filePath.startsWith(resPath + "/");
    }

    // --- 🔴 修复：改用字符串计算 ---
    private String getResourceRelativePath(VirtualFile moduleRoot, VirtualFile file) {
        String resPath = getResDirPath(moduleRoot);
        String filePath = file.getPath().replace('\\', '/');

        if (!filePath.startsWith(resPath)) return null;

        String relative = filePath.substring(resPath.length());
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


    private String resolveClassName(Project project, VirtualFile file) {
        return ReadAction.compute(() -> {
            // 1) PSI 解析 (保持不变)
            if (file.getLength() > 0) {
                PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
                if (psiFile instanceof PsiJavaFile) {
                    PsiJavaFile javaFile = (PsiJavaFile) psiFile;
                    String pkg = javaFile.getPackageName();
                    String cls = file.getNameWithoutExtension();
                    return pkg.isEmpty() ? cls : pkg + "." + cls;
                }
            }

            // 2) 路径计算
            // 🔴 修复：通过模块根定位 src，解决 src/src 问题
            VirtualFile moduleRoot = findModuleRoot(file);
            if (moduleRoot == null) return null;

            VirtualFile srcDir = moduleRoot.findChild("src");
            if (srcDir == null) return null;

            String rel = getPathRelativeTo(srcDir, file);
            if (rel == null) return null;

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
}