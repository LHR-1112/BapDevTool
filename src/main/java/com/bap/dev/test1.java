package com.bap.dev;

import bap.dev.JavaDto;
import bap.java.CJavaCode;
import bap.md.ver.VersionNode;
import bap.md.yun.ArtifactDefine;
import com.leavay.common.util.GsonUtil;
import com.leavay.common.util.ToolUtilities;

import java.util.*;
import java.util.stream.Collectors;

public class test1 {
    public static void main(String[] args) {
        try {
            String projectUuid = "2a7c333b_116e_48e8_a913_1feebcea1e5d";
            String fullClassName = "cell.practicalTool.controller.IPtController";

            BapRpcClient client = new BapRpcClient();
            client.connect("ws://175.178.82.117:2020", "root", "public");

            List<ArtifactDefine> artifactDefines = client.getService().queryInstalledArtifacts(true);
            System.out.println(GsonUtil.toJson(artifactDefines));


            System.out.println("ok");
        } catch (Exception e) {
            System.out.println(ToolUtilities.getFullExceptionStack(e));
        }
    }
}
