package com.bap.dev.action;

import bap.java.CJavaCode;
import bap.java.CJavaConst;
import bap.java.CJavaDebuggerDto;
import com.bap.dev.BapRpcClient;
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

    // 自定义日志颜色类型
    private static final ConsoleViewContentType LOG_INFO_TYPE = new ConsoleViewContentType("LOG_INFO",
            new TextAttributes(new JBColor(new Color(0, 180, 0), new Color(98, 151, 85)), null, null, null, Font.BOLD));
    private static final ConsoleViewContentType LOG_WARN_TYPE = new ConsoleViewContentType("LOG_WARN",
            new TextAttributes(new JBColor(new Color(180, 100, 0), new Color(204, 120, 50)), null, null, null, Font.BOLD));
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
            Messages.showWarningDialog("在当前模块路径下未找到 .develop 配置文件。", "配置丢失");
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
            printError(console, "Error: Unable to parse Java file. Please check the file syntax.");
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

    private void executeDebug(Project project, ConsoleView console, String className, String debugPackageName, String code, String uri, String user, String pwd) {
        console.clear();
        console.print("Preparing to debug class [" + className + "] in package [" + debugPackageName + "]...\n", ConsoleViewContentType.SYSTEM_OUTPUT);

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
                    javaCode.setCode(code);

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

    private ConsoleView getOrCreateConsole(Project project) {
        final String consoleTitle = "Cloud Debug";
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
            console.print("\n---------------- EXECUTION FINISHED ----------------\n", ConsoleViewContentType.LOG_INFO_OUTPUT);
            console.print("DebugKey: " + debugKey + "\n", ConsoleViewContentType.NORMAL_OUTPUT);
            console.print("IsException: " + isException + "\n", isException ? ConsoleViewContentType.ERROR_OUTPUT : ConsoleViewContentType.NORMAL_OUTPUT);
            console.print("Result Object: " + result + "\n", ConsoleViewContentType.NORMAL_OUTPUT);
            console.print("Result Text: " + resultText + "\n", ConsoleViewContentType.NORMAL_OUTPUT);

            if (traces != null && !traces.isEmpty()) {
                console.print("\n--- Remote Traces ---\n", ConsoleViewContentType.SYSTEM_OUTPUT);
                for (String line : traces) {
                    printColoredLog(console, line);
                }
            }
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
            super("Rerun", "Rerun cloud debug", AllIcons.Actions.Restart);
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