package com.bap.dev.ui;

import bap.java.CJavaCode;
import bap.md.ver.VersionNode;
import com.bap.dev.BapRpcClient;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.chains.SimpleDiffRequestChain;
import com.intellij.diff.editor.ChainDiffVirtualFile;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.icons.AllIcons;
import com.intellij.ide.DataManager;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.actionSystem.*; // 确保包含 ActionManager, ActionPopupMenu
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumnModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

public class HistoryListDialog extends DialogWrapper {

    private final Project project;
    private final VirtualFile localFile;
    private final List<VersionNode> historyList;

    // 连接信息
    private final String uri;
    private final String user;
    private final String pwd;

    private JBTable table;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public HistoryListDialog(Project project, VirtualFile localFile, List<VersionNode> historyList, String uri, String user, String pwd) {
        super(project);
        this.project = project;
        this.localFile = localFile;
        this.historyList = historyList;
        this.uri = uri;
        this.user = user;
        this.pwd = pwd;

        setTitle("Cloud History: " + localFile.getName());
        setModal(false);
        setSize(800, 600);
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        // 1. 定义表头
        String[] columnNames = {"Ver", "Time", "User", "Comments"};

        // 2. 转换数据
        Object[][] data = new Object[historyList.size()][4];
        for (int i = 0; i < historyList.size(); i++) {
            VersionNode node = historyList.get(i);
            data[i][0] = node.versionNo;
            data[i][1] = dateFormat.format(new Date(node.commitTime));
            data[i][2] = node.commiter;
            data[i][3] = node.comments;
        }

        // 3. 创建不可编辑的 Model
        DefaultTableModel model = new DefaultTableModel(data, columnNames) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };

        // 4. 创建表格
        table = new JBTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(24);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));

        // 5. 设置列宽和渲染器
        TableColumnModel cm = table.getColumnModel();
        cm.getColumn(0).setPreferredWidth(50);
        cm.getColumn(0).setMaxWidth(60);
        DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(JLabel.CENTER);
        cm.getColumn(0).setCellRenderer(centerRenderer);

        cm.getColumn(1).setPreferredWidth(140);
        cm.getColumn(1).setMaxWidth(160);

        cm.getColumn(2).setPreferredWidth(80);
        cm.getColumn(2).setMaxWidth(120);
        cm.getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                label.setIcon(AllIcons.General.User);
                return label;
            }
        });

        cm.getColumn(3).setPreferredWidth(300);

        // 6. 鼠标监听
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                // 双击 -> 查看代码
                if (e.getClickCount() == 2) {
                    int row = table.getSelectedRow();
                    if (row >= 0) {
                        VersionNode selected = historyList.get(row);
                        showFileContent(selected);
                    }
                }

                // --- 🔴 新增：右键 -> 菜单 ---
                if (SwingUtilities.isRightMouseButton(e)) {
                    int row = table.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        // 选中鼠标所在的行
                        table.setRowSelectionInterval(row, row);
                        VersionNode selected = historyList.get(row);
                        showContextMenu(selected, e);
                    }
                }
            }
        });

        // 7. 包装
        JBScrollPane scrollPane = new JBScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(700, 400));
        scrollPane.setBorder(JBUI.Borders.empty());

        return scrollPane;
    }

    // --- 🔴 新增：显示右键菜单的方法 ---
    private void showContextMenu(VersionNode node, MouseEvent e) {
        DefaultActionGroup group = new DefaultActionGroup();

        group.add(new AnAction("Compare with Previous Version", "Compare with previous cloud version", AllIcons.Actions.Diff) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                compareWithPrevious(node);
            }
        });

        group.add(new AnAction("Compare with Local", "Compare with current local file", AllIcons.Actions.Diff) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                compareWithLocal(node);
            }
        });

        ActionPopupMenu popup = ActionManager.getInstance().createActionPopupMenu("HistoryListPopup", group);
        popup.getComponent().show(e.getComponent(), e.getX(), e.getY());
    }

    @Override
    protected Action[] createActions() {
        // "Compare..." 按钮
        Action compareAction = new AbstractAction("Compare...") {
            @Override
            public void actionPerformed(ActionEvent e) {
                int row = table.getSelectedRow();
                if (row < 0) {
                    Messages.showWarningDialog("请先选择一个版本。", "提示");
                    return;
                }
                VersionNode selected = historyList.get(row);

                // 弹出选项菜单
                DefaultActionGroup group = new DefaultActionGroup();
                group.add(new AnAction("Compare with Previous Version") {
                    @Override public void actionPerformed(@NotNull AnActionEvent e) { compareWithPrevious(selected); }
                });
                group.add(new AnAction("Compare with Local") {
                    @Override public void actionPerformed(@NotNull AnActionEvent e) { compareWithLocal(selected); }
                });

                ListPopup popup = JBPopupFactory.getInstance().createActionGroupPopup(
                        "Select Comparison",
                        group,
                        DataManager.getInstance().getDataContext((Component) e.getSource()),
                        JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                        true
                );

                // 显示在按钮附近
                Component src = (Component) e.getSource();
                popup.showUnderneathOf(src);
            }
        };

        return new Action[]{
                // 还原按钮
                new DialogWrapperAction("Rollback") {
                    @Override
                    protected void doAction(ActionEvent e) {
                        int row = table.getSelectedRow();
                        if (row >= 0) {
                            VersionNode selected = historyList.get(row);
                            if (Messages.showYesNoDialog(project,
                                    "确定要回滚到版本 v" + selected.versionNo + " 吗？\n本地未提交的修改将丢失。",
                                    "确认还原", Messages.getQuestionIcon()) == Messages.YES) {
                                updateToLocal(selected);
                            }
                        } else {
                            Messages.showWarningDialog("请先选择一个版本。", "提示");
                        }
                    }
                },
                compareAction, // 使用新的 Compare Action
                getCancelAction()
        };
    }

    // --- 业务逻辑 1: 查看代码 ---
    private void showFileContent(VersionNode node) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Fetching Code...", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                BapRpcClient client = new BapRpcClient();
                try {
                    client.connect(uri, user, pwd);
                    CJavaCode historyCode = client.getService().getHistoryCode(node.getUuid());
                    final String content = (historyCode != null) ? historyCode.code : "";

                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (content == null || content.isEmpty()) {
                            Messages.showWarningDialog("内容为空。", "提示");
                            return;
                        }

                        // 构造文件名：类名_v版本号.java (例如: MyClass_v10.java)
                        String fileName = node.key.substring(node.key.lastIndexOf('.') + 1) + "_v" + node.versionNo + ".java";
                        LightVirtualFile virtualFile = new LightVirtualFile(fileName, JavaFileType.INSTANCE, content);

                        // 设置为只读 (可选)
                        virtualFile.setWritable(false);

                        // 打开编辑器标签页
                        FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, virtualFile), true);
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    client.shutdown();
                }
            }
        });
    }

    // --- 业务逻辑 2: 与上一版本比较 ---
    private void compareWithPrevious(VersionNode currentNode) {
        // 查找上一版本
        Optional<VersionNode> prevOpt = historyList.stream()
                .filter(n -> n.versionNo < currentNode.versionNo)
                .max(Comparator.comparingInt(n -> Math.toIntExact(n.versionNo)));

        if (!prevOpt.isPresent()) {
            Messages.showInfoMessage("没有更早的历史版本。", "提示");
            return;
        }
        VersionNode prevNode = prevOpt.get();

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Fetching Codes...", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                BapRpcClient client = new BapRpcClient();
                try {
                    client.connect(uri, user, pwd);

                    CJavaCode curCode = client.getService().getHistoryCode(currentNode.getUuid());
                    CJavaCode prevCode = client.getService().getHistoryCode(prevNode.getUuid());

                    String contentCur = (curCode != null) ? curCode.code : "";
                    String contentPrev = (prevCode != null) ? prevCode.code : "";

                    ApplicationManager.getApplication().invokeLater(() ->
                            showDiff(contentPrev, contentCur,
                                    "Previous (v" + prevNode.versionNo + ")",
                                    "Current (v" + currentNode.versionNo + ")",
                                    localFile.getName())
                    );
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    client.shutdown();
                }
            }
        });
    }

    // --- 业务逻辑 3: 与本地比较 ---
    private void compareWithLocal(VersionNode node) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Fetching History Code...", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                BapRpcClient client = new BapRpcClient();
                try {
                    client.connect(uri, user, pwd);
                    CJavaCode historyCode = client.getService().getHistoryCode(node.getUuid());
                    final String remoteContent = (historyCode != null) ? historyCode.code : "";

                    ApplicationManager.getApplication().invokeLater(() ->
                            showDiffWithLocal(remoteContent, "Remote (v" + node.versionNo + ")")
                    );
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    client.shutdown();
                }
            }
        });
    }

    // --- 业务逻辑 4: 还原到本地 ---
    private void updateToLocal(VersionNode node) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Restoring...", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                BapRpcClient client = new BapRpcClient();
                try {
                    client.connect(uri, user, pwd);
                    CJavaCode historyCode = client.getService().getHistoryCode(node.getUuid());
                    final String content = (historyCode != null) ? historyCode.code : null;

                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (content != null) {
                            try {
                                WriteAction.run(() -> {
                                    localFile.setBinaryContent(content.getBytes(StandardCharsets.UTF_8));
                                    com.intellij.openapi.editor.Document doc = FileDocumentManager.getInstance().getDocument(localFile);
                                    if (doc != null) FileDocumentManager.getInstance().reloadFromDisk(doc);
                                    localFile.refresh(false, false);
                                });
                                Messages.showInfoMessage("已还原到 v" + node.versionNo, "Success");
                            } catch (Exception e) {
                                Messages.showErrorDialog("写入失败: " + e.getMessage(), "Error");
                            }
                        } else {
                            Messages.showWarningDialog("该版本无内容。", "Error");
                        }
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    client.shutdown();
                }
            }
        });
    }

    private void showDiff(String contentA, String contentB, String titleA, String titleB, String fileName) {
        DiffContentFactory factory = DiffContentFactory.getInstance();
        SimpleDiffRequest request = new SimpleDiffRequest("Compare " + fileName,
                factory.create(project, contentA, JavaFileType.INSTANCE),
                factory.create(project, contentB, JavaFileType.INSTANCE),
                titleA, titleB);

        SimpleDiffRequestChain chain = new SimpleDiffRequestChain(request);
        ChainDiffVirtualFile virtualFile = new ChainDiffVirtualFile(chain, "Diff: " + fileName);
        FileEditorManager.getInstance(project).openFile(virtualFile, true);
    }

    private void showDiffWithLocal(String remoteContent, String remoteTitle) {
        DiffContentFactory factory = DiffContentFactory.getInstance();
        SimpleDiffRequest request = new SimpleDiffRequest("Compare " + localFile.getName(),
                factory.create(project, remoteContent, JavaFileType.INSTANCE), // Left: History
                factory.create(project, localFile), // Right: Local
                remoteTitle, "Local (Current)");

        SimpleDiffRequestChain chain = new SimpleDiffRequestChain(request);
        ChainDiffVirtualFile virtualFile = new ChainDiffVirtualFile(chain, "Diff: " + remoteTitle);
        FileEditorManager.getInstance(project).openFile(virtualFile, true);
    }
}