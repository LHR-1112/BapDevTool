package com.bap.dev.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.ui.JBColor;
import com.intellij.util.xmlb.XmlSerializerUtil;
import com.intellij.util.xmlb.annotations.Transient; // 必须引入这个
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.util.*;
import java.util.List;

@State(
        name = "com.bap.dev.settings.BapSettingsState",
        storages = @Storage("BapPluginSettings.xml")
)
public class BapSettingsState implements PersistentStateComponent<BapSettingsState> {

    public boolean compileOnPublish = true;
    public boolean autoRefresh = false;

    // 登录历史 (全局)
    public List<LoginProfile> loginHistory = new ArrayList<>();

    // 模块重定向历史 (Map<ModulePath, List<RelocateProfile>>)
    public Map<String, List<RelocateProfile>> moduleRelocateHistory = new HashMap<>();

    // 颜色配置 (存储 int RGB) - 这些字段会被正常序列化
    public int modifiedColor = JBColor.YELLOW.getRGB();
    public int addedColor = JBColor.BLUE.getRGB();
    public int deletedColor = JBColor.RED.getRGB();

    // --- 🔴 核心修复：添加 @Transient 注解 ---
    // 告诉 IDEA 不要序列化这些方法，它们只是 UI 辅助用的
    @Transient
    public Color getModifiedColorObj() { return new Color(modifiedColor); }

    @Transient
    public Color getAddedColorObj() { return new Color(addedColor); }

    @Transient
    public Color getDeletedColorObj() { return new Color(deletedColor); }
    // ---------------------------------------

    public void setModifiedColorObj(Color c) { modifiedColor = c.getRGB(); }
    public void setAddedColorObj(Color c) { addedColor = c.getRGB(); }
    public void setDeletedColorObj(Color c) { deletedColor = c.getRGB(); }


    // --- 内部类定义 (保持不变) ---
    public static class RelocateProfile {
        public String uri = "";
        public String user = "";
        public String password = "";
        public String projectUuid = "";
        public String projectName = "";
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

        @Override
        public String toString() {
            return projectName + "  Wait-For  " + uri;
        }
    }

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
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            LoginProfile that = (LoginProfile) o;
            return Objects.equals(uri, that.uri);
        }
        @Override
        public int hashCode() { return Objects.hash(uri); }
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

    public void addRelocateHistory(String modulePath, RelocateProfile profile) {
        List<RelocateProfile> list = moduleRelocateHistory.computeIfAbsent(modulePath, k -> new ArrayList<>());
        list.remove(profile);
        list.add(0, profile);
        if (list.size() > 10) {
            moduleRelocateHistory.put(modulePath, new ArrayList<>(list.subList(0, 10)));
        }
    }

    public List<RelocateProfile> getRelocateHistory(String modulePath) {
        return moduleRelocateHistory.getOrDefault(modulePath, Collections.emptyList());
    }
}