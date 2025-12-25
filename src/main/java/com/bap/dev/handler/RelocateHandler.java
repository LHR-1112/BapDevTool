package com.bap.dev.handler;

import bap.java.CJavaConst;
import bap.java.CJavaProjectDto;
import com.bap.dev.BapRpcClient;
import com.bap.dev.settings.BapSettingsState;
import com.bap.dev.ui.LogonDialog;
import com.bap.dev.ui.RelocateDialog;
import com.bap.dev.ui.RelocateHistoryDialog;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RelocateHandler {

    public static void relocate(Project project, VirtualFile moduleRoot) {
        String modulePath = moduleRoot.getPath();
        File confFile = new File(modulePath, CJavaConst.PROJECT_DEVELOP_CONF_FILE);

        // 1. 读取当前配置 (为了获取默认 AdminTool 和做对比)
        String oldContent = "";
        String defUri = "", defUser = "", defPwd = "", defAdminTool = "bap.client.BapMainFrame";

        if (confFile.exists()) {
            try {
                oldContent = Files.readString(confFile.toPath());
                defUri = extractAttr(oldContent, "Uri");
                defUser = extractAttr(oldContent, "User");
                defPwd = extractAttr(oldContent, "Password");
                String tool = extractAttr(oldContent, "AdminTool");
                if (tool != null) defAdminTool = tool;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        final String finalAdminTool = defAdminTool; // 供后续使用

        // --- 🔴 Step 0: 检查历史记录 ---
        List<BapSettingsState.RelocateProfile> history = BapSettingsState.getInstance().getRelocateHistory(modulePath);
        if (!history.isEmpty()) {
            // --- 🔴 修改：传入 modulePath ---
            RelocateHistoryDialog historyDialog = new RelocateHistoryDialog(project, history, modulePath);
            // -----------------------------
            if (historyDialog.showAndGet()) {
                if (historyDialog.isNewConnectionRequested()) {
                    // 用户点了 "New Connection"，继续下面的标准流程
                } else {
                    // 用户选了历史记录 -> 直接写入文件
                    BapSettingsState.RelocateProfile profile = historyDialog.getSelectedProfile();
                    if (profile != null) {
                        try {
                            // 优先使用历史里的 AdminTool，如果没有则使用文件原本的
                            String toolToWrite = (profile.adminTool != null && !profile.adminTool.isEmpty()) ? profile.adminTool : finalAdminTool;

                            saveNewConfig(confFile, profile.projectUuid, profile.uri, profile.user, profile.pwd, toolToWrite);

                            // 更新一下历史记录的顺序（置顶）
                            BapSettingsState.getInstance().addRelocateHistory(modulePath, profile);

                            Messages.showInfoMessage("Successfully switched back to project:\n" + profile.projectName, "Relocated");
                            return; // 结束，不走网络连接
                        } catch (IOException e) {
                            showError("Failed to write config: " + e.getMessage(), project);
                            // 写入失败，可能想走新连接，继续往下流转
                        }
                    }
                }
            } else {
                return; // 用户点击 Cancel
            }
        }
        // -----------------------------

        // 2. 弹出登录框 (Step 1)
        // 这里的 defUri 等如果上面历史记录没命中，还是用文件里的旧值
        LogonDialog logonDialog = new LogonDialog(project, defUri, defUser, defPwd);
        if (!logonDialog.showAndGet()) {
            return; // 用户取消
        }

        String newUri = logonDialog.getUri();
        String newUser = logonDialog.getUser();
        String newPwd = logonDialog.getPwd();

        // 3. 后台连接并获取列表
        BapRpcClient client = new BapRpcClient();

        ProgressManager.getInstance().run(new Task.Modal(project, "Connecting to " + newUri + "...", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    indicator.setIndeterminate(true);
                    client.connect(newUri, newUser, newPwd);

                    indicator.setText("Fetching project list...");
                    List<CJavaProjectDto> projects = client.getService().getAllProjects();

                    // 4. UI 线程弹出工程选择框 (Step 2)
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (projects == null || projects.isEmpty()) {
                            showError("连接成功，但未获取到任何工程列表。", project);
                            client.shutdown();
                            return;
                        }

                        RelocateDialog pjDialog = new RelocateDialog(project, client, projects);
                        if (pjDialog.showAndGet()) {
                            CJavaProjectDto selected = pjDialog.getSelectedProject();
                            if (selected != null) {
                                // 5. 执行重定向 (保存全量新配置)
                                try {
                                    saveNewConfig(confFile, selected.getUuid(), newUri, newUser, newPwd, finalAdminTool);

                                    // --- 🔴 成功后保存到历史记录 ---
                                    BapSettingsState.RelocateProfile profile = new BapSettingsState.RelocateProfile(
                                            newUri, newUser, newPwd, selected.getUuid(), selected.getName(), finalAdminTool
                                    );
                                    BapSettingsState.getInstance().addRelocateHistory(modulePath, profile);
                                    // ----------------------------

                                    Messages.showInfoMessage("Project relocated to: " + selected.getName() + "\nServer: " + newUri, "Success");
                                } catch (Exception e) {
                                    showError("保存配置失败: " + e.getMessage(), project);
                                }
                            }
                        }
                        client.shutdown();
                    });

                } catch (Exception e) {
                    showError("连接失败: " + e.getMessage(), project);
                    client.shutdown();
                }
            }
        });
    }

    private static void saveNewConfig(File confFile, String pjUuid, String uri, String user, String pwd, String adminTool) throws IOException {
        String xmlContent = String.format(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                        "\n" +
                        "<Development Project=\"%s\" Uri=\"%s\" AdminTool=\"%s\" User=\"%s\" Password=\"%s\" LocalNioPort=\"-1\"/>",
                pjUuid,
                uri,
                adminTool,
                user,
                pwd
        );

        try (FileOutputStream fos = new FileOutputStream(confFile)) {
            fos.write(xmlContent.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String extractAttr(String xml, String attr) {
        Pattern p = Pattern.compile(attr + "=\"([^\"]*)\"");
        Matcher m = p.matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    private static void showError(String msg, Project project) {
        ApplicationManager.getApplication().invokeLater(() -> Messages.showErrorDialog(project, msg, "Relocate Error"));
    }
}