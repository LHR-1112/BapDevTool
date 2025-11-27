package com.bap.dev.activity;

import com.bap.dev.handler.ProjectRefresher;
import com.bap.dev.listener.BapDocumentListener;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.intellij.openapi.startup.StartupActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// 1. 实现 ProjectActivity 接口
public class BapStartupActivity implements ProjectActivity, StartupActivity {

    // 2. 实现 execute 方法。
    // 注意：因为这是 Kotlin 的 suspend 函数，Java 中需要多接收一个 Continuation 参数，并返回 Object
    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        // --- 你的原有逻辑开始 ---
        BapDocumentListener listener = new BapDocumentListener(project);

        // 注册监听器
        EditorFactory.getInstance().getEventMulticaster().addDocumentListener(listener, project);
        // --- 你的原有逻辑结束 ---

        // 3. 必须返回 Kotlin 的 Unit.INSTANCE
        return Unit.INSTANCE;
    }

    @Override
    public void runActivity(@NotNull Project project) {
        // 启动后台任务刷新所有模块
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Refreshing Bap Modules...", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                // 调用 ProjectRefresher 的全量刷新方法
                new ProjectRefresher(project).refreshAllModules();
            }
        });
    }
}