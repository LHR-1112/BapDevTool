package com.bap.dev.activity;

import com.bap.dev.settings.BapSettingsState;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.notification.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState; // 引入这个
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.ui.Messages;
import com.intellij.util.io.HttpRequests;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CheckUpdateActivity implements StartupActivity {

    private static final String GITHUB_OWNER = "LHR-1112";
    private static final String GITHUB_REPO = "BapDevTool";
    private static final String PLUGIN_ID = "com.bap.dev.BapDevPlugin";
    private static final String API_URL = "https://api.github.com/repos/" + GITHUB_OWNER + "/" + GITHUB_REPO + "/releases/latest";

    @Override
    public void runActivity(@NotNull Project project) {
        if (!BapSettingsState.getInstance().checkUpdateOnStartup) {
            return;
        }

        System.out.println("Starting update check for Bap Plugin...");
        runUpdateCheck(project, false);
    }

    public static void runUpdateCheck(@Nullable Project project, boolean isManual) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                checkForUpdates(project, isManual);
            } catch (Exception e) {
                if (isManual) {
                    // --- 🔴 修复点：添加 ModalityState.any() ---
                    ApplicationManager.getApplication().invokeLater(() ->
                                    Messages.showErrorDialog(project, "Check failed: " + e.getMessage(), "Update Error"),
                            ModalityState.any()
                    );
                }
                e.printStackTrace();
            }
        });
    }

    private static void checkForUpdates(@Nullable Project project, boolean isManual) throws Exception {
        System.out.println("Checking for plugin updates...");

        PluginId id = PluginId.getId(PLUGIN_ID);
        var pluginDescriptor = PluginManagerCore.getPlugin(id);

        if (pluginDescriptor == null) {
            String msg = "Error: 找不到插件描述信息! ID: " + PLUGIN_ID;
            System.err.println(msg);
            if (isManual) {
                // --- 🔴 修复点 ---
                ApplicationManager.getApplication().invokeLater(() ->
                                Messages.showErrorDialog(project, msg, "Error"),
                        ModalityState.any()
                );
            }
            return;
        }

        String currentVersion = pluginDescriptor.getVersion();
        System.out.println("Current local version: " + currentVersion);

        String response = HttpRequests.request(API_URL).readString();
        String latestVersion = extractTagName(response);
        System.out.println("Latest version from GitHub: " + latestVersion);

        if (latestVersion == null) {
            if (isManual) {
                // --- 🔴 修复点 ---
                ApplicationManager.getApplication().invokeLater(() ->
                                Messages.showErrorDialog(project, "无法解析版本号", "Error"),
                        ModalityState.any()
                );
            }
            return;
        }

        String cleanCurrent = currentVersion.replace("v", "");
        String cleanLatest = latestVersion.replace("v", "");

        if (compareVersion(cleanLatest, cleanCurrent) > 0) {
            // --- 🔴 修复点 ---
            ApplicationManager.getApplication().invokeLater(() ->
                            showUpdateNotification(project, currentVersion, latestVersion),
                    ModalityState.any()
            );
        } else {
            if (isManual) {
                // --- 🔴 修复点 ---
                ApplicationManager.getApplication().invokeLater(() ->
                                Messages.showInfoMessage(project, "当前版本 (" + currentVersion + ") 已是最新。", "Check Update"),
                        ModalityState.any()
                );
            }
        }
    }

    private static void showUpdateNotification(@Nullable Project project, String current, String latest) {
        // 如果 Project 为 null (从设置页手动检查时)，通知可能无法显示在特定项目窗口
        // 但 createNotification 会尝试查找活动窗口，通常没问题
        NotificationGroup group = NotificationGroupManager.getInstance()
                .getNotificationGroup("Cloud Project Download");

        if (group == null) return;

        String content = String.format(
                "检测到 Bap Plugin 新版本: <b>%s</b> (当前: %s)<br/>" +
                        "<a href='https://github.com/%s/%s/releases/latest'>前往 GitHub 下载</a>",
                latest, current, GITHUB_OWNER, GITHUB_REPO
        );

        Notification notification = group.createNotification("Bap Plugin Update", content, NotificationType.INFORMATION);
        notification.setListener(NotificationListener.URL_OPENING_LISTENER);
        notification.notify(project);
    }

    private static String extractTagName(String json) {
        Pattern pattern = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private static int compareVersion(String v1, String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        int length = Math.max(parts1.length, parts2.length);

        for (int i = 0; i < length; i++) {
            int num1 = i < parts1.length ? Integer.parseInt(parts1[i]) : 0;
            int num2 = i < parts2.length ? Integer.parseInt(parts2[i]) : 0;
            if (num1 > num2) return 1;
            if (num1 < num2) return -1;
        }
        return 0;
    }
}