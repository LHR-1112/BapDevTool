package com.bap.dev.ui;

import bap.java.CJavaConst;
import com.bap.dev.handler.ProjectRefresher;
import com.bap.dev.i18n.BapBundle;
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
import com.intellij.openapi.fileTypes.FileTypeManager;
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
import com.intellij.testFramework.LightVirtualFile;
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
import java.io.File;
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
        // 🔴 新增：扁平化切换按钮 (放在定位按钮后面)
        group.add(new ToggleFlattenPackagesAction());

        group.addSeparator();
        group.add(ActionManager.getInstance().getAction("com.bap.dev.action.UpdateFileAction"));
        group.add(ActionManager.getInstance().getAction("com.bap.dev.action.UpdateAllAction"));
        group.addSeparator();
        group.add(ActionManager.getInstance().getAction("com.bap.dev.action.CommitFileAction"));
        group.add(ActionManager.getInstance().getAction("com.bap.dev.action.CommitFileAndPublishAction"));
        group.addSeparator();
        group.add(ActionManager.getInstance().getAction("com.bap.dev.action.CommitAllAction"));
        group.add(ActionManager.getInstance().getAction("com.bap.dev.action.CommitAllAndPublishAction"));
        group.addSeparator();
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
                        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                        Object userObject = node.getUserObject();

                        if (userObject instanceof VirtualFileWrapper) {
                            VirtualFileWrapper wrapper = (VirtualFileWrapper) userObject;
                            VirtualFile file = wrapper.file;

                            // 1. 蓝A (Added): 打开本地文件编辑器
                            if (wrapper.status == BapFileStatus.ADDED) {
                                if (file.isValid() && !file.isDirectory()) {
                                    FileEditorManager.getInstance(project).openFile(file, true);
                                }
                            }
                            // 2. 黄M (Modified) 或 红D (Deleted): 打开对比 Action
                            // 对于红D，CompareAction 会显示本地为空 vs 云端代码，实现了“查看云端版本”的效果
                            else if (wrapper.status == BapFileStatus.MODIFIED || wrapper.status == BapFileStatus.DELETED_LOCALLY) {
                                runAction("com.bap.dev.action.CompareJavaCodeAction", e);
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
        int n = 5;
        int cellW = 18;
        int gap = 2;

        int totalW = n * cellW + (n - 1) * gap;
        int startX = rightEdge - totalW;

        int x = p.x;
        if (x < startX || x > rightEdge) return;

        // 命中第几个按钮（从左到右 0..n-1）
        int dx = x - startX;
        int index = dx / (cellW + gap);
        if (index < 0 || index >= n) return;

        tree.setSelectionPath(path);

        // 🔴 修改 2: 增加 case 3 处理 CommitAllAndPublishAction
        switch (index) {
            case 0 -> runAction("com.bap.dev.action.RefreshProjectAction", e);
            case 1 -> runAction("com.bap.dev.action.UpdateAllAction", e);
            case 2 -> runAction("com.bap.dev.action.CommitAllAction", e);
            case 3 -> runAction("com.bap.dev.action.CommitAllAndPublishAction", e); // 新增：提交并发布
            case 4 -> runAction("com.bap.dev.action.PublishProjectAction", e);
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

                Map<BapFileStatus, List<VirtualFileWrapper>> moduleChanges = new HashMap<>(); // 🔴 List<VirtualFileWrapper>

                if (!statuses.isEmpty()) {
                    for (Map.Entry<String, BapFileStatus> entry : statuses.entrySet()) {
                        String path = entry.getKey();
                        BapFileStatus status = entry.getValue();
                        if (status == BapFileStatus.NORMAL) continue;

                        if (path.startsWith(moduleWrapper.rootFile.getPath())) {

                            // --- 🔴 核心修改开始 ---
                            VirtualFileWrapper wrapper = null;

                            if (status == BapFileStatus.DELETED_LOCALLY) {
                                // 🔴 修改开始：创建“带父级”的虚拟文件
                                File ioFile = new File(path);
                                String fileName = ioFile.getName();
                                var fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName);

                                // 1. 寻找最近的存在的物理父目录
                                // 因为文件删了，可能连父文件夹也删了，所以要向上查找直到找到存在的目录
                                VirtualFile bestParent = findBestPhysicalParent(new File(ioFile.getParent()));

                                // 如果实在找不到(极少见)，就用模块根目录兜底
                                if (bestParent == null) bestParent = moduleWrapper.rootFile;

                                // 2. 创建自定义虚拟文件
                                VirtualFile fakeFile = new BapDeletedVirtualFile(fileName, fileType, path, bestParent);

                                wrapper = new VirtualFileWrapper(fakeFile, path, status);
                                // 🔴 修改结束
                            } else {
                                // 普通文件：查找本地文件
                                VirtualFile file = LocalFileSystem.getInstance().findFileByPath(path);
                                if (file != null) {
                                    wrapper = new VirtualFileWrapper(file, path, status);
                                }
                            }

                            if (wrapper != null) {
                                moduleChanges.computeIfAbsent(status, k -> new ArrayList<>()).add(wrapper);
                            }
                            // --- 🔴 核心修改结束 ---
                        }
                    }
                }

                if (!moduleChanges.isEmpty()) {
                    addStatusCategory(moduleNode, moduleChanges, BapFileStatus.MODIFIED, BapBundle.message("status.modified"), moduleWrapper.rootFile);
                    addStatusCategory(moduleNode, moduleChanges, BapFileStatus.ADDED, BapBundle.message("status.added"), moduleWrapper.rootFile);
                    addStatusCategory(moduleNode, moduleChanges, BapFileStatus.DELETED_LOCALLY, BapBundle.message("status.deleted"), moduleWrapper.rootFile);
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

    // 递归向上查找存在的物理目录
    private VirtualFile findBestPhysicalParent(File ioDir) {
        if (ioDir == null) return null;
        VirtualFile vf = LocalFileSystem.getInstance().findFileByIoFile(ioDir);
        if (vf != null && vf.isValid() && vf.isDirectory()) {
            return vf;
        }
        return findBestPhysicalParent(ioDir.getParentFile());
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
        String absolutePath; // 🔴 新增：用于存储真实物理路径
        BapFileStatus status;

        VirtualFileWrapper(VirtualFile file, String absolutePath, BapFileStatus status) {
            this.file = file;
            this.absolutePath = absolutePath;
            this.status = status;
        }

        @Override public String toString() { return file.getName(); }
        // 记得更新 equals/hashCode 使用 absolutePath
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            VirtualFileWrapper that = (VirtualFileWrapper) o;
            return Objects.equals(absolutePath, that.absolutePath);
        }
        @Override public int hashCode() { return Objects.hash(absolutePath); }
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
        // 单选逻辑 (用于确定 Action 是否启用，或获取上下文 ModuleRoot)
        if (CommonDataKeys.VIRTUAL_FILE.is(dataId)) {
            TreePath path = tree.getSelectionPath();
            if (path == null) return null;
            return getFileFromPath(path);
        }

        // 🔴 核心修改：多选/批量逻辑 (供给 CommitFileAction/UpdateFileAction)
        if (CommonDataKeys.VIRTUAL_FILE_ARRAY.is(dataId)) {
            TreePath[] paths = tree.getSelectionPaths();
            if (paths == null || paths.length == 0) return null;

            // 使用 Set 去重 (防止父子节点同时选中导致重复)
            Set<VirtualFile> fileSet = new LinkedHashSet<>();
            for (TreePath path : paths) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                collectFilesFromNode(node, fileSet); // 🔴 抽取递归方法
            }
            return fileSet.isEmpty() ? null : fileSet.toArray(new VirtualFile[0]);
        }
        return super.getData(dataId);
    }

    // 🔴 递归收集文件 (处理 Category, Directory, File 节点)
    private void collectFilesFromNode(DefaultMutableTreeNode node, Set<VirtualFile> fileSet) {
        Object userObj = node.getUserObject();
        if (userObj instanceof VirtualFileWrapper) {
            fileSet.add(((VirtualFileWrapper) userObj).file);
        } else if (userObj instanceof CategoryWrapper || userObj instanceof DirectoryWrapper) {
            // 如果选中了分类或文件夹，递归收集子节点
            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                TreeNode child = node.getChildAt(i);
                if (child instanceof DefaultMutableTreeNode) {
                    collectFilesFromNode((DefaultMutableTreeNode) child, fileSet);
                }
            }
        }
    }

    // 1. 修改：让 Category 节点也能返回所属的 Module 根目录
    // 这样 UpdateAllAction / CommitAllAction 才能识别到项目并启用
    private VirtualFile getFileFromPath(TreePath path) {
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        Object userObject = node.getUserObject();
        if (userObject instanceof VirtualFileWrapper) {
            return ((VirtualFileWrapper) userObject).file;
        } else if (userObject instanceof ModuleWrapper) {
            return ((ModuleWrapper) userObject).rootFile;
        } else if (userObject instanceof CategoryWrapper) {
            // 🔴 新增：如果选中分组节点，向上查找并返回模块根目录
            return getModuleRootFromNode(node);
        }
        return null;
    }

    private class ToolbarRefreshAction extends AnAction {
        public ToolbarRefreshAction() { super(BapBundle.message("action.refresh"), BapBundle.message("ui.BapChangesTreePanel.action.refresh.desc"), AllIcons.Actions.Refresh); }
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

            ProgressManager.getInstance().run(new Task.Backgroundable(project, BapBundle.message("ui.BapChangesTreePanel.progress.title"), true) {
                @Override public void run(@NotNull ProgressIndicator indicator) {
                    ProjectRefresher refresher = new ProjectRefresher(project);
                    for (VirtualFile root : modulesToRefresh) {
                        indicator.setText(BapBundle.message("progress.refreshing_target", root.getName())); // "Refreshing " + root.getName() + "..."
                        refresher.refreshModule(root, false);
                    }
                }
            });
        }
    }

    private class ExpandAllAction extends AnAction {
        public ExpandAllAction() {
            super(BapBundle.message("action.expand_all"), BapBundle.message("ui.BapChangesTreePanel.action.expand.desc"), AllIcons.Actions.Expandall);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            // 1. 获取选中路径
            TreePath[] selectionPaths = tree.getSelectionPaths();

            if (selectionPaths != null && selectionPaths.length > 0) {
                // 2. 有选中：递归展开选中的节点
                for (TreePath path : selectionPaths) {
                    expandNodeRecursively(path);
                }
            } else {
                // 3. 无选中：展开全部
                TreeUtil.expandAll(tree);
            }
        }

        // 递归展开帮助方法
        private void expandNodeRecursively(TreePath parentPath) {
            // 先展开当前节点
            tree.expandPath(parentPath);

            DefaultMutableTreeNode node = (DefaultMutableTreeNode) parentPath.getLastPathComponent();
            Enumeration<?> children = node.children();
            while (children.hasMoreElements()) {
                DefaultMutableTreeNode child = (DefaultMutableTreeNode) children.nextElement();
                TreePath childPath = parentPath.pathByAddingChild(child);
                expandNodeRecursively(childPath);
            }
        }
    }

    private class CollapseAllAction extends AnAction {
        public CollapseAllAction() {
            super(BapBundle.message("action.collapse_all"), BapBundle.message("ui.BapChangesTreePanel.action.collapse.desc"), AllIcons.Actions.Collapseall);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            // 1. 获取选中路径
            TreePath[] selectionPaths = tree.getSelectionPaths();

            if (selectionPaths != null && selectionPaths.length > 0) {
                // 2. 有选中：递归折叠选中的节点
                for (TreePath path : selectionPaths) {
                    collapseNodeRecursively(path);
                }
            } else {
                // 3. 无选中：折叠全部 (保留根节点下的一级)
                TreeUtil.collapseAll(tree, 0);
            }
        }

        // 递归折叠帮助方法
        private void collapseNodeRecursively(TreePath parentPath) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) parentPath.getLastPathComponent();

            // 后序遍历：先折叠子节点，再折叠自己，这样下次展开时子节点是收起状态
            Enumeration<?> children = node.children();
            while (children.hasMoreElements()) {
                DefaultMutableTreeNode child = (DefaultMutableTreeNode) children.nextElement();
                TreePath childPath = parentPath.pathByAddingChild(child);
                collapseNodeRecursively(childPath);
            }

            // 折叠当前节点
            tree.collapsePath(parentPath);
        }
    }

    private class LocateCurrentFileAction extends AnAction {
        public LocateCurrentFileAction() {
            super(BapBundle.message("ui.BapChangesTreePanel.action.locate.text"), BapBundle.message("ui.BapChangesTreePanel.action.locate.desc"), AllIcons.General.Locate);
        }
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
            // Module 节点
            group.add(am.getAction("com.bap.dev.action.RefreshProjectAction"));
            group.addSeparator();
            group.add(am.getAction("com.bap.dev.action.UpdateLibsAction"));
            group.add(am.getAction("com.bap.dev.action.UpdateAllAction"));
            group.addSeparator();
            group.add(am.getAction("com.bap.dev.action.ShowProjectHistoryAction"));
            group.addSeparator();
            group.add(am.getAction("com.bap.dev.action.CommitAllAction"));
            // 🔴 新增
            group.add(am.getAction("com.bap.dev.action.CommitAllAndPublishAction"));

            group.add(am.getAction("com.bap.dev.action.PublishProjectAction"));
            group.addSeparator();
            group.add(am.getAction("com.bap.dev.action.RelocateProjectAction"));
            group.add(am.getAction("com.bap.dev.action.OpenAdminToolAction"));

        } else if (userObject instanceof CategoryWrapper || userObject instanceof DirectoryWrapper) {
            // Category 节点 (Modified/Added/Deleted 分组)
            group.add(am.getAction("com.bap.dev.action.UpdateFileAction"));
            group.add(am.getAction("com.bap.dev.action.CommitFileAction"));
            // 🔴 新增
            group.add(am.getAction("com.bap.dev.action.CommitFileAndPublishAction"));

        } else if (userObject instanceof VirtualFileWrapper) {
            // File 节点
            group.add(am.getAction("com.bap.dev.action.UpdateFileAction"));
            group.add(am.getAction("com.bap.dev.action.CommitFileAction"));
            // 🔴 新增
            group.add(am.getAction("com.bap.dev.action.CommitFileAndPublishAction"));

            group.addSeparator();
            group.add(am.getAction("com.bap.dev.action.CompareJavaCodeAction"));
            group.add(am.getAction("com.bap.dev.action.ShowHistoryAction"));
        }

        if (group.getChildrenCount() > 0) {
            ActionPopupMenu popupMenu = am.createActionPopupMenu("BapChangesPopup", group);
            popupMenu.getComponent().show(e.getComponent(), e.getX(), e.getY());
        }
    }

    // --- 3. 对应修改 addStatusCategory (参数类型变了) ---
    private void addStatusCategory(DefaultMutableTreeNode parent, Map<BapFileStatus, List<VirtualFileWrapper>> map, BapFileStatus status, String title, VirtualFile moduleRoot) {
        List<VirtualFileWrapper> wrappers = map.get(status);
        if (wrappers != null && !wrappers.isEmpty()) {
            // 按路径排序
            wrappers.sort(Comparator.comparing(w -> w.absolutePath));

            String nodeTitle = BapBundle.message("ui.BapChangesTreePanel.category.format", title, wrappers.size());
            DefaultMutableTreeNode categoryNode = new DefaultMutableTreeNode(new CategoryWrapper(nodeTitle, status));
            parent.add(categoryNode);

            // 获取配置状态
            boolean isFlat = BapSettingsState.getInstance().flattenPackages;

            for (VirtualFileWrapper wrapper : wrappers) {
                // 1. 计算相对目录路径 (已去除 src)
                String relativeDir = getRelativeDirectory(moduleRoot, wrapper.absolutePath);

                DefaultMutableTreeNode parentNode = categoryNode;

                if (!relativeDir.isEmpty()) {
                    if (isFlat) {
                        // 🟢 扁平模式：将路径转换为点分隔包名 (例如 com.bap.dev)，直接创建一级节点
                        String packageName = relativeDir.replace('/', '.');
                        parentNode = findOrCreateChildDir(parentNode, packageName);
                    } else {
                        // 🔵 树状模式：递归创建嵌套节点 (com -> bap -> dev)
                        String[] dirs = relativeDir.split("/");
                        for (String dirName : dirs) {
                            if (dirName.isEmpty()) continue;
                            parentNode = findOrCreateChildDir(parentNode, dirName);
                        }
                    }
                }

                // 3. 添加文件节点
                parentNode.add(new DefaultMutableTreeNode(wrapper));
            }
        }
    }

    // 🔴 新增：查找或创建文件夹节点
    private DefaultMutableTreeNode findOrCreateChildDir(DefaultMutableTreeNode parent, String dirName) {
        int count = parent.getChildCount();
        // 简单线性查找 (子节点数量通常不多)
        for (int i = 0; i < count; i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) parent.getChildAt(i);
            Object userObj = child.getUserObject();
            if (userObj instanceof DirectoryWrapper && ((DirectoryWrapper) userObj).name.equals(dirName)) {
                return child;
            }
        }
        // 未找到，创建新节点
        DirectoryWrapper dirWrapper = new DirectoryWrapper(dirName);
        DefaultMutableTreeNode newNode = new DefaultMutableTreeNode(dirWrapper);
        parent.add(newNode);
        return newNode;
    }

    // 🔴 新增：计算相对目录路径 (去除 src 前缀)
    private String getRelativeDirectory(VirtualFile moduleRoot, String fileAbsolutePath) {
        String rootPath = moduleRoot.getPath().replace('\\', '/');
        String filePath = fileAbsolutePath.replace('\\', '/');

        if (!filePath.startsWith(rootPath)) return "";

        // 获取相对于模块根目录的路径
        String relative = filePath.substring(rootPath.length());
        if (relative.startsWith("/")) relative = relative.substring(1);

        // 如果是 src/ 开头，去掉 src/ (为了更简洁的显示包结构)
        if (relative.startsWith("src/")) {
            relative = relative.substring(4);
        } else if (relative.equals("src")) {
            relative = "";
        }

        // 去掉文件名，只保留目录
        int lastSlash = relative.lastIndexOf('/');
        if (lastSlash >= 0) {
            return relative.substring(0, lastSlash);
        }
        return ""; // 文件就在根目录下 (或者 src 下)
    }

    // --- 🔴 新增：DirectoryWrapper 类 ---
    private static class DirectoryWrapper {
        String name;
        DirectoryWrapper(String name) { this.name = name; }
        @Override public String toString() { return name; }
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            DirectoryWrapper that = (DirectoryWrapper) o;
            return Objects.equals(name, that.name);
        }
        @Override public int hashCode() { return Objects.hash(name); }
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
        private final JPanel buttonPanel = new JPanel(new GridLayout(1, 5, 2, 0));

        public BapChangeRenderer() {
            modulePanel.setOpaque(true);
            buttonPanel.setOpaque(false);

            // 🔴 修改 4: 同步图标并添加新按钮
            buttonPanel.add(iconLabel(AllIcons.Actions.Refresh)); // Refresh
            buttonPanel.add(iconLabel(AllIcons.Actions.CheckOut)); // Update (plugin.xml: CheckOut)
            buttonPanel.add(iconLabel(AllIcons.Actions.AddList));  // Commit (plugin.xml: AddList) -> 替换了原来的 Actions.Commit
            buttonPanel.add(iconLabel(AllIcons.RunConfigurations.Compound)); // 🔴 新增：Commit & Publish
            buttonPanel.add(iconLabel(AllIcons.Actions.Execute)); // Publish

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
                } else if (userObject instanceof DirectoryWrapper) {
                    // 🔴 新增：DirectoryWrapper 渲染
                    renderer.append(((DirectoryWrapper) userObject).name, SimpleTextAttributes.REGULAR_ATTRIBUTES);
                    renderer.setIcon(AllIcons.Nodes.Package); // 使用包图标
                } else if (userObject instanceof VirtualFileWrapper) {
                    VirtualFileWrapper wrapper = (VirtualFileWrapper) userObject;
                    SimpleTextAttributes attr = SimpleTextAttributes.REGULAR_ATTRIBUTES;
                    String suffix = "";
                    BapSettingsState settings = BapSettingsState.getInstance();
                    java.awt.Color modColor = settings.getModifiedColorObj();
                    java.awt.Color addColor = settings.getAddedColorObj();
                    java.awt.Color delColor = settings.getDeletedColorObj();
                    switch (wrapper.status) {
                        case MODIFIED: attr = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, modColor); suffix = " "+BapBundle.message("status.symbol.modified"); break; // " [M]"
                        case ADDED: attr = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, addColor); suffix = " "+BapBundle.message("status.symbol.added"); break;       // " [A]"
                        case DELETED_LOCALLY: attr = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, delColor); suffix = " "+BapBundle.message("status.symbol.deleted"); break; // " [D]"
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

    /**
     * 🔴 核心修复：一个“有父级”的虚拟文件
     * 专门用于欺骗 Action，让它们能通过 getParent() 找到 ModuleRoot
     */
    private static class BapDeletedVirtualFile extends LightVirtualFile {
        private final VirtualFile physicalParent;
        private final String absolutePath;

        public BapDeletedVirtualFile(String name, com.intellij.openapi.fileTypes.FileType fileType, String absolutePath, VirtualFile physicalParent) {
            super(name, fileType, "");
            this.absolutePath = absolutePath;
            this.physicalParent = physicalParent;
            setWritable(false);
        }

        @Override
        public VirtualFile getParent() {
            return physicalParent; // 关键：返回真实的物理父目录
        }

        @Override
        public String getPath() {
            return absolutePath; // 返回真实的绝对路径
        }

        @Override
        public boolean isValid() {
            return true; // 欺骗 Action 说这个文件是有效的（否则某些检查会过不去）
        }

        // 确保 equals/hashCode 正常，防止集合操作异常
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            BapDeletedVirtualFile that = (BapDeletedVirtualFile) obj;
            return Objects.equals(absolutePath, that.absolutePath);
        }

        @Override
        public int hashCode() {
            return Objects.hash(absolutePath);
        }
    }

    private class ToggleFlattenPackagesAction extends ToggleAction {
        public ToggleFlattenPackagesAction() {
            // 使用 IntelliJ 自带的 "Flatten Packages" 图标
            super(BapBundle.message("ui.BapChangesTreePanel.flatten_packages"), // 建议在 Bundle 中添加: "Flatten Packages" 或 "扁平化包路径"
                    BapBundle.message("ui.BapChangesTreePanel.action.flatten.desc"),
                    AllIcons.ObjectBrowser.FlattenPackages);
        }

        @Override
        public boolean isSelected(@NotNull AnActionEvent e) {
            return BapSettingsState.getInstance().flattenPackages;
        }

        @Override
        public void setSelected(@NotNull AnActionEvent e, boolean state) {
            BapSettingsState.getInstance().flattenPackages = state;
            rebuildTree(); // 切换后立即重绘树
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }
    }
}