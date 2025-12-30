package com.bap.dev.ui;

import bap.java.CJavaCode;
import bap.md.ver.VersionNode;
import com.bap.dev.BapRpcClient;
import com.bap.dev.service.BapConnectionManager;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.chains.SimpleDiffRequestChain;
import com.intellij.diff.editor.ChainDiffVirtualFile;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.icons.AllIcons;
import com.intellij.ide.DataManager;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.ide.highlighter.XmlFileType;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
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
import cplugin.ms.dto.CResFileDto;
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
import java.io.File;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

public class ProjectHistoryDialog extends DialogWrapper {

    private final Project project;
    private final List<VersionNode> projectVersions; // 🔴 修改：项目级版本列表
    private final String projectUuid;
    private final String uri;
    private final String user;
    private final String pwd;

    private JBTable versionTable;
    private JBList<VersionNode> fileList;
    private DefaultListModel<VersionNode> fileListModel;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public ProjectHistoryDialog(Project project, List<VersionNode> projectVersions, String projectUuid, String uri, String user, String pwd) {
        super(project);
        this.project = project;
        this.projectVersions = projectVersions;
        this.projectUuid = projectUuid;
        this.uri = uri;
        this.user = user;
        this.pwd = pwd;

        // 默认按时间倒序
        this.projectVersions.sort((o1, o2) -> Long.compare(o2.commitTime, o1.commitTime));

        setTitle("Project Cloud History");
        setModal(false);
        setSize(950, 600);
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JBSplitter splitter = new JBSplitter(false, 0.45f);

        // --- 左侧：版本列表 (Project Versions) ---
        String[] columnNames = {"Ver", "Time", "User", "Comments"};
        Object[][] data = new Object[projectVersions.size()][4];

        for (int i = 0; i < projectVersions.size(); i++) {
            VersionNode node = projectVersions.get(i);
            data[i][0] = node.versionNo;
            data[i][1] = dateFormat.format(new Date(node.commitTime));
            data[i][2] = node.commiter;
            data[i][3] = node.comments;
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

        // 🔴 监听选中，加载详情
        versionTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = versionTable.getSelectedRow();
                if (row >= 0) {
                    Long versionNo = (Long) versionTable.getValueAt(row, 0);
                    fetchAndShowFiles(versionNo);
                }
            }
        });

        // --- 右侧：文件列表 (File Nodes) ---
        fileListModel = new DefaultListModel<>();
        fileList = new JBList<>(fileListModel);
        fileList.setEmptyText("Select a version to view changed files");
        fileList.setCellRenderer(new ColoredListCellRenderer<VersionNode>() {
            @Override
            protected void customizeCellRenderer(@NotNull JList<? extends VersionNode> list, VersionNode value, int index, boolean selected, boolean hasFocus) {
                String name = value.key;
                boolean isRes = isResourceFile(name);
                setIcon(isRes ? AllIcons.FileTypes.Xml : AllIcons.FileTypes.Java); // 简单区分图标

                int lastSep = Math.max(name.lastIndexOf('.'), name.lastIndexOf('/'));
                String shortName = (lastSep > 0) ? name.substring(lastSep + 1) : name;
                if (!isRes && lastSep > 0) {
                    // Java类名处理
                    shortName = name.substring(lastSep + 1);
                }

                append(shortName, SimpleTextAttributes.REGULAR_ATTRIBUTES);
                append(" (" + name + ")", SimpleTextAttributes.GRAYED_ATTRIBUTES);
            }
        });

        fileList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    VersionNode selected = fileList.getSelectedValue();
                    if (selected != null) showFileContent(selected);
                }
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
        left.setBorder(IdeBorderFactory.createTitledBorder("Project Versions", false));
        JComponent right = new JBScrollPane(fileList);
        right.setBorder(IdeBorderFactory.createTitledBorder("Changed Files in Version", false));

        splitter.setFirstComponent(left);
        splitter.setSecondComponent(right);

        // 默认选中第一行（最新的版本）
        if (!projectVersions.isEmpty()) {
            versionTable.setRowSelectionInterval(0, 0);
        }

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(splitter, BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(950, 600));
        return panel;
    }

    // 🔴 判断是否为资源文件 (根据 Key 是否包含 '/')
    private boolean isResourceFile(String key) {
        return key != null && key.contains("/");
    }

    // 🔴 异步加载文件列表
    private void fetchAndShowFiles(Long versionNo) {
        fileList.setPaintBusy(true);
        fileListModel.clear();

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Loading Version Details...", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                BapRpcClient client = BapConnectionManager.getInstance(project).getSharedClient(uri, user, pwd);
                try {
                    
                    // 🔴 调用新接口查询详情
                    // versionNo 需要转 int (假设 API 定义是 int)
                    List<VersionNode> details = client.getService().queryVersionDetail(projectUuid, versionNo.intValue(), true);

                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (details != null) {
                            details.sort(Comparator.comparing(n -> n.key));
                            details.forEach(fileListModel::addElement);
                        }
                        fileList.setPaintBusy(false);
                    });
                } catch (Exception e) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        fileList.setPaintBusy(false);
                        Messages.showErrorDialog("加载详情失败: " + e.getMessage(), "Error");
                    });
                }
            }
        });
    }

    // 🔴 逻辑 0: 获取并显示文件内容
    private void showFileContent(VersionNode node) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Fetching Content...", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                BapRpcClient client = BapConnectionManager.getInstance(project).getSharedClient(uri, user, pwd);
                try {
                    
                    String content = "";
                    String fileName = "";

                    if (isResourceFile(node.key)) {
                        // 资源文件
                        CResFileDto res = client.getService().getHistoryFile(node.getUuid());
                        if (res != null && res.getFileBin() != null) {
                            content = new String(res.getFileBin()); // 暂定资源文件是文本
                            fileName = new File(node.key).getName() + "_v" + node.versionNo;
                        }
                    } else {
                        // Java 代码
                        CJavaCode code = client.getService().getHistoryCode(node.getUuid());
                        if (code != null) {
                            content = code.code;
                            fileName = node.key.substring(node.key.lastIndexOf('.') + 1) + "_v" + node.versionNo + ".java";
                        }
                    }

                    final String finalContent = content;
                    final String finalName = fileName;

                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (finalContent == null || finalContent.isEmpty()) {
                            Messages.showWarningDialog("内容为空或非文本格式。", "提示");
                            return;
                        }
                        LightVirtualFile virtualFile = new LightVirtualFile(finalName,
                                isResourceFile(node.key) ? XmlFileType.INSTANCE : JavaFileType.INSTANCE,
                                finalContent);
                        virtualFile.setWritable(false);
                        FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, virtualFile), true);
                    });

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    // 🔴 逻辑 2: 与本地比较
    private void compareWithLocal(VersionNode historyNode) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Fetching Cloud Content...", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                BapRpcClient client = BapConnectionManager.getInstance(project).getSharedClient(uri, user, pwd);
                try {
                    
                    String remoteContent = "";
                    VirtualFile localFile = null;

                    // 1. 获取本地文件 & 远程内容
                    if (isResourceFile(historyNode.key)) {
                        // 资源处理
                        final String resPath = historyNode.key;
                        localFile = ReadAction.compute(() -> {
                            // 假设本地结构是 src/res/...
                            // 需要找到模块根，然后找 src/res/path
                            // 这里简单尝试在项目范围内搜索，或者需要传入模块根
                            // 简化处理：尝试在 ProjectScope 找名字匹配的
                            // 更好的方式：根据 historyNode.key (相对路径) 去找
                            // 暂时留空，提示用户需要手动打开，或者尝试用 FilenameIndex 查找
                            return null; // 资源文件定位比较复杂，视情况实现
                        });

                        CResFileDto res = client.getService().getHistoryFile(historyNode.getUuid());
                        if (res != null) remoteContent = new String(res.getFileBin());

                    } else {
                        // Java 处理
                        localFile = ReadAction.compute(() -> {
                            GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
                            PsiClass psiClass = JavaPsiFacade.getInstance(project).findClass(historyNode.key, scope);
                            return psiClass != null ? psiClass.getContainingFile().getVirtualFile() : null;
                        });

                        CJavaCode code = client.getService().getHistoryCode(historyNode.getUuid());
                        if (code != null) remoteContent = code.code;
                    }

                    final String finalRemoteContent = remoteContent;
                    final VirtualFile finalLocalFile = localFile;

                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (finalLocalFile == null) {
                            Messages.showWarningDialog("在本地项目中未找到对应的文件: " + historyNode.key, "未找到文件");
                            // 也可以选择展示只读内容
                            return;
                        }
                        showDiffWithLocal(finalLocalFile, finalRemoteContent,
                                "History (v" + historyNode.versionNo + ")",
                                historyNode.key);
                    });

                } catch (Exception e) {
                    ApplicationManager.getApplication().invokeLater(() ->
                            Messages.showErrorDialog("比对失败: " + e.getMessage(), "错误"));
                }
            }
        });
    }

    // --- 逻辑 1: 与上一版本比较 (依然复杂，因为需要找到该文件的上一个版本) ---
    private void compareWithPreviousVersion(VersionNode currentFileNode) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Finding Previous Version...", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                BapRpcClient client = BapConnectionManager.getInstance(project).getSharedClient(uri, user, pwd);
                try {
                    

                    // 1. 查找该文件的所有历史，以找到前一个版本
                    // 假设 queryFileHistory 接口依然可用，这是定位单文件历史的最佳方式
                    List<VersionNode> fileHistory = client.getService().queryFileHistory(projectUuid, currentFileNode.key);

                    Optional<VersionNode> prevOpt = fileHistory.stream()
                            .filter(n -> n.versionNo < currentFileNode.versionNo)
                            .max(Comparator.comparingLong(n -> n.versionNo));

                    if (!prevOpt.isPresent()) {
                        ApplicationManager.getApplication().invokeLater(() ->
                                Messages.showInfoMessage("没有找到更早的版本。", "提示"));
                        return;
                    }
                    VersionNode prevNode = prevOpt.get();

                    // 2. 获取两份内容
                    String currentContent = "";
                    String prevContent = "";

                    if (isResourceFile(currentFileNode.key)) {
                        CResFileDto cur = client.getService().getHistoryFile(currentFileNode.getUuid());
                        CResFileDto prev = client.getService().getHistoryFile(prevNode.getUuid());
                        if (cur != null) currentContent = new String(cur.getFileBin());
                        if (prev != null) prevContent = new String(prev.getFileBin());
                    } else {
                        CJavaCode cur = client.getService().getHistoryCode(currentFileNode.getUuid());
                        CJavaCode prev = client.getService().getHistoryCode(prevNode.getUuid());
                        if (cur != null) currentContent = cur.code;
                        if (prev != null) prevContent = prev.code;
                    }

                    final String c1 = prevContent;
                    final String c2 = currentContent;

                    ApplicationManager.getApplication().invokeLater(() ->
                            showDiff(c1, c2,
                                    "Previous (v" + prevNode.versionNo + ")",
                                    "Current (v" + currentFileNode.versionNo + ")",
                                    currentFileNode.key)
                    );

                } catch (Exception e) {
                    e.printStackTrace();
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

    private void showDiffWithLocal(VirtualFile localFile, String remoteContent, String remoteTitle, String fileName) {
        DiffContentFactory factory = DiffContentFactory.getInstance();
        SimpleDiffRequest request = new SimpleDiffRequest("Compare " + fileName,
                factory.create(project, remoteContent, JavaFileType.INSTANCE),
                factory.create(project, localFile),
                remoteTitle, "Local (Current)");
        SimpleDiffRequestChain chain = new SimpleDiffRequestChain(request);
        ChainDiffVirtualFile virtualFile = new ChainDiffVirtualFile(chain, "Diff: " + fileName);
        FileEditorManager.getInstance(project).openFile(virtualFile, true);
    }

    // --- 🔴 修改：右键菜单新增 Save to Local ---
    private void showFileContextMenu(VersionNode node, MouseEvent e) {
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new AnAction("Compare with Local", "", AllIcons.Actions.Diff) {
            @Override public void actionPerformed(@NotNull AnActionEvent e) { compareWithLocal(node); }
        });
        group.add(new AnAction("Compare with Previous Version", "", AllIcons.Actions.Diff) {
            @Override public void actionPerformed(@NotNull AnActionEvent e) { compareWithPreviousVersion(node); }
        });

        // 🔴 新增：资源文件下载选项
        if (isResourceFile(node.key)) {
            group.addSeparator();
            group.add(new AnAction("Save to Local", "Download and save to local disk", AllIcons.Actions.Download) {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e) {
                    saveResourceToLocal(node);
                }
            });
        }

        ActionPopupMenu popup = ActionManager.getInstance().createActionPopupMenu("ProjectHistoryFilePopup", group);
        popup.getComponent().show(e.getComponent(), e.getX(), e.getY());
    }

    // --- 🔴 新增：下载资源文件逻辑 ---
    private void saveResourceToLocal(VersionNode node) {
        // 1. 选择保存位置
        FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor();
        descriptor.setTitle("Select Destination Folder");
        VirtualFile targetDir = FileChooser.chooseFile(descriptor, project, null);
        if (targetDir == null) return;

        // 2. 后台下载并保存
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Downloading Resource...", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                BapRpcClient client = BapConnectionManager.getInstance(project).getSharedClient(uri, user, pwd);
                try {
                    client.connect(uri, user, pwd);
                    CResFileDto resFile = client.getService().getHistoryFile(node.getUuid());

                    if (resFile != null && resFile.getFileBin() != null) {
                        // 从 key (如 src/res/a.png) 提取文件名 a.png
                        String fileName = new File(node.key).getName();
                        File destFile = new File(targetDir.getPath(), fileName);

                        // 写入文件
                        Files.write(destFile.toPath(), resFile.getFileBin());

                        ApplicationManager.getApplication().invokeLater(() ->
                                Messages.showInfoMessage("Saved to: " + destFile.getAbsolutePath(), "Success"));
                    } else {
                        ApplicationManager.getApplication().invokeLater(() ->
                                Messages.showErrorDialog("File content is empty or not found.", "Error"));
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    ApplicationManager.getApplication().invokeLater(() ->
                            Messages.showErrorDialog("Download failed: " + e.getMessage(), "Error"));
                } finally {
                    client.shutdown();
                }
            }
        });
    }

    @Override
    protected Action[] createActions() {
        Action compareAction = new AbstractAction("Compare...") {
            @Override
            public void actionPerformed(ActionEvent e) {
                VersionNode selected = fileList.getSelectedValue();
                if (selected == null) {
                    Messages.showWarningDialog("请先在右侧选择一个文件。", "提示");
                    return;
                }
                DefaultActionGroup group = new DefaultActionGroup();
                group.add(new AnAction("Compare with Previous Version") {
                    @Override public void actionPerformed(@NotNull AnActionEvent e) { compareWithPreviousVersion(selected); }
                });
                group.add(new AnAction("Compare with Local") {
                    @Override public void actionPerformed(@NotNull AnActionEvent e) { compareWithLocal(selected); }
                });

                // 🔴 底部按钮也加上 Save to Local 方便操作
                if (isResourceFile(selected.key)) {
                    group.addSeparator();
                    group.add(new AnAction("Save to Local") {
                        @Override public void actionPerformed(@NotNull AnActionEvent e) { saveResourceToLocal(selected); }
                    });
                }

                ListPopup popup = JBPopupFactory.getInstance().createActionGroupPopup(
                        "Select Action", group, DataManager.getInstance().getDataContext((Component) e.getSource()),
                        JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true);
                popup.showUnderneathOf((Component) e.getSource());
            }
        };
        return new Action[]{compareAction, getCancelAction()};
    }
}