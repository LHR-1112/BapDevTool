package com.bap.dev.settings;

import com.bap.dev.activity.CheckUpdateActivity; // 引入检查更新类
import com.intellij.openapi.options.Configurable;
import com.intellij.ui.CollectionListModel;
import com.intellij.ui.ColorPanel;
import com.intellij.ui.JBColor;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBList;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class BapSettingsConfigurable implements Configurable {

    private JBCheckBox compileOnPublishCheckbox;
    private JBCheckBox autoRefreshCheckbox;
    // --- 🔴 新增复选框 ---
    private JBCheckBox checkUpdateCheckbox;
    // -------------------

    private JBCheckBox showProjectNodeActionsCheckBox;

    private ColorPanel modifiedColorPanel;
    private ColorPanel addedColorPanel;
    private ColorPanel deletedColorPanel;

    private final CollectionListModel<String> uriListModel = new CollectionListModel<>();
    private final JBList<String> uriList = new JBList<>(uriListModel);

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Bap Settings";
    }

    @Override
    public @Nullable JComponent createComponent() {
        compileOnPublishCheckbox = new JBCheckBox("发布时自动编译");
        autoRefreshCheckbox = new JBCheckBox("自动刷新文件状态");
        autoRefreshCheckbox.setToolTipText("开启后，文件修改保存时会自动触发云端比对（可能会有网络延迟）");

        // --- 🔴 初始化新增组件 ---
        checkUpdateCheckbox = new JBCheckBox("启动时自动检查更新");

        showProjectNodeActionsCheckBox = new JBCheckBox("显示工程节点右侧操作按钮");

        JButton checkUpdateBtn = new JButton("检查更新");
        checkUpdateBtn.addActionListener(e -> {
            // 传入 null project (因为这是 Application 级别的设置页)，isManual = true
            CheckUpdateActivity.runUpdateCheck(null, true);
        });

        // 将复选框和按钮放在一行，或者分行
        JPanel updatePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        updatePanel.add(checkUpdateCheckbox);
        updatePanel.add(Box.createHorizontalStrut(10));
        updatePanel.add(checkUpdateBtn);
        // -----------------------

        modifiedColorPanel = new ColorPanel();
        addedColorPanel = new ColorPanel();
        deletedColorPanel = new ColorPanel();

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
                .addComponent(autoRefreshCheckbox)
                .addComponent(updatePanel) // 添加更新配置行
                .addComponent(showProjectNodeActionsCheckBox) // 添加更新配置行
                .addSeparator()
                .addLabeledComponent("Modified color:", createColorRow(modifiedColorPanel, JBColor.YELLOW))
                .addLabeledComponent("Added color:", createColorRow(addedColorPanel, JBColor.BLUE))
                .addLabeledComponent("Deleted color:", createColorRow(deletedColorPanel, JBColor.RED))
                .addSeparator()
                .addLabeledComponentFillVertically("Server URI History:", uriListPanel)
                .getPanel();
    }

    private JPanel createColorRow(ColorPanel panel, Color defaultColor) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        row.add(panel);
        JButton resetBtn = new JButton("还原");
        resetBtn.setToolTipText("Restore default color");
        resetBtn.addActionListener(e -> panel.setSelectedColor(defaultColor));
        row.add(resetBtn);
        return row;
    }

    @Override
    public boolean isModified() {
        BapSettingsState settings = BapSettingsState.getInstance();

        boolean checkboxModified = compileOnPublishCheckbox.isSelected() != settings.compileOnPublish;
        boolean autoRefreshModified = autoRefreshCheckbox.isSelected() != settings.autoRefresh;
        // --- 🔴 检查新增配置 ---
        boolean checkUpdateModified = checkUpdateCheckbox.isSelected() != settings.checkUpdateOnStartup;
        boolean showProjectNodeModified = showProjectNodeActionsCheckBox.isSelected() != settings.showProjectNodeActions;
        // --------------------

        List<String> currentStoredUris = settings.loginHistory.stream()
                .map(p -> p.uri)
                .collect(Collectors.toList());
        boolean listModified = !uriListModel.getItems().equals(currentStoredUris);

        boolean colorModified = !isColorEqual(modifiedColorPanel.getSelectedColor(), settings.getModifiedColorObj()) ||
                !isColorEqual(addedColorPanel.getSelectedColor(), settings.getAddedColorObj()) ||
                !isColorEqual(deletedColorPanel.getSelectedColor(), settings.getDeletedColorObj());

        return checkboxModified || autoRefreshModified || checkUpdateModified || showProjectNodeModified || listModified || colorModified;
    }

    private boolean isColorEqual(Color c1, Color c2) {
        if (c1 == null && c2 == null) return true;
        if (c1 == null || c2 == null) return false;
        return c1.equals(c2);
    }

    @Override
    public void apply() {
        BapSettingsState settings = BapSettingsState.getInstance();
        settings.compileOnPublish = compileOnPublishCheckbox.isSelected();
        settings.autoRefresh = autoRefreshCheckbox.isSelected();
        // --- 🔴 保存新增配置 ---
        settings.checkUpdateOnStartup = checkUpdateCheckbox.isSelected();
        // --------------------

        settings.showProjectNodeActions = showProjectNodeActionsCheckBox.isSelected();

        List<String> uiUris = uriListModel.getItems();
        List<BapSettingsState.LoginProfile> newHistory = new ArrayList<>();
        for (String uri : uiUris) {
            BapSettingsState.LoginProfile existing = settings.getProfile(uri);
            if (existing != null) {
                newHistory.add(existing);
            } else {
                newHistory.add(new BapSettingsState.LoginProfile(uri, "", ""));
            }
        }
        settings.loginHistory = newHistory;

        if (modifiedColorPanel.getSelectedColor() != null) settings.setModifiedColorObj(modifiedColorPanel.getSelectedColor());
        if (addedColorPanel.getSelectedColor() != null) settings.setAddedColorObj(addedColorPanel.getSelectedColor());
        if (deletedColorPanel.getSelectedColor() != null) settings.setDeletedColorObj(deletedColorPanel.getSelectedColor());
    }

    @Override
    public void reset() {
        BapSettingsState settings = BapSettingsState.getInstance();
        compileOnPublishCheckbox.setSelected(settings.compileOnPublish);
        autoRefreshCheckbox.setSelected(settings.autoRefresh);
        // --- 🔴 重置新增配置 ---
        checkUpdateCheckbox.setSelected(settings.checkUpdateOnStartup);
        // --------------------

        uriListModel.removeAll();
        List<String> uris = settings.loginHistory.stream()
                .map(p -> p.uri)
                .collect(Collectors.toList());
        uriListModel.addAll(0, uris);

        modifiedColorPanel.setSelectedColor(settings.getModifiedColorObj());
        addedColorPanel.setSelectedColor(settings.getAddedColorObj());
        deletedColorPanel.setSelectedColor(settings.getDeletedColorObj());

        showProjectNodeActionsCheckBox.setSelected(settings.showProjectNodeActions);
    }

    @Override
    public void disposeUIResources() {
        compileOnPublishCheckbox = null;
        autoRefreshCheckbox = null;
        checkUpdateCheckbox = null;
        modifiedColorPanel = null;
        addedColorPanel = null;
        deletedColorPanel = null;
    }
}