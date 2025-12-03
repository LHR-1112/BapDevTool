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
import java.util.Objects;

@State(
        name = "com.bap.dev.settings.BapSettingsState",
        storages = @Storage("BapPluginSettings.xml")
)
public class BapSettingsState implements PersistentStateComponent<BapSettingsState> {

    public boolean compileOnPublish = true;
    public boolean autoRefresh = false;

    // --- 🔴 修改：使用对象列表替代简单的 String 列表 ---
    public List<LoginProfile> loginHistory = new ArrayList<>();

    // 定义静态内部类用于存储单条配置 (必须是 public static 才能被 IDEA 序列化)
    public static class LoginProfile {
        public String uri = "";
        public String user = "";
        public String password = ""; // 注意：生产环境建议使用 CredentialStore 存储密码，此处为简化存入 XML

        // 无参构造函数用于序列化
        public LoginProfile() {}

        public LoginProfile(String uri, String user, String password) {
            this.uri = uri;
            this.user = user;
            this.password = password;
        }

        // 重写 equals 以便列表操作
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            LoginProfile that = (LoginProfile) o;
            return Objects.equals(uri, that.uri);
        }

        @Override
        public int hashCode() {
            return Objects.hash(uri);
        }
    }
    // ----------------------------------------------------

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
     * 🔴 新增：添加或更新登录配置 (置顶并去重)
     */
    public void addOrUpdateProfile(String uri, String user, String pwd) {
        if (uri == null || uri.trim().isEmpty()) return;

        // 创建新对象
        LoginProfile newProfile = new LoginProfile(uri.trim(), user, pwd);

        // 如果已存在该 URI，先移除旧的
        loginHistory.removeIf(p -> p.uri.equals(uri.trim()));

        // 添加到头部
        loginHistory.add(0, newProfile);

        // 限制数量 (例如保留最近20条)
        if (loginHistory.size() > 20) {
            loginHistory = new ArrayList<>(loginHistory.subList(0, 20));
        }
    }

    /**
     * 🔴 新增：根据 URI 获取对应的用户名密码
     */
    public LoginProfile getProfile(String uri) {
        for (LoginProfile p : loginHistory) {
            if (p.uri.equals(uri)) {
                return p;
            }
        }
        return null;
    }
}