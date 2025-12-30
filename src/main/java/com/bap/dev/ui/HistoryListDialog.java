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
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

public class HistoryListDialog extends DialogWrapper {

    private final Project project;
    private final VirtualFile localFile;
    private final List<VersionNode> historyList;
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

    private boolean isResource() {
        return localFile != null && !"java".equalsIgnoreCase(localFile.getExtension());
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        String[] columnNames = {"Ver", "Time", "User", "Comments"};
        Object[][] data = new Object[historyList.size()][4];
        for (int i = 0; i < historyList.size(); i++) {
            VersionNode node = historyList.get(i);
            data[i][0] = node.versionNo;
            data[i][1] = dateFormat.format(new Date(node.commitTime));
            data[i][2] = node.commiter;
            data[i][3] = node.comments;
        }

        DefaultTableModel model = new DefaultTableModel(data, columnNames) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };

        table = new JBTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(24);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));

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

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = table.getSelectedRow();
                    if (row >= 0) showFileContent(historyList.get(row));
                }
                if (SwingUtilities.isRightMouseButton(e)) {
                    int row = table.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        table.setRowSelectionInterval(row, row);
                        showContextMenu(historyList.get(row), e);
                    }
                }
            }
        });

        JBScrollPane scrollPane = new JBScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(700, 400));
        scrollPane.setBorder(JBUI.Borders.empty());
        return scrollPane;
    }

    private void showContextMenu(VersionNode node, MouseEvent e) {
        DefaultActionGroup group = new DefaultActionGroup();

        group.add(new AnAction("Compare with Previous Version", "", AllIcons.Actions.Diff) {
            @Override public void actionPerformed(@NotNull AnActionEvent e) { compareWithPrevious(node); }
        });
        group.add(new AnAction("Compare with Local", "", AllIcons.Actions.Diff) {
            @Override public void actionPerformed(@NotNull AnActionEvent e) { compareWithLocal(node); }
        });

        if (isResource()) {
            group.addSeparator();
            group.add(new AnAction("Save to Local", "Download and save to local disk", AllIcons.Actions.Download) {
                @Override public void actionPerformed(@NotNull AnActionEvent e) { saveResourceToLocal(node); }
            });
        }

        ActionPopupMenu popup = ActionManager.getInstance().createActionPopupMenu("HistoryListPopup", group);
        popup.getComponent().show(e.getComponent(), e.getX(), e.getY());
    }

    // --- 核心修改：动态创建底部按钮 ---
    @Override
    protected Action[] createActions() {
        List<Action> actions = new ArrayList<>();

        // 1. Rollback
        actions.add(new DialogWrapperAction("Rollback") {
            @Override
            protected void doAction(ActionEvent e) {
                int row = table.getSelectedRow();
                if (row >= 0) {
                    VersionNode selected = historyList.get(row);
                    if (Messages.showYesNoDialog(project, "确定要回滚到版本 v" + selected.versionNo + " 吗？\n本地未提交的修改将丢失。", "确认还原", Messages.getQuestionIcon()) == Messages.YES) {
                        updateToLocal(selected);
                    }
                } else {
                    Messages.showWarningDialog("请先选择一个版本。", "提示");
                }
            }
        });

        // 2. Compare
        actions.add(new AbstractAction("Compare...") {
            @Override
            public void actionPerformed(ActionEvent e) {
                int row = table.getSelectedRow();
                if (row < 0) { Messages.showWarningDialog("请先选择一个版本。", "提示"); return; }
                VersionNode selected = historyList.get(row);

                DefaultActionGroup group = new DefaultActionGroup();
                group.add(new AnAction("Compare with Previous Version") {
                    @Override public void actionPerformed(@NotNull AnActionEvent e) { compareWithPrevious(selected); }
                });
                group.add(new AnAction("Compare with Local") {
                    @Override public void actionPerformed(@NotNull AnActionEvent e) { compareWithLocal(selected); }
                });

                ListPopup popup = JBPopupFactory.getInstance().createActionGroupPopup(
                        "Select Comparison", group, DataManager.getInstance().getDataContext((Component) e.getSource()),
                        JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true);
                popup.showUnderneathOf((Component) e.getSource());
            }
        });

        // 3. 🔴 Save to Local (仅资源文件显示)
        if (isResource()) {
            actions.add(new AbstractAction("Save to Local") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    int row = table.getSelectedRow();
                    if (row < 0) { Messages.showWarningDialog("请先选择一个版本。", "提示"); return; }
                    saveResourceToLocal(historyList.get(row));
                }
            });
        }

        // 4. Cancel
        actions.add(getCancelAction());

        return actions.toArray(new Action[0]);
    }

    private void saveResourceToLocal(VersionNode node) {
        FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor();
        descriptor.setTitle("Select Destination Folder");
        VirtualFile targetDir = FileChooser.chooseFile(descriptor, project, null);
        if (targetDir == null) return;

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Downloading Resource...", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                BapRpcClient client = BapConnectionManager.getInstance(project).getSharedClient(uri, user, pwd);
                try {
                    client.connect(uri, user, pwd);
                    CResFileDto resFile = client.getService().getHistoryFile(node.getUuid());

                    if (resFile != null && resFile.getFileBin() != null) {
                        String ext = localFile.getExtension();
                        String fileName = localFile.getNameWithoutExtension() + "_v" + node.versionNo;
                        if (ext != null && !ext.isEmpty()) fileName += "." + ext;

                        File destFile = new File(targetDir.getPath(), fileName);
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

    private void showFileContent(VersionNode node) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Fetching Content...", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                BapRpcClient client = BapConnectionManager.getInstance(project).getSharedClient(uri, user, pwd);
                try {
                    client.connect(uri, user, pwd);
                    String content = "";
                    if (isResource()) {
                        CResFileDto res = client.getService().getHistoryFile(node.getUuid());
                        if (res != null && res.getFileBin() != null) content = new String(res.getFileBin());
                    } else {
                        CJavaCode code = client.getService().getHistoryCode(node.getUuid());
                        if (code != null) content = code.code;
                    }

                    final String finalContent = content;
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (finalContent == null || finalContent.isEmpty()) {
                            Messages.showWarningDialog("内容为空或无法显示。", "提示");
                            return;
                        }
                        String fileName = localFile.getNameWithoutExtension() + "_v" + node.versionNo + "." + localFile.getExtension();
                        LightVirtualFile virtualFile = new LightVirtualFile(fileName,
                                isResource() ? XmlFileType.INSTANCE : JavaFileType.INSTANCE,
                                finalContent);
                        virtualFile.setWritable(false);
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

    private void compareWithPrevious(VersionNode currentNode) {
        Optional<VersionNode> prevOpt = historyList.stream()
                .filter(n -> n.versionNo < currentNode.versionNo)
                .max(Comparator.comparingInt(n -> Math.toIntExact(n.versionNo)));

        if (!prevOpt.isPresent()) {
            Messages.showInfoMessage("没有更早的历史版本。", "提示");
            return;
        }
        VersionNode prevNode = prevOpt.get();

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Fetching History...", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                BapRpcClient client = BapConnectionManager.getInstance(project).getSharedClient(uri, user, pwd);
                try {
                    client.connect(uri, user, pwd);
                    String curContent = "", prevContent = "";

                    if (isResource()) {
                        CResFileDto cur = client.getService().getHistoryFile(currentNode.getUuid());
                        CResFileDto prev = client.getService().getHistoryFile(prevNode.getUuid());
                        if (cur != null) curContent = new String(cur.getFileBin());
                        if (prev != null) prevContent = new String(prev.getFileBin());
                    } else {
                        CJavaCode cur = client.getService().getHistoryCode(currentNode.getUuid());
                        CJavaCode prev = client.getService().getHistoryCode(prevNode.getUuid());
                        if (cur != null) curContent = cur.code;
                        if (prev != null) prevContent = prev.code;
                    }

                    final String c1 = prevContent;
                    final String c2 = curContent;

                    ApplicationManager.getApplication().invokeLater(() ->
                            showDiff(c1, c2, "Previous (v" + prevNode.versionNo + ")", "Current (v" + currentNode.versionNo + ")", localFile.getName())
                    );
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    client.shutdown();
                }
            }
        });
    }

    private void compareWithLocal(VersionNode node) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Fetching History...", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                BapRpcClient client = BapConnectionManager.getInstance(project).getSharedClient(uri, user, pwd);
                try {
                    client.connect(uri, user, pwd);
                    String remoteContent = "";

                    if (isResource()) {
                        CResFileDto res = client.getService().getHistoryFile(node.getUuid());
                        if (res != null && res.getFileBin() != null) remoteContent = new String(res.getFileBin());
                    } else {
                        CJavaCode code = client.getService().getHistoryCode(node.getUuid());
                        if (code != null) remoteContent = code.code;
                    }

                    final String finalContent = remoteContent;
                    ApplicationManager.getApplication().invokeLater(() ->
                            showDiffWithLocal(finalContent, "Remote (v" + node.versionNo + ")")
                    );
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    client.shutdown();
                }
            }
        });
    }

    private void updateToLocal(VersionNode node) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Restoring...", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                BapRpcClient client = BapConnectionManager.getInstance(project).getSharedClient(uri, user, pwd);
                try {
                    client.connect(uri, user, pwd);
                    byte[] content = null;

                    if (isResource()) {
                        CResFileDto res = client.getService().getHistoryFile(node.getUuid());
                        if (res != null) content = res.getFileBin();
                    } else {
                        CJavaCode code = client.getService().getHistoryCode(node.getUuid());
                        if (code != null && code.code != null) content = code.code.getBytes(StandardCharsets.UTF_8);
                    }

                    final byte[] finalContent = content;
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (finalContent != null) {
                            try {
                                WriteAction.run(() -> {
                                    localFile.setBinaryContent(finalContent);
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
                factory.create(project, contentA, isResource() ? XmlFileType.INSTANCE : JavaFileType.INSTANCE),
                factory.create(project, contentB, isResource() ? XmlFileType.INSTANCE : JavaFileType.INSTANCE),
                titleA, titleB);
        SimpleDiffRequestChain chain = new SimpleDiffRequestChain(request);
        ChainDiffVirtualFile virtualFile = new ChainDiffVirtualFile(chain, "Diff: " + fileName);
        FileEditorManager.getInstance(project).openFile(virtualFile, true);
    }

    private void showDiffWithLocal(String remoteContent, String remoteTitle) {
        DiffContentFactory factory = DiffContentFactory.getInstance();
        SimpleDiffRequest request = new SimpleDiffRequest("Compare " + localFile.getName(),
                factory.create(project, remoteContent, isResource() ? XmlFileType.INSTANCE : JavaFileType.INSTANCE),
                factory.create(project, localFile),
                remoteTitle, "Local (Current)");
        SimpleDiffRequestChain chain = new SimpleDiffRequestChain(request);
        ChainDiffVirtualFile virtualFile = new ChainDiffVirtualFile(chain, "Diff: " + remoteTitle);
        FileEditorManager.getInstance(project).openFile(virtualFile, true);
    }
}