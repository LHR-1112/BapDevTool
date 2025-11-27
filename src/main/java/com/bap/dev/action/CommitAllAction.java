package com.bap.dev.action;

import bap.java.CJavaCode;
import bap.java.CJavaConst;
import bap.java.CJavaFolderDto;
import bap.md.java.CJavaProject;
import bap.md.java.CResFileDo;
import com.bap.dev.BapRpcClient;
import com.bap.dev.handler.ProjectRefresher;
import com.bap.dev.listener.BapChangesNotifier;
import com.bap.dev.service.BapFileStatus;
import com.bap.dev.service.BapFileStatusService;
import com.cdao.impl.entity.field.GID;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.kwaidoo.ms.tool.CmnUtil;
import cplugin.ms.dto.CResFileDto;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CommitAllAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile selectedFile = e.getData(CommonDataKeys.VIRTUAL_FILE);

        if (project == null || selectedFile == null) return;

        VirtualFile moduleRoot = findModuleRoot(selectedFile);
        if (moduleRoot == null) {
            Messages.showWarningDialog("未找到 .develop 配置文件。", "错误");
            return;
        }

        // 1. 启动后台任务
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Preparing Commit...", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    indicator.setIndeterminate(true);

                    // A. 强制刷新文件状态
                    indicator.setText("Refreshing module status...");
                    ProjectRefresher refresher = new ProjectRefresher(project);
                    refresher.refreshModule(moduleRoot);

                    // B. 收集所有变动文件
                    indicator.setText("Collecting changes...");
                    List<VirtualFile> changedFiles = collectChangedFiles(project, moduleRoot);

                    if (changedFiles.isEmpty()) {
                        showInfo("没有检测到需要提交的文件 (M/A/D)。");
                        return;
                    }

                    // C. 弹出确认框
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (showConfirmDialog(project, changedFiles)) {
                            startBatchCommit(project, moduleRoot, changedFiles);
                        }
                    });

                } catch (Exception ex) {
                    ex.printStackTrace();
                    showError("准备提交失败: " + ex.getMessage());
                }
            }
        });
    }

    private void startBatchCommit(Project project, VirtualFile moduleRoot, List<VirtualFile> files) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Committing Files...", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                BapRpcClient client = new BapRpcClient();
                int successCount = 0;
                int failCount = 0;
                try {
                    File confFile = new File(moduleRoot.getPath(), CJavaConst.PROJECT_DEVELOP_CONF_FILE);
                    String content = Files.readString(confFile.toPath());
                    String uri = extractAttr(content, "Uri");
                    String user = extractAttr(content, "User");
                    String pwd = extractAttr(content, "Password");
                    String projectUuid = extractAttr(content, "Project");

                    indicator.setText("Connecting...");
                    client.connect(uri, user, pwd);

                    // --- 🔴 优化：一次性获取文件夹列表 ---
                    List<CJavaFolderDto> folders = client.getService().getFolders(projectUuid);
                    // --------------------------------

                    for (int i = 0; i < files.size(); i++) {
                        VirtualFile file = files.get(i);
                        if (indicator.isCanceled()) break;
                        indicator.setFraction((double) (i + 1) / files.size());
                        indicator.setText("Processing " + file.getName() + "...");
                        try {
                            // 将 folders 传给处理方法
                            if (isResourceFile(moduleRoot, file)) {
                                processSingleResource(project, client, projectUuid, moduleRoot, file, folders);
                            } else {
                                processSingleJavaFile(project, client, projectUuid, file, moduleRoot, folders);
                            }
                            successCount++;
                        } catch (Exception ex) {
                            ex.printStackTrace();
                            failCount++;
                            System.err.println("Failed to commit " + file.getName() + ": " + ex.getMessage());
                        }
                    }
                    String msg = String.format("提交完成。\n成功: %d\n失败: %d", successCount, failCount);
                    NotificationType type = failCount > 0 ? NotificationType.WARNING : NotificationType.INFORMATION;
                    sendNotification(project, "Commit All Result", msg, type);
                } catch (Exception ex) {
                    showError("批量提交中断: " + ex.getMessage());
                } finally {
                    client.shutdown();
                    ApplicationManager.getApplication().invokeLater(() -> {
                        new ProjectRefresher(project).refreshModule(moduleRoot);
                        project.getMessageBus().syncPublisher(BapChangesNotifier.TOPIC).onChangesUpdated();
                    });
                }
            }
        });
    }

    // --- 资源文件处理 ---
    private void processSingleResource(Project project, BapRpcClient client, String projectUuid, VirtualFile moduleRoot, VirtualFile file, List<CJavaFolderDto> folders) throws Exception {
        BapFileStatus status = BapFileStatusService.getInstance(project).getStatus(file);
        String relativePath = getResourceRelativePath(moduleRoot, file);
        if (relativePath == null) throw new Exception("无法计算资源路径");

        if (status == BapFileStatus.DELETED_LOCALLY) {
            CResFileDto existingDto = client.getService().getResFile(projectUuid, relativePath, false);
            if (existingDto != null) {
                client.getService().deleteResFile(new GID("bap.md.java.CResFileDo", existingDto.getUuid()));
            }
            ApplicationManager.getApplication().invokeLater(() -> {
                try { WriteAction.run(() -> file.delete(this)); } catch (Exception ignore) {}
            });
            return;
        }

        // 使用传入的 folders 列表查找 res
        CJavaFolderDto resFolder = folders.stream()
                .filter(item -> "res".equals(item.getName()))
                .findFirst()
                .orElse(null);
        if (resFolder == null) throw new Exception("云端 res 文件夹不存在");

        CResFileDto existingDto = client.getService().getResFile(projectUuid, relativePath, false);
        if (existingDto != null) {
            client.getService().deleteResFile(new GID("bap.md.java.CResFileDo", existingDto.getUuid()));
        }

        byte[] content = file.contentsToByteArray();
        CResFileDto uploadDto = new CResFileDto();
        String fileName = file.getName();
        String filePackage = "";
        int lastSlash = relativePath.lastIndexOf('/');
        if (lastSlash >= 0) {
            filePackage = relativePath.substring(0, lastSlash).replace('/', '.');
        }
        uploadDto.setFileName(fileName);
        uploadDto.setFilePackage(filePackage);
        uploadDto.setOwner(resFolder.getUuid());
        uploadDto.setFileBin(content);
        uploadDto.setSize((long) content.length);

        client.getService().importResFile(new GID("bap.md.java.CJavaProject", projectUuid), uploadDto);
        BapFileStatusService.getInstance(project).setStatus(file, BapFileStatus.NORMAL);
    }

    // --- Java 文件处理 ---
    private void processSingleJavaFile(Project project, BapRpcClient client, String projectUuid, VirtualFile file, VirtualFile moduleRoot, List<CJavaFolderDto> folders) throws Exception {
        BapFileStatus status = BapFileStatusService.getInstance(project).getStatus(file);
        String fullClassName = resolveClassName(project, file);
        if (fullClassName == null) throw new Exception("无法解析类名");

        if (status == BapFileStatus.DELETED_LOCALLY) {
            client.getService().deleteCode(projectUuid, fullClassName, true);
            ApplicationManager.getApplication().invokeLater(() -> {
                try { WriteAction.run(() -> file.delete(this)); } catch (Exception ignore) {}
            });
            return;
        }

        String localContent = new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
        Object remoteObj = client.getService().getJavaCode(projectUuid, fullClassName);
        CJavaCode cJavaCode;

        if (remoteObj != null) {
            if (remoteObj instanceof CJavaCode) {
                cJavaCode = (CJavaCode) remoteObj;
            } else {
                cJavaCode = new CJavaCode();
                try {
                    java.lang.reflect.Field fUuid = remoteObj.getClass().getField("uuid");
                    cJavaCode.setUuid((String) fUuid.get(remoteObj));
                } catch (Exception ignore) {}
            }
        } else {
            cJavaCode = new CJavaCode();
            cJavaCode.setProjectUuid(projectUuid);
            cJavaCode.setUuid(CmnUtil.allocUUIDWithUnderline());

            cJavaCode.setMainClass(file.getNameWithoutExtension());
            int lastDot = fullClassName.lastIndexOf('.');
            cJavaCode.setJavaPackage((lastDot > 0) ? fullClassName.substring(0, lastDot) : "");

            // --- 🔴 核心修复：计算并设置 Owner ---
            String ownerUuid = findOwnerUuid(moduleRoot, file, folders);
            if (ownerUuid == null) throw new Exception("无法确定 Owner 文件夹");
            cJavaCode.setOwner(ownerUuid);
            // ------------------------------
        }

        cJavaCode.setCode(localContent);
        client.getService().saveJavaCode(cJavaCode, true);
        BapFileStatusService.getInstance(project).setStatus(file, BapFileStatus.NORMAL);
    }

    // --- 查找 Owner 辅助方法 ---
    private String findOwnerUuid(VirtualFile moduleRoot, VirtualFile file, List<CJavaFolderDto> folders) {
        VirtualFile srcDir = moduleRoot.findChild("src");
        if (srcDir == null) return null;
        String path = VfsUtilCore.getRelativePath(file, srcDir); // core/com/pkg/A.java
        if (path == null) return null;
        int idx = path.indexOf('/');
        if (idx <= 0) return null;
        String folderName = path.substring(0, idx); // core

        return folders.stream()
                .filter(f -> f.getName().equals(folderName))
                .map(CJavaFolderDto::getUuid)
                .findFirst()
                .orElse(null);
    }

    // --- 辅助方法 (保持不变) ---
    private boolean isResourceFile(VirtualFile moduleRoot, VirtualFile file) {
        VirtualFile resDir = moduleRoot.findFileByRelativePath("src/res");
        return resDir != null && VfsUtilCore.isAncestor(resDir, file, true);
    }

    private String getResourceRelativePath(VirtualFile moduleRoot, VirtualFile file) {
        VirtualFile resDir = moduleRoot.findFileByRelativePath("src/res");
        return resDir != null ? VfsUtilCore.getRelativePath(file, resDir) : null;
    }

    private List<VirtualFile> collectChangedFiles(Project project, VirtualFile moduleRoot) {
        BapFileStatusService statusService = BapFileStatusService.getInstance(project);
        List<VirtualFile> result = new ArrayList<>();
        Map<String, BapFileStatus> allStatuses = statusService.getAllStatuses();

        for (Map.Entry<String, BapFileStatus> entry : allStatuses.entrySet()) {
            String path = entry.getKey();
            BapFileStatus status = entry.getValue();
            if (status == BapFileStatus.NORMAL) continue;
            if (path.startsWith(moduleRoot.getPath())) {
                VirtualFile file = LocalFileSystem.getInstance().findFileByPath(path);
                if (file != null) result.add(file);
            }
        }
        return result;
    }

    private boolean showConfirmDialog(Project project, List<VirtualFile> files) {
        StringBuilder sb = new StringBuilder("以下文件将被提交到云端:\n\n");
        int count = 0;
        for (VirtualFile f : files) {
            BapFileStatus status = BapFileStatusService.getInstance(project).getStatus(f);
            String symbol = "[?]";
            if (status == BapFileStatus.MODIFIED) symbol = "[M]";
            if (status == BapFileStatus.ADDED)    symbol = "[A]";
            if (status == BapFileStatus.DELETED_LOCALLY) symbol = "[D]";

            sb.append(symbol).append(" ").append(f.getName()).append("\n");
            if (++count > 15) {
                sb.append("... 等 ").append(files.size()).append(" 个文件");
                break;
            }
        }
        return Messages.showOkCancelDialog(project, sb.toString(), "Confirm Commit All", "Commit", "Cancel", Messages.getQuestionIcon()) == Messages.OK;
    }

    private String resolveClassName(Project project, VirtualFile file) {
        return ReadAction.compute(() -> {
            if (file.getLength() > 0) {
                PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
                if (psiFile instanceof PsiJavaFile) {
                    PsiJavaFile javaFile = (PsiJavaFile) psiFile;
                    String pkg = javaFile.getPackageName();
                    String cls = file.getNameWithoutExtension();
                    return pkg.isEmpty() ? cls : pkg + "." + cls;
                }
            }
            VirtualFile parent = file.getParent();
            VirtualFile srcDir = null;
            while (parent != null) {
                if ("src".equals(parent.getName())) { srcDir = parent; break; }
                parent = parent.getParent();
            }
            if (srcDir != null) {
                String path = VfsUtilCore.getRelativePath(file, srcDir);
                if (path != null) {
                    int slash = path.indexOf('/');
                    if (slash > 0) {
                        String pkgPath = path.substring(slash + 1);
                        if (pkgPath.endsWith(".java")) pkgPath = pkgPath.substring(0, pkgPath.length() - 5);
                        return pkgPath.replace('/', '.');
                    }
                }
            }
            return null;
        });
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

    private void showError(String msg) {
        ApplicationManager.getApplication().invokeLater(() -> Messages.showErrorDialog(msg, "Commit Error"));
    }

    private void showInfo(String msg) {
        ApplicationManager.getApplication().invokeLater(() -> Messages.showInfoMessage(msg, "Commit All"));
    }

    private void sendNotification(Project project, String title, String content, NotificationType type) {
        Notification notification = new Notification("Cloud Project Download", title, content, type);
        Notifications.Bus.notify(notification, project);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        e.getPresentation().setEnabledAndVisible(file != null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}