package com.bap.dev.util;

import bap.java.CJavaConst;
import com.intellij.openapi.vfs.VirtualFile;

public class BapUtils {

    /**
     * 递归向上查找包含 .develop 文件的根目录
     */
    public static VirtualFile findModuleRoot(VirtualFile current) {
        if (current == null) return null;
        VirtualFile dir = current.isDirectory() ? current : current.getParent();

        while (dir != null) {
            VirtualFile configFile = dir.findChild(CJavaConst.PROJECT_DEVELOP_CONF_FILE);
            if (configFile != null && configFile.exists()) {
                return dir;
            }
            dir = dir.getParent();
        }
        return null;
    }
}