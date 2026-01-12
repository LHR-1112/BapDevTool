package com.bap.dev.action;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

public class CommitFileAndPublishAction extends CommitFileAction {

    @Override
    public void update(@NotNull AnActionEvent e) {
        // 复用父类的检查逻辑 (是否有选文件等)
        super.update(e);
        // 可以根据需要修改显示的文本，或者在 plugin.xml 里配置
//        e.getPresentation().setText("Commit and Publish");
    }

    @Override
    protected void onSuccess(Project project, VirtualFile[] files, VirtualFile moduleRoot) {
        // 1. 先执行提交成功的逻辑 (刷新、通知等)
        super.onSuccess(project, files, moduleRoot);

        // 2. 🔴 修改：启动后台线程等待 1 秒，然后回到 UI 线程执行发布
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                Thread.sleep(1000); // 间隔 1 秒
            } catch (InterruptedException ignored) {}

            ApplicationManager.getApplication().invokeLater(() -> triggerPublishAction(project, moduleRoot));
        });
    }

    // 辅助：手动触发发布 Action
    protected void triggerPublishAction(Project project, VirtualFile moduleRoot) {
        AnAction publishAction = ActionManager.getInstance().getAction("com.bap.dev.action.PublishProjectAction");
        if (publishAction != null) {
            // 构造一个 DataContext，伪装成用户选中了当前模块的根目录
            // 这样 PublishAction 就能正确识别要发布的模块
            DataContext dataContext = dataId -> {
                if (CommonDataKeys.PROJECT.is(dataId)) return project;
                if (CommonDataKeys.VIRTUAL_FILE.is(dataId)) return moduleRoot;
                return null;
            };

            AnActionEvent event = AnActionEvent.createFromAnAction(publishAction, null, ActionPlaces.UNKNOWN, dataContext);
            publishAction.actionPerformed(event);
        }
    }
}