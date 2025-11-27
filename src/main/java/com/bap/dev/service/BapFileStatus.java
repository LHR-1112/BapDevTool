package com.bap.dev.service;

public enum BapFileStatus {
    NORMAL,           // 正常 (一致)
    MODIFIED,         // 修改 (本地与云端MD5不同) -> 黄色 M
    ADDED,            // 新增 (本地有，云端无) -> 蓝色 A
    DELETED_LOCALLY   // 缺失 (云端有，本地无) -> 红色 D
}