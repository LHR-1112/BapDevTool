package com.bap.dev.ui;

import bap.java.CJavaConst;
import com.bap.dev.handler.ProjectRefresher;
import com.bap.dev.listener.BapChangesNotifier;
import com.bap.dev.service.BapFileStatus;
import com.bap.dev.service.BapFileStatusService;
import com.bap.dev.settings.BapSettingsState;
import com.intellij.icons.AllIcons;
import com.intellij.ide.DataManager;
import com.intellij.ide.util.treeView.TreeState;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleUtil;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.Tree;
// 🔴 修复：TreeUtil 的正确包路径 (IntelliJ 2020+)
import com.intellij.util.ui.tree.TreeUtil;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreeCellRenderer;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.List;

public class BapChangesTreePanel extends SimpleToolWindowPanel implements Disposable {

    public static final Key<VirtualFile> LAST_BAP_MODULE_ROOT = Key.create("LAST_BAP_MODULE_ROOT");

    private final Project project;
    private final Tree tree;
    private final DefaultTreeModel treeModel;

    public BapChangesTreePanel(Project project) {
        super(true, true);
        this.project = project;

        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Root");
        treeModel = new DefaultTreeModel(root);
        tree = new Tree(treeModel);
        tree.setRootVisible(false);
        tree.setCellRenderer(new BapChangeRenderer());

        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new ToolbarRefreshAction());
        group.add(new ExpandAllAction());
        group.add(new CollapseAllAction());
        group.add(new LocateCurrentFileAction());

        group.addSeparator();
        group.add(ActionManager.getInstance().getAction("com.bap.dev.action.CommitFileAction"));
        group.add(ActionManager.getInstance().getAction("com.bap.dev.action.UpdateFileAction"));
        group.addSeparator();
        group.add(ActionManager.getInstance().getAction("com.bap.dev.action.CommitAllAction"));
        group.add(ActionManager.getInstance().getAction("com.bap.dev.action.UpdateAllAction"));
        group.add(ActionManager.getInstance().getAction("com.bap.dev.action.PublishProjectAction"));

        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("BapChangesToolbar", group, true);
        toolbar.setTargetComponent(this);
        setToolbar(toolbar.getComponent());

        setContent(new JBScrollPane(tree));

        project.getMessageBus().connect(this).subscribe(BapChangesNotifier.TOPIC, new BapChangesNotifier() {
            @Override
            public void onChangesUpdated() {
                rebuildTree();
            }
        });

        rebuildTree();

        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                // 处理按钮点击
                // 暂时注释掉，太难用了
                handleButtonClick(e);

                if (e.getClickCount() == 2) {
                    TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                    if (path != null) {
                        Object node = path.getLastPathComponent();
                        if (node instanceof DefaultMutableTreeNode) {
                            Object userObject = ((DefaultMutableTreeNode) node).getUserObject();
                            if (userObject instanceof VirtualFileWrapper) {
                                VirtualFile file = ((VirtualFileWrapper) userObject).file;
                                if (file.isValid() && !file.isDirectory()) {
                                    AnAction compareAction = ActionManager.getInstance().getAction("com.bap.dev.action.CompareJavaCodeAction");
                                    if (compareAction != null) {
                                        DataContext dataContext = DataManager.getInstance().getDataContext(tree);
                                        AnActionEvent event = AnActionEvent.createFromAnAction(compareAction, e, ActionPlaces.TOOLWINDOW_CONTENT, dataContext);
                                        compareAction.actionPerformed(event);
                                    }
                                }
                            }
                        }
                    }
                }

                if (SwingUtilities.isRightMouseButton(e)) {
                    int row = tree.getClosestRowForLocation(e.getX(), e.getY());
                    if (!tree.isRowSelected(row)) {
                        tree.setSelectionRow(row);
                    }
                    TreePath path = tree.getPathForRow(row);
                    if (path != null) {
                        Object node = path.getLastPathComponent();
                        if (node instanceof DefaultMutableTreeNode) {
                            showContextMenu((DefaultMutableTreeNode) node, e);
                        }
                    }
                }
            }
        });
    }

    private void handleButtonClick(MouseEvent e) {
        if (!BapSettingsState.getInstance().showProjectNodeActions) return; // ✅ 关闭则不响应
        if (!SwingUtilities.isLeftMouseButton(e)) return;

        Point p = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), tree);

        // 用 row 定位，避免 x 影响
        int row = tree.getClosestRowForLocation(p.x, p.y);
        if (row < 0) return;
        Rectangle rowRect = tree.getRowBounds(row);
        if (rowRect == null) return;
        if (p.y < rowRect.y || p.y > rowRect.y + rowRect.height) return;

        // ✅ 关键：用 rowRect 的右边界，而不是 visibleRect
        int rightEdge = rowRect.x + rowRect.width;

        Rectangle r = tree.getRowBounds(row);
        if (r == null || p.y < r.y || p.y > r.y + r.height) return;

        TreePath path = tree.getPathForRow(row);
        if (path == null) return;

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        Object userObject = node.getUserObject();
        if (!(userObject instanceof ModuleWrapper)) return;


        // ⚠️ 下面三项必须和你的 buttonPanel 布局一致
        int n = 4;      // 你现在是 4 个按钮（含刷新）
        int cellW = 18; // 建议固定成 18（见下方“同步渲染尺寸”）
        int gap = 2;    // 你的 GridLayout hgap

        int totalW = n * cellW + (n - 1) * gap;
        int startX = rightEdge - totalW;

        int x = p.x;
        if (x < startX || x > rightEdge) return;

        // 命中第几个按钮（从左到右 0..n-1）
        int dx = x - startX;
        int index = dx / (cellW + gap);
        if (index < 0 || index >= n) return;

        tree.setSelectionPath(path);

        // ✅ index 映射必须与你 buttonPanel.add 顺序一致
        switch (index) {
            case 0 -> runAction("com.bap.dev.action.RefreshProjectAction", e);
            case 1 -> runAction("com.bap.dev.action.UpdateAllAction", e);
            case 2 -> runAction("com.bap.dev.action.CommitAllAction", e);
            case 3 -> runAction("com.bap.dev.action.PublishProjectAction", e);
        }
    }

    private void runAction(String actionId, MouseEvent e) {
        AnAction action = ActionManager.getInstance().getAction(actionId);
        if (action != null) {
            DataContext dataContext = DataManager.getInstance().getDataContext(tree);
            AnActionEvent event = AnActionEvent.createFromAnAction(action, e, ActionPlaces.TOOLWINDOW_CONTENT, dataContext);
            action.actionPerformed(event);
        }
    }

    @Override
    public void dispose() {
    }

    private void rebuildTree() {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;

            TreeState state = TreeState.createOn(tree);

            DefaultMutableTreeNode root = (DefaultMutableTreeNode) treeModel.getRoot();
            root.removeAllChildren();

            List<ModuleWrapper> bapModules = findAllBapModules();
            bapModules.sort(Comparator.comparing(m -> m.name));

            Map<String, BapFileStatus> statuses = BapFileStatusService.getInstance(project).getAllStatuses();

            for (ModuleWrapper moduleWrapper : bapModules) {
                DefaultMutableTreeNode moduleNode = new DefaultMutableTreeNode(moduleWrapper);
                root.add(moduleNode);

                Map<BapFileStatus, List<VirtualFile>> moduleChanges = new HashMap<>();

                if (!statuses.isEmpty()) {
                    for (Map.Entry<String, BapFileStatus> entry : statuses.entrySet()) {
                        String path = entry.getKey();
                        BapFileStatus status = entry.getValue();
                        if (status == BapFileStatus.NORMAL) continue;

                        if (path.startsWith(moduleWrapper.rootFile.getPath())) {
                            VirtualFile file = LocalFileSystem.getInstance().findFileByPath(path);
                            if (file != null) {
                                moduleChanges.computeIfAbsent(status, k -> new ArrayList<>()).add(file);
                            }
                        }
                    }
                }

                if (!moduleChanges.isEmpty()) {
                    addStatusCategory(moduleNode, moduleChanges, BapFileStatus.MODIFIED, "Modified");
                    addStatusCategory(moduleNode, moduleChanges, BapFileStatus.ADDED, "Added");
                    addStatusCategory(moduleNode, moduleChanges, BapFileStatus.DELETED_LOCALLY, "Deleted");
                }
            }

            treeModel.reload();
            state.applyTo(tree);

            if (state.isEmpty()) {
                for (int i = 0; i < tree.getRowCount(); i++) {
                    TreePath path = tree.getPathForRow(i);
                    DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                    if (node.getChildCount() > 0) {
                        tree.expandRow(i);
                    }
                }
            }

            VirtualFile targetModule = project.getUserData(LAST_BAP_MODULE_ROOT);
            if (targetModule != null) {
                project.putUserData(LAST_BAP_MODULE_ROOT, null);
                DefaultMutableTreeNode targetNode = findModuleNode(root, targetModule);
                if (targetNode != null) {
                    TreePath path = new TreePath(targetNode.getPath());
                    tree.setSelectionPath(path);
                    tree.scrollPathToVisible(path);
                    tree.expandPath(path);
                }
            }
        });
    }

    private DefaultMutableTreeNode findModuleNode(DefaultMutableTreeNode root, VirtualFile moduleRoot) {
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) root.getChildAt(i);
            Object userObj = node.getUserObject();
            if (userObj instanceof ModuleWrapper) {
                if (((ModuleWrapper) userObj).rootFile.equals(moduleRoot)) {
                    return node;
                }
            }
        }
        return null;
    }

    private static class ModuleWrapper {
        String name;
        VirtualFile rootFile;
        ModuleWrapper(String name, VirtualFile rootFile) { this.name = name; this.rootFile = rootFile; }
        @Override public String toString() { return name; }
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ModuleWrapper that = (ModuleWrapper) o;
            return Objects.equals(rootFile.getPath(), that.rootFile.getPath());
        }
        @Override public int hashCode() { return Objects.hash(rootFile.getPath()); }
    }

    private static class CategoryWrapper {
        String title;
        BapFileStatus status;
        CategoryWrapper(String title, BapFileStatus status) { this.title = title; this.status = status; }
        @Override public String toString() { return status.name(); }
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            CategoryWrapper that = (CategoryWrapper) o;
            return status == that.status;
        }
        @Override public int hashCode() { return Objects.hash(status); }
    }

    private static class VirtualFileWrapper {
        VirtualFile file;
        BapFileStatus status;
        VirtualFileWrapper(VirtualFile file, BapFileStatus status) { this.file = file; this.status = status; }
        @Override public String toString() { return file.getName(); }
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            VirtualFileWrapper that = (VirtualFileWrapper) o;
            return Objects.equals(file.getPath(), that.file.getPath());
        }
        @Override public int hashCode() { return Objects.hash(file.getPath()); }
    }

    private List<ModuleWrapper> findAllBapModules() {
        List<ModuleWrapper> result = new ArrayList<>();
        if (project.isDisposed()) return result;
        Module[] modules = ModuleManager.getInstance(project).getModules();
        for (Module module : modules) {
            VirtualFile[] contentRoots = ModuleRootManager.getInstance(module).getContentRoots();
            for (VirtualFile root : contentRoots) {
                if (root.findChild(CJavaConst.PROJECT_DEVELOP_CONF_FILE) != null) {
                    result.add(new ModuleWrapper(module.getName(), root));
                    break;
                }
            }
        }
        return result;
    }

    @Override
    public @Nullable Object getData(@NotNull String dataId) {
        if (CommonDataKeys.VIRTUAL_FILE.is(dataId)) {
            TreePath path = tree.getSelectionPath();
            if (path == null) return null;
            return getFileFromPath(path);
        }
        if (CommonDataKeys.VIRTUAL_FILE_ARRAY.is(dataId)) {
            TreePath[] paths = tree.getSelectionPaths();
            if (paths == null || paths.length == 0) return null;
            List<VirtualFile> files = new ArrayList<>();
            for (TreePath path : paths) {
                VirtualFile f = getFileFromPath(path);
                if (f != null) files.add(f);
            }
            return files.isEmpty() ? null : files.toArray(new VirtualFile[0]);
        }
        return super.getData(dataId);
    }

    private VirtualFile getFileFromPath(TreePath path) {
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        Object userObject = node.getUserObject();
        if (userObject instanceof VirtualFileWrapper) {
            return ((VirtualFileWrapper) userObject).file;
        } else if (userObject instanceof ModuleWrapper) {
            return ((ModuleWrapper) userObject).rootFile;
        }
        return null;
    }

    private class ToolbarRefreshAction extends AnAction {
        public ToolbarRefreshAction() { super("Refresh", "Refresh selected module", AllIcons.Actions.Refresh); }
        @Override public void actionPerformed(@NotNull AnActionEvent e) {
            TreePath selectionPath = tree.getSelectionPath();
            List<VirtualFile> modulesToRefresh = new ArrayList<>();
            if (selectionPath != null) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) selectionPath.getLastPathComponent();
                VirtualFile moduleRoot = getModuleRootFromNode(node);
                if (moduleRoot != null) modulesToRefresh.add(moduleRoot);
            }
            if (modulesToRefresh.isEmpty()) {
                List<ModuleWrapper> allBapModules = findAllBapModules();
                for (ModuleWrapper m : allBapModules) modulesToRefresh.add(m.rootFile);
            }
            if (modulesToRefresh.isEmpty()) { rebuildTree(); return; }

            ProgressManager.getInstance().run(new Task.Backgroundable(project, "Refreshing Bap Modules...", true) {
                @Override public void run(@NotNull ProgressIndicator indicator) {
                    ProjectRefresher refresher = new ProjectRefresher(project);
                    for (VirtualFile root : modulesToRefresh) {
                        indicator.setText("Refreshing " + root.getName() + "...");
                        refresher.refreshModule(root, false);
                    }
                }
            });
        }
    }

    private class ExpandAllAction extends AnAction {
        public ExpandAllAction() { super("Expand All", "Expand all nodes", AllIcons.Actions.Expandall); }
        @Override public void actionPerformed(@NotNull AnActionEvent e) { TreeUtil.expandAll(tree); }
    }

    private class CollapseAllAction extends AnAction {
        public CollapseAllAction() { super("Collapse All", "Collapse all nodes", AllIcons.Actions.Collapseall); }
        @Override public void actionPerformed(@NotNull AnActionEvent e) { TreeUtil.collapseAll(tree, 0); }
    }

    private class LocateCurrentFileAction extends AnAction {
        public LocateCurrentFileAction() { super("Select Opened File", "Locate current opened file", AllIcons.General.Locate); }
        @Override public void actionPerformed(@NotNull AnActionEvent e) {
            VirtualFile[] selectedFiles = FileEditorManager.getInstance(project).getSelectedFiles();
            if (selectedFiles.length == 0) return;
            VirtualFile currentFile = selectedFiles[0];
            DefaultMutableTreeNode root = (DefaultMutableTreeNode) treeModel.getRoot();
            TreePath path = findNodeForFile(root, currentFile);
            if (path == null) path = findNodeForModule(root, currentFile);
            if (path != null) TreeUtil.selectPath(tree, path);
        }
        private TreePath findNodeForFile(DefaultMutableTreeNode root, VirtualFile target) {
            for (int i = 0; i < root.getChildCount(); i++) {
                DefaultMutableTreeNode moduleNode = (DefaultMutableTreeNode) root.getChildAt(i);
                for (int j = 0; j < moduleNode.getChildCount(); j++) {
                    DefaultMutableTreeNode catNode = (DefaultMutableTreeNode) moduleNode.getChildAt(j);
                    for (int k = 0; k < catNode.getChildCount(); k++) {
                        DefaultMutableTreeNode fileNode = (DefaultMutableTreeNode) catNode.getChildAt(k);
                        Object userObj = fileNode.getUserObject();
                        if (userObj instanceof VirtualFileWrapper && ((VirtualFileWrapper) userObj).file.equals(target)) return new TreePath(fileNode.getPath());
                    }
                }
            }
            return null;
        }
        private TreePath findNodeForModule(DefaultMutableTreeNode root, VirtualFile target) {
            for (int i = 0; i < root.getChildCount(); i++) {
                DefaultMutableTreeNode moduleNode = (DefaultMutableTreeNode) root.getChildAt(i);
                Object userObj = moduleNode.getUserObject();
                if (userObj instanceof ModuleWrapper) {
                    VirtualFile moduleRoot = ((ModuleWrapper) userObj).rootFile;
                    if (moduleRoot != null && VfsUtilCore.isAncestor(moduleRoot, target, false)) return new TreePath(moduleNode.getPath());
                }
            }
            return null;
        }
    }

    private VirtualFile getModuleRootFromNode(DefaultMutableTreeNode node) {
        Object userObject = node.getUserObject();
        if (userObject instanceof ModuleWrapper) return ((ModuleWrapper) userObject).rootFile;
        else if (userObject instanceof CategoryWrapper) {
            TreeNode parent = node.getParent();
            if (parent instanceof DefaultMutableTreeNode) return getModuleRootFromNode((DefaultMutableTreeNode) parent);
        }
        else if (userObject instanceof VirtualFileWrapper) return findModuleRoot(((VirtualFileWrapper) userObject).file);
        return null;
    }

    private VirtualFile findModuleRoot(VirtualFile file) {
        VirtualFile dir = file.isDirectory() ? file : file.getParent();
        while (dir != null) {
            if (dir.findChild("src") != null || dir.findChild(".develop") != null) return dir;
            dir = dir.getParent();
        }
        return null;
    }

    private void showContextMenu(DefaultMutableTreeNode node, MouseEvent e) {
        Object userObject = node.getUserObject();
        DefaultActionGroup group = new DefaultActionGroup();
        ActionManager am = ActionManager.getInstance();

        if (userObject instanceof ModuleWrapper) {
            group.add(am.getAction("com.bap.dev.action.RefreshProjectAction"));
            group.addSeparator();
            group.add(am.getAction("com.bap.dev.action.UpdateLibsAction"));
            group.add(am.getAction("com.bap.dev.action.UpdateAllAction"));
            group.addSeparator();
            group.add(am.getAction("com.bap.dev.action.ShowProjectHistoryAction"));
            group.addSeparator();
            group.add(am.getAction("com.bap.dev.action.CommitAllAction"));
            group.add(am.getAction("com.bap.dev.action.PublishProjectAction"));
            group.addSeparator();
            group.add(am.getAction("com.bap.dev.action.RelocateProjectAction"));
            group.add(am.getAction("com.bap.dev.action.OpenAdminToolAction"));
        } else if (userObject instanceof VirtualFileWrapper) {
            group.add(am.getAction("com.bap.dev.action.UpdateFileAction"));
            group.add(am.getAction("com.bap.dev.action.CommitFileAction"));
            group.addSeparator();
            group.add(am.getAction("com.bap.dev.action.CompareJavaCodeAction"));
            group.add(am.getAction("com.bap.dev.action.ShowHistoryAction"));
        }

        if (group.getChildrenCount() > 0) {
            ActionPopupMenu popupMenu = am.createActionPopupMenu("BapChangesPopup", group);
            popupMenu.getComponent().show(e.getComponent(), e.getX(), e.getY());
        }
    }

    private void addStatusCategory(DefaultMutableTreeNode parent, Map<BapFileStatus, List<VirtualFile>> map, BapFileStatus status, String title) {
        List<VirtualFile> files = map.get(status);
        if (files != null && !files.isEmpty()) {
            files.sort(Comparator.comparing(VirtualFile::getName));
            DefaultMutableTreeNode categoryNode = new DefaultMutableTreeNode(new CategoryWrapper(title + " (" + files.size() + ")", status));
            parent.add(categoryNode);
            for (VirtualFile file : files) {
                categoryNode.add(new DefaultMutableTreeNode(new VirtualFileWrapper(file, status)));
            }
        }
    }

    // --- 🔴 修复布局：使用 FlowLayout 防止按钮错位 ---
    private static class BapChangeRenderer implements TreeCellRenderer {

        private JLabel iconLabel(Icon icon) {
            JLabel l = new JLabel(icon);
            Dimension d = new Dimension(18, 18);
            l.setPreferredSize(d);
            l.setMinimumSize(d);
            l.setMaximumSize(d);
            return l;
        }

        private final ColoredTreeCellRenderer fileRenderer = new ColoredTreeCellRenderer() {
            @Override
            public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
                renderContent(this, value);
            }
        };

        // 🔴 关键修复：改为 FlowLayout(LEFT)，让按钮紧跟文本
        private final JPanel modulePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));

        private final ColoredTreeCellRenderer moduleTextRenderer = new ColoredTreeCellRenderer() {
            @Override
            public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
                renderContent(this, value);
            }
        };
        private final JPanel buttonPanel = new JPanel(new GridLayout(1, 4, 2, 0));

        public BapChangeRenderer() {
            modulePanel.setOpaque(true);
            buttonPanel.setOpaque(false);

            buttonPanel.add(iconLabel(AllIcons.Actions.Refresh));
            buttonPanel.add(iconLabel(AllIcons.Actions.CheckOut));
            buttonPanel.add(iconLabel(AllIcons.Actions.Commit));
            buttonPanel.add(iconLabel(AllIcons.Actions.Execute));

            modulePanel.add(moduleTextRenderer);
            modulePanel.add(buttonPanel);
        }

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
            Object userObject = ((DefaultMutableTreeNode) value).getUserObject();

            if (userObject instanceof ModuleWrapper) {
                moduleTextRenderer.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
                moduleTextRenderer.setOpaque(false);

                if (selected) {
                    modulePanel.setBackground(UIUtil.getTreeSelectionBackground(hasFocus));
                    moduleTextRenderer.setForeground(UIUtil.getTreeSelectionForeground(hasFocus));
                } else {
                    modulePanel.setBackground(UIUtil.getTreeBackground());
                    moduleTextRenderer.setForeground(UIUtil.getTreeForeground());
                }

                // ✅ 开关控制：隐藏/显示右侧三个按钮
                buttonPanel.setVisible(BapSettingsState.getInstance().showProjectNodeActions);
                return modulePanel;
            } else {
                return fileRenderer.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
            }
        }

        private void renderContent(ColoredTreeCellRenderer renderer, Object value) {
            if (value instanceof DefaultMutableTreeNode) {
                Object userObject = ((DefaultMutableTreeNode) value).getUserObject();
                if (userObject instanceof ModuleWrapper) {
                    renderer.append(((ModuleWrapper) userObject).name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                    renderer.setIcon(AllIcons.Nodes.Module);
                } else if (userObject instanceof CategoryWrapper) {
                    CategoryWrapper wrapper = (CategoryWrapper) userObject;
                    SimpleTextAttributes attr = SimpleTextAttributes.REGULAR_ATTRIBUTES;
                    BapSettingsState settings = BapSettingsState.getInstance();
                    if (wrapper.status == BapFileStatus.MODIFIED) attr = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, settings.getModifiedColorObj());
                    else if (wrapper.status == BapFileStatus.ADDED) attr = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, settings.getAddedColorObj());
                    else if (wrapper.status == BapFileStatus.DELETED_LOCALLY) attr = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, settings.getDeletedColorObj());
                    renderer.append(wrapper.title, attr);
                    renderer.setIcon(AllIcons.Nodes.Folder);
                } else if (userObject instanceof VirtualFileWrapper) {
                    VirtualFileWrapper wrapper = (VirtualFileWrapper) userObject;
                    SimpleTextAttributes attr = SimpleTextAttributes.REGULAR_ATTRIBUTES;
                    String suffix = "";
                    BapSettingsState settings = BapSettingsState.getInstance();
                    java.awt.Color modColor = settings.getModifiedColorObj();
                    java.awt.Color addColor = settings.getAddedColorObj();
                    java.awt.Color delColor = settings.getDeletedColorObj();
                    switch (wrapper.status) {
                        case MODIFIED: attr = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, modColor); suffix = " [M]"; break;
                        case ADDED: attr = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, addColor); suffix = " [A]"; break;
                        case DELETED_LOCALLY: attr = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, delColor); suffix = " [D]"; break;
                    }
                    renderer.append(wrapper.file.getName(), attr);
                    renderer.append(suffix, SimpleTextAttributes.GRAYED_ATTRIBUTES);
                    if (wrapper.file.isDirectory()) renderer.setIcon(AllIcons.Nodes.Folder);
                    else if ("java".equalsIgnoreCase(wrapper.file.getExtension())) renderer.setIcon(AllIcons.FileTypes.Java);
                    else renderer.setIcon(AllIcons.FileTypes.Text);
                }
            }
        }
    }
}