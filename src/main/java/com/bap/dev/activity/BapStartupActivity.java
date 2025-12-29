package com.bap.dev.activity;

import com.bap.dev.handler.ProjectRefresher;
import com.bap.dev.listener.BapDocumentListener;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import org.jetbrains.annotations.NotNull;

// 使用旧版接口，兼容性好，代码简单
public class BapStartupActivity implements StartupActivity {

    @Override
    public void runActivity(@NotNull Project project) {
        // 1. 注册文档监听器
        BapDocumentListener listener = new BapDocumentListener(project);
        EditorFactory.getInstance().getEventMulticaster().addDocumentListener(listener, project);

        // 2. 启动后台任务刷新所有模块
        // FIXME 这里因为在项目刷新时添加了无法连接的报错，会导致一些废弃的项目在打开时疯狂报无法连接，所以先注释掉
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Refreshing Bap Modules...", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                new ProjectRefresher(project).refreshAllModules();
            }
        });
    }
}