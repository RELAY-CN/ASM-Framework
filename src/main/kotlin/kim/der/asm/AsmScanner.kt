/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm

import kim.der.asm.api.annotation.AsmMixin
import java.io.File
import java.net.JarURLConnection
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
    ) = scanDirectory(directory, packageName, Thread.currentThread().contextClassLoader)

    /**
     * 扫描目录中的 ASM 类
     */
    private fun scanDirectory(
        directory: File,
        packageName: String,
        classLoader: ClassLoader,
    ) {
        if (!directory.exists()) return

        val files = directory.listFiles() ?: return

        for (file in files) {
            if (file.isDirectory) {
                scanDirectory(file, "$packageName.${file.name}", classLoader)
            } else if (file.name.endsWith(".class")) {
                val className = "$packageName.${file.name.substring(0, file.name.length - 6)}"
                registerAsmClass(className, classLoader)
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
    ) = scanJar(jarFile, packageName, Thread.currentThread().contextClassLoader)

    /**
     * 扫描 JAR 文件中的 ASM 类
     */
    private fun scanJar(
        jarFile: File,
        packageName: String,
        parentClassLoader: ClassLoader,
    ) {
        if (!jarFile.exists()) return

        try {
            val packagePath = packageName.replace('.', '/')
            val packagePrefix = if (packagePath.isEmpty()) "" else "$packagePath/"

            JarFile(jarFile).use { jar ->
                URLClassLoader(arrayOf(jarFile.toURI().toURL()), parentClassLoader).use { classLoader ->
                    val entries = jar.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val name = entry.name

                        if (!entry.isDirectory && name.startsWith(packagePrefix) && name.endsWith(".class")) {
                            val className = name.substring(0, name.length - 6).replace('/', '.')
                            registerAsmClass(className, classLoader)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // 忽略错误
        } catch (_: LinkageError) {
            // 忽略类链接错误
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
                    "file" -> scanDirectory(File(resource.toURI()), packageName, classLoader)
                    "jar" -> {
                        val connection = resource.openConnection()
                        if (connection is JarURLConnection) {
                            scanJar(File(connection.jarFileURL.toURI()), packageName, classLoader)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // 忽略错误
        }
    }

    /**
     * 加载并注册带有 @AsmMixin 注解的类
     *
     * 使用延迟初始化加载，避免扫描阶段执行类初始化逻辑
     */
    private fun registerAsmClass(
        className: String,
        classLoader: ClassLoader,
    ) {
        try {
            val clazz = Class.forName(className, false, classLoader)
            if (clazz.isAnnotationPresent(AsmMixin::class.java)) {
                AsmRegistry.register(clazz)
            }
        } catch (_: Exception) {
            // 忽略无法加载的类
        } catch (_: LinkageError) {
            // 忽略类链接错误
        }
    }
}
