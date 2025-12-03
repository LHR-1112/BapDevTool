package com.bap.dev.handler;

import bap.java.CJavaConst;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.JavaSdk;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.net.URI; // 引入 URI 类
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AdminToolLauncher {

    public static void launch(Project project, VirtualFile moduleRoot) {
        File confFile = new File(moduleRoot.getPath(), CJavaConst.PROJECT_DEVELOP_CONF_FILE);
        if (!confFile.exists()) {
            showError(project, "未找到配置文件: " + confFile.getName());
            return;
        }

        try {
            // 1. 读取配置
            String content = new String(Files.readAllBytes(confFile.toPath()), StandardCharsets.UTF_8);
            String uriStr = extractAttr(content, "Uri"); // 比如 ws://127.0.0.1:2020
            String user = extractAttr(content, "User");
            String pwd = extractAttr(content, "Password");
            String adminTool = extractAttr(content, "AdminTool");

            if (adminTool == null || adminTool.isEmpty()) {
                adminTool = "bap.client.BapMainFrame";
            }

            // 2. 构建 Java 启动参数
            JavaParameters params = new JavaParameters();

            Sdk projectSdk = ProjectRootManager.getInstance(project).getProjectSdk();
            if (projectSdk == null) {
                projectSdk = ProjectJdkTable.getInstance().findMostRecentSdkOfType(JavaSdk.getInstance());
            }
            if (projectSdk == null) {
                showError(project, "未找到有效的 JDK。");
                return;
            }
            params.setJdk(projectSdk);
            params.setMainClass(adminTool);

            // --- ✅ 新增：强制关闭 Headless 模式，允许 GUI 显示 ---
            params.getVMParametersList().add("-Djava.awt.headless=false");

            // --- ✅ 新增：显式设置工作目录 ---
            params.setWorkingDirectory(moduleRoot.getPath());

            // --- 🔴 核心修复：解析 URI 并拆分参数以匹配 BapMainFrame 的要求 ---
            // BapMainFrame main(args) 要求: args[0]=host, args[1]=port, args[2]=path, args[3]=user, args[4]=pwd

            URI uriObj = URI.create(uriStr);
            String host = uriObj.getHost();
            int port = uriObj.getPort();
            String path = uriObj.getPath();
            if (path == null) path = ""; // 防止 null

            // 按顺序添加 5 个参数
            // 原代码
            // params.getProgramParametersList().add(host); // args[0]

            // ✅ 修改后的代码：参考 DevConf 的逻辑
            if ("wss".equalsIgnoreCase(uriObj.getScheme())) {
                // 如果是 wss，将协议头拼接到 host 参数中
                params.getProgramParametersList().add("wss://" + host);
            } else {
                params.getProgramParametersList().add(host);
            }
            params.getProgramParametersList().add(String.valueOf(port));// args[1]
            params.getProgramParametersList().add(path);                // args[2]
            params.getProgramParametersList().add(user);                // args[3]
            params.getProgramParametersList().add(pwd);                 // args[4]
            // ---------------------------------------------------------------

            // D. 构建 Classpath
            Module module = findModule(project, moduleRoot);
            if (module != null) {
                params.configureByModule(module, JavaParameters.JDK_AND_CLASSES);
            } else {
                File libDir = new File(moduleRoot.getPath(), "lib");
                addJarsFromDir(params, libDir);
                addJarsFromDir(params, new File(libDir, "platform"));
                addJarsFromDir(params, new File(libDir, "plugin"));
                addJarsFromDir(params, new File(libDir, "project"));
            }

            // 3. 启动进程
            GeneralCommandLine commandLine = params.toCommandLine();
            OSProcessHandler handler = new OSProcessHandler(commandLine);

            // --- ✅ 新增：监听输出流 ---
            handler.addProcessListener(new ProcessAdapter() {
                @Override
                public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                    // 将子进程的输出打印到 IDEA 的 Log 中，或者如果您有 ConsoleView 可以打印到那里
                    // 这里为了简单，先打印到系统控制台（在 IDEA 的 idea.log 或启动终端可见）
                    System.out.println("[AdminTool] " + event.getText());
                }

                @Override
                public void processTerminated(@NotNull ProcessEvent event) {
                    String msg = "管理工具已关闭 (Exit Code: " + event.getExitCode() + ")";
                    sendNotification(project, "管理工具已关闭", msg);
                }
            });

            handler.startNotify();

            sendNotification(project, "启动成功", "管理工具已启动 (Target: " + host + ":" + port + ")");

        } catch (Exception e) {
            e.printStackTrace();
            showError(project, "启动失败: " + e.getMessage());
        }
    }

    // ... 下面的辅助方法保持不变 ...

    private static Module findModule(Project project, VirtualFile moduleRoot) {
        for (Module m : ModuleManager.getInstance(project).getModules()) {
            for (VirtualFile root : ModuleRootManager.getInstance(m).getContentRoots()) {
                if (root.equals(moduleRoot)) return m;
            }
        }
        return null;
    }

    private static void addJarsFromDir(JavaParameters params, File dir) {
        if (dir != null && dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile() && (f.getName().endsWith(".jar") || f.getName().endsWith(".zip"))) {
                        params.getClassPath().add(f.getAbsolutePath());
                    }
                }
            }
        }
    }

    private static String extractAttr(String xml, String attr) {
        Pattern p = Pattern.compile(attr + "=\"([^\"]*)\"");
        Matcher m = p.matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    private static void showError(Project project, String msg) {
        ApplicationManager.getApplication().invokeLater(() -> Messages.showErrorDialog(msg, "Launcher Error"));
    }

    private static void sendNotification(Project project, String title, String content) {
        Notification notification = new Notification("Cloud Project Download", title, content, NotificationType.INFORMATION);
        Notifications.Bus.notify(notification, project);
    }
}