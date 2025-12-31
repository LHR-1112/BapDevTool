package com.bap.dev.action;

import bap.java.CJavaConst;
import com.bap.dev.BapRpcClient;
import com.bap.dev.handler.LibConfigurator;
import com.bap.dev.handler.LibDownloader;
import com.bap.dev.i18n.BapBundle;
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
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UpdateLibsAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile selectedFile = e.getData(CommonDataKeys.VIRTUAL_FILE);

        if (project == null || selectedFile == null) return;

        // 查找模块根目录
        VirtualFile moduleRoot = findModuleRoot(selectedFile);
        if (moduleRoot == null) {
            Messages.showWarningDialog(
                    BapBundle.message("warning.no_develop_config"), // "未找到 .develop 配置文件。"
                    BapBundle.message("notification.error_title")   // "错误"
            );
            return;
        }

        ProgressManager.getInstance().run(new Task.Backgroundable(project, BapBundle.message("action.UpdateLibsAction.progress.title"), true) { // "Updating Libraries..."
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                updateLibraries(project, moduleRoot, indicator);
            }
        });
    }

    private void updateLibraries(Project project, VirtualFile moduleRoot, ProgressIndicator indicator) {
        File confFile = new File(moduleRoot.getPath(), CJavaConst.PROJECT_DEVELOP_CONF_FILE);
        String uri = null, user = null, pwd = null, projectUuid = null;

        try {
            // 简单读取配置
            String content = new String(Files.readAllBytes(confFile.toPath()), "UTF-8");
            uri = extractAttr(content, "Uri");
            user = extractAttr(content, "User");
            pwd = extractAttr(content, "Password");
            projectUuid = extractAttr(content, "Project");
        } catch (Exception e) {
            showError(BapBundle.message("error.read_config", e.getMessage())); // "读取配置失败: " + e.getMessage()
            return;
        }

        if (uri == null || projectUuid == null) {
            showError(BapBundle.message("error.config_incomplete")); // "配置文件信息不全"
            return;
        }

        BapRpcClient client = new BapRpcClient();
        try {
            indicator.setText(BapBundle.message("progress.connecting")); // "Connecting..."
            client.connect(uri, user, pwd);

            // 1. 下载依赖
            File projectRootIo = new File(moduleRoot.getPath());
            LibDownloader downloader = new LibDownloader(client, projectRootIo);
            downloader.updateLibDiff(projectUuid, indicator);

            // 2. 配置 IDEA 依赖
            indicator.setText(BapBundle.message("action.UpdateLibsAction.progress.configuring")); // "Configuring project structure..."
            LibConfigurator.configureLibraries(project, moduleRoot);

            sendNotification(project,
                    BapBundle.message("action.UpdateLibsAction.notification.success_title"),   // "更新成功"
                    BapBundle.message("action.UpdateLibsAction.notification.success_content")  // "依赖库下载完成并已更新项目配置。"
            );

        } catch (Exception e) {
            e.printStackTrace();
            showError(BapBundle.message("action.UpdateLibsAction.error.update_failed", e.getMessage())); // "更新失败: " + e.getMessage()
        } finally {
            client.shutdown();
        }
    }

    // ... 通用辅助方法 (findModuleRoot, extractAttr, showError, sendNotification) ...
    // 请复制之前 Action 中已有的这些方法

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
                // 修改9: Error Dialog Title
                Messages.showErrorDialog(msg, BapBundle.message("title.update_error"))); // "Update Libs Error"
    }

    private void sendNotification(Project project, String title, String content) {
        Notification notification = new Notification(
                BapBundle.message("notification.group.cloud.download"), // "Cloud Project Download"
                title, content, NotificationType.INFORMATION);
        Notifications.Bus.notify(notification, project);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}