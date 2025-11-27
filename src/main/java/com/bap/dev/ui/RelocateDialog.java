package com.bap.dev.ui;

import bap.java.CJavaProjectDto;
import com.bap.dev.BapRpcClient;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.List;

public class RelocateDialog extends DialogWrapper {

    private final ComboBox<CJavaProjectDto> projectCombo = new ComboBox<>();
    private final JButton createBtn = new JButton("New Project");
    private final BapRpcClient client; // 持有连接，用于新建工程

    public RelocateDialog(@Nullable Project project, BapRpcClient client, List<CJavaProjectDto> projects) {
        super(project);
        this.client = client;
        setTitle("Relocate Project");

        // 配置下拉框渲染器
        projectCombo.setRenderer(new SimpleListCellRenderer<>() {
            @Override
            public void customize(@NotNull JList<? extends CJavaProjectDto> list, CJavaProjectDto value, int index, boolean selected, boolean hasFocus) {
                if (value != null) {
                    // 显示工程名 + (UUID)
                    setText(value.getName() + " (" + value.getUuid() + ")");
                }
            }
        });

        // 填充数据
        for (CJavaProjectDto p : projects) {
            projectCombo.addItem(p);
        }
        if (projectCombo.getItemCount() > 0) projectCombo.setSelectedIndex(0);

        // 绑定新建按钮事件
        createBtn.addActionListener(e -> createNewProject());

        init();
    }

    private void createNewProject() {
        String name = Messages.showInputDialog(getPeer().getOwner(), "Enter new project name:", "Create New Project", Messages.getQuestionIcon());
        if (name == null || name.trim().isEmpty()) return;

        // 后台创建，前台刷新
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                // 默认创建普通工程 (Type 2: DEPEND_PLATFORM)
                // 如果需要更多类型，可以再弹个下拉框选 Type
                CJavaProjectDto newPj = client.getService().createProject(name.trim(), 2);

                ApplicationManager.getApplication().invokeLater(() -> {
                    if (newPj != null) {
                        projectCombo.addItem(newPj);
                        projectCombo.setSelectedItem(newPj);
                        Messages.showInfoMessage("Project created: " + name, "Success");
                    }
                });
            } catch (Exception e) {
                ApplicationManager.getApplication().invokeLater(() -> Messages.showErrorDialog("Create failed: " + e.getMessage(), "Error"));
            }
        });
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 0));
        panel.add(projectCombo, BorderLayout.CENTER);
        panel.add(createBtn, BorderLayout.EAST);

        return FormBuilder.createFormBuilder()
                .addLabeledComponent("Select target project:", panel)
                .addTooltip("This will update the .develop file to point to the selected project.")
                .getPanel();
    }

    public CJavaProjectDto getSelectedProject() {
        return (CJavaProjectDto) projectCombo.getSelectedItem();
    }
}