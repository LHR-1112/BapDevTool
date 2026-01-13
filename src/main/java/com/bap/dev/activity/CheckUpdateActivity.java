package com.bap.dev.activity;

import com.bap.dev.i18n.BapBundle;
import com.bap.dev.settings.BapSettingsState;
import com.intellij.ide.BrowserUtil;
import com.intellij.ide.plugins.*;
import com.intellij.notification.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.updateSettings.impl.UpdateSettings; // 🔴 新增引用
import com.intellij.util.io.HttpRequests;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CheckUpdateActivity implements StartupActivity {

    private static final String PLUGIN_ID = "com.bap.dev.BapDevPlugin";
    private static final String PLUGINS_XML_URL = "https://lhr-1112.github.io/BapDevTool/plugins.xml";
    private static final AtomicBoolean CHECKED_THIS_SESSION = new AtomicBoolean(false);

    @Override
    public void runActivity(@NotNull Project project) {
        BapSettingsState s = BapSettingsState.getInstance();
        if (!s.checkUpdateOnStartup) return;
        if (!CHECKED_THIS_SESSION.compareAndSet(false, true)) return;
        runUpdateCheck(project, false);
    }

    public static void runUpdateCheck(@Nullable Project project, boolean isManual) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                // 🔴 在检查更新前，先确保自定义仓库已配置
                ensureRepositoryUrl(project);

                checkForUpdates(project, isManual);
            } catch (Exception e) {
                if (isManual) {
                    ApplicationManager.getApplication().invokeLater(() ->
                                    Messages.showErrorDialog(project,
                                            BapBundle.message("activity.CheckUpdateActivity.error.check_failed", e.getMessage()),
                                            BapBundle.message("title.update_error")),
                            ModalityState.any());
                }
                e.printStackTrace();
            }
        });
    }

    // --- 🔴 新增：自动检查并添加自定义仓库链接 ---
    private static void ensureRepositoryUrl(@Nullable Project project) {
        try {
            UpdateSettings settings = UpdateSettings.getInstance();
            List<String> hosts = settings.getStoredPluginHosts(); // 获取的是 Live List (可变引用)

            if (!hosts.contains(PLUGINS_XML_URL)) {
                hosts.add(PLUGINS_XML_URL); // 直接添加即可，IDEA 会自动持久化这个 State 对象

                // 通知用户已自动添加
                ApplicationManager.getApplication().invokeLater(() -> {
                    NotificationGroup group = NotificationGroupManager.getInstance().getNotificationGroup("Cloud Project Download");
                    if (group != null) {
                        Notification n = group.createNotification(
                                BapBundle.message("title.plugin_update"),
                                BapBundle.message("activity.CheckUpdateActivity.notification.ensure_repository_url"),
                                NotificationType.INFORMATION
                        );
                        n.notify(project);
                    }
                }, ModalityState.any());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    // ------------------------------------------

    private static void checkForUpdates(@Nullable Project project, boolean isManual) throws Exception {
        PluginId id = PluginId.getId(PLUGIN_ID);
        var pluginDescriptor = PluginManagerCore.getPlugin(id);

        if (pluginDescriptor == null) {
            if (isManual) {
                ApplicationManager.getApplication().invokeLater(() ->
                                Messages.showErrorDialog(project,
                                        BapBundle.message("activity.CheckUpdateActivity.error.desc_not_found", PLUGIN_ID),
                                        BapBundle.message("notification.error_title")),
                        ModalityState.any());
            }
            return;
        }

        String currentVersion = pluginDescriptor.getVersion();
        String pluginsXml = HttpRequests.request(PLUGINS_XML_URL).readString();
        RepoEntry latest = parseRepoEntry(pluginsXml, PLUGIN_ID);

        if (latest == null || latest.version == null || latest.downloadUrl == null) {
            if (isManual) {
                ApplicationManager.getApplication().invokeLater(() ->
                                Messages.showErrorDialog(project,
                                        BapBundle.message("activity.CheckUpdateActivity.error.xml_parse"),
                                        BapBundle.message("title.update_error")),
                        ModalityState.any());
            }
            return;
        }

        String cleanCurrent = normalizeVersion(currentVersion);
        String cleanLatest = normalizeVersion(latest.version);

        if (compareVersion(cleanLatest, cleanCurrent) > 0) {
            ApplicationManager.getApplication().invokeLater(
                    () -> showUpdateUi(project, currentVersion, latest.version, latest, isManual),
                    ModalityState.any()
            );
        } else if (isManual) {
            ApplicationManager.getApplication().invokeLater(
                    () -> Messages.showInfoMessage(project,
                            BapBundle.message("activity.CheckUpdateActivity.info.latest", currentVersion),
                            BapBundle.message("title.check_update")),
                    ModalityState.any()
            );
        }
    }

    private static void showUpdateUi(@Nullable Project project, String current, String latest, RepoEntry latestEntry, boolean isManual) {
        String html = buildUpdateHtml(current, latest, latestEntry);
        if (isManual) {
            showUpdateModal(project, html, latest, latestEntry);
        } else {
            showUpdateNotification(project, current, latestEntry);
        }
    }

    private static String buildUpdateHtml(String current, String latest, RepoEntry latestEntry) {
        String notesHtml = "";
        if (latestEntry.changeNotes != null && !latestEntry.changeNotes.isBlank()) {
            String text = latestEntry.changeNotes.trim();
            text = text.length() > 800 ? text.substring(0, 800) + "\n…" : text;
            notesHtml = BapBundle.message("activity.CheckUpdateActivity.html.notes_header") + escapeHtml(text).replace("\n", "<br/>");
        }
        return BapBundle.message("activity.CheckUpdateActivity.html.detected", latest, current, notesHtml);
    }

    private static void showUpdateModal(@Nullable Project project, String htmlContent, String latest, RepoEntry latestEntry) {
        String[] options = new String[] {
                BapBundle.message("activity.CheckUpdateActivity.open_plugins"),
                BapBundle.message("button.github_download"),
                BapBundle.message("button.cancel"),
        };
        int choice = Messages.showDialog(
                project,
                "<html>" + htmlContent + "</html>",
                BapBundle.message("title.plugin_update"),
                options,
                -1,
                Messages.getInformationIcon()
        );
        switch (choice) {
            case 0 -> ShowSettingsUtil.getInstance().showSettingsDialog(project, "Plugins");
            case 1 -> {
                if (latestEntry.backupUrl != null && !latestEntry.backupUrl.isBlank()) BrowserUtil.browse(latestEntry.backupUrl.trim());
                else if (latestEntry.downloadUrl != null && !latestEntry.downloadUrl.isBlank()) BrowserUtil.browse(latestEntry.downloadUrl.trim());
                else BrowserUtil.browse(latestEntry.backupUrl);
            }
            default -> { }
        }
    }

    private static void showUpdateNotification(@Nullable Project project, String current, RepoEntry latest) {
        NotificationGroup group = NotificationGroupManager.getInstance().getNotificationGroup("Cloud Project Download");
        if (group == null) return;

        String notesHtml = "";
        if (latest.changeNotes != null && !latest.changeNotes.isBlank()) {
            String text = latest.changeNotes.trim();
            text = text.length() > 800 ? text.substring(0, 800) + "\n…" : text;
            notesHtml = BapBundle.message("activity.CheckUpdateActivity.html.notes_header") + escapeHtml(text).replace("\n", "<br/>");
        } else {
            notesHtml = BapBundle.message("activity.CheckUpdateActivity.html.no_notes");
        }

        String content = BapBundle.message("activity.CheckUpdateActivity.html.detected", latest.version, current, notesHtml);
        Notification n = group.createNotification(BapBundle.message("title.plugin_update"), content, NotificationType.INFORMATION);

        n.addAction(NotificationAction.createSimple(BapBundle.message("button.github_download"), () -> {
            if (latest.backupUrl != null && !latest.backupUrl.isBlank()) BrowserUtil.browse(latest.backupUrl);
            else Messages.showInfoMessage(project, BapBundle.message("activity.CheckUpdateActivity.info.no_github"), BapBundle.message("title.tip"));
        }));

        n.addAction(NotificationAction.createSimple(BapBundle.message("activity.CheckUpdateActivity.open_plugins"), () -> {
            n.expire();
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "Plugins");
        }));
        n.notify(project);
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static RepoEntry parseRepoEntry(String xml, String pluginId) {
        Pattern block = Pattern.compile(
                "<plugin\\b[^>]*\\bid\\s*=\\s*\"" + Pattern.quote(pluginId) + "\"[^>]*>.*?</plugin>",
                Pattern.DOTALL);
        Matcher m = block.matcher(xml);
        if (!m.find()) return null;

        String pluginBlock = m.group(0);
        String version = extractAttr(pluginBlock, "version");
        String url = extractAttr(pluginBlock, "url");
        String backup = "https://github.com/LHR-1112/BapDevTool/releases/download/v" + version
                + "/BapDevTool-" + version + ".zip";

        String changeNotes = extractTagText(pluginBlock, "change-notes");

        if (url == null) {
            Pattern p = Pattern.compile("<download-url>\\s*([^<\\s]+)\\s*</download-url>");
            Matcher dm = p.matcher(pluginBlock);
            if (dm.find()) url = dm.group(1).trim();
        }

        if (version == null) return null;
        return new RepoEntry(version.trim(), url == null ? null : url.trim(), backup, changeNotes);
    }

    private static String extractTagText(String block, String tag) {
        Pattern p = Pattern.compile("<" + Pattern.quote(tag) + ">([\\s\\S]*?)</" + Pattern.quote(tag) + ">");
        Matcher m = p.matcher(block);
        if (!m.find()) return null;
        String raw = m.group(1);
        raw = raw.replaceFirst("^\\s*<!\\[CDATA\\[", "");
        raw = raw.replaceFirst("]]>\\s*$", "");
        return raw;
    }

    private static String extractAttr(String s, String attr) {
        Pattern p = Pattern.compile("\\b" + Pattern.quote(attr) + "\\s*=\\s*\"([^\"]+)\"");
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String normalizeVersion(String v) {
        if (v == null) return "";
        return v.trim().replaceFirst("^[vV]", "");
    }

    private static int compareVersion(String v1, String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        int length = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < length; i++) {
            int num1 = i < parts1.length ? safeInt(parts1[i]) : 0;
            int num2 = i < parts2.length ? safeInt(parts2[i]) : 0;
            if (num1 > num2) return 1;
            if (num1 < num2) return -1;
        }
        return 0;
    }

    private static int safeInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }

    private static class RepoEntry {
        final String version;
        final String downloadUrl;
        final String backupUrl;
        final String changeNotes;

        RepoEntry(String version, String downloadUrl, String backupUrl, String changeNotes) {
            this.version = version;
            this.downloadUrl = downloadUrl;
            this.backupUrl = backupUrl;
            this.changeNotes = changeNotes;
        }
    }
}