package com.bap.dev.handler;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.libraries.LibraryTable;
import com.intellij.openapi.vfs.JarFileSystem;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.File;

public class LibConfigurator {

    public static void configureLibraries(Project project, VirtualFile moduleRoot) {
        ApplicationManager.getApplication().invokeLater(() -> {
            WriteAction.run(() -> {
                try {
                    // 1. 找到对应的模块
                    // 假设 moduleRoot 就是 Module 的 Content Root
                    Module module = null;
                    for (Module m : ModuleManager.getInstance(project).getModules()) {
                        // 简单匹配：如果模块文件在这个目录下
                        // 更严谨的做法是通过 ProjectFileIndex 查找
                        if (moduleRoot.equals(ModuleRootManager.getInstance(m).getContentRoots()[0])) {
                            module = m;
                            break;
                        }
                    }

                    if (module == null) {
                        // Fallback: 拿第一个模块（简单粗暴，视项目结构而定）
                        module = ModuleManager.getInstance(project).getModules()[0];
                    }

                    ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
                    ModifiableRootModel model = rootManager.getModifiableModel();

                    try {
                        // 2. 刷新 VFS，确保能看到刚下载的 jar
                        moduleRoot.refresh(false, true);

                        // 3. 配置三个核心库目录
                        addLibrary(model, moduleRoot, "lib/platform");
                        addLibrary(model, moduleRoot, "lib/plugin");
                        addLibrary(model, moduleRoot, "lib/project");

                        // 4. 提交模型
                        model.commit();

                    } catch (Exception e) {
                        if (!model.isDisposed()) model.dispose();
                        e.printStackTrace();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        });
    }

    private static void addLibrary(ModifiableRootModel model, VirtualFile root, String relativePath) {
        VirtualFile libDir = root.findFileByRelativePath(relativePath);
        if (libDir == null || !libDir.exists()) return;

        LibraryTable libraryTable = model.getModuleLibraryTable();

        // 检查是否已存在，存在则移除旧的重新加，或者查找更新
        // 这里采用“按目录名创建库”的策略
        String libName = "Lib: " + relativePath;
        Library existingLib = libraryTable.getLibraryByName(libName);
        if (existingLib != null) {
            libraryTable.removeLibrary(existingLib);
        }

        Library library = libraryTable.createLibrary(libName);
        Library.ModifiableModel libModel = library.getModifiableModel();

        VirtualFile[] children = libDir.getChildren();
        boolean hasJar = false;
        for (VirtualFile file : children) {
            if (!file.isDirectory() && (file.getExtension().equalsIgnoreCase("jar") || file.getExtension().equalsIgnoreCase("zip"))) {
                String jarPath = file.getPath() + "!/";
                VirtualFile jarRoot = JarFileSystem.getInstance().refreshAndFindFileByPath(jarPath);
                if (jarRoot != null) {
                    libModel.addRoot(jarRoot, OrderRootType.CLASSES);
                    hasJar = true;
                }
            }
        }

        libModel.commit();
    }
}