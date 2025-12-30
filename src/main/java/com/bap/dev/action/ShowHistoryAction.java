package com.bap.dev.action;

import bap.java.CJavaConst;
import bap.md.ver.VersionNode;
import com.bap.dev.BapRpcClient;
import com.bap.dev.service.BapConnectionManager;
import com.bap.dev.ui.HistoryListDialog;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ShowHistoryAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile selectedFile = e.getData(CommonDataKeys.VIRTUAL_FILE);

        if (project == null || selectedFile == null) return;

        // 1. 基础检查 & 保存
        FileDocumentManager.getInstance().saveAllDocuments();
        VirtualFile moduleRoot = findModuleRoot(selectedFile);
        if (moduleRoot == null) {
            Messages.showWarningDialog("未找到 .develop 配置文件。", "错误");
            return;
        }

        // 2. 解析云端标识 (Java全类名 或 资源文件相对路径)
        String remoteKey = resolveRemoteKey(project, moduleRoot, selectedFile);

        if (remoteKey == null) {
            Messages.showWarningDialog("无法解析该文件的云端路径。\nJava文件需正确配置包名，资源文件需位于 src/res 目录下。", "不支持的文件");
            return;
        }

        // 3. 启动后台任务获取历史列表
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Querying File History...", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                // 传入解析好的 remoteKey
                queryHistory(project, moduleRoot, remoteKey, selectedFile);
            }
        });
    }

    // --- 🔴 新增：统一解析文件标识 ---
    private String resolveRemoteKey(Project project, VirtualFile moduleRoot, VirtualFile file) {
        return ReadAction.compute(() -> {
            // Case A: Java 文件 -> 获取全类名 (com.pkg.MyClass)
            if ("java".equalsIgnoreCase(file.getExtension())) {
                PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
                if (psiFile instanceof PsiJavaFile) {
                    PsiJavaFile javaFile = (PsiJavaFile) psiFile;
                    String packageName = javaFile.getPackageName();
                    String className = file.getNameWithoutExtension();
                    return packageName.isEmpty() ? className : packageName + "." + className;
                }
            }

            // Case B: 资源文件 -> 获取相对于 src/res 的路径 (pt/view/index.html)
            VirtualFile resDir = moduleRoot.findFileByRelativePath("src/res");
            if (resDir != null && VfsUtilCore.isAncestor(resDir, file, true)) {
                return VfsUtilCore.getRelativePath(file, resDir);
            }

            return null;
        });
    }

    private void queryHistory(Project project, VirtualFile moduleRoot, String remoteKey, VirtualFile localFile) {
        File confFile = new File(moduleRoot.getPath(), CJavaConst.PROJECT_DEVELOP_CONF_FILE);
        String uri = null, user = null, pwd = null, projectUuid = null;
        try {
            String content = Files.readString(confFile.toPath());
            uri = extractAttr(content, "Uri");
            user = extractAttr(content, "User");
            pwd = extractAttr(content, "Password");
            projectUuid = extractAttr(content, "Project");
        } catch (Exception e) {
            showError("读取配置失败: " + e.getMessage());
            return;
        }

        if (uri == null || projectUuid == null) {
            showError("配置文件信息不全");
            return;
        }

        BapRpcClient client = BapConnectionManager.getInstance(project).getSharedClient(uri, user, pwd);
        try {
            client.connect(uri, user, pwd);

            // 调用查询接口 (Java类名 和 资源路径 均通过此接口查询)
            List<VersionNode> historyList = client.getService().queryFileHistory(projectUuid, remoteKey);

            // UI 线程显示列表
            final String fUri = uri;
            final String fUser = user;
            final String fPwd = pwd;

            ApplicationManager.getApplication().invokeLater(() -> {
                if (historyList == null || historyList.isEmpty()) {
                    Messages.showInfoMessage("未找到该文件的云端历史记录。", "无记录");
                } else {
                    // 弹出列表对话框 (复用 HistoryListDialog)
                    new HistoryListDialog(project, localFile, historyList, fUri, fUser, fPwd).show();
                }
            });

        } catch (Exception e) {
            e.printStackTrace();
            showError("查询历史失败: " + e.getMessage());
        } finally {
            client.shutdown();
        }
    }

    // --- 辅助方法 ---

    private VirtualFile findModuleRoot(VirtualFile current) {
        VirtualFile dir = current.isDirectory() ? current : current.getParent();
        while (dir != null) {
            VirtualFile configFile = dir.findChild(CJavaConst.PROJECT_DEVELOP_CONF_FILE);
            if (configFile != null && configFile.exists()) return dir;
            dir = dir.getParent();
        }
        return null;
    }

    private String extractAttr(String xml, String attr) {
        Pattern p = Pattern.compile(attr + "=\"([^\"]*)\"");
        Matcher m = p.matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    private void showError(String msg) {
        ApplicationManager.getApplication().invokeLater(() -> Messages.showErrorDialog(msg, "History Error"));
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);

        // 🔴 修改：解除文件类型限制，只要是文件且在 src 目录下即可
        // 具体的路径合法性 (是否在 src/res 或 src/java) 交给 actionPerformed 判断
        boolean isValidFile = file != null && !file.isDirectory();
        boolean inSrc = file != null && file.getPath().contains("/src/");

        e.getPresentation().setEnabledAndVisible(isValidFile && inSrc);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}