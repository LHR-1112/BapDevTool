package com.bap.dev.action;

import bap.java.CJavaProjectDto;
import com.bap.dev.BapRpcClient;
import com.bap.dev.handler.ProjectDownloader;
import com.bap.dev.i18n.BapBundle;
import com.bap.dev.ui.LogonDialog;
import com.bap.dev.ui.ProjectDownloadDialog;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.application.ApplicationConfiguration;
import com.intellij.execution.application.ApplicationConfigurationType;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.icons.AllIcons; // 导入图标库
import com.intellij.ide.impl.ProjectUtil;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.ActionUpdateThread; // 导入
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAware; // 导入
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.libraries.LibraryTable;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.JarFileSystem;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.model.java.JavaResourceRootType;
import org.jetbrains.jps.model.java.JavaSourceRootType;

import java.io.File;
import java.util.List;

// 1. 实现 DumbAware
public class ProjectDownloadAction extends AnAction implements DumbAware {

    private static final String NOTIFICATION_GROUP_ID = "Cloud Project Download";
    private static final String PREF_URI = "practicalTool.uri";
    private static final String PREF_USER = "practicalTool.user";

    // 使用默认构造函数即可，元数据由 XML 提供
    public ProjectDownloadAction() {
        super();
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }

    // 4. 覆盖 update 确保可用
    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(true);
        // 不要在这里 setIcon；让 plugin.xml 的 icon 生效
        // 也不要无条件 setVisible(true)，除非你有条件判断
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        // ... (保持您原有的逻辑不变，直接粘贴之前的代码即可) ...
        Project project = e.getProject();
        PropertiesComponent props = PropertiesComponent.getInstance();
        String defaultUri = props.getValue(PREF_URI, "ws://127.0.0.1:2020");
        String defaultUser = props.getValue(PREF_USER, "root");

        LogonDialog logonDialog = new LogonDialog(project, defaultUri, defaultUser, "");
        if (!logonDialog.showAndGet()) return;

        String uri = logonDialog.getUri();
        String user = logonDialog.getUser();
        String pwd = logonDialog.getPwd();

        props.setValue(PREF_URI, uri);
        props.setValue(PREF_USER, user);

        BapRpcClient tempClient = new BapRpcClient();

        ProgressManager.getInstance().run(new Task.Modal(project, BapBundle.message("progress.connecting"), true) { // "Connecting..."
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    indicator.setIndeterminate(true);
                    tempClient.connect(uri, user, pwd);
                    indicator.setText(BapBundle.message("action.ProjectDownloadAction.progress.fetching_list"));
                    List<CJavaProjectDto> projects = tempClient.getService().getAllProjects();

                    ApplicationManager.getApplication().invokeLater(() -> {
                        tempClient.shutdown();
                        if (projects == null || projects.isEmpty()) {
                            Messages.showWarningDialog(
                                    BapBundle.message("action.ProjectDownloadAction.warning.no_projects"), // "连接成功..."
                                    BapBundle.message("action.ProjectDownloadAction.warning.no_data")      // "无数据"
                            );
                            return;
                        }

                        ProjectDownloadDialog selectDialog = new ProjectDownloadDialog(project, projects);
                        if (selectDialog.showAndGet()) {
                            String uuid = selectDialog.getSelectedProjectUuid();
                            String projectName = selectDialog.getSelectedProjectName();
                            if (uuid == null) return;

                            String targetRoot = null;
                            boolean isOpenNewWindow = true;

                            if (project == null) {
                                FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor();
                                descriptor.setTitle(BapBundle.message("action.ProjectDownloadAction.chooser.title")); // "选择项目保存位置"
                                descriptor.setDescription(BapBundle.message("action.ProjectDownloadAction.chooser.desc")); // "请选择一个文件夹..."
                                VirtualFile file = FileChooser.chooseFile(descriptor, null, null);
                                if (file == null) return;
                                targetRoot = file.getPath();
                                isOpenNewWindow = true;
                            } else {
                                int choice = Messages.showDialog(project,
                                        BapBundle.message("action.ProjectDownloadAction.dialog.mode_msg"), // "请选择工程下载方式..."
                                        BapBundle.message("action.ProjectDownloadAction.dialog.mode_title"), // "下载选项"
                                        new String[]{
                                                BapBundle.message("action.ProjectDownloadAction.button.add"), // "添加到当前项目"
                                                BapBundle.message("action.ProjectDownloadAction.button.open"), // "作为独立项目打开"
                                                BapBundle.message("button.cancel") // "取消"
                                        },
                                        0,
                                        Messages.getQuestionIcon());

                                if (choice == 2 || choice == -1) return;
                                isOpenNewWindow = (choice == 1);
                                if (isOpenNewWindow) {
                                    FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor();
                                    descriptor.setTitle(BapBundle.message("action.ProjectDownloadAction.chooser.title")); // "选择项目保存位置"
                                    VirtualFile file = FileChooser.chooseFile(descriptor, project, null);
                                    if (file == null) return;
                                    targetRoot = file.getPath();
                                } else {
                                    targetRoot = project.getBasePath();
                                }
                            }

                            if (targetRoot != null) {
                                startDownloadTask(project, uri, user, pwd, uuid, projectName, targetRoot, isOpenNewWindow);
                            }
                        }
                    });

                } catch (Exception ex) {
                    tempClient.shutdown();
                    ApplicationManager.getApplication().invokeLater(() ->
                            // 修改7: Error Dialog
                            Messages.showErrorDialog(BapBundle.message("action.ProjectDownloadAction.error.connect_prefix", ex.getMessage()), // "连接失败: " + ex.getMessage()
                                    BapBundle.message("notification.error_title"))); // "错误"
                }
            }
        });
    }

    // ... (请务必保留 startDownloadTask, configureModuleStructure, createRunConfiguration, sendNotification 等所有辅助方法) ...
    private void startDownloadTask(Project currentProject, String uri, String user, String pwd, String uuid, String projectName, String targetRoot, boolean isOpenNewWindow) {
        ProgressManager.getInstance().run(new Task.Backgroundable(currentProject, BapBundle.message("action.ProjectDownloadAction.progress.download_prefix", projectName), true) { // "正在下载模块 " + projectName + "..."
            private final ProjectDownloader downloader = new ProjectDownloader();
            private boolean isSuccess = false;
            private final File moduleDir = new File(targetRoot, projectName);

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    indicator.setText(BapBundle.message("action.ProjectDownloadAction.progress.connect_server")); // "正在连接服务器..."
                    downloader.connect(uri, user, pwd);
                    indicator.setText(BapBundle.message("action.ProjectDownloadAction.progress.download_create")); // "正在下载并创建模块..."
                    downloader.downloadProject(uuid, projectName, targetRoot, null, indicator);
                    isSuccess = true;
                    VirtualFile newModuleDirVFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(moduleDir);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (newModuleDirVFile == null) {
                            // 修改10: Notification
                            sendNotification(currentProject,
                                    BapBundle.message("action.ProjectDownloadAction.notification.refresh_fail"), // "刷新失败"
                                    BapBundle.message("action.ProjectDownloadAction.notification.dir_not_found"), // "无法找到新下载的目录"
                                    NotificationType.ERROR);
                            return;
                        }
                        newModuleDirVFile.refresh(true, true, () -> {
                            ApplicationManager.getApplication().invokeLater(() -> {
                                if (isOpenNewWindow) {
                                    Project newProject = ProjectUtil.openOrImport(moduleDir.getPath(), currentProject, true);
                                    if (newProject != null) {
                                        configureModuleStructure(newProject, newModuleDirVFile, moduleDir, projectName);
                                    } else {
                                        // 修改11: Error Dialog
                                        Messages.showErrorDialog(BapBundle.message("action.ProjectDownloadAction.error.open_fail_prefix", moduleDir.getPath()), // "无法打开新项目: "
                                                BapBundle.message("notification.error_title")); // "错误"
                                    }
                                } else {
                                    configureModuleStructure(currentProject, newModuleDirVFile, moduleDir, projectName);
                                }
                            });
                        });
                    });
                } catch (InterruptedException | RuntimeException cancelEx) {
                    String msg = cancelEx.getMessage();
                    if ("USER_CANCEL_DOWNLOAD".equals(msg) || cancelEx instanceof InterruptedException) {
                        ApplicationManager.getApplication().invokeLater(() ->
                                // 修改12: Notification (Cancelled)
                                sendNotification(currentProject,
                                        BapBundle.message("action.ProjectDownloadAction.notification.cancel_title"), // "下载已取消"
                                        BapBundle.message("action.ProjectDownloadAction.notification.cleaning"), // "正在清理临时文件..."
                                        NotificationType.INFORMATION));
                    } else {
                        cancelEx.printStackTrace();
                        ApplicationManager.getApplication().invokeLater(() ->
                                // 修改13: Error Dialog
                                Messages.showErrorDialog(BapBundle.message("action.ProjectDownloadAction.error.download_prefix", msg), // "下载出错: " + msg
                                        BapBundle.message("notification.error_title"))); // "错误"
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                    ApplicationManager.getApplication().invokeLater(() ->
                            // 修改14: Error Dialog
                            Messages.showErrorDialog(BapBundle.message("action.ProjectDownloadAction.error.download_prefix", ex.getMessage()), // "下载出错: " + ex.getMessage()
                                    BapBundle.message("notification.error_title"))); // "错误"
                } finally {
                    downloader.shutdown();
                    if (!isSuccess) {
                        cleanupFailedDownload(indicator);
                    }
                }
            }

            private void cleanupFailedDownload(ProgressIndicator indicator) {
                if (moduleDir.exists()) {
                    try {
                        // 修改15: Indicator text
                        indicator.setText(BapBundle.message("action.ProjectDownloadAction.progress.cleaning_residual")); // "正在清理残余文件..."
                        FileUtil.delete(moduleDir);
                    } catch (Exception e) {
                        System.err.println("Failed to clean up directory: " + moduleDir.getAbsolutePath());
                    }
                }
            }
        });
    }

    private void configureModuleStructure(Project project, VirtualFile newModuleDirVFile, File newModuleDirIo, String projectName) {
        try {
            WriteAction.run(() -> {
                if (project.isDisposed()) return;
                if (!newModuleDirVFile.exists()) throw new RuntimeException(BapBundle.message("action.ProjectDownloadAction.error.module_dir_missing")); // "无法找到模块目录"
                ModuleManager moduleManager = ModuleManager.getInstance(project);
                Module newModule = moduleManager.findModuleByName(projectName);
                if (newModule == null) {
                    String imlPath = new File(newModuleDirIo, projectName + ".iml").getAbsolutePath();
                    newModule = moduleManager.newModule(imlPath, "JAVA_MODULE");
                }
                ModuleRootManager rootManager = ModuleRootManager.getInstance(newModule);
                ModifiableRootModel model = rootManager.getModifiableModel();
                try {
                    for (ContentEntry entry : model.getContentEntries()) model.removeContentEntry(entry);
                    ContentEntry contentEntry = model.addContentEntry(newModuleDirVFile);
                    addSourceFolderIfExist(contentEntry, newModuleDirVFile, "src/core", false);
                    addSourceFolderIfExist(contentEntry, newModuleDirVFile, "src/src", false);
                    addSourceFolderIfExist(contentEntry, newModuleDirVFile, "src/res", true);
                    addExcludeFolderIfExist(contentEntry, newModuleDirVFile, "lib");
                    addExcludeFolderIfExist(contentEntry, newModuleDirVFile, "openSource");
                    addFolderJarsToLibrary(model, newModuleDirVFile, "lib/platform");
                    addFolderJarsToLibrary(model, newModuleDirVFile, "lib/plugin");
                    addFolderJarsToLibrary(model, newModuleDirVFile, "lib/project");
                    CompilerModuleExtension extension = model.getModuleExtension(CompilerModuleExtension.class);
                    if (extension != null) {
                        extension.inheritCompilerOutputPath(false);
                        extension.setExcludeOutput(true);
                        File binDir = new File(newModuleDirIo, "bin");
                        if (!binDir.exists()) binDir.mkdirs();
                        VirtualFile binVFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(binDir);
                        File testDir = new File(newModuleDirIo, "test");
                        if (!testDir.exists()) testDir.mkdirs();
                        VirtualFile testVFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(testDir);
                        if (binVFile != null) extension.setCompilerOutputPath(binVFile);
                        if (testVFile != null) extension.setCompilerOutputPathForTests(testVFile);
                    }
                    Sdk projectSdk = ProjectRootManager.getInstance(project).getProjectSdk();
                    if (projectSdk != null) model.setSdk(projectSdk);
                    else model.inheritSdk();
                    model.commit();

                    Module finalModule = newModule;
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (!project.isDisposed())
                            createRunConfiguration(project, finalModule, projectName, newModuleDirIo.getAbsolutePath());
                    });
                    sendNotification(project,
                            BapBundle.message("action.ProjectDownloadAction.notification.success_title"), // "下载并配置成功"
                            BapBundle.message("action.ProjectDownloadAction.notification.success_content", projectName), // "模块 <b>" + projectName + "</b> 已就绪。"
                            NotificationType.INFORMATION);
                } catch (Exception e) {
                    if (!model.isDisposed()) model.dispose();
                    throw e;
                }
            });
        } catch (Exception err) {
            err.printStackTrace();
            sendNotification(project,
                    BapBundle.message("action.ProjectDownloadAction.notification.config_fail"), // "配置模块失败"
                    BapBundle.message("action.ProjectDownloadAction.error.generic_prefix", err.getMessage()), // "出错：" + err.getMessage()
                    NotificationType.WARNING);
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
            if (isResource) entry.addSourceFolder(dir, JavaResourceRootType.RESOURCE);
            else entry.addSourceFolder(dir, JavaSourceRootType.SOURCE);
        }
    }

    private void addExcludeFolderIfExist(ContentEntry entry, VirtualFile root, String relativePath) {
        VirtualFile dir = root.findFileByRelativePath(relativePath);
        if (dir != null && dir.exists()) entry.addExcludeFolder(dir);
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
                if (jarRoot != null) libModel.addRoot(jarRoot, OrderRootType.CLASSES);
            }
        }
        libModel.commit();
    }

    private void sendNotification(Project project, String title, String content, NotificationType type) {
        Notification notification = new Notification(NOTIFICATION_GROUP_ID, title, content, type);
        Notifications.Bus.notify(notification, project);
    }
}