package com.bap.dev.handler;

import bap.java.CJavaCenterIntf;
import bap.java.CJavaConst;
import bap.java.FileUpdatePackage;
import com.bap.dev.BapRpcClient;
import com.bap.dev.i18n.BapBundle;
import com.intellij.openapi.progress.ProgressIndicator;
import com.leavay.common.util.ProgressCtrl.ProgressControllerFEIntf;
import com.leavay.common.util.ProgressCtrl.crpc.CProgressProxy;
import com.leavay.common.util.ZipUtils;
import com.leavay.nio.crpc.CRpcAdapter;

import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class LibDownloader {

    private final BapRpcClient client;
    private final File projectRoot;

    public LibDownloader(BapRpcClient client, File projectRoot) {
        this.client = client;
        this.projectRoot = projectRoot;
    }

    /**
     * 增量更新依赖库
     */
    public void updateLibDiff(String projectUuid, ProgressIndicator indicator) throws Exception {
        CJavaCenterIntf intf = client.getService();

        File libPath = new File(projectRoot, CJavaConst.PATH_EXPORT_Lib);
        File platformLibPath = new File(projectRoot, CJavaConst.PATH_EXPORT_Platform);
        File projectLibPath = new File(projectRoot, CJavaConst.PATH_EXPORT_Project);

        // 1. 扫描本地文件 MD5
        indicator.setText(BapBundle.message("action.LibDownloader.progress.scanning")); // "Scanning local files..."
        Map<String, String> pfMapMd5 = new HashMap<>();
        Map<String, String> pjMapMd5 = new HashMap<>();
        long daoTag = -1;
        long pluginTag = -1;
        String srcMd5 = null;

        List<File> allLibFiles = getAllFiles(libPath);
        for (File f : allLibFiles) {
            String absPath = f.getAbsolutePath();
            String name = f.getName();

            if (name.equals(CJavaConst.PATH_EXPORT_PLUGIN_TAG_FILE)) {
                pluginTag = parseLong(readFileUtf8(f));
            } else if (name.equals(CJavaConst.PATH_EXPORT_DAO_TAG_FILE)) {
                daoTag = parseLong(readFileUtf8(f));
            } else if (absPath.contains(CJavaConst.PATH_EXPORT_Project)) {
                String rel = getRelativePath(projectLibPath, f);
                pjMapMd5.put(rel, calculateMD5(f));
            } else if (absPath.contains(CJavaConst.PATH_EXPORT_Platform)) {
                String rel = getRelativePath(platformLibPath, f);
                pfMapMd5.put(rel, calculateMD5(f));
            } else if (absPath.contains(CJavaConst.PATH_EXPORT_Src + File.separator + CJavaConst.Open_Src_File)) {
                srcMd5 = calculateMD5(f);
            }
        }

        // 2. DAO Model
        indicator.setText(BapBundle.message("action.LibDownloader.progress.dao")); // "Downloading DAO model..."
        indicator.setFraction(0.1);
        byte[] btDao = intf.exportModelFile(daoTag);
        if (btDao != null) {
            updateZipPackage(btDao, new File(projectRoot, CJavaConst.PATH_EXPORT_Model));
        }

        // 3. Plugin Jars
        indicator.setText(BapBundle.message("action.LibDownloader.progress.plugin")); // "Downloading plugin jars..."
        indicator.setFraction(0.3);
        updatePluginJars(intf, projectUuid, getSrcFolders(), indicator);

        // 4. Open Source
        indicator.setText(BapBundle.message("action.LibDownloader.progress.opensource")); // "Downloading open source..."
        indicator.setFraction(0.4);
        byte[] btSrc = intf.exportOpenSource(srcMd5);
        if (btSrc != null) {
            File openSrcFile = new File(projectRoot, CJavaConst.PATH_EXPORT_Open_Src + "/" + CJavaConst.Open_Src_File);
            saveFile(openSrcFile, btSrc);
        }

        // 5. Platform Jars (增量更新)
        indicator.setText(BapBundle.message("action.LibDownloader.progress.platform")); // "Downloading platform libraries..."
        indicator.setFraction(0.5);
        File tmpPlatformZip = File.createTempFile("platform_update", ".zip");
        try (OutputStream out = new FileOutputStream(tmpPlatformZip)) {

            ProgressControllerFEIntf headlessProxy = createHeadlessDialogProxy();

            // --- 🔴 修复点：在 Lambda 内部加 try-catch ---
            CProgressProxy<byte[]> srvProg = CProgressProxy.build(headlessProxy, (byte[] data) -> {
                try {
                    if (data != null && data.length > 0) {
                        out.write(data);
                    }
                } catch (IOException e) {
                    // Lambda 不允许抛出受检异常，必须包装成 RuntimeException
                    throw new RuntimeException(BapBundle.message("action.LibDownloader.error.write_stream"), e); // "Failed to write stream data"
                }
            });
            // -------------------------------------------

            CRpcAdapter.setTempTimeout(24 * 60 * 60 * 1000);
            Set<String> delList = intf.streamExportPlatformJars(srvProg, pfMapMd5);
            out.flush();

            if (delList != null) {
                for (String del : delList) {
                    new File(platformLibPath, del).delete();
                }
                if (tmpPlatformZip.length() > 0) {
                    ZipUtils.unzip(tmpPlatformZip.getAbsolutePath(), projectRoot.getAbsolutePath());
                }
            }
        } finally {
            tmpPlatformZip.delete();
        }

        // 6. Project Jars
        indicator.setText(BapBundle.message("action.LibDownloader.progress.project")); // "Downloading project libraries..."
        indicator.setFraction(0.8);
        Object pProjectJarPkgObj = intf.exportProjectJars(projectUuid, pjMapMd5);

        // 处理 Pair 类型 (使用反射兼容不同的 Pair 实现)
        if (pProjectJarPkgObj != null) {
            try {
                // 获取 byte[] left (zip content)
                java.lang.reflect.Method getLeft = pProjectJarPkgObj.getClass().getMethod("getLeft");
                byte[] zipBytes = (byte[]) getLeft.invoke(pProjectJarPkgObj);

                // 获取 Set<String> right (delete list) - 有些 Pair 实现叫 getRight，有些叫 getValue
                java.lang.reflect.Method getRight;
                try {
                    getRight = pProjectJarPkgObj.getClass().getMethod("getRight");
                } catch (NoSuchMethodException e) {
                    getRight = pProjectJarPkgObj.getClass().getMethod("getValue");
                }
                Set<String> deletes = (Set<String>) getRight.invoke(pProjectJarPkgObj);

                if (deletes != null) {
                    for (String del : deletes) new File(projectLibPath, del).delete();
                }
                if (zipBytes != null && zipBytes.length > 0) {
                    File tmp = File.createTempFile("pj_lib", ".zip");
                    saveFile(tmp, zipBytes);
                    ZipUtils.unzip(tmp.getAbsolutePath(), projectRoot.getAbsolutePath());
                    tmp.delete();
                }
            } catch (Exception e) {
                e.printStackTrace();
                System.err.println("Failed to parse Project Jars Pair: " + e.getMessage());
            }
        }
    }

    private void updatePluginJars(CJavaCenterIntf intf, String pjUuid, List<String> srcFolders, ProgressIndicator indicator) throws Exception {
        Map<String, String> pluginMd5 = scanLocalPluginMd5();
        FileUpdatePackage zipPkg = intf.exportPluginJars(pluginMd5, pjUuid, srcFolders);

        if (zipPkg != null) {
            byte[] zipContent = zipPkg.getZipContent();
            if (zipContent != null && zipContent.length > 0) {
                File tmp = File.createTempFile("plugin_update", ".zip");
                saveFile(tmp, zipContent);
                ZipUtils.unzip(tmp.getAbsolutePath(), projectRoot.getAbsolutePath());
                tmp.delete();
            }

            if (zipPkg.getDeleteList() != null) {
                for (String del : zipPkg.getDeleteList()) {
                    new File(projectRoot, CJavaConst.PATH_EXPORT_Plugin + "/" + del).delete();
                }
            }
        }
    }

    // --- 🔴 新增方法: 创建无界面代理 ---
    private ProgressControllerFEIntf createHeadlessDialogProxy() throws Exception {
        Class<?> interfaceClass = Class.forName("com.leavay.common.util.ProgressCtrl.ProgressControllerFEIntf");
        return (ProgressControllerFEIntf) Proxy.newProxyInstance(
                this.getClass().getClassLoader(),
                new Class<?>[]{interfaceClass},
                (proxy, method, args) -> {
                    String name = method.getName();
                    // 拦截服务端调用的元数据方法，返回默认值
                    switch (name) {
                        case "getMaximum": return 100;
                        case "getMinimum": return 0;
                        case "isCanceled": case "isTerminated": return false;
                    }
                    Class<?> returnType = method.getReturnType();
                    if (returnType == int.class) return 0;
                    if (returnType == long.class) return 0L;
                    if (returnType == boolean.class) return false;
                    return null;
                }
        );
    }

    // --- 其他辅助方法保持不变 ---

    private void updateZipPackage(byte[] zipData, File targetFolder) throws Exception {
        if (targetFolder.exists()) deleteDir(targetFolder);
        File tmp = File.createTempFile("update", ".zip");
        saveFile(tmp, zipData);
        ZipUtils.unzip(tmp.getAbsolutePath(), projectRoot.getAbsolutePath());
        tmp.delete();
    }

    private List<String> getSrcFolders() {
        File src = new File(projectRoot, "src");
        if (!src.exists()) return Collections.emptyList();
        String[] list = src.list();
        return list == null ? Collections.emptyList() : Arrays.asList(list);
    }

    private Map<String, String> scanLocalPluginMd5() {
        Map<String, String> map = new HashMap<>();
        File pluginDir = new File(projectRoot, CJavaConst.PATH_EXPORT_Plugin);
        List<File> files = getAllFiles(pluginDir);
        for (File f : files) {
            map.put(f.getName(), calculateMD5(f));
        }
        return map;
    }

    private List<File> getAllFiles(File dir) {
        List<File> result = new ArrayList<>();
        if (dir == null || !dir.exists()) return result;
        try (Stream<Path> walk = Files.walk(Paths.get(dir.toURI()))) {
            result = walk.filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            e.printStackTrace();
        }
        return result;
    }

    private String getRelativePath(File base, File file) {
        return base.toURI().relativize(file.toURI()).getPath();
    }

    private String calculateMD5(File file) {
        try {
            byte[] b = Files.readAllBytes(file.toPath());
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(b);
            StringBuilder hex = new StringBuilder();
            for (byte v : hash) hex.append(String.format("%02X", v));
            return hex.toString();
        } catch (Exception e) { return ""; }
    }

    private void saveFile(File file, byte[] data) throws IOException {
        if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
        }
    }

    private void deleteDir(File dir) {
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) for (File c : files) deleteDir(c);
        }
        dir.delete();
    }

    private long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return -1; }
    }

    private String readFileUtf8(File f) {
        try { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); } catch (Exception e) { return ""; }
    }
}