package com.bap.dev.service;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service(Service.Level.PROJECT)
public final class BapFileStatusService {
    // 使用 ConcurrentHashMap 保证线程安全
    private final Map<String, BapFileStatus> fileStatuses = new ConcurrentHashMap<>();

    public static BapFileStatusService getInstance(Project project) {
        return project.getService(BapFileStatusService.class);
    }

    public void setStatus(VirtualFile file, BapFileStatus status) {
        if (file != null) {
            if (status == BapFileStatus.NORMAL) {
                fileStatuses.remove(file.getPath());
            } else {
                fileStatuses.put(file.getPath(), status);
            }
        }
    }

    public BapFileStatus getStatus(VirtualFile file) {
        if (file == null) return BapFileStatus.NORMAL;
        return fileStatuses.getOrDefault(file.getPath(), BapFileStatus.NORMAL);
    }

    public void clearAll() {
        fileStatuses.clear();
    }

    // 在 BapFileStatusService 类中添加：
    public Map<String, BapFileStatus> getAllStatuses() {
        // 返回不可修改的视图，防止外部直接修改 Map
        return java.util.Collections.unmodifiableMap(fileStatuses);
    }
}