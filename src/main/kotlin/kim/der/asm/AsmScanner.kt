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
 * ASM 扫描结果。
 */
data class AsmScanResult(
    val registeredClasses: List<String> = emptyList(),
    val skippedClasses: List<String> = emptyList(),
    val failures: List<AsmScanFailure> = emptyList(),
) {
    fun merge(other: AsmScanResult): AsmScanResult =
        AsmScanResult(
            registeredClasses = registeredClasses + other.registeredClasses,
            skippedClasses = skippedClasses + other.skippedClasses,
            failures = failures + other.failures,
        )
}

/**
 * ASM 扫描失败条目。
 */
data class AsmScanFailure(
    val className: String,
    val reason: String,
)

/**
 * ASM 扫描器
 * 扫描包或 JAR 文件中的 ASM 类
 *
 * @author Dr (dr@der.kim)
 */
object AsmScanner {
    /**
     * 扫描指定包中的所有 ASM 类并注册。
     */
    @JvmStatic
    fun scanPackage(packageName: String) {
        scanPackageWithResult(packageName)
    }

    /**
     * 扫描指定包中的所有 ASM 类并返回诊断结果。
     */
    @JvmStatic
    fun scanPackageWithResult(packageName: String): AsmScanResult =
        scanClassLoaderWithResult(Thread.currentThread().contextClassLoader, packageName)

    /**
     * 扫描目录中的 ASM 类。
     */
    @JvmStatic
    fun scanDirectory(
        directory: File,
        packageName: String,
    ) {
        scanDirectoryWithResult(directory, packageName)
    }

    /**
     * 扫描目录中的 ASM 类并返回诊断结果。
     */
    @JvmStatic
    fun scanDirectoryWithResult(
        directory: File,
        packageName: String,
    ): AsmScanResult = scanDirectory(directory, packageName, Thread.currentThread().contextClassLoader)

    /**
     * 扫描目录中的 ASM 类。
     */
    private fun scanDirectory(
        directory: File,
        packageName: String,
        classLoader: ClassLoader,
    ): AsmScanResult {
        if (!directory.exists()) return AsmScanResult()

        val files = directory.listFiles() ?: return AsmScanResult()
        var result = AsmScanResult()

        for (file in files) {
            result =
                result.merge(
                    if (file.isDirectory) {
                        scanDirectory(file, "$packageName.${file.name}", classLoader)
                    } else if (file.name.endsWith(".class")) {
                        val className = "$packageName.${file.name.substring(0, file.name.length - 6)}"
                        registerAsmClass(className, classLoader)
                    } else {
                        AsmScanResult()
                    },
                )
        }

        return result
    }

    /**
     * 扫描 JAR 文件中的 ASM 类。
     */
    @JvmStatic
    fun scanJar(
        jarFile: File,
        packageName: String,
    ) {
        scanJarWithResult(jarFile, packageName)
    }

    /**
     * 扫描 JAR 文件中的 ASM 类并返回诊断结果。
     */
    @JvmStatic
    fun scanJarWithResult(
        jarFile: File,
        packageName: String,
    ): AsmScanResult = scanJar(jarFile, packageName, Thread.currentThread().contextClassLoader)

    /**
     * 扫描 JAR 文件中的 ASM 类。
     */
    private fun scanJar(
        jarFile: File,
        packageName: String,
        parentClassLoader: ClassLoader,
    ): AsmScanResult {
        if (!jarFile.exists()) return AsmScanResult()

        return try {
            val packagePath = packageName.replace('.', '/')
            val packagePrefix = if (packagePath.isEmpty()) "" else "$packagePath/"
            var result = AsmScanResult()

            JarFile(jarFile).use { jar ->
                URLClassLoader(arrayOf(jarFile.toURI().toURL()), parentClassLoader).use { classLoader ->
                    val entries = jar.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val name = entry.name

                        if (!entry.isDirectory && name.startsWith(packagePrefix) && name.endsWith(".class")) {
                            val className = name.substring(0, name.length - 6).replace('/', '.')
                            result = result.merge(registerAsmClass(className, classLoader))
                        }
                    }
                }
            }

            result
        } catch (throwable: Throwable) {
            AsmScanResult(failures = listOf(AsmScanFailure(jarFile.absolutePath, throwable.message ?: throwable.javaClass.name)))
        }
    }

    /**
     * 扫描指定类加载器中的所有 ASM 类。
     */
    @JvmStatic
    fun scanClassLoader(
        classLoader: ClassLoader,
        packageName: String,
    ) {
        scanClassLoaderWithResult(classLoader, packageName)
    }

    /**
     * 扫描指定类加载器中的所有 ASM 类并返回诊断结果。
     */
    @JvmStatic
    fun scanClassLoaderWithResult(
        classLoader: ClassLoader,
        packageName: String,
    ): AsmScanResult {
        val path = packageName.replace('.', '/')
        return try {
            var result = AsmScanResult()
            classLoader.getResources(path).iterator().forEach { resource ->
                result =
                    result.merge(
                        when (resource.protocol) {
                            "file" -> scanDirectory(File(resource.toURI()), packageName, classLoader)
                            "jar" -> {
                                val connection = resource.openConnection()
                                if (connection is JarURLConnection) {
                                    scanJar(File(connection.jarFileURL.toURI()), packageName, classLoader)
                                } else {
                                    AsmScanResult()
                                }
                            }
                            else -> AsmScanResult()
                        },
                    )
            }
            result
        } catch (throwable: Throwable) {
            AsmScanResult(failures = listOf(AsmScanFailure(packageName, throwable.message ?: throwable.javaClass.name)))
        }
    }

    /**
     * 加载并注册带有 @AsmMixin 注解的类。
     *
     * 使用延迟初始化加载，避免扫描阶段执行类初始化逻辑。
     */
    private fun registerAsmClass(
        className: String,
        classLoader: ClassLoader,
    ): AsmScanResult =
        try {
            val clazz = Class.forName(className, false, classLoader)
            if (clazz.isAnnotationPresent(AsmMixin::class.java)) {
                AsmRegistry.register(clazz)
                AsmScanResult(registeredClasses = listOf(className))
            } else {
                AsmScanResult(skippedClasses = listOf(className))
            }
        } catch (throwable: Throwable) {
            AsmScanResult(failures = listOf(AsmScanFailure(className, throwable.message ?: throwable.javaClass.name)))
        }
}
