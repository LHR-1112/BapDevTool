package com.bap.dev.action;

import bap.java.CJavaConst;
import com.bap.dev.handler.AdminToolLauncher;
import com.bap.dev.i18n.BapBundle;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

public class OpenAdminToolAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile selectedFile = e.getData(CommonDataKeys.VIRTUAL_FILE);

        if (project == null || selectedFile == null) return;

        // 查找模块根目录
        VirtualFile moduleRoot = findModuleRoot(selectedFile);
        if (moduleRoot == null) {
            Messages.showWarningDialog(
                    BapBundle.message("error.develop_not_found"),
                    BapBundle.message("notification.error_title")
            );
            return;
        }

        // TODO 这里暂时用原来的方法打开BapMainFrame，后面要换成在Idea内部打开
        // 启动管理工具
        AdminToolLauncher.launch(project, moduleRoot);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        e.getPresentation().setEnabledAndVisible(file != null);
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
}