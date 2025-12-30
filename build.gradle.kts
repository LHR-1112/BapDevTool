plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "com.bap.dev"
version = "1.2.3"

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
            
            <h2>🔄 更新说明</h2>
            <p>覆盖安装即可更新。</p>
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
            <h3>v1.2.4</h3>
            <ul>
                <li><b>特性修改</b>：commit的提示窗口，自动聚焦到commit按钮，而不是提交信息窗口</li>
                <li><b>特性修改</b>：BapChanges在commit/update等操作之后，自动聚焦到项目节点</li>
                <li><b>特性修改</b>：代码Debug面板增加Rerun，自动换行，导出等按钮</li>
                <li><b>bug修复</b>：修复密码错误、无法连接时，刷新项目无提示</li>
            </ul>
            
            <h3>v1.2.3</h3>
            <ul>
                <li><b>新特性</b>：工程relocate的历史记录，增加删除功能</li>
                <li><b>bug</b>：更新依赖jar包：tcmcat-bap.jar</li>
                <li><b>新特性</b>：下载工程的时候，加上进度条提醒和网速显示</li>
                <li><b>新特性</b>：下载工程的时候，添加终止下载的能力</li>
                <li><b>新特性</b>：拉取工程时提供选项：新建一个项目、作为当前项目的一个模块</li>
                <li><b>新特性</b>：在首页添加“下载工程”按钮，方便新用户使用</li>
                <li><b>新特性</b>：新增能力：云端调试（只要本地的代码类有Main方法就可以运行，通过Trace输出进行调试，参考管理工具中的JavaTool，无需发布插件）</li>
            </ul>
            
            <h3>v1.2.2</h3>
            <ul>
                <li><b>新特性</b>：BapChanges界面，增加一键收缩、展开、定位按钮</li>
                <li><b>特性修改</b>：BapChanges界面，双击文件直接弹出云端与本地的对比</li>
                <li><b>bug修复</b>：蓝A的资源文件，点击update无法删除</li>
                <li><b>新特性</b>：commit的时候，提示目标服务器与工程信息</li>
                <li><b>特性修改</b>：修改设置界面中，颜色修改的属性名称</li>
                <li><b>特性修改</b>：丰富BapChangesTreePanel的右键菜单，与项目树的保持一致</li>
                <li><b>特性修改</b>：将部分操作获取BapClient的方法改为通过BapConnectionManager获取长连接</li>
            </ul>
            
            <h3>v1.2.1</h3>
            <ul>
                <li><b>新特性</b>：设置中可更改文件状态的颜色</li>
            </ul>
            
            <h3>v1.2</h3>
            <ul>
                <li><b>bug修复</b>：去除没用到的依赖jar</li>
                <li><b>bug修复</b>：重新编排右键菜单选项的顺序</li>
                <li><b>bug修复</b>：修复管理工具无法连接"wss://"类型的地址</li>
                <li><b>新特性</b>：UpdateAll</li>
                <li><b>新特性</b>：整合commit时的文件列表提示与comment填写</li>
                <li><b>新特性</b>：工程重定向的历史记录</li>
                <li><b>新特性</b>：插件版本更新提醒</li>
                <li><b>新特性</b>：新增文件状态颜色的修改</li>
            </ul>
            
            <h3>v1.1.2</h3>
            <ul>
                <li><b>新特性</b>：commit时的提交信息</li>
                <li><b>bug修复</b>：解决无法记录提交历史记录的问题</li>
            </ul>
            
            <h3>v1.1.1</h3>
            <ul>
                <li><b>bug修复</b>：支援至2022.3.2版本</li>
                <li><b>bug修复</b>：解决管理工具无法显示的问题</li>
            </ul>
            
            <h3>v1.1</h3>
            <ul>
                <li><b>新特性</b>：版本管理(历史查询、回滚、对比)</li>
                <li><b>新特性</b>：自动刷新文件状态设置</li>
                <li><b>bug修复</b>：修复发布自动编译失效问题</li>
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
