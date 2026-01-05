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
import com.intellij.util.io.HttpRequests;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
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
                checkForUpdates(project, isManual);
            } catch (Exception e) {
                if (isManual) {
                    ApplicationManager.getApplication().invokeLater(() ->
                                    // 修改1: Error Dialog
                                    Messages.showErrorDialog(project,
                                            BapBundle.message("activity.CheckUpdateActivity.error.check_failed", e.getMessage()), // "Check failed: " + e.getMessage()
                                            BapBundle.message("title.update_error")), // "Update Error" (Common)
                            ModalityState.any());
                }
                e.printStackTrace();
            }
        });
    }

    // ... (checkForUpdates, showUpdateNotification, downloadAndInstall 方法保持不变，直接复用上文即可) ...
    // 为节省篇幅，这里省略中间未修改的方法，请保留原样。
    // 重点修改下面的 installPluginZipAfterRestart

    private static void checkForUpdates(@Nullable Project project, boolean isManual) throws Exception {
        PluginId id = PluginId.getId(PLUGIN_ID);
        var pluginDescriptor = PluginManagerCore.getPlugin(id);

        if (pluginDescriptor == null) {
            if (isManual) {
                ApplicationManager.getApplication().invokeLater(() ->
                                // 修改2: Error Dialog
                                Messages.showErrorDialog(project,
                                        BapBundle.message("activity.CheckUpdateActivity.error.desc_not_found", PLUGIN_ID), // "Error: 找不到插件描述信息! ID: " + PLUGIN_ID
                                        BapBundle.message("notification.error_title")), // "Error" (Common)
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
                                // 修改3: Error Dialog
                                Messages.showErrorDialog(project,
                                        BapBundle.message("activity.CheckUpdateActivity.error.xml_parse"), // "无法从 plugins.xml 解析版本/下载地址"
                                        BapBundle.message("title.update_error")), // "Update Error" (Common)
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
                            BapBundle.message("activity.CheckUpdateActivity.info.latest", currentVersion), // "当前版本 (" + currentVersion + ") 已是最新。"
                            BapBundle.message("title.check_update")), // "检查更新" (Common)
                    ModalityState.any()
            );
        }
    }

    private static void showUpdateUi(@Nullable Project project, String current, String latest, RepoEntry latestEntry, boolean isManual) {
        String html = buildUpdateHtml(current, latest, latestEntry);

        if (isManual) {
            // ✅ 设置页点击：弹 Modal，内容与通知一致
            showUpdateModal(project, html, latest, latestEntry);
        } else {
            // ✅ 启动自动检查：右下角通知
            showUpdateNotification(project, current, latestEntry);
        }
    }

    private static String buildUpdateHtml(String current, String latest, RepoEntry latestEntry) {
        String notesHtml = "";
        if (latestEntry.changeNotes != null && !latestEntry.changeNotes.isBlank()) {
            String text = latestEntry.changeNotes.trim();
            text = text.length() > 800 ? text.substring(0, 800) + "\n…" : text;
            notesHtml = BapBundle.message("activity.CheckUpdateActivity.html.notes_header") + escapeHtml(text).replace("\n", "<br/>"); // "<br/><br/><b>更新内容：</b><br/>" ...
        }

        return BapBundle.message("activity.CheckUpdateActivity.html.detected", latest, current, notesHtml); // "检测到 Bap Plugin 新版本..."
    }

    private static void showUpdateModal(@Nullable Project project, String htmlContent, String latest, RepoEntry latestEntry) {
        String[] options = new String[] {
                BapBundle.message("button.update_restart"), // "更新并重启"
                BapBundle.message("button.github_download"), // "GitHub下载"
                BapBundle.message("button.cancel"),         // 索引 2: "取消" (新增)
        };

        int choice = Messages.showDialog(
                project,
                "<html>" + htmlContent + "</html>",
                BapBundle.message("title.plugin_update"), // "Bap Plugin Update" (Common)
                options,
                -1,
                Messages.getInformationIcon()
        );

        switch (choice) {
            case 0 -> downloadAndInstall(project, latestEntry);
            case 1 -> {
                if (latestEntry.backupUrl != null && !latestEntry.backupUrl.isBlank()) {
                    BrowserUtil.browse(latestEntry.backupUrl.trim());
                } else if (latestEntry.downloadUrl != null && !latestEntry.downloadUrl.isBlank()) {
                    BrowserUtil.browse(latestEntry.downloadUrl.trim());
                } else {
                    BrowserUtil.browse(latestEntry.backupUrl);
                }
            }
            default -> { /* 关闭 */ }
        }
    }

    private static void showUpdateNotification(@Nullable Project project, String current, RepoEntry latest) {
        NotificationGroup group = NotificationGroupManager.getInstance()
                .getNotificationGroup("Cloud Project Download");
        if (group == null) return;

        String notesHtml = "";
        if (latest.changeNotes != null && !latest.changeNotes.isBlank()) {
            String text = latest.changeNotes.trim();
            text = text.length() > 800 ? text.substring(0, 800) + "\n…" : text; // 防止通知太长
            notesHtml = BapBundle.message("activity.CheckUpdateActivity.html.notes_header") + escapeHtml(text).replace("\n", "<br/>");
        } else {
            notesHtml = BapBundle.message("activity.CheckUpdateActivity.html.no_notes"); // "<br/><br/>(无更新内容)"
        }

        String content = BapBundle.message("activity.CheckUpdateActivity.html.detected", latest.version, current, notesHtml);

        Notification n = group.createNotification(
                BapBundle.message("title.plugin_update"), // "Bap Plugin Update" (Common)
                content,
                NotificationType.INFORMATION);

        n.addAction(NotificationAction.createSimple(BapBundle.message("button.github_download"), () -> { // "GitHub下载"
            if (latest.backupUrl != null && !latest.backupUrl.isBlank()) {
                BrowserUtil.browse(latest.backupUrl);
            } else {
                // 修改11: Info Message
                Messages.showInfoMessage(project,
                        BapBundle.message("activity.CheckUpdateActivity.info.no_github"), // "未配置 GitHub 备份下载链接。"
                        BapBundle.message("title.tip")); // "Info" -> "Tip" (Common)
            }
        }));

        n.addAction(NotificationAction.createSimple(BapBundle.message("button.update_restart"), () -> { // "立即更新并重启" (复用 common)
            n.expire();
            downloadAndInstall(project, latest);
        }));

        n.notify(project);
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static void downloadAndInstall(@Nullable Project project, RepoEntry latest) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, BapBundle.message("activity.CheckUpdateActivity.progress.updating"), true) { // "Updating Plugin..."
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    indicator.setText(BapBundle.message("activity.CheckUpdateActivity.progress.downloading", latest.version)); // "Downloading version..."
                    String fileName = "BapDevPlugin-" + latest.version + ".zip";
                    File zipFile = downloadToTemp(latest.downloadUrl, fileName);

                    indicator.setText(BapBundle.message("activity.CheckUpdateActivity.progress.installing")); // "Installing..."
                    ApplicationManager.getApplication().invokeLater(() -> {
                        try {
                            installPluginZipAfterRestart(zipFile);

                            int result = Messages.showYesNoDialog(project,
                                    BapBundle.message("activity.CheckUpdateActivity.dialog.restart_msg"), // "插件更新已下载..."
                                    BapBundle.message("title.restart_ide"), // "Restart IDE" (Common)
                                    Messages.getQuestionIcon());

                            if (result == Messages.YES) {
                                ApplicationManager.getApplication().restart();
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                            // 修改15: Error
                            Messages.showErrorDialog(project,
                                    BapBundle.message("activity.CheckUpdateActivity.error.install_failed", e.getMessage()), // "安装失败: "
                                    BapBundle.message("title.update_error")); // Common
                        }
                    });

                } catch (Exception e) {
                    e.printStackTrace();
                    ApplicationManager.getApplication().invokeLater(() ->
                            // 修改16: Error
                            Messages.showErrorDialog(project,
                                    BapBundle.message("activity.CheckUpdateActivity.error.download_failed", e.getMessage()), // "下载失败: "
                                    BapBundle.message("title.update_error"))); // Common
                }
            }
        });
    }

    /**
     * 核心修改：全面适配新旧版 API
     */
    private static void installPluginZipAfterRestart(File pluginZip) throws Exception {
        Class<?> installer = Class.forName("com.intellij.ide.plugins.PluginInstaller");

        // 1. 获取插件描述符
        Path zipPath = pluginZip.toPath();
        IdeaPluginDescriptor descriptor = PluginDescriptorLoader.loadDescriptorFromArtifact(zipPath, null);
        if (descriptor == null) {
            throw new IOException(BapBundle.message("activity.CheckUpdateActivity.error.descriptor_io", pluginZip.getAbsolutePath())); // "无法解析插件描述符..."
        }

        // 2. 尝试查找已安装的旧路径
        File oldFile = null;
        Path oldPath = null;
        IdeaPluginDescriptor installed = PluginManagerCore.getPlugin(descriptor.getPluginId());
        if (installed != null) {
            oldPath = installed.getPluginPath();
            if (oldPath != null) oldFile = oldPath.toFile();
        }

        // --- 方案 A: 2024.1+ 新版 API (Path 参数) ---
        // installAfterRestart(IdeaPluginDescriptor, Path, Path, boolean)
        try {
            Method m = installer.getMethod("installAfterRestart", IdeaPluginDescriptor.class, Path.class, Path.class, boolean.class);
            m.invoke(null, descriptor, zipPath, oldPath, true);
            return;
        } catch (NoSuchMethodException ignored) {}

        // --- 方案 B: 2020.3 - 2023.x 中间版本 API (File 参数, 4参数) ---
        // installAfterRestart(File, boolean, File, IdeaPluginDescriptor)
        try {
            Method m = installer.getMethod("installAfterRestart", File.class, boolean.class, File.class, IdeaPluginDescriptor.class);
            m.invoke(null, pluginZip, true, oldFile, descriptor);
            return;
        } catch (NoSuchMethodException ignored) {}

        // --- 方案 C: 古老版本 (File 参数, 2参数) ---
        // installAfterRestart(File, boolean)
        try {
            Method m = installer.getMethod("installAfterRestart", File.class, boolean.class);
            m.invoke(null, pluginZip, true);
            return;
        } catch (NoSuchMethodException ignored) {}

        throw new UnsupportedOperationException(BapBundle.message("activity.CheckUpdateActivity.error.api_unsupported")); // "当前 IDE 版本不支持..."
    }

    // ... (RepoEntry, parseRepoEntry, extractAttr, normalizeVersion, compareVersion, safeInt, downloadToTemp 保持不变) ...

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
        // 去掉 CDATA 外壳（有就剥）
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

    private static File downloadToTemp(String url, String fileName) throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "bap-plugin-update");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File out = new File(dir, fileName);
        Files.deleteIfExists(out.toPath());

        HttpRequests.request(url).connect(request -> {
            try (InputStream in = request.getInputStream();
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) >= 0) {
                    fos.write(buf, 0, r);
                }
            }
            return null;
        });

        if (!out.exists() || out.length() < 1024) {
            throw new IllegalStateException(BapBundle.message("activity.CheckUpdateActivity.error.file_small", out.getAbsolutePath())); // "下载失败或文件过小..."
        }
        return out;
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