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

public class BapSettingsConfigurable implements Configurable {

    private JCheckBox compileOnPublishCheckbox;
    // --- 🔴 新增 ---
    private JCheckBox autoRefreshCheckbox;
    // -------------

    private final CollectionListModel<String> uriListModel = new CollectionListModel<>();
    private final JBList<String> uriList = new JBList<>(uriListModel);

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Bap Settings";
    }

    @Override
    public @Nullable JComponent createComponent() {
        compileOnPublishCheckbox = new JCheckBox("发布时自动编译 (Rebuild All on Publish)");

        // --- 🔴 新增复选框 ---
        autoRefreshCheckbox = new JCheckBox("自动刷新文件状态 (Auto Refresh File Status)");
        autoRefreshCheckbox.setToolTipText("开启后，文件修改保存时会自动触发云端比对（可能会有网络延迟）");
        // -------------------

        JPanel uriListPanel = ToolbarDecorator.createDecorator(uriList)
                .setAddAction(button -> {
                    String input = JOptionPane.showInputDialog("Enter Server URI:");
                    if (input != null && !input.trim().isEmpty()) {
                        uriListModel.add(input.trim());
                    }
                })
                .createPanel();

        return FormBuilder.createFormBuilder()
                .addComponent(compileOnPublishCheckbox)
                .addComponent(autoRefreshCheckbox) // 添加到面板
                .addSeparator()
                .addLabeledComponentFillVertically("Server URI History:", uriListPanel)
                .getPanel();
    }

    @Override
    public boolean isModified() {
        BapSettingsState settings = BapSettingsState.getInstance();

        boolean checkboxModified = compileOnPublishCheckbox.isSelected() != settings.compileOnPublish;
        // --- 🔴 检查修改 ---
        boolean autoRefreshModified = autoRefreshCheckbox.isSelected() != settings.autoRefresh;
        // ------------------
        boolean listModified = !uriListModel.getItems().equals(settings.uriHistory);

        return checkboxModified || autoRefreshModified || listModified;
    }

    @Override
    public void apply() {
        BapSettingsState settings = BapSettingsState.getInstance();
        settings.compileOnPublish = compileOnPublishCheckbox.isSelected();
        // --- 🔴 保存 ---
        settings.autoRefresh = autoRefreshCheckbox.isSelected();
        // -------------
        settings.uriHistory = new ArrayList<>(uriListModel.getItems());
    }

    @Override
    public void reset() {
        BapSettingsState settings = BapSettingsState.getInstance();
        compileOnPublishCheckbox.setSelected(settings.compileOnPublish);
        // --- 🔴 重置 ---
        autoRefreshCheckbox.setSelected(settings.autoRefresh);
        // -------------

        uriListModel.removeAll();
        uriListModel.addAll(0, settings.uriHistory);
    }

    @Override
    public void disposeUIResources() {
        compileOnPublishCheckbox = null;
        autoRefreshCheckbox = null;
    }
}