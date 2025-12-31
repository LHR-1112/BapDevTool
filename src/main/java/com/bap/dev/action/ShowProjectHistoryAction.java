package com.bap.dev.action;

import bap.java.CJavaConst;
import bap.md.ver.VersionNode;
import com.bap.dev.BapRpcClient;
import com.bap.dev.i18n.BapBundle;
import com.bap.dev.service.BapConnectionManager;
import com.bap.dev.ui.ProjectHistoryDialog;
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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ShowProjectHistoryAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile selectedFile = e.getData(CommonDataKeys.VIRTUAL_FILE);

        if (project == null || selectedFile == null) return;

        File confFile = new File(selectedFile.getPath(), CJavaConst.PROJECT_DEVELOP_CONF_FILE);
        if (!confFile.exists()) {
            Messages.showWarningDialog(
                    BapBundle.message("action.ShowProjectHistoryAction.warning.select_root"), // "请选中 Bap 模块的根目录..."
                    BapBundle.message("title.tip")                                            // "提示" (Moved to common)
            );
            return;
        }

        ProgressManager.getInstance().run(new Task.Backgroundable(project, BapBundle.message("action.ShowProjectHistoryAction.progress.loading"), true) { // "Loading Project History..."
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                loadAndShowHistory(project, selectedFile, indicator);
            }
        });
    }

    private void loadAndShowHistory(Project project, VirtualFile moduleRoot, ProgressIndicator indicator) {
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
            showError(BapBundle.message("error.config_incomplete")); // "配置文件信息不全"
            return;
        }

        BapRpcClient client = BapConnectionManager.getInstance(project).getSharedClient(uri, user, pwd);
        try {
            indicator.setIndeterminate(true);
            indicator.setText(BapBundle.message("progress.connecting")); // "Connecting..."
            client.connect(uri, user, pwd);

            indicator.setText(BapBundle.message("action.ShowProjectHistoryAction.progress.fetching")); // "Fetching project version list..."

            // 🔴 核心修改：改为直接查询项目版本列表
            List<VersionNode> versionList = client.getService().queryVersionList(projectUuid);

            final String fUri = uri;
            final String fUser = user;
            final String fPwd = pwd;
            final String fUuid = projectUuid;

            ApplicationManager.getApplication().invokeLater(() -> {
                if (versionList == null || versionList.isEmpty()) {
                    Messages.showInfoMessage(
                            BapBundle.message("action.ShowProjectHistoryAction.info.no_records"),      // "未找到任何历史记录。"
                            BapBundle.message("action.ShowProjectHistoryAction.title.project_history") // "Project History"
                    );
                } else {
                    // 传入 List<VersionNode> 而不是 Map
                    new ProjectHistoryDialog(project, versionList, fUuid, fUri, fUser, fPwd).show();
                }
            });

        } catch (Exception e) {
            e.printStackTrace();
            showError(BapBundle.message("action.ShowProjectHistoryAction.error.fetch_failed", e.getMessage())); // "获取历史失败: " + e.getMessage()
        } finally {
            client.shutdown();
        }
    }

    private String extractAttr(String xml, String attr) {
        Pattern p = Pattern.compile(attr + "=\"([^\"]*)\"");
        Matcher m = p.matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    private void showError(String msg) {
        ApplicationManager.getApplication().invokeLater(() -> Messages.showErrorDialog(msg, BapBundle.message("title.history_error")));
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        boolean isModuleRoot = file != null && file.isDirectory() && file.findChild(CJavaConst.PROJECT_DEVELOP_CONF_FILE) != null;
        e.getPresentation().setEnabledAndVisible(isModuleRoot);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}