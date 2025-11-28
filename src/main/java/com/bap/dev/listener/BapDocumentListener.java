package com.bap.dev.listener;

import com.bap.dev.handler.ProjectRefresher;
import com.bap.dev.settings.BapSettingsState;
import com.bap.dev.util.BapUtils;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Alarm;
import org.jetbrains.annotations.NotNull;

public class BapDocumentListener implements DocumentListener {

    private final Project project;
    private final Alarm debounceAlarm;

    public BapDocumentListener(Project project) {
        this.project = project;
        // 使用 SWING_THREAD 确保在 UI 线程执行 (DocumentListener 本身就在 UI 线程触发)
        this.debounceAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, project);
    }

    @Override
    public void documentChanged(@NotNull DocumentEvent event) {
        // --- 🔴 核心检查：如果开关未开启，直接返回 ---
        if (!BapSettingsState.getInstance().autoRefresh) {
            return;
        }

        // 1. 获取当前被修改的文档
        Document document = event.getDocument();

        // 2. 通过文档找到对应的文件
        VirtualFile file = FileDocumentManager.getInstance().getFile(document);

        // 过滤掉无效文件、或者不是项目里的文件
        if (file == null || !file.isValid()) return;

        // 3. 查找是否属于 Bap 模块
        VirtualFile moduleRoot = BapUtils.findModuleRoot(file);

        if (moduleRoot != null) {
            // 4. 触发防抖刷新
            scheduleRefresh(moduleRoot);
        }
    }

    private void scheduleRefresh(VirtualFile moduleRoot) {
        // 取消之前的请求（如果用户一直在打字，就一直重置计时器）
        debounceAlarm.cancelAllRequests();

        // 延迟 1000ms (1秒) 后执行。
        // 时间太短会导致用户还在思考时就刷新，太长则反应迟钝。建议 500ms - 1500ms。
        debounceAlarm.addRequest(() -> {
            if (project.isDisposed()) return;

            // 二次检查：防止在防抖期间用户关闭了开关
            if (!BapSettingsState.getInstance().autoRefresh) return;

            // 执行刷新逻辑
            // 注意：此时文件内容还在内存中，没有保存到磁盘！
            // 如果 ProjectRefresher 读取的是 java.io.File (磁盘)，它读到的还是旧的。
            // 如果 ProjectRefresher 读取的是 PSI 或 VirtualFile，它能读到新的。
            try {
                new ProjectRefresher(project).refreshModule(moduleRoot);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 1000);
    }
}