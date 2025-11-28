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

    // --- 🔴 新增：自动刷新开关 ---
    public boolean autoRefresh = false; // 默认为 false
    // ----------------------------

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

    public void addUriToHistory(String uri) {
        if (uri == null || uri.trim().isEmpty()) return;
        uriHistory.remove(uri);
        uriHistory.add(0, uri);
        if (uriHistory.size() > 20) {
            uriHistory = new ArrayList<>(uriHistory.subList(0, 20));
        }
    }
}