# BapDevTool——IntelliJ IDEA 云开发插件

基于原有 Eclipse 云开发插件重构的 **IntelliJ IDEA** 版本。

[![GitHub release (latest by date)](https://img.shields.io/github/v/release/LHR-1112/BapDevTool?style=for-the-badge)](https://github.com/LHR-1112/BapDevTool/releases)

---

## 📥 安装指南

> ⚠️ **前置条件**
>
> - IntelliJ IDEA 版本必须 **≥ IU-223.\***
> - 当前插件尚未上架 JetBrains 官方插件市场

插件支持 **两种安装 / 更新方式**，请根据你的使用场景选择。

---

### ✅ 安装方式一：通过 GitHub 下载本地安装
#### 安装步骤

1. 前往 GitHub Releases 页面，下载 **最新版本插件压缩包**  
   [https://github.com/LHR-1112/BapDevTool/releases](https://github.com/LHR-1112/BapDevTool/releases)
2. 打开 **IntelliJ IDEA**
3. 进入  
   `Settings / Preferences` → `Plugins`
4. 点击右上角 **⚙️（齿轮）**
5. 选择 **Install Plugin from Disk...**
6. 选择下载好的插件压缩包
7. 安装完成后 **重启 IntelliJ IDEA**

---

### ✅ 安装方式二：通过私有插件仓库安装
#### 安装步骤

1. 打开 **IntelliJ IDEA**
2. 进入  
   `Settings / Preferences` → `Plugins`
3. 点击右上角 **⚙️（齿轮）**
4. 选择 **Manage Plugin Repositories...**
5. 添加 **Bap 私有插件仓库地址**  
   [https://lhr-1112.github.io/BapDevTool/plugins.xml](https://lhr-1112.github.io/BapDevTool/plugins.xml)
6. 确认并保存设置
7. 在插件市场中搜索 **BapDevPlugin** 并安装

---

## 🛠️ 开发环境（仅供参考）

* **系统**: MacOS X
* **CPU**: Apple Silicon M4
* **IDE**: IntelliJ IDEA 2025.3.1 (Ultimate Edition)
* **JDK**: Oracle OpenJDK 24.0.1 - aarch64

> ⚠️ **注意**: 当前版本仅在上述环境中进行过测试。若在其他环境中使用，可能会遇到兼容性问题。

---

## ⚠️ 已知问题与局限

* **管理工具**：暂时沿用原 Eclipse 版本的页面和逻辑。
* **文件状态**：外部写入的文件，偶尔会出现识别不到文件状态的问题。
* **文件状态**：开启了自动刷新文件状态，一直刷新会非常卡

---

## 📜 变更日志

完整的版本变更历史见 **[CHANGELOG.md](CHANGELOG.md)**。

---

## 📧 BUG 反馈与联系方式

如果您在使用过程中遇到任何问题或 BUG，欢迎通过以下渠道反馈：

* **邮箱**: 2991747768@qq.com
* **GitHub Issues**: [点击前往](https://github.com/LHR-1112/BapDevTool/issues)
