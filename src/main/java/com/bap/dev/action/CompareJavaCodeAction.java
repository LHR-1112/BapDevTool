package com.bap.dev.action;

import bap.java.CJavaCode;
import bap.java.CJavaConst;
import com.bap.dev.BapRpcClient;
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
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;

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
            Messages.showWarningDialog("未找到 .develop 配置文件，无法连接云端。", "错误");
            return;
        }

        // 3. 解析全类名 (修复版)
        String fullClassName = ReadAction.compute(() -> {
            if (!selectedFile.isValid()) return null;

            // A. 优先尝试 PSI 解析 (适用于有内容的正常文件)
            if (selectedFile.getLength() > 0) {
                PsiFile psiFile = PsiManager.getInstance(project).findFile(selectedFile);
                if (psiFile instanceof PsiJavaFile) {
                    PsiJavaFile javaFile = (PsiJavaFile) psiFile;
                    String packageName = javaFile.getPackageName();
                    String className = selectedFile.getNameWithoutExtension();
                    if (packageName != null && !packageName.isEmpty()) {
                        return packageName + "." + className;
                    }
                }
            }

            // B. 兜底逻辑：通过文件路径推断 (适用于空文件/红D文件)
            VirtualFile parent = selectedFile.getParent();
            VirtualFile srcDir = null;

            // 向上寻找 "src" 目录
            while (parent != null) {
                if ("src".equals(parent.getName())) {
                    srcDir = parent;
                    break;
                }
                parent = parent.getParent();
            }

            if (srcDir != null) {
                // pathFromSrc 例如: "core/rda/dtos/A.java"
                String pathFromSrc = VfsUtilCore.getRelativePath(selectedFile, srcDir);
                if (pathFromSrc != null) {
                    // 去掉第一层目录 (core)，保留后面作为包名
                    int firstSlash = pathFromSrc.indexOf('/');
                    if (firstSlash > 0) {
                        String packagePath = pathFromSrc.substring(firstSlash + 1); // "rda/dtos/A.java"
                        if (packagePath.endsWith(".java") || packagePath.endsWith(".JAVA")) {
                            packagePath = packagePath.substring(0, packagePath.length() - 5);
                        }
                        return packagePath.replace('/', '.'); // "rda.dtos.A"
                    }
                }
            }

            return null;
        });

        if (fullClassName == null) {
            Messages.showWarningDialog("无法解析 Java 类名，请确认这是有效的 Java 文件。", "错误");
            return;
        }

        // 4. 启动后台任务
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Fetching Remote Code...", true) {
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
            showError(project, "读取配置失败: " + e.getMessage());
            return;
        }

        if (uri == null || projectUuid == null) {
            showError(project, "配置文件信息不全");
            return;
        }

        BapRpcClient client = new BapRpcClient();
        try {
            client.connect(uri, user, pwd);
            Object remoteObj = client.getService().getJavaCode(projectUuid, fullClassName);

            String remoteCodeContent = null;
            if (remoteObj != null) {
                if (remoteObj instanceof CJavaCode) {
                    remoteCodeContent = ((CJavaCode) remoteObj).getCode(); // 注意: 这里可能需要 getCode() 方法
                } else {
                    remoteCodeContent = getFieldString(remoteObj, "code");
                }
            }

            final String finalRemoteCode = remoteCodeContent;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (finalRemoteCode == null) {
                    Messages.showInfoMessage("云端不存在此文件 (New File)", "比对结果");
                } else {
                    showDiffWindow(project, localFile, finalRemoteCode);
                }
            });

        } catch (Exception e) {
            showError(project, "RPC 请求失败: " + e.getMessage());
        } finally {
            client.shutdown();
        }
    }

    private void showDiffWindow(Project project, VirtualFile localFile, String remoteContent) {
        DiffContentFactory contentFactory = DiffContentFactory.getInstance();
        DiffContent localDiffContent = contentFactory.create(project, localFile);
        DiffContent remoteDiffContent = contentFactory.create(project, remoteContent, JavaFileType.INSTANCE);

        SimpleDiffRequest request = new SimpleDiffRequest(
                "Bap Code Compare: " + localFile.getName(),
                localDiffContent,
                remoteDiffContent,
                "Local (Disk)",
                "Remote (Cloud)"
        );

        DiffManager.getInstance().showDiff(project, request);
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
        ApplicationManager.getApplication().invokeLater(() -> Messages.showErrorDialog(msg, "Compare Error"));
    }
}