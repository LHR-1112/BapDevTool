package com.bap.dev.ui;

import com.bap.dev.service.BapFileStatus;
import com.bap.dev.service.BapFileStatusService;
import com.bap.dev.settings.BapSettingsState; // 引入配置
import com.intellij.ide.projectView.PresentationData;
import com.intellij.ide.projectView.ProjectViewNode;
import com.intellij.ide.projectView.ProjectViewNodeDecorator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.SimpleTextAttributes;

import java.awt.Color;

public class BapProjectDecorator implements ProjectViewNodeDecorator {

    @Override
    public void decorate(ProjectViewNode<?> node, PresentationData data) {
        // 🔴 核心修改：检查配置开关
        if (!BapSettingsState.getInstance().showProjectTreeStatus) {
            return; // 如果开关关闭，直接退出，不改变原有样式
        }

        Project project = node.getProject();
        VirtualFile file = node.getVirtualFile();
        if (project == null || file == null) return;

        BapFileStatusService statusService = BapFileStatusService.getInstance(project);
        BapFileStatus status = statusService.getStatus(file);

        if (status == BapFileStatus.NORMAL) return;

        data.clearText();
        SimpleTextAttributes attr;
        String suffix;

        // --- 🔴 从配置中获取颜色 ---
        BapSettingsState settings = BapSettingsState.getInstance();
        Color modColor = settings.getModifiedColorObj();
        Color addColor = settings.getAddedColorObj();
        Color delColor = settings.getDeletedColorObj();

        switch (status) {
            case MODIFIED:
                attr = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, modColor);
                suffix = "M";
                break;
            case ADDED:
                attr = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, addColor);
                suffix = "A";
                break;
            case DELETED_LOCALLY:
                attr = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, delColor);
                suffix = "D";
                break;
            default:
                attr = SimpleTextAttributes.REGULAR_ATTRIBUTES;
                suffix = "";
        }

        data.addText(file.getName(), attr);
        data.setLocationString(suffix);
    }
}