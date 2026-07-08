/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.utils

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path

/**
 * [AsmBytecodeDump] 行为与文档契约测试。
 */
class AsmBytecodeDumpTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("应按 internal name 写出 class 文件并保留原始字节")
    fun writeClassFileUsesInternalNameAndPreservesBytes() {
        // Given
        val bytecode = sampleClassBytes("com/example/DumpTarget")

        // When
        val output =
            AsmBytecodeDump.writeClassFile(
                className = "com.example.DumpTarget",
                classfileBuffer = bytecode,
                outputDirectory = tempDir,
            )

        // Then
        assertThat(output)
            .`as`("Then: binary name 应规范成 internal name 路径")
            .isEqualTo(tempDir.resolve("com/example/DumpTarget.class"))
        assertThat(Files.readAllBytes(output))
            .`as`("Then: 写出内容应与输入字节码完全一致")
            .isEqualTo(bytecode)
    }

    @Test
    @DisplayName("应生成包含类名的 ASM Trace 文本并可写文件")
    fun toTextAndWriteTextExposeClassTrace() {
        // Given
        val bytecode = sampleClassBytes("kim/der/asm/utils/TraceTarget")

        // When
        val text = AsmBytecodeDump.toText(bytecode)
        val textPath = AsmBytecodeDump.writeText(bytecode, tempDir.resolve("TraceTarget.trace.txt"))

        // Then
        assertThat(text)
            .`as`("Then: Trace 文本应包含目标类 internal name")
            .contains("kim/der/asm/utils/TraceTarget")
        assertThat(Files.readString(textPath))
            .`as`("Then: 写出的 Trace 文件应与 toText 结果一致")
            .isEqualTo(text)
    }

    @Test
    @DisplayName("空字节码应快速失败")
    fun emptyBytecodeFailsFast() {
        assertThatThrownBy { AsmBytecodeDump.toText(ByteArray(0)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("classfileBuffer must not be empty")
    }

    @Test
    @DisplayName("公开文档应暴露 AsmBytecodeDump 调试入口")
    fun documentationContractsKeepAsmBytecodeDumpAligned() {
        // Given
        val readme = Files.readString(Path.of("README.md"))
        val guide = Files.readString(Path.of("GUIDE.md"))
        val api = Files.readString(Path.of("API.md"))
        val kdoc =
            Files.readString(
                Path.of("src", "main", "kotlin", "kim", "der", "asm", "utils", "AsmBytecodeDump.kt"),
            )

        // Then
        assertThat(readme)
            .`as`("Then: README 特性应提到调试导出")
            .contains("AsmBytecodeDump")
            .contains("ASM Trace")
        assertThat(guide)
            .`as`("Then: GUIDE 调试技巧应推荐 AsmBytecodeDump")
            .contains("AsmBytecodeDump.writeClassFile")
            .contains("AsmBytecodeDump.toText")
            .contains("只做只读导出")
        assertThat(api)
            .`as`("Then: API 工具类应记录 AsmBytecodeDump")
            .contains("### AsmBytecodeDump")
            .contains("writeClassFile(className: String, classfileBuffer: ByteArray, outputDirectory: Path): Path")
            .contains("toText(classfileBuffer: ByteArray, parsingOptions: Int = 0): String")
        assertThat(kdoc)
            .`as`("Then: AsmBytecodeDump KDoc 应说明只读导出边界")
            .contains("只做只读导出")
            .contains("不会修改输入字节码")
    }

    private fun sampleClassBytes(internalName: String): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V11,
            Opcodes.ACC_PUBLIC,
            internalName,
            null,
            "java/lang/Object",
            null,
        )
        val init =
            writer.visitMethod(
                Opcodes.ACC_PUBLIC,
                "<init>",
                "()V",
                null,
                null,
            )
        init.visitCode()
        init.visitVarInsn(Opcodes.ALOAD, 0)
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        init.visitInsn(Opcodes.RETURN)
        init.visitMaxs(1, 1)
        init.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }
}
