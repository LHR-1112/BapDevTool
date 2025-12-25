package com.bap.dev.handler;

import bap.java.CJavaConst;
import bap.java.CJavaFolderDto;
import bap.java.CJavaProjectDto;
import cn.hutool.core.util.StrUtil;
import com.bap.dev.BapRpcClient;
import com.intellij.openapi.progress.ProgressIndicator;
import com.leavay.common.util.ProgressCtrl.ProgressControllerFEIntf;
import com.leavay.common.util.ProgressCtrl.crpc.CProgressProxy;
import com.leavay.common.util.ZipUtils;
import com.leavay.nio.crpc.CRpcAdapter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.DecimalFormat;
import java.util.*;
import java.util.function.Supplier;

public class ProjectDownloader {

    // 使用抽象出来的客户端
    private final BapRpcClient client = new BapRpcClient();

    public void connect(String uri, String user, String pwd) throws Exception {
        client.connect(uri, user, pwd);
    }

    public List<CJavaProjectDto> fetchProjectList(String uri, String user, String pwd) throws Exception {
        try {
            client.connect(uri, user, pwd);
            List<CJavaProjectDto> projects = client.getService().getAllProjects();
            if (projects == null) {
                return Collections.emptyList();
            }
            return projects;
        } finally {
            // 注意：获取列表通常是短连接操作，获取完可以关闭
            // 但如果是 downloadProject 流程，则由 Action 层控制 shutdown
            client.shutdown();
        }
    }

    public void downloadProject(String projectUuid, String projectName, String targetDir, List<String> folders, ProgressIndicator indicator) throws Exception {
        File rootDir = new File(targetDir);
        String safeName = (projectName == null || projectName.trim().isEmpty()) ? projectUuid : projectName;
        File moduleFolder = new File(rootDir, safeName);

        if (!moduleFolder.exists()) {
            moduleFolder.mkdirs();
        }

        System.out.println("Downloading Project [" + safeName + "] into [" + moduleFolder.getAbsolutePath() + "]");

        // 1. 准备文件夹过滤
        Set<String> folderSet = new HashSet<>();
        if (folders != null && !folders.isEmpty()) {
            folderSet.addAll(folders);
        } else {
            try {
                // 使用 client 获取服务
                List<CJavaFolderDto> allFolders = client.getService().getFolders(projectUuid);
                if (allFolders != null) {
                    for (CJavaFolderDto f : allFolders) folderSet.add(f.getName());
                }
            } catch (Exception e) {
                System.err.println("Fetch folders failed, ignore.");
            }
        }

        String tempFileName = "checkout_temp.zip";
        File tmpZip = new File(moduleFolder, tempFileName);

        // --- 🔴 新增：用于网速统计的状态变量 ---
        // stats[0]=totalBytes, stats[1]=lastTime, stats[2]=lastBytes
        final long[] stats = {0, System.currentTimeMillis(), 0};
        final DecimalFormat df = new DecimalFormat("#.00");

        try {
            CRpcAdapter.setTempTimeout(24 * 60 * 60 * 1000);

            // --- 🔴 修改：使用 indicator 检查取消 ---
            if (indicator != null && indicator.isCanceled()) throw new RuntimeException("USER_CANCEL_DOWNLOAD");

            try (OutputStream outFile = Files.newOutputStream(tmpZip.toPath())) {
                ProgressControllerFEIntf headlessDialogProxy = createHeadlessDialogProxy();
                CProgressProxy<byte[]> srvProg = CProgressProxy.build(headlessDialogProxy, (data) -> {
                    // --- 🔴 修改：使用 indicator 检查取消 ---
                    if (indicator != null && indicator.isCanceled()) throw new RuntimeException("USER_CANCEL_DOWNLOAD");

                    try {
                        if (data != null && data.length > 0) {
                            outFile.write(data);

                            // --- 🔴 新增：计算网速并更新 UI ---
                            if (indicator != null) {
                                int len = data.length;
                                stats[0] += len;
                                long now = System.currentTimeMillis();
                                // 每 500ms 更新一次 UI，避免闪烁
                                if (now - stats[1] > 500) {
                                    long timeDiff = now - stats[1];
                                    long bytesDiff = stats[0] - stats[2];

                                    // 计算速度 (MB/s)
                                    double speed = (bytesDiff / 1024.0 / 1024.0) / (timeDiff / 1000.0);
                                    double totalMb = stats[0] / 1024.0 / 1024.0;

                                    String speedStr = df.format(speed) + " MB/s";
                                    String sizeStr = df.format(totalMb) + " MB";

                                    // 更新提示信息
                                    indicator.setText2("已下载: " + sizeStr + "  |  速度: " + speedStr);

                                    stats[1] = now;
                                    stats[2] = stats[0];
                                }
                            }
                            // ------------------------------------
                        }
                    } catch (Exception exp) { throw new RuntimeException(exp); }
                });

                // 使用 client 获取服务进行下载
                client.getService().streamExportProject(srvProg, projectUuid, folderSet, null);
            }

            System.out.println("Unzipping to: " + moduleFolder.getAbsolutePath());
            ZipUtils.unzip(tmpZip.getAbsolutePath(), moduleFolder.getAbsolutePath());

            generateConfigFile(moduleFolder, projectUuid);
            generateLaunchFile(moduleFolder);

        } catch (Exception e) {
            if (isCancelException(e)) throw new InterruptedException("User Canceled");
            throw e;
        } finally {
            if (tmpZip.exists()) tmpZip.delete();
        }
    }

    public void shutdown() {
        client.shutdown();
    }

    private void generateConfigFile(File dstFolder, String projectUuid) throws Exception {
        String adminTool = CJavaConst.DFT_DEV_ADMIN_TOOL;
        try {
            // 使用 client 获取服务
            adminTool = client.getService().getDevAdminTool();
        } catch (Throwable err) {
            System.err.println("Warning: Failed to get AdminTool config, using default.");
        }

        if (adminTool == null || adminTool.isEmpty()) {
            adminTool = "bap.client.BapMainFrame";
        }

        // 从 client 获取连接信息
        String uri = (client.getUri() == null) ? "" : client.getUri();
        String user = (client.getUser() == null) ? "" : client.getUser();
        String pwd = (client.getPwd() == null) ? "" : client.getPwd();

        String xmlContent = String.format(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                        "\n" +
                        "<Development Project=\"%s\" Uri=\"%s\" AdminTool=\"%s\" User=\"%s\" Password=\"%s\" LocalNioPort=\"-1\"/>",
                projectUuid,
                uri,
                adminTool,
                user,
                pwd
        );

        File confFile = new File(dstFolder, CJavaConst.PROJECT_DEVELOP_CONF_FILE);
        try (FileOutputStream fos = new FileOutputStream(confFile)) {
            fos.write(xmlContent.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void generateLaunchFile(File dstFolder) throws Exception {
        String templatePath = CJavaConst.PROJECT_LAUNCH_TEMPLATE;
        templatePath = templatePath.substring(1);

        String content = "";
        try {
            InputStream in = this.getClass().getClassLoader().getResourceAsStream(templatePath);
            if (in != null) {
                byte[] bytes = new byte[in.available()];
                in.read(bytes);
                content = new String(bytes, StandardCharsets.UTF_8);
                in.close();
            }
        } catch (Exception e) {
            // ignore
        }

        if (StrUtil.isEmpty(content)) {
            content = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n<launchConfiguration type=\"org.eclipse.jdt.launching.localJavaApplication\">\n</launchConfiguration>";
        }

        File launchFile = new File(dstFolder, CJavaConst.PROJECT_LAUNCH_FILE);
        try (FileOutputStream fos = new FileOutputStream(launchFile)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    // ... isCancelException 和 createHeadlessDialogProxy 保持不变 ...

    private boolean isCancelException(Throwable t) {
        while (t != null) {
            if ("USER_CANCEL_DOWNLOAD".equals(t.getMessage())) return true;
            t = t.getCause();
        }
        return false;
    }

    private ProgressControllerFEIntf createHeadlessDialogProxy() throws Exception {
        Class<?> interfaceClass = Class.forName("com.leavay.common.util.ProgressCtrl.ProgressControllerFEIntf");
        return (ProgressControllerFEIntf) Proxy.newProxyInstance(
                this.getClass().getClassLoader(),
                new Class<?>[]{interfaceClass},
                (proxy, method, args) -> {
                    String name = method.getName();
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
}