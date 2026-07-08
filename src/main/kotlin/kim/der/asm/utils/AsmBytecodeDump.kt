/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.utils

import org.objectweb.asm.ClassReader
import org.objectweb.asm.util.TraceClassVisitor
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * 转换后字节码调试导出工具。
 *
 * 供排查 Mixin 是否生效、注入点是否命中、参数映射是否正确时使用。
 * 该工具只做只读导出，不会修改输入字节码，也不会注册或触发任何转换。
 *
 * 典型用法：
 * 1. 用 [kim.der.asm.transformer.AsmProcessor.transform] 得到转换后字节码
 * 2. 调用 [writeClassFile] 写出 `.class`，再用 JD-GUI / CFR 反编译
 * 3. 或调用 [toText] / [writeText] 直接查看 ASM Trace 文本
 *
 * @author Dr (dr@der.kim)
 * @date 2026-07-09
 */
object AsmBytecodeDump {
    /**
     * 将 classfile 字节码写出为 `.class` 文件。
     *
     * 父目录不存在时会自动创建。已存在的目标文件会被覆盖。
     *
     * @param classfileBuffer 原始或转换后的 classfile 字节码；不得为空数组
     * @param outputPath 目标文件路径，例如 `build/asm-dump/com/example/Target.class`
     * @return 实际写出的路径
     * @throws IllegalArgumentException [classfileBuffer] 为空时抛出
     * @throws IOException 创建目录或写文件失败时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2026-07-09
     */
    @JvmStatic
    @Throws(IOException::class)
    fun writeClassFile(
        classfileBuffer: ByteArray,
        outputPath: Path,
    ): Path {
        require(classfileBuffer.isNotEmpty()) { "classfileBuffer must not be empty" }
        val parent = outputPath.parent
        if (parent != null) {
            Files.createDirectories(parent)
        }
        Files.write(
            outputPath,
            classfileBuffer,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        return outputPath
    }

    /**
     * 按目标类 internal name 写出 `.class` 文件。
     *
     * 例如 `className = "com/example/Target"` 且 `outputDirectory = Path.of("build/asm-dump")`
     * 时，会写出 `build/asm-dump/com/example/Target.class`。
     *
     * @param className 目标类 internal name，例如 `com/example/Target`；也接受 `com.example.Target`
     * @param classfileBuffer 原始或转换后的 classfile 字节码
     * @param outputDirectory 导出根目录
     * @return 实际写出的路径
     * @throws IllegalArgumentException [className] 或 [classfileBuffer] 非法时抛出
     * @throws IOException 创建目录或写文件失败时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2026-07-09
     */
    @JvmStatic
    @Throws(IOException::class)
    fun writeClassFile(
        className: String,
        classfileBuffer: ByteArray,
        outputDirectory: Path,
    ): Path {
        val normalized = normalizeInternalName(className)
        require(normalized.isNotEmpty()) { "className must not be blank" }
        val outputPath = outputDirectory.resolve("$normalized.class")
        return writeClassFile(classfileBuffer, outputPath)
    }

    /**
     * 将 classfile 字节码格式化为 ASM Trace 文本。
     *
     * 输出适合快速核对方法签名、指令序列与注入结果，不替代完整反编译。
     *
     * @param classfileBuffer 原始或转换后的 classfile 字节码
     * @param parsingOptions 传给 [ClassReader.accept] 的读取标志，默认 `0`
     * @return Trace 文本；末尾通常带换行
     * @throws IllegalArgumentException [classfileBuffer] 为空时抛出
     * @throws IllegalArgumentException 字节码无法被 ASM 解析时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2026-07-09
     */
    @JvmStatic
    fun toText(
        classfileBuffer: ByteArray,
        parsingOptions: Int = 0,
    ): String {
        require(classfileBuffer.isNotEmpty()) { "classfileBuffer must not be empty" }
        return try {
            val reader = ClassReader(classfileBuffer)
            val stringWriter = StringWriter()
            PrintWriter(stringWriter).use { printWriter ->
                reader.accept(TraceClassVisitor(null, printWriter), parsingOptions)
            }
            stringWriter.toString()
        } catch (throwable: Throwable) {
            throw IllegalArgumentException("Failed to trace classfile bytecode", throwable)
        }
    }

    /**
     * 将 ASM Trace 文本写出到文件。
     *
     * 父目录不存在时会自动创建。已存在的目标文件会被覆盖。
     *
     * @param classfileBuffer 原始或转换后的 classfile 字节码
     * @param outputPath 目标文本路径，例如 `build/asm-dump/Target.trace.txt`
     * @param parsingOptions 传给 [ClassReader.accept] 的读取标志，默认 `0`
     * @return 实际写出的路径
     * @throws IllegalArgumentException [classfileBuffer] 为空或无法解析时抛出
     * @throws IOException 创建目录或写文件失败时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2026-07-09
     */
    @JvmStatic
    @Throws(IOException::class)
    fun writeText(
        classfileBuffer: ByteArray,
        outputPath: Path,
        parsingOptions: Int = 0,
    ): Path {
        val text = toText(classfileBuffer, parsingOptions)
        val parent = outputPath.parent
        if (parent != null) {
            Files.createDirectories(parent)
        }
        Files.writeString(
            outputPath,
            text,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        return outputPath
    }

    /**
     * 把 binary name 或 internal name 规范成 internal name。
     */
    private fun normalizeInternalName(className: String): String =
        className
            .trim()
            .removeSuffix(".class")
            .replace('.', '/')
}
