/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm

import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.Copy
import kim.der.asm.api.annotation.Overwrite
import kim.der.asm.transformer.AsmProcessor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AsmScannerLifecycleTest {
    @TempDir
    lateinit var directory: Path

    @BeforeEach
    @AfterEach
    fun clearRegistry() {
        AsmRegistry.clear()
    }

    @Test
    fun externalJarBytecodeRemainsUsableAfterLoaderCloses() {
        assertNull(javaClass.classLoader.getResource("$MIXIN.class"))
        val jar = createJar(mixinBytes(42))
        val result = AsmScanner.scanJarWithResult(jar.toFile(), "lifecycle.external")

        assertEquals(listOf(MIXIN.replace('/', '.')), result.registeredClasses)
        assertTrue(result.failures.isEmpty(), result.failures.toString())
        val info = AsmRegistry.getForTarget(TARGET).single()
        assertNull(info.asmClass.classLoader.getResourceAsStream("$MIXIN.class"))
        // 删除原 JAR 后再转换，验证仅依赖扫描快照，且扫描器没有遗留文件句柄。
        Files.delete(jar)

        val target = transformAndLoad()
        assertEquals(42, target.getMethod("value").invoke(null))
        assertEquals(21, target.getMethod("copiedValue").invoke(null))
        assertEquals(1, target.getMethod("inlineValue").invoke(null))
        assertEquals(1, target.getField("touches").getInt(null))
    }

    @Test
    fun parentDefinedMixinDoesNotUseShadowedJarBytecode() {
        val parentBytes = mixinBytes(7)
        val parent = object : ClassLoader(javaClass.classLoader) {
            override fun findClass(name: String): Class<*> {
                if (name == MIXIN.replace('/', '.')) {
                    return defineClass(name, parentBytes, 0, parentBytes.size)
                }
                return super.findClass(name)
            }

            override fun getResourceAsStream(name: String): InputStream? =
                if (name == "$MIXIN.class") ByteArrayInputStream(parentBytes) else super.getResourceAsStream(name)
        }
        val jar = createJar(mixinBytes(42))
        val thread = Thread.currentThread()
        val originalLoader = thread.contextClassLoader
        val result = try {
            thread.contextClassLoader = parent
            AsmScanner.scanJarWithResult(jar.toFile(), "lifecycle.external")
        } finally {
            thread.contextClassLoader = originalLoader
        }

        assertTrue(result.failures.isEmpty(), result.failures.toString())
        assertSame(parent, AsmRegistry.getForTarget(TARGET).single().asmClass.classLoader)
        assertEquals(7, transformAndLoad().getMethod("value").invoke(null))
    }

    @Test
    fun multiReleaseJarUsesRuntimeSelectedBytecode() {
        val jar = createJar(mixinBytes(42), mixinBytes(7))
        val result = AsmScanner.scanJarWithResult(jar.toFile(), "lifecycle.external")

        assertTrue(result.failures.isEmpty(), result.failures.toString())
        val mixin = AsmRegistry.getForTarget(TARGET).single().asmClass
        assertEquals(7, mixin.getMethod("value").invoke(null))
        assertEquals(7, transformAndLoad().getMethod("value").invoke(null))
    }

    private fun createJar(bytes: ByteArray, versionedBytes: ByteArray? = null): Path {
        val jar = directory.resolve("mixin.jar")
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            if (versionedBytes != null) mainAttributes[Attributes.Name.MULTI_RELEASE] = "true"
        }
        JarOutputStream(Files.newOutputStream(jar), manifest).use { output ->
            output.putNextEntry(JarEntry("$MIXIN.class"))
            output.write(bytes)
            output.closeEntry()
            if (versionedBytes != null) {
                output.putNextEntry(JarEntry("META-INF/versions/11/$MIXIN.class"))
                output.write(versionedBytes)
                output.closeEntry()
            }
        }
        return jar
    }

    private fun mixinBytes(value: Int): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, MIXIN, null, "java/lang/Object", null)
        writer.visitAnnotation(Type.getDescriptor(AsmMixin::class.java), true).apply {
            visit("value", TARGET)
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "value", "()I", null, null).apply {
            visitAnnotation(Type.getDescriptor(Overwrite::class.java), true).apply {
                visit("method", "value()I")
                visitEnd()
            }
            visitCode()
            visitLdcInsn(value)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(1, 0)
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "copiedValue", "()I", null, null).apply {
            visitAnnotation(Type.getDescriptor(Copy::class.java), true).visitEnd()
            visitCode()
            visitLdcInsn(21)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(1, 0)
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "beforeInline", "()V", null, null).apply {
            visitAnnotation(Type.getDescriptor(AsmInject::class.java), true).apply {
                visit("method", "inlineValue()I")
                visit("inline", true)
                visitEnd()
            }
            visitCode()
            visitFieldInsn(Opcodes.GETSTATIC, TARGET, "touches", "I")
            visitInsn(Opcodes.ICONST_1)
            visitInsn(Opcodes.IADD)
            visitFieldInsn(Opcodes.PUTSTATIC, TARGET, "touches", "I")
            visitInsn(Opcodes.RETURN)
            visitMaxs(2, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun transformAndLoad(): Class<*> {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, TARGET, null, "java/lang/Object", null)
        writer.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "touches", "I", null, null).visitEnd()
        for (name in listOf("value", "inlineValue")) {
            writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, name, "()I", null, null).apply {
                visitCode()
                visitInsn(Opcodes.ICONST_1)
                visitInsn(Opcodes.IRETURN)
                visitMaxs(1, 0)
                visitEnd()
            }
        }
        writer.visitEnd()
        val bytes = AsmProcessor().transform(TARGET, writer.toByteArray(), javaClass.classLoader)
        return object : ClassLoader(javaClass.classLoader) {
            fun loadTarget(): Class<*> = defineClass(TARGET.replace('/', '.'), bytes, 0, bytes.size)
        }.loadTarget()
    }

    companion object {
        private const val MIXIN = "lifecycle/external/JarMixin"
        private const val TARGET = "lifecycle/JarTarget"
    }
}
