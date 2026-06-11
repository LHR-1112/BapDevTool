package com.bap.dev.service;

import cell.panelxpro.metadata.IPanelMetadataService;
import com.leavay.nio.crpc.CRpcAdapter;
import com.leavay.nio.crpc.CRpcClientWrapper;
import com.intellij.openapi.diagnostic.Logger;

import java.net.URI;

public class MetadataRpcClient {
    private static final Logger LOG = Logger.getInstance(MetadataRpcClient.class);

    private CRpcClientWrapper<IPanelMetadataService> rpcWrapper;
    private String currentUri;
    private String currentUser;
    private String currentPwd;

    public void connect(String uri, String user, String pwd) throws Exception {
        if (isConnected()) {
            shutdown();
        }

        LOG.info("MetadataRpcClient: Connecting to " + uri + "...");

        rpcWrapper = new CRpcClientWrapper<>(IPanelMetadataService.class, URI.create(uri)) {
            public CRpcAdapter getAdapter() {
                return CRpcAdapter.getInstance();
            }
        };

        this.currentUri = uri;
        this.currentUser = user;
        this.currentPwd = pwd;

        LOG.info("MetadataRpcClient: Connected to " + uri);
    }

    public IPanelMetadataService getService() {
        if (rpcWrapper == null) {
            throw new IllegalStateException("RPC client is not connected. Call connect() first.");
        }
        return rpcWrapper.getIntf(true);
    }

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

    public String getUri() { return currentUri; }
    public String getUser() { return currentUser; }
    public String getPwd() { return currentPwd; }
}
