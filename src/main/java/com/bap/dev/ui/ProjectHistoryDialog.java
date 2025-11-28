package com.bap.dev.ui;

import bap.java.CJavaCode;
import bap.md.ver.VersionNode;
import com.bap.dev.BapRpcClient;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffDialogHints;
import com.intellij.diff.DiffManager;
import com.intellij.diff.chains.SimpleDiffRequestChain;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.editor.ChainDiffVirtualFile;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.icons.AllIcons;
import com.intellij.ide.DataManager;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.IdeBorderFactory;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
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
import java.util.stream.Collectors;

public class ProjectHistoryDialog extends DialogWrapper {

    private final Project project;
    private final Map<Long, List<VersionNode>> versionMap;
    private final String projectUuid;
    private final String uri;
    private final String user;
    private final String pwd;

    private final List<Long> sortedVersions;
    private JBTable versionTable;
    private JBList<VersionNode> fileList;
    private DefaultListModel<VersionNode> fileListModel;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public ProjectHistoryDialog(Project project, Map<Long, List<VersionNode>> versionMap, String projectUuid, String uri, String user, String pwd) {
        super(project);
        this.project = project;
        this.versionMap = versionMap;
        this.projectUuid = projectUuid;
        this.uri = uri;
        this.user = user;
        this.pwd = pwd;

        this.sortedVersions = new ArrayList<>(versionMap.keySet());
        this.sortedVersions.sort(Collections.reverseOrder());

        setTitle("Project Cloud History");
        setModal(false);
        setSize(900, 600);
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JBSplitter splitter = new JBSplitter(false, 0.4f);

        // --- 左侧：版本表格 ---
        String[] columnNames = {"Ver", "Time", "User", "Comments"};
        Object[][] data = new Object[sortedVersions.size()][4];

        for (int i = 0; i < sortedVersions.size(); i++) {
            Long vNo = sortedVersions.get(i);
            List<VersionNode> nodes = versionMap.get(vNo);
            if (nodes == null || nodes.isEmpty()) continue;
            VersionNode info = nodes.get(0);
            data[i][0] = vNo;
            data[i][1] = dateFormat.format(new Date(info.commitTime));
            data[i][2] = info.commiter;
            data[i][3] = info.comments;
        }

        DefaultTableModel tableModel = new DefaultTableModel(data, columnNames) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };

        versionTable = new JBTable(tableModel);
        versionTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        versionTable.setRowHeight(24);
        versionTable.setShowGrid(false);

        TableColumnModel cm = versionTable.getColumnModel();
        cm.getColumn(0).setMaxWidth(60);
        cm.getColumn(1).setPreferredWidth(140);
        cm.getColumn(1).setMaxWidth(160);
        cm.getColumn(2).setMaxWidth(100);

        cm.getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                label.setIcon(AllIcons.General.User);
                return label;
            }
        });

        versionTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = versionTable.getSelectedRow();
                if (row >= 0) updateFileList(sortedVersions.get(row));
            }
        });

        // --- 右侧：文件列表 ---
        fileListModel = new DefaultListModel<>();
        fileList = new JBList<>(fileListModel);
        fileList.setCellRenderer(new ColoredListCellRenderer<VersionNode>() {
            @Override
            protected void customizeCellRenderer(@NotNull JList<? extends VersionNode> list, VersionNode value, int index, boolean selected, boolean hasFocus) {
                setIcon(AllIcons.FileTypes.Java);
                String name = value.key;
                int lastDot = name.lastIndexOf('.');
                if (lastDot > 0) name = name.substring(lastDot + 1);
                append(name, SimpleTextAttributes.REGULAR_ATTRIBUTES);
                append(" (" + value.key + ")", SimpleTextAttributes.GRAYED_ATTRIBUTES);
            }
        });

        // 鼠标监听
        fileList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                // 双击 -> 浏览文件
                if (e.getClickCount() == 2) {
                    VersionNode selected = fileList.getSelectedValue();
                    if (selected != null) {
                        // --- 🔴 修改点：双击改为查看内容 ---
                        showFileContent(selected);
                    }
                }
                // 右键 -> 菜单
                if (SwingUtilities.isRightMouseButton(e)) {
                    int index = fileList.locationToIndex(e.getPoint());
                    if (index >= 0) {
                        fileList.setSelectedIndex(index);
                        showFileContextMenu(fileList.getSelectedValue(), e);
                    }
                }
            }
        });

        JComponent left = new JBScrollPane(versionTable);
        left.setBorder(IdeBorderFactory.createTitledBorder("Version List", false));
        JComponent right = new JBScrollPane(fileList);
        right.setBorder(IdeBorderFactory.createTitledBorder("Changed Files", false));

        splitter.setFirstComponent(left);
        splitter.setSecondComponent(right);

        if (!sortedVersions.isEmpty()) versionTable.setRowSelectionInterval(0, 0);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(splitter, BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(900, 600));
        return panel;
    }

    private void updateFileList(Long versionNo) {
        fileListModel.clear();
        List<VersionNode> nodes = versionMap.get(versionNo);
        if (nodes != null) {
            nodes.sort(Comparator.comparing(n -> n.key));
            nodes.forEach(fileListModel::addElement);
        }
    }

    // --- 右键菜单 (保持不变，作为快捷操作) ---
    private void showFileContextMenu(VersionNode node, MouseEvent e) {
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new AnAction("Compare with Local", "Compare with current local file", AllIcons.Actions.Diff) {
            @Override public void actionPerformed(@NotNull AnActionEvent e) { compareWithLocal(node); }
        });
        group.add(new AnAction("Compare with Previous Version", "Compare with previous cloud version", AllIcons.Actions.Diff) {
            @Override public void actionPerformed(@NotNull AnActionEvent e) { compareWithPreviousVersion(node); }
        });
        ActionPopupMenu popup = ActionManager.getInstance().createActionPopupMenu("ProjectHistoryFilePopup", group);
        popup.getComponent().show(e.getComponent(), e.getX(), e.getY());
    }

    @Override
    protected Action[] createActions() {
        // --- 🔴 修改点：添加 Compare 按钮 ---
        // 创建一个 Action，点击后弹出选择菜单
        Action compareAction = new AbstractAction("Compare...") {
            @Override
            public void actionPerformed(ActionEvent e) {
                VersionNode selected = fileList.getSelectedValue();
                if (selected == null) {
                    Messages.showWarningDialog("请先在右侧选择一个文件。", "提示");
                    return;
                }

                // 构建菜单
                DefaultActionGroup group = new DefaultActionGroup();
                group.add(new AnAction("Compare with Previous Version") {
                    @Override public void actionPerformed(@NotNull AnActionEvent e) { compareWithPreviousVersion(selected); }
                });
                group.add(new AnAction("Compare with Local") {
                    @Override public void actionPerformed(@NotNull AnActionEvent e) { compareWithLocal(selected); }
                });

                // 显示 Popup
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
                compareAction,
                getCancelAction()
        };
    }

    // --- 逻辑 0: 浏览文件内容 (修改为在 Tab 页打开) ---
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

                        // --- 🔴 核心修改：使用 LightVirtualFile 在标签页打开 ---
                        // 构造文件名：类名_v版本号.java (例如: MyClass_v10.java)
                        String fileName = node.key.substring(node.key.lastIndexOf('.') + 1) + "_v" + node.versionNo + ".java";
                        LightVirtualFile virtualFile = new LightVirtualFile(fileName, JavaFileType.INSTANCE, content);

                        // 设置为只读 (可选)
                        virtualFile.setWritable(false);

                        // 打开编辑器标签页
                        FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, virtualFile), true);
                        // --------------------------------------------------
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    client.shutdown();
                }
            }
        });
    }

    // --- 辅助：显示 Diff (修改为非模态窗口) ---
    private void showDiff(String contentA, String contentB, String titleA, String titleB, String fileName) {
        DiffContentFactory factory = DiffContentFactory.getInstance();
        SimpleDiffRequest request = new SimpleDiffRequest("Compare " + fileName,
                factory.create(project, contentA, JavaFileType.INSTANCE),
                factory.create(project, contentB, JavaFileType.INSTANCE),
                titleA, titleB);

        // --- 🔴 核心修复：使用 ChainDiffVirtualFile 在编辑器标签页打开 ---
        // 1. 将请求包装为 Chain
        SimpleDiffRequestChain chain = new SimpleDiffRequestChain(request);

        // 2. 创建表示 Diff 的虚拟文件
        ChainDiffVirtualFile virtualFile = new ChainDiffVirtualFile(chain, "Diff: " + fileName);

        // 3. 使用文件编辑器打开
        FileEditorManager.getInstance(project).openFile(virtualFile, true);
    }

    private void showDiffWithLocal(VirtualFile localFile, String remoteContent, String remoteTitle, String fileName) {
        DiffContentFactory factory = DiffContentFactory.getInstance();
        SimpleDiffRequest request = new SimpleDiffRequest("Compare " + fileName,
                factory.create(project, remoteContent, JavaFileType.INSTANCE), // Left: History
                factory.create(project, localFile), // Right: Local
                remoteTitle, "Local (Current)");

        // --- 🔴 核心修复：同上 ---
        SimpleDiffRequestChain chain = new SimpleDiffRequestChain(request);
        ChainDiffVirtualFile virtualFile = new ChainDiffVirtualFile(chain, "Diff: " + fileName);
        FileEditorManager.getInstance(project).openFile(virtualFile, true);
    }

    // --- 逻辑 1: 与上一版本比较 ---
    private void compareWithPreviousVersion(VersionNode currentVersionNode) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Fetching Previous Version...", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                BapRpcClient client = new BapRpcClient();
                try {
                    client.connect(uri, user, pwd);

                    Long currentVersionNo = (long) currentVersionNode.versionNo;
                    String fullClass = currentVersionNode.key;

                    List<VersionNode> versionNodes = client.getService().queryFileHistory(projectUuid, fullClass);

                    // 筛选 < currentVersion 的最大版本
                    Optional<VersionNode> previousNodeOpt = versionNodes.stream()
                            .filter(item -> item.versionNo < currentVersionNo)
                            .max(Comparator.comparingInt(item -> Math.toIntExact(item.versionNo)));

                    if (!previousNodeOpt.isPresent()) {
                        ApplicationManager.getApplication().invokeLater(() ->
                                Messages.showInfoMessage("没有更早的历史版本。", "提示"));
                        return;
                    }

                    VersionNode previousNode = previousNodeOpt.get();

                    // 获取代码
                    CJavaCode currentCode = client.getService().getHistoryCode(currentVersionNode.getUuid());
                    CJavaCode prevCode = client.getService().getHistoryCode(previousNode.getUuid());

                    String contentA = (prevCode != null) ? prevCode.code : "";
                    String contentB = (currentCode != null) ? currentCode.code : "";

                    // 显示 Diff (Left: Previous, Right: Current)
                    ApplicationManager.getApplication().invokeLater(() ->
                            showDiff(contentA, contentB,
                                    "Previous (v" + previousNode.versionNo + ")",
                                    "Current (v" + currentVersionNode.versionNo + ")",
                                    fullClass)
                    );

                } catch (Exception e) {
                    e.printStackTrace();
                    ApplicationManager.getApplication().invokeLater(() ->
                            Messages.showErrorDialog("比对失败: " + e.getMessage(), "错误"));
                } finally {
                    client.shutdown();
                }
            }
        });
    }

    // --- 逻辑 2: 与本地比较 ---
    private void compareWithLocal(VersionNode historyNode) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Fetching Cloud Code...", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                BapRpcClient client = new BapRpcClient();
                try {
                    // 查找本地文件
                    VirtualFile localFile = ReadAction.compute(() -> {
                        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
                        PsiClass psiClass = JavaPsiFacade.getInstance(project).findClass(historyNode.key, scope);
                        return psiClass != null ? psiClass.getContainingFile().getVirtualFile() : null;
                    });

                    if (localFile == null) {
                        ApplicationManager.getApplication().invokeLater(() ->
                                Messages.showWarningDialog("在本地项目中找不到类: " + historyNode.key, "未找到文件"));
                        return;
                    }

                    // 获取云端代码
                    client.connect(uri, user, pwd);
                    CJavaCode cloudCode = client.getService().getHistoryCode(historyNode.getUuid());
                    String remoteContent = (cloudCode != null) ? cloudCode.code : "";

                    // 显示 Diff (Left: History, Right: Local)
                    ApplicationManager.getApplication().invokeLater(() ->
                            showDiffWithLocal(localFile, remoteContent,
                                    "History (v" + historyNode.versionNo + ")",
                                    historyNode.key)
                    );

                } catch (Exception e) {
                    ApplicationManager.getApplication().invokeLater(() ->
                            Messages.showErrorDialog("比对失败: " + e.getMessage(), "错误"));
                } finally {
                    client.shutdown();
                }
            }
        });
    }
}