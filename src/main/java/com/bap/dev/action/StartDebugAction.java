package com.bap.dev.action;

import bap.java.CJavaCode;
import bap.java.CJavaConst;
import bap.java.CJavaDebuggerDto;
import com.bap.dev.BapRpcClient;
import com.bap.dev.i18n.BapBundle;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileDocumentManager; // 新增
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager; // 新增
import com.intellij.ui.JBColor;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StartDebugAction extends AnAction {

    private static final String RERUN_TASK_KEY = "BAP_CLOUD_DEBUG_RERUN_TASK";

    // 🔴 修改：INFO 改为蓝色
    private static final ConsoleViewContentType LOG_INFO_TYPE = new ConsoleViewContentType("LOG_INFO",
            new TextAttributes(new JBColor(new Color(0, 102, 204), new Color(73, 156, 228)), null, null, null, Font.BOLD));

    // 🔴 修改：WARN 改为黄色 (浅色背景用暗黄，深色背景用亮黄)
    private static final ConsoleViewContentType LOG_WARN_TYPE = new ConsoleViewContentType("LOG_WARN",
            new TextAttributes(new JBColor(new Color(204, 153, 0), Color.YELLOW), null, null, null, Font.BOLD));

    private static final ConsoleViewContentType LOG_ERROR_TYPE = new ConsoleViewContentType("LOG_ERROR",
            new TextAttributes(new JBColor(Color.RED, new Color(255, 107, 104)), null, null, null, Font.BOLD));

    private static final ConsoleViewContentType LOG_FATAL_TYPE = new ConsoleViewContentType("LOG_FATAL",
            new TextAttributes(new JBColor(Color.MAGENTA, new Color(255, 0, 255)), null, null, null, Font.BOLD));

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(true);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);

        if (project == null || !(psiFile instanceof PsiJavaFile)) return;

        // 使用 VirtualFile，因为它在文件修改后依然有效
        VirtualFile vFile = psiFile.getVirtualFile();
        if (vFile == null) return;

        // 1. 获取连接配置 (配置通常不会变，可以在这里获取一次)
        String[] config = findConfig(vFile);
        if (config == null) {
            Messages.showWarningDialog(
                    BapBundle.message("warning.no_develop_config"),
                    BapBundle.message("notification.error_title")
            );
            return;
        }
        String uri = config[0];
        String user = config[1];
        String pwd = config[2];

        // 2. 获取或创建控制台
        ConsoleView console = getOrCreateConsole(project);

        // 3. 定义调试任务 (闭包中捕获 vFile，而不是写死的 code)
        Runnable debugTask = () -> launchDebug(project, vFile, console, uri, user, pwd);

        // 4. 绑定任务并执行
        console.getComponent().putClientProperty(RERUN_TASK_KEY, debugTask);
        debugTask.run();
    }

    /**
     * 新增方法：负责保存文件、重新解析代码、应用修改逻辑，然后发起调试
     */
    private void launchDebug(Project project, VirtualFile vFile, ConsoleView console, String uri, String user, String pwd) {
        // 1. 强制保存所有文档，确保磁盘上的文件是最新的（或者确保 PSI 获取的是最新的内存状态）
        ApplicationManager.getApplication().runWriteAction(() -> FileDocumentManager.getInstance().saveAllDocuments());

        // 2. 重新解析类信息 (ReadAction)
        String[] classInfo = ReadAction.compute(() -> {
            PsiFile psiFile = PsiManager.getInstance(project).findFile(vFile);
            if (!(psiFile instanceof PsiJavaFile)) return null;
            PsiJavaFile javaFile = (PsiJavaFile) psiFile;

            String pkg = javaFile.getPackageName();
            PsiClass[] classes = javaFile.getClasses();
            if (classes.length == 0) return null;
            String name = classes[0].getName();
            String code = javaFile.getText(); // 获取最新的代码
            return new String[]{pkg, name, code};
        });

        if (classInfo == null) {
            printError(console, BapBundle.message("action.StartDebugAction.error.parse_java")); // "Error: Unable to parse..."
            return;
        }

        String packageName = classInfo[0];
        String className = classInfo[1];
        String fullCode = classInfo[2];

        // 3. 应用 .debug 包名修改逻辑
        String debugPackageName = (packageName == null || packageName.isEmpty())
                ? "debug"
                : packageName + ".debug";

        String modifiedCode;
        if (packageName == null || packageName.isEmpty()) {
            modifiedCode = "package " + debugPackageName + ";\n" + fullCode;
        } else {
            String regex = "package\\s+" + Pattern.quote(packageName) + "\\s*;";
            modifiedCode = fullCode.replaceFirst(regex, "package " + debugPackageName + ";");
        }

        // 4. 执行调试
        executeDebug(project, console, className, debugPackageName, modifiedCode, uri, user, pwd);
    }

    // 🔴 核心修改：使用轮询机制替代 Thread.sleep
    private void executeDebug(Project project, ConsoleView console, String className, String debugPackageName, String code, String uri, String user, String pwd) {
        console.clear();
        console.print(BapBundle.message("action.StartDebugAction.console.preparing", className, debugPackageName), ConsoleViewContentType.SYSTEM_OUTPUT);

        ProgressManager.getInstance().run(new Task.Backgroundable(project, BapBundle.message("action.StartDebugAction.progress.debugging", className), true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                BapRpcClient client = new BapRpcClient();
                String debugKey = null;
                try {
                    indicator.setText(BapBundle.message("progress.connecting"));
                    client.connect(uri, user, pwd);
                    console.print(BapBundle.message("action.StartDebugAction.console.connected", uri), ConsoleViewContentType.SYSTEM_OUTPUT);

                    CJavaCode javaCode = new CJavaCode();
                    javaCode.setJavaPackage(debugPackageName);
                    javaCode.setName(className);
                    javaCode.setMainClass(className);
                    javaCode.setUuid(UUID.randomUUID().toString().replace("-", "_"));
                    javaCode.setCode(code);

                    indicator.setText(BapBundle.message("action.StartDebugAction.progress.executing"));
                    console.print(BapBundle.message("action.StartDebugAction.console.upload_execute"), ConsoleViewContentType.SYSTEM_OUTPUT);

                    debugKey = client.getService().startDebugJava(javaCode, new URI(uri));

                    if (debugKey == null) {
                        printError(console, BapBundle.message("action.StartDebugAction.error.null_key"));
                        return;
                    }

                    // --- 🔴 轮询循环开始 ---
                    while (true) {
                        // 1. 检查用户是否点击取消
                        if (indicator.isCanceled()) {
                            console.print("\n" + BapBundle.message("action.StartDebugAction.console.canceled") + "\n", ConsoleViewContentType.SYSTEM_OUTPUT);
                            try {
                                client.getService().terminateDebug(debugKey, 5000);
                            } catch (Exception ignore) {}
                            break;
                        }

                        // 2. 拉取并打印日志 (Trace)
                        try {
                            // 注意：根据之前的代码，方法名可能是 popTrace 或 popDebugTrace，这里保持原文件中的 popTrace
                            // 如果接口变了，请修改为 client.getService().popDebugTrace(debugKey)
                            List<String> traces = client.getService().popTrace(debugKey);
                            if (traces != null && !traces.isEmpty()) {
                                // 在 UI 线程打印颜色日志
                                ApplicationManager.getApplication().invokeLater(() -> {
                                    for (String line : traces) {
                                        printColoredLog(console, line);
                                    }
                                });
                            }
                        } catch (Exception ignore) {}

                        // 3. 获取当前状态
                        int status = -1;
                        try {
                            status = client.getService().getStatus(debugKey);
                        } catch (Exception e) {
                            // 网络波动忽略，继续重试
                        }

                        // 4. 判断结束状态
                        if (status == CJavaConst.STATUS_ERROR) {
                            handleExecutionResult(client, debugKey, true);
                            break;
                        } else if (status == CJavaConst.STATUS_FINISHED) {
                            handleExecutionResult(client, debugKey, false);
                            break;
                        }

                        // 5. 间隔休眠
                        try {
                            Thread.sleep(300);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                    // --- 🔴 轮询循环结束 ---

                } catch (Exception ex) {
                    printError(console, BapBundle.message("action.StartDebugAction.error.execution_failed", ex.getMessage()));
                    ex.printStackTrace();
                } finally {
                    client.shutdown();
                }
            }

            // 辅助方法：处理最终结果
            private void handleExecutionResult(BapRpcClient client, String debugKey, boolean isError) {
                Object result = null;
                String resultText = "";
                try {
                    result = client.getService().getResult(debugKey); // 或 getDebugResult
                } catch (Exception ignore) {}

                try {
                    resultText = client.getService().getResultText(debugKey); // 或 getDebugResultText
                } catch (Exception ignore) {}

                // 最后再拉一次 Trace 防止遗漏
                List<String> finalTraces = null;
                try { finalTraces = client.getService().popTrace(debugKey); } catch (Exception ignore) {}

                printResult(console, debugKey, null, isError, result, resultText, finalTraces);
            }
        });
    }

    private ConsoleView getOrCreateConsole(Project project) {
        final String consoleTitle = BapBundle.message("action.StartDebugAction.title.cloud_debug"); // "Cloud Debug"
        RunContentManager contentManager = ExecutionManager.getInstance(project).getContentManager();

        for (RunContentDescriptor descriptor : contentManager.getAllDescriptors()) {
            if (consoleTitle.equals(descriptor.getDisplayName()) && descriptor.getExecutionConsole() instanceof ConsoleView) {
                contentManager.toFrontRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor);
                return (ConsoleView) descriptor.getExecutionConsole();
            }
        }

        ConsoleView consoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();
        JComponent consolePanel = consoleView.getComponent(); // Force init

        DefaultActionGroup toolbarActions = new DefaultActionGroup();

        // 1. Rerun Action
        toolbarActions.add(new RerunAction(consoleView));

        // 2. Console default actions
        AnAction[] consoleActions = consoleView.createConsoleActions();
        for (AnAction action : consoleActions) {
            toolbarActions.add(action);
        }

        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.RUNNER_TOOLBAR, toolbarActions, false);
        toolbar.setTargetComponent(consolePanel);

        JPanel uiPanel = new JPanel(new BorderLayout());
        uiPanel.add(consolePanel, BorderLayout.CENTER);
        uiPanel.add(toolbar.getComponent(), BorderLayout.WEST);

        RunContentDescriptor descriptor = new RunContentDescriptor(
                consoleView,
                null,
                uiPanel,
                consoleTitle
        );
        descriptor.setExecutionId(System.nanoTime());

        contentManager.showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor);

        return consoleView;
    }

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
            console.print(BapBundle.message("action.StartDebugAction.console.finish_header"), ConsoleViewContentType.LOG_INFO_OUTPUT);

            console.print(BapBundle.message("action.StartDebugAction.label.debug_key"), ConsoleViewContentType.NORMAL_OUTPUT);
            console.print(debugKey + "\n", ConsoleViewContentType.NORMAL_OUTPUT);

            console.print(BapBundle.message("action.StartDebugAction.label.is_exception"), isException ? ConsoleViewContentType.ERROR_OUTPUT : ConsoleViewContentType.NORMAL_OUTPUT);
            console.print(isException + "\n", isException ? ConsoleViewContentType.ERROR_OUTPUT : ConsoleViewContentType.NORMAL_OUTPUT);

            console.print(BapBundle.message("action.StartDebugAction.label.result_obj"), ConsoleViewContentType.NORMAL_OUTPUT);
            console.print(result + "\n", ConsoleViewContentType.NORMAL_OUTPUT);

            console.print(BapBundle.message("action.StartDebugAction.label.result_text"), ConsoleViewContentType.NORMAL_OUTPUT);
            console.print(resultText + "\n", ConsoleViewContentType.NORMAL_OUTPUT);

            if (traces != null && !traces.isEmpty()) {
                console.print(BapBundle.message("action.StartDebugAction.console.remote_traces"), ConsoleViewContentType.SYSTEM_OUTPUT);
                for (String line : traces) {
                    printColoredLog(console, line);
                }
            }
            // Footer line can reuse header or keep simple separator
            console.print("----------------------------------------------------\n", ConsoleViewContentType.LOG_INFO_OUTPUT);
        });
    }

    private void printColoredLog(ConsoleView console, String line) {
        String tag = null;
        ConsoleViewContentType type = null;

        if (line.contains("[FATAL]")) {
            tag = "[FATAL]";
            type = LOG_FATAL_TYPE;
        } else if (line.contains("[ERROR]")) {
            tag = "[ERROR]";
            type = LOG_ERROR_TYPE;
        } else if (line.contains("[WARN]")) {
            tag = "[WARN]";
            type = LOG_WARN_TYPE;
        } else if (line.contains("[INFO]")) {
            tag = "[INFO]";
            type = LOG_INFO_TYPE;
        }

        if (tag != null) {
            int idx = line.indexOf(tag);
            if (idx > 0) {
                console.print(line.substring(0, idx), ConsoleViewContentType.NORMAL_OUTPUT);
            }
            console.print(tag, type);
            console.print(line.substring(idx + tag.length()) + "\n", ConsoleViewContentType.NORMAL_OUTPUT);
        } else {
            console.print(line + "\n", ConsoleViewContentType.NORMAL_OUTPUT);
        }
    }

    private void printError(ConsoleView console, String msg) {
        ApplicationManager.getApplication().invokeLater(() ->
                console.print("\n[ERROR] " + msg + "\n", ConsoleViewContentType.ERROR_OUTPUT));
    }

    private static class RerunAction extends AnAction implements DumbAware {
        private final ConsoleView consoleView;

        public RerunAction(ConsoleView consoleView) {
            super(
                    BapBundle.message("action.StartDebugAction.action.rerun"),      // "Rerun"
                    BapBundle.message("action.StartDebugAction.action.rerun.desc"), // "Rerun cloud debug"
                    AllIcons.Actions.Restart
            );
            this.consoleView = consoleView;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Object taskObj = consoleView.getComponent().getClientProperty(RERUN_TASK_KEY);
            if (taskObj instanceof Runnable) {
                ((Runnable) taskObj).run();
            }
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            Object taskObj = consoleView.getComponent().getClientProperty(RERUN_TASK_KEY);
            e.getPresentation().setEnabled(taskObj instanceof Runnable);
        }
    }
}