package com.bap.dev.handler;

import bap.dev.FileDto;
import bap.dev.JavaDto;
import bap.java.CJavaCode;
import bap.java.CJavaConst;
import com.bap.dev.BapRpcClient;
import com.bap.dev.listener.BapChangesNotifier;
import com.bap.dev.service.BapConnectionManager;
import com.bap.dev.service.BapFileStatus;
import com.bap.dev.service.BapFileStatusService;
import com.intellij.ide.projectView.ProjectView;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager; // 引入
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager; // 引入
import com.intellij.openapi.vcs.FileStatusManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileVisitor;
import com.intellij.psi.PsiManager;
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
                    System.out.println("Auto-refreshing module: " + module.getName());
                    refreshModule(root);
                    // 一个模块刷新一次即可 (假设只有一个根是 Bap 根)
                    break;
                }
            }
        }
    }

    public void refreshModule(VirtualFile moduleDir) {
        // ... (保持原有的 refreshModule 代码逻辑不变) ...
        // 为了节省篇幅，这里省略 refreshModule 的具体实现，请保留你现有的代码
        // 确保它最后会调用 project.getMessageBus().syncPublisher(...).onChangesUpdated();

        // 0. 保存文档
        ApplicationManager.getApplication().invokeAndWait(() -> {
            FileDocumentManager.getInstance().saveAllDocuments();
        });

        // 1. 读取配置
        File confFile = new File(moduleDir.getPath(), CJavaConst.PROJECT_DEVELOP_CONF_FILE);
        if (!confFile.exists()) return;

        String uri = null, user = null, pwd = null, projectUuid = null;
        try {
            String content = Files.readString(confFile.toPath());
            uri = extractAttr(content, "Uri");
            user = extractAttr(content, "User");
            pwd = extractAttr(content, "Password");
            projectUuid = extractAttr(content, "Project");
        } catch (Exception e) { e.printStackTrace(); return; }

        if (uri == null || projectUuid == null) return;

        // 2. 获取客户端
        BapRpcClient client = null;
        try {
            client = BapConnectionManager.getInstance(project).getSharedClient(uri, user, pwd);
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        try {
            VirtualFile srcDir = moduleDir.findChild("src");
            if (srcDir == null || !srcDir.exists()) return;

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

                project.getMessageBus().syncPublisher(BapChangesNotifier.TOPIC).onChangesUpdated();
            });

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ... (保持 refreshResFolder, refreshJavaFolder, doubleCheckResource 等所有辅助方法不变) ...
    // 请直接复用之前的文件内容

    // --- 资源文件刷新逻辑 ---
    private void refreshResFolder(BapRpcClient client, String projectUuid, VirtualFile subDir, BapFileStatusService statusService) {
        try {
            Map<String, FileDto> tempMap = client.getService().queryAllFileMap(projectUuid, "res");
            final Map<String, FileDto> cloudFileMap = (tempMap != null) ? tempMap : new HashMap<>();
            final Map<String, FileDto> missingLocalFilesMap = new HashMap<>(cloudFileMap);

            VfsUtilCore.visitChildrenRecursively(subDir, new VirtualFileVisitor<Void>() {
                @Override
                public boolean visitFile(@NotNull VirtualFile file) {
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
            System.err.println("Failed to refresh res folder: " + e.getMessage());
        }
    }

    private void doubleCheckResource(BapRpcClient client, String projectUuid, String relativePath, VirtualFile file, BapFileStatusService statusService) {
        try {
            CResFileDto resFile = client.getService().getResFile(projectUuid, relativePath, false);
            if (resFile != null) {
                statusService.setStatus(file, BapFileStatus.NORMAL);
                System.out.println("Double check found file: " + relativePath);
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
            System.err.println("Failed to refresh java folder: " + folderName);
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

    private void createPlaceholderCommon(VirtualFile dirRoot, java.util.Set<String> missingPaths, BapFileStatusService statusService) {
        ApplicationManager.getApplication().invokeLater(() -> {
            WriteAction.run(() -> {
                for (String relativePath : missingPaths) {
                    try {
                        if (relativePath == null) continue;
                        File ioFile = new File(dirRoot.getPath(), relativePath);
                        if (!ioFile.exists()) {
                            ioFile.getParentFile().mkdirs();
                            ioFile.createNewFile();
                        }
                        VirtualFile vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile);
                        if (vFile != null) {
                            statusService.setStatus(vFile, BapFileStatus.DELETED_LOCALLY);
                        }
                    } catch (IOException e) {}
                }
            });
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