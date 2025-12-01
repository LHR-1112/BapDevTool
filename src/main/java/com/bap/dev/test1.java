package com.bap.dev;

import bap.java.CJavaCode;
import bap.java.CommitPackage;
import bap.md.yun.ArtifactDefine;
import com.leavay.common.util.GsonUtil;
import com.leavay.common.util.ToolUtilities;

import java.util.*;

public class test1 {
    public static void main(String[] args) {
        try {
            String projectUuid = "2a7c333b_116e_48e8_a913_1feebcea1e5d";
            String fullClassName = "cell.practicalTool.controller.IPtController";

            BapRpcClient client = new BapRpcClient();
            client.connect("ws://175.178.82.117:2020", "root", "public");
            CJavaCode javaCode = client.getService().getJavaCode(projectUuid, fullClassName);
            String code = javaCode.getCode();
            code = code.replace("初始化", "初始化1");

            Map<String, List<CJavaCode>> codeMap = new HashMap<>();
            codeMap.put("src", Collections.singletonList(javaCode));


            javaCode.setCode(code);
            CommitPackage commitPackage = new CommitPackage();
            commitPackage.setComments("提交测试");
            commitPackage.setMapFolder2Codes(codeMap);

            client.getService().commitCode(projectUuid, commitPackage);

            System.out.println("ok");
        } catch (Exception e) {
            System.out.println(ToolUtilities.getFullExceptionStack(e));
        }
    }
}
