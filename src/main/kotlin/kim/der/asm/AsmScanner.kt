/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm

import kim.der.asm.api.annotation.AsmMixin
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarFile

/**
 * ASM 扫描器
 * 扫描包或 JAR 文件中的 ASM 类
 *
 * @author Dr (dr@der.kim)
 */
object AsmScanner {
    /**
     * 扫描指定包中的所有 ASM 类并注册
     */
    @JvmStatic
    fun scanPackage(packageName: String) {
        scanClassLoader(Thread.currentThread().contextClassLoader, packageName)
    }

    /**
     * 扫描目录中的 ASM 类
     */
    @JvmStatic
    fun scanDirectory(
        directory: File,
        packageName: String,
    ) {
        if (!directory.exists()) return

        val files = directory.listFiles() ?: return

        for (file in files) {
            if (file.isDirectory) {
                scanDirectory(file, "$packageName.${file.name}")
            } else if (file.name.endsWith(".class")) {
                val className = "$packageName.${file.name.substring(0, file.name.length - 6)}"
                try {
                    val clazz = Class.forName(className)
                    if (clazz.isAnnotationPresent(AsmMixin::class.java)) {
                        AsmRegistry.register(clazz)
                    }
                } catch (e: Exception) {
                    // 忽略无法加载的类
                }
            }
        }
    }

    /**
     * 扫描 JAR 文件中的 ASM 类
     */
    @JvmStatic
    fun scanJar(
        jarFile: File,
        packageName: String,
    ) {
        if (!jarFile.exists()) return

        try {
            val jar = JarFile(jarFile)
            val entries = jar.entries()
            val path = packageName.replace('.', '/')

            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name

                if (name.startsWith(path) && name.endsWith(".class")) {
                    val className = name.replace('/', '.').substring(0, name.length - 6)
                    try {
                        val classLoader = URLClassLoader(arrayOf(jarFile.toURI().toURL()))
                        val clazz = classLoader.loadClass(className)
                        if (clazz.isAnnotationPresent(AsmMixin::class.java)) {
                            AsmRegistry.register(clazz)
                        }
                    } catch (e: Exception) {
                        // 忽略无法加载的类
                    }
                }
            }
        } catch (e: Exception) {
            // 忽略错误
        }
    }

    /**
     * 扫描指定类加载器中的所有 ASM 类
     */
    @JvmStatic
    fun scanClassLoader(
        classLoader: ClassLoader,
        packageName: String,
    ) {
        val path = packageName.replace('.', '/')
        try {
            classLoader.getResources(path).iterator().forEach { resource ->
                when (resource.protocol) {
                    "file" -> scanDirectory(File(resource.file), packageName)
                    "jar" -> {
                        val jarPath = resource.path.substring(5, resource.path.indexOf("!"))
                        scanJar(File(jarPath), packageName)
                    }
                }
            }
        } catch (e: Exception) {
            // 忽略错误
        }
    }
}
