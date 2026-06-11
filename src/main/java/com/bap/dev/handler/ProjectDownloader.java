package com.bap.dev.handler;

import bap.java.CJavaConst;
import bap.java.CJavaFolderDto;
import bap.java.CJavaProjectDto;
import cn.hutool.core.util.StrUtil;
import com.bap.dev.BapRpcClient;
import com.bap.dev.i18n.BapBundle;
import com.intellij.openapi.diagnostic.Logger;
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
import java.util.concurrent.atomic.AtomicInteger; // 引入 AtomicInteger

public class ProjectDownloader {

    private final BapRpcClient client = new BapRpcClient();
    private static final Logger LOG = Logger.getInstance(ProjectDownloader.class);

    public void connect(String uri, String user, String pwd) throws Exception {
        client.connect(uri, user, pwd);
    }

    public void shutdown() {
        client.shutdown();
    }

    public void downloadProject(String projectUuid, String projectName, String targetDir, List<String> folders, ProgressIndicator indicator) throws Exception {
        File rootDir = new File(targetDir);
        String safeName = (projectName == null || projectName.trim().isEmpty()) ? projectUuid : projectName;
        File moduleFolder = new File(rootDir, safeName);

        if (!moduleFolder.exists()) {
            moduleFolder.mkdirs();
        }

        LOG.info(BapBundle.message("handler.ProjectDownloader.log.downloading", safeName, moduleFolder.getAbsolutePath())); // "Downloading Project [...] into [...]"

        Set<String> folderSet = new HashSet<>();
        if (folders != null && !folders.isEmpty()) {
            folderSet.addAll(folders);
        } else {
            try {
                List<CJavaFolderDto> allFolders = client.getService().getFolders(projectUuid);
                if (allFolders != null) {
                    for (CJavaFolderDto f : allFolders) folderSet.add(f.getName());
                }
            } catch (Exception e) {
                LOG.warn(BapBundle.message("handler.ProjectDownloader.error.fetch_folders")); // "Fetch folders failed, ignore."
            }
        }

        String tempFileName = "checkout_temp.zip";
        File tmpZip = new File(moduleFolder, tempFileName);

        // 统计状态：[0]=totalBytes, [1]=lastTime, [2]=lastBytes
        final long[] stats = {0, System.currentTimeMillis(), 0};
        final DecimalFormat df = new DecimalFormat("#.00");

        // --- 用于接收服务端回传的进度百分比 (0-100) ---
        AtomicInteger serverPercent = new AtomicInteger(0);

        try {
            CRpcAdapter.setTempTimeout(24 * 60 * 60 * 1000);

            if (indicator != null && indicator.isCanceled()) {
                throw new RuntimeException("USER_CANCEL_DOWNLOAD");
            }

            try (OutputStream outFile = Files.newOutputStream(tmpZip.toPath())) {
                // 传入 serverPercent 以便从代理中获取进度
                ProgressControllerFEIntf headlessDialogProxy = createHeadlessDialogProxy(serverPercent);

                CProgressProxy<byte[]> srvProg = CProgressProxy.build(headlessDialogProxy, (data) -> {
                    if (indicator != null && indicator.isCanceled()) throw new RuntimeException("USER_CANCEL_DOWNLOAD");

                    try {
                        if (data != null && data.length > 0) {
                            outFile.write(data);

                            if (indicator != null) {
                                int len = data.length;
                                stats[0] += len; // 当前已下载字节数
                                long now = System.currentTimeMillis();

                                // 每 500ms 更新一次 UI
                                if (now - stats[1] > 500) {
                                    long timeDiff = now - stats[1];
                                    long bytesDiff = stats[0] - stats[2];

                                    // 1. 计算网速
                                    double speed = (bytesDiff / 1024.0 / 1024.0) / (timeDiff / 1000.0);
                                    String speedStr = df.format(speed) + " MB/s";

                                    // 2. 计算当前已下载量
                                    double currentMb = stats[0] / 1024.0 / 1024.0;
                                    String currentStr = df.format(currentMb) + " MB";

                                    // 3. --- 🔴 核心修改：仅显示进度百分比，不显示总大小 ---
                                    int pct = serverPercent.get();

                                    if (pct > 0) {
                                        // 设置确定性进度条
                                        indicator.setIndeterminate(false);
                                        indicator.setFraction(pct / 100.0);

                                        // 显示格式：已下载: 10.5 MB (50%)  |  速度: 2.0 MB/s
                                        indicator.setText2(BapBundle.message("handler.ProjectDownloader.status.progress_pct", currentStr, pct, speedStr));
                                    } else {
                                        // 还没收到进度
                                        indicator.setIndeterminate(true);
                                        indicator.setText2(BapBundle.message("handler.ProjectDownloader.status.progress", currentStr, speedStr));
                                    }

                                    stats[1] = now;
                                    stats[2] = stats[0];
                                }
                            }
                        }
                    } catch (Exception exp) {
                        throw new RuntimeException(exp);
                    }
                });

                client.getService().streamExportProject(srvProg, projectUuid, folderSet, null);
            }

            LOG.info(BapBundle.message("handler.ProjectDownloader.log.unzipping_to", moduleFolder.getAbsolutePath())); // "Unzipping to: ..."
            if (indicator != null) {
                indicator.setIndeterminate(true);
                // 修改6: Indicator Text (复用 common)
                indicator.setText(BapBundle.message("progress.unzipping")); // "正在解压文件..."
                indicator.setText2("");
            }

            ZipUtils.unzip(tmpZip.getAbsolutePath(), moduleFolder.getAbsolutePath());

            generateConfigFile(moduleFolder, projectUuid);
            generateLaunchFile(moduleFolder);

        } catch (Exception e) {
            if (isCancelException(e)) throw new InterruptedException(BapBundle.message("handler.ProjectDownloader.error.user_cancel")); // "User Canceled"
            throw e;
        } finally {
            if (tmpZip.exists()) tmpZip.delete();
        }
    }

    // ... (generateConfigFile, generateLaunchFile, isCancelException 保持不变) ...
    private void generateConfigFile(File dstFolder, String projectUuid) throws Exception {
        String adminTool = CJavaConst.DFT_DEV_ADMIN_TOOL;
        try {
            adminTool = client.getService().getDevAdminTool();
        } catch (Throwable err) {
        }
        if (adminTool == null || adminTool.isEmpty()) adminTool = "bap.client.BapMainFrame";
        String uri = (client.getUri() == null) ? "" : client.getUri();
        String user = (client.getUser() == null) ? "" : client.getUser();
        String pwd = (client.getPwd() == null) ? "" : client.getPwd();
        String xmlContent = String.format("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n\n<Development Project=\"%s\" Uri=\"%s\" AdminTool=\"%s\" User=\"%s\" Password=\"%s\" LocalNioPort=\"-1\"/>", projectUuid, uri, adminTool, user, pwd);
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
        }
        if (StrUtil.isEmpty(content))
            content = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n<launchConfiguration type=\"org.eclipse.jdt.launching.localJavaApplication\">\n</launchConfiguration>";
        File launchFile = new File(dstFolder, CJavaConst.PROJECT_LAUNCH_FILE);
        try (FileOutputStream fos = new FileOutputStream(launchFile)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private boolean isCancelException(Throwable t) {
        while (t != null) {
            if ("USER_CANCEL_DOWNLOAD".equals(t.getMessage())) return true;
            t = t.getCause();
        }
        return false;
    }

    // --- 拦截 sendProcess 获取进度 ---
    private ProgressControllerFEIntf createHeadlessDialogProxy(AtomicInteger serverPercentRef) throws Exception {
        Class<?> interfaceClass = Class.forName("com.leavay.common.util.ProgressCtrl.ProgressControllerFEIntf");
        return (ProgressControllerFEIntf) Proxy.newProxyInstance(
                this.getClass().getClassLoader(),
                new Class<?>[]{interfaceClass},
                (proxy, method, args) -> {
                    String name = method.getName();

                    // --- 拦截 sendProcess(int percent, String msg, boolean ...) ---
                    if ("sendProcess".equals(name) && args != null && args.length > 0) {
                        Object arg0 = args[0];
                        if (arg0 instanceof Number) {
                            // 直接使用这个值作为进度百分比
                            serverPercentRef.set(((Number) arg0).intValue());
                        }
                        return null;
                    }

                    switch (name) {
                        case "getMaximum":
                            return 100;
                        case "getMinimum":
                            return 0;
                        case "isCanceled":
                        case "isTerminated":
                            return false;
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