package com.bap.dev.activity;

import com.bap.dev.settings.BapSettingsState;
import com.intellij.ide.BrowserUtil;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.notification.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.ui.Messages;
import com.intellij.util.io.HttpRequests;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CheckUpdateActivity implements StartupActivity {

    private static final String PLUGIN_ID = "com.bap.dev.BapDevPlugin";

    /**
     * 指向你内部插件仓库的 plugins.xml（GitHub Pages / 内网都行）
     * 你也可以放到设置里可配置，这里先写死。
     */
    private static final String PLUGINS_XML_URL = "https://lhr-1112.github.io/BapDevTool/plugins.xml";

    /**
     * 防止每打开一个 Project 都弹一次（StartupActivity 是 per-project 的）
     */
    private static final AtomicBoolean CHECKED_THIS_SESSION = new AtomicBoolean(false);

    @Override
    public void runActivity(@NotNull Project project) {
        BapSettingsState s = BapSettingsState.getInstance();
        if (!s.checkUpdateOnStartup) return;

        // 每次启动 IDEA / 第一次打开项目才检查一次
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
                                    Messages.showErrorDialog(project, "Check failed: " + e.getMessage(), "Update Error"),
                            ModalityState.any());
                }
                e.printStackTrace();
            }
        });
    }

    private static void checkForUpdates(@Nullable Project project, boolean isManual) throws Exception {
        PluginId id = PluginId.getId(PLUGIN_ID);
        var pluginDescriptor = PluginManagerCore.getPlugin(id);

        if (pluginDescriptor == null) {
            String msg = "Error: 找不到插件描述信息! ID: " + PLUGIN_ID;
            if (isManual) {
                ApplicationManager.getApplication().invokeLater(() ->
                                Messages.showErrorDialog(project, msg, "Error"),
                        ModalityState.any());
            }
            return;
        }

        String currentVersion = pluginDescriptor.getVersion();

        // 1) 从 plugins.xml 获取“最新版本 + 下载链接”
        String pluginsXml = HttpRequests.request(PLUGINS_XML_URL).readString();
        RepoEntry latest = parseRepoEntry(pluginsXml, PLUGIN_ID);

        if (latest == null || latest.version == null || latest.downloadUrl == null) {
            if (isManual) {
                ApplicationManager.getApplication().invokeLater(() ->
                                Messages.showErrorDialog(project, "无法从 plugins.xml 解析版本/下载地址", "Update Error"),
                        ModalityState.any());
            }
            return;
        }

        String cleanCurrent = normalizeVersion(currentVersion);
        String cleanLatest = normalizeVersion(latest.version);

        BapSettingsState s = BapSettingsState.getInstance();
        if (s.ignoredVersion != null && !s.ignoredVersion.isBlank()
                && normalizeVersion(s.ignoredVersion).equals(cleanLatest)) {
            // 用户选择忽略这个版本：直接不提示
            return;
        }

        if (compareVersion(cleanLatest, cleanCurrent) > 0) {
            ApplicationManager.getApplication().invokeLater(() ->
                            showUpdateNotification(project, currentVersion, latest),
                    ModalityState.any());
        } else if (isManual) {
            ApplicationManager.getApplication().invokeLater(() ->
                            Messages.showInfoMessage(project,
                                    "当前版本 (" + currentVersion + ") 已是最新。", "Check Update"),
                    ModalityState.any());
        }
    }

    private static void showUpdateNotification(@Nullable Project project, String current, RepoEntry latest) {
        NotificationGroup group = NotificationGroupManager.getInstance()
                .getNotificationGroup("Cloud Project Download");
        if (group == null) return;

        String content = String.format(
                "检测到 Bap Plugin 新版本: <b>%s</b> (当前: %s)<br/>",
                latest.version, current
        );

        Notification n = group.createNotification("Bap Plugin Update", content, NotificationType.INFORMATION);

        // ✅ 立即更新（自动下载 + 安装 + 提示重启）
        n.addAction(NotificationAction.createSimple("立即更新", () -> {
            n.expire();
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    File zip = downloadToTemp(latest.downloadUrl, "BapDevTool-" + latest.version + ".zip");
                    installPluginZipAfterRestart(zip);
                    ApplicationManager.getApplication().invokeLater(() ->
                                    Messages.showInfoMessage(project,
                                            "插件已下载并安排在重启后安装。\n请重启 IDEA 完成更新。", "Update Ready"),
                            ModalityState.any());
                } catch (Exception ex) {
                    ApplicationManager.getApplication().invokeLater(() ->
                                    Messages.showErrorDialog(project,
                                            "自动更新失败: " + ex.getMessage(), "Update Error"),
                            ModalityState.any());
                }
            });
        }));


        n.addAction(NotificationAction.createSimple("GitHub下载", () -> {
            if (latest.backupUrl != null && !latest.backupUrl.isBlank()) {
                BrowserUtil.browse(latest.backupUrl);
            } else {
                Messages.showInfoMessage(project, "未配置 GitHub 备份下载链接。", "Info");
            }
        }));

        // ✅ 忽略此版本（以后不再提示这个版本）
        n.addAction(NotificationAction.createSimple("忽略此版本", () -> {
            BapSettingsState.getInstance().ignoredVersion = latest.version;
            n.expire();
        }));

        n.notify(project);
    }

    /**
     * 从 plugins.xml 中解析指定 pluginId 的 version 和 url
     * 兼容 <plugin ... url="..."> 与 <download-url>...</download-url>
     */
    private static RepoEntry parseRepoEntry(String xml, String pluginId) {
        // 1) 找到 <plugin ... id="xxx" ...> ... </plugin>
        Pattern block = Pattern.compile(
                "<plugin\\b[^>]*\\bid\\s*=\\s*\"" + Pattern.quote(pluginId) + "\"[^>]*>.*?</plugin>",
                Pattern.DOTALL);
        Matcher m = block.matcher(xml);
        if (!m.find()) return null;

        String pluginBlock = m.group(0);

        // version="x.y.z"
        String version = extractAttr(pluginBlock, "version");
        // url="http..."
        String url = extractAttr(pluginBlock, "url");

        String backup = "https://github.com/LHR-1112/BapDevTool/releases/download/v" + version
                + "/BapDevTool-" + version + ".zip";

        // 或者 <download-url>...</download-url>
        if (url == null) {
            Pattern p = Pattern.compile("<download-url>\\s*([^<\\s]+)\\s*</download-url>");
            Matcher dm = p.matcher(pluginBlock);
            if (dm.find()) url = dm.group(1).trim();
        }

        if (version == null) {
            // 兜底：version 也可能在其它标签里（一般不会）
            return null;
        }
        return new RepoEntry(version.trim(),
                url == null ? null : url.trim(),
                backup);
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
            throw new IllegalStateException("下载失败或文件过小: " + out.getAbsolutePath());
        }
        return out;
    }

    /**
     * 使用 IDEA 的安装机制安排“重启后安装”
     * 用反射调用，兼容不同 IDE 版本的 API 变动。
     */
    private static void installPluginZipAfterRestart(File pluginZip) throws Exception {
        // 优先：PluginInstaller.installAfterRestart(File, boolean)
        Class<?> installer = Class.forName("com.intellij.ide.plugins.PluginInstaller");

        try {
            Method m = installer.getMethod("installAfterRestart", File.class, boolean.class);
            m.invoke(null, pluginZip, true);
            return;
        } catch (NoSuchMethodException ignored) {
            // 某些版本方法签名不同，继续兜底
        }

        // 兜底：PluginInstaller.installAfterRestart(File)
        try {
            Method m = installer.getMethod("installAfterRestart", File.class);
            m.invoke(null, pluginZip);
            return;
        } catch (NoSuchMethodException ignored) {
            // 继续兜底
        }

        throw new UnsupportedOperationException("当前 IDE 版本不支持 installAfterRestart 安装接口");
    }

    private static class RepoEntry {
        final String version;
        final String downloadUrl; // 主更新（CDN/内部仓库）
        final String backupUrl;   // 备份（GitHub）

        RepoEntry(String version, String downloadUrl, String backupUrl) {
            this.version = version;
            this.downloadUrl = downloadUrl;
            this.backupUrl = backupUrl;
        }
    }
}