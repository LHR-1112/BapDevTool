package com.bap.dev.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

@State(
        name = "com.bap.dev.settings.BapSettingsState",
        storages = @Storage("BapPluginSettings.xml")
)
public class BapSettingsState implements PersistentStateComponent<BapSettingsState> {

    public boolean compileOnPublish = true;
    public boolean autoRefresh = false;

    // 登录历史 (全局)
    public List<LoginProfile> loginHistory = new ArrayList<>();

    // --- 🔴 新增：模块重定向历史 (Map<ModulePath, List<RelocateProfile>>) ---
    public Map<String, List<RelocateProfile>> moduleRelocateHistory = new HashMap<>();

    // 定义重定向配置对象
    public static class RelocateProfile {
        public String uri = "";
        public String user = "";
        public String password = "";
        public String projectUuid = "";
        public String projectName = ""; // 用于显示友好名称
        public String adminTool = "";

        public RelocateProfile() {}

        public RelocateProfile(String uri, String user, String password, String projectUuid, String projectName, String adminTool) {
            this.uri = uri;
            this.user = user;
            this.password = password;
            this.projectUuid = projectUuid;
            this.projectName = projectName;
            this.adminTool = adminTool;
        }

        // 用于去重：同一个服务器下的同一个工程视为重复
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            RelocateProfile that = (RelocateProfile) o;
            return Objects.equals(uri, that.uri) && Objects.equals(projectUuid, that.projectUuid);
        }

        @Override
        public int hashCode() {
            return Objects.hash(uri, projectUuid);
        }

        // 用于在列表显示
        @Override
        public String toString() {
            return projectName + "  Wait-For  " + uri; // 临时格式，UI中会自定义渲染
        }
    }
    // -------------------------------------------------------------

    // ... (LoginProfile 内部类保持不变，省略以节省空间) ...
    public static class LoginProfile {
        public String uri = "";
        public String user = "";
        public String password = "";
        public LoginProfile() {}
        public LoginProfile(String uri, String user, String password) {
            this.uri = uri;
            this.user = user;
            this.password = password;
        }
        @Override public boolean equals(Object o) { /*...*/ return false; }
        @Override public int hashCode() { /*...*/ return 0; }
    }

    public static BapSettingsState getInstance() {
        return ApplicationManager.getApplication().getService(BapSettingsState.class);
    }

    @Override
    public @Nullable BapSettingsState getState() { return this; }

    @Override
    public void loadState(@NotNull BapSettingsState state) {
        XmlSerializerUtil.copyBean(state, this);
    }

    public void addOrUpdateProfile(String uri, String user, String pwd) {
        // ... (保持原有的登录记录逻辑) ...
        if (uri == null || uri.trim().isEmpty()) return;
        loginHistory.removeIf(p -> p.uri.equals(uri.trim()));
        loginHistory.add(0, new LoginProfile(uri.trim(), user, pwd));
        if (loginHistory.size() > 20) loginHistory = new ArrayList<>(loginHistory.subList(0, 20));
    }

    public LoginProfile getProfile(String uri) {
        for (LoginProfile p : loginHistory) {
            if (p.uri.equals(uri)) return p;
        }
        return null;
    }

    // --- 🔴 新增：添加重定向历史 ---
    public void addRelocateHistory(String modulePath, RelocateProfile profile) {
        List<RelocateProfile> list = moduleRelocateHistory.computeIfAbsent(modulePath, k -> new ArrayList<>());

        // 去重并置顶
        list.remove(profile);
        list.add(0, profile);

        // 每个模块最多保留 10 条历史
        if (list.size() > 10) {
            moduleRelocateHistory.put(modulePath, new ArrayList<>(list.subList(0, 10)));
        }
    }

    public List<RelocateProfile> getRelocateHistory(String modulePath) {
        return moduleRelocateHistory.getOrDefault(modulePath, Collections.emptyList());
    }
}