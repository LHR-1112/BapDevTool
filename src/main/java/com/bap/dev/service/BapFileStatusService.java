package com.bap.dev.service;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service(Service.Level.PROJECT)
public final class BapFileStatusService {
    // 使用 ConcurrentHashMap 保证线程安全，Key 是文件的绝对路径
    private final Map<String, BapFileStatus> fileStatuses = new ConcurrentHashMap<>();

    public static BapFileStatusService getInstance(Project project) {
        return project.getService(BapFileStatusService.class);
    }

    // --- 🔴 修改：重载 setStatus 方法 ---

    /**
     * 针对存在的物理文件设置状态
     */
    public void setStatus(VirtualFile file, BapFileStatus status) {
        if (file != null) {
            setStatus(file.getPath(), status);
        }
    }

    /**
     * 🔴 新增：针对路径字符串设置状态
     * 专门用于标记 "DELETED_LOCALLY" 这种本地文件不存在的情况
     */
    public void setStatus(String path, BapFileStatus status) {
        if (path != null) {
            if (status == BapFileStatus.NORMAL) {
                fileStatuses.remove(path);
            } else {
                fileStatuses.put(path, status);
            }
        }
    }

    // --- 🔴 修改：重载 getStatus 方法 ---

    public BapFileStatus getStatus(VirtualFile file) {
        if (file == null) return BapFileStatus.NORMAL;
        return getStatus(file.getPath());
    }

    public BapFileStatus getStatus(String path) {
        if (path == null) return BapFileStatus.NORMAL;
        return fileStatuses.getOrDefault(path, BapFileStatus.NORMAL);
    }

    public void clearAll() {
        fileStatuses.clear();
    }

    public Map<String, BapFileStatus> getAllStatuses() {
        // 返回不可修改的视图，防止外部直接修改 Map
        return Collections.unmodifiableMap(fileStatuses);
    }
}