package com.bap.dev.action;

import bap.java.CJavaConst;
import com.bap.dev.i18n.BapBundle;
import com.bap.dev.service.BapConnectionManager;
import com.bap.dev.service.DtoSyncService;
import com.bap.dev.service.DtoSyncService.DtoSyncEntry;
import com.bap.dev.service.DtoSyncService.DtoSyncStatus;
import com.bap.dev.service.MetadataRpcClient;
import com.bap.dev.ui.DtoSyncDialog;
import com.bap.dev.ui.MetadataDomainDialog;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import panelxpro.metadata.dto.DomainInfoDto;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class SyncPanelDtoAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(SyncPanelDtoAction.class);


    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null || file == null) return;

        // 1. 查找模块根目录
        VirtualFile moduleRoot = findModuleRoot(file);
        if (moduleRoot == null) {
            Messages.showWarningDialog(
                    BapBundle.message("warning.no_develop_config"),
                    BapBundle.message("notification.error_title"));
            return;
        }

        // 2. 读取 .develop 配置
        try {
            File confFile = new File(moduleRoot.getPath(), CJavaConst.PROJECT_DEVELOP_CONF_FILE);
            String content = Files.readString(confFile.toPath());
            String uri = extractAttr(content, "Uri");
            String user = extractAttr(content, "User");
            String pwd = extractAttr(content, "Password");
            String domainCode = extractAttr(content, "MetadataDomain");

            if (uri == null) {
                Messages.showErrorDialog(
                        BapBundle.message("error.config_incomplete"),
                        BapBundle.message("notification.error_title"));
                return;
            }

            // 3. 确保全局 RPC Session 已建立
            BapConnectionManager.getInstance(project).getSharedClient(uri, user, pwd);

            // 4. 创建 MetadataRpcClient 并连接
            MetadataRpcClient metadataClient = new MetadataRpcClient();
            metadataClient.connect(uri, user, pwd);

            // 5. 如果 MetadataDomain 为空，从远程获取域列表供用户选择
            if (domainCode == null || domainCode.trim().isEmpty()) {
                List<DomainInfoDto> domains;
                try {
                    domains = metadataClient.getService().listDomains();
                } catch (Exception ex) {
                    metadataClient.shutdown();
                    Messages.showErrorDialog(
                            BapBundle.message("error.metadata.fetch", ex.getMessage()),
                            BapBundle.message("notification.error_title"));
                    return;
                }

                if (domains == null || domains.isEmpty()) {
                    metadataClient.shutdown();
                    Messages.showWarningDialog(
                            BapBundle.message("dialog.domain.config.error.empty"),
                            BapBundle.message("notification.error_title"));
                    return;
                }

                MetadataDomainDialog dialog = new MetadataDomainDialog(project, domains);
                if (dialog.showAndGet()) {
                    domainCode = dialog.getDomainCode();
                    writeDomainToConfig(confFile, content, domainCode);
                } else {
                    metadataClient.shutdown();
                    return; // 用户取消
                }
            }

            // 6. 后台任务执行拉取和比对
            final String finalDomainCode = domainCode;
            ProgressManager.getInstance().run(new Task.Backgroundable(
                    project, BapBundle.message("action.SyncPanelDto.progress.title"), true) {
                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    indicator.setIndeterminate(true);
                    indicator.setText(BapBundle.message("action.SyncPanelDto.progress.fetching"));

                    try {
                        DtoSyncService syncService = new DtoSyncService();
                        List<DtoSyncEntry> allEntries = syncService.fetchAndCompare(
                                metadataClient.getService(), moduleRoot, finalDomainCode);

                        indicator.setText(BapBundle.message("action.SyncPanelDto.progress.comparing"));

                        List<DtoSyncEntry> changedEntries = allEntries.stream()
                                .filter(entry -> entry.getStatus() != DtoSyncStatus.UNCHANGED)
                                .collect(Collectors.toList());

                        List<DomainInfoDto> domainList;
                        try {
                            domainList = metadataClient.getService().listDomains();
                        } catch (Exception ignored) {
                            domainList = new ArrayList<>();
                        }
                        final List<DomainInfoDto> finalDomains = domainList;

                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (changedEntries.isEmpty()) {
                                Messages.showInfoMessage(
                                        BapBundle.message("notification.dto.sync.noChanges"),
                                        BapBundle.message("dialog.dto.sync.title"));
                                metadataClient.shutdown();
                            } else {
                                showSyncDialog(project, changedEntries, finalDomainCode,
                                        finalDomains, metadataClient, moduleRoot);
                            }
                        });
                    } catch (Exception ex) {
                        LOG.error("DTO sync failed", ex);
                        metadataClient.shutdown();
                        ApplicationManager.getApplication().invokeLater(() ->
                                Messages.showErrorDialog(
                                        BapBundle.message("error.metadata.fetch", ex.getMessage()),
                                        BapBundle.message("notification.error_title")));
                    }
                }
            });

        } catch (Exception ex) {
            Messages.showErrorDialog(
                    BapBundle.message("error.metadata.connect", ex.getMessage()),
                    BapBundle.message("notification.error_title"));
        }
    }

    private void showSyncDialog(Project project, List<DtoSyncEntry> entries,
                               String domainCode, List<DomainInfoDto> domains,
                               MetadataRpcClient metadataClient, VirtualFile moduleRoot) {
        DtoSyncDialog dialog = new DtoSyncDialog(
                project, entries, domainCode, domains, metadataClient, moduleRoot);
        try {
            if (dialog.showAndGet()) {
                List<DtoSyncEntry> selected = dialog.getSelectedEntries();
                if (!selected.isEmpty()) {
                    try {
                        int count = WriteAction.compute(() -> {
                            DtoSyncService syncService = new DtoSyncService();
                            return syncService.writeSelectedFiles(project, selected);
                        });
                        sendNotification(project,
                                BapBundle.message("dialog.dto.sync.title"),
                                BapBundle.message("notification.dto.sync.success", count),
                                NotificationType.INFORMATION);
                    } catch (Exception ex) {
                        Messages.showErrorDialog(ex.getMessage(),
                                BapBundle.message("notification.error_title"));
                    }
                }
            }
        } finally {
            metadataClient.shutdown();
        }
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

    private void writeDomainToConfig(File confFile, String currentContent, String domainCode) {
        try {
            String newAttr = " MetadataDomain=\"" + domainCode + "\"";
            String updated;
            if (currentContent.contains("MetadataDomain=\"")) {
                // 替换已有的
                updated = currentContent.replaceAll(
                        "MetadataDomain=\"[^\"]*\"", "MetadataDomain=\"" + domainCode + "\"");
            } else {
                // 在 /> 或 > 之前追加
                updated = currentContent.replaceFirst("(\\s*/?>)", newAttr + "$1");
            }
            Files.writeString(confFile.toPath(), updated);
        } catch (Exception e) {
            LOG.error("Failed to write MetadataDomain to .develop", e);
        }
    }

    private void sendNotification(Project project, String title, String content, NotificationType type) {
        Notification notification = new Notification(
                BapBundle.message("notification.group.bap"), title, content, type);
        Notifications.Bus.notify(notification, project);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        boolean enabled = file != null && findModuleRoot(file) != null;
        e.getPresentation().setEnabledAndVisible(enabled);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
