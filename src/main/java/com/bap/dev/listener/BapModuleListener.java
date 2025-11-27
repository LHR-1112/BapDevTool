package com.bap.dev.listener;

import bap.java.CJavaConst;
import com.bap.dev.handler.ProjectRefresher;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.ModuleListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class BapModuleListener implements ModuleListener {

    @Override
    public void moduleAdded(@NotNull Project project, @NotNull Module module) {
        // 当新模块添加时触发
        VirtualFile[] contentRoots = ModuleRootManager.getInstance(module).getContentRoots();

        for (VirtualFile root : contentRoots) {
            // 检查是否为 Bap 模块 (有配置文件)
            if (root.findChild(CJavaConst.PROJECT_DEVELOP_CONF_FILE) != null) {

                // 启动后台任务刷新该模块
                ProgressManager.getInstance().run(new Task.Backgroundable(project, "Refreshing New Module...", true) {
                    @Override
                    public void run(@NotNull ProgressIndicator indicator) {
                        new ProjectRefresher(project).refreshModule(root);
                    }
                });
                break;
            }
        }
    }
}