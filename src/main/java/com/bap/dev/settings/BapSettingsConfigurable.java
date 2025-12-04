package com.bap.dev.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.ui.CollectionListModel;
import com.intellij.ui.ColorPanel;
import com.intellij.ui.JBColor;
import com.intellij.ui.ToolbarDecorator;
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

    private JCheckBox compileOnPublishCheckbox;
    private JCheckBox autoRefreshCheckbox;

    private final CollectionListModel<String> uriListModel = new CollectionListModel<>();
    private final JBList<String> uriList = new JBList<>(uriListModel);

    // --- 🔴 新增颜色选择器 ---
    private ColorPanel modifiedColorPanel;
    private ColorPanel addedColorPanel;
    private ColorPanel deletedColorPanel;
    // -----------------------

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Bap Settings";
    }

    @Override
    public @Nullable JComponent createComponent() {
        compileOnPublishCheckbox = new JCheckBox("发布时自动编译 (Rebuild All on Publish)");
        autoRefreshCheckbox = new JCheckBox("自动刷新文件状态 (Auto Refresh File Status)");
        autoRefreshCheckbox.setToolTipText("开启后，文件修改保存时会自动触发云端比对（可能会有网络延迟）");

        // 初始化颜色面板
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
                .addSeparator()
                // --- 🔴 修改：使用 createColorRow 添加带重置按钮的行 ---
                .addLabeledComponent("Modified color:", createColorRow(modifiedColorPanel, JBColor.YELLOW))
                .addLabeledComponent("Added color:", createColorRow(addedColorPanel, JBColor.BLUE))
                .addLabeledComponent("Deleted color:", createColorRow(deletedColorPanel, JBColor.RED))
                .addSeparator()
                .addLabeledComponentFillVertically("Server URI History:", uriListPanel)
                .getPanel();
    }

    /**
     * 辅助方法：创建颜色选择行（左侧颜色选择器，右侧重置按钮）
     */
    private JPanel createColorRow(ColorPanel panel, Color defaultColor) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        row.add(panel);

        JButton resetBtn = new JButton("use default");
        resetBtn.setToolTipText("Restore default color");
        // 绑定事件：点击还原为默认颜色
        resetBtn.addActionListener(e -> {
            panel.setSelectedColor(defaultColor);
        });

        row.add(resetBtn);
        return row;
    }

    @Override
    public boolean isModified() {
        BapSettingsState settings = BapSettingsState.getInstance();

        boolean checkboxModified = compileOnPublishCheckbox.isSelected() != settings.compileOnPublish;
        boolean autoRefreshModified = autoRefreshCheckbox.isSelected() != settings.autoRefresh;

        // 🔴 比较列表：将 settings 中的对象列表转为 URI 字符串列表进行比较
        List<String> currentStoredUris = settings.loginHistory.stream()
                .map(p -> p.uri)
                .collect(Collectors.toList());
        boolean listModified = !uriListModel.getItems().equals(currentStoredUris);

        // 检查颜色变动
        boolean colorModified = !modifiedColorPanel.getSelectedColor().equals(settings.getModifiedColorObj()) ||
                !addedColorPanel.getSelectedColor().equals(settings.getAddedColorObj()) ||
                !deletedColorPanel.getSelectedColor().equals(settings.getDeletedColorObj());

        return checkboxModified || autoRefreshModified || listModified || colorModified;
    }

    @Override
    public void apply() {
        BapSettingsState settings = BapSettingsState.getInstance();
        settings.compileOnPublish = compileOnPublishCheckbox.isSelected();
        settings.autoRefresh = autoRefreshCheckbox.isSelected();

        // --- 🔴 保存逻辑：智能合并 ---
        // 我们只在界面上维护了 URI 列表，没有维护密码。
        // 保存时，我们需要根据 UI 上的 URI 列表重建 loginHistory。
        // 如果该 URI 之前存在，保留原本的 User/Pwd；如果不存在，创建新的。
        List<String> uiUris = uriListModel.getItems();
        List<BapSettingsState.LoginProfile> newHistory = new ArrayList<>();

        for (String uri : uiUris) {
            BapSettingsState.LoginProfile existing = settings.getProfile(uri);
            if (existing != null) {
                // 保留旧的凭证
                newHistory.add(existing);
            } else {
                // 新增的 URI，密码留空
                newHistory.add(new BapSettingsState.LoginProfile(uri, "", ""));
            }
        }
        settings.loginHistory = newHistory;

        // 保存颜色
        if (modifiedColorPanel.getSelectedColor() != null) settings.setModifiedColorObj(modifiedColorPanel.getSelectedColor());
        if (addedColorPanel.getSelectedColor() != null) settings.setAddedColorObj(addedColorPanel.getSelectedColor());
        if (deletedColorPanel.getSelectedColor() != null) settings.setDeletedColorObj(deletedColorPanel.getSelectedColor());
    }

    @Override
    public void reset() {
        BapSettingsState settings = BapSettingsState.getInstance();
        compileOnPublishCheckbox.setSelected(settings.compileOnPublish);
        autoRefreshCheckbox.setSelected(settings.autoRefresh);

        // --- 🔴 重置逻辑：从对象列表中提取 URI ---
        uriListModel.removeAll();
        List<String> uris = settings.loginHistory.stream()
                .map(p -> p.uri)
                .collect(Collectors.toList());

        uriListModel.addAll(0, uris);

        // 重置颜色显示
        modifiedColorPanel.setSelectedColor(settings.getModifiedColorObj());
        addedColorPanel.setSelectedColor(settings.getAddedColorObj());
        deletedColorPanel.setSelectedColor(settings.getDeletedColorObj());
    }

    @Override
    public void disposeUIResources() {
        compileOnPublishCheckbox = null;
        autoRefreshCheckbox = null;
    }
}