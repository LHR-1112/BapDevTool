package com.bap.dev.action;

import bap.java.CJavaCode;
import bap.java.CJavaConst;
import bap.java.CJavaDebuggerDto;
import com.bap.dev.BapRpcClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StartDebugAction extends AnAction {

    @Override
    public void update(@NotNull AnActionEvent e) {
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        e.getPresentation().setEnabledAndVisible(psiFile instanceof PsiJavaFile);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);

        if (project == null || !(psiFile instanceof PsiJavaFile)) return;

        PsiJavaFile javaFile = (PsiJavaFile) psiFile;

        // 1. 获取类信息
        String[] classInfo = ReadAction.compute(() -> {
            String pkg = javaFile.getPackageName();
            PsiClass[] classes = javaFile.getClasses();
            if (classes.length == 0) return null;
            String name = classes[0].getName();
            String code = javaFile.getText();
            return new String[]{pkg, name, code};
        });

        if (classInfo == null) {
            Messages.showWarningDialog("无法解析当前 Java 类。", "错误");
            return;
        }

        String packageName = classInfo[0];
        String className = classInfo[1];
        String fullCode = classInfo[2];

        // 1.1 构建新的包名 (追加 .debug)
        String debugPackageName = (packageName == null || packageName.isEmpty())
                ? "debug"
                : packageName + ".debug";

        // 1.2 修改源代码中的 package 声明
        String modifiedCode;
        if (packageName == null || packageName.isEmpty()) {
            modifiedCode = "package " + debugPackageName + ";\n" + fullCode;
        } else {
            String regex = "package\\s+" + Pattern.quote(packageName) + "\\s*;";
            modifiedCode = fullCode.replaceFirst(regex, "package " + debugPackageName + ";");
        }

        // 2. 获取连接配置
        VirtualFile vFile = javaFile.getVirtualFile();
        String[] config = findConfig(vFile);
        if (config == null) {
            Messages.showWarningDialog("在当前模块路径下未找到 .develop 配置文件。", "配置丢失");
            return;
        }
        String uri = config[0];
        String user = config[1];
        String pwd = config[2];

        // 3. 打开控制台 (修改为默认 Run 窗口)
        ConsoleView console = getOrCreateConsole(project);
        console.clear();
        console.print("Preparing to debug class [" + className + "] in package [" + debugPackageName + "]...\n", ConsoleViewContentType.SYSTEM_OUTPUT);

        // 4. 后台执行
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Debugging " + className, true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                BapRpcClient client = new BapRpcClient();
                try {
                    indicator.setText("Connecting...");
                    client.connect(uri, user, pwd);
                    console.print("Connected to " + uri + "\n", ConsoleViewContentType.SYSTEM_OUTPUT);

                    CJavaCode javaCode = new CJavaCode();
                    javaCode.setJavaPackage(debugPackageName);
                    javaCode.setName(className);
                    javaCode.setMainClass(className);
                    javaCode.setUuid(UUID.randomUUID().toString().replace("-", "_"));
                    javaCode.setCode(modifiedCode);

                    indicator.setText("Executing...");
                    console.print("Uploading and Executing...\n", ConsoleViewContentType.SYSTEM_OUTPUT);

                    String debugKey = client.getService().startDebugJava(javaCode, new URI(uri));

                    if (debugKey == null) {
                        printError(console, "Server returned null debugKey.");
                        return;
                    }

                    Thread.sleep(800);

                    List<CJavaDebuggerDto> allDebugger = client.getService().getAllDebugger(false);
                    CJavaDebuggerDto debuggerDto = null;
                    if (allDebugger != null) {
                        for (CJavaDebuggerDto dto : allDebugger) {
                            if (debugKey.equals(dto.getUuid())) {
                                debuggerDto = dto;
                                break;
                            }
                        }
                    }

                    boolean isException = false;
                    try { isException = client.getService().isException(debugKey); } catch (Exception ignore) {}

                    Object result = null;
                    try { result = client.getService().getResult(debugKey); } catch (Exception ignore) {}

                    String resultText = "";
                    try { resultText = client.getService().getResultText(debugKey); } catch (Exception ignore) {}

                    List<String> traces = null;
                    try { traces = client.getService().popTrace(debugKey); } catch (Exception ignore) {}

                    printResult(console, debugKey, debuggerDto, isException, result, resultText, traces);

                } catch (Exception ex) {
                    printError(console, "Execution Failed: " + ex.getMessage());
                    ex.printStackTrace();
                } finally {
                    client.shutdown();
                }
            }
        });
    }

    // --- 核心修改：使用 ExecutionManager 在 Run 窗口打开 ---
    private ConsoleView getOrCreateConsole(Project project) {
        final String consoleTitle = "Cloud Debug";
        RunContentManager contentManager = ExecutionManager.getInstance(project).getContentManager();

        // 1. 尝试查找已存在的 Tab
        for (RunContentDescriptor descriptor : contentManager.getAllDescriptors()) {
            if (consoleTitle.equals(descriptor.getDisplayName()) && descriptor.getExecutionConsole() instanceof ConsoleView) {
                // 激活这个 Tab
                contentManager.toFrontRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor);
                return (ConsoleView) descriptor.getExecutionConsole();
            }
        }

        // 2. 如果不存在，创建新的 ConsoleView
        ConsoleView consoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();

        // 3. 包装成 RunContentDescriptor
        RunContentDescriptor descriptor = new RunContentDescriptor(
                consoleView,
                null,
                consoleView.getComponent(),
                consoleTitle
        );
        // 设置一个 ID 防止混淆（可选）
        descriptor.setExecutionId(System.nanoTime());

        // 4. 显示到 Run 窗口
        contentManager.showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor);

        return consoleView;
    }
    // -----------------------------------------------------

    private String[] findConfig(VirtualFile current) {
        VirtualFile dir = current.getParent();
        while (dir != null) {
            VirtualFile configFile = dir.findChild(CJavaConst.PROJECT_DEVELOP_CONF_FILE);
            if (configFile != null && configFile.exists()) {
                try {
                    File file = new File(configFile.getPath());
                    String content = Files.readString(file.toPath());
                    String uri = extractAttr(content, "Uri");
                    String user = extractAttr(content, "User");
                    String pwd = extractAttr(content, "Password");
                    if (uri != null && user != null) {
                        return new String[]{uri, user, pwd != null ? pwd : ""};
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            dir = dir.getParent();
        }
        return null;
    }

    private String extractAttr(String xml, String attr) {
        Pattern p = Pattern.compile(attr + "=\"([^\"]*)\"");
        Matcher m = p.matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    private void printResult(ConsoleView console, String debugKey, CJavaDebuggerDto dto, boolean isException, Object result, String resultText, List<String> traces) {
        ApplicationManager.getApplication().invokeLater(() -> {
            console.print("\n---------------- EXECUTION FINISHED ----------------\n", ConsoleViewContentType.LOG_INFO_OUTPUT);
            console.print("DebugKey: " + debugKey + "\n", ConsoleViewContentType.NORMAL_OUTPUT);
            console.print("IsException: " + isException + "\n", isException ? ConsoleViewContentType.ERROR_OUTPUT : ConsoleViewContentType.NORMAL_OUTPUT);
            console.print("Result Object: " + result + "\n", ConsoleViewContentType.NORMAL_OUTPUT);
            console.print("Result Text: " + resultText + "\n", ConsoleViewContentType.NORMAL_OUTPUT);

            if (traces != null && !traces.isEmpty()) {
                console.print("\n--- Remote Traces ---\n", ConsoleViewContentType.SYSTEM_OUTPUT);
                for (String line : traces) {
                    if (line.contains("ERROR") || line.contains("Exception")) {
                        console.print(line + "\n", ConsoleViewContentType.ERROR_OUTPUT);
                    } else {
                        console.print(line + "\n", ConsoleViewContentType.NORMAL_OUTPUT);
                    }
                }
            }
            console.print("----------------------------------------------------\n", ConsoleViewContentType.LOG_INFO_OUTPUT);
        });
    }

    private void printError(ConsoleView console, String msg) {
        ApplicationManager.getApplication().invokeLater(() ->
                console.print("\n[ERROR] " + msg + "\n", ConsoleViewContentType.ERROR_OUTPUT));
    }
}