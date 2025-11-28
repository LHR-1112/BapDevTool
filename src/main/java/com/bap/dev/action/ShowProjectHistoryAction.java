package com.bap.dev.action;

import bap.dev.JavaDto;
import bap.java.CJavaConst;
import bap.md.ver.VersionNode;
import com.bap.dev.BapRpcClient;
import com.bap.dev.ui.ProjectHistoryDialog;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ShowProjectHistoryAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile selectedFile = e.getData(CommonDataKeys.VIRTUAL_FILE);

        if (project == null || selectedFile == null) return;

        // 检查是否为模块根目录
        File confFile = new File(selectedFile.getPath(), CJavaConst.PROJECT_DEVELOP_CONF_FILE);
        if (!confFile.exists()) {
            Messages.showWarningDialog("请选中 Bap 模块的根目录 (包含 .develop 文件) 执行此操作。", "提示");
            return;
        }

        // 启动后台任务
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Loading Project History...", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                loadAndShowHistory(project, selectedFile, indicator);
            }
        });
    }

    private void loadAndShowHistory(Project project, VirtualFile moduleRoot, ProgressIndicator indicator) {
        // 1. 读取配置
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

        BapRpcClient client = new BapRpcClient();
        try {
            indicator.setIndeterminate(true);
            indicator.setText("Connecting...");
            client.connect(uri, user, pwd);

            // 2. 扫描 src 下的所有子文件夹
            VirtualFile srcDir = moduleRoot.findChild("src");
            if (srcDir == null || !srcDir.exists()) {
                showError("未找到 src 目录");
                return;
            }

            Map<Long, List<VersionNode>> versionNodesMap = new HashMap<>();

            // 获取所有一级子目录 (folderName)
            List<String> folders = new ArrayList<>();
            for (VirtualFile child : srcDir.getChildren()) {
                if (child.isDirectory()) {
                    folders.add(child.getName());
                }
            }

            int totalFolders = folders.size();
            for (int i = 0; i < totalFolders; i++) {
                String folderName = folders.get(i);
                if (indicator.isCanceled()) break;

                indicator.setIndeterminate(false);
                indicator.setFraction((double) i / totalFolders);
                indicator.setText("Scanning folder: " + folderName + "...");

                try {
                    // A. 获取该文件夹下的所有代码文件
                    Map<String, JavaDto> codeFileMap = client.getService().queryCodeFile(projectUuid, folderName);

                    if (codeFileMap != null && !codeFileMap.isEmpty()) {
                        Set<String> fullClassPaths = codeFileMap.values().stream()
                                .map(JavaDto::getFullClass)
                                .collect(Collectors.toSet());

                        // B. 遍历每个文件查询历史
                        int fileCount = 0;
                        for (String fullClassPath : fullClassPaths) {
                            fileCount++;
                            indicator.setText2("Fetching history for: " + fullClassPath);

                            List<VersionNode> versionNodes = client.getService().queryFileHistory(projectUuid, fullClassPath);

                            if (versionNodes != null) {
                                for (VersionNode versionNode : versionNodes) {
                                    // 注意: DTO 里的 versionNo 是 int，这里转 Long 作为 Key
                                    Long versionNo = versionNode.versionNo;
                                    versionNodesMap.computeIfAbsent(versionNo, k -> new ArrayList<>()).add(versionNode);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Failed to query folder: " + folderName + " - " + e.getMessage());
                }
            }

            // 3. 显示结果
            final String fUri = uri;
            final String fUser = user;
            final String fPwd = pwd;
            final String fUuid = projectUuid; // 确保传递 projectUuid

            ApplicationManager.getApplication().invokeLater(() -> {
                if (versionNodesMap.isEmpty()) {
                    Messages.showInfoMessage("未找到任何历史记录。", "Project History");
                } else {
                    // 传入所有需要的参数
                    new ProjectHistoryDialog(project, versionNodesMap, fUuid, fUri, fUser, fPwd).show();
                }
            });

        } catch (Exception e) {
            e.printStackTrace();
            showError("获取历史失败: " + e.getMessage());
        } finally {
            client.shutdown();
        }
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
        // 只有选中模块根目录时才显示 (包含 .develop 文件)
        boolean isModuleRoot = file != null && file.isDirectory() && file.findChild(CJavaConst.PROJECT_DEVELOP_CONF_FILE) != null;
        e.getPresentation().setEnabledAndVisible(isModuleRoot);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}