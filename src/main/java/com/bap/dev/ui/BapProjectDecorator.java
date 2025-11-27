package com.bap.dev.ui;

import com.bap.dev.service.BapFileStatus;
import com.bap.dev.service.BapFileStatusService;
import com.intellij.ide.projectView.PresentationData;
import com.intellij.ide.projectView.ProjectViewNode;
import com.intellij.ide.projectView.ProjectViewNodeDecorator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleTextAttributes;

public class BapProjectDecorator implements ProjectViewNodeDecorator {

    @Override
    public void decorate(ProjectViewNode<?> node, PresentationData data) {
        Project project = node.getProject();
        VirtualFile file = node.getVirtualFile();
        if (project == null || file == null) return;

        BapFileStatusService statusService = BapFileStatusService.getInstance(project);
        BapFileStatus status = statusService.getStatus(file);

        if (status == BapFileStatus.NORMAL) return;

        data.clearText();
        SimpleTextAttributes attr;
        String suffix;

        switch (status) {
            case MODIFIED: // 黄色 M
                attr = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.YELLOW);
                suffix = "M";
                break;
            case ADDED:    // 蓝色 A
                attr = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.BLUE);
                suffix = "A";
                break;
            case DELETED_LOCALLY: // 红色 D
                attr = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.RED);
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