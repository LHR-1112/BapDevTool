package com.bap.dev.settings;

import com.bap.dev.activity.CheckUpdateActivity; // 引入检查更新类
import com.bap.dev.i18n.BapBundle;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.Messages;
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
    private JBCheckBox confirmCommitCheckbox;
    private JBCheckBox checkUpdateCheckbox;
    private JBCheckBox showProjectNodeActionsCheckBox;
    private JBCheckBox showProjectTreeStatusCheckBox;

    private ColorPanel modifiedColorPanel;
    private ColorPanel addedColorPanel;
    private ColorPanel deletedColorPanel;

    private final CollectionListModel<String> uriListModel = new CollectionListModel<>();
    private final JBList<String> uriList = new JBList<>(uriListModel);

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return BapBundle.message("configurable.BapSettingsConfigurable.display_name"); // "Bap Settings"
    }

    @Override
    public @Nullable JComponent createComponent() {
        compileOnPublishCheckbox = new JBCheckBox(BapBundle.message("configurable.BapSettingsConfigurable.checkbox.compile_on_publish")); // "发布时自动编译"

        autoRefreshCheckbox = new JBCheckBox(BapBundle.message("configurable.BapSettingsConfigurable.checkbox.auto_refresh")); // "自动刷新文件状态"
        autoRefreshCheckbox.setToolTipText(BapBundle.message("configurable.BapSettingsConfigurable.tooltip.auto_refresh")); // "开启后..."
        autoRefreshCheckbox.addActionListener(e -> {
            if (autoRefreshCheckbox.isSelected()) {
                int result = Messages.showYesNoDialog(
                        BapBundle.message("configurable.BapSettingsConfigurable.performance_warning.message"),
                        BapBundle.message("configurable.BapSettingsConfigurable.performance_warning.title"),
                        Messages.getWarningIcon()
                );

                // 如果用户点击“否”或关闭窗口，则取消勾选
                if (result != Messages.YES) {
                    autoRefreshCheckbox.setSelected(false);
                }
            }
        });

        confirmCommitCheckbox = new JBCheckBox(BapBundle.message("configurable.BapSettingsConfigurable.checkbox.confirm_commit"));
        showProjectNodeActionsCheckBox = new JBCheckBox(BapBundle.message("configurable.BapSettingsConfigurable.checkbox.show_project_node_actions")); // "显示工程节点右侧操作按钮"
        showProjectTreeStatusCheckBox = new JBCheckBox(BapBundle.message("configurable.BapSettingsConfigurable.checkbox.show_file_status_in_file_tree"));   // "在项目树中显示文件状态"
        showProjectTreeStatusCheckBox.addActionListener(e -> {
            if (showProjectTreeStatusCheckBox.isSelected()) {
                int result = Messages.showYesNoDialog(
                        BapBundle.message("configurable.BapSettingsConfigurable.conflict_warning.message"),
                        BapBundle.message("configurable.BapSettingsConfigurable.conflict_warning.title"),
                        Messages.getWarningIcon()
                );

                // 如果用户点击“否”或关闭窗口，则取消勾选
                if (result != Messages.YES) {
                    showProjectTreeStatusCheckBox.setSelected(false);
                }
            }
        });

        checkUpdateCheckbox = new JBCheckBox(BapBundle.message("configurable.BapSettingsConfigurable.checkbox.check_update")); // "启动时自动检查更新"
        JButton checkUpdateBtn = new JButton(BapBundle.message("title.check_update")); // "检查更新"
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
                    String input = JOptionPane.showInputDialog(BapBundle.message("configurable.BapSettingsConfigurable.dialog.enter_uri")); // "Enter Server URI:"
                    if (input != null && !input.trim().isEmpty()) {
                        uriListModel.add(input.trim());
                    }
                })
                .createPanel();

        return FormBuilder.createFormBuilder()
                .addComponent(compileOnPublishCheckbox)
                .addComponent(autoRefreshCheckbox)
                .addComponent(confirmCommitCheckbox) // 🔴 添加到面板
                .addComponent(showProjectNodeActionsCheckBox) // 添加更新配置行
                .addComponent(showProjectTreeStatusCheckBox) // 添加更新配置行
                .addSeparator()
                .addLabeledComponent(BapBundle.message("configurable.BapSettingsConfigurable.label.modified_color"), createColorRow(modifiedColorPanel, JBColor.YELLOW)) // "Modified color:"
                .addLabeledComponent(BapBundle.message("configurable.BapSettingsConfigurable.label.added_color"), createColorRow(addedColorPanel, JBColor.BLUE))       // "Added color:"
                .addLabeledComponent(BapBundle.message("configurable.BapSettingsConfigurable.label.deleted_color"), createColorRow(deletedColorPanel, JBColor.RED))    // "Deleted color:"
                .addSeparator()
                .addLabeledComponentFillVertically(BapBundle.message("configurable.BapSettingsConfigurable.label.uri_history"), uriListPanel) // "Server URI History:"
                .addSeparator()
                .addComponent(updatePanel) // 添加更新配置行
                .getPanel();
    }

    private JPanel createColorRow(ColorPanel panel, Color defaultColor) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        row.add(panel);
        JButton resetBtn = new JButton(BapBundle.message("button.reset")); // "还原"
        resetBtn.setToolTipText(BapBundle.message("configurable.BapSettingsConfigurable.tooltip.restore_color")); // "Restore default color"
        resetBtn.addActionListener(e -> panel.setSelectedColor(defaultColor));
        row.add(resetBtn);
        return row;
    }

    @Override
    public boolean isModified() {
        BapSettingsState settings = BapSettingsState.getInstance();

        boolean compileOnPublishModified = compileOnPublishCheckbox.isSelected() != settings.compileOnPublish;
        boolean autoRefreshModified = autoRefreshCheckbox.isSelected() != settings.autoRefresh;
        boolean confirmCommitModified = confirmCommitCheckbox.isSelected() != settings.confirmBeforeCommit;
        boolean checkUpdateModified = checkUpdateCheckbox.isSelected() != settings.checkUpdateOnStartup;
        boolean showProjectNodeModified = showProjectNodeActionsCheckBox.isSelected() != settings.showProjectNodeActions;
        boolean showProjectTreeStatusModified = showProjectTreeStatusCheckBox.isSelected() != settings.showProjectTreeStatus;

        List<String> currentStoredUris = settings.loginHistory.stream()
                .map(p -> p.uri)
                .collect(Collectors.toList());
        boolean listModified = !uriListModel.getItems().equals(currentStoredUris);

        boolean colorModified = !isColorEqual(modifiedColorPanel.getSelectedColor(), settings.getModifiedColorObj()) ||
                !isColorEqual(addedColorPanel.getSelectedColor(), settings.getAddedColorObj()) ||
                !isColorEqual(deletedColorPanel.getSelectedColor(), settings.getDeletedColorObj());

        return compileOnPublishModified || autoRefreshModified || confirmCommitModified || checkUpdateModified ||
                showProjectNodeModified || showProjectTreeStatusModified || listModified || colorModified;
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
        settings.confirmBeforeCommit = confirmCommitCheckbox.isSelected();
        settings.checkUpdateOnStartup = checkUpdateCheckbox.isSelected();
        settings.showProjectNodeActions = showProjectNodeActionsCheckBox.isSelected();
        settings.showProjectTreeStatus = showProjectTreeStatusCheckBox.isSelected();

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
        confirmCommitCheckbox.setSelected(settings.confirmBeforeCommit);
        checkUpdateCheckbox.setSelected(settings.checkUpdateOnStartup);
        showProjectNodeActionsCheckBox.setSelected(settings.showProjectNodeActions);
        showProjectTreeStatusCheckBox.setSelected(settings.showProjectTreeStatus);

        uriListModel.removeAll();
        List<String> uris = settings.loginHistory.stream()
                .map(p -> p.uri)
                .collect(Collectors.toList());
        uriListModel.addAll(0, uris);

        modifiedColorPanel.setSelectedColor(settings.getModifiedColorObj());
        addedColorPanel.setSelectedColor(settings.getAddedColorObj());
        deletedColorPanel.setSelectedColor(settings.getDeletedColorObj());


    }

    @Override
    public void disposeUIResources() {
        compileOnPublishCheckbox = null;
        autoRefreshCheckbox = null;
        confirmCommitCheckbox = null;
        checkUpdateCheckbox = null;
        showProjectNodeActionsCheckBox = null;
        showProjectTreeStatusCheckBox = null;
        modifiedColorPanel = null;
        addedColorPanel = null;
        deletedColorPanel = null;
    }
}