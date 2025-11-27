package com.bap.dev.listener;

import com.intellij.util.messages.Topic;

public interface BapChangesNotifier {
    // 定义消息主题
    Topic<BapChangesNotifier> TOPIC = Topic.create("Bap Changes Notifier", BapChangesNotifier.class);

    // 当 Bap 文件状态发生变化（刷新、提交、更新）后调用
    void onChangesUpdated();
}