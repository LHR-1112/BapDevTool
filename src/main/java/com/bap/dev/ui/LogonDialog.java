package com.bap.dev.ui;

import com.bap.dev.i18n.BapBundle;
import com.bap.dev.settings.BapSettingsState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.event.ItemEvent;
import java.util.List;

public class LogonDialog extends DialogWrapper {

    private final ComboBox<String> uriCombo = new ComboBox<>();
    private final JTextField userField = new JTextField();
    private final JPasswordField pwdField = new JPasswordField();

    public LogonDialog(@Nullable Project project, String defaultUri, String defaultUser, String defaultPwd) {
        super(project);
        setTitle(BapBundle.message("ui.LogonDialog.title")); // "Connect to Bap Server"

        setupUriCombo(defaultUri);

        // 1. 先设置传入的默认值 (作为基础)
        if (defaultUser != null) userField.setText(defaultUser);
        if (defaultPwd != null) pwdField.setText(defaultPwd);

        // 2. 🔴 修复：总是尝试根据当前选中的 URI 加载历史凭证
        // 即使 userField 有值（传入的默认用户），也应该优先显示该 URL 历史上绑定的账号密码
        String currentUri = (String) uriCombo.getSelectedItem();
        fillCredentialsForUri(currentUri);

        init();
    }

    private void setupUriCombo(String defaultUri) {
        uriCombo.setEditable(true);

        List<BapSettingsState.LoginProfile> history = BapSettingsState.getInstance().loginHistory;
        for (BapSettingsState.LoginProfile profile : history) {
            uriCombo.addItem(profile.uri);
        }

        if (defaultUri != null && !defaultUri.isEmpty()) {
            uriCombo.setSelectedItem(defaultUri);
        } else if (uriCombo.getItemCount() > 0) {
            uriCombo.setSelectedIndex(0);
        }

        // 监听下拉框变化
        uriCombo.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) {
                fillCredentialsForUri((String) e.getItem());
            }
        });
    }

    // 辅助方法：查找并填充
    private void fillCredentialsForUri(String uri) {
        if (uri == null || uri.trim().isEmpty()) return;

        BapSettingsState.LoginProfile profile = BapSettingsState.getInstance().getProfile(uri);
        if (profile != null) {
            // 只有当历史记录里有值时才覆盖
            userField.setText(profile.user);
            pwdField.setText(profile.pwd);
        }
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        return FormBuilder.createFormBuilder()
                .addLabeledComponent(BapBundle.message("label.server_uri"), uriCombo) // "Server URI:"
                .addLabeledComponent(BapBundle.message("label.user"), userField)      // "User:"
                .addLabeledComponent(BapBundle.message("label.password"), pwdField)   // "Password:"
                .getPanel();
    }

    public String getUri() {
        Object item = uriCombo.getEditor().getItem();
        return item != null ? item.toString().trim() : "";
    }

    public String getUser() { return userField.getText().trim(); }
    public String getPwd() { return new String(pwdField.getPassword()); }

    @Override
    protected void doOKAction() {
        String currentUri = getUri();
        String currentUser = getUser();
        String currentPwd = getPwd();

        if (!currentUri.isEmpty()) {
            // 登录成功保存三元组
            BapSettingsState.getInstance().addOrUpdateProfile(currentUri, currentUser, currentPwd);
        }
        super.doOKAction();
    }
}