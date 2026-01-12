package com.bap.dev;

import bap.java.CJavaCenterIntf;
import com.cdao.CDaoConst;
import com.cdao.mgr.CSession;
import com.leavay.nio.crpc.CRpcAdapter;
import com.leavay.nio.crpc.CRpcClientWrapper;
import com.intellij.openapi.diagnostic.Logger;

import java.net.URI;

/**
 * 负责管理 RPC 连接、登录和会话
 */
public class BapRpcClient {
    private static final Logger LOG = Logger.getInstance(BapRpcClient.class);

    private CRpcClientWrapper<CJavaCenterIntf> rpcWrapper;

    // 保存连接信息，供外部获取（例如生成配置文件时需要）
    private String currentUri;
    private String currentUser;
    private String currentPwd;

    /**
     * 建立连接并登录
     */
    public void connect(String uri, String user, String pwd) throws Exception {
        if (isConnected()) {
            shutdown();
        }

        LOG.info("BapRpcClient: Connecting to " + uri + "...");

        rpcWrapper = new CRpcClientWrapper<>(CJavaCenterIntf.class, URI.create(uri)) {
            public CRpcAdapter getAdapter() {
                return CRpcAdapter.getInstance();
            }
        };

        // 调用登录接口
        CSession session = rpcWrapper.getIntf(true).login(user, pwd);
        // 设置全局 Session 上下文
        CRpcAdapter.setGlobalContext(CDaoConst.CTX_SESSION, session);

        this.currentUri = uri;
        this.currentUser = user;
        this.currentPwd = pwd;

        LOG.info("BapRpcClient: Connected and Logged in as " + user);
    }

    /**
     * 获取 RPC 接口服务
     */
    public CJavaCenterIntf getService() {
        if (rpcWrapper == null) {
            throw new IllegalStateException("RPC client is not connected. Call connect() first.");
        }
        return rpcWrapper.getIntf(true);
    }

    /**
     * 关闭连接
     */
    public void shutdown() {
        if (rpcWrapper != null) {
            try {
                rpcWrapper.shutdown();
            } catch (Exception e) {
                e.printStackTrace();
            }
            rpcWrapper = null;
        }
    }

    public boolean isConnected() {
        return rpcWrapper != null;
    }

    public boolean ping() {
        try {
            rpcWrapper.ping();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // --- Getters ---

    public String getUri() { return currentUri; }
    public String getUser() { return currentUser; }
    public String getPwd() { return currentPwd; }
}