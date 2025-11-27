package com.bap.dev.listener;

import com.bap.dev.handler.ProjectRefresher;
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

    // 1. 添加日志记录器，方便在 IDEA 的 "Run" 或 "Debug" 控制台看输出
    private static final Logger LOG = Logger.getInstance(BapAutoRefreshListener.class);

    private final Project project;
    private final Alarm debounceAlarm;

    public BapAutoRefreshListener(Project project) {
        this.project = project;
        // 修正点：使用 SWING_THREAD (即 UI 线程/EDT)
        this.debounceAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, project);
    }

    @Override
    public void after(@NotNull List<? extends VFileEvent> events) {
        Set<VirtualFile> modulesToRefresh = new HashSet<>();

        for (VFileEvent event : events) {
            // 3. 只要不是内容修改事件，通常忽略（防止文件属性变化也触发）
            if (!(event instanceof VFileContentChangeEvent)) {
                continue;
            }

            VirtualFile file = event.getFile();
            if (file == null || !file.isValid()) continue;

            // LOG.info("检测到文件变化: " + file.getPath()); // 调试时可开启

            VirtualFile moduleRoot = BapUtils.findModuleRoot(file);
            if (moduleRoot != null) {
                LOG.info("找到所属模块，准备刷新: " + moduleRoot.getName());
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
            // 4. 再次确认 Project 没被关闭
            if (project.isDisposed()) return;

            LOG.info(">>> 执行防抖后的自动刷新 <<<");

            ProjectRefresher refresher = new ProjectRefresher(project);
            for (VirtualFile moduleRoot : modules) {
                // 必须放在 try-catch 里，防止一个错误导致后续都不执行
                try {
                    refresher.refreshModule(moduleRoot);
                } catch (Exception e) {
                    LOG.error("自动刷新模块失败: " + moduleRoot.getName(), e);
                }
            }
        }, 500);
    }
}