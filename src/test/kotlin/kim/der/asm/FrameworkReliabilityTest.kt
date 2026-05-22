/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm

import kim.der.asm.api.annotation.Accessor
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.At
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.Invoker
import kim.der.asm.api.annotation.ModifyArg
import kim.der.asm.api.annotation.ModifyConstant
import kim.der.asm.api.annotation.ModifyReturnValue
import kim.der.asm.api.annotation.Redirect
import kim.der.asm.api.annotation.RedirectAllMethods
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
    fun inlineHeadIntoNonVoidTargetDoesNotInlineHandlerReturnInstruction() {
        AsmRegistry.register(InlineVoidHeadReturnTargetMixin::class.java)

        val transformed = AsmProcessor().transform("ReturnTarget", returnTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ReturnTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("value", result)
    }

    @Test
    fun inlineHeadWithReturningHandlerDoesNotReturnFromTarget() {
        AsmRegistry.register(InlineStringHeadReturnTargetMixin::class.java)

        val transformed = AsmProcessor().transform("ReturnTarget", returnTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ReturnTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("value", result)
    }

    @Test
    fun inlineReturnInjectionDoesNotReturnFromTarget() {
        AsmRegistry.register(InlineStringReturnTargetMixin::class.java)

        val transformed = AsmProcessor().transform("ReturnTarget", returnTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ReturnTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("value", result)
    }

    @Test
    fun tailInjectionWithClassMixinClonesLabelsSafely() {
        AsmRegistry.register(ClassTailInjectMixin::class.java)

        val transformed = AsmProcessor().transform("ReturnTarget", returnTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ReturnTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("value", result)
    }

    @Test
    fun kotlinObjectInlineInstanceTargetPreservesObjectReceiverForHelperCall() {
        AsmRegistry.register(ObjectInstanceInlineMixin::class.java)

        val transformed = AsmProcessor().transform("InlineTarget", inlineTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("InlineTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("run").invoke(instance)
    }

    @Test
    fun kotlinObjectInlineStaticTargetPreservesObjectReceiverForHelperCall() {
        AsmRegistry.register(ObjectInstanceStaticInlineMixin::class.java)

        val transformed = AsmProcessor().transform("StaticHeadTarget", staticHeadTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("StaticHeadTarget", transformed)

        clazz.getMethod("run").invoke(null)
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
    fun overwriteWithMissingTargetMethodFailsDuringTransform() {
        AsmRegistry.register(MissingOverwriteTargetMixin::class.java)

        assertThrows(AsmTransformException::class.java) {
            AsmProcessor().transform("StrictTarget", strictTargetBytes(), javaClass.classLoader)
        }
    }

    @Test
    fun removeMethodWithMissingTargetFailsDuringTransform() {
        AsmRegistry.register(MissingRemoveMethodTargetMixin::class.java)

        assertThrows(AsmTransformException::class.java) {
            AsmProcessor().transform("StrictTarget", strictTargetBytes(), javaClass.classLoader)
        }
    }

    @Test
    fun removeSynchronizedWithMissingTargetFailsDuringTransform() {
        AsmRegistry.register(MissingRemoveSynchronizedTargetMixin::class.java)

        assertThrows(AsmTransformException::class.java) {
            AsmProcessor().transform("StrictTarget", strictTargetBytes(), javaClass.classLoader)
        }
    }

    @Test
    fun injectWithMissingTargetMethodFailsDuringTransform() {
        AsmRegistry.register(MissingInjectTargetMixin::class.java)

        assertThrows(AsmTransformException::class.java) {
            AsmProcessor().transform("StrictTarget", strictTargetBytes(), javaClass.classLoader)
        }
    }

    @Test
    fun modifyArgWithMissingTargetMethodFailsDuringTransform() {
        AsmRegistry.register(MissingModifyArgTargetMixin::class.java)

        assertThrows(AsmTransformException::class.java) {
            AsmProcessor().transform("StrictTarget", strictTargetBytes(), javaClass.classLoader)
        }
    }

    @Test
    fun redirectWithMissingTargetMethodFailsDuringTransform() {
        AsmRegistry.register(MissingRedirectTargetMixin::class.java)

        assertThrows(AsmTransformException::class.java) {
            AsmProcessor().transform("StrictTarget", strictTargetBytes(), javaClass.classLoader)
        }
    }

    @Test
    fun modifyReturnValueWithMissingTargetMethodFailsDuringTransform() {
        AsmRegistry.register(MissingModifyReturnTargetMixin::class.java)

        assertThrows(AsmTransformException::class.java) {
            AsmProcessor().transform("StrictTarget", strictTargetBytes(), javaClass.classLoader)
        }
    }

    @Test
    fun modifyConstantWithMissingTargetMethodFailsDuringTransform() {
        AsmRegistry.register(MissingModifyConstantTargetMixin::class.java)

        assertThrows(AsmTransformException::class.java) {
            AsmProcessor().transform("StrictTarget", strictTargetBytes(), javaClass.classLoader)
        }
    }

    @Test
    fun invokeInjectWithMissingCallTargetFailsDuringTransform() {
        AsmRegistry.register(MissingInvokeCallTargetMixin::class.java)

        assertThrows(AsmTransformException::class.java) {
            AsmProcessor().transform("RedirectTarget", redirectTargetBytes(), javaClass.classLoader)
        }
    }

    @Test
    fun redirectWithMissingCallTargetFailsDuringTransform() {
        AsmRegistry.register(MissingRedirectCallTargetMixin::class.java)

        assertThrows(AsmTransformException::class.java) {
            AsmProcessor().transform("RedirectTarget", redirectTargetBytes(), javaClass.classLoader)
        }
    }

    @Test
    fun modifyConstantWithMissingConstantFailsDuringTransform() {
        AsmRegistry.register(MissingModifyConstantValueMixin::class.java)

        assertThrows(AsmTransformException::class.java) {
            AsmProcessor().transform("ReturnTarget", returnTargetBytes(), javaClass.classLoader)
        }
    }

    @Test
    fun modifyReturnValueOnVoidTargetFailsDuringTransform() {
        AsmRegistry.register(VoidModifyReturnValueMixin::class.java)

        assertThrows(AsmTransformException::class.java) {
            AsmProcessor().transform("StrictTarget", strictTargetBytes(), javaClass.classLoader)
        }
    }

    @Test
    fun invokeInjectMatchesCallTargetWithoutOwner() {
        AsmRegistry.register(InvokeWithoutOwnerTargetMixin::class.java)

        val transformed = AsmProcessor().transform("RedirectTarget", redirectTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val method = classNode.methods.single { it.name == "call" }
        val handlerCalls = method.instructions.toArray().filterIsInstance<org.objectweb.asm.tree.MethodInsnNode>().filter {
            it.name == "inject"
        }

        assertEquals(1, handlerCalls.size)
    }

    @Test
    fun redirectAllMethodsDoesNotRequireExplicitMethodTarget() {
        AsmRegistry.register(RedirectAllTrimMixin::class.java)

        AsmProcessor().transform("RedirectAllTarget", redirectAllTargetBytes(), javaClass.classLoader)
    }

    @Test
    fun kotlinObjectHandlerForStaticTargetUsesInstanceCallWhenNotJvmStatic() {
        AsmRegistry.register(ObjectInstanceStaticHeadMixin::class.java)

        val transformed = AsmProcessor().transform("StaticHeadTarget", staticHeadTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val method = classNode.methods.single { it.name == "run" }
        val mixinOwner = org.objectweb.asm.Type.getInternalName(ObjectInstanceStaticHeadMixin::class.java)
        val handlerCalls = method.instructions.toArray().filterIsInstance<org.objectweb.asm.tree.MethodInsnNode>().filter {
            it.owner == mixinOwner && it.name == "inject"
        }
        val instanceLoads = method.instructions.toArray().filterIsInstance<org.objectweb.asm.tree.FieldInsnNode>().filter {
            it.owner == mixinOwner && it.name == "INSTANCE" && it.opcode == Opcodes.GETSTATIC
        }

        assertEquals(1, handlerCalls.size)
        assertEquals(Opcodes.INVOKEVIRTUAL, handlerCalls.single().opcode)
        assertEquals(1, instanceLoads.size)
    }

    @Test
    fun kotlinObjectModifyArgHandlerForStaticTargetUsesInstanceCallWhenNotJvmStatic() {
        AsmRegistry.register(ObjectInstanceStaticModifyArgMixin::class.java)

        val transformed = AsmProcessor().transform("StaticArgTarget", staticArgTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val method = classNode.methods.single { it.name == "echo" }
        val mixinOwner = org.objectweb.asm.Type.getInternalName(ObjectInstanceStaticModifyArgMixin::class.java)
        val instructions = method.instructions.toArray()
        val callIndex = instructions.indexOfFirst {
            it is org.objectweb.asm.tree.MethodInsnNode && it.owner == mixinOwner && it.name == "modify"
        }
        assertEquals(true, callIndex >= 0)
        val call = instructions[callIndex] as org.objectweb.asm.tree.MethodInsnNode
        val instructionsBeforeCall = instructions.take(callIndex)
        val instanceLoad = instructionsBeforeCall.filterIsInstance<org.objectweb.asm.tree.FieldInsnNode>().lastOrNull {
            it.owner == mixinOwner && it.name == "INSTANCE" && it.opcode == Opcodes.GETSTATIC
        }
        val argumentLoad = instructionsBeforeCall.filterIsInstance<org.objectweb.asm.tree.VarInsnNode>().lastOrNull {
            it.opcode == Opcodes.ALOAD && it.`var` == 0
        }

        assertEquals(Opcodes.INVOKEVIRTUAL, call.opcode)
        assertEquals(true, instanceLoad != null)
        assertEquals(true, argumentLoad != null)
        assertEquals(true, instructionsBeforeCall.indexOf(instanceLoad) < instructionsBeforeCall.indexOf(argumentLoad))
    }

    @Test
    fun kotlinObjectModifyReturnHandlerForStaticTargetUsesInstanceCallWhenNotJvmStatic() {
        AsmRegistry.register(ObjectInstanceStaticModifyReturnMixin::class.java)

        val transformed = AsmProcessor().transform("StaticReturnTarget", staticReturnTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val method = classNode.methods.single { it.name == "value" }
        val mixinOwner = org.objectweb.asm.Type.getInternalName(ObjectInstanceStaticModifyReturnMixin::class.java)
        val instructions = method.instructions.toArray()
        val callIndex = instructions.indexOfFirst {
            it is org.objectweb.asm.tree.MethodInsnNode && it.owner == mixinOwner && it.name == "modify"
        }
        assertEquals(true, callIndex >= 0)
        val call = instructions[callIndex] as org.objectweb.asm.tree.MethodInsnNode
        val instructionsBeforeCall = instructions.take(callIndex)
        val instanceLoad = instructionsBeforeCall.filterIsInstance<org.objectweb.asm.tree.FieldInsnNode>().lastOrNull {
            it.owner == mixinOwner && it.name == "INSTANCE" && it.opcode == Opcodes.GETSTATIC
        }
        val returnValueLoad = instructionsBeforeCall.filterIsInstance<org.objectweb.asm.tree.VarInsnNode>().lastOrNull {
            it.opcode == Opcodes.ALOAD && it.`var` == 0
        }

        assertEquals(Opcodes.INVOKEVIRTUAL, call.opcode)
        assertEquals(true, instanceLoad != null)
        assertEquals(true, returnValueLoad != null)
        assertEquals(true, instructionsBeforeCall.indexOf(instanceLoad) < instructionsBeforeCall.indexOf(returnValueLoad))
    }

    @Test
    fun kotlinObjectOverwriteStaticTargetPreservesObjectReceiverForHelperCall() {
        AsmRegistry.register(ObjectInstanceStaticOverwriteMixin::class.java)

        val transformed = AsmProcessor().transform("StaticReturnTarget", staticReturnTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("StaticReturnTarget", transformed)
        val result = clazz.getMethod("value").invoke(null)

        assertEquals("helper", result)
    }

    @Test
    fun kotlinObjectOverwriteInstanceTargetPreservesObjectReceiverForHelperCall() {
        AsmRegistry.register(ObjectInstanceOverwriteMixin::class.java)

        val transformed = AsmProcessor().transform("ReturnTarget", returnTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ReturnTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("helper", result)
    }

    @Test
    fun kotlinObjectCopyPreservesObjectReceiverForHelperCall() {
        AsmRegistry.register(ObjectInstanceCopyMixin::class.java)

        val transformed = AsmProcessor().transform("ReturnTarget", returnTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ReturnTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("copied").invoke(instance)

        assertEquals("helper", result)
    }

    @Test
    fun accessorMethodConflictFailsDuringTransform() {
        AsmRegistry.register(ConflictingAccessorMixin::class.java)

        assertThrows(AsmTransformException::class.java) {
            AsmProcessor().transform("AccessorConflictTarget", accessorConflictTargetBytes(), javaClass.classLoader)
        }
    }

    @Test
    fun invokerMethodConflictFailsDuringTransform() {
        AsmRegistry.register(ConflictingInvokerMixin::class.java)

        assertThrows(AsmTransformException::class.java) {
            AsmProcessor().transform("InvokerConflictTarget", invokerConflictTargetBytes(), javaClass.classLoader)
        }
    }

    @Test
    fun modifyConstantDoesNotTreatNewInstructionAsClassConstant() {
        AsmRegistry.register(ClassConstantModifyMixin::class.java)

        val transformed = AsmProcessor().transform("NewInstructionTarget", newInstructionTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val method = classNode.methods.single { it.name == "create" }
        val hasNewStringBuilder = method.instructions.toArray().any {
            it is org.objectweb.asm.tree.TypeInsnNode &&
                it.opcode == Opcodes.NEW &&
                it.desc == "java/lang/StringBuilder"
        }

        assertEquals(true, hasNewStringBuilder)
    }

    @Test
    fun modifyConstantDoesNotTreatCheckcastAsClassConstant() {
        AsmRegistry.register(CheckcastConstantModifyMixin::class.java)

        val transformed = AsmProcessor().transform("CastInstructionTarget", castInstructionTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val method = classNode.methods.single { it.name == "cast" }
        val methodCalls = method.instructions.toArray().filterIsInstance<org.objectweb.asm.tree.MethodInsnNode>().map { it.name }
        val hasCheckcast = method.instructions.toArray().any {
            it is org.objectweb.asm.tree.TypeInsnNode &&
                it.opcode == Opcodes.CHECKCAST &&
                it.desc == "java/lang/String"
        }

        assertEquals(true, hasCheckcast)
        assertEquals(false, methodCalls.contains("modify"))
    }

    @Test
    fun overwriteDoesNotRewriteNonShadowOverloadByNameOnly() {
        AsmRegistry.register(ShadowOverloadOverwriteMixin::class.java)

        assertThrows(AsmTransformException::class.java) {
            AsmProcessor().transform("ShadowOverloadTarget", shadowOverloadTargetBytes(), javaClass.classLoader)
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

    @AsmMixin("ReturnTarget")
    object InlineVoidHeadReturnTargetMixin {
        @AsmInject(method = "value()Ljava/lang/String;", inline = true)
        @JvmStatic
        fun injectInline() {
            "side-effect".length
        }
    }

    @AsmMixin("ReturnTarget")
    object InlineStringHeadReturnTargetMixin {
        @AsmInject(method = "value()Ljava/lang/String;", inline = true)
        @JvmStatic
        fun injectInline(): String = "handler"
    }

    @AsmMixin("ReturnTarget")
    object InlineStringReturnTargetMixin {
        @AsmInject(method = "value()Ljava/lang/String;", target = InjectionPoint.RETURN, inline = true)
        @JvmStatic
        fun injectInline(): String = "handler"
    }

    @AsmMixin("ReturnTarget")
    class ClassTailInjectMixin {
        @AsmInject(method = "value()Ljava/lang/String;", target = InjectionPoint.TAIL)
        fun injectTail() {
        }
    }

    @AsmMixin("InlineTarget")
    object ObjectInstanceInlineMixin {
        @AsmInject(method = "run()V", inline = true)
        fun injectInline() {
            helper()
        }

        fun helper(): String = "helper"
    }

    @AsmMixin("StaticHeadTarget")
    object ObjectInstanceStaticInlineMixin {
        @AsmInject(method = "run()V", inline = true)
        fun injectInline() {
            helper()
        }

        fun helper(): String = "helper"
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

    @AsmMixin("StrictTarget")
    object MissingOverwriteTargetMixin {
        @Overwrite("missing()V")
        @JvmStatic
        fun missing() {
        }
    }

    @AsmMixin("StrictTarget")
    object MissingRemoveMethodTargetMixin {
        @RemoveMethod("missing()V")
        @JvmStatic
        fun missing() {
        }
    }

    @AsmMixin("StrictTarget")
    object MissingRemoveSynchronizedTargetMixin {
        @RemoveSynchronized("missing()V")
        @JvmStatic
        fun missing() {
        }
    }

    @AsmMixin("StrictTarget")
    object MissingInjectTargetMixin {
        @AsmInject(method = "missing()V")
        @JvmStatic
        fun inject() {
        }
    }

    @AsmMixin("StrictTarget")
    object MissingModifyArgTargetMixin {
        @ModifyArg(method = "missing(Ljava/lang/String;)Ljava/lang/String;", index = 0)
        @JvmStatic
        fun modify(original: String): String = original
    }

    @AsmMixin("StrictTarget")
    object MissingRedirectTargetMixin {
        @Redirect(
            method = "missing()V",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.trim()Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun redirect(value: String): String = value
    }

    @AsmMixin("StrictTarget")
    object MissingModifyReturnTargetMixin {
        @ModifyReturnValue(method = "missing()Ljava/lang/String;")
        @JvmStatic
        fun modify(original: String): String = original
    }

    @AsmMixin("StrictTarget")
    object MissingModifyConstantTargetMixin {
        @ModifyConstant(method = "missing()Ljava/lang/String;", constant = "value")
        @JvmStatic
        fun modify(original: String): String = original
    }

    @AsmMixin("RedirectTarget")
    object MissingInvokeCallTargetMixin {
        @AsmInject(
            method = "call()Ljava/lang/String;",
            target = InjectionPoint.INVOKE,
            at = At(target = "java/lang/String.strip()Ljava/lang/String;"),
        )
        @JvmStatic
        fun inject() {
        }
    }

    @AsmMixin("RedirectTarget")
    object MissingRedirectCallTargetMixin {
        @Redirect(
            method = "call()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.strip()Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun redirect(value: String): String = value
    }

    @AsmMixin("ReturnTarget")
    object MissingModifyConstantValueMixin {
        @ModifyConstant(method = "value()Ljava/lang/String;", constant = "missing")
        @JvmStatic
        fun modify(original: String): String = original
    }

    @AsmMixin("StrictTarget")
    object VoidModifyReturnValueMixin {
        @ModifyReturnValue(method = "keep()V")
        @JvmStatic
        fun modify(original: String): String = original
    }

    @AsmMixin("RedirectTarget")
    object InvokeWithoutOwnerTargetMixin {
        @AsmInject(
            method = "call()Ljava/lang/String;",
            target = InjectionPoint.INVOKE,
            at = At(
                value = InjectionPoint.INVOKE,
                target = "trim()Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun inject() {
        }
    }

    @AsmMixin("RedirectAllTarget")
    @RedirectAllMethods
    object RedirectAllTrimMixin {
        @Redirect(
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.trim()Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun redirect(value: String): String = value
    }

    @AsmMixin("StaticHeadTarget")
    object ObjectInstanceStaticHeadMixin {
        @AsmInject(method = "run()V")
        fun inject() {
        }
    }

    @AsmMixin("StaticArgTarget")
    object ObjectInstanceStaticModifyArgMixin {
        @ModifyArg(method = "echo(Ljava/lang/String;)Ljava/lang/String;", index = 0)
        fun modify(original: String): String = original
    }

    @AsmMixin("StaticReturnTarget")
    object ObjectInstanceStaticModifyReturnMixin {
        @ModifyReturnValue(method = "value()Ljava/lang/String;")
        fun modify(original: String): String = original
    }

    @AsmMixin("StaticReturnTarget")
    object ObjectInstanceStaticOverwriteMixin {
        @Overwrite("value()Ljava/lang/String;")
        fun value(): String = helper()

        fun helper(): String = "helper"
    }

    @AsmMixin("ReturnTarget")
    object ObjectInstanceOverwriteMixin {
        @Overwrite("value()Ljava/lang/String;")
        fun value(): String = helper()

        fun helper(): String = "helper"
    }

    @AsmMixin("ReturnTarget")
    object ObjectInstanceCopyMixin {
        @Copy("copied()Ljava/lang/String;")
        fun copied(): String = helper()

        fun helper(): String = "helper"
    }

    @AsmMixin("AccessorConflictTarget")
    class ConflictingAccessorMixin {
        @Accessor("name")
        fun getName(): String = throw UnsupportedOperationException()
    }

    @AsmMixin("InvokerConflictTarget")
    class ConflictingInvokerMixin {
        @Invoker("target")
        fun invokeTarget(): String = throw UnsupportedOperationException()
    }

    @AsmMixin("NewInstructionTarget")
    object ClassConstantModifyMixin {
        @ModifyConstant(method = "create()Ljava/lang/StringBuilder;")
        @JvmStatic
        fun modify(type: Class<*>): Class<*> = type
    }

    @AsmMixin("CastInstructionTarget")
    object CheckcastConstantModifyMixin {
        @ModifyConstant(method = "cast(Ljava/lang/Object;)Ljava/lang/String;")
        @JvmStatic
        fun modify(type: Class<*>): Class<*> = type
    }

    @AsmMixin("ShadowOverloadTarget")
    class ShadowOverloadOverwriteMixin {
        @Shadow
        private fun lookup(value: String): String = throw UnsupportedOperationException()

        private fun lookup(value: Int): String = value.toString()

        @Overwrite("value()Ljava/lang/String;")
        fun value(): String = lookup(1)
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

    private fun redirectAllTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "RedirectAllTarget", null, "java/lang/Object", null)
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

    private fun staticHeadTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "StaticHeadTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "run", "()V", null, null).apply {
            visitCode()
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun staticArgTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "StaticArgTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "echo", "(Ljava/lang/String;)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun staticReturnTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "StaticReturnTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("value")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 0)
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
    private fun accessorConflictTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "AccessorConflictTarget", null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE, "name", "Ljava/lang/String;", null, null).visitEnd()
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "getName", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("existing")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }
    private fun invokerConflictTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "InvokerConflictTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "target", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("target")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "invokeTarget", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("existing")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }
    private fun newInstructionTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "NewInstructionTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "create", "()Ljava/lang/StringBuilder;", null, null).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder")
            visitInsn(Opcodes.DUP)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }
    private fun castInstructionTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "CastInstructionTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "cast", "(Ljava/lang/Object;)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 1)
            visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 2)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }
    private fun shadowOverloadTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "ShadowOverloadTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("original")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "lookup", "(Ljava/lang/String;)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 1)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 2)
            visitEnd()
        }
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

    private fun loadClass(
        className: String,
        bytes: ByteArray,
    ): Class<*> {
        val loader =
            object : ClassLoader(Thread.currentThread().contextClassLoader) {
                override fun findClass(name: String): Class<*> {
                    if (name == className) {
                        return defineClass(name, bytes, 0, bytes.size)
                    }
                    throw ClassNotFoundException(name)
                }
            }
        return loader.loadClass(className)
    }
}




