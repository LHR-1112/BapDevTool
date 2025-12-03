package com.bap.dev.ui;

import bap.java.CJavaConst;
import com.bap.dev.handler.ProjectRefresher;
import com.bap.dev.listener.BapChangesNotifier;
import com.bap.dev.service.BapFileStatus;
import com.bap.dev.service.BapFileStatusService;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleUtil;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.Tree;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;

public class BapChangesTreePanel extends SimpleToolWindowPanel implements Disposable {

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

        // 工具栏配置
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new ToolbarRefreshAction());
        group.addSeparator();
        group.add(ActionManager.getInstance().getAction("com.bap.dev.action.CommitJavaCodeAction"));
        group.add(ActionManager.getInstance().getAction("com.bap.dev.action.UpdateJavaCodeAction"));
        group.addSeparator();
        group.add(ActionManager.getInstance().getAction("com.bap.dev.action.CommitAllAction"));
        // --- 🔴 新增：工具栏添加 Update All ---
        group.add(ActionManager.getInstance().getAction("com.bap.dev.action.UpdateAllAction"));
        // -----------------------------------
        group.add(ActionManager.getInstance().getAction("com.bap.dev.action.PublishProjectAction"));

        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("BapChangesToolbar", group, true);
        toolbar.setTargetComponent(this);
        setToolbar(toolbar.getComponent());

        setContent(new JBScrollPane(tree));

        // 绑定生命周期
        project.getMessageBus().connect(this).subscribe(BapChangesNotifier.TOPIC, new BapChangesNotifier() {
            @Override
            public void onChangesUpdated() {
                rebuildTree();
            }
        });

        // 初始构建
        rebuildTree();

        // 鼠标监听器
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                    if (path != null) {
                        Object node = path.getLastPathComponent();
                        if (node instanceof DefaultMutableTreeNode) {
                            Object userObject = ((DefaultMutableTreeNode) node).getUserObject();
                            if (userObject instanceof VirtualFileWrapper) {
                                VirtualFile file = ((VirtualFileWrapper) userObject).file;
                                if (file.isValid() && !file.isDirectory()) {
                                    new com.intellij.openapi.fileEditor.OpenFileDescriptor(project, file).navigate(true);
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

    @Override
    public void dispose() {
        // 资源释放
    }

    private void showContextMenu(DefaultMutableTreeNode node, MouseEvent e) {
        Object userObject = node.getUserObject();
        DefaultActionGroup group = new DefaultActionGroup();
        if (userObject instanceof ModuleWrapper) {
            group.add(ActionManager.getInstance().getAction("com.bap.dev.action.RefreshProjectAction"));
            group.add(ActionManager.getInstance().getAction("com.bap.dev.action.CommitAllAction"));
            // --- 🔴 新增：右键菜单添加 Update All ---
            group.add(ActionManager.getInstance().getAction("com.bap.dev.action.UpdateAllAction"));
            // ------------------------------------
            group.add(ActionManager.getInstance().getAction("com.bap.dev.action.PublishProjectAction"));
        } else if (userObject instanceof VirtualFileWrapper) {
            group.add(ActionManager.getInstance().getAction("com.bap.dev.action.CommitJavaCodeAction"));
            group.add(ActionManager.getInstance().getAction("com.bap.dev.action.UpdateJavaCodeAction"));
            group.add(ActionManager.getInstance().getAction("com.bap.dev.action.CompareJavaCodeAction"));
        }
        if (group.getChildrenCount() > 0) {
            ActionPopupMenu popupMenu = ActionManager.getInstance().createActionPopupMenu("BapChangesPopup", group);
            popupMenu.getComponent().show(e.getComponent(), e.getX(), e.getY());
        }
    }

    // ... (其他方法保持不变：rebuildTree, findAllBapModules, getData, ToolbarRefreshAction, getModuleRootFromNode, findModuleRoot, addStatusCategory, 渲染器, 内部类) ...
    // 请直接复制之前完整的实现，这里为了节省篇幅省略重复代码

    private void rebuildTree() {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
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
                            if (file != null && file.isValid()) {
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
            for (int i = 0; i < tree.getRowCount(); i++) {
                TreePath path = tree.getPathForRow(i);
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                if (node.getChildCount() > 0) {
                    tree.expandRow(i);
                }
            }
        });
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
        public ToolbarRefreshAction() {
            super("Refresh", "Refresh selected module (or all if none selected)", AllIcons.Actions.Refresh);
        }
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            TreePath selectionPath = tree.getSelectionPath();
            List<VirtualFile> modulesToRefresh = new ArrayList<>();
            if (selectionPath != null) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) selectionPath.getLastPathComponent();
                VirtualFile moduleRoot = getModuleRootFromNode(node);
                if (moduleRoot != null) modulesToRefresh.add(moduleRoot);
            }
            if (modulesToRefresh.isEmpty()) {
                DefaultMutableTreeNode root = (DefaultMutableTreeNode) treeModel.getRoot();
                for (int i = 0; i < root.getChildCount(); i++) {
                    TreeNode child = root.getChildAt(i);
                    if (child instanceof DefaultMutableTreeNode) {
                        Object userObj = ((DefaultMutableTreeNode) child).getUserObject();
                        if (userObj instanceof ModuleWrapper) {
                            modulesToRefresh.add(((ModuleWrapper) userObj).rootFile);
                        }
                    }
                }
            }
            if (modulesToRefresh.isEmpty()) {
                List<ModuleWrapper> allBapModules = findAllBapModules();
                for (ModuleWrapper m : allBapModules) modulesToRefresh.add(m.rootFile);
                if (modulesToRefresh.isEmpty()) { rebuildTree(); return; }
            }
            ProgressManager.getInstance().run(new Task.Backgroundable(project, "Refreshing Bap Modules...", true) {
                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    ProjectRefresher refresher = new ProjectRefresher(project);
                    for (VirtualFile root : modulesToRefresh) {
                        indicator.setText("Refreshing " + root.getName() + "...");
                        refresher.refreshModule(root);
                    }
                }
            });
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

    private static class BapChangeRenderer extends ColoredTreeCellRenderer {
        @Override
        public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
            if (value instanceof DefaultMutableTreeNode) {
                Object userObject = ((DefaultMutableTreeNode) value).getUserObject();
                if (userObject instanceof ModuleWrapper) {
                    append(((ModuleWrapper) userObject).name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                    setIcon(AllIcons.Nodes.Module);
                }
                else if (userObject instanceof CategoryWrapper) {
                    CategoryWrapper wrapper = (CategoryWrapper) userObject;
                    SimpleTextAttributes attr = SimpleTextAttributes.REGULAR_ATTRIBUTES;
                    if (wrapper.status == BapFileStatus.MODIFIED) attr = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.YELLOW);
                    else if (wrapper.status == BapFileStatus.ADDED) attr = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.BLUE);
                    else if (wrapper.status == BapFileStatus.DELETED_LOCALLY) attr = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.RED);
                    append(wrapper.title, attr);
                    setIcon(AllIcons.Nodes.Folder);
                }
                else if (userObject instanceof VirtualFileWrapper) {
                    VirtualFileWrapper wrapper = (VirtualFileWrapper) userObject;
                    SimpleTextAttributes attr = SimpleTextAttributes.REGULAR_ATTRIBUTES;
                    String suffix = "";
                    switch (wrapper.status) {
                        case MODIFIED: attr = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.YELLOW); suffix = " [M]"; break;
                        case ADDED: attr = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.BLUE); suffix = " [A]"; break;
                        case DELETED_LOCALLY: attr = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.RED); suffix = " [D]"; break;
                    }
                    append(wrapper.file.getName(), attr);
                    append(suffix, SimpleTextAttributes.GRAYED_ATTRIBUTES);
                    if (wrapper.file.isDirectory()) setIcon(AllIcons.Nodes.Folder);
                    else if ("java".equalsIgnoreCase(wrapper.file.getExtension())) setIcon(AllIcons.FileTypes.Java);
                    else setIcon(AllIcons.FileTypes.Text);
                }
            }
        }
    }

    private static class ModuleWrapper {
        String name;
        VirtualFile rootFile;
        ModuleWrapper(String name, VirtualFile rootFile) { this.name = name; this.rootFile = rootFile; }
        @Override public String toString() { return name; }
    }

    private static class CategoryWrapper {
        String title;
        BapFileStatus status;
        CategoryWrapper(String title, BapFileStatus status) { this.title = title; this.status = status; }
    }

    private static class VirtualFileWrapper {
        VirtualFile file;
        BapFileStatus status;
        VirtualFileWrapper(VirtualFile file, BapFileStatus status) { this.file = file; this.status = status; }
    }
}