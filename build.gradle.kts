plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "com.bap.dev"
version = "1.1.2"

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

        changeNotes = """
      Initial version
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
