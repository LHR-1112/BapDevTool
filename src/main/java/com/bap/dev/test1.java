package com.bap.dev;

import bap.java.CJavaCode;
import com.leavay.common.util.GsonUtil;
import com.leavay.common.util.ToolUtilities;

public class test1 {
    public static void main(String[] args) {
        try {
            String projectUuid = "2a7c333b_116e_48e8_a913_1feebcea1e5d";

            BapRpcClient client = new BapRpcClient();
            client.connect("ws://175.178.82.117:2020", "root", "public");
            CJavaCode javaCode = client.getService().getJavaCode(projectUuid, "practicalTool.utils.PracticalToolUtils");
            System.out.println(GsonUtil.toJson(javaCode));

            System.out.println("ok");
        } catch (Exception e) {
            System.out.println(ToolUtilities.getFullExceptionStack(e));
        }
    }
}
