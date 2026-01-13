package com.bap.dev.handler;

import bap.dev.FileDto;
import bap.dev.JavaDto;
import bap.java.CJavaCode;
import bap.java.CJavaConst;
import bap.java.NoFolderException;
import com.bap.dev.BapRpcClient;
import com.bap.dev.i18n.BapBundle;
import com.bap.dev.listener.BapChangesNotifier;
import com.bap.dev.service.BapConnectionManager;
import com.bap.dev.service.BapFileStatus;
import com.bap.dev.service.BapFileStatusService;
import com.bap.dev.ui.BapChangesTreePanel;
import com.intellij.ide.projectView.ProjectView;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager; // 引入
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager; // 引入
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vcs.FileStatusManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileVisitor;
import com.intellij.psi.PsiManager;
import com.leavay.common.util.ToolUtilities;
import cplugin.ms.dto.CResFileDto;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProjectRefresher {

    private final Project project;
    private static final Logger LOG = Logger.getInstance(ProjectRefresher.class);

    public ProjectRefresher(Project project) {
        this.project = project;
    }

    /**
     * 新增：刷新项目中的所有 Bap 模块
     */
    public void refreshAllModules() {
        if (project.isDisposed()) return;

        Module[] modules = ModuleManager.getInstance(project).getModules();
        for (Module module : modules) {
            // 获取模块的 Content Roots
            VirtualFile[] contentRoots = ModuleRootManager.getInstance(module).getContentRoots();
            for (VirtualFile root : contentRoots) {
                // 只要根目录下有 .develop 文件，就认为是 Bap 模块
                if (root.findChild(CJavaConst.PROJECT_DEVELOP_CONF_FILE) != null) {
                    LOG.info(BapBundle.message("handler.ProjectRefresher.log.auto_refresh", module.getName())); // "Auto-refreshing module: " + module.getName()
                    refreshModule(root, true);
                    // 一个模块刷新一次即可 (假设只有一个根是 Bap 根)
                    break;
                }
            }
        }
    }

    // 兼容旧代码的方法重载 (默认为静默，或者你可以根据调用点逐个修改)
    public void refreshModule(VirtualFile moduleDir) {
        refreshModule(moduleDir, true);
    }

    /**
     * 核心刷新方法
     * @param moduleDir 模块根目录
     * @param silentMode 是否静默模式 (true=不弹窗报错, false=弹窗报错)
     */
    public void refreshModule(VirtualFile moduleDir, boolean silentMode) {
        // 0. 保存文档
        ApplicationManager.getApplication().invokeAndWait(() -> {
            FileDocumentManager.getInstance().saveAllDocuments();
        });

        // 1. 读取配置
        File confFile = new File(moduleDir.getPath(), CJavaConst.PROJECT_DEVELOP_CONF_FILE);
        if (!confFile.exists()) {
            // 配置文件不存在通常不用弹窗，因为可能是普通文件夹
            return;
        }

        String uri = null, user = null, pwd = null, projectUuid = null;
        try {
            String content = Files.readString(confFile.toPath());
            uri = extractAttr(content, "Uri");
            user = extractAttr(content, "User");
            pwd = extractAttr(content, "Password");
            projectUuid = extractAttr(content, "Project");
        } catch (Exception e) {
            e.printStackTrace();
            // 🔴 配置文件损坏提示
            showError(
                    BapBundle.message("error.read_config", e.getMessage()), // "配置读取失败" (Key suggestion, matching CN: title.config_error or specific)
                    BapBundle.message("warning.no_develop_config"), // "无法读取 .develop 配置文件: " + e.getMessage()
                    silentMode
            );
            return;
        }

        if (uri == null || projectUuid == null) {
            // 🔴 关键信息缺失提示
            showError(
                    BapBundle.message("error.config_incomplete"),
                    BapBundle.message("error.config_incomplete"),
                    silentMode
            );
            return;
        }

        // 2. 获取客户端
        BapRpcClient client = null;
        try {
            client = BapConnectionManager.getInstance(project).getSharedClient(uri, user, pwd);
        } catch (Exception e) {
            e.printStackTrace();
            // 🔴 连接/鉴权失败提示 (这里会捕获密码错误)
            showError(
                    BapBundle.message("title.connection_failed"), // "连接失败" (Common)
                    BapBundle.message("handler.ProjectRefresher.error.connect_detail", uri, e.getMessage()), // "无法连接到服务器..."
                    silentMode
            );
            return;
        }

        try {
            VirtualFile srcDir = moduleDir.findChild("src");
            if (srcDir == null || !srcDir.exists()) {
                // src 不存在也不算严重错误，可能是空项目，可以选择不提示或 log
                return;
            }

            BapFileStatusService statusService = BapFileStatusService.getInstance(project);

            // 3. 遍历 src 下的子目录
            for (VirtualFile subDir : srcDir.getChildren()) {
                if (subDir.isDirectory()) {
                    String folderName = subDir.getName();
                    if ("res".equals(folderName)) {
                        refreshResFolder(client, projectUuid, subDir, statusService);
                    } else {
                        refreshJavaFolder(client, projectUuid, subDir, statusService);
                    }
                }
            }

            // 4. 刷新 UI 并发送通知
            ApplicationManager.getApplication().invokeLater(() -> {
                PsiManager.getInstance(project).dropPsiCaches();
                FileStatusManager.getInstance(project).fileStatusesChanged();
                ProjectView.getInstance(project).refresh();

                // 🔴 新增：设置最后刷新的模块，以便 TreePanel 自动选中
                project.putUserData(BapChangesTreePanel.LAST_BAP_MODULE_ROOT, moduleDir);

                project.getMessageBus().syncPublisher(BapChangesNotifier.TOPIC).onChangesUpdated();
            });

        } catch (Exception e) {
            e.printStackTrace();
            // 🔴 刷新过程中的其他异常
            showError(
                    BapBundle.message("title.refresh_exception"), // "刷新异常" (Common)
                    BapBundle.message("handler.ProjectRefresher.error.unknown", e.getMessage()), // "同步过程中发生未知错误: " + e.getMessage()
                    silentMode
            );
        }
    }

    // --- 🔴 辅助：判断是否为忽略文件 ---
    private boolean isIgnored(VirtualFile file) {
        // 1. 显式过滤 MacOS 垃圾文件
        if (".DS_Store".equals(file.getName())) return true;
        // 2. 使用 IDEA 全局配置的忽略列表 (包含 .git, .svn, .DS_Store 等)
        return FileTypeManager.getInstance().isFileIgnored(file);
    }

    // 🔴 修改：增加 silentMode 判断
    private void showError(String title, String content, boolean silentMode) {
        if (silentMode) {
            // 静默模式下只打印 Log，不打扰用户
            LOG.warn("[" + title + "] " + content);
        } else {
            // 手动模式下弹窗
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!project.isDisposed()) {
                    Messages.showErrorDialog(project, content, title);

                }
            });
        }
    }

    // ... (保持 refreshResFolder, refreshJavaFolder, doubleCheckResource 等所有辅助方法不变) ...
    // 请直接复用之前的文件内容

    // --- 资源文件刷新逻辑 ---
    private void refreshResFolder(BapRpcClient client, String projectUuid, VirtualFile subDir, BapFileStatusService statusService) {
        try {
            Map<String, FileDto> tempMap;
            try {
                tempMap = client.getService().queryAllFileMap(projectUuid, "res");
            } catch (Exception ex) {
                // 云端没有 res 目录：视为云端空目录，而不是刷新失败
                Throwable exceptionRootCause = ToolUtilities.getExceptionRootCause(ex);
                if (NoFolderException.class.equals(exceptionRootCause.getClass())) {
                    tempMap = new HashMap<>();
                } else {
                    throw ex;
                }
            }
            final Map<String, FileDto> cloudFileMap = (tempMap != null) ? tempMap : new HashMap<>();
            final Map<String, FileDto> missingLocalFilesMap = new HashMap<>(cloudFileMap);

            VfsUtilCore.visitChildrenRecursively(subDir, new VirtualFileVisitor<Void>() {
                @Override
                public boolean visitFile(@NotNull VirtualFile file) {
                    // 🔴 过滤逻辑：忽略 .DS_Store 等文件
                    if (isIgnored(file)) return false;

                    if (!file.isDirectory()) {
                        String key = calculateKey(subDir, file);
                        FileDto cloudDto = cloudFileMap.get(key);
                        if (cloudDto != null) {
                            checkResourceModified(file, cloudDto.getMd5(), statusService);
                            missingLocalFilesMap.remove(key);
                        } else {
                            doubleCheckResource(client, projectUuid, key, file, statusService);
                        }
                    }
                    return true;
                }
            });

            if (!missingLocalFilesMap.isEmpty()) {
                createResourcePlaceholders(subDir, missingLocalFilesMap, statusService);
            }
        } catch (Exception e) {
            LOG.warn(BapBundle.message("handler.ProjectRefresher.log.refresh_res_fail", e.getMessage()),e); // "Failed to refresh res folder: " + e.getMessage()
        }
    }

    private void doubleCheckResource(BapRpcClient client, String projectUuid, String relativePath, VirtualFile file, BapFileStatusService statusService) {
        try {
            CResFileDto resFile = client.getService().getResFile(projectUuid, relativePath, false);
            if (resFile != null) {
                statusService.setStatus(file, BapFileStatus.NORMAL);
                LOG.info(BapBundle.message("handler.ProjectRefresher.log.double_check", relativePath)); // "Double check found file: " + relativePath
            } else {
                statusService.setStatus(file, BapFileStatus.ADDED);
            }
        } catch (Exception e) {
            statusService.setStatus(file, BapFileStatus.ADDED);
        }
    }

    private void refreshJavaFolder(BapRpcClient client, String projectUuid, VirtualFile subDir, BapFileStatusService statusService) {
        String folderName = subDir.getName();
        try {
            Map<String, JavaDto> tempMap = client.getService().queryCodeFile(projectUuid, folderName);
            final Map<String, JavaDto> cloudCodeMap = (tempMap != null) ? tempMap : new HashMap<>();
            final Map<String, JavaDto> missingLocalFilesMap = new HashMap<>(cloudCodeMap);

            VfsUtilCore.visitChildrenRecursively(subDir, new VirtualFileVisitor<Void>() {
                @Override
                public boolean visitFile(@NotNull VirtualFile file) {
                    // 🔴 过滤逻辑：忽略 .DS_Store 等文件
                    if (isIgnored(file)) return false;

                    if (!file.isDirectory() && "java".equalsIgnoreCase(file.getExtension())) {
                        String key = calculateKey(subDir, file);
                        JavaDto cloudDto = cloudCodeMap.get(key);

                        if (cloudDto != null) {
                            verifyModification(client, projectUuid, file, cloudDto, statusService);
                            missingLocalFilesMap.remove(key);
                        } else {
                            statusService.setStatus(file, BapFileStatus.ADDED);
                        }
                    }
                    return true;
                }
            });

            if (!missingLocalFilesMap.isEmpty()) {
                createJavaPlaceholders(subDir, missingLocalFilesMap, statusService);
            }
        } catch (Exception e) {
            LOG.warn(BapBundle.message("handler.ProjectRefresher.log.refresh_java_fail", client.getUri() + "_" + folderName),e); // "Failed to refresh java folder: " + folderName
        }
    }

    private void checkResourceModified(VirtualFile file, String remoteMd5, BapFileStatusService statusService) {
        try {
            if (file.getLength() == 0) {
                statusService.setStatus(file, BapFileStatus.DELETED_LOCALLY);
                return;
            }
            byte[] content = file.contentsToByteArray();
            String localMd5 = calculateBytesMD5(content);

            if (remoteMd5 != null && remoteMd5.equalsIgnoreCase(localMd5)) {
                statusService.setStatus(file, BapFileStatus.NORMAL);
            } else {
                statusService.setStatus(file, BapFileStatus.MODIFIED);
            }
        } catch (Exception e) {
            statusService.setStatus(file, BapFileStatus.MODIFIED);
        }
    }

    private void verifyModification(BapRpcClient client, String projectUuid, VirtualFile file, JavaDto cloudDto, BapFileStatusService statusService) {
        try {
            if (file.getLength() == 0) {
                statusService.setStatus(file, BapFileStatus.DELETED_LOCALLY);
                return;
            }
            String localContent = new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
            if (localContent.trim().isEmpty()) {
                statusService.setStatus(file, BapFileStatus.DELETED_LOCALLY);
                return;
            }

            String standardLocalMd5 = calculateStandardMD5(localContent.replace("\r\n", "\n"));
            if (cloudDto.getMd5() != null && cloudDto.getMd5().equalsIgnoreCase(standardLocalMd5)) {
                statusService.setStatus(file, BapFileStatus.NORMAL);
                return;
            }

            if (cloudDto.getFullClass() != null) {
                Object remoteObj = client.getService().getJavaCode(projectUuid, cloudDto.getFullClass());
                String remoteCode = extractCodeString(remoteObj);
                if (remoteCode != null) {
                    String looseLocal = calculateLooseMD5(localContent);
                    String looseRemote = calculateLooseMD5(remoteCode);
                    if (looseLocal.equals(looseRemote)) {
                        statusService.setStatus(file, BapFileStatus.NORMAL);
                        return;
                    }
                }
            }
            statusService.setStatus(file, BapFileStatus.MODIFIED);
        } catch (Exception e) {
            statusService.setStatus(file, BapFileStatus.MODIFIED);
        }
    }

    private void createJavaPlaceholders(VirtualFile dirRoot, Map<String, JavaDto> missingMap, BapFileStatusService statusService) {
        createPlaceholderCommon(dirRoot, missingMap.keySet(), statusService);
    }

    private void createResourcePlaceholders(VirtualFile dirRoot, Map<String, FileDto> missingMap, BapFileStatusService statusService) {
        createPlaceholderCommon(dirRoot, missingMap.keySet(), statusService);
    }

    // --- 🔴 核心修改：仅记录状态，不创建文件 ---
    private void createPlaceholderCommon(VirtualFile dirRoot, java.util.Set<String> missingPaths, BapFileStatusService statusService) {
        ApplicationManager.getApplication().invokeLater(() -> {
            for (String relativePath : missingPaths) {
                // 1. 过滤垃圾文件
                if (relativePath == null || relativePath.contains(".DS_Store")) continue;

                // 2. 构造绝对路径
                File ioFile = new File(dirRoot.getPath(), relativePath);
                String fullPath = ioFile.getAbsolutePath().replace(File.separatorChar, '/');

                // 3. 🔴 仅设置状态，不创建文件
                // 注意：请确保 BapFileStatusService 提供了 setStatus(String, BapFileStatus) 方法
                // 如果只有 setStatus(VirtualFile, ...)，你需要添加该重载方法，因为此时 VirtualFile 不存在。
                statusService.setStatus(fullPath, BapFileStatus.DELETED_LOCALLY);
            }
        });
    }

    private String extractCodeString(Object obj) {
        if (obj == null) return null;
        if (obj instanceof CJavaCode) return ((CJavaCode) obj).code;
        try {
            java.lang.reflect.Field f = obj.getClass().getField("code");
            Object val = f.get(obj);
            return val != null ? val.toString() : null;
        } catch (Exception e) { return null; }
    }

    private String calculateKey(VirtualFile root, VirtualFile file) {
        String path = VfsUtilCore.getRelativePath(file, root);
        return path != null ? path : "";
    }

    private String calculateBytesMD5(byte[] content) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(content);
            return bytesToHex(hash);
        } catch (Exception e) { return ""; }
    }

    private String calculateLooseMD5(String content) {
        try {
            String normalized = content.replaceAll("\\s+", "");
            return calculateStandardMD5(normalized);
        } catch (Exception e) { return ""; }
    }

    private String calculateStandardMD5(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(content.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (Exception e) { return ""; }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) hex.append(String.format("%02X", b));
        return hex.toString();
    }

    private String extractAttr(String xml, String attr) {
        Pattern p = Pattern.compile(attr + "=\"([^\"]*)\"");
        Matcher m = p.matcher(xml);
        return m.find() ? m.group(1) : null;
    }
}