/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm

import kim.der.asm.api.annotation.Accessor
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.At
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.ModifyArg
import kim.der.asm.api.annotation.ModifyConstant
import kim.der.asm.api.annotation.ModifyReturnValue
import kim.der.asm.api.annotation.Redirect
import kim.der.asm.api.annotation.Copy
import kim.der.asm.api.annotation.Overwrite
import kim.der.asm.api.annotation.RemoveMethod
import kim.der.asm.api.annotation.RemoveSynchronized
import kim.der.asm.api.annotation.Shadow
import kim.der.asm.api.annotation.Shift
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
    fun redirectWithInvalidHandlerSignatureFailsDuringTransform() {
        AsmRegistry.register(InvalidRedirectHandlerMixin::class.java)

        assertThrows(AsmTransformException::class.java) {
            AsmProcessor().transform("RedirectTarget", redirectTargetBytes(), javaClass.classLoader)
        }
    }
    @Test
    fun overwriteWithIncompatibleReturnTypeFailsDuringTransform() {
        AsmRegistry.register(IncompatibleOverwriteMixin::class.java)

        assertThrows(AsmTransformException::class.java) {
            AsmProcessor().transform("ReturnTarget", returnTargetBytes(), javaClass.classLoader)
        }
    }

    @Test
    fun copyWithIncompatibleReturnTypeFailsDuringTransform() {
        AsmRegistry.register(IncompatibleCopyMixin::class.java)

        assertThrows(AsmTransformException::class.java) {
            AsmProcessor().transform("ReturnTarget", returnTargetBytes(), javaClass.classLoader)
        }
    }
    @Test
    fun inlineMethodWithTryCatchFailsDuringTransform() {
        AsmRegistry.register(InlineTryCatchMixin::class.java)

        assertThrows(AsmTransformException::class.java) {
            AsmProcessor().transform("InlineTarget", inlineTargetBytes(), javaClass.classLoader)
        }
    }

    @Test
    fun injectWithUnmappableHandlerParameterFailsDuringTransform() {
        AsmRegistry.register(UnmappableInjectParameterMixin::class.java)

        assertThrows(AsmTransformException::class.java) {
            AsmProcessor().transform("StrictTarget", strictTargetBytes(), javaClass.classLoader)
        }
    }

    @Test
    fun modifyReturnValueWithTooManyHandlerParametersFailsDuringTransform() {
        AsmRegistry.register(TooManyModifyReturnParametersMixin::class.java)

        assertThrows(AsmTransformException::class.java) {
            AsmProcessor().transform("ReturnTarget", returnTargetBytes(), javaClass.classLoader)
        }
    }

    @Test
    fun invokeReplaceWithIncompatibleReturnTypeFailsDuringTransform() {
        AsmRegistry.register(IncompatibleInvokeReplaceMixin::class.java)

        assertThrows(AsmTransformException::class.java) {
            AsmProcessor().transform("RedirectTarget", redirectTargetBytes(), javaClass.classLoader)
        }
    }

    @Test
    fun modifyArgWithTooManyHandlerParametersFailsDuringTransform() {
        AsmRegistry.register(TooManyModifyArgParametersMixin::class.java)

        assertThrows(AsmTransformException::class.java) {
            AsmProcessor().transform("ArgTarget", argTargetBytes(), javaClass.classLoader)
        }
    }

    @Test
    fun modifyConstantWithIncompatibleReturnTypeFailsDuringTransform() {
        AsmRegistry.register(IncompatibleModifyConstantMixin::class.java)

        assertThrows(AsmTransformException::class.java) {
            AsmProcessor().transform("ReturnTarget", returnTargetBytes(), javaClass.classLoader)
        }
    }

    @Test
    fun modifyConstantWithoutExplicitValueSkipsOtherConstantTypes() {
        AsmRegistry.register(StringOnlyModifyConstantMixin::class.java)

        val transformed = AsmProcessor().transform("MixedConstantTarget", mixedConstantTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val method = classNode.methods.single { it.name == "value" }
        val constants = method.instructions.toArray().filterIsInstance<org.objectweb.asm.tree.LdcInsnNode>().map { it.cst }
        val methodCalls = method.instructions.toArray().filterIsInstance<org.objectweb.asm.tree.MethodInsnNode>().map { it.name }

        assertEquals(true, constants.contains(1))
        assertEquals(true, methodCalls.contains("modify"))
    }

    @Test
    fun shadowWithMissingFieldFailsDuringTransform() {
        AsmRegistry.register(MissingShadowFieldMixin::class.java)

        assertThrows(AsmTransformException::class.java) {
            AsmProcessor().transform("StrictTarget", strictTargetBytes(), javaClass.classLoader)
        }
    }

    @Test
    fun shadowWithMismatchedFieldTypeFailsDuringTransform() {
        AsmRegistry.register(MismatchedShadowFieldMixin::class.java)

        assertThrows(AsmTransformException::class.java) {
            AsmProcessor().transform("FieldTarget", fieldTargetBytes(), javaClass.classLoader)
        }
    }

    @Test
    fun shadowWithMissingMethodFailsDuringTransform() {
        AsmRegistry.register(MissingShadowMethodMixin::class.java)

        assertThrows(AsmTransformException::class.java) {
            AsmProcessor().transform("StrictTarget", strictTargetBytes(), javaClass.classLoader)
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

    @AsmMixin("RedirectTarget")
    object InvalidRedirectHandlerMixin {
        @Redirect(
            method = "call()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.trim()Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun invalidHandler(unexpected: Int): String = unexpected.toString()
    }
    @AsmMixin("ReturnTarget")
    object IncompatibleOverwriteMixin {
        @Overwrite("value()Ljava/lang/String;")
        @JvmStatic
        fun value(): Int = 1
    }

    @AsmMixin("ReturnTarget")
    object IncompatibleCopyMixin {
        @Copy("copied()Ljava/lang/String;")
        @JvmStatic
        fun copied(): Int = 1
    }
    @AsmMixin("InlineTarget")
    object InlineTryCatchMixin {
        @AsmInject(method = "run()V", inline = true)
        @JvmStatic
        fun injectInline() {
            try {
                " value ".trim()
            } catch (_: RuntimeException) {
                // ignored for test fixture
            }
        }
    }

    @AsmMixin("StrictTarget")
    object UnmappableInjectParameterMixin {
        @AsmInject(method = "keep()V")
        @JvmStatic
        fun inject(unavailable: String) {
            unavailable.length
        }
    }

    @AsmMixin("ReturnTarget")
    object TooManyModifyReturnParametersMixin {
        @ModifyReturnValue(method = "value()Ljava/lang/String;")
        @JvmStatic
        fun modify(
            original: String,
            unavailable: Int,
        ): String = "$original$unavailable"
    }

    @AsmMixin("RedirectTarget")
    object IncompatibleInvokeReplaceMixin {
        @AsmInject(
            method = "call()Ljava/lang/String;",
            target = InjectionPoint.INVOKE,
            at = At(
                target = "java/lang/String.trim()Ljava/lang/String;",
                shift = Shift.REPLACE,
            ),
        )
        @JvmStatic
        fun replace(): Int = 1
    }

    @AsmMixin("ArgTarget")
    object TooManyModifyArgParametersMixin {
        @ModifyArg(method = "echo(Ljava/lang/String;)Ljava/lang/String;", index = 0)
        @JvmStatic
        fun modify(
            original: String,
            unavailable: String,
        ): String = "$original$unavailable"
    }

    @AsmMixin("ReturnTarget")
    object IncompatibleModifyConstantMixin {
        @ModifyConstant(method = "value()Ljava/lang/String;", constant = "value")
        @JvmStatic
        fun modify(original: String): Int = original.length
    }

    @AsmMixin("MixedConstantTarget")
    object StringOnlyModifyConstantMixin {
        @ModifyConstant(method = "value()Ljava/lang/String;")
        @JvmStatic
        fun modify(original: String): String = "changed"
    }

    @AsmMixin("StrictTarget")
    class MissingShadowFieldMixin {
        @Shadow
        private val missing: String? = null
    }

    @AsmMixin("FieldTarget")
    class MismatchedShadowFieldMixin {
        @Shadow
        private val name: Int = 0
    }

    @AsmMixin("StrictTarget")
    class MissingShadowMethodMixin {
        @Shadow
        private fun missing(): String = throw UnsupportedOperationException()
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

    private fun redirectTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "RedirectTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "call", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn(" value ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "trim", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }
    private fun returnTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "ReturnTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("value")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }
    private fun argTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "ArgTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "echo", "(Ljava/lang/String;)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 1)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 2)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }
    private fun fieldTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "FieldTarget", null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE, "name", "Ljava/lang/String;", null, null).visitEnd()
        addDefaultConstructor(cw)
        cw.visitEnd()
        return cw.toByteArray()
    }
    private fun mixedConstantTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "MixedConstantTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn(1)
            visitInsn(Opcodes.POP)
            visitLdcInsn("original")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }
    private fun inlineTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "InlineTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null).apply {
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




