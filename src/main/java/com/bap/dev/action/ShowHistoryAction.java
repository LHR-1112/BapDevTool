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

        // 2. 解析全类名
        String fullClassName = ReadAction.compute(() -> {
            if (!selectedFile.isValid()) return null;
            PsiFile psiFile = PsiManager.getInstance(project).findFile(selectedFile);
            if (psiFile instanceof PsiJavaFile) {
                PsiJavaFile javaFile = (PsiJavaFile) psiFile;
                String packageName = javaFile.getPackageName();
                String className = selectedFile.getNameWithoutExtension();
                return packageName.isEmpty() ? className : packageName + "." + className;
            }
            return null;
        });

        if (fullClassName == null) {
            Messages.showWarningDialog("无法解析 Java 类名，请确认文件有效。", "错误");
            return;
        }

        // 3. 启动后台任务获取历史列表
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Querying File History...", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                queryHistory(project, moduleRoot, fullClassName, selectedFile);
            }
        });
    }

    private void queryHistory(Project project, VirtualFile moduleRoot, String fullClassName, VirtualFile localFile) {
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

            // 调用查询接口
            List<VersionNode> historyList = client.getService().queryFileHistory(projectUuid, fullClassName);

            // UI 线程显示列表
            final String fUri = uri;
            final String fUser = user;
            final String fPwd = pwd;

            ApplicationManager.getApplication().invokeLater(() -> {
                if (historyList == null || historyList.isEmpty()) {
                    Messages.showInfoMessage("未找到该文件的云端历史记录。", "无记录");
                } else {
                    // 弹出列表对话框
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

    // --- 辅助方法 (复用) ---

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
        // 仅 Java 文件显示
        boolean isJava = file != null && !file.isDirectory() && "java".equalsIgnoreCase(file.getExtension());
        // 且必须在 src 目录下 (根据需求)
        // 简单判断：路径包含 /src/
        boolean inSrc = file != null && file.getPath().contains("/src/");

        e.getPresentation().setEnabledAndVisible(isJava && inSrc);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}