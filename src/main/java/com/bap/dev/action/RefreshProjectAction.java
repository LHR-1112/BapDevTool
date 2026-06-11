package com.bap.dev.action;

import bap.java.CJavaConst; // 引入常量定义 .develop 文件名
import com.bap.dev.handler.ProjectRefresher;
import com.bap.dev.i18n.BapBundle;
import com.bap.dev.util.BapUtils;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

public class RefreshProjectAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile selectedFile = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null || selectedFile == null) return;

        // 使用工具类
        VirtualFile moduleRoot = BapUtils.findModuleRoot(selectedFile);

        if (moduleRoot != null) {
            ProgressManager.getInstance().run(new Task.Backgroundable(project, BapBundle.message("progress.refresh_module"), true) { // "Refreshing Bap Module..."
                //                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    // 🔴 修改：传入 false，表示这是手动操作，需要弹窗报错
                    new ProjectRefresher(project).refreshModule(moduleRoot, false);
                }
            });
        } else {
            Messages.showWarningDialog(
                    BapBundle.message("warning.no_develop_config"), // "未找到模块配置文件 (.develop)。"
                    BapBundle.message("action.RefreshProjectAction.title.cannot_refresh")      // "无法刷新"
            );
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        // 只要选中了文件，就允许点击，具体的有效性检查放在点击后做
        e.getPresentation().setEnabledAndVisible(file != null);
    }

    // --- 🔴 核心修复：指定 update 方法在后台线程运行 ---
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}