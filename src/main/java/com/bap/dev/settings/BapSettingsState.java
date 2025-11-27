package com.bap.dev.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

@State(
        name = "com.bap.dev.settings.BapSettingsState",
        storages = @Storage("BapPluginSettings.xml")
)
public class BapSettingsState implements PersistentStateComponent<BapSettingsState> {

    public boolean compileOnPublish = true;

    // 新增：URI 历史记录列表
    public List<String> uriHistory = new ArrayList<>();

    public static BapSettingsState getInstance() {
        return ApplicationManager.getApplication().getService(BapSettingsState.class);
    }

    @Override
    public @Nullable BapSettingsState getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull BapSettingsState state) {
        XmlSerializerUtil.copyBean(state, this);
    }

    /**
     * 辅助方法：添加 URI 到历史记录 (置顶 + 去重 + 限制数量)
     */
    public void addUriToHistory(String uri) {
        if (uri == null || uri.trim().isEmpty()) return;

        uriHistory.remove(uri); // 如果已存在，先移除
        uriHistory.add(0, uri); // 加到最前面

        if (uriHistory.size() > 20) { // 限制保留最近 20 条
            uriHistory = new ArrayList<>(uriHistory.subList(0, 20));
        }
    }
}