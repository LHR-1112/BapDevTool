package com.bap.dev.action;

import bap.java.CJavaConst;
import com.bap.dev.BapRpcClient;
import com.bap.dev.i18n.BapBundle;
import com.bap.dev.service.BapConnectionManager;
import com.bap.dev.settings.BapSettingsState;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.leavay.common.util.ToolUtilities;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PublishProjectAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile selectedFile = e.getData(CommonDataKeys.VIRTUAL_FILE);

        if (project == null || selectedFile == null) return;

        // 向上查找模块根目录
        VirtualFile moduleRoot = findModuleRoot(selectedFile);
        if (moduleRoot == null) {
            Messages.showWarningDialog(
                    BapBundle.message("warning.no_develop_config"), // "未找到 .develop 配置文件..."
                    BapBundle.message("action.PublishProjectAction.title.cannot_publish")       // "无法发布"
            );
            return;
        }

        // 启动后台任务
        ProgressManager.getInstance().run(new Task.Backgroundable(project, BapBundle.message("action.PublishProjectAction.progress.publishing_title"), true) { // "Publishing Bap Project..."
            //            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                performPublish(project, moduleRoot, indicator);
            }
        });
    }

    private void performPublish(Project project, VirtualFile moduleRoot, ProgressIndicator indicator) {
        // 1. 读取配置
        File confFile = new File(moduleRoot.getPath(), CJavaConst.PROJECT_DEVELOP_CONF_FILE);
        String uri = null, user = null, pwd = null, projectUuid = null;
        try {
            String content = Files.readString(confFile.toPath());
            uri = extractAttr(content, "Uri");
            user = extractAttr(content, "User");
            pwd = extractAttr(content, "Password");
            projectUuid = extractAttr(content, "Project");
        } catch (Exception e) {
            showError(BapBundle.message("error.read_config", e.getMessage())); // "读取配置失败: " + e.getMessage()
            return;
        }

        if (uri == null || projectUuid == null) {
            showError(BapBundle.message("error.config_incomplete")); // "配置文件信息不全，无法发布。"
            return;
        }

        BapRpcClient client = BapConnectionManager.getInstance(project).getSharedClient(uri, user, pwd);
        try {
            indicator.setIndeterminate(true);
            indicator.setText(BapBundle.message("progress.connecting")); // "Connecting to server..." -> "Connecting..." (复用)
            client.connect(uri, user, pwd);

            // 读取全局配置: "发布时自动编译"
            boolean compileOnPublish = BapSettingsState.getInstance().compileOnPublish;

            if (compileOnPublish) {
                // 如果配置为自动编译，则执行 rebuildAll
                indicator.setText(BapBundle.message("action.PublishProjectAction.progress.rebuilding", projectUuid)); // "Rebuilding project (" + projectUuid + ")..."
                client.getService().rebuildAll(projectUuid);
            } else {
                indicator.setText(BapBundle.message("action.PublishProjectAction.progress.skip_rebuild")); // "Skipping rebuild (See Bap Settings)..."
            }

            // 3. 执行 Export Plugin
            indicator.setText(BapBundle.message("action.PublishProjectAction.progress.exporting")); // "Exporting to plugin..."

            // 逻辑：如果自动编译(true) -> 就不忽略错误(false)；如果不自动编译(false) -> 就忽略错误(true)
            boolean ignoreCompileError = !compileOnPublish;

            client.getService().exportProject2Plugin(projectUuid, null, true, ignoreCompileError);

            // 4. 成功通知
            sendNotification(project,
                    BapBundle.message("action.PublishProjectAction.notification.success_title"),   // "发布成功"
                    BapBundle.message("action.PublishProjectAction.notification.success_content")  // "项目已成功重新编译并导出插件。"
            );

        } catch (Exception e) {
            e.printStackTrace();
            sendNotification(project,
                    BapBundle.message("action.PublishProjectAction.notification.fail_title"), // "发布失败或存在编译报错"
                    ToolUtilities.getFullExceptionStack(e)
            );
        } finally {
            client.shutdown();
        }
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

    // --- 辅助方法 ---

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
        ApplicationManager.getApplication().invokeLater(() ->
                // 修改11: Error Dialog Title
                Messages.showErrorDialog(msg, BapBundle.message("title.publish_error"))); // "Publish Error"
    }

    private void sendNotification(Project project, String title, String content) {
        Notification notification = new Notification(
                BapBundle.message("notification.group.cloud.download"), // 修改12: 复用 common 中的 group id,
                title,
                content,
                NotificationType.INFORMATION
        );
        Notifications.Bus.notify(notification, project);
    }
}