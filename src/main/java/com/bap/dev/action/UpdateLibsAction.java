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
import java.util.concurrent.atomic.AtomicBoolean;
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
        LibDownloader.PendingUpdate pending = null;
        try {
            indicator.setText(BapBundle.message("progress.connecting")); // "Connecting..."
            client.connect(uri, user, pwd);

            LibDownloader downloader = new LibDownloader(client, new File(moduleRoot.getPath()));

            // 1. 扫描本地指纹 + 预检：只调轻量接口，不传输 jar 字节，先在下载前告诉用户要更新什么
            LibDownloader.LocalState local = downloader.scanLocal(indicator);
            LibDownloader.PreflightResult pre = downloader.preflight(projectUuid, local, indicator);
            if (!confirm(project, buildPreflightMessage(pre),
                    BapBundle.message("action.UpdateLibsAction.dialog.preflight_title"),   // "更新依赖"
                    BapBundle.message("action.UpdateLibsAction.button.start_download"))) { // "开始下载"
                return;
            }

            // 2. 下载到内存/临时文件，此时还没有碰工程目录
            pending = downloader.download(projectUuid, local, indicator);
            if (pending.isEmpty()) {
                sendNotification(project,
                        BapBundle.message("action.UpdateLibsAction.notification.no_update_title"),   // "无需更新"
                        BapBundle.message("action.UpdateLibsAction.notification.no_update_content")  // "云端依赖与本地一致，没有需要更新的内容。"
                );
                return;
            }

            // 3. 给出精确数量，用户确认后才覆盖工程文件
            if (!confirm(project, buildConfirmMessage(pending.getResult()),
                    BapBundle.message("action.UpdateLibsAction.dialog.confirm_title"),    // "确认更新依赖"
                    BapBundle.message("action.UpdateLibsAction.button.do_update"))) {     // "开始更新"
                return;
            }

            // 4. 落盘，并配置 IDEA 依赖
            downloader.apply(pending, indicator);
            indicator.setText(BapBundle.message("action.UpdateLibsAction.progress.configuring")); // "Configuring project structure..."
            LibConfigurator.configureLibraries(project, moduleRoot);

            sendNotification(project,
                    BapBundle.message("action.UpdateLibsAction.notification.success_title"),   // "更新成功"
                    buildSuccessMessage(pending.getResult())
            );

        } catch (Exception e) {
            e.printStackTrace();
            showError(BapBundle.message("action.UpdateLibsAction.error.update_failed", e.getMessage())); // "更新失败: " + e.getMessage()
        } finally {
            if (pending != null) pending.cleanup();
            client.shutdown();
        }
    }

    /** 预检提示：下载前让用户知道大概要更新什么 */
    private String buildPreflightMessage(LibDownloader.PreflightResult pre) {
        StringBuilder sb = new StringBuilder();
        if (pre.projectScanned && (pre.projectJarsToUpdate > 0 || pre.projectJarsToDelete > 0)) {
            sb.append(BapBundle.message("action.UpdateLibsAction.dialog.preflight.project",   // "  • 项目依赖：{0} 个需要更新，{1} 个本地多余将被删除"
                    pre.projectJarsToUpdate, pre.projectJarsToDelete)).append("\n");
        }
        if (pre.daoWillUpdate) {
            sb.append(BapBundle.message("action.UpdateLibsAction.dialog.preflight.dao")).append("\n"); // "  • DAO 模型：需要更新"
        }
        if (pre.pluginChanged) {
            sb.append(BapBundle.message("action.UpdateLibsAction.dialog.preflight.plugin")).append("\n"); // "  • 插件包：有变更（服务端只提供整体版本号，个数需下载后确定）"
        }
        if (pre.platformScanned && (pre.platformNewFiles > 0 || pre.platformMissingFiles > 0 || pre.platformSameNameFiles > 0)) {
            sb.append(BapBundle.message("action.UpdateLibsAction.dialog.preflight.platform",   // "  • 平台依赖：新增 {0} 个，本地多余 {1} 个；同名 {2} 个内容是否变化需下载后确定"
                    pre.platformNewFiles, pre.platformMissingFiles, pre.platformSameNameFiles)).append("\n");
        }
        if (sb.length() == 0) {
            sb.append(BapBundle.message("action.UpdateLibsAction.dialog.preflight.none")); // "  • 未预检到变更"
        }
        return BapBundle.message("action.UpdateLibsAction.dialog.preflight_msg", sb.toString().trim());
    }

    /** 落盘前的精确确认 */
    private String buildConfirmMessage(LibDownloader.UpdateResult r) {
        String openSource = r.openSourceUpdated
                ? BapBundle.message("action.UpdateLibsAction.dialog.open_source_suffix")   // "开源包也将同步更新。"
                : "";
        return BapBundle.message("action.UpdateLibsAction.dialog.confirm_msg",   // "本次实际需要更新 {0} 个 Jar 包（平台 {1} / 插件 {2} / 项目 {3} / DAO {4}）。{5}\n\n确认后将覆盖工程内的依赖文件，是否继续？"
                r.totalJars(), r.platform, r.plugin, r.project, r.dao, openSource);
    }

    private String buildSuccessMessage(LibDownloader.UpdateResult r) {
        String content = BapBundle.message("action.UpdateLibsAction.notification.success_content",
                r.totalJars(), r.platform, r.plugin, r.project, r.dao);
        if (r.openSourceUpdated) {
            content += BapBundle.message("action.UpdateLibsAction.notification.open_source_updated");
        }
        return content;
    }

    /** 后台任务线程里弹确认框，必须切回 EDT */
    private boolean confirm(Project project, String message, String title, String okText) {
        AtomicBoolean ok = new AtomicBoolean(false);
        ApplicationManager.getApplication().invokeAndWait(() ->
                ok.set(Messages.showOkCancelDialog(project, message, title, okText,
                        BapBundle.message("button.cancel"), Messages.getWarningIcon()) == Messages.OK));
        return ok.get();
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
                BapBundle.message("notification.group.bap"), // "Cloud Project Download"
                title, content, NotificationType.INFORMATION);
        Notifications.Bus.notify(notification, project);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}