package com.bap.dev;

import bap.java.CJavaCode;
import bap.java.CommitPackage;
import bap.md.yun.ArtifactDefine;
import com.leavay.common.util.GsonUtil;
import com.leavay.common.util.ToolUtilities;

import java.net.URI;
import java.util.*;

public class test1 {
    public static void main(String[] args) {
        try {
            String projectUuid = "2a7c333b_116e_48e8_a913_1feebcea1e5d";
            String fullClassName = "cell.practicalTool.controller.IPtController";

            BapRpcClient client = new BapRpcClient();
            client.connect("ws://175.178.82.117:2020", "root", "public");


            URI uriObj = URI.create("wss://demo.kwaidoo.com:443/zbyth/process/websocket");
            String host = uriObj.getHost();
            int port = uriObj.getPort();
            String path = uriObj.getPath();
            if (path == null) path = ""; // 防止 null





            System.out.println("ok");
        } catch (Exception e) {
            System.out.println(ToolUtilities.getFullExceptionStack(e));
        }
    }
}
