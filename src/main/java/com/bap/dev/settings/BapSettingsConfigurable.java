package com.bap.dev.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.ui.CollectionListModel;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBList;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;

public class BapSettingsConfigurable implements Configurable {

    private JCheckBox compileOnPublishCheckbox;

    // 列表数据模型
    private final CollectionListModel<String> uriListModel = new CollectionListModel<>();
    // 列表 UI 组件
    private final JBList<String> uriList = new JBList<>(uriListModel);

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Bap Settings";
    }

    @Override
    public @Nullable JComponent createComponent() {
        compileOnPublishCheckbox = new JCheckBox("发布时自动编译 (Rebuild All on Publish)");

        // 创建带工具栏的列表面板 (Add, Remove, Move Up, Move Down)
        JPanel uriListPanel = ToolbarDecorator.createDecorator(uriList)
                .setAddAction(button -> {
                    // 点击添加按钮的处理
                    String input = JOptionPane.showInputDialog("Enter Server URI:");
                    if (input != null && !input.trim().isEmpty()) {
                        uriListModel.add(input.trim());
                    }
                })
                .createPanel();

        return FormBuilder.createFormBuilder()
                .addComponent(compileOnPublishCheckbox)
                .addSeparator()
                .addLabeledComponentFillVertically("Server URI History:", uriListPanel)
                .getPanel();
    }

    @Override
    public boolean isModified() {
        BapSettingsState settings = BapSettingsState.getInstance();

        boolean checkboxModified = compileOnPublishCheckbox.isSelected() != settings.compileOnPublish;
        boolean listModified = !uriListModel.getItems().equals(settings.uriHistory);

        return checkboxModified || listModified;
    }

    @Override
    public void apply() {
        BapSettingsState settings = BapSettingsState.getInstance();
        settings.compileOnPublish = compileOnPublishCheckbox.isSelected();

        // 保存 List (深拷贝一份，防止引用问题)
        settings.uriHistory = new ArrayList<>(uriListModel.getItems());
    }

    @Override
    public void reset() {
        BapSettingsState settings = BapSettingsState.getInstance();
        compileOnPublishCheckbox.setSelected(settings.compileOnPublish);

        // 重置 List 数据
        uriListModel.removeAll();
        uriListModel.addAll(0, settings.uriHistory);
    }

    @Override
    public void disposeUIResources() {
        compileOnPublishCheckbox = null;
    }
}