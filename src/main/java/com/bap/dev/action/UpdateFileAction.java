package com.bap.dev.action;

import bap.java.CJavaCode;
import bap.java.CJavaConst;
import com.bap.dev.BapRpcClient;
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
import cplugin.ms.dto.CResFileDto;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UpdateFileAction extends AnAction {

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
            Messages.showWarningDialog("未找到 .develop 配置文件。", "错误");
            return;
        }

        // 2. 启动后台批量任务
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Updating Files from Cloud...", true) {
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
                    indicator.setText("Updating " + file.getName() + " (" + (i + 1) + "/" + total + ")...");

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

                        String msg = "批量更新完成。成功: " + finalSuccess + ", 失败: " + finalFail;
                        NotificationType type = finalFail > 0 ? NotificationType.WARNING : NotificationType.INFORMATION;
                        sendNotification(project, "Update Result", msg, type);
                    });
                }
            }
        });
    }

    // --- 处理资源文件 (保持上次的修复版) ---
    private void updateResourceFile(Project project, VirtualFile moduleRoot, VirtualFile file) throws Exception {
        String relativePath = getResourceRelativePath(moduleRoot, file);
        if (relativePath == null) throw new Exception("无法计算资源路径");

        // --- 🔴 修改开始：手动读取配置并使用 BapConnectionManager ---
        File confFile = new File(moduleRoot.getPath(), CJavaConst.PROJECT_DEVELOP_CONF_FILE);
        String content = Files.readString(confFile.toPath());
        String uri = extractAttr(content, "Uri");
        String user = extractAttr(content, "User");
        String pwd = extractAttr(content, "Password");

        BapRpcClient client = BapConnectionManager.getInstance(project).getSharedClient(uri, user, pwd);
        // --- 🔴 修改结束 ---

        String projectUuid = getProjectUuid(moduleRoot);

        try {
            // 1. 尝试获取资源 (带内容 true)
            CResFileDto resDto = client.getService().getResFile(projectUuid, relativePath, false);

            if (resDto != null && resDto.getFileBin() != null) {
                // A. 存在且有内容 -> 覆盖本地 (修复 黄M 和 红D)
                overwriteFile(project, file, resDto.getFileBin());
            } else {
                // B. 云端不存在 (或内容为空)
                BapFileStatus status = BapFileStatusService.getInstance(project).getStatus(file);

                if (status == BapFileStatus.DELETED_LOCALLY) {
                    // 如果本地是红D (本来就是空占位符)，且云端确实没有
                    // 直接移除本地占位符
                    deleteLocalFile(project, file);
                } else {
                    // 如果是蓝A (本地有，云端无)，或者普通文件被误删
                    // 对于批量操作，不建议弹窗打断，这里直接跳过或者记录日志
                    // 或者我们可以设定策略：Update 操作对于蓝A文件不做处理 (因为它本来就只在本地有)
                    // 如果想强行同步（即删除本地），可以使用 deleteLocalFile(project, file);

                    deleteLocalFile(project, file);
                    System.out.println("Skipping local-only file: " + file.getName());
                }
            }
        } finally {
            client.shutdown();
        }
    }

    // --- 处理 Java 文件 (保持不变) ---
    private void updateJavaFile(Project project, VirtualFile moduleRoot, VirtualFile file) throws Exception {
        String fullClassName = resolveClassName(project, file);
        if (fullClassName == null) throw new Exception("无法解析类名");

        // --- 🔴 修改开始：手动读取配置并使用 BapConnectionManager ---
        File confFile = new File(moduleRoot.getPath(), CJavaConst.PROJECT_DEVELOP_CONF_FILE);
        String content = Files.readString(confFile.toPath());
        String uri = extractAttr(content, "Uri");
        String user = extractAttr(content, "User");
        String pwd = extractAttr(content, "Password");

        BapRpcClient client = BapConnectionManager.getInstance(project).getSharedClient(uri, user, pwd);
        // --- 🔴 修改结束 ---

        String projectUuid = getProjectUuid(moduleRoot);

        try {
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
                System.out.println("Skipping local-only file: " + file.getName());
            }
        } finally {
            client.shutdown();
        }
    }

    // --- 统一的文件操作 ---

    private void overwriteFile(Project project, VirtualFile file, byte[] content) {
        // 使用 invokeAndWait 确保在循环继续前文件已写完，或者用 invokeLater 异步排队
        // 在批量操作中，invokeLater 是安全的
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                WriteAction.run(() -> {
                    file.setBinaryContent(content);

                    com.intellij.openapi.editor.Document doc = FileDocumentManager.getInstance().getDocument(file);
                    if (doc != null) FileDocumentManager.getInstance().reloadFromDisk(doc);

                    BapFileStatusService.getInstance(project).setStatus(file, BapFileStatus.NORMAL);
                    file.refresh(false, false);
                });
            } catch (Exception e) {
                showError("写入文件失败: " + e.getMessage());
            }
        });
    }

    private void deleteLocalFile(Project project, VirtualFile file) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                WriteAction.run(() -> {
                    BapFileStatusService.getInstance(project).setStatus(file, BapFileStatus.NORMAL);
                    file.delete(this);
                });
            } catch (Exception e) {
                showError("删除失败: " + e.getMessage());
            }
        });
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

    private String getFieldString(Object obj, String fieldName) {
        try {
            java.lang.reflect.Field field = obj.getClass().getField(fieldName);
            Object val = field.get(obj);
            return val != null ? val.toString() : null;
        } catch (Exception e) { return null; }
    }

    private void showError(String msg) {
        ApplicationManager.getApplication().invokeLater(() -> Messages.showErrorDialog(msg, "Update Error"));
    }

    private void sendNotification(Project project, String title, String content, NotificationType type) {
        Notification notification = new Notification("Cloud Project Download", title, content, type);
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