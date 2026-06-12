package com.bap.dev.action;

import bap.java.CJavaConst;
import com.bap.dev.i18n.BapBundle;
import com.bap.dev.service.BapConnectionManager;
import com.bap.dev.service.MetadataRpcClient;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;
import panelxpro.metadata.dto.RegisterFunctionResult;

import java.io.File;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RegisterClassRuleFunctionsAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(RegisterClassRuleFunctionsAction.class);

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null || file == null) return;

        VirtualFile moduleRoot = findModuleRoot(file);
        if (moduleRoot == null) {
            Messages.showWarningDialog(
                    BapBundle.message("warning.no_develop_config"),
                    BapBundle.message("notification.error_title"));
            return;
        }

        try {
            File confFile = new File(moduleRoot.getPath(), CJavaConst.PROJECT_DEVELOP_CONF_FILE);
            String content = Files.readString(confFile.toPath());
            String uri = extractAttr(content, "Uri");
            String user = extractAttr(content, "User");
            String pwd = extractAttr(content, "Password");
            String domainCode = extractAttr(content, "MetadataDomain");

            if (uri == null || domainCode == null || domainCode.trim().isEmpty()) {
                Messages.showErrorDialog(
                        BapBundle.message("error.config_incomplete"),
                        BapBundle.message("notification.error_title"));
                return;
            }

            PsiJavaFile psiFile = (PsiJavaFile) PsiManager.getInstance(project).findFile(file);
            if (psiFile == null) return;
            PsiClass[] classes = psiFile.getClasses();
            if (classes.length == 0) return;
            String className = classes[0].getQualifiedName();
            if (className == null) return;

            BapConnectionManager.getInstance(project).getSharedClient(uri, user, pwd);

            MetadataRpcClient metadataClient = new MetadataRpcClient();
            metadataClient.connect(uri, user, pwd);

            final String finalClassName = className;
            final String finalDomainCode = domainCode;
            ProgressManager.getInstance().run(new Task.Backgroundable(
                    project, BapBundle.message("action.registerFunction.progress.title"), true) {
                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    indicator.setIndeterminate(true);
                    try {
                        RegisterFunctionResult result = metadataClient.getService()
                                .registerFunctions(finalDomainCode, finalClassName);
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (result.isSuccess()) {
                                int count = result.getFunctions() != null ? result.getFunctions().size() : 0;
                                sendNotification(project,
                                        BapBundle.message("action.registerFunction.notification.title"),
                                        BapBundle.message("action.registerFunction.notification.allSuccess",
                                                count, finalClassName),
                                        NotificationType.INFORMATION);
                            } else {
                                Messages.showErrorDialog(
                                        BapBundle.message("error.registerFunction.failed", result.getMessage()),
                                        BapBundle.message("notification.error_title"));
                            }
                        });
                    } catch (Exception ex) {
                        LOG.error("Register functions failed", ex);
                        ApplicationManager.getApplication().invokeLater(() ->
                                Messages.showErrorDialog(
                                        BapBundle.message("error.registerFunction.failed", ex.getMessage()),
                                        BapBundle.message("notification.error_title")));
                    } finally {
                        metadataClient.shutdown();
                    }
                }
            });

        } catch (Exception ex) {
            Messages.showErrorDialog(
                    BapBundle.message("error.metadata.connect", ex.getMessage()),
                    BapBundle.message("notification.error_title"));
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        boolean enabled = file != null && "java".equalsIgnoreCase(file.getExtension())
                && findModuleRoot(file) != null;
        e.getPresentation().setEnabledAndVisible(enabled);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
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

    private void sendNotification(Project project, String title, String content, NotificationType type) {
        Notification notification = new Notification(
                BapBundle.message("notification.group.bap"), title, content, type);
        Notifications.Bus.notify(notification, project);
    }
}
