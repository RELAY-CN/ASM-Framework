/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm

import kim.der.asm.api.annotation.Accessor
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.RemoveMethod
import kim.der.asm.api.annotation.RemoveSynchronized
import kim.der.asm.transformer.AsmProcessor
import kim.der.asm.transformer.AsmTransformException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class FrameworkReliabilityTest {
    @AfterEach
    fun tearDown() {
        AsmRegistry.clear()
    }

    @Test
    fun transformFailsFastInsteadOfWritingPartiallyTransformedClass() {
        AsmRegistry.register(RemoveKeepMethodMixin::class.java)
        AsmRegistry.register(MissingAccessorMixin::class.java)

        assertThrows(AsmTransformException::class.java) {
            AsmProcessor().transform("StrictTarget", strictTargetBytes(), javaClass.classLoader)
        }
    }

    @Test
    fun removeSynchronizedRemovesBlockMonitorInstructions() {
        AsmRegistry.register(RemoveBlockSynchronizedMixin::class.java)

        val transformed = AsmProcessor().transform("SyncTarget", syncTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val method = classNode.methods.single { it.name == "blockSync" }
        val monitorOpcodes = method.instructions.toArray().filter { it.opcode == Opcodes.MONITORENTER || it.opcode == Opcodes.MONITOREXIT }

        assertEquals(0, monitorOpcodes.size, "移除同步后不应留下 monitorenter/monitorexit 指令")
    }

    @Test
    fun scannerReportsClassLoadingFailures() {
        val jarFile = Files.createTempFile("asm-scanner-invalid-", ".jar")
        try {
            JarOutputStream(Files.newOutputStream(jarFile)).use { output ->
                output.putNextEntry(JarEntry("broken/pkg/Broken.class"))
                output.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
                output.closeEntry()
            }

            val result = AsmScanner.scanJarWithResult(jarFile.toFile(), "broken.pkg")

            assertEquals(0, result.registeredClasses.size)
            assertEquals(1, result.failures.size)
            assertEquals("broken.pkg.Broken", result.failures.single().className)
        } finally {
            Files.deleteIfExists(jarFile)
        }
    }

    @Test
    fun registryAllowsConcurrentReadsAndWrites() {
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val failures = mutableListOf<Throwable>()

        repeat(8) { worker ->
            executor.submit {
                try {
                    start.await()
                    repeat(500) { index ->
                        if ((index + worker) % 3 == 0) {
                            AsmRegistry.register(RemoveKeepMethodMixin::class.java)
                        } else {
                            AsmRegistry.getForTarget("StrictTarget")
                        }
                    }
                } catch (throwable: Throwable) {
                    synchronized(failures) {
                        failures.add(throwable)
                    }
                }
            }
        }

        start.countDown()
        executor.shutdown()
        executor.awaitTermination(10, TimeUnit.SECONDS)

        assertEquals(emptyList<Throwable>(), failures)
    }

    @AsmMixin("StrictTarget")
    object RemoveKeepMethodMixin {
        @RemoveMethod("keep()V")
        @JvmStatic
        fun keep() {
        }
    }

    @AsmMixin("StrictTarget")
    object MissingAccessorMixin {
        @Accessor("missingField")
        @JvmStatic
        fun getMissingField(): String = throw UnsupportedOperationException()
    }

    @AsmMixin("SyncTarget")
    object RemoveBlockSynchronizedMixin {
        @RemoveSynchronized("blockSync(Ljava/lang/Object;)V")
        @JvmStatic
        fun blockSync(value: Any) {
        }
    }

    private fun strictTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "StrictTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "keep", "()V", null, null).apply {
            visitCode()
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun syncTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "SyncTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "blockSync", "(Ljava/lang/Object;)V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitInsn(Opcodes.DUP)
            visitInsn(Opcodes.MONITORENTER)
            visitInsn(Opcodes.MONITOREXIT)
            visitInsn(Opcodes.RETURN)
            visitMaxs(2, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun addDefaultConstructor(cw: ClassWriter) {
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
    }

    private fun readClass(bytes: ByteArray): ClassNode {
        val classNode = ClassNode()
        ClassReader(bytes).accept(classNode, ClassReader.EXPAND_FRAMES)
        return classNode
    }
}

