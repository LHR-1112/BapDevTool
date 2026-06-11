package com.bap.dev.ui;

import bap.java.CJavaConst;
import com.bap.dev.i18n.BapBundle;
import com.bap.dev.service.DtoSyncService;
import com.bap.dev.service.DtoSyncService.DtoSyncEntry;
import com.bap.dev.service.DtoSyncService.DtoSyncStatus;
import com.bap.dev.service.MetadataRpcClient;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.DiffRequestPanel;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.icons.AllIcons;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.Splitter;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.CheckboxTree;
import com.intellij.ui.CheckedTreeNode;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.tree.TreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import panelxpro.metadata.dto.DomainInfoDto;

import javax.swing.*;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

public class DtoSyncDialog extends DialogWrapper {

    private static final Logger LOG = Logger.getInstance(DtoSyncDialog.class);

    private final Project project;
    private final List<DomainInfoDto> domains;
    private final MetadataRpcClient metadataClient;
    private final VirtualFile moduleRoot;

    private List<DtoSyncEntry> entries;
    private String currentDomainCode;

    private JBLabel domainLabel;
    private CheckboxTree fileTree;
    private CheckedTreeNode rootNode;
    private DiffRequestPanel diffPanel;
    private JPanel rightPanel;
    private CardLayout rightCardLayout;

    private static final String CARD_PLACEHOLDER = "placeholder";
    private static final String CARD_DIFF = "diff";

    public DtoSyncDialog(Project project,
                         List<DtoSyncEntry> entries,
                         String domainCode,
                         List<DomainInfoDto> domains,
                         MetadataRpcClient metadataClient,
                         VirtualFile moduleRoot) {
        super(project);
        this.project = project;
        this.entries = entries;
        this.currentDomainCode = domainCode;
        this.domains = domains;
        this.metadataClient = metadataClient;
        this.moduleRoot = moduleRoot;

        this.rootNode = new CheckedTreeNode(null);
        buildTree();
        this.fileTree = new CheckboxTree(new FileTreeRenderer(), rootNode);
        this.diffPanel = DiffManager.getInstance().createRequestPanel(project, getDisposable(), null);

        setTitle(BapBundle.message("dialog.dto.sync.title"));
        setOKButtonText(BapBundle.message("dialog.dto.sync.button.sync"));
        init();
    }

    @Override
    protected @Nullable JComponent createNorthPanel() {
        domainLabel = new JBLabel(BapBundle.message("dialog.dto.sync.domain.label", currentDomainCode));
        domainLabel.setFont(domainLabel.getFont().deriveFont(Font.BOLD));
        domainLabel.setBorder(JBUI.Borders.emptyLeft(4));

        JButton switchButton = new JButton(BapBundle.message("dialog.dto.sync.domain.switch"));
        switchButton.addActionListener(e -> onSwitchDomain());

        JPanel toolbar = new JPanel(new BorderLayout());
        toolbar.add(domainLabel, BorderLayout.WEST);
        toolbar.add(switchButton, BorderLayout.EAST);
        toolbar.setBorder(JBUI.Borders.emptyBottom(6));
        return toolbar;
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        fileTree.addTreeSelectionListener(e -> {
            javax.swing.tree.TreePath path = e.getPath();
            if (path == null) return;
            Object last = path.getLastPathComponent();
            if (last instanceof CheckedTreeNode) {
                Object userObject = ((CheckedTreeNode) last).getUserObject();
                if (userObject instanceof DtoSyncEntry) {
                    showDiffForEntry((DtoSyncEntry) userObject);
                    rightCardLayout.show(rightPanel, CARD_DIFF);
                }
            }
        });

        JBScrollPane treePane = new JBScrollPane(fileTree);
        treePane.setBorder(JBUI.Borders.empty());

        rightCardLayout = new CardLayout();
        rightPanel = new JPanel(rightCardLayout);

        JBLabel placeholder = new JBLabel(BapBundle.message("dialog.dto.sync.diff.placeholder"));
        placeholder.setHorizontalAlignment(SwingConstants.CENTER);
        placeholder.setForeground(JBColor.GRAY);
        rightPanel.add(placeholder, CARD_PLACEHOLDER);
        rightPanel.add(diffPanel.getComponent(), CARD_DIFF);
        rightCardLayout.show(rightPanel, CARD_PLACEHOLDER);

        Splitter splitter = new Splitter(false, 0.3f);
        splitter.setFirstComponent(treePane);
        splitter.setSecondComponent(rightPanel);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(splitter, BorderLayout.CENTER);
        wrapper.setPreferredSize(new Dimension(1200, 700));

        TreeUtil.expandAll(fileTree);
        return wrapper;
    }

    @Override
    protected JPanel createSouthAdditionalPanel() {
        JButton selectAll = new JButton(BapBundle.message("dialog.dto.sync.button.selectAll"));
        selectAll.addActionListener(e -> setAllChecked(true));

        JButton deselectAll = new JButton(BapBundle.message("dialog.dto.sync.button.deselectAll"));
        deselectAll.addActionListener(e -> setAllChecked(false));

        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        panel.add(selectAll);
        panel.add(deselectAll);
        return panel;
    }

    public List<DtoSyncEntry> getSelectedEntries() {
        List<DtoSyncEntry> result = new ArrayList<>();
        Enumeration<?> nodes = rootNode.depthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            Object obj = nodes.nextElement();
            if (obj instanceof CheckedTreeNode) {
                CheckedTreeNode node = (CheckedTreeNode) obj;
                if (node.isLeaf() && node.isChecked() && node.getUserObject() instanceof DtoSyncEntry) {
                    result.add((DtoSyncEntry) node.getUserObject());
                }
            }
        }
        return result;
    }

    private void buildTree() {
        Map<String, List<DtoSyncEntry>> grouped = new LinkedHashMap<>();
        for (DtoSyncEntry entry : entries) {
            String pkg = entry.getRemoteEntry().getPackageName();
            grouped.computeIfAbsent(pkg, k -> new ArrayList<>()).add(entry);
        }
        for (Map.Entry<String, List<DtoSyncEntry>> e : grouped.entrySet()) {
            CheckedTreeNode pkgNode = new CheckedTreeNode(e.getKey());
            pkgNode.setChecked(true);
            for (DtoSyncEntry entry : e.getValue()) {
                CheckedTreeNode fileNode = new CheckedTreeNode(entry);
                fileNode.setChecked(entry.getStatus() != DtoSyncStatus.DELETED);
                pkgNode.add(fileNode);
            }
            rootNode.add(pkgNode);
        }
    }

    private void rebuildTree() {
        rootNode.removeAllChildren();
        buildTree();
        ((DefaultTreeModel) fileTree.getModel()).reload();
        TreeUtil.expandAll(fileTree);
    }

    private void showDiffForEntry(DtoSyncEntry entry) {
        DiffContentFactory factory = DiffContentFactory.getInstance();
        String localText = entry.getLocalContent() != null ? entry.getLocalContent() : "";
        DiffContent localContent = factory.create(project, localText, JavaFileType.INSTANCE);
        String remoteText = entry.getRemoteEntry().getSourceCode();
        DiffContent remoteContent = factory.create(project, remoteText, JavaFileType.INSTANCE);
        SimpleDiffRequest request = new SimpleDiffRequest(
                entry.getRemoteEntry().getFileName(),
                localContent, remoteContent,
                BapBundle.message("dialog.dto.sync.label.local"),
                BapBundle.message("dialog.dto.sync.label.remote"));
        diffPanel.setRequest(request);
    }

    private void onSwitchDomain() {
        if (domains == null || domains.isEmpty()) {
            Messages.showWarningDialog(
                    BapBundle.message("dialog.domain.config.error.empty"),
                    BapBundle.message("notification.error_title"));
            return;
        }
        MetadataDomainDialog domainDialog = new MetadataDomainDialog(project, domains);
        if (!domainDialog.showAndGet()) return;

        String newCode = domainDialog.getDomainCode();
        if (newCode == null || newCode.equals(currentDomainCode)) return;

        ProgressManager.getInstance().run(new Task.Modal(project,
                BapBundle.message("dialog.dto.sync.domain.switching"), true) {
            private List<DtoSyncEntry> newEntries;
            private Exception error;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    DtoSyncService syncService = new DtoSyncService();
                    List<DtoSyncEntry> all = syncService.fetchAndCompare(
                            metadataClient.getService(), moduleRoot, newCode);
                    newEntries = all.stream()
                            .filter(e -> e.getStatus() != DtoSyncStatus.UNCHANGED)
                            .collect(Collectors.toList());
                } catch (Exception ex) {
                    error = ex;
                }
            }

            @Override
            public void onSuccess() {
                if (error != null) {
                    Messages.showErrorDialog(error.getMessage(),
                            BapBundle.message("notification.error_title"));
                    return;
                }
                entries = newEntries;
                currentDomainCode = newCode;
                writeDomainToConfig(newCode);

                domainLabel.setText(BapBundle.message("dialog.dto.sync.domain.label", newCode));
                rebuildTree();
                rightCardLayout.show(rightPanel, CARD_PLACEHOLDER);
            }
        });
    }

    private void writeDomainToConfig(String domainCode) {
        try {
            File confFile = new File(moduleRoot.getPath(), CJavaConst.PROJECT_DEVELOP_CONF_FILE);
            String content = Files.readString(confFile.toPath());
            String updated;
            if (content.contains("MetadataDomain=")) {
                updated = content.replaceAll("MetadataDomain=\"[^\"]*\"",
                        "MetadataDomain=\"" + domainCode + "\"");
            } else {
                updated = content.replaceFirst("/>", " MetadataDomain=\"" + domainCode + "\"/>");
            }
            Files.writeString(confFile.toPath(), updated);
        } catch (Exception e) {
            LOG.warn("Failed to write domain config", e);
        }
    }

    private void setAllChecked(boolean checked) {
        Enumeration<?> nodes = rootNode.depthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            Object obj = nodes.nextElement();
            if (obj instanceof CheckedTreeNode) {
                ((CheckedTreeNode) obj).setChecked(checked);
            }
        }
        fileTree.repaint();
    }

    private static class FileTreeRenderer extends CheckboxTree.CheckboxTreeCellRenderer {
        @Override
        public void customizeRenderer(JTree tree, Object value, boolean selected, boolean expanded,
                                      boolean leaf, int row, boolean hasFocus) {
            if (!(value instanceof CheckedTreeNode)) return;
            Object userObject = ((CheckedTreeNode) value).getUserObject();

            if (userObject instanceof String) {
                getTextRenderer().setIcon(AllIcons.Nodes.Package);
                getTextRenderer().append((String) userObject);
            } else if (userObject instanceof DtoSyncEntry) {
                DtoSyncEntry entry = (DtoSyncEntry) userObject;
                getTextRenderer().setIcon(AllIcons.FileTypes.Java);
                getTextRenderer().append(entry.getRemoteEntry().getFileName());

                DtoSyncStatus status = entry.getStatus();
                if (status == DtoSyncStatus.NEW) {
                    getTextRenderer().append(
                            "  " + BapBundle.message("dialog.dto.sync.status.new"),
                            new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD,
                                    new JBColor(new Color(0, 128, 0), new Color(98, 181, 67))));
                } else if (status == DtoSyncStatus.MODIFIED) {
                    getTextRenderer().append(
                            "  " + BapBundle.message("dialog.dto.sync.status.modified"),
                            new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD,
                                    new JBColor(new Color(0, 90, 180), new Color(75, 143, 212))));
                } else if (status == DtoSyncStatus.DELETED) {
                    getTextRenderer().append(
                            "  " + BapBundle.message("dialog.dto.sync.status.deleted"),
                            new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD,
                                    new JBColor(new Color(178, 0, 0), new Color(212, 75, 75))));
                }
            }
        }
    }
}
