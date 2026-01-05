package com.bap.dev.ui;

import bap.java.CJavaProjectDto;
import com.bap.dev.i18n.BapBundle;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.List;

public class ProjectDownloadDialog extends DialogWrapper {

    private final ComboBox<CJavaProjectDto> projectCombo = new ComboBox<>();

    public ProjectDownloadDialog(@Nullable Project project, List<CJavaProjectDto> projects) {
        super(project);
        setTitle(BapBundle.message("ui.ProjectDownloadDialog.title")); // "Select Project to Download"

        // 配置渲染器：显示 工程名 (UUID)
        projectCombo.setRenderer(new SimpleListCellRenderer<>() {
            @Override
            public void customize(@NotNull JList<? extends CJavaProjectDto> list, CJavaProjectDto value, int index, boolean selected, boolean hasFocus) {
                if (value != null) {
                    setText(BapBundle.message("ui.ProjectDownloadDialog.item_format", value.getName(), value.getUuid())); // value.getName() + " (" + value.getUuid() + ")"
                }
            }
        });

        // 填充数据
        if (projects != null) {
            for (CJavaProjectDto p : projects) {
                projectCombo.addItem(p);
            }
        }

        // 默认选中第一个
        if (projectCombo.getItemCount() > 0) {
            projectCombo.setSelectedIndex(0);
        }

        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        return FormBuilder.createFormBuilder()
                .addLabeledComponent(BapBundle.message("ui.ProjectDownloadDialog.label.select_project"), projectCombo) // "Select project:"
                .getPanel();
    }

    public CJavaProjectDto getSelectedProject() {
        return (CJavaProjectDto) projectCombo.getSelectedItem();
    }

    public String getSelectedProjectUuid() {
        CJavaProjectDto selected = getSelectedProject();
        return selected != null ? selected.getUuid() : null;
    }

    public String getSelectedProjectName() {
        CJavaProjectDto selected = getSelectedProject();
        return selected != null ? selected.getName() : null;
    }
}