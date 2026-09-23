plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "com.bap.dev"
version = "1.3.6"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
//        create("IC", "2025.1")
        create("IC", "2022.3.2") // 建议使用 2022.3 系列的一个具体版本，例如 2022.3.4
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // --- ✅ 关键修改：添加这一行 ---
        // 这告诉 Gradle 你的插件编译时需要用到 IDEA 自带的 Java 插件的类
        bundledPlugin("com.intellij.java")
    }

    implementation(fileTree("lib") {
        include("**/*.jar")
    })
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
//            sinceBuild = "251"
            // 1. 兼容下限保持 223，确保 2022.3 版本能安装
            sinceBuild = "223"

            // 2. 关键修改：设置一个兼容上限，包含您的 251.x 版本
            // '252.*' 表示兼容到 2025.2 版本，即包含 251.x
            // 如果您想要更久远的兼容性，可以设置更高的版本号，如 '999.*'
            untilBuild = "999.*"
        }

        // 1. Description: 包含简介、安装、环境、已知问题、联系方式
        description = """
            <p>基于原有 Eclipse 云开发插件重构的 <b>IntelliJ IDEA</b> 版本。</p>
            <br>
            
            <h2>📥 安装指南</h2>
            <p><b>注意</b>！您的 Idea 版本必须 >=IU-223.XXX 才能安装此插件。</p>
            <ol>
                <li><b>下载</b>最新的插件压缩包。</li>
                <li>进入 <b>Settings</b> -> <b>Plugins</b>。</li>
                <li>点击齿轮图标，选择 <b>Install Plugin from Disk...</b>。</li>
                <li>选择压缩包安装并<b>重启</b> IDE。</li>
            </ol>
            <br>
            
            <h2>⚠️ 已知问题</h2>
            <ul>
                <li><b>管理工具</b>：沿用原 Eclipse 逻辑。</li>
                <li><b>颜色冲突</b>：可能与 Git 文件状态颜色冲突。</li>
            </ul>
            <br>
        """.trimIndent()

        // 2. ChangeNotes: 专门放变更日志 (通常只放最新几个版本或全部)
        changeNotes = """
            <h3>v1.3.6</h3>
            <ul>
                <li><b>新特性</b>：更新依赖前先做预检并弹窗提示将要更新的内容，下载完成后再给出精确数量二次确认，确认后才覆盖工程文件；任一环节取消都不会改动磁盘</li>
                <li><b>新特性</b>：更新完成的通知中显示本次实际更新的 Jar 包数量（按平台 / 插件 / 项目 / DAO 分类）</li>
                <li><b>特性修改</b>：更新流程重构为「扫描本地指纹 → 预检 → 下载到内存/临时区 → 用户确认 → 落盘」，下载阶段不再直接写工程目录</li>
                <li><b>bug修复</b>：修复开源源码包 openSource/src.zip 的 MD5 校验路径写错，导致每次更新都无条件全量重新下载约 9MB 源码包的问题</li>
                <li><b>bug修复</b>：修复平台依赖包的解压被包在"待删除文件列表"判断内，服务端未返回待删列表时已下载的增量包会被静默丢弃的问题</li>
            </ul>

            <h3>v1.3.5</h3>
            <ul>
                <li><b>新特性</b>：更新（Update）操作增加二次确认弹窗，执行前按 [M 覆盖] / [A 删除] / [D 还原] 列出受影响文件清单</li>
                <li><b>新特性</b>：新增设置"更新代码前显示确认弹窗"（默认开启）</li>
                <li><b>特性修改</b>：Update All（拉取全部）的确认弹窗改为受上述设置控制</li>
            </ul>

            <h3>v1.3.4</h3>
            <ul>
                <li><b>新特性</b>：新增面板DTO同步功能，通过 RPC 批量拉取远程面板DTO源码到本地</li>
                <li><b>新特性</b>：左侧包目录树 + 右侧嵌入式 Diff 对比面板，支持新增/修改/删除三种状态标记</li>
                <li><b>新特性</b>：新增规则函数注册功能，支持类级别（注册全部方法）和方法级别（注册单个方法）</li>
                <li><b>新特性</b>：新增独立"切换业务域"菜单项</li>
            </ul>

            <h3>v1.3.3</h3>
            <ul>
                <li><b>bug修复</b>：修复启动管理工具时，命令行会拼接所有依赖jar包的绝对路径，导致在Windows上CreateProcess报206的问题</li>
            </ul>
            
            <h3>v1.3.2</h3>
            <ul>
                <li><b>特性修改</b>：一键更新弃用原来的接口，改为私仓更新</li>
                <li><b>特性修改</b>：修改 BapChangesTreePanel 文件节点的双击逻辑</li>
                <li><b>特性修改</b>：工程重定向：修改新增连接的逻辑；增加属性：备注；</li>
                <li><b>特性修改</b>：新增设置：在项目树中显示文件状态（处理与git的显示冲突）</li>
                <li><b>bug修复</b>：无法删除src/src/目录下的文件</li>
            </ul>
            
            <h3>v1.3.1</h3>
            <ul>
                <li><b>新特性</b>：新增设置：commit是否需要确认</li>
                <li><b>新特性</b>：新增两个动作：CommitFileAndPublishAction、CommitAllAndPublishAction</li>
                <li><b>特性修改</b>：更改右键菜单，BapChangesTreePanel的按钮图标及布局</li>
                <li><b>新特性</b>：BapChangesTreePanel文件增加包路径显示，不再平铺</li>
                <li><b>新特性</b>：BapChangesTreePanel添加switch：扁平化/树形展示包路径</li>
            </ul>
            
            <h3>v1.3</h3>
            <ul>
                <li><b>新特性</b>：BapChanges界面，在工程根节点上增加一些快捷操作</li>
                <li><b>新特性</b>：添加一键更新插件并重启的能力，同时保留github下载的入口</li>
                <li><b>新特性</b>：修改项目历史的查询逻辑，增加资源文件历史的展示</li>
                <li><b>新特性</b>：修改文件历史的查询逻辑，增加资源文件历史的展示</li>
                <li><b>bug修复</b>：修复设置界面点击检查更新无反应的问题</li>
                <li><b>新特性</b>：适配i18n</li>
                <li><b>新特性</b>：BapChanges界面，Modified/Added/Deleted三个节点增加右键菜单</li>
                <li><b>新特性</b>：文件状态检测过滤掉“.DS_Store”</li>
                <li><b>特性修改</b>：去除顶栏Bap目录和下载项目、设置选项的图标</li>
                <li><b>特性修改</b>：红D文件原来的逻辑是在本地的文件系统中添加一个空文件作为占位符，改成使用内存中的 LightVirtualFile 来代替物理文件进行展示，不再在本地生成占位缓存文件</li>
                <li><b>特性修改</b>：更新日志的打印方式</li>
                <li><b>bug修复</b>：修复在资源文件根目录的文件commit后会套上一个同名文件夹，导致Idea报错崩溃的问题</li>
            </ul>
        """.trimIndent()
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
//        sourceCompatibility = "21"
//        targetCompatibility = "21"
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
//        kotlinOptions.jvmTarget = "21"
        kotlinOptions.jvmTarget = "17"
    }
}

tasks {
    runIde {
        // 强制沙箱环境使用英文 (en_US)
        jvmArgs = listOf("-Duser.language=en", "-Duser.region=US")

        // 如果想测试中文环境，请使用：
        // jvmArgs = listOf("-Duser.language=zh", "-Duser.region=CN")
    }
}