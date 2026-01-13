package com.bap.dev.listener;

import com.bap.dev.handler.ProjectRefresher;
import com.bap.dev.settings.BapSettingsState; // 引入配置类
import com.bap.dev.util.BapUtils;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.util.Alarm;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class BapAutoRefreshListener implements BulkFileListener {

    private static final Logger LOG = Logger.getInstance(BapAutoRefreshListener.class);

    private final Project project;
    private final Alarm debounceAlarm;

    public BapAutoRefreshListener(Project project) {
        this.project = project;
        this.debounceAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, project);
    }

    @Override
    public void after(@NotNull List<? extends VFileEvent> events) {
        // --- 🔴 核心检查：如果开关未开启，直接返回 ---
        if (!BapSettingsState.getInstance().autoRefresh) {
            return;
        }
        // ----------------------------------------

        Set<VirtualFile> modulesToRefresh = new HashSet<>();

        for (VFileEvent event : events) {
            if (!(event instanceof VFileContentChangeEvent)) {
                continue;
            }

            VirtualFile file = event.getFile();
            if (file == null || !file.isValid()) continue;

            // 辅助方法: 从文件向上查找模块根目录(包含.develop)
            // 注意：这里假设你有 BapUtils.findModuleRoot 方法，如果没有，请直接把方法体复制进来
            VirtualFile moduleRoot = BapUtils.findModuleRoot(file);

            if (moduleRoot != null) {
                LOG.info("检测到变更，准备自动刷新: " + moduleRoot.getName());
                modulesToRefresh.add(moduleRoot);
            }
        }

        if (!modulesToRefresh.isEmpty()) {
            scheduleRefresh(modulesToRefresh);
        }
    }

    private void scheduleRefresh(Set<VirtualFile> modules) {
        debounceAlarm.cancelAllRequests();
        debounceAlarm.addRequest(() -> {
            if (project.isDisposed()) return;

            // 二次检查：防止在防抖期间用户关闭了开关
            if (!BapSettingsState.getInstance().autoRefresh) return;

            LOG.info(">>> 执行自动刷新 <<<");

            ProjectRefresher refresher = new ProjectRefresher(project);
            for (VirtualFile moduleRoot : modules) {
                try {
                    refresher.refreshModule(moduleRoot);
                } catch (Exception e) {
                    LOG.warn("自动刷新模块失败: " + moduleRoot.getName(), e);
                }
            }
        }, 1000); // 设置 1秒 防抖延迟，避免频繁触发
    }
}