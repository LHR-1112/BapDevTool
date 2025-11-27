package com.bap.dev.ui;

import com.bap.dev.settings.BapSettingsState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.List;

public class LogonDialog extends DialogWrapper {

    private final ComboBox<String> uriCombo = new ComboBox<>();
    private final JTextField userField = new JTextField();
    private final JPasswordField pwdField = new JPasswordField();

    public LogonDialog(@Nullable Project project, String defaultUri, String defaultUser, String defaultPwd) {
        super(project);
        setTitle("Connect to Bap Server");

        setupUriCombo(defaultUri);

        userField.setText(defaultUser != null ? defaultUser : "");
        pwdField.setText(defaultPwd != null ? defaultPwd : "");

        init();
    }

    private void setupUriCombo(String defaultUri) {
        uriCombo.setEditable(true);

        // 1. 从全局配置中加载历史记录
        List<String> history = BapSettingsState.getInstance().uriHistory;
        for (String url : history) {
            uriCombo.addItem(url);
        }

        // 2. 设置默认选中项
        if (defaultUri != null && !defaultUri.isEmpty()) {
            uriCombo.setSelectedItem(defaultUri);
        } else if (uriCombo.getItemCount() > 0) {
            uriCombo.setSelectedIndex(0);
        }
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        return FormBuilder.createFormBuilder()
                .addLabeledComponent("Server URI:", uriCombo)
                .addLabeledComponent("User:", userField)
                .addLabeledComponent("Password:", pwdField)
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
        // 点击登录时，将当前 URI 存入历史记录 (自动去重/置顶)
        String currentUri = getUri();
        if (!currentUri.isEmpty()) {
            BapSettingsState.getInstance().addUriToHistory(currentUri);
        }
        super.doOKAction();
    }
}