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
import java.util.stream.Collectors;

public class BapSettingsConfigurable implements Configurable {

    private JCheckBox compileOnPublishCheckbox;
    private JCheckBox autoRefreshCheckbox;

    private final CollectionListModel<String> uriListModel = new CollectionListModel<>();
    private final JBList<String> uriList = new JBList<>(uriListModel);

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Bap Settings";
    }

    @Override
    public @Nullable JComponent createComponent() {
        compileOnPublishCheckbox = new JCheckBox("发布时自动编译 (Rebuild All on Publish)");
        autoRefreshCheckbox = new JCheckBox("自动刷新文件状态 (Auto Refresh File Status)");
        autoRefreshCheckbox.setToolTipText("开启后，文件修改保存时会自动触发云端比对（可能会有网络延迟）");

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
                .addLabeledComponentFillVertically("Server URI History:", uriListPanel)
                .getPanel();
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

        return checkboxModified || autoRefreshModified || listModified;
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
    }

    @Override
    public void disposeUIResources() {
        compileOnPublishCheckbox = null;
        autoRefreshCheckbox = null;
    }
}