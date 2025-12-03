package com.bap.dev.activity;

import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.notification.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.util.io.HttpRequests;
import org.jetbrains.annotations.NotNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CheckUpdateActivity implements StartupActivity {

    // 🔴 请替换为你的 GitHub 用户名和仓库名
    private static final String GITHUB_OWNER = "LHR-1112";
    private static final String GITHUB_REPO = "BapDevTool";

    // 你的插件 ID (必须与 plugin.xml 中的 <id> 一致)
    private static final String PLUGIN_ID = "com.bap.dev.BapDevPlugin";

    private static final String API_URL = "https://api.github.com/repos/" + GITHUB_OWNER + "/" + GITHUB_REPO + "/releases/latest";

    @Override
    public void runActivity(@NotNull Project project) {
        System.out.println("Starting update check for Bap Plugin...");
        // 在后台线程执行网络请求，避免卡顿 UI
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                checkForUpdates(project);
            } catch (Exception e) {
                // 网络错误通常忽略，不打扰用户
            }
        });
    }

    private void checkForUpdates(Project project) {
        try {
            System.out.println("Checking for plugin updates...");

            // 1. 获取当前插件版本
            // 🔍 调试点 1：检查 ID 是否正确
            PluginId id = PluginId.getId(PLUGIN_ID);
            var pluginDescriptor = PluginManagerCore.getPlugin(id);

            if (pluginDescriptor == null) {
                System.err.println("Error: 找不到插件描述信息! 请检查 PLUGIN_ID [" + PLUGIN_ID + "] 是否与 plugin.xml 中的 <id> 完全一致。");
                return;
            }

            String currentVersion = pluginDescriptor.getVersion();
            System.out.println("Current local version: " + currentVersion);

            // 2. 请求 GitHub API
            System.out.println("Requesting GitHub API: " + API_URL);
            String response = HttpRequests.request(API_URL).readString();

            // 🔍 调试点 2：打印 API 返回内容（防止返回空或错误信息）
            // System.out.println("GitHub Response: " + response);

            String latestVersion = extractTagName(response);
            System.out.println("Latest version from GitHub: " + latestVersion);

            if (latestVersion == null) {
                System.err.println("Error: 无法从响应中提取 tag_name");
                return;
            }

            // 3. 去除前缀
            String cleanCurrent = currentVersion.replace("v", "");
            String cleanLatest = latestVersion.replace("v", "");

            // 4. 比较版本
            if (compareVersion(cleanLatest, cleanCurrent) > 0) {
                System.out.println("✨ New version detected! Preparing notification.");
                ApplicationManager.getApplication().invokeLater(() ->
                        showUpdateNotification(project, currentVersion, latestVersion)
                );
            } else {
                System.out.println("Up to date. No action needed.");
            }

        } catch (Exception e) {
            // 🔍 调试点 3：必须打印异常，否则不知道网络请求为什么失败
            System.err.println("Update check failed with exception:");
            e.printStackTrace();
        }
    }

    private void showUpdateNotification(Project project, String current, String latest) {
        NotificationGroup group = NotificationGroupManager.getInstance()
                .getNotificationGroup("Bap Update Notification");

        String content = String.format(
                "检测到 Bap Plugin 新版本: <b>%s</b> (当前: %s)<br/>" +
                        "<a href='https://github.com/%s/%s/releases/latest'>前往 GitHub 下载</a>",
                latest, current, GITHUB_OWNER, GITHUB_REPO
        );

        Notification notification = group.createNotification("Bap Plugin Update", content, NotificationType.INFORMATION);
        notification.setListener(NotificationListener.URL_OPENING_LISTENER); // 让链接可点击
        notification.notify(project);
    }

    // 简单的正则提取 "tag_name": "v1.2.0"
    private String extractTagName(String json) {
        Pattern pattern = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * 版本号比较逻辑
     * @return 1 if v1 > v2, -1 if v1 < v2, 0 if equal
     */
    private int compareVersion(String v1, String v2) {
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