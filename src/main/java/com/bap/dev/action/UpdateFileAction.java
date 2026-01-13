package com.bap.dev.action;

import bap.java.CJavaCode;
import bap.java.CJavaConst;
import com.bap.dev.BapRpcClient;
import com.bap.dev.i18n.BapBundle;
import com.bap.dev.listener.BapChangesNotifier;
import com.bap.dev.service.BapConnectionManager;
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
import com.intellij.openapi.diagnostic.Logger;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UpdateFileAction extends AnAction {

    private static final Logger LOG = Logger.getInstance(UpdateFileAction.class);

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();

        // --- 🔴 核心修改：支持多选 ---
        VirtualFile[] selectedFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);

        if (project == null || selectedFiles == null || selectedFiles.length == 0) return;

        // 1. 检查第一个文件的模块根目录 (简单起见，假设多选文件都在同一个模块下)
        // 如果需要支持跨模块多选，则需要在循环内动态查找
        VirtualFile firstFile = selectedFiles[0];
        VirtualFile moduleRoot = findModuleRoot(firstFile);
        if (moduleRoot == null) {
            Messages.showWarningDialog(
                    BapBundle.message("warning.no_develop_config"), // "未找到 .develop 配置文件。"
                    BapBundle.message("notification.error_title")   // "错误"
            );
            return;
        }

        // 2. 启动后台批量任务
        ProgressManager.getInstance().run(new Task.Backgroundable(project, BapBundle.message("action.UpdateFileAction.progress.title"), true) { // "Updating Files from Cloud..."
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(false);
                int total = selectedFiles.length;
                int successCount = 0;
                int failCount = 0;

                for (int i = 0; i < total; i++) {
                    VirtualFile file = selectedFiles[i];
                    if (indicator.isCanceled()) break;

                    // 更新进度条
                    indicator.setFraction((double) (i + 1) / total);
                    indicator.setText(BapBundle.message("action.UpdateFileAction.progress.text", file.getName(), (i + 1), total)); // "Updating " + file.getName() + " (" + (i + 1) + "/" + total + ")..."

                    if (file.isDirectory()) continue; // 跳过文件夹

                    // 如果文件跨模块，重新查找根目录
                    VirtualFile currentModuleRoot = findModuleRoot(file);
                    if (currentModuleRoot == null) {
                        failCount++;
                        continue;
                    }

                    try {
                        if (isResourceFile(currentModuleRoot, file)) {
                            updateResourceFile(project, currentModuleRoot, file);
                        } else {
                            updateJavaFile(project, currentModuleRoot, file);
                        }
                        successCount++;
                    } catch (Exception ex) {
                        ex.printStackTrace();
                        failCount++;
                        System.err.println("Failed to update " + file.getName() + ": " + ex.getMessage());
                    }
                }

                // 3. 全部完成后通知刷新
                if (successCount > 0) {
                    final int finalSuccess = successCount;
                    final int finalFail = failCount;
                    ApplicationManager.getApplication().invokeLater(() -> {
                        PsiManager.getInstance(project).dropPsiCaches();
                        FileStatusManager.getInstance(project).fileStatusesChanged();
                        project.getMessageBus().syncPublisher(BapChangesNotifier.TOPIC).onChangesUpdated();

                        String msg = BapBundle.message("action.UpdateFileAction.notification.finish_msg", finalSuccess, finalFail); // "批量更新完成。成功: " + finalSuccess + ", 失败: " + finalFail
                        NotificationType type = finalFail > 0 ? NotificationType.WARNING : NotificationType.INFORMATION;
                        sendNotification(project,
                                BapBundle.message("action.UpdateFileAction.notification.title"), // "Update Result"
                                msg, type);
                    });
                }
            }
        });
    }

    // --- 处理资源文件 ---
    private void updateResourceFile(Project project, VirtualFile moduleRoot, VirtualFile file) throws Exception {
        String relativePath = getResourceRelativePath(moduleRoot, file);
        if (relativePath == null) throw new Exception(BapBundle.message("action.UpdateFileAction.error.calc_path"));

        File confFile = new File(moduleRoot.getPath(), CJavaConst.PROJECT_DEVELOP_CONF_FILE);
        String content = Files.readString(confFile.toPath());
        String uri = extractAttr(content, "Uri");
        String user = extractAttr(content, "User");
        String pwd = extractAttr(content, "Password");

        BapRpcClient client = BapConnectionManager.getInstance(project).getSharedClient(uri, user, pwd);
        String projectUuid = getProjectUuid(moduleRoot);

        // 🔴 修复：确保查询路径以 "/" 开头，否则服务器可能找不到文件 (针对红D恢复)
        String queryPath = relativePath.startsWith("/") ? relativePath : "/" + relativePath;

        CResFileDto resDto = client.getService().getResFile(projectUuid, queryPath, false);

        if (resDto != null && resDto.getFileBin() != null) {
            overwriteFile(project, file, resDto.getFileBin());
        } else {
            BapFileStatus status = BapFileStatusService.getInstance(project).getStatus(file);
            if (status == BapFileStatus.DELETED_LOCALLY) {
                deleteLocalFile(project, file);
            } else {
                deleteLocalFile(project, file);
                LOG.info("Skipping local-only file: " + file.getName());
            }
        }
    }

    // --- 处理 Java 文件 (保持不变) ---
    private void updateJavaFile(Project project, VirtualFile moduleRoot, VirtualFile file) throws Exception {
        String fullClassName = resolveClassName(project, file);
        if (fullClassName == null) throw new Exception(BapBundle.message("action.UpdateFileAction.error.resolve_class")); // "无法解析类名"

        // --- 🔴 修改开始：手动读取配置并使用 BapConnectionManager ---
        File confFile = new File(moduleRoot.getPath(), CJavaConst.PROJECT_DEVELOP_CONF_FILE);
        String content = Files.readString(confFile.toPath());
        String uri = extractAttr(content, "Uri");
        String user = extractAttr(content, "User");
        String pwd = extractAttr(content, "Password");

        BapRpcClient client = BapConnectionManager.getInstance(project).getSharedClient(uri, user, pwd);
        // --- 🔴 修改结束 ---

        String projectUuid = getProjectUuid(moduleRoot);

        Object remoteObj = client.getService().getJavaCode(projectUuid, fullClassName);
        String codeContent = null;

        if (remoteObj != null) {
            if (remoteObj instanceof CJavaCode) {
                codeContent = ((CJavaCode) remoteObj).code;
            } else {
                try {
                    java.lang.reflect.Field f = remoteObj.getClass().getField("code");
                    codeContent = (String) f.get(remoteObj);
                } catch (Exception ignore) {}
            }
        }

        if (codeContent != null) {
            overwriteFile(project, file, codeContent.getBytes(StandardCharsets.UTF_8));
        } else {
            // 同上，对于 Java 文件，如果是本地新增的，Update 操作默认忽略
            LOG.info("Skipping local-only file: " + file.getName());
        }
    }

    // --- 统一的文件操作 ---

    // 🔴 修改：overwriteFile 方法
    // 强制穿透内存文件，写入物理磁盘
    private void overwriteFile(Project project, VirtualFile file, byte[] content) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                WriteAction.run(() -> {
                    // 1. 准备物理文件对象
                    File ioFile = new File(file.getPath());

                    // 2. 确保父目录存在
                    if (!ioFile.getParentFile().exists()) {
                        ioFile.getParentFile().mkdirs();
                    }

                    // 3. 写入物理磁盘 (覆盖 LightVirtualFile 无法写入磁盘的问题)
                    Files.write(ioFile.toPath(), content);

                    // 4. 关键：刷新 VFS 以获取真正的 VirtualFile
                    // 使用 refreshAndFindFileByIoFile 让 IDEA 感知到磁盘上的新文件
                    VirtualFile realFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile);

                    // 5. 设置状态并刷新
// 兼容：statusMap 里存的是“路径字符串”，所以无论 realFile 是否拿到，都先按 path 清一次
                    BapFileStatusService.getInstance(project).setStatus(file.getPath(), BapFileStatus.NORMAL);

                    if (realFile != null) {
                        BapFileStatusService.getInstance(project).setStatus(realFile, BapFileStatus.NORMAL);
                        realFile.refresh(false, false);
                    }
                });
            } catch (Exception e) {
                showError(BapBundle.message("action.UpdateFileAction.error.write_failed", e.getMessage()));
            }
        });
    }

    private void deleteLocalFile(Project project, VirtualFile file) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                WriteAction.run(() -> {
                    // 红D(LightVirtualFile) 没有物理文件：只清状态即可；物理文件则顺便删除
                    BapFileStatusService svc = BapFileStatusService.getInstance(project);
                    svc.setStatus(file.getPath(), BapFileStatus.NORMAL);

                    if (file.isValid() && file.isInLocalFileSystem()) {
                        file.delete(this);
                    }
                });
            } catch (Exception e) {
                showError(BapBundle.message("action.UpdateFileAction.error.delete_failed", e.getMessage())); // "删除失败: " + e.getMessage()
            }
        });
    }


    private String getProjectUuid(VirtualFile moduleRoot) throws Exception {
        File confFile = new File(moduleRoot.getPath(), CJavaConst.PROJECT_DEVELOP_CONF_FILE);
        String content = Files.readString(confFile.toPath());
        return extractAttr(content, "Project");
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
            String filePath = file.getPath().replace('\\', '/'); // BapDeletedVirtualFile 返回绝对路径

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

    private String getFieldString(Object obj, String fieldName) {
        try {
            java.lang.reflect.Field field = obj.getClass().getField(fieldName);
            Object val = field.get(obj);
            return val != null ? val.toString() : null;
        } catch (Exception e) { return null; }
    }

    private void showError(String msg) {
        ApplicationManager.getApplication().invokeLater(() ->
                // 修改10: Error Dialog Title (使用提取到 common 的 key)
                Messages.showErrorDialog(msg, BapBundle.message("title.update_error")) // "Update Error"
        );
    }

    private void sendNotification(Project project, String title, String content, NotificationType type) {
        Notification notification = new Notification(
                BapBundle.message("notification.group.cloud.download"), // "Cloud Project Download"
                title, content, type);
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