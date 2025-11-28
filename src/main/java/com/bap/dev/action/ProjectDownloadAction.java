package com.bap.dev.action;

import bap.java.CJavaProjectDto;
import com.bap.dev.BapRpcClient;
import com.bap.dev.handler.ProjectDownloader;
import com.bap.dev.ui.LogonDialog;
import com.bap.dev.ui.ProjectDownloadDialog;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.application.ApplicationConfiguration;
import com.intellij.execution.application.ApplicationConfigurationType;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.libraries.LibraryTable;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.JarFileSystem;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.model.java.JavaResourceRootType;
import org.jetbrains.jps.model.java.JavaSourceRootType;

import java.io.File;
import java.util.List;

public class ProjectDownloadAction extends AnAction {

    private static final String NOTIFICATION_GROUP_ID = "Cloud Project Download";

    // 历史记录 Key
    private static final String PREF_URI = "practicalTool.uri";
    private static final String PREF_USER = "practicalTool.user";

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();

        if (project == null || project.getBasePath() == null) {
            Messages.showErrorDialog("请先打开一个项目，插件将作为模块下载到当前项目根目录下。", "无法确定目标路径");
            return;
        }

        // 1. 获取历史配置
        PropertiesComponent props = PropertiesComponent.getInstance();
        String defaultUri = props.getValue(PREF_URI, "ws://183.6.70.7:24620");
        String defaultUser = props.getValue(PREF_USER, "root");

        // 2. 弹出登录框 (Step 1)
        LogonDialog logonDialog = new LogonDialog(project, defaultUri, defaultUser, "");
        if (!logonDialog.showAndGet()) {
            return; // 用户取消
        }

        String uri = logonDialog.getUri();
        String user = logonDialog.getUser();
        String pwd = logonDialog.getPwd();

        // 保存常用配置
        props.setValue(PREF_URI, uri);
        props.setValue(PREF_USER, user);

        // 3. 后台连接并获取列表 (Step 2)
        BapRpcClient tempClient = new BapRpcClient();

        ProgressManager.getInstance().run(new Task.Modal(project, "Connecting...", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    indicator.setIndeterminate(true);
                    tempClient.connect(uri, user, pwd);

                    indicator.setText("Fetching project list...");
                    List<CJavaProjectDto> projects = tempClient.getService().getAllProjects();

                    // 4. UI 线程弹出工程选择框 (Step 3)
                    ApplicationManager.getApplication().invokeLater(() -> {
                        // 用完临时连接即关闭，下载时会由 Downloader 重新建立连接
                        tempClient.shutdown();

                        if (projects == null || projects.isEmpty()) {
                            Messages.showWarningDialog("连接成功，但服务端没有返回任何工程。", "无数据");
                            return;
                        }

                        ProjectDownloadDialog selectDialog = new ProjectDownloadDialog(project, projects);
                        if (selectDialog.showAndGet()) {
                            String uuid = selectDialog.getSelectedProjectUuid();
                            String projectName = selectDialog.getSelectedProjectName();
                            String currentProjectRoot = project.getBasePath();

                            if (uuid == null) return;

                            // 5. 启动下载任务 (Step 4)
                            startDownloadTask(project, uri, user, pwd, uuid, projectName, currentProjectRoot);
                        }
                    });

                } catch (Exception ex) {
                    tempClient.shutdown();
                    ApplicationManager.getApplication().invokeLater(() ->
                            Messages.showErrorDialog("连接失败: " + ex.getMessage(), "错误"));
                }
            }
        });
    }

    /**
     * 执行下载任务（复用之前的逻辑）
     */
    private void startDownloadTask(Project project, String uri, String user, String pwd, String uuid, String projectName, String currentProjectRoot) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "正在下载模块 " + projectName + "...", true) {
            private final ProjectDownloader downloader = new ProjectDownloader();

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    indicator.setText("正在连接服务器...");
                    downloader.connect(uri, user, pwd);

                    indicator.setText("正在下载并创建模块...");
                    downloader.downloadProject(uuid, projectName, currentProjectRoot, null, indicator::isCanceled);

                    File newModuleDirIo = new File(currentProjectRoot, projectName);

                    // 移出 UI 线程的 IO 操作
                    VirtualFile newModuleDirVFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(newModuleDirIo);

                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (newModuleDirVFile == null) {
                            sendNotification(project, "刷新失败", "无法找到新下载的目录", NotificationType.ERROR);
                            return;
                        }

                        // 异步递归刷新
                        newModuleDirVFile.refresh(true, true, () -> {
                            configureModuleStructure(project, newModuleDirVFile, newModuleDirIo, projectName);
                        });
                    });

                } catch (InterruptedException cancelEx) {
                    ApplicationManager.getApplication().invokeLater(() -> sendNotification(project, "下载已取消", "", NotificationType.INFORMATION));
                } catch (Exception ex) {
                    ex.printStackTrace();
                    ApplicationManager.getApplication().invokeLater(() -> Messages.showErrorDialog("下载出错: " + ex.getMessage(), "错误"));
                } finally {
                    downloader.shutdown();
                }
            }

            @Override
            public void onCancel() {
                super.onCancel();
                downloader.shutdown();
            }
        });
    }

    private void configureModuleStructure(Project project, VirtualFile newModuleDirVFile, File newModuleDirIo, String projectName) {
        try {
            WriteAction.run(() -> {
                if (project.isDisposed()) return;
                if (!newModuleDirVFile.exists()) {
                    throw new RuntimeException("无法找到模块目录: " + newModuleDirIo.getAbsolutePath());
                }

                String imlPath = new File(newModuleDirIo, projectName + ".iml").getAbsolutePath();
                ModuleManager moduleManager = ModuleManager.getInstance(project);
                Module newModule = moduleManager.newModule(imlPath, "JAVA_MODULE");

                ModuleRootManager rootManager = ModuleRootManager.getInstance(newModule);
                ModifiableRootModel model = rootManager.getModifiableModel();

                try {
                    for (ContentEntry entry : model.getContentEntries()) {
                        model.removeContentEntry(entry);
                    }

                    ContentEntry contentEntry = model.addContentEntry(newModuleDirVFile);

                    addSourceFolderIfExist(contentEntry, newModuleDirVFile, "src/core", false);
                    addSourceFolderIfExist(contentEntry, newModuleDirVFile, "src/src", false);
                    addSourceFolderIfExist(contentEntry, newModuleDirVFile, "src/res", true);

                    addExcludeFolderIfExist(contentEntry, newModuleDirVFile, "lib");
                    addExcludeFolderIfExist(contentEntry, newModuleDirVFile, "openSource");

                    addFolderJarsToLibrary(model, newModuleDirVFile, "lib/platform");
                    addFolderJarsToLibrary(model, newModuleDirVFile, "lib/plugin");
                    addFolderJarsToLibrary(model, newModuleDirVFile, "lib/project");

                    // --- 🔴 新增：配置编译输出路径 ---
                    CompilerModuleExtension extension = model.getModuleExtension(CompilerModuleExtension.class);
                    if (extension != null) {
                        // 1. 勾选“使用模块编译输出路径” (不继承项目路径)
                        extension.inheritCompilerOutputPath(false);

                        // 2. 勾选“排除输出目录”
                        extension.setExcludeOutput(true);

                        // 3. 设置输出目录为 "模块目录/bin"
                        File binDir = new File(newModuleDirIo, "bin");
                        if (!binDir.exists()) {
                            binDir.mkdirs(); // 如果 bin 不存在则创建
                        }
                        // 刷新并获取 bin 的 VirtualFile
                        VirtualFile binVFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(binDir);

                        // 4. 设置测试输出目录为 "模块目录/test"
                        File testDir = new File(newModuleDirIo, "test"); // 新增：测试输出目录
                        if (!testDir.exists()) {
                            testDir.mkdirs(); // 新增：如果 test 不存在则创建
                        }
                        // 刷新并获取 test 的 VirtualFile
                        VirtualFile testVFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(testDir); // 新增

                        if (binVFile != null) {
                            extension.setCompilerOutputPath(binVFile);
                        }

                        if (testVFile != null) {
                            extension.setCompilerOutputPathForTests(testVFile); // 🔴 修改：测试输出设为 test
                        }
                    }
                    // ------------------------------

                    Sdk projectSdk = ProjectRootManager.getInstance(project).getProjectSdk();
                    if (projectSdk != null) {
                        model.setSdk(projectSdk);
                    } else {
                        model.inheritSdk();
                    }
                    model.commit();

                    createRunConfiguration(project, newModule, projectName, newModuleDirIo.getAbsolutePath());

                    sendNotification(project, "下载并配置成功",
                            "模块 <b>" + projectName + "</b> 已创建。<br/>" +
                                    "编译路径已设为 bin，依赖库已加载。",
                            NotificationType.INFORMATION);

                } catch (Exception e) {
                    if (!model.isDisposed()) model.dispose();
                    throw e;
                }
            });
        } catch (Exception err) {
            err.printStackTrace();
            sendNotification(project, "配置模块失败", "出错：" + err.getMessage(), NotificationType.WARNING);
        }
    }

    private void createRunConfiguration(Project project, Module module, String name, String workingDir) {
        RunManager runManager = RunManager.getInstance(project);
        ApplicationConfigurationType type = ConfigurationTypeUtil.findConfigurationType(ApplicationConfigurationType.class);
        ConfigurationFactory factory = type.getConfigurationFactories()[0];
        RunnerAndConfigurationSettings settings = runManager.createConfiguration(name, factory);
        ApplicationConfiguration appConfig = (ApplicationConfiguration) settings.getConfiguration();
        appConfig.setMainClassName("bap.debug.BapDebugger");
        appConfig.setModule(module);
        appConfig.setWorkingDirectory(workingDir);
        runManager.addConfiguration(settings);
        runManager.setSelectedConfiguration(settings);
    }

    private void addSourceFolderIfExist(ContentEntry entry, VirtualFile root, String relativePath, boolean isResource) {
        VirtualFile dir = root.findFileByRelativePath(relativePath);
        if (dir != null && dir.exists()) {
            if (isResource) {
                entry.addSourceFolder(dir, JavaResourceRootType.RESOURCE);
            } else {
                entry.addSourceFolder(dir, JavaSourceRootType.SOURCE);
            }
        }
    }

    private void addExcludeFolderIfExist(ContentEntry entry, VirtualFile root, String relativePath) {
        VirtualFile dir = root.findFileByRelativePath(relativePath);
        if (dir != null && dir.exists()) {
            entry.addExcludeFolder(dir);
        }
    }

    private void addFolderJarsToLibrary(ModifiableRootModel model, VirtualFile root, String relativePath) {
        VirtualFile libDir = root.findFileByRelativePath(relativePath);
        if (libDir == null || !libDir.exists()) return;
        VirtualFile[] children = libDir.getChildren();
        if (children == null || children.length == 0) return;
        LibraryTable libraryTable = model.getModuleLibraryTable();
        Library library = libraryTable.createLibrary("Lib: " + relativePath);
        Library.ModifiableModel libModel = library.getModifiableModel();
        for (VirtualFile file : children) {
            if (!file.isDirectory() && "jar".equalsIgnoreCase(file.getExtension())) {
                String jarPath = file.getPath() + "!/";
                VirtualFile jarRoot = JarFileSystem.getInstance().refreshAndFindFileByPath(jarPath);
                if (jarRoot != null) {
                    libModel.addRoot(jarRoot, OrderRootType.CLASSES);
                }
            }
        }
        libModel.commit();
    }

    private void sendNotification(Project project, String title, String content, NotificationType type) {
        Notification notification = new Notification(NOTIFICATION_GROUP_ID, title, content, type);
        Notifications.Bus.notify(notification, project);
    }
}