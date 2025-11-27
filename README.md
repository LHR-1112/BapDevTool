# BapDevTool——IntelliJ IDEA 云开发插件

基于原有 Eclipse 云开发插件重构的 **IntelliJ IDEA** 版本。

[![GitHub release (latest by date)](https://img.shields.io/github/v/release/your-repo/bapdevtools?style=for-the-badge)](https://github.com/LHR-1112/BapDevTool/releases)
[![IntelliJ Platform Plugin](https://img.shields.io/jetbrains/plugin/d/your-plugin-id?style=for-the-badge)](https://plugins.jetbrains.com/plugin/com.bap.dev.BapDevPlugin)

---

## ⬇️ 下载链接

您可以通过以下网盘链接下载最新的插件 JAR 包：

* **百度网盘**: `http://...` (请替换为实际链接)
* **夸克网盘**: `http://...` (请替换为实际链接)

---

## 📥 安装指南

您可以按照以下步骤从本地 JAR 包安装插件：

1.  **下载**最新的插件 JAR 包。
2.  进入 **IntelliJ IDEA** 的 `Settings` (设置) -> `Plugins` (插件)。
3.  点击插件页面右上角的 **齿轮/设置** 图标。
4.  选择 **“Install Plugin from Disk...”** (从磁盘安装插件)。
5.  选择下载好的 JAR 包进行安装。
6.  安装完成后，**重启** IntelliJ IDEA 即可使用。

---

## 🛠️ 开发环境（仅供参考）

本插件在以下环境中进行过测试：

* **系统**: macOS X
* **CPU**: Apple Silicon M4
* **IDE**: IntelliJ IDEA 2025.1.3 (Ultimate Edition)
* **JDK**: Oracle OpenJDK 24.0.1 - aarch64

> ⚠️ **注意**: 当前版本仅在上述环境中进行过测试。若在其他环境中使用，可能会遇到兼容性问题。

---

## 🔄 更新插件

更新过程与安装类似，只需覆盖安装即可：

1.  **下载**最新的插件 JAR 包。
2.  按照 **安装指南** 的步骤进行安装（覆盖旧版本）。

---

## ⚠️ 已知问题与局限

* **管理工具**：暂时沿用原 Eclipse 版本的页面和逻辑。
* **文件状态颜色冲突**：当项目已配置 **Git** 时，本插件提供的文件状态颜色提示可能会与 Git 的颜色提示发生冲突。

---

## 📜 变更日志 (ChangeLog)

### v1.0 (初始发布)

* **重构**：使用 IntelliJ IDEA 插件开发环境，将原 Eclipse 上的云开发插件进行彻底重构。
* **新特性**：新增与 Git 类似的文件状态颜色提示功能。

---

## 📧 BUG 反馈与联系方式

如果您在使用过程中遇到任何问题或 BUG，欢迎通过以下渠道反馈：

* **邮箱**: 2991747768@qq.com
