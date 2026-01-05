package com.bap.dev.ui;

import com.bap.dev.i18n.BapBundle;
import com.bap.dev.settings.BapSettingsState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.*;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBPasswordField; // 引入密码框
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

public class RelocateHistoryDialog extends DialogWrapper {

    private final JBList<BapSettingsState.RelocateProfile> historyList;
    private BapSettingsState.RelocateProfile selectedProfile;
    private boolean isNewConnectionSelected = false;

    private final String modulePath;
    private final CollectionListModel<BapSettingsState.RelocateProfile> listModel;

    public RelocateHistoryDialog(@Nullable Project project, List<BapSettingsState.RelocateProfile> history, String modulePath) {
        super(project);
        this.modulePath = modulePath;
        setTitle(BapBundle.message("ui.RelocateHistoryDialog.title"));

        this.listModel = new CollectionListModel<>(history);
        historyList = new JBList<>(listModel);
        historyList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        if (!history.isEmpty()) {
            historyList.setSelectedIndex(0);
        }

        historyList.setCellRenderer(new ColoredListCellRenderer<>() {
            @Override
            protected void customizeCellRenderer(@NotNull JList<? extends BapSettingsState.RelocateProfile> list, BapSettingsState.RelocateProfile value, int index, boolean selected, boolean hasFocus) {
                append(value.projectName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                // [修改] 使用 Bundle (renderer.on)
                append(BapBundle.message("ui.RelocateHistoryDialog.renderer.on"), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                append(value.uri, SimpleTextAttributes.REGULAR_ATTRIBUTES);
                // [修改] 使用 Bundle (renderer.user)
                append(BapBundle.message("ui.RelocateHistoryDialog.renderer.user", value.user), SimpleTextAttributes.GRAYED_ATTRIBUTES);
            }
        });

        historyList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    doOKAction();
                }
            }
        });

        setOKButtonText(BapBundle.message("ui.RelocateHistoryDialog.button.relocate_selected"));
        setCancelButtonText(BapBundle.message("button.cancel"));

        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel listPanel = ToolbarDecorator.createDecorator(historyList)
                .setRemoveAction(button -> {
                    BapSettingsState.RelocateProfile selected = historyList.getSelectedValue();
                    if (selected != null) {
                        BapSettingsState.getInstance().removeRelocateHistory(modulePath, selected);
                        ListUtil.removeSelectedItems(historyList);
                    }
                })
                .setEditAction(button -> editSelectedProfile()) // 编辑按钮
                .disableAddAction()
                .disableUpDownActions()
                .createPanel();

        return FormBuilder.createFormBuilder()
                // [修改] 使用 Bundle (label.recent_locations)
                .addLabeledComponent(BapBundle.message("ui.RelocateHistoryDialog.label.recent_locations"), listPanel)
                // [修改] 使用 Bundle (tooltip.select_history)
                .addTooltip(BapBundle.message("ui.RelocateHistoryDialog.tooltip.select_history"))
                .getPanel();
    }


    private void editSelectedProfile() {
        BapSettingsState.RelocateProfile selected = historyList.getSelectedValue();
        if (selected == null) return;

        EditProfileDialog dialog = new EditProfileDialog(selected);
        if (dialog.showAndGet()) {
            // 更新对象字段
            selected.uri = dialog.getUri();
            selected.user = dialog.getUser();
            selected.pwd = dialog.getPassword();       // 🔴 更新密码
            selected.projectUuid = dialog.getProjectUuid(); // 🔴 更新 UUID

            // 刷新列表显示
            historyList.repaint();
        }
    }

    // --- 🔴 编辑弹窗：包含所有关键字段 ---
    private class EditProfileDialog extends DialogWrapper {
        private final JTextField uriField = new JTextField();
        private final JTextField userField = new JTextField();
        private final JBPasswordField passwordField = new JBPasswordField(); // 🔴 密码框
        private final JTextField projectUuidField = new JTextField();      // 🔴 UUID框

        public EditProfileDialog(BapSettingsState.RelocateProfile profile) {
            super(RelocateHistoryDialog.this.getContentPane(), true);
            setTitle(BapBundle.message("ui.RelocateHistoryDialog.edit.title"));

            uriField.setText(profile.uri);
            userField.setText(profile.user);
            passwordField.setText(profile.pwd);
            projectUuidField.setText(profile.projectUuid);

            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            return FormBuilder.createFormBuilder()
                    // [修改] 使用 Bundle (label.server_uri, label.user, label.password, edit.label.uuid)
                    .addLabeledComponent(BapBundle.message("label.server_uri"), uriField)
                    .addLabeledComponent(BapBundle.message("label.user"), userField)
                    .addLabeledComponent(BapBundle.message("label.password"), passwordField)
                    .addLabeledComponent(BapBundle.message("ui.RelocateHistoryDialog.edit.label.uuid"), projectUuidField)
                    .getPanel();
        }

        public String getUri() { return uriField.getText().trim(); }
        public String getUser() { return userField.getText().trim(); }
        public String getPassword() { return new String(passwordField.getPassword()); }
        public String getProjectUuid() { return projectUuidField.getText().trim(); }
    }

    @Override
    protected void createDefaultActions() {
        super.createDefaultActions();
        myOKAction.putValue(Action.NAME, BapBundle.message("ui.RelocateHistoryDialog.button.use_history"));
    }

    @Override
    protected JComponent createSouthPanel() {
        JComponent southPanel = super.createSouthPanel();
        JButton newConnBtn = new JButton(BapBundle.message("ui.RelocateHistoryDialog.button.new_connection"));
        newConnBtn.addActionListener(e -> {
            isNewConnectionSelected = true;
            close(OK_EXIT_CODE);
        });

        JPanel panel = new JPanel(new java.awt.BorderLayout());
        panel.add(newConnBtn, java.awt.BorderLayout.WEST);
        panel.add(southPanel, java.awt.BorderLayout.EAST);
        return panel;
    }

    public boolean isNewConnectionRequested() {
        return isNewConnectionSelected;
    }

    public BapSettingsState.RelocateProfile getSelectedProfile() {
        return historyList.getSelectedValue();
    }
}