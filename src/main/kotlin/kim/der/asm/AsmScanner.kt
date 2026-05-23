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
 *
 * 该结果用于替代静默扫描：调用方可以据此区分已注册、已扫描但不是 ASM、以及加载失败的类。
 * 结果对象是不可变快照，多个扫描来源的结果可通过 [merge] 合并。
 *
 * @param registeredClasses 成功注册到 [AsmRegistry] 的类名，使用 Java binary name
 * @param skippedClasses 成功加载但未标注 [AsmMixin] 的类名
 * @param failures 扫描或类加载失败的条目
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
data class AsmScanResult(
    val registeredClasses: List<String> = emptyList(),
    val skippedClasses: List<String> = emptyList(),
    val failures: List<AsmScanFailure> = emptyList(),
) {
    /**
     * 合并另一个扫描结果。
     *
     * 合并操作只拼接三个结果列表，不去重，也不改变原有顺序，便于调用方保留扫描来源的先后关系。
     *
     * @param other 另一个扫描结果
     * @return 合并后的新结果
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    fun merge(other: AsmScanResult): AsmScanResult =
        AsmScanResult(
            registeredClasses = registeredClasses + other.registeredClasses,
            skippedClasses = skippedClasses + other.skippedClasses,
            failures = failures + other.failures,
        )
}

/**
 * ASM 扫描失败条目。
 *
 * @param className 失败的类名、包名或 JAR 路径，取决于失败发生的位置
 * @param reason 失败原因摘要，优先使用异常消息，缺失时使用异常类名
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
data class AsmScanFailure(
    val className: String,
    val reason: String,
)

/**
 * ASM 扫描器。
 *
 * 负责从包、目录、JAR 或指定 [ClassLoader] 中查找带 [AsmMixin] 的类并注册到 [AsmRegistry]。
 * 扫描过程中使用 `Class.forName(name, false, loader)` 加载类，避免仅扫描阶段触发类初始化副作用。
 *
 * 无返回值的扫描方法保留兼容性；需要诊断时应优先使用 `*WithResult` 入口读取成功、跳过与失败统计。
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
object AsmScanner {
    /**
     * 扫描指定包中的所有 ASM 类并注册。
     *
     * 该入口使用当前线程上下文类加载器，并忽略返回的诊断结果。若调用方需要知道哪些类被跳过或失败，
     * 应使用 [scanPackageWithResult]。
     *
     * @param packageName 包名，例如 `com.example.asms`
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    @JvmStatic
    fun scanPackage(packageName: String) {
        scanPackageWithResult(packageName)
    }

    /**
     * 扫描指定包中的所有 ASM 类并返回诊断结果。
     *
     * 该入口会委托当前线程上下文类加载器查找包资源，并合并所有 `file` 与 `jar` 资源的扫描结果。
     *
     * @param packageName 包名，例如 `com.example.asms`
     * @return 扫描诊断结果
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    @JvmStatic
    fun scanPackageWithResult(packageName: String): AsmScanResult =
        scanClassLoaderWithResult(Thread.currentThread().contextClassLoader, packageName)

    /**
     * 扫描目录中的 ASM 类。
     *
     * 该入口使用当前线程上下文类加载器加载目录中发现的类，并忽略诊断结果。
     *
     * @param directory 包根目录对应的文件系统目录
     * @param packageName 该目录对应的包名
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
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
     *
     * 目录不存在或无法列出文件时返回空结果。子目录会按包名追加目录名递归扫描。
     *
     * @param directory 包根目录对应的文件系统目录
     * @param packageName 该目录对应的包名
     * @return 扫描诊断结果
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    @JvmStatic
    fun scanDirectoryWithResult(
        directory: File,
        packageName: String,
    ): AsmScanResult = scanDirectory(directory, packageName, Thread.currentThread().contextClassLoader)

    /**
     * 扫描目录中的 ASM 类。
     *
     * @param directory 当前扫描目录
     * @param packageName 当前目录对应的包名
     * @param classLoader 用于加载类的类加载器
     * @return 当前目录及其子目录的扫描诊断结果
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
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
     *
     * 该入口使用当前线程上下文类加载器作为父加载器，并忽略诊断结果。
     *
     * @param jarFile 待扫描的 JAR 文件
     * @param packageName 限定扫描的包名；为空字符串时扫描整个 JAR
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
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
     *
     * 该入口会为目标 JAR 创建临时 [URLClassLoader]，并在扫描结束后关闭。
     *
     * @param jarFile 待扫描的 JAR 文件
     * @param packageName 限定扫描的包名；为空字符串时扫描整个 JAR
     * @return 扫描诊断结果
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    @JvmStatic
    fun scanJarWithResult(
        jarFile: File,
        packageName: String,
    ): AsmScanResult = scanJar(jarFile, packageName, Thread.currentThread().contextClassLoader)

    /**
     * 扫描 JAR 文件中的 ASM 类。
     *
     * JAR 不存在时返回空结果。打开 JAR、遍历条目或创建类加载器失败时，会返回包含单个失败条目的结果，
     * 不会向外抛出异常。
     *
     * @param jarFile 待扫描的 JAR 文件
     * @param packageName 限定扫描的包名
     * @param parentClassLoader 临时 JAR 类加载器的父加载器
     * @return 扫描诊断结果
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
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
     *
     * 该入口忽略诊断结果；需要读取扫描统计时使用 [scanClassLoaderWithResult]。
     *
     * @param classLoader 用于查找资源与加载类的类加载器
     * @param packageName 包名，例如 `com.example.asms`
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
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
     *
     * 当前实现支持 `file` 与 `jar` 资源协议；其他协议会被跳过并计为空结果。
     * 资源枚举或 URI 转换失败时，会返回包含包级失败条目的结果。
     *
     * @param classLoader 用于查找资源与加载类的类加载器
     * @param packageName 包名，例如 `com.example.asms`
     * @return 扫描诊断结果
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
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
     *
     * @param className 待加载的 Java binary name
     * @param classLoader 用于加载类的类加载器
     * @return 单个类的扫描诊断结果
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
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
