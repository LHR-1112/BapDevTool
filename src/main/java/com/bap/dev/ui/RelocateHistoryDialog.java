package com.bap.dev.ui;

import bap.java.CJavaProjectDto;
import com.bap.dev.BapRpcClient;
import com.bap.dev.i18n.BapBundle;
import com.bap.dev.service.BapConnectionManager;
import com.bap.dev.settings.BapSettingsState;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.*;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBPasswordField; // 引入密码框
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RelocateHistoryDialog extends DialogWrapper {

    private final JBList<BapSettingsState.RelocateProfile> historyList;
    private BapSettingsState.RelocateProfile selectedProfile;
    private boolean isNewConnectionSelected = false;

    private final String modulePath;
    private final CollectionListModel<BapSettingsState.RelocateProfile> listModel;

    // 🔴 关键修复 1: 必须在这里声明 project 变量，内部类才能访问
    private final Project project;

    public RelocateHistoryDialog(@Nullable Project project, List<BapSettingsState.RelocateProfile> history, String modulePath) {
        super(project);

        // 🔴 关键修复 2: 必须在构造函数中赋值
        this.project = project;

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

                // 🔴 核心修改：在列表项中显示备注
                if (value.remark != null && !value.remark.trim().isEmpty()) {
                    append("  (" + value.remark + ")", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
                }
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


    // --- 🔴 编辑已有连接 ---
    private void editSelectedProfile() {
        BapSettingsState.RelocateProfile selected = historyList.getSelectedValue();
        if (selected == null) return;

        EditProfileDialog dialog = new EditProfileDialog(selected);
        if (dialog.showAndGet()) {
            // 保存修改
            selected.uri = dialog.getUri();
            selected.user = dialog.getUser();
            selected.pwd = dialog.getPassword();
            selected.projectUuid = dialog.getProjectUuid();
            selected.remark = dialog.getRemark(); // 保存备注

            // 同步更新项目名称
            if (dialog.getSelectedProjectName() != null) {
                selected.projectName = dialog.getSelectedProjectName();
            }

            historyList.repaint();
        }
    }

    // --- 🔴 新增：创建新连接 (复用 EditProfileDialog) ---
    private void createNewConnection() {
        // 1. 创建一个默认的空对象
        BapSettingsState.RelocateProfile newProfile = new BapSettingsState.RelocateProfile();
        newProfile.projectName = "";
        newProfile.uri = "";
        newProfile.user = "";
        newProfile.pwd = "";
        newProfile.remark = "";

        // 2. 弹出编辑框 (复用)
        EditProfileDialog dialog = new EditProfileDialog(newProfile);
        if (dialog.showAndGet()) {
            // 3. 提取填写的数据
            newProfile.uri = dialog.getUri();
            newProfile.user = dialog.getUser();
            newProfile.pwd = dialog.getPassword();
            newProfile.projectUuid = dialog.getProjectUuid();
            newProfile.remark = dialog.getRemark();
            if (dialog.getSelectedProjectName() != null) {
                newProfile.projectName = dialog.getSelectedProjectName();
            }

            // 4. 添加到列表和状态中
            listModel.add(newProfile);
            historyList.setSelectedValue(newProfile, true);
            BapSettingsState.getInstance().addRelocateHistory(modulePath, newProfile);
        }
    }

    // --- 🔴 编辑弹窗：包含所有关键字段 ---
    private class EditProfileDialog extends DialogWrapper {
        private final ComboBox<String> uriField = new ComboBox<>(); // 服务器下拉
        private final JTextField userField = new JTextField();
        private final JBPasswordField passwordField = new JBPasswordField();
        private final ComboBox<ProjectItem> projectBox = new ComboBox<>(); // 项目下拉
        private final JTextField remarkField = new JTextField(); // 备注

        public EditProfileDialog(BapSettingsState.RelocateProfile profile) {
            super(RelocateHistoryDialog.this.getContentPane(), true);
            setTitle(BapBundle.message("ui.RelocateHistoryDialog.edit.title"));

            // 初始化服务器下拉
            Set<String> uris = new HashSet<>();
            for (BapSettingsState.RelocateProfile p : listModel.getItems()) {
                if (p.uri != null) uris.add(p.uri);
            }
            if (profile.uri != null) uris.add(profile.uri);
            for (String uri : uris) uriField.addItem(uri);
            uriField.setEditable(true);
            uriField.setSelectedItem(profile.uri != null ? profile.uri : "");

            // 🔴 增加非空判断，防止新对象报错
            userField.setText(profile.user != null ? profile.user : "");
            passwordField.setText(profile.pwd != null ? profile.pwd : "");
            remarkField.setText(profile.remark != null ? profile.remark : "");

            projectBox.setEditable(false);
            projectBox.addItem(new ProjectItem(
                    profile.projectName != null ? profile.projectName : "",
                    profile.projectUuid != null ? profile.projectUuid : ""
            ));
            projectBox.setSelectedIndex(0);

            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            // 创建“加载项目”按钮
            JButton loadBtn = new JButton(BapBundle.message("button.refresh")); // 或者使用图标 AllIcons.Actions.Refresh
            loadBtn.addActionListener(e -> loadProjects());

            JPanel serverPanel = new JPanel(new BorderLayout());
            serverPanel.add(uriField, BorderLayout.CENTER);

            JPanel projectPanel = new JPanel(new BorderLayout(5, 0));
            projectPanel.add(projectBox, BorderLayout.CENTER);
            projectPanel.add(loadBtn, BorderLayout.EAST);

            return FormBuilder.createFormBuilder()
                    .addLabeledComponent(BapBundle.message("label.server_uri"), serverPanel)
                    .addLabeledComponent(BapBundle.message("label.user"), userField)
                    .addLabeledComponent(BapBundle.message("label.password"), passwordField)
                    .addLabeledComponent(BapBundle.message("ui.RelocateHistoryDialog.edit.label.project"), projectPanel) // 项目选择
                    .addLabeledComponent(BapBundle.message("label.remark"), remarkField) // 建议添加到 Bundle: label.remark
                    .getPanel();
        }

        // 后台加载项目列表
        private void loadProjects() {
            String uri = (String) uriField.getSelectedItem();
            String user = userField.getText().trim();
            String pwd = new String(passwordField.getPassword());

            if (uri == null || uri.isEmpty()) return;

            ProgressManager.getInstance().run(new Task.Modal(project, BapBundle.message("ui.RelocateHistoryDialog.progress.loading"), true) {
                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    indicator.setIndeterminate(true);
                    try {
                        // 使用共享连接
                        BapRpcClient client = BapConnectionManager.getInstance(project).getSharedClient(uri, user, pwd);
                        // 注意：如果连接参数变了，getSharedClient 可能会复用旧连接，建议这里强制 connect 或者单独 new 一个 client
                        // 为了简单，这里假定用户修改不多，或者 ConnectionManager 能处理 key 变化
                        // 更保险的做法是 client.connect(...) 确保连通性
                        // 但 SharedClient 不暴露 connect。
                        // 如果要强制刷新，可以使用 client.getService().getProjects() 会触发重连逻辑(如果没连上)

                        java.util.List<CJavaProjectDto> projects = client.getService().getAllProjects(); // 假设有此 API

                        ApplicationManager.getApplication().invokeLater(() -> {
                            projectBox.removeAllItems();
                            if (projects != null) {
                                for (CJavaProjectDto p : projects) {
                                    projectBox.addItem(new ProjectItem(p.getName(), p.getUuid()));
                                }
                                if (projectBox.getItemCount() > 0) {
                                    projectBox.setSelectedIndex(0);
                                    // 2. 修改: 成功时不弹窗，仅在下拉框自动展开以示反馈 (可选)
                                    projectBox.showPopup();
                                }
                            }
                        });
                    } catch (Exception ex) {
                        ApplicationManager.getApplication().invokeLater(() ->
                                // 3. 适配 i18n: 错误提示
                                Messages.showErrorDialog(
                                        BapBundle.message("ui.RelocateHistoryDialog.error.load_failed", ex.getMessage()),
                                        BapBundle.message("title.error")
                                )
                        );
                    }
                }
            });
        }
        public String getUri() { return (String) uriField.getSelectedItem(); }
        public String getUser() { return userField.getText().trim(); }
        public String getPassword() { return new String(passwordField.getPassword()); }

        public String getProjectUuid() {
            ProjectItem item = (ProjectItem) projectBox.getSelectedItem();
            return item != null ? item.uuid : "";
        }

        public String getSelectedProjectName() {
            ProjectItem item = (ProjectItem) projectBox.getSelectedItem();
            return item != null ? item.name : null;
        }

        public String getRemark() { return remarkField.getText().trim(); }
    }

    // 辅助类：用于 ComboBox 显示
    private static class ProjectItem {
        String name;
        String uuid;

        public ProjectItem(String name, String uuid) {
            this.name = name;
            this.uuid = uuid;
        }

        // 🔴 优化：如果 uuid 为空，只显示名字，避免显示 weird 的 " ()"
        @Override
        public String toString() {
            if (uuid == null || uuid.isEmpty()) return name;
            return name + " (" + uuid + ")";
        }
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

        // 🔴 修改：点击 "新建连接" -> 弹出复用的 EditDialog -> 确定后保存
        newConnBtn.addActionListener(e -> createNewConnection());

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


