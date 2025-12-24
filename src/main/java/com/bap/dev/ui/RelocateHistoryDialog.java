package com.bap.dev.ui;

import com.bap.dev.settings.BapSettingsState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.*;
import com.intellij.ui.components.JBList;
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

    // --- 🔴 新增字段 ---
    private final String modulePath;
    private final CollectionListModel<BapSettingsState.RelocateProfile> listModel;

    // --- 🔴 修改构造函数：接收 modulePath ---
    public RelocateHistoryDialog(@Nullable Project project, List<BapSettingsState.RelocateProfile> history, String modulePath) {
        super(project);
        this.modulePath = modulePath;
        setTitle("Select Relocation Target");

        // 使用 CollectionListModel 以便动态删除
        this.listModel = new CollectionListModel<>(history);
        historyList = new JBList<>(listModel);
        historyList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        if (!history.isEmpty()) {
            historyList.setSelectedIndex(0);
        }

        // 自定义渲染器：显示 "工程名 @ 服务器地址"
        historyList.setCellRenderer(new ColoredListCellRenderer<>() {
            @Override
            protected void customizeCellRenderer(@NotNull JList<? extends BapSettingsState.RelocateProfile> list, BapSettingsState.RelocateProfile value, int index, boolean selected, boolean hasFocus) {
                append(value.projectName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                append("  on  ", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                append(value.uri, SimpleTextAttributes.REGULAR_ATTRIBUTES);
                append(" (" + value.user + ")", SimpleTextAttributes.GRAYED_ATTRIBUTES);
            }
        });

        // 双击列表项直接确定
        historyList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    doOKAction();
                }
            }
        });

        // 自定义按钮
        setOKButtonText("Relocate to Selected");
        setCancelButtonText("Cancel");

        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        // --- 🔴 修改：使用 ToolbarDecorator 增加删除按钮 ---
        JPanel listPanel = ToolbarDecorator.createDecorator(historyList)
                .setRemoveAction(button -> {
                    BapSettingsState.RelocateProfile selected = historyList.getSelectedValue();
                    if (selected != null) {
                        // 1. 从持久化状态中删除
                        BapSettingsState.getInstance().removeRelocateHistory(modulePath, selected);
                        // 2. 从界面列表中删除
                        ListUtil.removeSelectedItems(historyList);
                    }
                })
                .disableAddAction()
                .disableUpDownActions()
                .createPanel();

        return FormBuilder.createFormBuilder()
                .addLabeledComponent("Recent Locations:", listPanel)
                .addTooltip("Select a previous location to switch immediately, or remove obsolete ones.")
                .getPanel();
    }

    @Override
    protected void createDefaultActions() {
        super.createDefaultActions();
        // 添加一个 "New Relocation" 按钮到左侧
        myOKAction.putValue(Action.NAME, "Use Selected History");
    }

    @Override
    protected JComponent createSouthPanel() {
        JComponent southPanel = super.createSouthPanel();
        JButton newConnBtn = new JButton("New Connection / Change...");
        newConnBtn.addActionListener(e -> {
            isNewConnectionSelected = true;
            close(OK_EXIT_CODE); // 关闭对话框，返回 OK，但在 Handler 里判断标志位
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