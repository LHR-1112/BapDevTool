package com.bap.dev.service;

import com.bap.dev.BapRpcClient;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Project 级别的 Service，用于管理长连接
 * 实现 Disposable 接口，以便在项目关闭时自动断开连接
 */
@Service(Service.Level.PROJECT)
public final class BapConnectionManager implements Disposable {

    private final Project project;
    private BapRpcClient activeClient;

    // 记录当前的连接参数，用于判断是否需要切换环境
    private String currentUri;
    private String currentUser;
    private String currentPwd;

    public BapConnectionManager(Project project) {
        this.project = project;
    }

    /**
     * 获取共享的客户端实例。
     * 如果参数变了（比如切换了 .develop 配置），或者连接断了，会自动重连。
     */
    public synchronized BapRpcClient getSharedClient(String uri, String user, String pwd) {
        // 1. 检查配置是否发生变化
        boolean configChanged = !Objects.equals(uri, currentUri) ||
                !Objects.equals(user, currentUser) ||
                !Objects.equals(pwd, currentPwd);
        System.out.println("BapConnectionManager: Config changed = " + configChanged);

        // 2. 检查当前客户端是否不可用 (假设 BapRpcClient 有 isConnected() 方法，如果没有请自行添加或仅判断 null)
        // 如果 BapRpcClient 没有 isConnected 方法，至少判断 activeClient == null
        boolean needsReconnect = activeClient == null || configChanged;

        if (activeClient != null && !configChanged) {
            try {
                // 假设有一个轻量级的方法检测连接，比如 activeClient.ping() 或 activeClient.isOpen()
                 if (!activeClient.ping()) { needsReconnect = true; }
            } catch (Exception e) {
                needsReconnect = true;
            }
        }

        if (needsReconnect) {
            // 关闭旧连接
            closeConnection();

            // 创建新连接
            try {
                BapRpcClient client = new BapRpcClient();
                client.connect(uri, user, pwd);

                // 更新状态
                this.activeClient = client;
                this.currentUri = uri;
                this.currentUser = user;
                this.currentPwd = pwd;

            } catch (Exception e) {
                // 连接失败，确保清理
                closeConnection();
                throw new RuntimeException("无法建立 BAP 长连接: " + e.getMessage(), e);
            }
        }

        return activeClient;
    }

    private void closeConnection() {
        if (activeClient != null) {
            try {
                activeClient.shutdown();
            } catch (Exception e) {
                // ignore
            }
            activeClient = null;
        }
    }

    @Override
    public void dispose() {
        // 项目关闭时，IDEA 会自动调用此方法，释放连接
        closeConnection();
    }

    // 提供静态方法方便获取
    public static BapConnectionManager getInstance(@NotNull Project project) {
        return project.getService(BapConnectionManager.class);
    }
}