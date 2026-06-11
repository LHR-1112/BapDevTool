package com.bap.dev.action;

import bap.java.CJavaCode;
import bap.java.CJavaConst;
import com.bap.dev.BapRpcClient;
import com.bap.dev.service.BapConnectionManager;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.ide.highlighter.JavaFileType;
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
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;
import com.bap.dev.i18n.BapBundle;

import java.io.File;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CompareJavaCodeAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile selectedFile = e.getData(CommonDataKeys.VIRTUAL_FILE);

        if (project == null || selectedFile == null) return;

        // 1. 保存当前文档
        FileDocumentManager.getInstance().saveAllDocuments();

        // 2. 向上查找模块根目录
        VirtualFile moduleRoot = findModuleRoot(selectedFile);
        if (moduleRoot == null) {
            Messages.showWarningDialog(BapBundle.message("warning.no_develop_config"), BapBundle.message("notification.error_title"));
            return;
        }

        // 3. 解析全类名 (修复版：使用字符串路径兜底)
        String fullClassName = resolveClassName(project, selectedFile);

        if (fullClassName == null) {
            Messages.showWarningDialog(BapBundle.message("action.CompareJavaCodeAction.warning.invalid_classname"), BapBundle.message("notification.error_title"));
            return;
        }

        // 4. 启动后台任务
        ProgressManager.getInstance().run(new Task.Backgroundable(project, BapBundle.message("action.CompareJavaCodeAction.progress.fetching"), true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                fetchAndDiff(project, moduleRoot, fullClassName, selectedFile);
            }
        });
    }

    private void fetchAndDiff(Project project, VirtualFile moduleRoot, String fullClassName, VirtualFile localFile) {
        File confFile = new File(moduleRoot.getPath(), CJavaConst.PROJECT_DEVELOP_CONF_FILE);
        String uri = null, user = null, pwd = null, projectUuid = null;
        try {
            String content = Files.readString(confFile.toPath());
            uri = extractAttr(content, "Uri");
            user = extractAttr(content, "User");
            pwd = extractAttr(content, "Password");
            projectUuid = extractAttr(content, "Project");
        } catch (Exception e) {
            showError(project, BapBundle.message("error.read_config", e.getMessage()));
            return;
        }

        if (uri == null || projectUuid == null) {
            showError(project, BapBundle.message("error.config_incomplete"));
            return;
        }

        // 获取共享连接
        BapRpcClient client = BapConnectionManager.getInstance(project).getSharedClient(uri, user, pwd);
        try {
            // client.connect(uri, user, pwd); // SharedClient 内部已管理连接状态，通常无需手动 connect，除非是为了触发重连逻辑
            Object remoteObj = client.getService().getJavaCode(projectUuid, fullClassName);

            String remoteCodeContent = null;
            if (remoteObj != null) {
                if (remoteObj instanceof CJavaCode) {
                    remoteCodeContent = ((CJavaCode) remoteObj).getCode();
                } else {
                    remoteCodeContent = getFieldString(remoteObj, "code");
                }
            }

            final String finalRemoteCode = remoteCodeContent;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (finalRemoteCode == null) {
                    Messages.showInfoMessage(BapBundle.message("action.CompareJavaCodeAction.info.remote_missing"), BapBundle.message("action.CompareJavaCodeAction.info.diff_title"));
                } else {
                    showDiffWindow(project, localFile, finalRemoteCode);
                }
            });

        } catch (Exception e) {
            showError(project, BapBundle.message("error.rpc_failed", e.getMessage()));
        }
        // 🔴 修复：移除 finally { client.shutdown(); }，共享连接不能关闭！
    }

    private void showDiffWindow(Project project, VirtualFile localFile, String remoteContent) {
        DiffContentFactory contentFactory = DiffContentFactory.getInstance();

        // 针对“红D”文件（内容为空/不存在），LocalDiffContent 应该是空的
        DiffContent localDiffContent;
        if (!localFile.exists() || localFile.getLength() == 0) {
            localDiffContent = contentFactory.create(project, "", JavaFileType.INSTANCE);
        } else {
            localDiffContent = contentFactory.create(project, localFile);
        }

        DiffContent remoteDiffContent = contentFactory.create(project, remoteContent, JavaFileType.INSTANCE);

        SimpleDiffRequest request = new SimpleDiffRequest(
                BapBundle.message("action.CompareJavaCodeAction.dialog.title", localFile.getName()),
                localDiffContent,
                remoteDiffContent,
                BapBundle.message("action.CompareJavaCodeAction.label.local"),
                BapBundle.message("action.CompareJavaCodeAction.label.remote")
        );

        DiffManager.getInstance().showDiff(project, request);
    }

    // --- 🔴 修复：基于字符串路径的类名解析 (兼容红D/Deleted文件) ---
    private String resolveClassName(Project project, VirtualFile file) {
        return ReadAction.compute(() -> {
            // A. 优先 PSI 解析 (文件存在且有内容)
            if (file.isValid() && file.getLength() > 0) {
                PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
                if (psiFile instanceof PsiJavaFile) {
                    PsiJavaFile javaFile = (PsiJavaFile) psiFile;
                    String packageName = javaFile.getPackageName();
                    String className = file.getNameWithoutExtension();
                    if (!packageName.isEmpty()) {
                        return packageName + "." + className;
                    }
                }
            }

            // B. 兜底逻辑：字符串路径解析
            VirtualFile parent = file.getParent();
            VirtualFile srcDir = null;

            // 1. 尝试向上找 src
            while (parent != null) {
                if ("src".equals(parent.getName())) { srcDir = parent; break; }
                parent = parent.getParent();
            }

            // 2. 如果父级链断了 (因为是 DeletedPlaceholderFile)，尝试从 ModuleRoot 找
            if (srcDir == null) {
                VirtualFile moduleRoot = findModuleRoot(file);
                if (moduleRoot != null) {
                    srcDir = moduleRoot.findChild("src");
                }
            }

            if (srcDir == null) return null;

            // 3. 计算相对路径
            String srcPath = srcDir.getPath().replace('\\', '/');
            String filePath = file.getPath().replace('\\', '/'); // 红D文件会返回构造时的绝对路径

            if (!filePath.startsWith(srcPath)) return null;

            String relative = filePath.substring(srcPath.length());
            if (relative.startsWith("/")) relative = relative.substring(1);
            if (relative.isEmpty()) return null;

            // relative: "core/com/bap/Test.java" -> 去掉第一层 "core"
            int slash = relative.indexOf('/');
            if (slash > 0) {
                String pkgPath = relative.substring(slash + 1);
                if (pkgPath.toLowerCase().endsWith(".java")) {
                    pkgPath = pkgPath.substring(0, pkgPath.length() - 5);
                }
                return pkgPath.replace('/', '.');
            }

            return null;
        });
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        boolean isJava = file != null && !file.isDirectory() && "java".equalsIgnoreCase(file.getExtension());
        e.getPresentation().setEnabledAndVisible(isJava);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

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

    private String getFieldString(Object obj, String fieldName) {
        try {
            java.lang.reflect.Field field = obj.getClass().getField(fieldName);
            Object val = field.get(obj);
            return val != null ? val.toString() : null;
        } catch (Exception e) { return null; }
    }

    private void showError(Project project, String msg) {
        ApplicationManager.getApplication().invokeLater(() -> Messages.showErrorDialog(msg, BapBundle.message("action.CompareJavaCodeAction.error.compare_error")));
    }
}