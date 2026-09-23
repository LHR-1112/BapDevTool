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
import cplugin.ms.dto.CJarFileDto;

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

/**
 * 依赖库增量更新。整个流程拆成四步，方便在"下载前"和"落盘前"分别给用户提示：
 * <ol>
 *   <li>{@link #scanLocal} 扫描本地文件指纹</li>
 *   <li>{@link #preflight} 只调轻量接口做预检，不传输 jar 字节</li>
 *   <li>{@link #download} 把变更的包下到内存/临时文件，不碰工程目录</li>
 *   <li>{@link #apply} 用户确认后才解压覆盖，真正落盘</li>
 * </ol>
 */
public class LibDownloader {

    private final BapRpcClient client;
    private final File projectRoot;
    private final File libRoot;
    private final File platformLibDir;
    private final File projectLibDir;
    private final File modelDir;
    private final File pluginDir;
    private final File openSrcZip;

    /** 本地已下载依赖的指纹快照，用于和服务端比对 */
    public static class LocalState {
        public final Map<String, String> platformMd5 = new HashMap<>();
        public final Map<String, String> pluginMd5 = new HashMap<>();
        public final Map<String, String> projectMd5 = new HashMap<>();
        public long daoTag = -1;
        public long pluginTag = -1;
        public String openSourceMd5;
    }

    /** 下载前的预检结果。服务端没有"只查差异不传文件"的接口，所以只有部分类别能算准。 */
    public static class PreflightResult {
        /** 精确：服务端 dao tag 与本地不一致 */
        public boolean daoWillUpdate;
        /** 只能判断"有没有变化"：服务端只提供插件包的整体 tag */
        public boolean pluginChanged;
        /** 精确：getJarFiles 只回 name + fileMd5，不传 jar 内容 */
        public int projectJarsToUpdate;
        public int projectJarsToDelete;
        public boolean projectScanned;
        /** 精确：服务端有、本地没有的文件 */
        public int platformNewFiles;
        /** 精确：本地有、服务端没有的文件 */
        public int platformMissingFiles;
        /** 同名文件，内容是否变化必须下载后才能确定 */
        public int platformSameNameFiles;
        public boolean platformScanned;

        public boolean hasAnyHint() {
            return daoWillUpdate || pluginChanged || projectJarsToUpdate > 0 || projectJarsToDelete > 0
                    || platformNewFiles > 0 || platformMissingFiles > 0 || platformSameNameFiles > 0;
        }
    }

    /** 本次增量更新实际下载下来的文件数量。服务端只下发发生变更的文件，所以这些数量就是"需要更新"的量。 */
    public static class UpdateResult {
        public int platform;
        public int plugin;
        public int project;
        public int dao;
        public boolean openSourceUpdated;

        public int totalJars() {
            return platform + plugin + project + dao;
        }
    }

    /** 已下载但尚未写入工程目录的更新包。用户确认前不会碰磁盘上的工程文件。 */
    public static class PendingUpdate {
        private final UpdateResult result = new UpdateResult();
        private byte[] daoPackage;
        private byte[] pluginPackage;
        private List<String> pluginDeletes;
        private byte[] openSourceZip;
        private File platformZip;
        private Set<String> platformDeletes;
        private byte[] projectPackage;
        private Set<String> projectDeletes;

        public UpdateResult getResult() {
            return result;
        }

        public boolean isEmpty() {
            return result.totalJars() == 0 && !result.openSourceUpdated;
        }

        /** 用户取消或流程结束时清掉临时文件 */
        public void cleanup() {
            if (platformZip != null && platformZip.exists()) platformZip.delete();
        }
    }

    public LibDownloader(BapRpcClient client, File projectRoot) {
        this.client = client;
        this.projectRoot = projectRoot;
        this.libRoot = new File(projectRoot, CJavaConst.PATH_EXPORT_Lib);
        this.platformLibDir = new File(projectRoot, CJavaConst.PATH_EXPORT_Platform);
        this.projectLibDir = new File(projectRoot, CJavaConst.PATH_EXPORT_Project);
        this.modelDir = new File(projectRoot, CJavaConst.PATH_EXPORT_Model);
        this.pluginDir = new File(projectRoot, CJavaConst.PATH_EXPORT_Plugin);
        this.openSrcZip = new File(projectRoot, CJavaConst.PATH_EXPORT_Open_Src + File.separator + CJavaConst.Open_Src_File);
    }

    /** 第一步：扫描本地文件指纹 */
    public LocalState scanLocal(ProgressIndicator indicator) {
        indicator.setText(BapBundle.message("action.LibDownloader.progress.scanning")); // "Scanning local files..."
        LocalState local = new LocalState();

        for (File f : getAllFiles(libRoot)) {
            String absPath = f.getAbsolutePath();
            String name = f.getName();

            if (name.equals(CJavaConst.PATH_EXPORT_PLUGIN_TAG_FILE)) {
                local.pluginTag = parseLong(readFileUtf8(f));
            } else if (name.equals(CJavaConst.PATH_EXPORT_DAO_TAG_FILE)) {
                local.daoTag = parseLong(readFileUtf8(f));
            } else if (absPath.contains(CJavaConst.PATH_EXPORT_Project)) {
                local.projectMd5.put(getRelativePath(projectLibDir, f), calculateMD5(f));
            } else if (absPath.contains(CJavaConst.PATH_EXPORT_Platform)) {
                local.platformMd5.put(getRelativePath(platformLibDir, f), calculateMD5(f));
            }
        }

        for (File f : getAllFiles(pluginDir)) {
            local.pluginMd5.put(f.getName(), calculateMD5(f));
        }

        // openSource 包在 openSource/src.zip，不在 lib 目录下，必须单独算 MD5。
        // 传 null 时服务端认为本地没有该包，每次都会回传全量 zip。
        if (openSrcZip.isFile()) {
            local.openSourceMd5 = calculateMD5(openSrcZip);
        }
        return local;
    }

    /**
     * 第二步：预检。只调不传输 jar 字节的轻量接口：
     * <ul>
     *   <li>{@code getDaoTag} / {@code getPluginTag}：和本地 tag 比对</li>
     *   <li>{@code getJarFiles}：走 FIELDS_BRIEF，只回 name + fileMd5</li>
     *   <li>{@code scanLocalFiles}：与平台包导出用的是服务端同一段扫描逻辑，但只回文件名、没有 md5</li>
     * </ul>
     * 任何一项失败都不影响后续更新，只是那部分提示会缺失。
     */
    public PreflightResult preflight(String projectUuid, LocalState local, ProgressIndicator indicator) {
        CJavaCenterIntf intf = client.getService();
        PreflightResult pre = new PreflightResult();
        indicator.setText(BapBundle.message("action.LibDownloader.progress.preflight")); // "Checking cloud updates..."

        try {
            pre.daoWillUpdate = intf.getDaoTag() != local.daoTag;
        } catch (Exception ignore) {
            // 预检失败不影响更新，下面的精确确认仍然有效
        }

        try {
            pre.pluginChanged = intf.getPluginTag() != local.pluginTag;
        } catch (Exception ignore) {
        }

        try {
            List<CJarFileDto> serverJars = intf.getJarFiles(projectUuid);
            Map<String, String> localPj = new HashMap<>(local.projectMd5);
            for (CJarFileDto jar : serverJars) {
                String localMd5 = localPj.remove(jar.getName());
                String serverMd5 = jar.getFileMd5();
                // serverMd5 为空说明服务端没存指纹，只能交给下载后的精确统计
                if (localMd5 == null || (serverMd5 != null && !serverMd5.equalsIgnoreCase(localMd5))) {
                    pre.projectJarsToUpdate++;
                }
            }
            pre.projectJarsToDelete = localPj.size();
            pre.projectScanned = true;
        } catch (Exception ignore) {
        }

        try {
            // 与服务端 exportPlatformJars 的扫描范围保持一致：lib 与 lib_ui
            List<String> serverNames = intf.scanLocalFiles(CJavaConst.PATH_EXPORT_Lib, "lib_ui");
            Set<String> localNames = new HashSet<>(local.platformMd5.keySet());
            for (String name : serverNames) {
                if (localNames.remove(name)) {
                    pre.platformSameNameFiles++;
                } else {
                    pre.platformNewFiles++;
                }
            }
            pre.platformMissingFiles = localNames.size();
            pre.platformScanned = true;
        } catch (Exception ignore) {
        }
        return pre;
    }

    /** 第三步：把变更的包下载到内存/临时文件，不写工程目录 */
    public PendingUpdate download(String projectUuid, LocalState local, ProgressIndicator indicator) throws Exception {
        CJavaCenterIntf intf = client.getService();
        PendingUpdate pending = new PendingUpdate();
        UpdateResult result = pending.getResult();

        // 1. DAO Model
        indicator.setText(BapBundle.message("action.LibDownloader.progress.dao")); // "Downloading DAO model..."
        indicator.setFraction(0.1);
        pending.daoPackage = intf.exportModelFile(local.daoTag);
        if (pending.daoPackage != null) {
            result.dao = countZipJars(pending.daoPackage);
            reportCount(indicator, result);
        }

        // 2. Plugin Jars
        indicator.setText(BapBundle.message("action.LibDownloader.progress.plugin")); // "Downloading plugin jars..."
        indicator.setFraction(0.3);
        FileUpdatePackage zipPkg = intf.exportPluginJars(local.pluginMd5, projectUuid, getSrcFolders());
        if (zipPkg != null) {
            pending.pluginPackage = zipPkg.getZipContent();
            pending.pluginDeletes = zipPkg.getDeleteList();
            result.plugin = countZipJars(pending.pluginPackage);
            reportCount(indicator, result);
        }

        // 3. Open Source
        indicator.setText(BapBundle.message("action.LibDownloader.progress.opensource")); // "Downloading open source..."
        indicator.setFraction(0.4);
        pending.openSourceZip = intf.exportOpenSource(local.openSourceMd5);
        result.openSourceUpdated = pending.openSourceZip != null;

        // 4. Platform Jars (增量更新)
        indicator.setText(BapBundle.message("action.LibDownloader.progress.platform")); // "Downloading platform libraries..."
        indicator.setFraction(0.5);
        downloadPlatformJars(intf, local, pending);
        reportCount(indicator, result);

        // 5. Project Jars
        indicator.setText(BapBundle.message("action.LibDownloader.progress.project")); // "Downloading project libraries..."
        indicator.setFraction(0.8);
        downloadProjectJars(intf, projectUuid, local, pending);
        reportCount(indicator, result);

        return pending;
    }

    /** 第四步：用户确认后才真正落盘 */
    public void apply(PendingUpdate pending, ProgressIndicator indicator) throws Exception {
        indicator.setText(BapBundle.message("action.LibDownloader.progress.applying")); // "Applying updates..."

        if (pending.daoPackage != null) {
            updateZipPackage(pending.daoPackage, modelDir);
        }
        if (pending.pluginPackage != null && pending.pluginPackage.length > 0) {
            unzipToProject(pending.pluginPackage, "plugin_update");
        }
        if (pending.pluginDeletes != null) {
            for (String del : pending.pluginDeletes) new File(pluginDir, del).delete();
        }
        if (pending.openSourceZip != null) {
            saveFile(openSrcZip, pending.openSourceZip);
        }
        if (pending.platformDeletes != null) {
            for (String del : pending.platformDeletes) new File(platformLibDir, del).delete();
        }
        if (pending.platformZip != null && pending.platformZip.length() > 0) {
            ZipUtils.unzip(pending.platformZip.getAbsolutePath(), projectRoot.getAbsolutePath());
        }
        if (pending.projectDeletes != null) {
            for (String del : pending.projectDeletes) new File(projectLibDir, del).delete();
        }
        if (pending.projectPackage != null && pending.projectPackage.length > 0) {
            unzipToProject(pending.projectPackage, "pj_lib");
        }
    }

    /** 平台依赖走流式接口，直接落到临时文件，避免整个包驻留内存 */
    private void downloadPlatformJars(CJavaCenterIntf intf, LocalState local, PendingUpdate pending) throws Exception {
        File tmpPlatformZip = File.createTempFile("platform_update", ".zip");
        try {
            try (OutputStream out = new FileOutputStream(tmpPlatformZip)) {

                ProgressControllerFEIntf headlessProxy = createHeadlessDialogProxy();

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

                CRpcAdapter.setTempTimeout(24 * 60 * 60 * 1000);
                pending.platformDeletes = intf.streamExportPlatformJars(srvProg, local.platformMd5);
                out.flush();
            }
        } catch (Exception e) {
            // 下载失败就把临时文件清掉，否则交给 cleanup() 处理
            tmpPlatformZip.delete();
            throw e;
        }
        pending.platformZip = tmpPlatformZip;
        pending.getResult().platform = countZipJars(tmpPlatformZip);
    }

    private void downloadProjectJars(CJavaCenterIntf intf, String projectUuid, LocalState local, PendingUpdate pending) throws Exception {
        Object pProjectJarPkgObj = intf.exportProjectJars(projectUuid, local.projectMd5);

        // 处理 Pair 类型 (使用反射兼容不同的 Pair 实现)
        if (pProjectJarPkgObj == null) return;
        try {
            // 获取 byte[] left (zip content)
            java.lang.reflect.Method getLeft = pProjectJarPkgObj.getClass().getMethod("getLeft");
            pending.projectPackage = (byte[]) getLeft.invoke(pProjectJarPkgObj);

            // 获取 Set<String> right (delete list) - 有些 Pair 实现叫 getRight，有些叫 getValue
            java.lang.reflect.Method getRight;
            try {
                getRight = pProjectJarPkgObj.getClass().getMethod("getRight");
            } catch (NoSuchMethodException e) {
                getRight = pProjectJarPkgObj.getClass().getMethod("getValue");
            }
            pending.projectDeletes = (Set<String>) getRight.invoke(pProjectJarPkgObj);

            pending.getResult().project = countZipJars(pending.projectPackage);
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Failed to parse Project Jars Pair: " + e.getMessage());
        }
    }

    /** 把"已更新 N 个 Jar 包"实时回显到进度条第二行 */
    private void reportCount(ProgressIndicator indicator, UpdateResult result) {
        indicator.setText2(BapBundle.message("action.LibDownloader.progress.updated_count", result.totalJars()));
    }

    /** 统计压缩包里的 Jar 包数量。服务端只打包发生变更的文件，所以条目数就是本次要更新的量。
     *  注意过滤掉 plugin.tag / dao_model.tag 这类非 jar 的附属文件。 */
    private int countZipJars(byte[] zipData) {
        if (zipData == null || zipData.length == 0) return 0;
        int count = 0;
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(new ByteArrayInputStream(zipData))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (isJarEntry(entry)) count++;
            }
        } catch (IOException e) {
            return 0;
        }
        return count;
    }

    private int countZipJars(File zipFile) {
        if (zipFile == null || !zipFile.isFile() || zipFile.length() == 0) return 0;
        int count = 0;
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zipFile)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                if (isJarEntry(entries.nextElement())) count++;
            }
        } catch (IOException e) {
            return 0;
        }
        return count;
    }

    private boolean isJarEntry(java.util.zip.ZipEntry entry) {
        return !entry.isDirectory() && entry.getName().toLowerCase().endsWith(".jar");
    }

    // --- 创建无界面代理 ---
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

    private void updateZipPackage(byte[] zipData, File targetFolder) throws Exception {
        if (targetFolder.exists()) deleteDir(targetFolder);
        unzipToProject(zipData, "update");
    }

    private void unzipToProject(byte[] zipData, String tmpPrefix) throws Exception {
        File tmp = File.createTempFile(tmpPrefix, ".zip");
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
