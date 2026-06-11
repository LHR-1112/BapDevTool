package com.bap.dev.ui;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer; // 引入
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public class BapToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        BapChangesTreePanel panel = new BapChangesTreePanel(project);
        ContentFactory contentFactory = ContentFactory.getInstance();
        Content content = contentFactory.createContent(panel, "", false);
        toolWindow.getContentManager().addContent(content);

        // --- 🔴 修复点：将 Panel 的销毁绑定到 Content 上 ---
        // 当 Content 被关闭/移除时，panel.dispose() 会被调用，从而断开消息总线连接
        content.setDisposer(panel);
    }
}