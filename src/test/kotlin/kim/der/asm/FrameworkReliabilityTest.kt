/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm

import kim.der.asm.api.annotation.Accessor
import kim.der.asm.api.annotation.AddField
import kim.der.asm.api.annotation.AddInterface
import kim.der.asm.api.annotation.Args
import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.At
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.Invoker
import kim.der.asm.api.annotation.ModifyArg
import kim.der.asm.api.annotation.ModifyArgs
import kim.der.asm.api.annotation.ModifyConstant
import kim.der.asm.api.annotation.ModifyExpressionValue
import kim.der.asm.api.annotation.ModifyReceiver
import kim.der.asm.api.annotation.ModifyReturnValue
import kim.der.asm.api.annotation.ModifyVariable
import kim.der.asm.api.annotation.Mutable
import kim.der.asm.api.annotation.Operation
import kim.der.asm.api.annotation.Redirect
import kim.der.asm.api.annotation.RedirectAllMethods
import kim.der.asm.api.annotation.Copy
import kim.der.asm.api.annotation.Overwrite
import kim.der.asm.api.annotation.ReplaceAllMethods
import kim.der.asm.api.annotation.RemoveField
import kim.der.asm.api.annotation.RemoveInterface
import kim.der.asm.api.annotation.RemoveMethod
import kim.der.asm.api.annotation.RemoveSynchronized
import kim.der.asm.api.annotation.Shadow
import kim.der.asm.api.annotation.Shift
import kim.der.asm.api.annotation.Slice
import kim.der.asm.api.annotation.Unique
import kim.der.asm.api.annotation.WrapMethod
import kim.der.asm.api.annotation.WrapOperation
import kim.der.asm.api.annotation.WrapWithCondition
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
import java.io.ByteArrayOutputStream
import java.io.PrintStream
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
    fun redirectWithTooManyTargetMethodParametersFailsDuringTransform() {
        AsmRegistry.register(TooManyRedirectTargetParametersMixin::class.java)

        assertThrows(AsmTransformException::class.java) {
            AsmProcessor().transform("RedirectParamTarget", redirectParamTargetBytes(), javaClass.classLoader)
        }
    }

    @Test
    fun redirectExposesCountContractParameters() {
        val methods = Redirect::class.java.declaredMethods.associateBy { it.name }

        assertEquals(Int::class.javaPrimitiveType, methods["require"]?.returnType)
        assertEquals(Int::class.javaPrimitiveType, methods["expect"]?.returnType)
        assertEquals(Int::class.javaPrimitiveType, methods["allow"]?.returnType)
    }

    @Test
    fun injectionPointExposesInstanceofExpressionPoint() {
        val names = InjectionPoint.entries.map { it.name }

        assertEquals(true, "INSTANCEOF" in names)
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
    fun returnInjectionBoxesCharReturnValueWithCharacterWrapper() {
        AsmRegistry.register(CharReturnCallbackMixin::class.java)

        val transformed = AsmProcessor().transform("CharReturnTarget", charReturnTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("CharReturnTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals('a', result)
    }

    @Test
    fun returnInjectOrdinalSelectsSingleMatchedReturnPoint() {
        AsmRegistry.register(ReturnOrdinalMixin::class.java)

        val transformed = AsmProcessor().transform("MultiReturnTarget", multiReturnTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val method = classNode.methods.single { it.name == "value" && it.desc == "(Z)Ljava/lang/String;" }
        val instructions = method.instructions.toArray()
        val mixinOwner = org.objectweb.asm.Type.getInternalName(ReturnOrdinalMixin::class.java)
        val handlerCallIndexes = instructions.mapIndexedNotNull { index, insn ->
            if (insn is org.objectweb.asm.tree.MethodInsnNode && insn.owner == mixinOwner && insn.name == "inject") {
                index
            } else {
                null
            }
        }
        val returnIndexes = instructions.mapIndexedNotNull { index, insn ->
            if (insn.opcode == Opcodes.ARETURN) {
                index
            } else {
                null
            }
        }

        assertEquals(2, returnIndexes.size)
        assertEquals(1, handlerCallIndexes.size)
        assertEquals(true, handlerCallIndexes.single() > returnIndexes[0])
        assertEquals(true, handlerCallIndexes.single() < returnIndexes[1])
    }

    @Test
    fun modifyReturnValueOrdinalSelectsSingleMatchedReturnPoint() {
        AsmRegistry.register(ModifyReturnValueOrdinalMixin::class.java)

        val transformed = AsmProcessor().transform("MultiReturnTarget", multiReturnTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("MultiReturnTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        assertEquals("first", clazz.getMethod("value", Boolean::class.javaPrimitiveType).invoke(instance, true))
        assertEquals("modified-second", clazz.getMethod("value", Boolean::class.javaPrimitiveType).invoke(instance, false))
    }

    @Test
    fun modifyReturnValueExposesCountContractParameters() {
        val methods = ModifyReturnValue::class.java.declaredMethods.associateBy { it.name }

        assertEquals(Int::class.javaPrimitiveType, methods["require"]?.returnType)
        assertEquals(Int::class.javaPrimitiveType, methods["expect"]?.returnType)
        assertEquals(Int::class.javaPrimitiveType, methods["allow"]?.returnType)
    }

    @Test
    fun modifyReturnValueRequireGreaterThanMatchedCountFailsDuringTransform() {
        AsmRegistry.register(RequireThreeModifyReturnValueMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("MultiReturnTarget", multiReturnTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("requires at least 3 injection(s), actual 2") == true,
        )
    }

    @Test
    fun modifyReturnValueAllowLessThanMatchedCountFailsDuringTransform() {
        AsmRegistry.register(AllowOneModifyReturnValueMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("MultiReturnTarget", multiReturnTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("allows at most 1 injection(s), actual 2") == true,
        )
    }

    @Test
    fun modifyReturnValueExpectMismatchReportsWarningWithoutFailingTransform() {
        AsmRegistry.register(ExpectThreeModifyReturnValueMixin::class.java)
        val originalErr = System.err
        val output = ByteArrayOutputStream()

        try {
            PrintStream(output, true, Charsets.UTF_8.name()).use { capture ->
                System.setErr(capture)
                AsmProcessor().transform("MultiReturnTarget", multiReturnTargetBytes(), javaClass.classLoader)
            }
        } finally {
            System.setErr(originalErr)
        }

        assertEquals(
            true,
            output.toString(Charsets.UTF_8.name()).contains("expected 3 injection(s), actual 2"),
        )
    }

    @Test
    fun asmInjectRequireGreaterThanMatchedCountFailsDuringTransform() {
        AsmRegistry.register(RequireThreeReturnInjectMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("MultiReturnTarget", multiReturnTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("requires at least 3 injection(s), actual 2") == true,
        )
    }

    @Test
    fun asmInjectAllowLessThanMatchedCountFailsDuringTransform() {
        AsmRegistry.register(AllowOneReturnInjectMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("MultiReturnTarget", multiReturnTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("allows at most 1 injection(s), actual 2") == true,
        )
    }

    @Test
    fun asmInjectExpectMismatchReportsWarningWithoutFailingTransform() {
        AsmRegistry.register(ExpectThreeReturnInjectMixin::class.java)
        val originalErr = System.err
        val output = ByteArrayOutputStream()

        try {
            PrintStream(output, true, Charsets.UTF_8.name()).use { capture ->
                System.setErr(capture)
                AsmProcessor().transform("MultiReturnTarget", multiReturnTargetBytes(), javaClass.classLoader)
            }
        } finally {
            System.setErr(originalErr)
        }

        assertEquals(
            true,
            output.toString(Charsets.UTF_8.name()).contains("expected 3 injection(s), actual 2"),
        )
    }

    @Test
    fun invokeReplaceWithIncompatibleReturnTypeFailsDuringTransform() {
        AsmRegistry.register(IncompatibleInvokeReplaceMixin::class.java)

        assertThrows(AsmTransformException::class.java) {
            AsmProcessor().transform("RedirectTarget", redirectTargetBytes(), javaClass.classLoader)
        }
    }

    @Test
    fun invokeBeforeInjectionDropsUnusedHandlerReturnValue() {
        AsmRegistry.register(InvokeBeforeReturningHandlerMixin::class.java)

        val transformed = AsmProcessor().transform("RedirectTarget", redirectTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val method = classNode.methods.single { it.name == "call" }
        val mixinOwner = org.objectweb.asm.Type.getInternalName(InvokeBeforeReturningHandlerMixin::class.java)
        val instructions = method.instructions.toArray()
        val handlerCallIndex = instructions.indexOfFirst {
            it is org.objectweb.asm.tree.MethodInsnNode && it.owner == mixinOwner && it.name == "inject"
        }

        assertEquals(true, handlerCallIndex >= 0)
        assertEquals(Opcodes.POP, instructions[handlerCallIndex + 1].opcode)
    }

    @Test
    fun invokeAfterInjectionDropsWideUnusedHandlerReturnValue() {
        AsmRegistry.register(InvokeAfterWideReturningHandlerMixin::class.java)

        val transformed = AsmProcessor().transform("RedirectTarget", redirectTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val method = classNode.methods.single { it.name == "call" }
        val mixinOwner = org.objectweb.asm.Type.getInternalName(InvokeAfterWideReturningHandlerMixin::class.java)
        val instructions = method.instructions.toArray()
        val handlerCallIndex = instructions.indexOfFirst {
            it is org.objectweb.asm.tree.MethodInsnNode && it.owner == mixinOwner && it.name == "inject"
        }

        assertEquals(true, handlerCallIndex >= 0)
        assertEquals(Opcodes.POP2, instructions[handlerCallIndex + 1].opcode)
    }

    @Test
    fun invokeAfterInjectionPreservesCallReturnValueWhenUsingCallbackInfo() {
        AsmRegistry.register(InvokeAfterCallbackInfoMixin::class.java)

        val transformed = AsmProcessor().transform("RedirectTarget", redirectTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("RedirectTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("call").invoke(instance)

        assertEquals("value", result)
    }

    @Test
    fun headInjectionDropsWideUnusedHandlerReturnValueOnVoidTarget() {
        AsmRegistry.register(HeadWideReturningHandlerMixin::class.java)

        val transformed = AsmProcessor().transform("StrictTarget", strictTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("StrictTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("keep").invoke(instance)
    }

    @Test
    fun tailInjectionDropsWideUnusedHandlerReturnValueOnVoidTarget() {
        AsmRegistry.register(TailWideReturningHandlerMixin::class.java)

        val transformed = AsmProcessor().transform("StrictTarget", strictTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("StrictTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("keep").invoke(instance)
    }

    @Test
    fun returnInjectionDropsWideUnusedHandlerReturnValueOnVoidTarget() {
        AsmRegistry.register(ReturnWideReturningHandlerMixin::class.java)

        val transformed = AsmProcessor().transform("StrictTarget", strictTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("StrictTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("keep").invoke(instance)
    }

    @Test
    fun invokeBeforeInjectionMapsStaticCallArguments() {
        AsmRegistry.register(InvokeBeforeStaticCallArgumentMixin::class.java)

        val transformed = AsmProcessor().transform("StaticInvokeArgTarget", staticInvokeArgTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("StaticInvokeArgTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("42", result)
    }

    @Test
    fun invokeAfterInjectionMapsStaticCallArguments() {
        AsmRegistry.register(InvokeAfterStaticCallArgumentMixin::class.java)

        val transformed = AsmProcessor().transform("StaticInvokeArgTarget", staticInvokeArgTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("StaticInvokeArgTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("42", result)
    }

    @Test
    fun invokeBeforeInjectionCanUseTargetMethodParametersAfterCallArguments() {
        AsmRegistry.register(InvokeBeforeWithTargetParamsMixin::class.java)

        val transformed = AsmProcessor().transform("StaticRedirectParamTarget", staticRedirectParamTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("StaticRedirectParamTarget", transformed)
        val result = clazz.getMethod("value", String::class.java, Int::class.javaPrimitiveType).invoke(null, "suffix", 4)

        assertEquals("42", result)
    }

    @Test
    fun invokeAfterInjectionCanUseTargetMethodParametersWithCallbackInfo() {
        AsmRegistry.register(InvokeAfterWithCallbackAndTargetParamsMixin::class.java)

        val transformed = AsmProcessor().transform("StaticRedirectParamTarget", staticRedirectParamTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("StaticRedirectParamTarget", transformed)
        val result = clazz.getMethod("value", String::class.java, Int::class.javaPrimitiveType).invoke(null, "suffix", 5)

        assertEquals("42", result)
    }

    @Test
    fun invokeInjectionCanUseTargetMethodParametersWithoutCallArguments() {
        AsmRegistry.register(InvokeWithTargetParamsWithoutCallArgumentsMixin::class.java)

        val transformed = AsmProcessor().transform("RedirectParamTarget", redirectParamTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("RedirectParamTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value", String::class.java, Int::class.javaPrimitiveType).invoke(instance, "suffix", 6)

        assertEquals("base", result)
    }

    @Test
    fun invokeInjectionWithTooManyTargetMethodParametersFailsDuringTransform() {
        AsmRegistry.register(TooManyInvokeTargetParametersMixin::class.java)

        assertThrows(AsmTransformException::class.java) {
            AsmProcessor().transform("StaticRedirectParamTarget", staticRedirectParamTargetBytes(), javaClass.classLoader)
        }
    }

    @Test
    fun invokeBeforeInjectionDoesNotOverlapWideCallArgumentLocals() {
        AsmRegistry.register(InvokeBeforeWideStaticCallArgumentMixin::class.java)

        val transformed = AsmProcessor().transform("WideInvokeArgTarget", wideInvokeArgTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("WideInvokeArgTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("ok", result)
    }

    @Test
    fun modifyArgWithTooManyHandlerParametersFailsDuringTransform() {
        AsmRegistry.register(TooManyModifyArgParametersMixin::class.java)

        assertThrows(AsmTransformException::class.java) {
            AsmProcessor().transform("ArgTarget", argTargetBytes(), javaClass.classLoader)
        }
    }

    @Test
    fun modifyArgExposesCountContractParameters() {
        val methods = ModifyArg::class.java.declaredMethods.associateBy { it.name }

        assertEquals(Int::class.javaPrimitiveType, methods["require"]?.returnType)
        assertEquals(Int::class.javaPrimitiveType, methods["expect"]?.returnType)
        assertEquals(Int::class.javaPrimitiveType, methods["allow"]?.returnType)
    }

    @Test
    fun modifyArgCanUseTargetMethodParametersAtMethodStart() {
        AsmRegistry.register(ModifyArgWithTargetParamsMixin::class.java)

        val transformed = AsmProcessor().transform("ArgTarget", argTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ArgTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("echo", String::class.java).invoke(instance, "value")

        assertEquals("value-value", result)
    }

    @Test
    fun modifyArgCanUseStaticTargetMethodParametersAtMethodStart() {
        AsmRegistry.register(StaticModifyArgWithTargetParamsMixin::class.java)

        val transformed = AsmProcessor().transform("StaticArgTarget", staticArgTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("StaticArgTarget", transformed)
        val result = clazz.getMethod("echo", String::class.java).invoke(null, "value")

        assertEquals("value-value-static", result)
    }

    @Test
    fun modifyArgAtInvokeRewritesSelectedCallArgument() {
        AsmRegistry.register(InvokeModifyArgMixin::class.java)

        val transformed = AsmProcessor().transform("InvokeModifyArgTarget", invokeModifyArgTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("InvokeModifyArgTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("prefix-modified", result)
    }

    @Test
    fun modifyArgAtInvokeCanUseTargetMethodParameters() {
        AsmRegistry.register(InvokeModifyArgWithTargetParamsMixin::class.java)

        val transformed = AsmProcessor().transform(
            "InvokeModifyArgParamTarget",
            invokeModifyArgParamTargetBytes(),
            javaClass.classLoader,
        )
        val clazz = loadClass("InvokeModifyArgParamTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value", String::class.java, Int::class.javaPrimitiveType).invoke(instance, "suffix", 7)

        assertEquals("prefix-original-suffix7", result)
    }

    @Test
    fun modifyArgOrdinalSelectsSingleInvokeCallArgument() {
        AsmRegistry.register(InvokeModifyArgOrdinalMixin::class.java)

        val transformed = AsmProcessor().transform("MultiInvokeModifyArgTarget", multiInvokeModifyArgTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("MultiInvokeModifyArgTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("first-original:second-modified", result)
    }

    @Test
    fun modifyArgRequireGreaterThanMatchedCountFailsDuringTransform() {
        AsmRegistry.register(RequireThreeModifyArgMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("ModifyArgContractTarget", modifyArgContractTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("requires at least 3 injection(s), actual 2") == true,
        )
    }

    @Test
    fun modifyArgAllowLessThanMatchedCountFailsDuringTransform() {
        AsmRegistry.register(AllowOneModifyArgMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("ModifyArgContractTarget", modifyArgContractTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("allows at most 1 injection(s), actual 2") == true,
        )
    }

    @Test
    fun modifyArgExpectMismatchReportsWarningWithoutFailingTransform() {
        AsmRegistry.register(ExpectThreeModifyArgMixin::class.java)
        val originalErr = System.err
        val output = ByteArrayOutputStream()

        try {
            PrintStream(output, true, Charsets.UTF_8.name()).use { capture ->
                System.setErr(capture)
                AsmProcessor().transform("ModifyArgContractTarget", modifyArgContractTargetBytes(), javaClass.classLoader)
            }
        } finally {
            System.setErr(originalErr)
        }

        assertEquals(
            true,
            output.toString(Charsets.UTF_8.name()).contains("expected 3 injection(s), actual 2"),
        )
    }

    @Test
    fun modifyArgSliceLimitsInvokeCallArgumentMatchesBetweenFromAndTo() {
        AsmRegistry.register(InvokeModifyArgSliceMixin::class.java)

        val transformed = AsmProcessor().transform("SliceInvokeModifyArgTarget", sliceInvokeModifyArgTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("SliceInvokeModifyArgTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("pre-original:inside-modified:outside-original", result)
    }

    @Test
    fun modifyArgAtConstructorInvokeRewritesSelectedArgument() {
        AsmRegistry.register(ConstructorModifyArgMixin::class.java)

        val transformed = AsmProcessor().transform("ConstructorModifyArgTarget", constructorModifyArgTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ConstructorModifyArgTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("changed", result)
    }

    @Test
    fun modifyArgsExposesCountContractParameters() {
        val methods = ModifyArgs::class.java.declaredMethods.associateBy { it.name }

        assertEquals(Int::class.javaPrimitiveType, methods["require"]?.returnType)
        assertEquals(Int::class.javaPrimitiveType, methods["expect"]?.returnType)
        assertEquals(Int::class.javaPrimitiveType, methods["allow"]?.returnType)
    }

    @Test
    fun modifyArgsRequireGreaterThanMatchedCountFailsDuringTransform() {
        AsmRegistry.register(RequireThreeModifyArgsMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("MultiModifyArgsTarget", multiModifyArgsTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("requires at least 3 injection(s), actual 2") == true,
        )
    }

    @Test
    fun modifyArgsAllowLessThanMatchedCountFailsDuringTransform() {
        AsmRegistry.register(AllowOneModifyArgsMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("MultiModifyArgsTarget", multiModifyArgsTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("allows at most 1 injection(s), actual 2") == true,
        )
    }

    @Test
    fun modifyArgsExpectMismatchReportsWarningWithoutFailingTransform() {
        AsmRegistry.register(ExpectThreeModifyArgsMixin::class.java)
        val originalErr = System.err
        val output = ByteArrayOutputStream()

        try {
            PrintStream(output, true, Charsets.UTF_8.name()).use { capture ->
                System.setErr(capture)
                AsmProcessor().transform("MultiModifyArgsTarget", multiModifyArgsTargetBytes(), javaClass.classLoader)
            }
        } finally {
            System.setErr(originalErr)
        }

        assertEquals(
            true,
            output.toString(Charsets.UTF_8.name()).contains("expected 3 injection(s), actual 2"),
        )
    }

    @Test
    fun modifyArgsAtInvokeRewritesMultipleCallArguments() {
        AsmRegistry.register(ModifyArgsReplaceMixin::class.java)

        val transformed = AsmProcessor().transform("ModifyArgsTarget", modifyArgsTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ModifyArgsTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("hello changed", result)
    }

    @Test
    fun modifyArgsAtInvokeCanUseTargetMethodParameters() {
        AsmRegistry.register(ModifyArgsWithTargetParamsMixin::class.java)

        val transformed = AsmProcessor().transform("ModifyArgsParamTarget", modifyArgsParamTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ModifyArgsParamTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value", String::class.java, Int::class.javaPrimitiveType).invoke(instance, "suffix", 7)

        assertEquals("left-suffix-right-7", result)
    }

    @Test
    fun modifyArgsOrdinalSelectsSingleInvokeCall() {
        AsmRegistry.register(ModifyArgsOrdinalMixin::class.java)

        val transformed = AsmProcessor().transform("MultiModifyArgsTarget", multiModifyArgsTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("MultiModifyArgsTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("first raw:second changed", result)
    }

    @Test
    fun modifyArgsSliceLimitsInvokeCallMatchesBetweenFromAndTo() {
        AsmRegistry.register(ModifyArgsSliceMixin::class.java)

        val transformed = AsmProcessor().transform("SliceModifyArgsTarget", sliceModifyArgsTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("SliceModifyArgsTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("pre raw:inside changed:outside raw", result)
    }

    @Test
    fun modifyArgsWithMismatchedHandlerParametersFailsDuringTransform() {
        AsmRegistry.register(MismatchedModifyArgsParametersMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("ModifyArgsTarget", modifyArgsTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("first parameter must be Args") == true,
        )
    }

    @Test
    fun wrapWithConditionAtInvokeSkipsStaticVoidCallWhenFalse() {
        AsmRegistry.register(WrapConditionStaticDenyMixin::class.java)

        val transformed = AsmProcessor().transform("WrapConditionStaticTarget", wrapConditionStaticTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("WrapConditionStaticTarget", transformed)
        val result = clazz.getMethod("run").invoke(null)

        assertEquals(null, result)
    }

    @Test
    fun wrapWithConditionAtInvokeAllowsStaticVoidCallWhenTrue() {
        AsmRegistry.register(WrapConditionStaticAllowMixin::class.java)

        val transformed = AsmProcessor().transform("WrapConditionStaticTarget", wrapConditionStaticTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("WrapConditionStaticTarget", transformed)
        val result = clazz.getMethod("run").invoke(null)

        assertEquals("raw", result)
    }

    @Test
    fun wrapWithConditionAtInvokeReceivesInstanceReceiverAndCallArguments() {
        AsmRegistry.register(WrapConditionInstanceCallMixin::class.java)

        val transformed = AsmProcessor().transform("WrapConditionInstanceTarget", wrapConditionInstanceTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("WrapConditionInstanceTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("run").invoke(instance)

        assertEquals("raw3", result)
    }

    @Test
    fun wrapWithConditionAtInvokeCanUseTargetMethodParameters() {
        AsmRegistry.register(WrapConditionWithTargetParamsMixin::class.java)

        val transformed = AsmProcessor().transform("WrapConditionParamTarget", wrapConditionParamTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("WrapConditionParamTarget", transformed)
        val result = clazz.getMethod("run", String::class.java, Int::class.javaPrimitiveType).invoke(null, "suffix", 7)

        assertEquals("raw-suffix7", result)
    }

    @Test
    fun wrapWithConditionOrdinalSelectsSingleInvokeCall() {
        AsmRegistry.register(WrapConditionOrdinalMixin::class.java)

        val transformed = AsmProcessor().transform("MultiWrapConditionTarget", multiWrapConditionTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("MultiWrapConditionTarget", transformed)
        val result = clazz.getMethod("run").invoke(null)

        assertEquals("first", result)
    }

    @Test
    fun wrapWithConditionExposesCountContractParameters() {
        val methods = WrapWithCondition::class.java.declaredMethods.associateBy { it.name }

        assertEquals(Int::class.javaPrimitiveType, methods["require"]?.returnType)
        assertEquals(Int::class.javaPrimitiveType, methods["expect"]?.returnType)
        assertEquals(Int::class.javaPrimitiveType, methods["allow"]?.returnType)
    }

    @Test
    fun wrapWithConditionRequireGreaterThanMatchedCountFailsDuringTransform() {
        AsmRegistry.register(RequireThreeWrapConditionMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("MultiWrapConditionTarget", multiWrapConditionTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("requires at least 3 injection(s), actual 2") == true,
        )
    }

    @Test
    fun wrapWithConditionAllowLessThanMatchedCountFailsDuringTransform() {
        AsmRegistry.register(AllowOneWrapConditionMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("MultiWrapConditionTarget", multiWrapConditionTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("allows at most 1 injection(s), actual 2") == true,
        )
    }

    @Test
    fun wrapWithConditionExpectMismatchReportsWarningWithoutFailingTransform() {
        AsmRegistry.register(ExpectThreeWrapConditionMixin::class.java)
        val originalErr = System.err
        val output = ByteArrayOutputStream()

        try {
            PrintStream(output, true, Charsets.UTF_8.name()).use { capture ->
                System.setErr(capture)
                AsmProcessor().transform("MultiWrapConditionTarget", multiWrapConditionTargetBytes(), javaClass.classLoader)
            }
        } finally {
            System.setErr(originalErr)
        }

        assertEquals(
            true,
            output.toString(Charsets.UTF_8.name()).contains("expected 3 injection(s), actual 2"),
        )
    }

    @Test
    fun wrapWithConditionSliceLimitsInvokeCallMatchesBetweenFromAndTo() {
        AsmRegistry.register(WrapConditionSliceMixin::class.java)

        val transformed = AsmProcessor().transform("SliceWrapConditionTarget", sliceWrapConditionTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("SliceWrapConditionTarget", transformed)
        val result = clazz.getMethod("run").invoke(null)

        assertEquals("preoutside", result)
    }

    @Test
    fun wrapWithConditionRejectsNonVoidInvokeCall() {
        AsmRegistry.register(WrapConditionNonVoidCallMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("ExpressionValueTarget", expressionValueTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("only supports void method calls") == true,
        )
    }

    @Test
    fun wrapWithConditionWithNonBooleanHandlerFailsDuringTransform() {
        AsmRegistry.register(WrapConditionNonBooleanHandlerMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("WrapConditionStaticTarget", wrapConditionStaticTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("must return boolean") == true,
        )
    }

    @Test
    fun wrapWithConditionAtFieldAssignSkipsPutFieldWhenFalse() {
        AsmRegistry.register(WrapConditionFieldAssignDenyMixin::class.java)

        val transformed = AsmProcessor().transform("FieldPointTarget", fieldPointTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("FieldPointTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeName", String::class.java).invoke(instance, "blocked")
        val result = clazz.getMethod("readName").invoke(instance)

        assertEquals(null, result)
    }

    @Test
    fun wrapWithConditionAtFieldAssignAllowsPutFieldWhenTrue() {
        AsmRegistry.register(WrapConditionFieldAssignAllowMixin::class.java)

        val transformed = AsmProcessor().transform("FieldPointTarget", fieldPointTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("FieldPointTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeName", String::class.java).invoke(instance, "allowed")
        val result = clazz.getMethod("readName").invoke(instance)

        assertEquals("allowed", result)
    }

    @Test
    fun wrapWithConditionAtStaticFieldAssignSkipsPutStaticWhenFalse() {
        AsmRegistry.register(WrapConditionStaticFieldAssignDenyMixin::class.java)

        val transformed = AsmProcessor().transform("StaticFieldPointTarget", staticFieldPointTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("StaticFieldPointTarget", transformed)

        clazz.getMethod("writeName", String::class.java).invoke(null, "blocked")
        val result = clazz.getMethod("readName").invoke(null)

        assertEquals(null, result)
    }

    @Test
    fun wrapWithConditionAtFieldAssignCanUseTargetMethodParameters() {
        AsmRegistry.register(WrapConditionFieldAssignWithTargetParamsMixin::class.java)

        val transformed = AsmProcessor().transform("FieldParamTarget", fieldParamTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("FieldParamTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeName", String::class.java, String::class.java, Int::class.javaPrimitiveType)
            .invoke(instance, "field", "suffix", 5)
        val result = clazz.getMethod("readName", String::class.java, Int::class.javaPrimitiveType)
            .invoke(instance, "unused", 0)

        assertEquals("field", result)
    }

    @Test
    fun wrapWithConditionFieldAssignOrdinalSelectsSingleWrite() {
        AsmRegistry.register(WrapConditionFieldAssignOrdinalMixin::class.java)

        val transformed = AsmProcessor().transform(
            "FieldAssignOrdinalTarget",
            fieldAssignOrdinalTargetBytes(),
            javaClass.classLoader,
        )
        val clazz = loadClass("FieldAssignOrdinalTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeBoth", String::class.java, String::class.java).invoke(instance, "first", "second")
        val result = clazz.getField("name").get(instance)

        assertEquals("first", result)
    }

    @Test
    fun wrapWithConditionFieldAssignWithMismatchedHandlerParametersFailsDuringTransform() {
        AsmRegistry.register(WrapConditionFieldAssignMismatchedParametersMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("FieldPointTarget", fieldPointTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("parameter #") == true,
        )
    }

    @Test
    fun wrapWithConditionAtArrayWriteSkipsObjectArrayStoreWhenFalse() {
        AsmRegistry.register(WrapConditionArrayWriteDenyMixin::class.java)

        val transformed = AsmProcessor().transform("ArrayAccessTarget", arrayAccessTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ArrayAccessTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeName", Int::class.javaPrimitiveType, String::class.java).invoke(instance, 0, "blocked")
        val result = clazz.getMethod("readName", Int::class.javaPrimitiveType).invoke(instance, 0)

        assertEquals("raw", result)
    }

    @Test
    fun wrapWithConditionAtArrayWriteAllowsObjectArrayStoreWhenTrue() {
        AsmRegistry.register(WrapConditionArrayWriteAllowMixin::class.java)

        val transformed = AsmProcessor().transform("ArrayAccessTarget", arrayAccessTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ArrayAccessTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeName", Int::class.javaPrimitiveType, String::class.java).invoke(instance, 0, "allowed")
        val result = clazz.getMethod("readName", Int::class.javaPrimitiveType).invoke(instance, 0)

        assertEquals("allowed", result)
    }

    @Test
    fun wrapWithConditionAtArrayWriteSkipsPrimitiveArrayStoreWhenFalse() {
        AsmRegistry.register(WrapConditionPrimitiveArrayWriteDenyMixin::class.java)

        val transformed =
            AsmProcessor().transform(
                "PrimitiveArrayAccessTarget",
                primitiveArrayAccessTargetBytes(),
                javaClass.classLoader,
            )
        val clazz = loadClass("PrimitiveArrayAccessTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeScore", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(instance, 0, 99)
        val result = clazz.getMethod("readScore", Int::class.javaPrimitiveType).invoke(instance, 0)

        assertEquals(40, result)
    }

    @Test
    fun wrapWithConditionAtArrayWriteCanUseTargetMethodParameters() {
        AsmRegistry.register(WrapConditionArrayWriteWithTargetParamsMixin::class.java)

        val transformed = AsmProcessor().transform("ArrayParamTarget", arrayParamTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ArrayParamTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeName", Int::class.javaPrimitiveType, String::class.java, String::class.java)
            .invoke(instance, 0, "field", "suffix")
        val result = clazz.getMethod("readName", Int::class.javaPrimitiveType, String::class.java)
            .invoke(instance, 0, "unused")

        assertEquals("field", result)
    }

    @Test
    fun wrapWithConditionArrayWriteWithMismatchedHandlerParametersFailsDuringTransform() {
        AsmRegistry.register(WrapConditionArrayWriteMismatchedParametersMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("ArrayAccessTarget", arrayAccessTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("parameter #") == true,
        )
    }

    @Test
    fun modifyExpressionValueAtInvokeRewritesCallReturnValue() {
        AsmRegistry.register(ModifyExpressionValueTrimMixin::class.java)

        val transformed = AsmProcessor().transform("ExpressionValueTarget", expressionValueTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ExpressionValueTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("raw-changed", result)
    }

    @Test
    fun modifyExpressionValueAtInvokeCanUseTargetMethodParameters() {
        AsmRegistry.register(ModifyExpressionValueWithTargetParamsMixin::class.java)

        val transformed =
            AsmProcessor().transform("ExpressionValueParamTarget", expressionValueParamTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ExpressionValueParamTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value", String::class.java, Int::class.javaPrimitiveType).invoke(instance, "prefix", 7)

        assertEquals("prefix-raw-7", result)
    }

    @Test
    fun modifyExpressionValueOrdinalSelectsSingleInvokeReturnValue() {
        AsmRegistry.register(ModifyExpressionValueOrdinalMixin::class.java)

        val transformed =
            AsmProcessor().transform("MultiExpressionValueTarget", multiExpressionValueTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("MultiExpressionValueTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("first:second-changed", result)
    }

    @Test
    fun modifyExpressionValueExposesCountContractParameters() {
        val methods = ModifyExpressionValue::class.java.declaredMethods.associateBy { it.name }

        assertEquals(Int::class.javaPrimitiveType, methods["require"]?.returnType)
        assertEquals(Int::class.javaPrimitiveType, methods["expect"]?.returnType)
        assertEquals(Int::class.javaPrimitiveType, methods["allow"]?.returnType)
    }

    @Test
    fun modifyExpressionValueRequireGreaterThanMatchedCountFailsDuringTransform() {
        AsmRegistry.register(RequireThreeModifyExpressionValueMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("MultiExpressionValueTarget", multiExpressionValueTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("requires at least 3 injection(s), actual 2") == true,
        )
    }

    @Test
    fun modifyExpressionValueAllowLessThanMatchedCountFailsDuringTransform() {
        AsmRegistry.register(AllowOneModifyExpressionValueMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("MultiExpressionValueTarget", multiExpressionValueTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("allows at most 1 injection(s), actual 2") == true,
        )
    }

    @Test
    fun modifyExpressionValueExpectMismatchReportsWarningWithoutFailingTransform() {
        AsmRegistry.register(ExpectThreeModifyExpressionValueMixin::class.java)
        val originalErr = System.err
        val output = ByteArrayOutputStream()

        try {
            PrintStream(output, true, Charsets.UTF_8.name()).use { capture ->
                System.setErr(capture)
                AsmProcessor().transform("MultiExpressionValueTarget", multiExpressionValueTargetBytes(), javaClass.classLoader)
            }
        } finally {
            System.setErr(originalErr)
        }

        assertEquals(
            true,
            output.toString(Charsets.UTF_8.name()).contains("expected 3 injection(s), actual 2"),
        )
    }

    @Test
    fun modifyExpressionValueSliceLimitsInvokeReturnValueMatchesBetweenFromAndTo() {
        AsmRegistry.register(ModifyExpressionValueSliceMixin::class.java)

        val transformed =
            AsmProcessor().transform("SliceExpressionValueTarget", sliceExpressionValueTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("SliceExpressionValueTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("pre:inside-changed:outside", result)
    }

    @Test
    fun modifyExpressionValueFieldSliceLimitsFieldReadsBetweenFromAndTo() {
        AsmRegistry.register(ModifyExpressionValueFieldSliceMixin::class.java)

        val transformed = AsmProcessor().transform("SliceFieldReadTarget", sliceFieldReadTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("SliceFieldReadTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeName", String::class.java).invoke(instance, "raw")
        val result = clazz.getMethod("readSelected").invoke(instance)

        assertEquals("raw-field-slice", result)
    }

    @Test
    fun modifyExpressionValueNewSliceLimitsConstructionsBetweenFromAndTo() {
        AsmRegistry.register(ModifyExpressionValueNewSliceMixin::class.java)

        val transformed =
            AsmProcessor().transform("SliceNewExpressionValueTarget", sliceNewExpressionValueTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("SliceNewExpressionValueTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("createSelected").invoke(instance).toString()

        assertEquals("changed", result)
    }

    @Test
    fun modifyExpressionValueCastSliceLimitsCheckcastsBetweenFromAndTo() {
        AsmRegistry.register(ModifyExpressionValueCastSliceMixin::class.java)

        val transformed =
            AsmProcessor().transform("SliceCastInstructionTarget", sliceCastInstructionTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("SliceCastInstructionTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("castSelected", Any::class.java).invoke(instance, "raw")

        assertEquals("raw-cast-slice", result)
    }

    @Test
    fun modifyExpressionValueInstanceofSliceLimitsChecksBetweenFromAndTo() {
        AsmRegistry.register(ModifyExpressionValueInstanceofSliceMixin::class.java)

        val transformed =
            AsmProcessor().transform(
                "SliceInstanceofExpressionValueTarget",
                sliceInstanceofExpressionValueTargetBytes(),
                javaClass.classLoader,
            )
        val clazz = loadClass("SliceInstanceofExpressionValueTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("isSelected", Any::class.java).invoke(instance, "raw")

        assertEquals(false, result)
    }

    @Test
    fun modifyExpressionValueWithMismatchedHandlerParametersFailsDuringTransform() {
        AsmRegistry.register(MismatchedModifyExpressionValueMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("ExpressionValueTarget", expressionValueTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("first parameter must be") == true,
        )
    }

    @Test
    fun modifyExpressionValueAtFieldRewritesGetFieldValue() {
        AsmRegistry.register(ModifyExpressionValueFieldReadMixin::class.java)

        val transformed = AsmProcessor().transform("FieldPointTarget", fieldPointTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("FieldPointTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeName", String::class.java).invoke(instance, "raw")
        val result = clazz.getMethod("readName").invoke(instance)

        assertEquals("raw-field", result)
    }

    @Test
    fun modifyExpressionValueFieldMatchesNameOnlyTarget() {
        AsmRegistry.register(ModifyExpressionValueFieldNameOnlyMixin::class.java)

        val transformed = AsmProcessor().transform("FieldPointTarget", fieldPointTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("FieldPointTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeName", String::class.java).invoke(instance, "raw")
        val result = clazz.getMethod("readName").invoke(instance)

        assertEquals("raw-name-only-field", result)
    }

    @Test
    fun modifyExpressionValueAtStaticFieldRewritesGetStaticValue() {
        AsmRegistry.register(ModifyExpressionValueStaticFieldReadMixin::class.java)

        val transformed = AsmProcessor().transform("StaticFieldPointTarget", staticFieldPointTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("StaticFieldPointTarget", transformed)

        clazz.getMethod("writeName", String::class.java).invoke(null, "raw")
        val result = clazz.getMethod("readName").invoke(null)

        assertEquals("raw-static-field", result)
    }

    @Test
    fun modifyExpressionValueAtFieldCanUseTargetMethodParameters() {
        AsmRegistry.register(ModifyExpressionValueFieldWithTargetParamsMixin::class.java)

        val transformed = AsmProcessor().transform("FieldParamTarget", fieldParamTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("FieldParamTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeName", String::class.java, String::class.java, Int::class.javaPrimitiveType)
            .invoke(instance, "raw", "ignored", 0)
        val result = clazz.getMethod("readName", String::class.java, Int::class.javaPrimitiveType).invoke(instance, "suffix", 7)

        assertEquals("raw-suffix7", result)
    }

    @Test
    fun modifyExpressionValueFieldOrdinalSelectsSingleRead() {
        AsmRegistry.register(ModifyExpressionValueFieldOrdinalMixin::class.java)

        val transformed = AsmProcessor().transform("MultiFieldReadTarget", multiFieldReadTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("MultiFieldReadTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeName", String::class.java).invoke(instance, "raw")
        val result = clazz.getMethod("readTwice").invoke(instance)

        assertEquals("raw-changed", result)
    }

    @Test
    fun modifyExpressionValueFieldWithMismatchedHandlerParametersFailsDuringTransform() {
        AsmRegistry.register(MismatchedModifyExpressionValueFieldMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("FieldPointTarget", fieldPointTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("first parameter must be") == true,
        )
    }

    @Test
    fun modifyExpressionValueAtArrayReadRewritesObjectArrayElementValue() {
        AsmRegistry.register(ModifyExpressionValueArrayReadMixin::class.java)

        val transformed = AsmProcessor().transform("ArrayAccessTarget", arrayAccessTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ArrayAccessTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("readName", Int::class.javaPrimitiveType).invoke(instance, 0)

        assertEquals("raw-array", result)
    }

    @Test
    fun modifyExpressionValueAtArrayReadRewritesPrimitiveArrayElementValue() {
        AsmRegistry.register(ModifyExpressionValuePrimitiveArrayReadMixin::class.java)

        val transformed =
            AsmProcessor().transform("PrimitiveArrayAccessTarget", primitiveArrayAccessTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("PrimitiveArrayAccessTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("readScore", Int::class.javaPrimitiveType).invoke(instance, 0)

        assertEquals(42, result)
    }

    @Test
    fun modifyExpressionValueAtArrayReadCanUseTargetMethodParameters() {
        AsmRegistry.register(ModifyExpressionValueArrayReadWithTargetParamsMixin::class.java)

        val transformed = AsmProcessor().transform("ArrayParamTarget", arrayParamTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ArrayParamTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("readName", Int::class.javaPrimitiveType, String::class.java).invoke(instance, 0, "suffix")

        assertEquals("raw-suffix", result)
    }

    @Test
    fun modifyExpressionValueAtArrayLengthRewritesArrayLengthValue() {
        AsmRegistry.register(ModifyExpressionValueArrayLengthMixin::class.java)

        val transformed = AsmProcessor().transform("ArrayAccessTarget", arrayAccessTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ArrayAccessTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("nameCount").invoke(instance)

        assertEquals(4, result)
    }

    @Test
    fun modifyExpressionValueAtArrayLengthCanUseTargetMethodParameters() {
        AsmRegistry.register(ModifyExpressionValueArrayLengthWithTargetParamsMixin::class.java)

        val transformed = AsmProcessor().transform("ArrayAccessTarget", arrayAccessTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ArrayAccessTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("nameCount", Int::class.javaPrimitiveType).invoke(instance, 3)

        assertEquals(4, result)
    }

    @Test
    fun modifyExpressionValueArrayReadSliceLimitsReadsBetweenFromAndTo() {
        AsmRegistry.register(ModifyExpressionValueArrayReadSliceMixin::class.java)

        val transformed =
            AsmProcessor().transform("SliceArrayExpressionValueTarget", sliceArrayExpressionValueTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("SliceArrayExpressionValueTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("readSelected", Int::class.javaPrimitiveType).invoke(instance, 0)

        assertEquals("raw-array-slice", result)
    }

    @Test
    fun modifyExpressionValueArrayLengthSliceLimitsLengthsBetweenFromAndTo() {
        AsmRegistry.register(ModifyExpressionValueArrayLengthSliceMixin::class.java)

        val transformed =
            AsmProcessor().transform("SliceArrayExpressionValueTarget", sliceArrayExpressionValueTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("SliceArrayExpressionValueTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("countSelected").invoke(instance)

        assertEquals(4, result)
    }

    @Test
    fun modifyExpressionValueArrayLengthWithMismatchedHandlerParametersFailsDuringTransform() {
        AsmRegistry.register(MismatchedModifyExpressionValueArrayLengthMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("ArrayAccessTarget", arrayAccessTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("first parameter must be") == true,
        )
    }

    @Test
    fun modifyExpressionValueArrayReadWithMismatchedHandlerParametersFailsDuringTransform() {
        AsmRegistry.register(MismatchedModifyExpressionValueArrayReadMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("ArrayAccessTarget", arrayAccessTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("first parameter must be") == true,
        )
    }

    @Test
    fun modifyExpressionValueAtNewRewritesConstructedObject() {
        AsmRegistry.register(ModifyExpressionValueNewMixin::class.java)

        val transformed = AsmProcessor().transform(
            "NewInstructionTarget",
            newInstructionTargetBytes(),
            javaClass.classLoader,
        )
        val clazz = loadClass("NewInstructionTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("create").invoke(instance).toString()

        assertEquals("changed", result)
    }

    @Test
    fun modifyExpressionValueAtNewCanUseTargetMethodParameters() {
        AsmRegistry.register(ModifyExpressionValueNewWithTargetParamsMixin::class.java)

        val transformed = AsmProcessor().transform("NewParamTarget", newParamTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("NewParamTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("create", String::class.java, Int::class.javaPrimitiveType)
            .invoke(instance, "prefix", 7)
            .toString()

        assertEquals("prefix-7", result)
    }

    @Test
    fun modifyExpressionValueNewOrdinalSelectsSingleConstruction() {
        AsmRegistry.register(ModifyExpressionValueNewOrdinalMixin::class.java)

        val transformed = AsmProcessor().transform("MultiNewTarget", multiNewTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("MultiNewTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("first:changed", result)
    }

    @Test
    fun modifyExpressionValueNewWithMismatchedHandlerParametersFailsDuringTransform() {
        AsmRegistry.register(MismatchedModifyExpressionValueNewMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform(
                    "NewInstructionTarget",
                    newInstructionTargetBytes(),
                    javaClass.classLoader,
                )
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("first parameter must be") == true,
        )
    }

    @Test
    fun modifyExpressionValueAtCastRewritesCheckcastValue() {
        AsmRegistry.register(ModifyExpressionValueCastMixin::class.java)

        val transformed = AsmProcessor().transform("CastInstructionTarget", castInstructionTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("CastInstructionTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("cast", Any::class.java).invoke(instance, "raw")

        assertEquals("raw-cast", result)
    }

    @Test
    fun modifyExpressionValueAtCastCanUseTargetMethodParameters() {
        AsmRegistry.register(ModifyExpressionValueCastWithTargetParamsMixin::class.java)

        val transformed = AsmProcessor().transform("CastInstructionTarget", castInstructionTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("CastInstructionTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("cast", Any::class.java).invoke(instance, "raw")

        assertEquals("raw-raw", result)
    }

    @Test
    fun modifyExpressionValueCastWithMismatchedHandlerParametersFailsDuringTransform() {
        AsmRegistry.register(MismatchedModifyExpressionValueCastMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform(
                    "CastInstructionTarget",
                    castInstructionTargetBytes(),
                    javaClass.classLoader,
                )
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("first parameter must be") == true,
        )
    }

    @Test
    fun modifyExpressionValueAtInstanceofRewritesBooleanResult() {
        AsmRegistry.register(ModifyExpressionValueInstanceofMixin::class.java)

        val transformed = AsmProcessor().transform("InstanceofTarget", instanceofTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("InstanceofTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        assertEquals(false, clazz.getMethod("isString", Any::class.java, Boolean::class.javaPrimitiveType).invoke(instance, 42, false))
        assertEquals(true, clazz.getMethod("isString", Any::class.java, Boolean::class.javaPrimitiveType).invoke(instance, 42, true))
    }

    @Test
    fun modifyReceiverAtInvokeReplacesInstanceCallReceiver() {
        AsmRegistry.register(ModifyReceiverConcatMixin::class.java)

        val transformed = AsmProcessor().transform("ModifyReceiverTarget", modifyReceiverTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ModifyReceiverTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("changed-call", result)
    }

    @Test
    fun modifyReceiverAtInvokeCanUseTargetMethodParameters() {
        AsmRegistry.register(ModifyReceiverWithTargetParamsMixin::class.java)

        val transformed =
            AsmProcessor().transform("ModifyReceiverParamTarget", modifyReceiverParamTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ModifyReceiverParamTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value", String::class.java, Int::class.javaPrimitiveType).invoke(instance, "prefix", 7)

        assertEquals("prefix7-call", result)
    }

    @Test
    fun modifyReceiverOrdinalSelectsSingleInvokeReceiver() {
        AsmRegistry.register(ModifyReceiverOrdinalMixin::class.java)

        val transformed =
            AsmProcessor().transform("MultiModifyReceiverTarget", multiModifyReceiverTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("MultiModifyReceiverTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("first-a:changed-b", result)
    }

    @Test
    fun modifyReceiverExposesCountContractParameters() {
        val methods = ModifyReceiver::class.java.declaredMethods.associateBy { it.name }

        assertEquals(Int::class.javaPrimitiveType, methods["require"]?.returnType)
        assertEquals(Int::class.javaPrimitiveType, methods["expect"]?.returnType)
        assertEquals(Int::class.javaPrimitiveType, methods["allow"]?.returnType)
    }

    @Test
    fun modifyReceiverRequireGreaterThanMatchedCountFailsDuringTransform() {
        AsmRegistry.register(RequireThreeModifyReceiverMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("ModifyReceiverContractTarget", modifyReceiverContractTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("requires at least 3 injection(s), actual 2") == true,
        )
    }

    @Test
    fun modifyReceiverAllowLessThanMatchedCountFailsDuringTransform() {
        AsmRegistry.register(AllowOneModifyReceiverMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("ModifyReceiverContractTarget", modifyReceiverContractTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("allows at most 1 injection(s), actual 2") == true,
        )
    }

    @Test
    fun modifyReceiverExpectMismatchReportsWarningWithoutFailingTransform() {
        AsmRegistry.register(ExpectThreeModifyReceiverMixin::class.java)
        val originalErr = System.err
        val output = ByteArrayOutputStream()

        try {
            PrintStream(output, true, Charsets.UTF_8.name()).use { capture ->
                System.setErr(capture)
                AsmProcessor().transform("ModifyReceiverContractTarget", modifyReceiverContractTargetBytes(), javaClass.classLoader)
            }
        } finally {
            System.setErr(originalErr)
        }

        assertEquals(
            true,
            output.toString(Charsets.UTF_8.name()).contains("expected 3 injection(s), actual 2"),
        )
    }

    @Test
    fun modifyReceiverSliceLimitsInvokeReceiverMatchesBetweenFromAndTo() {
        AsmRegistry.register(ModifyReceiverSliceMixin::class.java)

        val transformed =
            AsmProcessor().transform("SliceModifyReceiverTarget", sliceModifyReceiverTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("SliceModifyReceiverTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("pre-a:changed-b:outside-c", result)
    }

    @Test
    fun modifyReceiverFieldSliceLimitsFieldReadReceiversBetweenFromAndTo() {
        AsmRegistry.register(ModifyReceiverFieldReadSliceMixin::class.java)

        val transformed =
            AsmProcessor().transform(
                "SliceModifyReceiverFieldTarget",
                sliceModifyReceiverFieldTargetBytes(),
                javaClass.classLoader,
            )
        val clazz = loadClass("SliceModifyReceiverFieldTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("readSelected").invoke(instance)

        assertEquals("primary:replacement", result)
    }

    @Test
    fun modifyReceiverFieldAssignSliceLimitsFieldWriteReceiversBetweenFromAndTo() {
        AsmRegistry.register(ModifyReceiverFieldAssignSliceMixin::class.java)

        val transformed =
            AsmProcessor().transform(
                "SliceModifyReceiverFieldTarget",
                sliceModifyReceiverFieldTargetBytes(),
                javaClass.classLoader,
            )
        val clazz = loadClass("SliceModifyReceiverFieldTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("writeSelected").invoke(instance)

        assertEquals("outside:inside", result)
    }

    @Test
    fun modifyReceiverRejectsStaticInvokeCall() {
        AsmRegistry.register(ModifyReceiverStaticCallMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("StaticInvokeArgTarget", staticInvokeArgTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("instance method calls") == true,
        )
    }

    @Test
    fun modifyReceiverWithMismatchedHandlerParametersFailsDuringTransform() {
        AsmRegistry.register(MismatchedModifyReceiverMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("ModifyReceiverTarget", modifyReceiverTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("first parameter must be") == true,
        )
    }

    @Test
    fun modifyReceiverAtFieldReadReplacesGetFieldReceiver() {
        AsmRegistry.register(ModifyReceiverFieldReadMixin::class.java)

        val transformed = AsmProcessor().transform("FieldPointTarget", fieldPointTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("FieldPointTarget", transformed)
        val original = clazz.getDeclaredConstructor().newInstance()
        val replacement = clazz.getDeclaredConstructor().newInstance()

        try {
            clazz.getMethod("writeName", String::class.java).invoke(original, "original")
            clazz.getMethod("writeName", String::class.java).invoke(replacement, "replacement")
            ModifyReceiverFieldReadMixin.replacement = replacement

            val result = clazz.getMethod("readName").invoke(original)

            assertEquals("replacement", result)
        } finally {
            ModifyReceiverFieldReadMixin.replacement = null
        }
    }

    @Test
    fun modifyReceiverAtFieldReadCanUseTargetMethodParameters() {
        AsmRegistry.register(ModifyReceiverFieldReadWithTargetParamsMixin::class.java)

        val transformed = AsmProcessor().transform("FieldParamTarget", fieldParamTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("FieldParamTarget", transformed)
        val original = clazz.getDeclaredConstructor().newInstance()
        val replacement = clazz.getDeclaredConstructor().newInstance()

        try {
            clazz.getMethod("writeName", String::class.java, String::class.java, Int::class.javaPrimitiveType)
                .invoke(replacement, "replacement", "unused", 0)
            ModifyReceiverFieldReadWithTargetParamsMixin.replacement = replacement

            val result = clazz.getMethod("readName", String::class.java, Int::class.javaPrimitiveType)
                .invoke(original, "prefix", 7)

            assertEquals("replacement", result)
            assertEquals("prefix7", ModifyReceiverFieldReadWithTargetParamsMixin.lastTargetParams)
        } finally {
            ModifyReceiverFieldReadWithTargetParamsMixin.replacement = null
            ModifyReceiverFieldReadWithTargetParamsMixin.lastTargetParams = null
        }
    }

    @Test
    fun modifyReceiverAtFieldAssignReplacesPutFieldReceiver() {
        AsmRegistry.register(ModifyReceiverFieldAssignMixin::class.java)

        val transformed = AsmProcessor().transform("FieldPointTarget", fieldPointTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("FieldPointTarget", transformed)
        val original = clazz.getDeclaredConstructor().newInstance()
        val replacement = clazz.getDeclaredConstructor().newInstance()

        try {
            ModifyReceiverFieldAssignMixin.replacement = replacement

            clazz.getMethod("writeName", String::class.java).invoke(original, "redirected")

            assertEquals(null, clazz.getMethod("readName").invoke(original))
            assertEquals("redirected", clazz.getMethod("readName").invoke(replacement))
        } finally {
            ModifyReceiverFieldAssignMixin.replacement = null
        }
    }

    @Test
    fun modifyReceiverRejectsStaticFieldRead() {
        AsmRegistry.register(ModifyReceiverStaticFieldReadMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("StaticFieldPointTarget", staticFieldPointTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("instance field reads") == true,
        )
    }

    @Test
    fun wrapOperationAtInvokeCanCallOriginalInstanceMethod() {
        AsmRegistry.register(WrapOperationInstanceCallMixin::class.java)

        val transformed = AsmProcessor().transform("ModifyReceiverTarget", modifyReceiverTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ModifyReceiverTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("original-wrapped-call", result)
    }

    @Test
    fun wrapOperationAtInvokeCanSkipOriginalCall() {
        AsmRegistry.register(WrapOperationSkipCallMixin::class.java)

        val transformed = AsmProcessor().transform("ModifyReceiverTarget", modifyReceiverTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ModifyReceiverTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("skipped", result)
    }

    @Test
    fun wrapOperationAtInvokeCanCallOriginalMultipleTimes() {
        AsmRegistry.register(WrapOperationMultipleCallsMixin::class.java)

        val transformed = AsmProcessor().transform("ModifyReceiverTarget", modifyReceiverTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ModifyReceiverTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("original-first|original-second", result)
    }

    @Test
    fun wrapOperationAtStaticInvokeCanCallOriginalMethod() {
        AsmRegistry.register(WrapOperationStaticCallMixin::class.java)

        val transformed = AsmProcessor().transform("StaticInvokeArgTarget", staticInvokeArgTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("StaticInvokeArgTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("wrapped-43", result)
    }

    @Test
    fun wrapOperationAtInvokeCanUseTargetMethodParameters() {
        AsmRegistry.register(WrapOperationWithTargetParamsMixin::class.java)

        val transformed =
            AsmProcessor().transform("ModifyReceiverParamTarget", modifyReceiverParamTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ModifyReceiverParamTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value", String::class.java, Int::class.javaPrimitiveType).invoke(instance, "prefix", 7)

        assertEquals("prefix7-call", result)
    }

    @Test
    fun wrapOperationOrdinalSelectsSingleInvokeCall() {
        AsmRegistry.register(WrapOperationOrdinalMixin::class.java)

        val transformed =
            AsmProcessor().transform("MultiModifyReceiverTarget", multiModifyReceiverTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("MultiModifyReceiverTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("first-a:wrapped-b", result)
    }

    @Test
    fun wrapOperationExposesCountContractParameters() {
        val methods = WrapOperation::class.java.declaredMethods.associateBy { it.name }

        assertEquals(Int::class.javaPrimitiveType, methods["require"]?.returnType)
        assertEquals(Int::class.javaPrimitiveType, methods["expect"]?.returnType)
        assertEquals(Int::class.javaPrimitiveType, methods["allow"]?.returnType)
    }

    @Test
    fun wrapMethodAnnotationIsAvailableForWholeMethodWrapping() {
        val annotationClass = Class.forName("kim.der.asm.api.annotation.WrapMethod")
        val methods = annotationClass.declaredMethods.associateBy { it.name }

        assertEquals(String::class.java, methods["method"]?.returnType)
        assertEquals(Int::class.javaPrimitiveType, methods["require"]?.returnType)
        assertEquals(Int::class.javaPrimitiveType, methods["expect"]?.returnType)
        assertEquals(Int::class.javaPrimitiveType, methods["allow"]?.returnType)
    }

    @Test
    fun wrapMethodCanCallOriginalStaticMethodWithChangedArguments() {
        AsmRegistry.register(WrapMethodStaticTargetMixin::class.java)

        val transformed = AsmProcessor().transform("WrapMethodStaticTarget", wrapMethodStaticTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("WrapMethodStaticTarget", transformed)
        val result =
            clazz.getMethod("value", String::class.java, Int::class.javaPrimitiveType)
                .invoke(null, "raw", 7)

        assertEquals("RAW8-wrapped", result)
    }

    @Test
    fun wrapMethodCanCallOriginalInstanceMethodWithChangedArguments() {
        AsmRegistry.register(WrapMethodInstanceTargetMixin::class.java)

        val transformed = AsmProcessor().transform("WrapMethodInstanceTarget", wrapMethodInstanceTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("WrapMethodInstanceTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result =
            clazz.getMethod("value", String::class.java, Int::class.javaPrimitiveType)
                .invoke(instance, "raw", 7)

        assertEquals("instance:RAW8-wrapped", result)
    }

    @Test
    fun wrapOperationRequireGreaterThanMatchedCountFailsDuringTransform() {
        AsmRegistry.register(RequireThreeWrapOperationMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("ModifyReceiverContractTarget", modifyReceiverContractTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("requires at least 3 injection(s), actual 2") == true,
        )
    }

    @Test
    fun wrapOperationAllowLessThanMatchedCountFailsDuringTransform() {
        AsmRegistry.register(AllowOneWrapOperationMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("ModifyReceiverContractTarget", modifyReceiverContractTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("allows at most 1 injection(s), actual 2") == true,
        )
    }

    @Test
    fun wrapOperationExpectMismatchReportsWarningWithoutFailingTransform() {
        AsmRegistry.register(ExpectThreeWrapOperationMixin::class.java)
        val originalErr = System.err
        val output = ByteArrayOutputStream()

        try {
            PrintStream(output, true, Charsets.UTF_8.name()).use { capture ->
                System.setErr(capture)
                AsmProcessor().transform("ModifyReceiverContractTarget", modifyReceiverContractTargetBytes(), javaClass.classLoader)
            }
        } finally {
            System.setErr(originalErr)
        }

        assertEquals(
            true,
            output.toString(Charsets.UTF_8.name()).contains("expected 3 injection(s), actual 2"),
        )
    }

    @Test
    fun wrapOperationSliceLimitsInvokeCallMatchesBetweenFromAndTo() {
        AsmRegistry.register(WrapOperationSliceMixin::class.java)

        val transformed = AsmProcessor().transform("SliceWrapOperationTarget", sliceWrapOperationTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("SliceWrapOperationTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("pre-raw:inside-wrapped:outside-raw", result)
    }

    @Test
    fun wrapOperationWithMismatchedHandlerParametersFailsDuringTransform() {
        AsmRegistry.register(MismatchedWrapOperationMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("ModifyReceiverTarget", modifyReceiverTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("Operation") == true,
        )
    }

    @Test
    fun wrapOperationAtConstructorCanCallOriginalConstructorWithChangedArguments() {
        AsmRegistry.register(WrapOperationConstructorMixin::class.java)

        val transformed =
            AsmProcessor().transform("ConstructorModifyArgTarget", constructorModifyArgTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ConstructorModifyArgTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("wrapped-raw", result)
    }

    @Test
    fun wrapOperationAtConstructorCanUseTargetMethodParameters() {
        AsmRegistry.register(WrapOperationConstructorWithTargetParamsMixin::class.java)

        val transformed = AsmProcessor().transform("NewParamTarget", newParamTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("NewParamTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("create", String::class.java, Int::class.javaPrimitiveType).invoke(instance, "prefix", 7)

        assertEquals("prefix-7", result.toString())
    }

    @Test
    fun wrapOperationAtConstructorWithMismatchedHandlerParametersFailsDuringTransform() {
        AsmRegistry.register(MismatchedWrapOperationConstructorMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("ConstructorModifyArgTarget", constructorModifyArgTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("Operation") == true,
        )
    }

    @Test
    fun wrapOperationAtFieldCanCallOriginalGetField() {
        AsmRegistry.register(WrapOperationFieldReadMixin::class.java)

        val transformed = AsmProcessor().transform("FieldPointTarget", fieldPointTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("FieldPointTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeName", String::class.java).invoke(instance, "raw")
        val result = clazz.getMethod("readName").invoke(instance)

        assertEquals("wrapped-raw", result)
    }

    @Test
    fun wrapOperationAtPrimitiveFieldCanCallOriginalGetField() {
        AsmRegistry.register(WrapOperationPrimitiveFieldReadMixin::class.java)

        val transformed =
            AsmProcessor().transform(
                "PrimitiveFieldPointTarget",
                primitiveFieldPointTargetBytes(),
                javaClass.classLoader,
            )
        val clazz = loadClass("PrimitiveFieldPointTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeScore", Int::class.javaPrimitiveType).invoke(instance, 40)
        val result = clazz.getMethod("readScore").invoke(instance)

        assertEquals(42, result)
    }

    @Test
    fun wrapOperationAtStaticFieldCanCallOriginalGetStatic() {
        AsmRegistry.register(WrapOperationStaticFieldReadMixin::class.java)

        val transformed = AsmProcessor().transform("StaticFieldPointTarget", staticFieldPointTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("StaticFieldPointTarget", transformed)

        clazz.getMethod("writeName", String::class.java).invoke(null, "raw")
        val result = clazz.getMethod("readName").invoke(null)

        assertEquals("wrapped-static-raw", result)
    }

    @Test
    fun wrapOperationAtFieldCanUseTargetMethodParameters() {
        AsmRegistry.register(WrapOperationFieldWithTargetParamsMixin::class.java)

        val transformed = AsmProcessor().transform("FieldParamTarget", fieldParamTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("FieldParamTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeName", String::class.java, String::class.java, Int::class.javaPrimitiveType)
            .invoke(instance, "raw", "ignored", 0)
        val result = clazz.getMethod("readName", String::class.java, Int::class.javaPrimitiveType).invoke(instance, "suffix", 7)

        assertEquals("raw-suffix7", result)
    }

    @Test
    fun wrapOperationFieldOrdinalSelectsSingleRead() {
        AsmRegistry.register(WrapOperationFieldOrdinalMixin::class.java)

        val transformed = AsmProcessor().transform("MultiFieldReadTarget", multiFieldReadTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("MultiFieldReadTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeName", String::class.java).invoke(instance, "raw")
        val result = clazz.getMethod("readTwice").invoke(instance)

        assertEquals("raw-wrapped", result)
    }

    @Test
    fun wrapOperationAtFieldWithMismatchedHandlerParametersFailsDuringTransform() {
        AsmRegistry.register(MismatchedWrapOperationFieldReadMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("FieldPointTarget", fieldPointTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("Operation") == true,
        )
    }

    @Test
    fun wrapOperationAtFieldAssignCanCallOriginalPutFieldWithChangedValue() {
        AsmRegistry.register(WrapOperationFieldAssignMixin::class.java)

        val transformed = AsmProcessor().transform("FieldPointTarget", fieldPointTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("FieldPointTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeName", String::class.java).invoke(instance, "raw")
        val result = clazz.getMethod("readName").invoke(instance)

        assertEquals("wrapped-raw", result)
    }

    @Test
    fun wrapOperationAtFieldAssignCanSkipOriginalPutField() {
        AsmRegistry.register(WrapOperationFieldAssignSkipMixin::class.java)

        val transformed = AsmProcessor().transform("FieldPointTarget", fieldPointTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("FieldPointTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeName", String::class.java).invoke(instance, "raw")
        val result = clazz.getMethod("readName").invoke(instance)

        assertEquals(null, result)
    }

    @Test
    fun wrapOperationAtPrimitiveFieldAssignCanCallOriginalPutFieldWithChangedValue() {
        AsmRegistry.register(WrapOperationPrimitiveFieldAssignMixin::class.java)

        val transformed =
            AsmProcessor().transform(
                "PrimitiveFieldPointTarget",
                primitiveFieldPointTargetBytes(),
                javaClass.classLoader,
            )
        val clazz = loadClass("PrimitiveFieldPointTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeScore", Int::class.javaPrimitiveType).invoke(instance, 40)
        val result = clazz.getMethod("readScore").invoke(instance)

        assertEquals(42, result)
    }

    @Test
    fun wrapOperationAtStaticFieldAssignCanCallOriginalPutStaticWithChangedValue() {
        AsmRegistry.register(WrapOperationStaticFieldAssignMixin::class.java)

        val transformed =
            AsmProcessor().transform("StaticFieldPointTarget", staticFieldPointTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("StaticFieldPointTarget", transformed)

        clazz.getMethod("writeName", String::class.java).invoke(null, "raw")
        val result = clazz.getMethod("readName").invoke(null)

        assertEquals("wrapped-static-raw", result)
    }

    @Test
    fun wrapOperationAtFieldAssignCanUseTargetMethodParameters() {
        AsmRegistry.register(WrapOperationFieldAssignWithTargetParamsMixin::class.java)

        val transformed = AsmProcessor().transform("FieldParamTarget", fieldParamTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("FieldParamTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeName", String::class.java, String::class.java, Int::class.javaPrimitiveType)
            .invoke(instance, "raw", "suffix", 7)
        val result = clazz.getMethod("readName", String::class.java, Int::class.javaPrimitiveType)
            .invoke(instance, "unused", 0)

        assertEquals("raw-suffix7", result)
    }

    @Test
    fun wrapOperationFieldAssignOrdinalSelectsSingleWrite() {
        AsmRegistry.register(WrapOperationFieldAssignOrdinalMixin::class.java)

        val transformed =
            AsmProcessor().transform("FieldAssignOrdinalTarget", fieldAssignOrdinalTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("FieldAssignOrdinalTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeBoth", String::class.java, String::class.java).invoke(instance, "first", "second")
        val result = clazz.getField("name").get(instance)

        assertEquals("wrapped-second", result)
    }

    @Test
    fun wrapOperationAtFieldAssignWithMismatchedHandlerParametersFailsDuringTransform() {
        AsmRegistry.register(MismatchedWrapOperationFieldAssignMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("FieldPointTarget", fieldPointTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("Operation") == true,
        )
    }

    @Test
    fun wrapOperationAtArrayReadCanCallOriginalObjectArrayLoad() {
        AsmRegistry.register(WrapOperationArrayReadMixin::class.java)

        val transformed = AsmProcessor().transform("ArrayAccessTarget", arrayAccessTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ArrayAccessTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("readName", Int::class.javaPrimitiveType).invoke(instance, 0)

        assertEquals("wrapped-raw", result)
    }

    @Test
    fun wrapOperationAtArrayReadCanCallOriginalPrimitiveArrayLoad() {
        AsmRegistry.register(WrapOperationPrimitiveArrayReadMixin::class.java)

        val transformed =
            AsmProcessor().transform(
                "PrimitiveArrayAccessTarget",
                primitiveArrayAccessTargetBytes(),
                javaClass.classLoader,
            )
        val clazz = loadClass("PrimitiveArrayAccessTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("readScore", Int::class.javaPrimitiveType).invoke(instance, 0)

        assertEquals(42, result)
    }

    @Test
    fun wrapOperationAtArrayLengthCanCallOriginalArrayLength() {
        AsmRegistry.register(WrapOperationArrayLengthMixin::class.java)

        val transformed = AsmProcessor().transform("ArrayAccessTarget", arrayAccessTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ArrayAccessTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("nameCount").invoke(instance)

        assertEquals(6, result)
    }

    @Test
    fun wrapOperationAtArrayLengthCanUseTargetMethodParameters() {
        AsmRegistry.register(WrapOperationArrayLengthWithTargetParamsMixin::class.java)

        val transformed = AsmProcessor().transform("ArrayAccessTarget", arrayAccessTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ArrayAccessTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("nameCount", Int::class.javaPrimitiveType).invoke(instance, 4)

        assertEquals(5, result)
    }

    @Test
    fun wrapOperationArrayLengthWithMismatchedHandlerReturnFailsDuringTransform() {
        AsmRegistry.register(MismatchedWrapOperationArrayLengthMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("ArrayAccessTarget", arrayAccessTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("array length handler") == true,
        )
    }

    @Test
    fun wrapOperationAtArrayWriteCanCallOriginalObjectArrayStoreWithChangedValue() {
        AsmRegistry.register(WrapOperationArrayWriteMixin::class.java)

        val transformed = AsmProcessor().transform("ArrayAccessTarget", arrayAccessTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ArrayAccessTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeName", Int::class.javaPrimitiveType, String::class.java).invoke(instance, 0, "value")
        val result = clazz.getMethod("readName", Int::class.javaPrimitiveType).invoke(instance, 0)

        assertEquals("wrapped-value", result)
    }

    @Test
    fun wrapOperationAtArrayWriteCanSkipOriginalObjectArrayStore() {
        AsmRegistry.register(WrapOperationArrayWriteSkipMixin::class.java)

        val transformed = AsmProcessor().transform("ArrayAccessTarget", arrayAccessTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ArrayAccessTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeName", Int::class.javaPrimitiveType, String::class.java).invoke(instance, 0, "blocked")
        val result = clazz.getMethod("readName", Int::class.javaPrimitiveType).invoke(instance, 0)

        assertEquals("raw", result)
    }

    @Test
    fun wrapOperationAtArrayWriteCanUseTargetMethodParameters() {
        AsmRegistry.register(WrapOperationArrayWriteWithTargetParamsMixin::class.java)

        val transformed = AsmProcessor().transform("ArrayParamTarget", arrayParamTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ArrayParamTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeName", Int::class.javaPrimitiveType, String::class.java, String::class.java)
            .invoke(instance, 0, "field", "suffix")
        val result = clazz.getMethod("readName", Int::class.javaPrimitiveType, String::class.java)
            .invoke(instance, 0, "unused")

        assertEquals("field-suffix", result)
    }

    @Test
    fun wrapOperationAtArrayAccessWithMismatchedHandlerParametersFailsDuringTransform() {
        AsmRegistry.register(MismatchedWrapOperationArrayReadMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("ArrayAccessTarget", arrayAccessTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("Operation") == true,
        )
    }

    @Test
    fun modifyConstantWithIncompatibleReturnTypeFailsDuringTransform() {
        AsmRegistry.register(IncompatibleModifyConstantMixin::class.java)

        assertThrows(AsmTransformException::class.java) {
            AsmProcessor().transform("ReturnTarget", returnTargetBytes(), javaClass.classLoader)
        }
    }

    @Test
    fun modifyConstantExposesSliceParameter() {
        val hasSliceParameter =
            ModifyConstant::class.java.declaredMethods.any {
                it.name == "slice" && it.returnType == Slice::class.java
            }

        assertEquals(true, hasSliceParameter)
    }

    @Test
    fun modifyConstantExposesCountContractParameters() {
        val methods = ModifyConstant::class.java.declaredMethods.associateBy { it.name }

        assertEquals(Int::class.javaPrimitiveType, methods["require"]?.returnType)
        assertEquals(Int::class.javaPrimitiveType, methods["expect"]?.returnType)
        assertEquals(Int::class.javaPrimitiveType, methods["allow"]?.returnType)
    }

    @Test
    fun modifyConstantRequireGreaterThanMatchedCountFailsDuringTransform() {
        AsmRegistry.register(RequireThreeModifyConstantMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("MultiIntConstantTarget", multiIntConstantTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("requires at least 3 injection(s), actual 2") == true,
        )
    }

    @Test
    fun modifyConstantAllowLessThanMatchedCountFailsDuringTransform() {
        AsmRegistry.register(AllowOneModifyConstantMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("MultiIntConstantTarget", multiIntConstantTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("allows at most 1 injection(s), actual 2") == true,
        )
    }

    @Test
    fun modifyConstantExpectMismatchReportsWarningWithoutFailingTransform() {
        AsmRegistry.register(ExpectThreeModifyConstantMixin::class.java)
        val originalErr = System.err
        val output = ByteArrayOutputStream()

        try {
            PrintStream(output, true, Charsets.UTF_8.name()).use { capture ->
                System.setErr(capture)
                AsmProcessor().transform("MultiIntConstantTarget", multiIntConstantTargetBytes(), javaClass.classLoader)
            }
        } finally {
            System.setErr(originalErr)
        }

        assertEquals(
            true,
            output.toString(Charsets.UTF_8.name()).contains("expected 3 injection(s), actual 2"),
        )
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
    fun modifyConstantUsesOriginalConstantAsHandlerArgument() {
        AsmRegistry.register(StringOnlyModifyConstantMixin::class.java)

        val transformed = AsmProcessor().transform("MixedConstantTarget", mixedConstantTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("MixedConstantTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("changed", result)
    }

    @Test
    fun modifyConstantSliceLimitsConstantsBetweenFromAndTo() {
        AsmRegistry.register(SliceModifyConstantMixin::class.java)

        val transformed = AsmProcessor().transform("SliceConstantTarget", sliceConstantTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("SliceConstantTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("target:changed:target", result)
    }

    @Test
    fun modifyConstantCanUseTargetMethodParameters() {
        AsmRegistry.register(ConstantWithTargetParamsMixin::class.java)

        val transformed = AsmProcessor().transform("ConstantParamTarget", constantParamTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ConstantParamTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value", String::class.java, Int::class.javaPrimitiveType).invoke(instance, "suffix", 3)

        assertEquals("base-suffix3", result)
    }

    @Test
    fun modifyConstantCanUseStaticTargetMethodParameters() {
        AsmRegistry.register(StaticConstantWithTargetParamsMixin::class.java)

        val transformed = AsmProcessor().transform("StaticConstantParamTarget", staticConstantParamTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("StaticConstantParamTarget", transformed)
        val result = clazz.getMethod("value", String::class.java, Int::class.javaPrimitiveType).invoke(null, "suffix", 4)

        assertEquals("static-suffix4", result)
    }

    @Test
    fun modifyConstantMatchesExplicitNullConstant() {
        AsmRegistry.register(NullModifyConstantMixin::class.java)

        val transformed = AsmProcessor().transform("NullConstantTarget", nullConstantTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("NullConstantTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("changed", result)
    }

    @Test
    fun modifyConstantMatchesBipushIntConstant() {
        AsmRegistry.register(BipushModifyConstantMixin::class.java)

        val transformed = AsmProcessor().transform("BipushConstantTarget", bipushConstantTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("BipushConstantTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals(42, result)
    }

    @Test
    fun modifyConstantMatchesSipushIntConstant() {
        AsmRegistry.register(SipushModifyConstantMixin::class.java)

        val transformed = AsmProcessor().transform("SipushConstantTarget", sipushConstantTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("SipushConstantTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals(301, result)
    }

    @Test
    fun modifyConstantOrdinalSelectsSingleMatchingConstant() {
        AsmRegistry.register(OrdinalModifyConstantMixin::class.java)

        val transformed = AsmProcessor().transform("MultiIntConstantTarget", multiIntConstantTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("MultiIntConstantTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals(49, result)
    }

    @Test
    fun shadowCanUseExplicitTargetNamesForOverwriteReferences() {
        AsmRegistry.register(ShadowAliasOverwriteMixin::class.java)

        val transformed = AsmProcessor().transform("ShadowAliasTarget", shadowAliasTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ShadowAliasTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("actual:seed", result)
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
    fun mutableFieldOnlyTransformWritesModifiedClassBytes() {
        AsmRegistry.register(MutableFieldOnlyMixin::class.java)

        val transformed = AsmProcessor().transform("FinalFieldTarget", finalFieldTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val field = classNode.fields.single { it.name == "name" }

        assertEquals(false, (field.access and Opcodes.ACC_FINAL) != 0)
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
    fun overwriteCanReplaceMethodAfterReplaceAllMethodsInSameMixin() {
        AsmRegistry.register(ReplaceAllThenOverwriteMixin::class.java)

        val transformed = AsmProcessor().transform("ReturnTarget", returnTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ReturnTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("overwritten", result)
    }

    @Test
    fun removeMethodWithMissingTargetFailsDuringTransform() {
        AsmRegistry.register(MissingRemoveMethodTargetMixin::class.java)

        assertThrows(AsmTransformException::class.java) {
            AsmProcessor().transform("StrictTarget", strictTargetBytes(), javaClass.classLoader)
        }
    }

    @Test
    fun removeFieldRemovesTargetField() {
        AsmRegistry.register(RemoveFieldMixin::class.java)

        val transformed = AsmProcessor().transform("FieldTarget", fieldTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)

        assertEquals(false, classNode.fields.any { it.name == "name" })
    }

    @Test
    fun removeFieldOnMixinFieldUsesAnnotatedFieldName() {
        AsmRegistry.register(RemoveFieldByFieldDeclarationMixin::class.java)

        val transformed = AsmProcessor().transform("FieldTarget", fieldTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)

        assertEquals(false, classNode.fields.any { it.name == "name" })
    }

    @Test
    fun removeFieldInfersTargetFieldFromRemoveMethodName() {
        AsmRegistry.register(RemoveFieldByRemoveMethodNameMixin::class.java)

        val transformed = AsmProcessor().transform("FieldTarget", fieldTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)

        assertEquals(false, classNode.fields.any { it.name == "name" })
    }

    @Test
    fun removeFieldInfersTargetFieldFromAccessorStyleMethodNames() {
        AsmRegistry.register(RemoveFieldByGetterNameMixin::class.java)
        AsmRegistry.register(RemoveFieldBySetterNameMixin::class.java)
        AsmRegistry.register(RemoveFieldByBooleanGetterNameMixin::class.java)

        val transformed = AsmProcessor().transform("FieldInferenceTarget", fieldInferenceTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)

        assertEquals(false, classNode.fields.any { it.name == "name" })
        assertEquals(false, classNode.fields.any { it.name == "score" })
        assertEquals(false, classNode.fields.any { it.name == "active" })
    }

    @Test
    fun removeFieldWithMissingTargetFailsDuringTransform() {
        AsmRegistry.register(MissingRemoveFieldTargetMixin::class.java)

        assertThrows(AsmTransformException::class.java) {
            AsmProcessor().transform("FieldTarget", fieldTargetBytes(), javaClass.classLoader)
        }
    }

    @Test
    fun addFieldAddsMissingFieldDeclaration() {
        AsmRegistry.register(AddFieldMixin::class.java)

        val transformed = AsmProcessor().transform("StrictTarget", strictTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val field = classNode.fields.single { it.name == "extraName" }

        assertEquals("Ljava/lang/String;", field.desc)
        assertEquals(true, (field.access and Opcodes.ACC_PRIVATE) != 0)
    }

    @Test
    fun addFieldUsesExplicitTargetName() {
        AsmRegistry.register(AddRenamedFieldMixin::class.java)

        val transformed = AsmProcessor().transform("StrictTarget", strictTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val field = classNode.fields.single { it.name == "renamedScore" }

        assertEquals("I", field.desc)
    }

    @Test
    fun addFieldSkipsExistingFieldName() {
        AsmRegistry.register(AddExistingFieldMixin::class.java)

        val transformed = AsmProcessor().transform("FieldTarget", fieldTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)

        assertEquals(1, classNode.fields.count { it.name == "name" })
    }

    @Test
    fun uniqueAnnotationIsAvailableForMemberConflictAvoidance() {
        val annotationClass = Class.forName("kim.der.asm.api.annotation.Unique")

        assertEquals(0, annotationClass.declaredMethods.size)
    }

    @Test
    fun uniqueCopyRenamesConflictingMethodAndRewritesCalls() {
        AsmRegistry.register(UniqueCopyMixin::class.java)

        val transformed = AsmProcessor().transform("UniqueCopyTarget", uniqueCopyTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val uniqueMethod =
            classNode.methods.single {
                it.desc == "()Ljava/lang/String;" &&
                    it.name.startsWith("helper\$") &&
                    (it.access and Opcodes.ACC_SYNTHETIC) != 0
            }
        val clazz = loadClass("UniqueCopyTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("entry").invoke(instance)

        assertEquals("unique", result)
        assertEquals(true, (uniqueMethod.access and Opcodes.ACC_PRIVATE) != 0)
        assertEquals(1, classNode.methods.count { it.name == "helper" && it.desc == "()Ljava/lang/String;" })
    }

    @Test
    fun uniqueCopyRewritesOverwriteCallsToRenamedMethod() {
        AsmRegistry.register(UniqueCopyOverwriteMixin::class.java)

        val transformed = AsmProcessor().transform("UniqueCopyOverwriteTarget", uniqueCopyOverwriteTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("UniqueCopyOverwriteTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("entry").invoke(instance)

        assertEquals("unique", result)
    }

    @Test
    fun uniqueCopyRewritesInlineCallsToRenamedMethod() {
        AsmRegistry.register(UniqueCopyInlineMixin::class.java)

        val transformed = AsmProcessor().transform("UniqueCopyInlineTarget", uniqueCopyInlineTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("UniqueCopyInlineTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("run").invoke(instance)
    }

    @Test
    fun addInterfaceAddsMissingInterface() {
        AsmRegistry.register(AddCloseableInterfaceMixin::class.java)

        val transformed = AsmProcessor().transform("InterfaceTarget", interfaceTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)

        assertEquals(true, classNode.interfaces.contains("java/io/Closeable"))
    }

    @Test
    fun addInterfaceDoesNotDuplicateExistingInterface() {
        AsmRegistry.register(AddRunnableInterfaceMixin::class.java)

        val transformed = AsmProcessor().transform("InterfaceTarget", interfaceTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val runnableCount = classNode.interfaces.count { it == "java/lang/Runnable" }

        assertEquals(1, runnableCount)
    }

    @Test
    fun addInterfaceNormalizesBinaryNamesAndDeduplicatesInput() {
        AsmRegistry.register(AddNormalizedInterfacesMixin::class.java)

        val transformed = AsmProcessor().transform("InterfaceTarget", interfaceTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)

        assertEquals(1, classNode.interfaces.count { it == "java/lang/Runnable" })
        assertEquals(1, classNode.interfaces.count { it == "java/lang/Cloneable" })
        assertEquals(true, classNode.interfaces.contains("java/io/Serializable"))
    }

    @Test
    fun removeInterfaceRemovesExistingInterface() {
        AsmRegistry.register(RemoveRunnableInterfaceMixin::class.java)

        val transformed = AsmProcessor().transform("InterfaceTarget", interfaceTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)

        assertEquals(false, classNode.interfaces.contains("java/lang/Runnable"))
    }

    @Test
    fun removeInterfaceNormalizesBinaryNamesAndDeduplicatesInput() {
        AsmRegistry.register(RemoveNormalizedInterfacesMixin::class.java)

        val transformed = AsmProcessor().transform("MultiInterfaceTarget", multiInterfaceTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)

        assertEquals(false, classNode.interfaces.contains("java/lang/Runnable"))
        assertEquals(false, classNode.interfaces.contains("java/lang/Cloneable"))
        assertEquals(true, classNode.interfaces.contains("java/io/Serializable"))
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
    fun invokeInjectOrdinalSelectsSingleMatchedCall() {
        AsmRegistry.register(InvokeOrdinalMixin::class.java)

        val transformed = AsmProcessor().transform("MultiInvokeTarget", multiInvokeTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val method = classNode.methods.single { it.name == "call" }
        val instructions = method.instructions.toArray()
        val mixinOwner = org.objectweb.asm.Type.getInternalName(InvokeOrdinalMixin::class.java)
        val handlerCallIndexes = instructions.mapIndexedNotNull { index, insn ->
            if (insn is org.objectweb.asm.tree.MethodInsnNode && insn.owner == mixinOwner && insn.name == "inject") {
                index
            } else {
                null
            }
        }
        val trimIndexes = instructions.mapIndexedNotNull { index, insn ->
            if (insn is org.objectweb.asm.tree.MethodInsnNode &&
                insn.owner == "java/lang/String" &&
                insn.name == "trim"
            ) {
                index
            } else {
                null
            }
        }

        assertEquals(2, trimIndexes.size)
        assertEquals(1, handlerCallIndexes.size)
        assertEquals(true, handlerCallIndexes.single() > trimIndexes[0])
        assertEquals(true, handlerCallIndexes.single() < trimIndexes[1])
    }

    @Test
    fun asmInjectInvokeSliceLimitsMatchedCallsBetweenFromAndTo() {
        AsmRegistry.register(InvokeSliceMixin::class.java)

        val transformed = AsmProcessor().transform("SliceInvokeTarget", sliceInvokeTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val method = classNode.methods.single { it.name == "call" }
        val instructions = method.instructions.toArray()
        val mixinOwner = org.objectweb.asm.Type.getInternalName(InvokeSliceMixin::class.java)
        val handlerCallIndexes = instructions.mapIndexedNotNull { index, insn ->
            if (insn is org.objectweb.asm.tree.MethodInsnNode && insn.owner == mixinOwner && insn.name == "inject") {
                index
            } else {
                null
            }
        }
        val trimIndexes = instructions.mapIndexedNotNull { index, insn ->
            if (insn is org.objectweb.asm.tree.MethodInsnNode &&
                insn.owner == "java/lang/String" &&
                insn.name == "trim"
            ) {
                index
            } else {
                null
            }
        }
        val boundaryIndexes = instructions.mapIndexedNotNull { index, insn ->
            if (insn is org.objectweb.asm.tree.MethodInsnNode &&
                insn.owner == "java/lang/String" &&
                insn.name == "toString"
            ) {
                index
            } else {
                null
            }
        }

        assertEquals(3, trimIndexes.size)
        assertEquals(2, boundaryIndexes.size)
        assertEquals(1, handlerCallIndexes.size)
        assertEquals(true, handlerCallIndexes.single() > boundaryIndexes[0])
        assertEquals(true, handlerCallIndexes.single() < boundaryIndexes[1])
        assertEquals(true, handlerCallIndexes.single() < trimIndexes[1])
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
    fun modifyVariableAtHeadRewritesInstanceMethodParameterByLocalIndex() {
        AsmRegistry.register(ModifyVariableInstanceParamMixin::class.java)

        val transformed = AsmProcessor().transform("VariableTarget", variableTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("VariableTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("echo", String::class.java).invoke(instance, "value")

        assertEquals("modified-value", result)
    }

    @Test
    fun modifyVariableExposesCountContractParameters() {
        val methods = ModifyVariable::class.java.declaredMethods.associateBy { it.name }

        assertEquals(Int::class.javaPrimitiveType, methods["require"]?.returnType)
        assertEquals(Int::class.javaPrimitiveType, methods["expect"]?.returnType)
        assertEquals(Int::class.javaPrimitiveType, methods["allow"]?.returnType)
    }

    @Test
    fun modifyVariableAtHeadRewritesStaticMethodParameterByLocalIndex() {
        AsmRegistry.register(ModifyVariableStaticParamMixin::class.java)

        val transformed = AsmProcessor().transform("StaticVariableTarget", staticVariableTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("StaticVariableTarget", transformed)
        val result = clazz.getMethod("echo", String::class.java).invoke(null, "value")

        assertEquals("static-value", result)
    }

    @Test
    fun modifyVariableAtHeadCanUseTargetMethodParameters() {
        AsmRegistry.register(ModifyVariableHeadTargetParamsMixin::class.java)

        val transformed = AsmProcessor().transform("VariableTarget", variableTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("VariableTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("echo", String::class.java).invoke(instance, "value")

        assertEquals("value-value", result)
    }

    @Test
    fun modifyVariableAtHeadCanUseStaticTargetMethodParameters() {
        AsmRegistry.register(ModifyVariableStaticHeadTargetParamsMixin::class.java)

        val transformed = AsmProcessor().transform("StaticVariableTarget", staticVariableTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("StaticVariableTarget", transformed)
        val result = clazz.getMethod("echo", String::class.java).invoke(null, "value")

        assertEquals("value-value-static", result)
    }

    @Test
    fun modifyVariableAtHeadSelectsParameterByTypeOrdinal() {
        AsmRegistry.register(ModifyVariableOrdinalParamMixin::class.java)

        val transformed = AsmProcessor().transform("OrdinalVariableTarget", ordinalVariableTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("OrdinalVariableTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("combine", String::class.java, String::class.java).invoke(instance, "first", "second")

        assertEquals("first:ordinal-second", result)
    }

    @Test
    fun modifyVariableAtStoreRewritesStoredLocalVariableByIndex() {
        AsmRegistry.register(ModifyVariableStoreMixin::class.java)

        val transformed = AsmProcessor().transform("StoreVariableTarget", storeVariableTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("StoreVariableTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("stored-local", result)
    }

    @Test
    fun modifyVariableAtStoreSelectsStoredLocalVariableByTypeOrdinal() {
        AsmRegistry.register(ModifyVariableStoreOrdinalMixin::class.java)

        val transformed = AsmProcessor().transform("StoreOrdinalVariableTarget", storeOrdinalVariableTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("StoreOrdinalVariableTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("first:stored-second", result)
    }

    @Test
    fun modifyVariableRequireGreaterThanMatchedCountFailsDuringTransform() {
        AsmRegistry.register(RequireThreeModifyVariableMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("StoreOrdinalVariableTarget", storeOrdinalVariableTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("requires at least 3 injection(s), actual 2") == true,
        )
    }

    @Test
    fun modifyVariableAllowLessThanMatchedCountFailsDuringTransform() {
        AsmRegistry.register(AllowOneModifyVariableMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("StoreOrdinalVariableTarget", storeOrdinalVariableTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("allows at most 1 injection(s), actual 2") == true,
        )
    }

    @Test
    fun modifyVariableExpectMismatchReportsWarningWithoutFailingTransform() {
        AsmRegistry.register(ExpectThreeModifyVariableMixin::class.java)
        val originalErr = System.err
        val output = ByteArrayOutputStream()

        try {
            PrintStream(output, true, Charsets.UTF_8.name()).use { capture ->
                System.setErr(capture)
                AsmProcessor().transform("StoreOrdinalVariableTarget", storeOrdinalVariableTargetBytes(), javaClass.classLoader)
            }
        } finally {
            System.setErr(originalErr)
        }

        assertEquals(
            true,
            output.toString(Charsets.UTF_8.name()).contains("expected 3 injection(s), actual 2"),
        )
    }

    @Test
    fun modifyVariableAtStoreCanUseTargetMethodParameters() {
        AsmRegistry.register(ModifyVariableStoreTargetParamsMixin::class.java)

        val transformed = AsmProcessor().transform("StoreVariableParamTarget", storeVariableParamTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("StoreVariableParamTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result =
            clazz.getMethod("value", String::class.java, Int::class.javaPrimitiveType).invoke(instance, "suffix", 3)

        assertEquals("stored-local-suffix3", result)
    }

    @Test
    fun modifyVariableStoreSliceLimitsLocalStoresBetweenFromAndTo() {
        AsmRegistry.register(ModifyVariableStoreSliceMixin::class.java)

        val transformed =
            AsmProcessor().transform("SliceStoreVariableTarget", sliceStoreVariableTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("SliceStoreVariableTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("pre:stored-inside:outside", result)
    }

    @Test
    fun modifyVariableAtLoadRewritesLoadedLocalVariableByIndex() {
        AsmRegistry.register(ModifyVariableLoadMixin::class.java)

        val transformed = AsmProcessor().transform("LoadVariableTarget", loadVariableTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("LoadVariableTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("loaded-local", result)
    }

    @Test
    fun modifyVariableAtLoadSelectsLoadedLocalVariableByTypeOrdinal() {
        AsmRegistry.register(ModifyVariableLoadOrdinalMixin::class.java)

        val transformed = AsmProcessor().transform("LoadOrdinalVariableTarget", loadOrdinalVariableTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("LoadOrdinalVariableTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("first:loaded-second", result)
    }

    @Test
    fun modifyVariableLoadSliceLimitsLocalLoadsBetweenFromAndTo() {
        AsmRegistry.register(ModifyVariableLoadSliceMixin::class.java)

        val transformed =
            AsmProcessor().transform("SliceLoadVariableTarget", sliceLoadVariableTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("SliceLoadVariableTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("pre:loaded-inside:outside", result)
    }

    @Test
    fun asmInjectLoadSliceLimitsLocalLoadsBetweenFromAndTo() {
        AsmRegistry.register(LoadInjectSliceMixin::class.java)
        LoadInjectSliceMixin.injectCount = 0

        val transformed =
            AsmProcessor().transform("SliceLoadVariableTarget", sliceLoadVariableTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("SliceLoadVariableTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("pre:inside:outside", result)
        assertEquals(1, LoadInjectSliceMixin.injectCount)
    }

    @Test
    fun modifyVariableAtLoadCanUseTargetMethodParameters() {
        AsmRegistry.register(ModifyVariableLoadTargetParamsMixin::class.java)

        val transformed = AsmProcessor().transform("LoadVariableParamTarget", loadVariableParamTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("LoadVariableParamTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result =
            clazz.getMethod("value", String::class.java, Int::class.javaPrimitiveType).invoke(instance, "suffix", 3)

        assertEquals("loaded-local-suffix3", result)
    }

    @Test
    fun modifyVariableWithTooManyTargetMethodParametersFailsDuringTransform() {
        AsmRegistry.register(TooManyModifyVariableParametersMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("VariableTarget", variableTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("requests 2 target parameter(s)") == true,
        )
    }

    @Test
    fun asmInjectCanRunBeforeLocalVariableLoad() {
        AsmRegistry.register(LoadInjectMixin::class.java)
        LoadInjectMixin.injectCount = 0

        val transformed = AsmProcessor().transform("LoadVariableTarget", loadVariableTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("LoadVariableTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("local", result)
        assertEquals(1, LoadInjectMixin.injectCount)
    }

    @Test
    fun asmInjectLoadArgsIndexLimitsLocalVariableSlot() {
        AsmRegistry.register(LoadInjectIndexMixin::class.java)
        LoadInjectIndexMixin.injectCount = 0

        val transformed = AsmProcessor().transform("LoadOrdinalVariableTarget", loadOrdinalVariableTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("LoadOrdinalVariableTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("first:second", result)
        assertEquals(1, LoadInjectIndexMixin.injectCount)
    }

    @Test
    fun asmInjectCanRunAfterLocalVariableStore() {
        AsmRegistry.register(StoreInjectMixin::class.java)
        StoreInjectMixin.injectCount = 0

        val transformed = AsmProcessor().transform("StoreVariableTarget", storeVariableTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("StoreVariableTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("local", result)
        assertEquals(1, StoreInjectMixin.injectCount)
    }

    @Test
    fun asmInjectStoreArgsVarLimitsLocalVariableSlot() {
        AsmRegistry.register(StoreInjectVarMixin::class.java)
        StoreInjectVarMixin.injectCount = 0

        val transformed = AsmProcessor().transform("StoreOrdinalVariableTarget", storeOrdinalVariableTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("StoreOrdinalVariableTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("first:second", result)
        assertEquals(1, StoreInjectVarMixin.injectCount)
    }

    @Test
    fun asmInjectStoreSliceLimitsLocalStoresBetweenFromAndTo() {
        AsmRegistry.register(StoreInjectSliceMixin::class.java)
        StoreInjectSliceMixin.injectCount = 0

        val transformed =
            AsmProcessor().transform("SliceStoreVariableTarget", sliceStoreVariableTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("SliceStoreVariableTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("pre:inside:outside", result)
        assertEquals(2, StoreInjectSliceMixin.injectCount)
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
    fun invokerCanGenerateConstructorFactoryMethod() {
        AsmRegistry.register(ConstructorInvokerMixin::class.java)

        val transformed =
            AsmProcessor().transform("ConstructorInvokerTarget", constructorInvokerTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ConstructorInvokerTarget", transformed)
        val created = clazz.getMethod("create", String::class.java).invoke(null, "created")
        val result = clazz.getMethod("value").invoke(created)

        assertEquals("created", result)
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
    fun fieldInjectInsertsHandlerBeforeMatchedFieldRead() {
        AsmRegistry.register(FieldReadInjectMixin::class.java)

        val transformed = AsmProcessor().transform("FieldPointTarget", fieldPointTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val method = classNode.methods.single { it.name == "readName" }
        val instructions = method.instructions.toArray()
        val handlerCallIndex = handlerCallIndex(instructions, FieldReadInjectMixin::class.java, "inject")
        val fieldReadIndex = instructions.indexOfFirst {
            it is org.objectweb.asm.tree.FieldInsnNode &&
                it.opcode == Opcodes.GETFIELD &&
                it.owner == "FieldPointTarget" &&
                it.name == "name"
        }

        assertEquals(true, handlerCallIndex >= 0)
        assertEquals(true, fieldReadIndex >= 0)
        assertEquals(fieldReadIndex - 1, handlerCallIndex)
    }

    @Test
    fun fieldInjectOrdinalSelectsSingleMatchedFieldRead() {
        AsmRegistry.register(FieldReadOrdinalMixin::class.java)

        val transformed = AsmProcessor().transform("MultiFieldReadTarget", multiFieldReadTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val method = classNode.methods.single { it.name == "readTwice" }
        val instructions = method.instructions.toArray()
        val mixinOwner = org.objectweb.asm.Type.getInternalName(FieldReadOrdinalMixin::class.java)
        val handlerCallIndexes = instructions.mapIndexedNotNull { index, insn ->
            if (insn is org.objectweb.asm.tree.MethodInsnNode && insn.owner == mixinOwner && insn.name == "inject") {
                index
            } else {
                null
            }
        }
        val fieldReadIndexes = instructions.mapIndexedNotNull { index, insn ->
            if (insn is org.objectweb.asm.tree.FieldInsnNode &&
                insn.opcode == Opcodes.GETFIELD &&
                insn.owner == "MultiFieldReadTarget" &&
                insn.name == "name"
            ) {
                index
            } else {
                null
            }
        }

        assertEquals(2, fieldReadIndexes.size)
        assertEquals(1, handlerCallIndexes.size)
        assertEquals(fieldReadIndexes[1] - 1, handlerCallIndexes.single())
    }

    @Test
    fun asmInjectFieldSliceLimitsFieldReadsBetweenFromAndTo() {
        AsmRegistry.register(FieldReadSliceMixin::class.java)
        FieldReadSliceMixin.injectCount = 0

        val transformed = AsmProcessor().transform("SliceFieldReadTarget", sliceFieldReadTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("SliceFieldReadTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeName", String::class.java).invoke(instance, "raw")
        val result = clazz.getMethod("readSelected").invoke(instance)

        assertEquals("raw", result)
        assertEquals(1, FieldReadSliceMixin.injectCount)
    }

    @Test
    fun fieldInjectWithMissingTargetFailsDuringTransform() {
        AsmRegistry.register(MissingFieldReadInjectMixin::class.java)

        assertThrows(AsmTransformException::class.java) {
            AsmProcessor().transform("FieldPointTarget", fieldPointTargetBytes(), javaClass.classLoader)
        }
    }

    @Test
    fun fieldInjectDropsUnusedHandlerReturnValue() {
        AsmRegistry.register(FieldReadReturningHandlerMixin::class.java)

        val transformed = AsmProcessor().transform("FieldPointTarget", fieldPointTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("FieldPointTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("readName").invoke(instance)

        assertEquals(null, result)
    }

    @Test
    fun asmInjectFieldAssignSliceLimitsFieldWritesBetweenFromAndTo() {
        AsmRegistry.register(FieldAssignSliceMixin::class.java)
        FieldAssignSliceMixin.injectCount = 0

        val transformed =
            AsmProcessor().transform("SliceFieldAssignTarget", sliceFieldAssignTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("SliceFieldAssignTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeSelected", String::class.java, String::class.java).invoke(instance, "outside", "inside")

        assertEquals("inside", clazz.getField("name").get(instance))
        assertEquals(1, FieldAssignSliceMixin.injectCount)
    }

    @Test
    fun redirectMethodCallSupportsKotlinObjectHandler() {
        AsmRegistry.register(ObjectInstanceRedirectMixin::class.java)

        val transformed = AsmProcessor().transform("RedirectTarget", redirectTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("RedirectTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("call").invoke(instance)

        assertEquals("object- value ", result)
    }

    @Test
    fun redirectMethodCallCanUseTargetMethodParameters() {
        AsmRegistry.register(RedirectWithTargetParamsMixin::class.java)

        val transformed = AsmProcessor().transform("RedirectParamTarget", redirectParamTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("RedirectParamTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value", String::class.java, Int::class.javaPrimitiveType).invoke(instance, "suffix", 3)

        assertEquals("base-suffix3", result)
    }

    @Test
    fun redirectStaticMethodCallCanUseTargetMethodParameters() {
        AsmRegistry.register(StaticRedirectWithTargetParamsMixin::class.java)

        val transformed = AsmProcessor().transform("StaticRedirectParamTarget", staticRedirectParamTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("StaticRedirectParamTarget", transformed)
        val result = clazz.getMethod("value", String::class.java, Int::class.javaPrimitiveType).invoke(null, "suffix", 4)

        assertEquals("42-suffix4", result)
    }

    @Test
    fun redirectConstructorCallReplacesNewObjectExpression() {
        AsmRegistry.register(ConstructorRedirectMixin::class.java)

        val transformed =
            AsmProcessor().transform("ConstructorModifyArgTarget", constructorModifyArgTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ConstructorModifyArgTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("redirected-raw", result)
    }

    @Test
    fun redirectConstructorCallCanUseTargetMethodParameters() {
        AsmRegistry.register(ConstructorRedirectWithTargetParamsMixin::class.java)

        val transformed = AsmProcessor().transform("NewParamTarget", newParamTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("NewParamTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("create", String::class.java, Int::class.javaPrimitiveType).invoke(instance, "prefix", 7)

        assertEquals("prefix-7", result.toString())
    }

    @Test
    fun redirectConstructorCallWithMismatchedHandlerReturnFailsDuringTransform() {
        AsmRegistry.register(MismatchedConstructorRedirectMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("ConstructorModifyArgTarget", constructorModifyArgTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("return type mismatch") == true ||
                exception.cause?.message?.contains("parameter") == true,
        )
    }

    @Test
    fun redirectFieldReadReplacesGetFieldValue() {
        AsmRegistry.register(FieldReadRedirectMixin::class.java)

        val transformed = AsmProcessor().transform("FieldPointTarget", fieldPointTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("FieldPointTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("readName").invoke(instance)

        assertEquals("redirected", result)
    }

    @Test
    fun redirectFieldReadSupportsKotlinObjectHandler() {
        AsmRegistry.register(ObjectInstanceFieldReadRedirectMixin::class.java)

        val transformed = AsmProcessor().transform("FieldPointTarget", fieldPointTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("FieldPointTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("readName").invoke(instance)

        assertEquals("object-field", result)
    }

    @Test
    fun redirectFieldReadMatchesNameOnlyTargetWhenAtValueIsField() {
        AsmRegistry.register(FieldReadNameOnlyRedirectMixin::class.java)

        val transformed = AsmProcessor().transform("FieldPointTarget", fieldPointTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("FieldPointTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("readName").invoke(instance)

        assertEquals("name-only", result)
    }

    @Test
    fun redirectStaticFieldReadReplacesGetStaticValue() {
        AsmRegistry.register(StaticFieldReadRedirectMixin::class.java)

        val transformed = AsmProcessor().transform("StaticFieldPointTarget", staticFieldPointTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("StaticFieldPointTarget", transformed)
        val result = clazz.getMethod("readName").invoke(null)

        assertEquals("static-redirected", result)
    }

    @Test
    fun redirectStaticFieldReadSupportsKotlinObjectHandler() {
        AsmRegistry.register(ObjectInstanceStaticFieldReadRedirectMixin::class.java)

        val transformed = AsmProcessor().transform("StaticFieldPointTarget", staticFieldPointTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("StaticFieldPointTarget", transformed)
        val result = clazz.getMethod("readName").invoke(null)

        assertEquals("object-static-field", result)
    }

    @Test
    fun redirectFieldReadCanUseTargetMethodParameters() {
        AsmRegistry.register(FieldReadWithTargetParamsMixin::class.java)

        val transformed = AsmProcessor().transform("FieldParamTarget", fieldParamTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("FieldParamTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("readName", String::class.java, Int::class.javaPrimitiveType).invoke(instance, "suffix", 3)

        assertEquals("field-suffix3", result)
    }

    @Test
    fun redirectStaticFieldReadCanUseTargetMethodParameters() {
        AsmRegistry.register(StaticFieldReadWithTargetParamsMixin::class.java)

        val transformed = AsmProcessor().transform("StaticFieldParamTarget", staticFieldParamTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("StaticFieldParamTarget", transformed)
        val result = clazz.getMethod("readName", String::class.java, Int::class.javaPrimitiveType).invoke(null, "suffix", 4)

        assertEquals("static-field-suffix4", result)
    }

    @Test
    fun redirectFieldAssignReplacesPutFieldWrite() {
        AsmRegistry.register(FieldAssignRedirectMixin::class.java)

        val transformed = AsmProcessor().transform("FieldPointTarget", fieldPointTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("FieldPointTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeName", String::class.java).invoke(instance, "original")
        val result = clazz.getMethod("readName").invoke(instance)

        assertEquals(null, result)
        assertEquals("original", FieldAssignRedirectMixin.lastValue)
    }

    @Test
    fun redirectFieldAssignSupportsKotlinObjectHandler() {
        ObjectInstanceFieldAssignRedirectMixin.lastValue = null
        AsmRegistry.register(ObjectInstanceFieldAssignRedirectMixin::class.java)

        val transformed = AsmProcessor().transform("FieldPointTarget", fieldPointTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("FieldPointTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeName", String::class.java).invoke(instance, "original")
        val result = clazz.getMethod("readName").invoke(instance)

        assertEquals(null, result)
        assertEquals("object-original", ObjectInstanceFieldAssignRedirectMixin.lastValue)
    }

    @Test
    fun redirectStaticFieldAssignReplacesPutStaticWrite() {
        AsmRegistry.register(StaticFieldAssignRedirectMixin::class.java)

        val transformed = AsmProcessor().transform("StaticFieldPointTarget", staticFieldPointTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("StaticFieldPointTarget", transformed)

        clazz.getMethod("writeName", String::class.java).invoke(null, "static-original")
        val result = clazz.getMethod("readName").invoke(null)

        assertEquals(null, result)
        assertEquals("static-original", StaticFieldAssignRedirectMixin.lastValue)
    }

    @Test
    fun redirectStaticFieldAssignSupportsKotlinObjectHandler() {
        ObjectInstanceStaticFieldAssignRedirectMixin.lastValue = null
        AsmRegistry.register(ObjectInstanceStaticFieldAssignRedirectMixin::class.java)

        val transformed = AsmProcessor().transform("StaticFieldPointTarget", staticFieldPointTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("StaticFieldPointTarget", transformed)

        clazz.getMethod("writeName", String::class.java).invoke(null, "static-original")
        val result = clazz.getMethod("readName").invoke(null)

        assertEquals(null, result)
        assertEquals("object-static-original", ObjectInstanceStaticFieldAssignRedirectMixin.lastValue)
    }

    @Test
    fun redirectFieldAssignCanUseTargetMethodParameters() {
        FieldAssignWithTargetParamsMixin.lastValue = null
        AsmRegistry.register(FieldAssignWithTargetParamsMixin::class.java)

        val transformed = AsmProcessor().transform("FieldParamTarget", fieldParamTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("FieldParamTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeName", String::class.java, String::class.java, Int::class.javaPrimitiveType)
            .invoke(instance, "field", "suffix", 5)

        assertEquals("field-suffix5", FieldAssignWithTargetParamsMixin.lastValue)
    }

    @Test
    fun redirectStaticFieldAssignCanUseTargetMethodParameters() {
        StaticFieldAssignWithTargetParamsMixin.lastValue = null
        AsmRegistry.register(StaticFieldAssignWithTargetParamsMixin::class.java)

        val transformed = AsmProcessor().transform("StaticFieldParamTarget", staticFieldParamTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("StaticFieldParamTarget", transformed)

        clazz.getMethod("writeName", String::class.java, String::class.java, Int::class.javaPrimitiveType)
            .invoke(null, "static-field", "suffix", 6)

        assertEquals("static-field-suffix6", StaticFieldAssignWithTargetParamsMixin.lastValue)
    }

    @Test
    fun redirectArrayReadReplacesObjectArrayElementAccess() {
        AsmRegistry.register(ArrayReadRedirectMixin::class.java)

        val transformed = AsmProcessor().transform("ArrayAccessTarget", arrayAccessTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ArrayAccessTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("readName", Int::class.javaPrimitiveType).invoke(instance, 0)

        assertEquals("redirected-raw", result)
    }

    @Test
    fun redirectArrayWriteReplacesObjectArrayElementStore() {
        AsmRegistry.register(ArrayWriteRedirectMixin::class.java)

        val transformed = AsmProcessor().transform("ArrayAccessTarget", arrayAccessTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ArrayAccessTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeName", Int::class.javaPrimitiveType, String::class.java).invoke(instance, 0, "raw")
        val result = clazz.getMethod("readName", Int::class.javaPrimitiveType).invoke(instance, 0)

        assertEquals("written-raw", result)
    }

    @Test
    fun redirectArrayReadReplacesPrimitiveArrayElementAccess() {
        AsmRegistry.register(PrimitiveArrayReadRedirectMixin::class.java)

        val transformed =
            AsmProcessor().transform("PrimitiveArrayAccessTarget", primitiveArrayAccessTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("PrimitiveArrayAccessTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("readScore", Int::class.javaPrimitiveType).invoke(instance, 0)

        assertEquals(42, result)
    }

    @Test
    fun redirectArrayWriteReplacesPrimitiveArrayElementStore() {
        AsmRegistry.register(PrimitiveArrayWriteRedirectMixin::class.java)

        val transformed =
            AsmProcessor().transform("PrimitiveArrayAccessTarget", primitiveArrayAccessTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("PrimitiveArrayAccessTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeScore", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(instance, 0, 40)
        val result = clazz.getMethod("readScore", Int::class.javaPrimitiveType).invoke(instance, 0)

        assertEquals(42, result)
    }

    @Test
    fun redirectArrayReadCanUseTargetMethodParameters() {
        AsmRegistry.register(ArrayReadWithTargetParamsRedirectMixin::class.java)

        val transformed = AsmProcessor().transform("ArrayParamTarget", arrayParamTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ArrayParamTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("readName", Int::class.javaPrimitiveType, String::class.java).invoke(instance, 0, "suffix")

        assertEquals("raw-suffix", result)
    }

    @Test
    fun redirectArrayLengthReplacesArrayLengthAccess() {
        AsmRegistry.register(ArrayLengthRedirectMixin::class.java)

        val transformed = AsmProcessor().transform("ArrayAccessTarget", arrayAccessTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ArrayAccessTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("nameCount").invoke(instance)

        assertEquals(6, result)
    }

    @Test
    fun redirectArrayLengthCanUseTargetMethodParameters() {
        AsmRegistry.register(ArrayLengthWithTargetParamsRedirectMixin::class.java)

        val transformed = AsmProcessor().transform("ArrayAccessTarget", arrayAccessTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ArrayAccessTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("nameCount", Int::class.javaPrimitiveType).invoke(instance, 4)

        assertEquals(5, result)
    }

    @Test
    fun redirectArrayLengthWithMismatchedHandlerReturnFailsDuringTransform() {
        AsmRegistry.register(MismatchedArrayLengthRedirectMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("ArrayAccessTarget", arrayAccessTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("array length handler") == true,
        )
    }

    @Test
    fun redirectArrayReadWithMismatchedHandlerParametersFailsDuringTransform() {
        AsmRegistry.register(MismatchedArrayReadRedirectMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("ArrayAccessTarget", arrayAccessTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("parameter #") == true,
        )
    }

    @Test
    fun redirectOrdinalSelectsSingleMethodCall() {
        AsmRegistry.register(RedirectOrdinalTrimMixin::class.java)

        val transformed = AsmProcessor().transform("RedirectOrdinalTarget", redirectOrdinalTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("RedirectOrdinalTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("first:redirected", result)
    }

    @Test
    fun redirectRequireGreaterThanMatchedCountFailsDuringTransform() {
        AsmRegistry.register(RequireThreeRedirectMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("RedirectOrdinalTarget", redirectOrdinalTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("requires at least 3 injection(s), actual 2") == true,
        )
    }

    @Test
    fun redirectAllowLessThanMatchedCountFailsDuringTransform() {
        AsmRegistry.register(AllowOneRedirectMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("RedirectOrdinalTarget", redirectOrdinalTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("allows at most 1 injection(s), actual 2") == true,
        )
    }

    @Test
    fun redirectExpectMismatchReportsWarningWithoutFailingTransform() {
        AsmRegistry.register(ExpectThreeRedirectMixin::class.java)
        val originalErr = System.err
        val output = ByteArrayOutputStream()

        try {
            PrintStream(output, true, Charsets.UTF_8.name()).use { capture ->
                System.setErr(capture)
                AsmProcessor().transform("RedirectOrdinalTarget", redirectOrdinalTargetBytes(), javaClass.classLoader)
            }
        } finally {
            System.setErr(originalErr)
        }

        assertEquals(
            true,
            output.toString(Charsets.UTF_8.name()).contains("expected 3 injection(s), actual 2"),
        )
    }

    @Test
    fun redirectSliceLimitsMethodCallMatchesBetweenFromAndTo() {
        AsmRegistry.register(RedirectSliceTrimMixin::class.java)

        val transformed = AsmProcessor().transform("RedirectSliceTarget", redirectSliceTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("RedirectSliceTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("pre:redirected:outside", result)
    }

    @Test
    fun redirectOrdinalSelectsSingleFieldRead() {
        AsmRegistry.register(FieldReadRedirectOrdinalMixin::class.java)

        val transformed = AsmProcessor().transform("FieldReadOrdinalTarget", fieldReadOrdinalTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("FieldReadOrdinalTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        clazz.getField("name").set(instance, "original")
        val result = clazz.getMethod("readBoth").invoke(instance)

        assertEquals("original:redirected", result)
    }

    @Test
    fun redirectOrdinalSelectsSingleFieldAssign() {
        FieldAssignRedirectOrdinalMixin.lastValue = null
        AsmRegistry.register(FieldAssignRedirectOrdinalMixin::class.java)

        val transformed = AsmProcessor().transform(
            "FieldAssignOrdinalTarget",
            fieldAssignOrdinalTargetBytes(),
            javaClass.classLoader,
        )
        val clazz = loadClass("FieldAssignOrdinalTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        clazz.getMethod("writeBoth", String::class.java, String::class.java).invoke(instance, "first", "second")
        val result = clazz.getField("name").get(instance)

        assertEquals("first", result)
        assertEquals("second", FieldAssignRedirectOrdinalMixin.lastValue)
    }

    @Test
    fun fieldAssignInjectInsertsHandlerBeforeMatchedFieldWrite() {
        AsmRegistry.register(FieldAssignInjectMixin::class.java)

        val transformed = AsmProcessor().transform("FieldPointTarget", fieldPointTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val method = classNode.methods.single { it.name == "writeName" }
        val instructions = method.instructions.toArray()
        val handlerCallIndex = handlerCallIndex(instructions, FieldAssignInjectMixin::class.java, "inject")
        val fieldWriteIndex = instructions.indexOfFirst {
            it is org.objectweb.asm.tree.FieldInsnNode &&
                it.opcode == Opcodes.PUTFIELD &&
                it.owner == "FieldPointTarget" &&
                it.name == "name"
        }

        assertEquals(true, handlerCallIndex >= 0)
        assertEquals(true, fieldWriteIndex >= 0)
        assertEquals(fieldWriteIndex - 1, handlerCallIndex)
    }

    @Test
    fun newInjectInsertsHandlerBeforeMatchedNewInstruction() {
        AsmRegistry.register(NewInstructionInjectMixin::class.java)

        val transformed = AsmProcessor().transform("NewInstructionTarget", newInstructionTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val method = classNode.methods.single { it.name == "create" }
        val instructions = method.instructions.toArray()
        val handlerCallIndex = handlerCallIndex(instructions, NewInstructionInjectMixin::class.java, "inject")
        val newIndex = instructions.indexOfFirst {
            it is org.objectweb.asm.tree.TypeInsnNode &&
                it.opcode == Opcodes.NEW &&
                it.desc == "java/lang/StringBuilder"
        }

        assertEquals(true, handlerCallIndex >= 0)
        assertEquals(true, newIndex >= 0)
        assertEquals(newIndex - 1, handlerCallIndex)
    }

    @Test
    fun castInjectInsertsHandlerBeforeMatchedCheckcastInstruction() {
        AsmRegistry.register(CastInstructionInjectMixin::class.java)

        val transformed = AsmProcessor().transform("CastInstructionTarget", castInstructionTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val method = classNode.methods.single { it.name == "cast" }
        val instructions = method.instructions.toArray()
        val handlerCallIndex = handlerCallIndex(instructions, CastInstructionInjectMixin::class.java, "inject")
        val castIndex = instructions.indexOfFirst {
            it is org.objectweb.asm.tree.TypeInsnNode &&
                it.opcode == Opcodes.CHECKCAST &&
                it.desc == "java/lang/String"
        }

        assertEquals(true, handlerCallIndex >= 0)
        assertEquals(true, castIndex >= 0)
        assertEquals(castIndex - 1, handlerCallIndex)
    }

    @Test
    fun asmInjectCastSliceLimitsCheckcastsBetweenFromAndTo() {
        AsmRegistry.register(CastInstructionSliceMixin::class.java)
        CastInstructionSliceMixin.injectCount = 0

        val transformed =
            AsmProcessor().transform("SliceCastInstructionTarget", sliceCastInstructionTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("SliceCastInstructionTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("castSelected", Any::class.java).invoke(instance, "raw")

        assertEquals("raw", result)
        assertEquals(1, CastInstructionSliceMixin.injectCount)
    }

    @Test
    fun newInjectAfterShiftFailsDuringTransform() {
        AsmRegistry.register(NewInstructionAfterInjectMixin::class.java)

        assertThrows(AsmTransformException::class.java) {
            AsmProcessor().transform("NewInstructionTarget", newInstructionTargetBytes(), javaClass.classLoader)
        }
    }

    @Test
    fun throwInjectInsertsHandlerBeforeMatchedThrowInstruction() {
        AsmRegistry.register(ThrowInstructionInjectMixin::class.java)

        val transformed = AsmProcessor().transform("ThrowPointTarget", throwPointTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val method = classNode.methods.single { it.name == "fail" }
        val instructions = method.instructions.toArray()
        val handlerCallIndex = handlerCallIndex(instructions, ThrowInstructionInjectMixin::class.java, "inject")
        val throwIndex = instructions.indexOfFirst { it.opcode == Opcodes.ATHROW }

        assertEquals(true, handlerCallIndex >= 0)
        assertEquals(true, throwIndex >= 0)
        assertEquals(throwIndex - 1, handlerCallIndex)
    }

    @Test
    fun asmInjectThrowSliceLimitsThrowsAfterFrom() {
        AsmRegistry.register(ThrowInstructionSliceMixin::class.java)

        val transformed =
            AsmProcessor().transform("SliceThrowInstructionTarget", sliceThrowInstructionTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val method = classNode.methods.single { it.name == "failSelected" }
        val instructions = method.instructions.toArray()
        val mixinOwner = org.objectweb.asm.Type.getInternalName(ThrowInstructionSliceMixin::class.java)
        val handlerCallIndexes = instructions.mapIndexedNotNull { index, insn ->
            if (insn is org.objectweb.asm.tree.MethodInsnNode && insn.owner == mixinOwner && insn.name == "inject") {
                index
            } else {
                null
            }
        }
        val throwIndexes = instructions.mapIndexedNotNull { index, insn ->
            if (insn.opcode == Opcodes.ATHROW) {
                index
            } else {
                null
            }
        }
        val boundaryIndexes = instructions.mapIndexedNotNull { index, insn ->
            if (insn is org.objectweb.asm.tree.MethodInsnNode &&
                insn.owner == "java/lang/String" &&
                insn.name == "toString"
            ) {
                index
            } else {
                null
            }
        }

        assertEquals(1, boundaryIndexes.size)
        val inSliceThrowIndexes = throwIndexes.filter { it > boundaryIndexes.single() }
        assertEquals(1, inSliceThrowIndexes.size)
        assertEquals(1, handlerCallIndexes.size)
        assertEquals(true, handlerCallIndexes.single() > boundaryIndexes.single())
        assertEquals(inSliceThrowIndexes.single() - 1, handlerCallIndexes.single())
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

    @AsmMixin("RedirectTarget")
    object ObjectInstanceRedirectMixin {
        @Redirect(
            method = "call()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.trim()Ljava/lang/String;",
            ),
        )
        fun redirect(value: String): String = "object-$value"
    }

    @AsmMixin("RedirectParamTarget")
    object RedirectWithTargetParamsMixin {
        @Redirect(
            method = "value(Ljava/lang/String;I)Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.trim()Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun redirect(
            value: String,
            suffix: String,
            count: Int,
        ): String = "${value.trim()}-$suffix$count"
    }

    @AsmMixin("StaticRedirectParamTarget")
    object StaticRedirectWithTargetParamsMixin {
        @Redirect(
            method = "value(Ljava/lang/String;I)Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/Integer.toString(I)Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun redirect(
            value: Int,
            suffix: String,
            count: Int,
        ): String = "$value-$suffix$count"
    }

    @AsmMixin("ConstructorModifyArgTarget")
    object ConstructorRedirectMixin {
        @Redirect(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/StringBuilder.<init>(Ljava/lang/String;)V",
            ),
        )
        @JvmStatic
        fun redirect(value: String): StringBuilder = StringBuilder("redirected-$value")
    }

    @AsmMixin("NewParamTarget")
    object ConstructorRedirectWithTargetParamsMixin {
        @Redirect(
            method = "create(Ljava/lang/String;I)Ljava/lang/StringBuilder;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/StringBuilder.<init>()V",
            ),
        )
        @JvmStatic
        fun redirect(
            prefix: String,
            count: Int,
        ): StringBuilder = StringBuilder("$prefix-$count")
    }

    @AsmMixin("ConstructorModifyArgTarget")
    object MismatchedConstructorRedirectMixin {
        @Redirect(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/StringBuilder.<init>(Ljava/lang/String;)V",
            ),
        )
        @JvmStatic
        fun redirect(value: String): String = value
    }

    @AsmMixin("RedirectParamTarget")
    object TooManyRedirectTargetParametersMixin {
        @Redirect(
            method = "value(Ljava/lang/String;I)Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.trim()Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun redirect(
            value: String,
            suffix: String,
            count: Int,
            unavailable: String,
        ): String = "$value$suffix$count$unavailable"
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

    @AsmMixin("CharReturnTarget")
    object CharReturnCallbackMixin {
        @AsmInject(method = "value()C", target = InjectionPoint.RETURN)
        @JvmStatic
        fun inject(callback: CallbackInfo) {
            callback.getReturnValue<Char>()
        }
    }

    @AsmMixin("MultiReturnTarget")
    object ReturnOrdinalMixin {
        @AsmInject(method = "value(Z)Ljava/lang/String;", target = InjectionPoint.RETURN, ordinal = 1)
        @JvmStatic
        fun inject() {
        }
    }

    @AsmMixin("MultiReturnTarget")
    object RequireThreeReturnInjectMixin {
        @AsmInject(method = "value(Z)Ljava/lang/String;", target = InjectionPoint.RETURN, require = 3)
        @JvmStatic
        fun inject() {
        }
    }

    @AsmMixin("MultiReturnTarget")
    object AllowOneReturnInjectMixin {
        @AsmInject(method = "value(Z)Ljava/lang/String;", target = InjectionPoint.RETURN, allow = 1)
        @JvmStatic
        fun inject() {
        }
    }

    @AsmMixin("MultiReturnTarget")
    object ExpectThreeReturnInjectMixin {
        @AsmInject(method = "value(Z)Ljava/lang/String;", target = InjectionPoint.RETURN, expect = 3)
        @JvmStatic
        fun inject() {
        }
    }

    @AsmMixin("MultiReturnTarget")
    object ModifyReturnValueOrdinalMixin {
        @ModifyReturnValue(method = "value(Z)Ljava/lang/String;", ordinal = 1)
        @JvmStatic
        fun modify(original: String): String = "modified-$original"
    }

    @AsmMixin("MultiReturnTarget")
    object RequireThreeModifyReturnValueMixin {
        @ModifyReturnValue(method = "value(Z)Ljava/lang/String;", require = 3)
        @JvmStatic
        fun modify(original: String): String = original
    }

    @AsmMixin("MultiReturnTarget")
    object AllowOneModifyReturnValueMixin {
        @ModifyReturnValue(method = "value(Z)Ljava/lang/String;", allow = 1)
        @JvmStatic
        fun modify(original: String): String = original
    }

    @AsmMixin("MultiReturnTarget")
    object ExpectThreeModifyReturnValueMixin {
        @ModifyReturnValue(method = "value(Z)Ljava/lang/String;", expect = 3)
        @JvmStatic
        fun modify(original: String): String = original
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

    @AsmMixin("RedirectTarget")
    object InvokeBeforeReturningHandlerMixin {
        @AsmInject(
            method = "call()Ljava/lang/String;",
            target = InjectionPoint.INVOKE,
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.trim()Ljava/lang/String;",
                shift = Shift.BEFORE,
            ),
        )
        @JvmStatic
        fun inject(): Int = 1
    }

    @AsmMixin("RedirectTarget")
    object InvokeAfterWideReturningHandlerMixin {
        @AsmInject(
            method = "call()Ljava/lang/String;",
            target = InjectionPoint.INVOKE,
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.trim()Ljava/lang/String;",
                shift = Shift.AFTER,
            ),
        )
        @JvmStatic
        fun inject(): Long = 1L
    }

    @AsmMixin("RedirectTarget")
    object InvokeAfterCallbackInfoMixin {
        @AsmInject(
            method = "call()Ljava/lang/String;",
            target = InjectionPoint.INVOKE,
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.trim()Ljava/lang/String;",
                shift = Shift.AFTER,
            ),
        )
        @JvmStatic
        fun inject(callback: CallbackInfo) {
            callback.isCancelled()
        }
    }

    @AsmMixin("StrictTarget")
    object HeadWideReturningHandlerMixin {
        @AsmInject(method = "keep()V", target = InjectionPoint.HEAD)
        @JvmStatic
        fun inject(): Long = 1L
    }

    @AsmMixin("StrictTarget")
    object TailWideReturningHandlerMixin {
        @AsmInject(method = "keep()V", target = InjectionPoint.TAIL)
        @JvmStatic
        fun inject(): Double = 1.0
    }

    @AsmMixin("StrictTarget")
    object ReturnWideReturningHandlerMixin {
        @AsmInject(method = "keep()V", target = InjectionPoint.RETURN)
        @JvmStatic
        fun inject(): Long = 1L
    }

    @AsmMixin("StaticInvokeArgTarget")
    object InvokeBeforeStaticCallArgumentMixin {
        @AsmInject(
            method = "value()Ljava/lang/String;",
            target = InjectionPoint.INVOKE,
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/Integer.toString(I)Ljava/lang/String;",
                shift = Shift.BEFORE,
            ),
        )
        @JvmStatic
        fun inject(value: Int) {
            value.toString()
        }
    }

    @AsmMixin("StaticInvokeArgTarget")
    object InvokeAfterStaticCallArgumentMixin {
        @AsmInject(
            method = "value()Ljava/lang/String;",
            target = InjectionPoint.INVOKE,
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/Integer.toString(I)Ljava/lang/String;",
                shift = Shift.AFTER,
            ),
        )
        @JvmStatic
        fun inject(value: Int) {
            if (value != 42) {
                throw IllegalStateException("Unexpected call argument: $value")
            }
        }
    }

    @AsmMixin("StaticRedirectParamTarget")
    object InvokeBeforeWithTargetParamsMixin {
        @AsmInject(
            method = "value(Ljava/lang/String;I)Ljava/lang/String;",
            target = InjectionPoint.INVOKE,
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/Integer.toString(I)Ljava/lang/String;",
                shift = Shift.BEFORE,
            ),
        )
        @JvmStatic
        fun inject(
            value: Int,
            suffix: String,
            count: Int,
        ) {
            if (value != 42 || suffix != "suffix" || count != 4) {
                throw IllegalStateException("Unexpected invoke arguments: $value, $suffix, $count")
            }
        }
    }

    @AsmMixin("StaticRedirectParamTarget")
    object InvokeAfterWithCallbackAndTargetParamsMixin {
        @AsmInject(
            method = "value(Ljava/lang/String;I)Ljava/lang/String;",
            target = InjectionPoint.INVOKE,
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/Integer.toString(I)Ljava/lang/String;",
                shift = Shift.AFTER,
            ),
        )
        @JvmStatic
        fun inject(
            callback: CallbackInfo,
            value: Int,
            suffix: String,
            count: Int,
        ) {
            if (callback.isCancelled() || value != 42 || suffix != "suffix" || count != 5) {
                throw IllegalStateException("Unexpected invoke arguments: $value, $suffix, $count")
            }
        }
    }

    @AsmMixin("RedirectParamTarget")
    object InvokeWithTargetParamsWithoutCallArgumentsMixin {
        @AsmInject(
            method = "value(Ljava/lang/String;I)Ljava/lang/String;",
            target = InjectionPoint.INVOKE,
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.trim()Ljava/lang/String;",
                shift = Shift.BEFORE,
            ),
        )
        @JvmStatic
        fun inject(
            suffix: String,
            count: Int,
        ) {
            if (suffix != "suffix" || count != 6) {
                throw IllegalStateException("Unexpected target arguments: $suffix, $count")
            }
        }
    }

    @AsmMixin("StaticRedirectParamTarget")
    object TooManyInvokeTargetParametersMixin {
        @AsmInject(
            method = "value(Ljava/lang/String;I)Ljava/lang/String;",
            target = InjectionPoint.INVOKE,
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/Integer.toString(I)Ljava/lang/String;",
                shift = Shift.BEFORE,
            ),
        )
        @JvmStatic
        fun inject(
            value: Int,
            suffix: String,
            count: Int,
            unavailable: String,
        ) {
            value.toString()
            suffix.length
            count.toString()
            unavailable.length
        }
    }

    @AsmMixin("WideInvokeArgTarget")
    object InvokeBeforeWideStaticCallArgumentMixin {
        @AsmInject(
            method = "value()Ljava/lang/String;",
            target = InjectionPoint.INVOKE,
            at = At(
                value = InjectionPoint.INVOKE,
                target = "WideInvokeArgTarget.combine(DI)Ljava/lang/String;",
                shift = Shift.BEFORE,
            ),
        )
        @JvmStatic
        fun inject(
            value: Double,
            index: Int,
        ) {
            if (value != 1.5 || index != 7) {
                throw IllegalStateException("Unexpected call arguments: $value, $index")
            }
        }
    }

    @AsmMixin("ArgTarget")
    object TooManyModifyArgParametersMixin {
        @ModifyArg(method = "echo(Ljava/lang/String;)Ljava/lang/String;", index = 0)
        @JvmStatic
        fun modify(
            original: String,
            targetValue: String,
            unavailable: String,
        ): String = "$original$targetValue$unavailable"
    }

    @AsmMixin("ArgTarget")
    object ModifyArgWithTargetParamsMixin {
        @ModifyArg(method = "echo(Ljava/lang/String;)Ljava/lang/String;", index = 0)
        @JvmStatic
        fun modify(
            original: String,
            targetValue: String,
        ): String = "$original-$targetValue"
    }

    @AsmMixin("StaticArgTarget")
    object StaticModifyArgWithTargetParamsMixin {
        @ModifyArg(method = "echo(Ljava/lang/String;)Ljava/lang/String;", index = 0)
        @JvmStatic
        fun modify(
            original: String,
            targetValue: String,
        ): String = "$original-$targetValue-static"
    }

    @AsmMixin("InvokeModifyArgTarget")
    object InvokeModifyArgMixin {
        @ModifyArg(
            method = "value()Ljava/lang/String;",
            index = 0,
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun modify(original: String): String = "modified"
    }

    @AsmMixin("InvokeModifyArgParamTarget")
    object InvokeModifyArgWithTargetParamsMixin {
        @ModifyArg(
            method = "value(Ljava/lang/String;I)Ljava/lang/String;",
            index = 0,
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun modify(
            original: String,
            suffix: String,
            count: Int,
        ): String = "$original-$suffix$count"
    }

    @AsmMixin("MultiInvokeModifyArgTarget")
    object InvokeModifyArgOrdinalMixin {
        @ModifyArg(
            method = "value()Ljava/lang/String;",
            index = 0,
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
            ),
            ordinal = 1,
        )
        @JvmStatic
        fun modify(original: String): String = "modified"
    }

    @AsmMixin("ModifyArgContractTarget")
    object RequireThreeModifyArgMixin {
        @ModifyArg(
            method = "value()Ljava/lang/String;",
            index = 0,
            at = At(
                value = InjectionPoint.INVOKE,
                target = "ModifyArgContractTarget.combine(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
            ),
            require = 3,
        )
        @JvmStatic
        fun modify(original: String): String = original
    }

    @AsmMixin("ModifyArgContractTarget")
    object AllowOneModifyArgMixin {
        @ModifyArg(
            method = "value()Ljava/lang/String;",
            index = 0,
            at = At(
                value = InjectionPoint.INVOKE,
                target = "ModifyArgContractTarget.combine(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
            ),
            allow = 1,
        )
        @JvmStatic
        fun modify(original: String): String = original
    }

    @AsmMixin("ModifyArgContractTarget")
    object ExpectThreeModifyArgMixin {
        @ModifyArg(
            method = "value()Ljava/lang/String;",
            index = 0,
            at = At(
                value = InjectionPoint.INVOKE,
                target = "ModifyArgContractTarget.combine(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
            ),
            expect = 3,
        )
        @JvmStatic
        fun modify(original: String): String = original
    }

    @AsmMixin("SliceInvokeModifyArgTarget")
    object InvokeModifyArgSliceMixin {
        @ModifyArg(
            method = "value()Ljava/lang/String;",
            index = 0,
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
            ),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
                to = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
            ),
        )
        @JvmStatic
        fun modify(original: String): String = "modified"
    }

    @AsmMixin("ConstructorModifyArgTarget")
    object ConstructorModifyArgMixin {
        @ModifyArg(
            method = "value()Ljava/lang/String;",
            index = 0,
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/StringBuilder.<init>(Ljava/lang/String;)V",
            ),
        )
        @JvmStatic
        fun modify(original: String): String = "changed"
    }

    @AsmMixin("ModifyArgsTarget")
    object ModifyArgsReplaceMixin {
        @ModifyArgs(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.replace(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun modify(args: Args) {
            args.set(0, "raw")
            args.set(1, "changed")
        }
    }

    @AsmMixin("ModifyArgsParamTarget")
    object ModifyArgsWithTargetParamsMixin {
        @ModifyArgs(
            method = "value(Ljava/lang/String;I)Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "ModifyArgsParamTarget.join(Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun modify(
            args: Args,
            suffix: String,
            count: Int,
        ) {
            args.set(0, "${args.get<String>(0)}-$suffix")
            args.set(1, "right")
            args.set(2, count)
        }
    }

    @AsmMixin("MultiModifyArgsTarget")
    object RequireThreeModifyArgsMixin {
        @ModifyArgs(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.replace(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;",
            ),
            require = 3,
        )
        @JvmStatic
        fun modify(args: Args) {
            args.get<CharSequence>(0)
        }
    }

    @AsmMixin("MultiModifyArgsTarget")
    object AllowOneModifyArgsMixin {
        @ModifyArgs(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.replace(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;",
            ),
            allow = 1,
        )
        @JvmStatic
        fun modify(args: Args) {
            args.get<CharSequence>(0)
        }
    }

    @AsmMixin("MultiModifyArgsTarget")
    object ExpectThreeModifyArgsMixin {
        @ModifyArgs(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.replace(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;",
            ),
            expect = 3,
        )
        @JvmStatic
        fun modify(args: Args) {
            args.get<CharSequence>(0)
        }
    }

    @AsmMixin("MultiModifyArgsTarget")
    object ModifyArgsOrdinalMixin {
        @ModifyArgs(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.replace(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;",
            ),
            ordinal = 1,
        )
        @JvmStatic
        fun modify(args: Args) {
            args.set(0, "raw")
            args.set(1, "changed")
        }
    }

    @AsmMixin("SliceModifyArgsTarget")
    object ModifyArgsSliceMixin {
        @ModifyArgs(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.replace(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;",
            ),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
                to = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
            ),
        )
        @JvmStatic
        fun modify(args: Args) {
            args.set(0, "raw")
            args.set(1, "changed")
        }
    }

    @AsmMixin("ModifyArgsTarget")
    object MismatchedModifyArgsParametersMixin {
        @ModifyArgs(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.replace(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun modify(args: String) {
            args.length
        }
    }

    @AsmMixin("WrapConditionStaticTarget")
    object WrapConditionStaticDenyMixin {
        @WrapWithCondition(
            method = "run()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "WrapConditionStaticTarget.record(Ljava/lang/String;)V",
            ),
        )
        @JvmStatic
        fun shouldRun(value: String): Boolean {
            value.length
            return false
        }
    }

    @AsmMixin("WrapConditionStaticTarget")
    object WrapConditionStaticAllowMixin {
        @WrapWithCondition(
            method = "run()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "WrapConditionStaticTarget.record(Ljava/lang/String;)V",
            ),
        )
        @JvmStatic
        fun shouldRun(value: String): Boolean = value == "raw"
    }

    @AsmMixin("WrapConditionInstanceTarget")
    object WrapConditionInstanceCallMixin {
        @WrapWithCondition(
            method = "run()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "WrapConditionInstanceTarget.record(Ljava/lang/String;I)V",
            ),
        )
        @JvmStatic
        fun shouldRun(
            target: Any,
            value: String,
            count: Int,
        ): Boolean {
            target.hashCode()
            return value == "raw" && count == 3
        }
    }

    @AsmMixin("WrapConditionParamTarget")
    object WrapConditionWithTargetParamsMixin {
        @WrapWithCondition(
            method = "run(Ljava/lang/String;I)Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "WrapConditionParamTarget.record(Ljava/lang/String;)V",
            ),
        )
        @JvmStatic
        fun shouldRun(
            value: String,
            suffix: String,
            count: Int,
        ): Boolean = value == "raw" && suffix == "suffix" && count == 7
    }

    @AsmMixin("MultiWrapConditionTarget")
    object WrapConditionOrdinalMixin {
        @WrapWithCondition(
            method = "run()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "MultiWrapConditionTarget.record(Ljava/lang/String;)V",
            ),
            ordinal = 1,
        )
        @JvmStatic
        fun shouldRun(value: String): Boolean {
            value.length
            return false
        }
    }

    @AsmMixin("MultiWrapConditionTarget")
    object RequireThreeWrapConditionMixin {
        @WrapWithCondition(
            method = "run()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "MultiWrapConditionTarget.record(Ljava/lang/String;)V",
            ),
            require = 3,
        )
        @JvmStatic
        fun shouldRun(value: String): Boolean {
            value.length
            return true
        }
    }

    @AsmMixin("MultiWrapConditionTarget")
    object AllowOneWrapConditionMixin {
        @WrapWithCondition(
            method = "run()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "MultiWrapConditionTarget.record(Ljava/lang/String;)V",
            ),
            allow = 1,
        )
        @JvmStatic
        fun shouldRun(value: String): Boolean {
            value.length
            return true
        }
    }

    @AsmMixin("MultiWrapConditionTarget")
    object ExpectThreeWrapConditionMixin {
        @WrapWithCondition(
            method = "run()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "MultiWrapConditionTarget.record(Ljava/lang/String;)V",
            ),
            expect = 3,
        )
        @JvmStatic
        fun shouldRun(value: String): Boolean {
            value.length
            return true
        }
    }

    @AsmMixin("SliceWrapConditionTarget")
    object WrapConditionSliceMixin {
        @WrapWithCondition(
            method = "run()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "SliceWrapConditionTarget.record(Ljava/lang/String;)V",
            ),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
                to = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
            ),
        )
        @JvmStatic
        fun shouldRun(value: String): Boolean {
            value.length
            return false
        }
    }

    @AsmMixin("ExpressionValueTarget")
    object WrapConditionNonVoidCallMixin {
        @WrapWithCondition(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.trim()Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun shouldRun(target: String): Boolean {
            target.length
            return false
        }
    }

    @AsmMixin("WrapConditionStaticTarget")
    object WrapConditionNonBooleanHandlerMixin {
        @WrapWithCondition(
            method = "run()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "WrapConditionStaticTarget.record(Ljava/lang/String;)V",
            ),
        )
        @JvmStatic
        fun shouldRun(value: String): String = value
    }

    @AsmMixin("FieldPointTarget")
    object WrapConditionFieldAssignDenyMixin {
        @WrapWithCondition(
            method = "writeName(Ljava/lang/String;)V",
            at = At(
                value = InjectionPoint.FIELD_ASSIGN,
                target = "FieldPointTarget.name:Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun shouldWrite(
            target: Any,
            value: String,
        ): Boolean {
            target.hashCode()
            value.length
            return false
        }
    }

    @AsmMixin("FieldPointTarget")
    object WrapConditionFieldAssignAllowMixin {
        @WrapWithCondition(
            method = "writeName(Ljava/lang/String;)V",
            at = At(
                value = InjectionPoint.FIELD_ASSIGN,
                target = "FieldPointTarget.name:Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun shouldWrite(
            target: Any,
            value: String,
        ): Boolean {
            target.hashCode()
            return value == "allowed"
        }
    }

    @AsmMixin("StaticFieldPointTarget")
    object WrapConditionStaticFieldAssignDenyMixin {
        @WrapWithCondition(
            method = "writeName(Ljava/lang/String;)V",
            at = At(
                value = InjectionPoint.FIELD_ASSIGN,
                target = "StaticFieldPointTarget.name:Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun shouldWrite(value: String): Boolean {
            value.length
            return false
        }
    }

    @AsmMixin("FieldParamTarget")
    object WrapConditionFieldAssignWithTargetParamsMixin {
        @WrapWithCondition(
            method = "writeName(Ljava/lang/String;Ljava/lang/String;I)V",
            at = At(
                value = InjectionPoint.FIELD_ASSIGN,
                target = "FieldParamTarget.name:Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun shouldWrite(
            target: Any,
            value: String,
            targetValue: String,
            suffix: String,
            count: Int,
        ): Boolean {
            target.hashCode()
            return value == targetValue && suffix == "suffix" && count == 5
        }
    }

    @AsmMixin("FieldAssignOrdinalTarget")
    object WrapConditionFieldAssignOrdinalMixin {
        @WrapWithCondition(
            method = "writeBoth(Ljava/lang/String;Ljava/lang/String;)V",
            at = At(
                value = InjectionPoint.FIELD_ASSIGN,
                target = "FieldAssignOrdinalTarget.name:Ljava/lang/String;",
            ),
            ordinal = 1,
        )
        @JvmStatic
        fun shouldWrite(
            target: Any,
            value: String,
        ): Boolean {
            target.hashCode()
            value.length
            return false
        }
    }

    @AsmMixin("FieldPointTarget")
    object WrapConditionFieldAssignMismatchedParametersMixin {
        @WrapWithCondition(
            method = "writeName(Ljava/lang/String;)V",
            at = At(
                value = InjectionPoint.FIELD_ASSIGN,
                target = "FieldPointTarget.name:Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun shouldWrite(
            target: Int,
            value: String,
        ): Boolean = target > 0 && value.isNotEmpty()
    }

    @AsmMixin("ArrayAccessTarget")
    object WrapConditionArrayWriteDenyMixin {
        @WrapWithCondition(
            method = "writeName(ILjava/lang/String;)V",
            at = At(
                value = InjectionPoint.FIELD_ASSIGN,
                target = "ArrayAccessTarget.names:[Ljava/lang/String;",
                args = ["array=set"],
            ),
        )
        @JvmStatic
        fun shouldWrite(
            array: Array<String>,
            index: Int,
            value: String,
        ): Boolean {
            array[index].length
            value.length
            return false
        }
    }

    @AsmMixin("ArrayAccessTarget")
    object WrapConditionArrayWriteAllowMixin {
        @WrapWithCondition(
            method = "writeName(ILjava/lang/String;)V",
            at = At(
                value = InjectionPoint.FIELD_ASSIGN,
                target = "ArrayAccessTarget.names:[Ljava/lang/String;",
                args = ["array=set"],
            ),
        )
        @JvmStatic
        fun shouldWrite(
            array: Array<String>,
            index: Int,
            value: String,
        ): Boolean = array[index] == "raw" && value == "allowed"
    }

    @AsmMixin("PrimitiveArrayAccessTarget")
    object WrapConditionPrimitiveArrayWriteDenyMixin {
        @WrapWithCondition(
            method = "writeScore(II)V",
            at = At(
                value = InjectionPoint.FIELD_ASSIGN,
                target = "PrimitiveArrayAccessTarget.scores:[I",
                args = ["array=set"],
            ),
        )
        @JvmStatic
        fun shouldWrite(
            array: IntArray,
            index: Int,
            value: Int,
        ): Boolean {
            array[index].toString()
            value.toString()
            return false
        }
    }

    @AsmMixin("ArrayParamTarget")
    object WrapConditionArrayWriteWithTargetParamsMixin {
        @WrapWithCondition(
            method = "writeName(ILjava/lang/String;Ljava/lang/String;)V",
            at = At(
                value = InjectionPoint.FIELD_ASSIGN,
                target = "ArrayParamTarget.names:[Ljava/lang/String;",
                args = ["array=set"],
            ),
        )
        @JvmStatic
        fun shouldWrite(
            array: Array<String>,
            index: Int,
            value: String,
            targetIndex: Int,
            targetValue: String,
            suffix: String,
        ): Boolean =
            array[index] == "raw" && index == targetIndex && value == targetValue && suffix == "suffix"
    }

    @AsmMixin("ArrayAccessTarget")
    object WrapConditionArrayWriteMismatchedParametersMixin {
        @WrapWithCondition(
            method = "writeName(ILjava/lang/String;)V",
            at = At(
                value = InjectionPoint.FIELD_ASSIGN,
                target = "ArrayAccessTarget.names:[Ljava/lang/String;",
                args = ["array=set"],
            ),
        )
        @JvmStatic
        fun shouldWrite(
            array: Array<String>,
            index: String,
            value: String,
        ): Boolean = array[index.length] == value
    }

    @AsmMixin("ExpressionValueTarget")
    object ModifyExpressionValueTrimMixin {
        @ModifyExpressionValue(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.trim()Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun modify(original: String): String = "$original-changed"
    }

    @AsmMixin("ExpressionValueParamTarget")
    object ModifyExpressionValueWithTargetParamsMixin {
        @ModifyExpressionValue(
            method = "value(Ljava/lang/String;I)Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.trim()Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun modify(
            original: String,
            prefix: String,
            count: Int,
        ): String = "$prefix-$original-$count"
    }

    @AsmMixin("MultiExpressionValueTarget")
    object ModifyExpressionValueOrdinalMixin {
        @ModifyExpressionValue(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.trim()Ljava/lang/String;",
            ),
            ordinal = 1,
        )
        @JvmStatic
        fun modify(original: String): String = "$original-changed"
    }

    @AsmMixin("MultiExpressionValueTarget")
    object RequireThreeModifyExpressionValueMixin {
        @ModifyExpressionValue(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.trim()Ljava/lang/String;",
            ),
            require = 3,
        )
        @JvmStatic
        fun modify(original: String): String = original
    }

    @AsmMixin("MultiExpressionValueTarget")
    object AllowOneModifyExpressionValueMixin {
        @ModifyExpressionValue(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.trim()Ljava/lang/String;",
            ),
            allow = 1,
        )
        @JvmStatic
        fun modify(original: String): String = original
    }

    @AsmMixin("MultiExpressionValueTarget")
    object ExpectThreeModifyExpressionValueMixin {
        @ModifyExpressionValue(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.trim()Ljava/lang/String;",
            ),
            expect = 3,
        )
        @JvmStatic
        fun modify(original: String): String = original
    }

    @AsmMixin("SliceExpressionValueTarget")
    object ModifyExpressionValueSliceMixin {
        @ModifyExpressionValue(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.trim()Ljava/lang/String;",
            ),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
                to = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
            ),
        )
        @JvmStatic
        fun modify(original: String): String = "$original-changed"
    }

    @AsmMixin("SliceFieldReadTarget")
    object ModifyExpressionValueFieldSliceMixin {
        @ModifyExpressionValue(
            method = "readSelected()Ljava/lang/String;",
            at = At(value = InjectionPoint.FIELD, target = "SliceFieldReadTarget.name:Ljava/lang/String;"),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
                to = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun modify(original: String): String = "$original-field-slice"
    }

    @AsmMixin("SliceNewExpressionValueTarget")
    object ModifyExpressionValueNewSliceMixin {
        @ModifyExpressionValue(
            method = "createSelected()Ljava/lang/StringBuilder;",
            at = At(value = InjectionPoint.NEW, target = "java/lang/StringBuilder"),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
                to = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun modify(original: StringBuilder): StringBuilder {
            original.length
            return StringBuilder("changed")
        }
    }

    @AsmMixin("SliceCastInstructionTarget")
    object ModifyExpressionValueCastSliceMixin {
        @ModifyExpressionValue(
            method = "castSelected(Ljava/lang/Object;)Ljava/lang/String;",
            at = At(value = InjectionPoint.CAST, target = "java/lang/String"),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
                to = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun modify(original: String): String = "$original-cast-slice"
    }

    @AsmMixin("SliceInstanceofExpressionValueTarget")
    object ModifyExpressionValueInstanceofSliceMixin {
        @ModifyExpressionValue(
            method = "isSelected(Ljava/lang/Object;)Z",
            at = At(value = InjectionPoint.INSTANCEOF, target = "java/lang/String"),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
                to = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun modify(original: Boolean): Boolean = !original
    }

    @AsmMixin("ExpressionValueTarget")
    object MismatchedModifyExpressionValueMixin {
        @ModifyExpressionValue(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.trim()Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun modify(original: Int): Int = original + 1
    }

    @AsmMixin("FieldPointTarget")
    object ModifyExpressionValueFieldReadMixin {
        @ModifyExpressionValue(
            method = "readName()Ljava/lang/String;",
            at = At(value = InjectionPoint.FIELD, target = "FieldPointTarget.name:Ljava/lang/String;"),
        )
        @JvmStatic
        fun modify(original: String): String = "$original-field"
    }

    @AsmMixin("FieldPointTarget")
    object ModifyExpressionValueFieldNameOnlyMixin {
        @ModifyExpressionValue(
            method = "readName()Ljava/lang/String;",
            at = At(value = InjectionPoint.FIELD, target = "name"),
        )
        @JvmStatic
        fun modify(original: String): String = "$original-name-only-field"
    }

    @AsmMixin("StaticFieldPointTarget")
    object ModifyExpressionValueStaticFieldReadMixin {
        @ModifyExpressionValue(
            method = "readName()Ljava/lang/String;",
            at = At(value = InjectionPoint.FIELD, target = "StaticFieldPointTarget.name:Ljava/lang/String;"),
        )
        @JvmStatic
        fun modify(original: String): String = "$original-static-field"
    }

    @AsmMixin("FieldParamTarget")
    object ModifyExpressionValueFieldWithTargetParamsMixin {
        @ModifyExpressionValue(
            method = "readName(Ljava/lang/String;I)Ljava/lang/String;",
            at = At(value = InjectionPoint.FIELD, target = "FieldParamTarget.name:Ljava/lang/String;"),
        )
        @JvmStatic
        fun modify(
            original: String,
            suffix: String,
            count: Int,
        ): String = "$original-$suffix$count"
    }

    @AsmMixin("MultiFieldReadTarget")
    object ModifyExpressionValueFieldOrdinalMixin {
        @ModifyExpressionValue(
            method = "readTwice()Ljava/lang/String;",
            at = At(value = InjectionPoint.FIELD, target = "MultiFieldReadTarget.name:Ljava/lang/String;"),
            ordinal = 1,
        )
        @JvmStatic
        fun modify(original: String): String = "$original-changed"
    }

    @AsmMixin("FieldPointTarget")
    object MismatchedModifyExpressionValueFieldMixin {
        @ModifyExpressionValue(
            method = "readName()Ljava/lang/String;",
            at = At(value = InjectionPoint.FIELD, target = "FieldPointTarget.name:Ljava/lang/String;"),
        )
        @JvmStatic
        fun modify(original: Int): Int = original + 1
    }

    @AsmMixin("ArrayAccessTarget")
    object ModifyExpressionValueArrayReadMixin {
        @ModifyExpressionValue(
            method = "readName(I)Ljava/lang/String;",
            at = At(
                value = InjectionPoint.FIELD,
                target = "ArrayAccessTarget.names:[Ljava/lang/String;",
                args = ["array=get"],
            ),
        )
        @JvmStatic
        fun modify(original: String): String = "$original-array"
    }

    @AsmMixin("PrimitiveArrayAccessTarget")
    object ModifyExpressionValuePrimitiveArrayReadMixin {
        @ModifyExpressionValue(
            method = "readScore(I)I",
            at = At(
                value = InjectionPoint.FIELD,
                target = "PrimitiveArrayAccessTarget.scores:[I",
                args = ["array=get"],
            ),
        )
        @JvmStatic
        fun modify(original: Int): Int = original + 2
    }

    @AsmMixin("ArrayParamTarget")
    object ModifyExpressionValueArrayReadWithTargetParamsMixin {
        @ModifyExpressionValue(
            method = "readName(ILjava/lang/String;)Ljava/lang/String;",
            at = At(
                value = InjectionPoint.FIELD,
                target = "ArrayParamTarget.names:[Ljava/lang/String;",
                args = ["array=get"],
            ),
        )
        @JvmStatic
        fun modify(
            original: String,
            index: Int,
            suffix: String,
        ): String {
            if (index != 0) {
                throw IllegalStateException("Unexpected index: $index")
            }
            return "$original-$suffix"
        }
    }

    @AsmMixin("ArrayAccessTarget")
    object ModifyExpressionValueArrayLengthMixin {
        @ModifyExpressionValue(
            method = "nameCount()I",
            at = At(
                value = InjectionPoint.FIELD,
                target = "ArrayAccessTarget.names:[Ljava/lang/String;",
                args = ["array=length"],
            ),
        )
        @JvmStatic
        fun modify(original: Int): Int = original + 3
    }

    @AsmMixin("ArrayAccessTarget")
    object ModifyExpressionValueArrayLengthWithTargetParamsMixin {
        @ModifyExpressionValue(
            method = "nameCount(I)I",
            at = At(
                value = InjectionPoint.FIELD,
                target = "ArrayAccessTarget.names:[Ljava/lang/String;",
                args = ["array=length"],
            ),
        )
        @JvmStatic
        fun modify(
            original: Int,
            bonus: Int,
        ): Int = original + bonus
    }

    @AsmMixin("SliceArrayExpressionValueTarget")
    object ModifyExpressionValueArrayReadSliceMixin {
        @ModifyExpressionValue(
            method = "readSelected(I)Ljava/lang/String;",
            at = At(
                value = InjectionPoint.FIELD,
                target = "SliceArrayExpressionValueTarget.names:[Ljava/lang/String;",
                args = ["array=get"],
            ),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
                to = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun modify(original: String): String = "$original-array-slice"
    }

    @AsmMixin("SliceArrayExpressionValueTarget")
    object ModifyExpressionValueArrayLengthSliceMixin {
        @ModifyExpressionValue(
            method = "countSelected()I",
            at = At(
                value = InjectionPoint.FIELD,
                target = "SliceArrayExpressionValueTarget.names:[Ljava/lang/String;",
                args = ["array=length"],
            ),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
                to = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun modify(original: Int): Int = original + 3
    }

    @AsmMixin("ArrayAccessTarget")
    object MismatchedModifyExpressionValueArrayLengthMixin {
        @ModifyExpressionValue(
            method = "nameCount()I",
            at = At(
                value = InjectionPoint.FIELD,
                target = "ArrayAccessTarget.names:[Ljava/lang/String;",
                args = ["array=length"],
            ),
        )
        @JvmStatic
        fun modify(original: String): String = original
    }

    @AsmMixin("ArrayAccessTarget")
    object MismatchedModifyExpressionValueArrayReadMixin {
        @ModifyExpressionValue(
            method = "readName(I)Ljava/lang/String;",
            at = At(
                value = InjectionPoint.FIELD,
                target = "ArrayAccessTarget.names:[Ljava/lang/String;",
                args = ["array=get"],
            ),
        )
        @JvmStatic
        fun modify(original: Int): Int = original + 1
    }

    @AsmMixin("NewInstructionTarget")
    object ModifyExpressionValueNewMixin {
        @ModifyExpressionValue(
            method = "create()Ljava/lang/StringBuilder;",
            at = At(value = InjectionPoint.NEW, target = "java/lang/StringBuilder"),
        )
        @JvmStatic
        fun modify(original: StringBuilder): StringBuilder {
            original.length
            return StringBuilder("changed")
        }
    }

    @AsmMixin("NewParamTarget")
    object ModifyExpressionValueNewWithTargetParamsMixin {
        @ModifyExpressionValue(
            method = "create(Ljava/lang/String;I)Ljava/lang/StringBuilder;",
            at = At(value = InjectionPoint.NEW, target = "java/lang/StringBuilder"),
        )
        @JvmStatic
        fun modify(
            original: StringBuilder,
            prefix: String,
            count: Int,
        ): StringBuilder {
            original.length
            return StringBuilder("$prefix-$count")
        }
    }

    @AsmMixin("MultiNewTarget")
    object ModifyExpressionValueNewOrdinalMixin {
        @ModifyExpressionValue(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.NEW, target = "java/lang/StringBuilder"),
            ordinal = 1,
        )
        @JvmStatic
        fun modify(original: StringBuilder): StringBuilder {
            original.length
            return StringBuilder("changed")
        }
    }

    @AsmMixin("NewInstructionTarget")
    object MismatchedModifyExpressionValueNewMixin {
        @ModifyExpressionValue(
            method = "create()Ljava/lang/StringBuilder;",
            at = At(value = InjectionPoint.NEW, target = "java/lang/StringBuilder"),
        )
        @JvmStatic
        fun modify(original: String): String = original
    }

    @AsmMixin("CastInstructionTarget")
    object ModifyExpressionValueCastMixin {
        @ModifyExpressionValue(
            method = "cast(Ljava/lang/Object;)Ljava/lang/String;",
            at = At(value = InjectionPoint.CAST, target = "java/lang/String"),
        )
        @JvmStatic
        fun modify(original: String): String = "$original-cast"
    }

    @AsmMixin("CastInstructionTarget")
    object ModifyExpressionValueCastWithTargetParamsMixin {
        @ModifyExpressionValue(
            method = "cast(Ljava/lang/Object;)Ljava/lang/String;",
            at = At(value = InjectionPoint.CAST, target = "java/lang/String"),
        )
        @JvmStatic
        fun modify(
            original: String,
            input: Any,
        ): String = "$original-$input"
    }

    @AsmMixin("CastInstructionTarget")
    object MismatchedModifyExpressionValueCastMixin {
        @ModifyExpressionValue(
            method = "cast(Ljava/lang/Object;)Ljava/lang/String;",
            at = At(value = InjectionPoint.CAST, target = "java/lang/String"),
        )
        @JvmStatic
        fun modify(original: Int): Int = original + 1
    }

    @AsmMixin("InstanceofTarget")
    object ModifyExpressionValueInstanceofMixin {
        @ModifyExpressionValue(
            method = "isString(Ljava/lang/Object;Z)Z",
            at = At(value = InjectionPoint.INSTANCEOF, target = "java/lang/String"),
        )
        @JvmStatic
        fun modify(
            original: Boolean,
            value: Any,
            force: Boolean,
        ): Boolean {
            value.hashCode()
            return original || force
        }
    }

    @AsmMixin("ModifyReceiverTarget")
    object ModifyReceiverConcatMixin {
        @ModifyReceiver(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun modify(original: String): String {
            original.length
            return "changed"
        }
    }

    @AsmMixin("ModifyReceiverParamTarget")
    object ModifyReceiverWithTargetParamsMixin {
        @ModifyReceiver(
            method = "value(Ljava/lang/String;I)Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun modify(
            original: String,
            prefix: String,
            count: Int,
        ): String {
            original.length
            return "$prefix$count"
        }
    }

    @AsmMixin("MultiModifyReceiverTarget")
    object ModifyReceiverOrdinalMixin {
        @ModifyReceiver(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
            ),
            ordinal = 1,
        )
        @JvmStatic
        fun modify(original: String): String {
            original.length
            return "changed"
        }
    }

    @AsmMixin("ModifyReceiverContractTarget")
    object RequireThreeModifyReceiverMixin {
        @ModifyReceiver(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
            ),
            require = 3,
        )
        @JvmStatic
        fun modify(original: String): String = original
    }

    @AsmMixin("ModifyReceiverContractTarget")
    object AllowOneModifyReceiverMixin {
        @ModifyReceiver(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
            ),
            allow = 1,
        )
        @JvmStatic
        fun modify(original: String): String = original
    }

    @AsmMixin("ModifyReceiverContractTarget")
    object ExpectThreeModifyReceiverMixin {
        @ModifyReceiver(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
            ),
            expect = 3,
        )
        @JvmStatic
        fun modify(original: String): String = original
    }

    @AsmMixin("SliceModifyReceiverTarget")
    object ModifyReceiverSliceMixin {
        @ModifyReceiver(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
            ),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
                to = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
            ),
        )
        @JvmStatic
        fun modify(original: String): String {
            original.length
            return "changed"
        }
    }

    @AsmMixin("SliceModifyReceiverFieldTarget")
    object ModifyReceiverFieldReadSliceMixin {
        @ModifyReceiver(
            method = "readSelected()Ljava/lang/String;",
            at = At(value = InjectionPoint.FIELD, target = "SliceModifyReceiverFieldTarget.value:Ljava/lang/String;"),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
                to = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun modify(original: Any): Any {
            val field = original.javaClass.getDeclaredField("replacement")
            field.isAccessible = true
            return field.get(original)
        }
    }

    @AsmMixin("SliceModifyReceiverFieldTarget")
    object ModifyReceiverFieldAssignSliceMixin {
        @ModifyReceiver(
            method = "writeSelected()Ljava/lang/String;",
            at = At(value = InjectionPoint.FIELD_ASSIGN, target = "SliceModifyReceiverFieldTarget.value:Ljava/lang/String;"),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
                to = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun modify(original: Any): Any {
            val field = original.javaClass.getDeclaredField("replacement")
            field.isAccessible = true
            return field.get(original)
        }
    }

    @AsmMixin("StaticInvokeArgTarget")
    object ModifyReceiverStaticCallMixin {
        @ModifyReceiver(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/Integer.toString(I)Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun modify(original: Any): Any = original
    }

    @AsmMixin("ModifyReceiverTarget")
    object MismatchedModifyReceiverMixin {
        @ModifyReceiver(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun modify(original: Int): Int = original + 1
    }

    @AsmMixin("FieldPointTarget")
    object ModifyReceiverFieldReadMixin {
        var replacement: Any? = null

        @ModifyReceiver(
            method = "readName()Ljava/lang/String;",
            at = At(value = InjectionPoint.FIELD, target = "FieldPointTarget.name:Ljava/lang/String;"),
        )
        @JvmStatic
        fun modify(original: Any): Any = replacement ?: original
    }

    @AsmMixin("FieldParamTarget")
    object ModifyReceiverFieldReadWithTargetParamsMixin {
        var replacement: Any? = null
        var lastTargetParams: String? = null

        @ModifyReceiver(
            method = "readName(Ljava/lang/String;I)Ljava/lang/String;",
            at = At(value = InjectionPoint.FIELD, target = "FieldParamTarget.name:Ljava/lang/String;"),
        )
        @JvmStatic
        fun modify(
            original: Any,
            prefix: String,
            count: Int,
        ): Any {
            lastTargetParams = "$prefix$count"
            return replacement ?: original
        }
    }

    @AsmMixin("FieldPointTarget")
    object ModifyReceiverFieldAssignMixin {
        var replacement: Any? = null

        @ModifyReceiver(
            method = "writeName(Ljava/lang/String;)V",
            at = At(value = InjectionPoint.FIELD_ASSIGN, target = "FieldPointTarget.name:Ljava/lang/String;"),
        )
        @JvmStatic
        fun modify(original: Any): Any = replacement ?: original
    }

    @AsmMixin("StaticFieldPointTarget")
    object ModifyReceiverStaticFieldReadMixin {
        @ModifyReceiver(
            method = "readName()Ljava/lang/String;",
            at = At(value = InjectionPoint.FIELD, target = "StaticFieldPointTarget.name:Ljava/lang/String;"),
        )
        @JvmStatic
        fun modify(original: Any): Any = original
    }

    @AsmMixin("ModifyReceiverTarget")
    object WrapOperationInstanceCallMixin {
        @WrapOperation(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun wrap(
            target: String,
            value: String,
            operation: Operation<String>,
        ): String {
            target.length
            value.length
            return operation.call(target, "-wrapped-call")
        }
    }

    @AsmMixin("ModifyReceiverTarget")
    object WrapOperationSkipCallMixin {
        @WrapOperation(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun wrap(
            target: String,
            value: String,
            operation: Operation<String>,
        ): String {
            target.length
            value.length
            operation.hashCode()
            return "skipped"
        }
    }

    @AsmMixin("ModifyReceiverTarget")
    object WrapOperationMultipleCallsMixin {
        @WrapOperation(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun wrap(
            target: String,
            value: String,
            operation: Operation<String>,
        ): String {
            target.length
            value.length
            return "${operation.call(target, "-first")}|${operation.call(target, "-second")}"
        }
    }

    @AsmMixin("StaticInvokeArgTarget")
    object WrapOperationStaticCallMixin {
        @WrapOperation(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/Integer.toString(I)Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun wrap(
            value: Int,
            operation: Operation<String>,
        ): String = "wrapped-${operation.call(value + 1)}"
    }

    @AsmMixin("WrapMethodStaticTarget")
    object WrapMethodStaticTargetMixin {
        @WrapMethod(method = "value(Ljava/lang/String;I)Ljava/lang/String;")
        @JvmStatic
        fun wrap(
            prefix: String,
            count: Int,
            operation: Operation<String>,
        ): String = "${operation.call(prefix.uppercase(), count + 1)}-wrapped"
    }

    @AsmMixin("WrapMethodInstanceTarget")
    object WrapMethodInstanceTargetMixin {
        @WrapMethod(method = "value(Ljava/lang/String;I)Ljava/lang/String;")
        @JvmStatic
        fun wrap(
            prefix: String,
            count: Int,
            operation: Operation<String>,
        ): String = "${operation.call(prefix.uppercase(), count + 1)}-wrapped"
    }

    @AsmMixin("ModifyReceiverParamTarget")
    object WrapOperationWithTargetParamsMixin {
        @WrapOperation(
            method = "value(Ljava/lang/String;I)Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun wrap(
            target: String,
            value: String,
            operation: Operation<String>,
            prefix: String,
            count: Int,
        ): String {
            target.length
            value.length
            return operation.call("$prefix$count", value)
        }
    }

    @AsmMixin("MultiModifyReceiverTarget")
    object WrapOperationOrdinalMixin {
        @WrapOperation(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
            ),
            ordinal = 1,
        )
        @JvmStatic
        fun wrap(
            target: String,
            value: String,
            operation: Operation<String>,
        ): String {
            target.length
            return operation.call("wrapped", value)
        }
    }

    @AsmMixin("ModifyReceiverContractTarget")
    object RequireThreeWrapOperationMixin {
        @WrapOperation(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
            ),
            require = 3,
        )
        @JvmStatic
        fun wrap(
            target: String,
            value: String,
            operation: Operation<String>,
        ): String = operation.call(target, value)
    }

    @AsmMixin("ModifyReceiverContractTarget")
    object AllowOneWrapOperationMixin {
        @WrapOperation(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
            ),
            allow = 1,
        )
        @JvmStatic
        fun wrap(
            target: String,
            value: String,
            operation: Operation<String>,
        ): String = operation.call(target, value)
    }

    @AsmMixin("ModifyReceiverContractTarget")
    object ExpectThreeWrapOperationMixin {
        @WrapOperation(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
            ),
            expect = 3,
        )
        @JvmStatic
        fun wrap(
            target: String,
            value: String,
            operation: Operation<String>,
        ): String = operation.call(target, value)
    }

    @AsmMixin("SliceWrapOperationTarget")
    object WrapOperationSliceMixin {
        @WrapOperation(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
            ),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
                to = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
            ),
        )
        @JvmStatic
        fun wrap(
            target: String,
            value: String,
            operation: Operation<String>,
        ): String {
            target.length
            value.length
            return operation.call(target, "-wrapped")
        }
    }

    @AsmMixin("ModifyReceiverTarget")
    object MismatchedWrapOperationMixin {
        @WrapOperation(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun wrap(
            target: String,
            value: String,
        ): String = target + value
    }

    @AsmMixin("ConstructorModifyArgTarget")
    object WrapOperationConstructorMixin {
        @WrapOperation(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/StringBuilder.<init>(Ljava/lang/String;)V",
            ),
        )
        @JvmStatic
        fun wrap(
            value: String,
            operation: Operation<StringBuilder>,
        ): StringBuilder = operation.call("wrapped-$value")
    }

    @AsmMixin("NewParamTarget")
    object WrapOperationConstructorWithTargetParamsMixin {
        @WrapOperation(
            method = "create(Ljava/lang/String;I)Ljava/lang/StringBuilder;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/StringBuilder.<init>()V",
            ),
        )
        @JvmStatic
        fun wrap(
            operation: Operation<StringBuilder>,
            prefix: String,
            count: Int,
        ): StringBuilder = operation.call().append(prefix).append("-").append(count)
    }

    @AsmMixin("ConstructorModifyArgTarget")
    object MismatchedWrapOperationConstructorMixin {
        @WrapOperation(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/StringBuilder.<init>(Ljava/lang/String;)V",
            ),
        )
        @JvmStatic
        fun wrap(value: String): StringBuilder = StringBuilder(value)
    }

    @AsmMixin("FieldPointTarget")
    object WrapOperationFieldReadMixin {
        @WrapOperation(
            method = "readName()Ljava/lang/String;",
            at = At(value = InjectionPoint.FIELD, target = "FieldPointTarget.name:Ljava/lang/String;"),
        )
        @JvmStatic
        fun wrap(
            target: Any,
            operation: Operation<String>,
        ): String = "wrapped-${operation.call(target)}"
    }

    @AsmMixin("PrimitiveFieldPointTarget")
    object WrapOperationPrimitiveFieldReadMixin {
        @WrapOperation(
            method = "readScore()I",
            at = At(value = InjectionPoint.FIELD, target = "PrimitiveFieldPointTarget.score:I"),
        )
        @JvmStatic
        fun wrap(
            target: Any,
            operation: Operation<Int>,
        ): Int = operation.call(target) + 2
    }

    @AsmMixin("StaticFieldPointTarget")
    object WrapOperationStaticFieldReadMixin {
        @WrapOperation(
            method = "readName()Ljava/lang/String;",
            at = At(value = InjectionPoint.FIELD, target = "StaticFieldPointTarget.name:Ljava/lang/String;"),
        )
        @JvmStatic
        fun wrap(operation: Operation<String>): String = "wrapped-static-${operation.call()}"
    }

    @AsmMixin("FieldParamTarget")
    object WrapOperationFieldWithTargetParamsMixin {
        @WrapOperation(
            method = "readName(Ljava/lang/String;I)Ljava/lang/String;",
            at = At(value = InjectionPoint.FIELD, target = "FieldParamTarget.name:Ljava/lang/String;"),
        )
        @JvmStatic
        fun wrap(
            target: Any,
            operation: Operation<String>,
            suffix: String,
            count: Int,
        ): String = "${operation.call(target)}-$suffix$count"
    }

    @AsmMixin("MultiFieldReadTarget")
    object WrapOperationFieldOrdinalMixin {
        @WrapOperation(
            method = "readTwice()Ljava/lang/String;",
            at = At(value = InjectionPoint.FIELD, target = "MultiFieldReadTarget.name:Ljava/lang/String;"),
            ordinal = 1,
        )
        @JvmStatic
        fun wrap(
            target: Any,
            operation: Operation<String>,
        ): String {
            target.hashCode()
            return "${operation.call(target)}-wrapped"
        }
    }

    @AsmMixin("FieldPointTarget")
    object MismatchedWrapOperationFieldReadMixin {
        @WrapOperation(
            method = "readName()Ljava/lang/String;",
            at = At(value = InjectionPoint.FIELD, target = "FieldPointTarget.name:Ljava/lang/String;"),
        )
        @JvmStatic
        fun wrap(target: Any): String = target.toString()
    }

    @AsmMixin("FieldPointTarget")
    object WrapOperationFieldAssignMixin {
        @WrapOperation(
            method = "writeName(Ljava/lang/String;)V",
            at = At(value = InjectionPoint.FIELD_ASSIGN, target = "FieldPointTarget.name:Ljava/lang/String;"),
        )
        @JvmStatic
        fun wrap(
            target: Any,
            value: String,
            operation: Operation<Unit>,
        ) {
            operation.call(target, "wrapped-$value")
        }
    }

    @AsmMixin("FieldPointTarget")
    object WrapOperationFieldAssignSkipMixin {
        @WrapOperation(
            method = "writeName(Ljava/lang/String;)V",
            at = At(value = InjectionPoint.FIELD_ASSIGN, target = "FieldPointTarget.name:Ljava/lang/String;"),
        )
        @JvmStatic
        fun wrap(
            target: Any,
            value: String,
            operation: Operation<Unit>,
        ) {
            target.hashCode()
            value.length
            operation.hashCode()
        }
    }

    @AsmMixin("PrimitiveFieldPointTarget")
    object WrapOperationPrimitiveFieldAssignMixin {
        @WrapOperation(
            method = "writeScore(I)V",
            at = At(value = InjectionPoint.FIELD_ASSIGN, target = "PrimitiveFieldPointTarget.score:I"),
        )
        @JvmStatic
        fun wrap(
            target: Any,
            value: Int,
            operation: Operation<Unit>,
        ) {
            operation.call(target, value + 2)
        }
    }

    @AsmMixin("StaticFieldPointTarget")
    object WrapOperationStaticFieldAssignMixin {
        @WrapOperation(
            method = "writeName(Ljava/lang/String;)V",
            at = At(value = InjectionPoint.FIELD_ASSIGN, target = "StaticFieldPointTarget.name:Ljava/lang/String;"),
        )
        @JvmStatic
        fun wrap(
            value: String,
            operation: Operation<Unit>,
        ) {
            operation.call("wrapped-static-$value")
        }
    }

    @AsmMixin("FieldParamTarget")
    object WrapOperationFieldAssignWithTargetParamsMixin {
        @WrapOperation(
            method = "writeName(Ljava/lang/String;Ljava/lang/String;I)V",
            at = At(value = InjectionPoint.FIELD_ASSIGN, target = "FieldParamTarget.name:Ljava/lang/String;"),
        )
        @JvmStatic
        fun wrap(
            target: Any,
            value: String,
            operation: Operation<Unit>,
            targetValue: String,
            suffix: String,
            count: Int,
        ) {
            assertEquals(value, targetValue)
            operation.call(target, "$value-$suffix$count")
        }
    }

    @AsmMixin("FieldAssignOrdinalTarget")
    object WrapOperationFieldAssignOrdinalMixin {
        @WrapOperation(
            method = "writeBoth(Ljava/lang/String;Ljava/lang/String;)V",
            at = At(
                value = InjectionPoint.FIELD_ASSIGN,
                target = "FieldAssignOrdinalTarget.name:Ljava/lang/String;",
            ),
            ordinal = 1,
        )
        @JvmStatic
        fun wrap(
            target: Any,
            value: String,
            operation: Operation<Unit>,
        ) {
            operation.call(target, "wrapped-$value")
        }
    }

    @AsmMixin("FieldPointTarget")
    object MismatchedWrapOperationFieldAssignMixin {
        @WrapOperation(
            method = "writeName(Ljava/lang/String;)V",
            at = At(value = InjectionPoint.FIELD_ASSIGN, target = "FieldPointTarget.name:Ljava/lang/String;"),
        )
        @JvmStatic
        fun wrap(
            target: Any,
            value: String,
        ) {
            target.hashCode()
            value.length
        }
    }

    @AsmMixin("ArrayAccessTarget")
    object WrapOperationArrayReadMixin {
        @WrapOperation(
            method = "readName(I)Ljava/lang/String;",
            at = At(
                value = InjectionPoint.FIELD,
                target = "ArrayAccessTarget.names:[Ljava/lang/String;",
                args = ["array=get"],
            ),
        )
        @JvmStatic
        fun wrap(
            array: Array<String>,
            index: Int,
            operation: Operation<String>,
        ): String = "wrapped-${operation.call(array, index)}"
    }

    @AsmMixin("PrimitiveArrayAccessTarget")
    object WrapOperationPrimitiveArrayReadMixin {
        @WrapOperation(
            method = "readScore(I)I",
            at = At(
                value = InjectionPoint.FIELD,
                target = "PrimitiveArrayAccessTarget.scores:[I",
                args = ["array=get"],
            ),
        )
        @JvmStatic
        fun wrap(
            array: IntArray,
            index: Int,
            operation: Operation<Int>,
        ): Int = operation.call(array, index) + 2
    }

    @AsmMixin("ArrayAccessTarget")
    object WrapOperationArrayLengthMixin {
        @WrapOperation(
            method = "nameCount()I",
            at = At(
                value = InjectionPoint.FIELD,
                target = "ArrayAccessTarget.names:[Ljava/lang/String;",
                args = ["array=length"],
            ),
        )
        @JvmStatic
        fun wrap(
            array: Array<String>,
            operation: Operation<Int>,
        ): Int = operation.call(array) + 5
    }

    @AsmMixin("ArrayAccessTarget")
    object WrapOperationArrayLengthWithTargetParamsMixin {
        @WrapOperation(
            method = "nameCount(I)I",
            at = At(
                value = InjectionPoint.FIELD,
                target = "ArrayAccessTarget.names:[Ljava/lang/String;",
                args = ["array=length"],
            ),
        )
        @JvmStatic
        fun wrap(
            array: Array<String>,
            operation: Operation<Int>,
            bonus: Int,
        ): Int = operation.call(array) + bonus
    }

    @AsmMixin("ArrayAccessTarget")
    object MismatchedWrapOperationArrayLengthMixin {
        @WrapOperation(
            method = "nameCount()I",
            at = At(
                value = InjectionPoint.FIELD,
                target = "ArrayAccessTarget.names:[Ljava/lang/String;",
                args = ["array=length"],
            ),
        )
        @JvmStatic
        fun wrap(
            array: Array<String>,
            operation: Operation<String>,
        ): String = operation.call(array)
    }

    @AsmMixin("ArrayAccessTarget")
    object WrapOperationArrayWriteMixin {
        @WrapOperation(
            method = "writeName(ILjava/lang/String;)V",
            at = At(
                value = InjectionPoint.FIELD_ASSIGN,
                target = "ArrayAccessTarget.names:[Ljava/lang/String;",
                args = ["array=set"],
            ),
        )
        @JvmStatic
        fun wrap(
            array: Array<String>,
            index: Int,
            value: String,
            operation: Operation<Unit>,
        ) {
            operation.call(array, index, "wrapped-$value")
        }
    }

    @AsmMixin("ArrayAccessTarget")
    object WrapOperationArrayWriteSkipMixin {
        @WrapOperation(
            method = "writeName(ILjava/lang/String;)V",
            at = At(
                value = InjectionPoint.FIELD_ASSIGN,
                target = "ArrayAccessTarget.names:[Ljava/lang/String;",
                args = ["array=set"],
            ),
        )
        @JvmStatic
        fun wrap(
            array: Array<String>,
            index: Int,
            value: String,
            operation: Operation<Unit>,
        ) {
            array[index].length
            value.length
            operation.hashCode()
        }
    }

    @AsmMixin("ArrayParamTarget")
    object WrapOperationArrayWriteWithTargetParamsMixin {
        @WrapOperation(
            method = "writeName(ILjava/lang/String;Ljava/lang/String;)V",
            at = At(
                value = InjectionPoint.FIELD_ASSIGN,
                target = "ArrayParamTarget.names:[Ljava/lang/String;",
                args = ["array=set"],
            ),
        )
        @JvmStatic
        fun wrap(
            array: Array<String>,
            index: Int,
            value: String,
            operation: Operation<Unit>,
            targetIndex: Int,
            targetValue: String,
            suffix: String,
        ) {
            assertEquals(index, targetIndex)
            assertEquals(value, targetValue)
            operation.call(array, index, "$value-$suffix")
        }
    }

    @AsmMixin("ArrayAccessTarget")
    object MismatchedWrapOperationArrayReadMixin {
        @WrapOperation(
            method = "readName(I)Ljava/lang/String;",
            at = At(
                value = InjectionPoint.FIELD,
                target = "ArrayAccessTarget.names:[Ljava/lang/String;",
                args = ["array=get"],
            ),
        )
        @JvmStatic
        fun wrap(
            array: Array<String>,
            index: String,
            operation: Operation<String>,
        ): String = operation.call(array, index.length)
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

    @AsmMixin("SliceConstantTarget")
    object SliceModifyConstantMixin {
        @ModifyConstant(
            method = "value()Ljava/lang/String;",
            constant = "target",
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
                to = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
            ),
        )
        @JvmStatic
        fun modify(original: String): String = "changed"
    }

    @AsmMixin("ConstantParamTarget")
    object ConstantWithTargetParamsMixin {
        @ModifyConstant(method = "value(Ljava/lang/String;I)Ljava/lang/String;", constant = "base-")
        @JvmStatic
        fun modify(
            original: String,
            suffix: String,
            count: Int,
        ): String = "$original$suffix$count"
    }

    @AsmMixin("StaticConstantParamTarget")
    object StaticConstantWithTargetParamsMixin {
        @ModifyConstant(method = "value(Ljava/lang/String;I)Ljava/lang/String;", constant = "static-")
        @JvmStatic
        fun modify(
            original: String,
            suffix: String,
            count: Int,
        ): String = "$original$suffix$count"
    }

    @AsmMixin("NullConstantTarget")
    object NullModifyConstantMixin {
        @ModifyConstant(method = "value()Ljava/lang/Object;", constant = "null")
        @JvmStatic
        fun modify(original: Any?): Any = "changed"
    }

    @AsmMixin("BipushConstantTarget")
    object BipushModifyConstantMixin {
        @ModifyConstant(method = "value()I", constant = "7")
        @JvmStatic
        fun modify(original: Int): Int = original + 35
    }

    @AsmMixin("SipushConstantTarget")
    object SipushModifyConstantMixin {
        @ModifyConstant(method = "value()I", constant = "300")
        @JvmStatic
        fun modify(original: Int): Int = original + 1
    }

    @AsmMixin("MultiIntConstantTarget")
    object OrdinalModifyConstantMixin {
        @ModifyConstant(method = "value()I", constant = "7", ordinal = 1)
        @JvmStatic
        fun modify(original: Int): Int = original + 35
    }

    @AsmMixin("MultiIntConstantTarget")
    object RequireThreeModifyConstantMixin {
        @ModifyConstant(method = "value()I", constant = "7", require = 3)
        @JvmStatic
        fun modify(original: Int): Int = original + 1
    }

    @AsmMixin("MultiIntConstantTarget")
    object AllowOneModifyConstantMixin {
        @ModifyConstant(method = "value()I", constant = "7", allow = 1)
        @JvmStatic
        fun modify(original: Int): Int = original + 1
    }

    @AsmMixin("MultiIntConstantTarget")
    object ExpectThreeModifyConstantMixin {
        @ModifyConstant(method = "value()I", constant = "7", expect = 3)
        @JvmStatic
        fun modify(original: Int): Int = original + 1
    }

    @AsmMixin("StrictTarget")
    class MissingShadowFieldMixin {
        @Shadow
        private val missing: String? = null
    }

    @AsmMixin("ShadowAliasTarget")
    class ShadowAliasOverwriteMixin {
        @Shadow("actualName")
        private val aliasName: String? = null

        @Shadow("actualLookup")
        private fun aliasLookup(value: String): String = throw UnsupportedOperationException()

        @Overwrite("value()Ljava/lang/String;")
        fun value(): String = aliasLookup(aliasName ?: "missing")
    }

    @AsmMixin("FieldTarget")
    class MismatchedShadowFieldMixin {
        @Shadow
        private val name: Int = 0
    }

    @AsmMixin("FinalFieldTarget")
    class MutableFieldOnlyMixin {
        @Shadow
        @Mutable
        private val name: String? = null
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

    @ReplaceAllMethods
    @AsmMixin("ReturnTarget")
    object ReplaceAllThenOverwriteMixin {
        @Overwrite("value()Ljava/lang/String;")
        @JvmStatic
        fun value(): String = "overwritten"
    }

    @AsmMixin("StrictTarget")
    object MissingRemoveMethodTargetMixin {
        @RemoveMethod("missing()V")
        @JvmStatic
        fun missing() {
        }
    }

    @AsmMixin("FieldTarget")
    object RemoveFieldMixin {
        @RemoveField("name")
        @JvmStatic
        fun removeName() {
        }
    }

    @AsmMixin("FieldTarget")
    object RemoveFieldByFieldDeclarationMixin {
        @RemoveField
        @JvmField
        val name: String? = null
    }

    @AsmMixin("FieldTarget")
    object RemoveFieldByRemoveMethodNameMixin {
        @RemoveField
        @JvmStatic
        fun removeName() {
        }
    }

    @AsmMixin("FieldInferenceTarget")
    object RemoveFieldByGetterNameMixin {
        @RemoveField
        @JvmStatic
        fun getName(): String = throw UnsupportedOperationException()
    }

    @AsmMixin("FieldInferenceTarget")
    object RemoveFieldBySetterNameMixin {
        @RemoveField
        @JvmStatic
        fun setScore(score: Int) {
            score.hashCode()
        }
    }

    @AsmMixin("FieldInferenceTarget")
    object RemoveFieldByBooleanGetterNameMixin {
        @RemoveField
        @JvmStatic
        fun isActive(): Boolean = throw UnsupportedOperationException()
    }

    @AsmMixin("FieldTarget")
    object MissingRemoveFieldTargetMixin {
        @RemoveField("missing")
        @JvmStatic
        fun removeMissing() {
        }
    }

    @AsmMixin("StrictTarget")
    class AddFieldMixin {
        @AddField
        private var extraName: String? = null
    }

    @AsmMixin("StrictTarget")
    class AddRenamedFieldMixin {
        @AddField("renamedScore")
        private var score: Int = 0
    }

    @AsmMixin("FieldTarget")
    class AddExistingFieldMixin {
        @AddField("name")
        private var duplicateName: String? = null
    }

    @AsmMixin("UniqueCopyTarget")
    object UniqueCopyMixin {
        @Copy("entry()Ljava/lang/String;")
        @JvmStatic
        fun entry(): String = helper()

        @Copy("helper()Ljava/lang/String;")
        @Unique
        @JvmStatic
        fun helper(): String = "unique"
    }

    @AsmMixin("UniqueCopyOverwriteTarget")
    object UniqueCopyOverwriteMixin {
        @Overwrite("entry()Ljava/lang/String;")
        @JvmStatic
        fun entry(): String = helper()

        @Copy("helper()Ljava/lang/String;")
        @Unique
        @JvmStatic
        fun helper(): String = "unique"
    }

    @AsmMixin("UniqueCopyInlineTarget")
    object UniqueCopyInlineMixin {
        @AsmInject(method = "run()V", inline = true)
        @JvmStatic
        fun injectInline() {
            if (helper() != "unique") {
                throw IllegalStateException("wrong helper")
            }
        }

        @Copy("helper()Ljava/lang/String;")
        @Unique
        @JvmStatic
        fun helper(): String = "unique"
    }

    @AsmMixin("InterfaceTarget")
    @AddInterface("java/io/Closeable")
    object AddCloseableInterfaceMixin

    @AsmMixin("InterfaceTarget")
    @AddInterface("java/lang/Runnable")
    object AddRunnableInterfaceMixin

    @AsmMixin("InterfaceTarget")
    @AddInterface(
        value = "java.lang.Runnable",
        interfaces = ["java.lang.Cloneable", "java/io/Serializable", "java.lang.Cloneable"],
    )
    object AddNormalizedInterfacesMixin

    @AsmMixin("InterfaceTarget")
    @RemoveInterface("java/lang/Runnable")
    object RemoveRunnableInterfaceMixin

    @AsmMixin("MultiInterfaceTarget")
    @RemoveInterface(
        value = "java.lang.Runnable",
        interfaces = ["java.lang.Cloneable", "java/lang/Runnable"],
    )
    object RemoveNormalizedInterfacesMixin

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

    @AsmMixin("MultiInvokeTarget")
    object InvokeOrdinalMixin {
        @AsmInject(
            method = "call()Ljava/lang/String;",
            target = InjectionPoint.INVOKE,
            at = At(value = InjectionPoint.INVOKE, target = "java/lang/String.trim()Ljava/lang/String;"),
            ordinal = 1,
        )
        @JvmStatic
        fun inject() {
        }
    }

    @AsmMixin("SliceInvokeTarget")
    object InvokeSliceMixin {
        @AsmInject(
            method = "call()Ljava/lang/String;",
            target = InjectionPoint.INVOKE,
            at = At(value = InjectionPoint.INVOKE, target = "java/lang/String.trim()Ljava/lang/String;"),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
                to = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
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

    @AsmMixin("VariableTarget")
    object ModifyVariableInstanceParamMixin {
        @ModifyVariable(
            method = "echo(Ljava/lang/String;)Ljava/lang/String;",
            at = At(value = InjectionPoint.HEAD),
            index = 1,
        )
        @JvmStatic
        fun modify(original: String): String = "modified-$original"
    }

    @AsmMixin("StaticVariableTarget")
    object ModifyVariableStaticParamMixin {
        @ModifyVariable(
            method = "echo(Ljava/lang/String;)Ljava/lang/String;",
            at = At(value = InjectionPoint.HEAD),
            index = 0,
        )
        @JvmStatic
        fun modify(original: String): String = "static-$original"
    }

    @AsmMixin("VariableTarget")
    object ModifyVariableHeadTargetParamsMixin {
        @ModifyVariable(
            method = "echo(Ljava/lang/String;)Ljava/lang/String;",
            at = At(value = InjectionPoint.HEAD),
            index = 1,
        )
        @JvmStatic
        fun modify(
            original: String,
            targetValue: String,
        ): String = "$original-$targetValue"
    }

    @AsmMixin("StaticVariableTarget")
    object ModifyVariableStaticHeadTargetParamsMixin {
        @ModifyVariable(
            method = "echo(Ljava/lang/String;)Ljava/lang/String;",
            at = At(value = InjectionPoint.HEAD),
            index = 0,
        )
        @JvmStatic
        fun modify(
            original: String,
            targetValue: String,
        ): String = "$original-$targetValue-static"
    }

    @AsmMixin("OrdinalVariableTarget")
    object ModifyVariableOrdinalParamMixin {
        @ModifyVariable(
            method = "combine(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
            at = At(value = InjectionPoint.HEAD),
            ordinal = 1,
        )
        @JvmStatic
        fun modify(original: String): String = "ordinal-$original"
    }

    @AsmMixin("StoreVariableTarget")
    object ModifyVariableStoreMixin {
        @ModifyVariable(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.STORE),
            index = 1,
        )
        @JvmStatic
        fun modify(original: String): String = "stored-$original"
    }

    @AsmMixin("StoreOrdinalVariableTarget")
    object ModifyVariableStoreOrdinalMixin {
        @ModifyVariable(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.STORE),
            ordinal = 1,
        )
        @JvmStatic
        fun modify(original: String): String = "stored-$original"
    }

    @AsmMixin("StoreOrdinalVariableTarget")
    object RequireThreeModifyVariableMixin {
        @ModifyVariable(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.STORE),
            require = 3,
        )
        @JvmStatic
        fun modify(original: String): String = original
    }

    @AsmMixin("StoreOrdinalVariableTarget")
    object AllowOneModifyVariableMixin {
        @ModifyVariable(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.STORE),
            allow = 1,
        )
        @JvmStatic
        fun modify(original: String): String = original
    }

    @AsmMixin("StoreOrdinalVariableTarget")
    object ExpectThreeModifyVariableMixin {
        @ModifyVariable(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.STORE),
            expect = 3,
        )
        @JvmStatic
        fun modify(original: String): String = original
    }

    @AsmMixin("StoreVariableParamTarget")
    object ModifyVariableStoreTargetParamsMixin {
        @ModifyVariable(
            method = "value(Ljava/lang/String;I)Ljava/lang/String;",
            at = At(value = InjectionPoint.STORE),
            index = 3,
        )
        @JvmStatic
        fun modify(
            original: String,
            suffix: String,
            count: Int,
        ): String = "stored-$original-$suffix$count"
    }

    @AsmMixin("SliceStoreVariableTarget")
    object ModifyVariableStoreSliceMixin {
        @ModifyVariable(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.STORE),
            index = 1,
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
                to = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
            ),
        )
        @JvmStatic
        fun modify(original: String): String = "stored-$original"
    }

    @AsmMixin("LoadVariableTarget")
    object ModifyVariableLoadMixin {
        @ModifyVariable(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.LOAD),
            index = 1,
        )
        @JvmStatic
        fun modify(original: String): String = "loaded-$original"
    }

    @AsmMixin("LoadVariableParamTarget")
    object ModifyVariableLoadTargetParamsMixin {
        @ModifyVariable(
            method = "value(Ljava/lang/String;I)Ljava/lang/String;",
            at = At(value = InjectionPoint.LOAD),
            index = 3,
        )
        @JvmStatic
        fun modify(
            original: String,
            suffix: String,
            count: Int,
        ): String = "loaded-$original-$suffix$count"
    }

    @AsmMixin("VariableTarget")
    object TooManyModifyVariableParametersMixin {
        @ModifyVariable(
            method = "echo(Ljava/lang/String;)Ljava/lang/String;",
            at = At(value = InjectionPoint.HEAD),
            index = 1,
        )
        @JvmStatic
        fun modify(
            original: String,
            targetValue: String,
            unavailable: String,
        ): String = "$original$targetValue$unavailable"
    }

    @AsmMixin("LoadOrdinalVariableTarget")
    object ModifyVariableLoadOrdinalMixin {
        @ModifyVariable(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.LOAD),
            ordinal = 1,
        )
        @JvmStatic
        fun modify(original: String): String = "loaded-$original"
    }

    @AsmMixin("SliceLoadVariableTarget")
    object ModifyVariableLoadSliceMixin {
        @ModifyVariable(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.LOAD),
            index = 1,
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
                to = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
            ),
        )
        @JvmStatic
        fun modify(original: String): String = "loaded-$original"
    }

    @AsmMixin("LoadVariableTarget")
    object LoadInjectMixin {
        var injectCount: Int = 0

        @AsmInject(
            method = "value()Ljava/lang/String;",
            target = InjectionPoint.LOAD,
            at = At(value = InjectionPoint.LOAD, shift = Shift.BEFORE),
            ordinal = 0,
        )
        @JvmStatic
        fun inject() {
            injectCount++
        }
    }

    @AsmMixin("LoadOrdinalVariableTarget")
    object LoadInjectIndexMixin {
        var injectCount: Int = 0

        @AsmInject(
            method = "value()Ljava/lang/String;",
            target = InjectionPoint.LOAD,
            at = At(value = InjectionPoint.LOAD, shift = Shift.BEFORE, args = ["index=2"]),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun inject() {
            injectCount++
        }
    }

    @AsmMixin("SliceLoadVariableTarget")
    object LoadInjectSliceMixin {
        var injectCount: Int = 0

        @AsmInject(
            method = "value()Ljava/lang/String;",
            target = InjectionPoint.LOAD,
            at = At(value = InjectionPoint.LOAD, shift = Shift.BEFORE),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
                to = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun inject() {
            injectCount++
        }
    }

    @AsmMixin("StoreVariableTarget")
    object StoreInjectMixin {
        var injectCount: Int = 0

        @AsmInject(
            method = "value()Ljava/lang/String;",
            target = InjectionPoint.STORE,
            at = At(value = InjectionPoint.STORE, shift = Shift.AFTER),
            ordinal = 0,
        )
        @JvmStatic
        fun inject() {
            injectCount++
        }
    }

    @AsmMixin("StoreOrdinalVariableTarget")
    object StoreInjectVarMixin {
        var injectCount: Int = 0

        @AsmInject(
            method = "value()Ljava/lang/String;",
            target = InjectionPoint.STORE,
            at = At(value = InjectionPoint.STORE, shift = Shift.AFTER, args = ["var=2"]),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun inject() {
            injectCount++
        }
    }

    @AsmMixin("SliceStoreVariableTarget")
    object StoreInjectSliceMixin {
        var injectCount: Int = 0

        @AsmInject(
            method = "value()Ljava/lang/String;",
            target = InjectionPoint.STORE,
            at = At(value = InjectionPoint.STORE, shift = Shift.AFTER),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
                to = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
            ),
            require = 2,
            allow = 2,
        )
        @JvmStatic
        fun inject() {
            injectCount++
        }
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

    @AsmMixin("ConstructorInvokerTarget")
    object ConstructorInvokerMixin {
        @Invoker("<init>")
        @JvmStatic
        fun create(value: String): Any = throw UnsupportedOperationException()
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

    @AsmMixin("FieldPointTarget")
    object FieldReadInjectMixin {
        @AsmInject(
            method = "readName()Ljava/lang/String;",
            target = InjectionPoint.FIELD,
            at = At(value = InjectionPoint.FIELD, target = "FieldPointTarget.name:Ljava/lang/String;"),
        )
        @JvmStatic
        fun inject() {
        }
    }

    @AsmMixin("MultiFieldReadTarget")
    object FieldReadOrdinalMixin {
        @AsmInject(
            method = "readTwice()Ljava/lang/String;",
            target = InjectionPoint.FIELD,
            at = At(value = InjectionPoint.FIELD, target = "MultiFieldReadTarget.name:Ljava/lang/String;"),
            ordinal = 1,
        )
        @JvmStatic
        fun inject() {
        }
    }

    @AsmMixin("SliceFieldReadTarget")
    object FieldReadSliceMixin {
        var injectCount: Int = 0

        @AsmInject(
            method = "readSelected()Ljava/lang/String;",
            target = InjectionPoint.FIELD,
            at = At(value = InjectionPoint.FIELD, target = "SliceFieldReadTarget.name:Ljava/lang/String;"),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
                to = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun inject() {
            injectCount++
        }
    }

    @AsmMixin("FieldPointTarget")
    object MissingFieldReadInjectMixin {
        @AsmInject(
            method = "readName()Ljava/lang/String;",
            target = InjectionPoint.FIELD,
            at = At(value = InjectionPoint.FIELD, target = "FieldPointTarget.missing:Ljava/lang/String;"),
        )
        @JvmStatic
        fun inject() {
        }
    }

    @AsmMixin("FieldPointTarget")
    object FieldReadReturningHandlerMixin {
        @AsmInject(
            method = "readName()Ljava/lang/String;",
            target = InjectionPoint.FIELD,
            at = At(value = InjectionPoint.FIELD, target = "FieldPointTarget.name:Ljava/lang/String;"),
        )
        @JvmStatic
        fun inject(): Int = 1
    }

    @AsmMixin("FieldPointTarget")
    object FieldReadRedirectMixin {
        @Redirect(
            method = "readName()Ljava/lang/String;",
            at = At(value = InjectionPoint.FIELD, target = "FieldPointTarget.name:Ljava/lang/String;"),
        )
        @JvmStatic
        fun redirect(target: Any): String = "redirected"
    }

    @AsmMixin("FieldPointTarget")
    object ObjectInstanceFieldReadRedirectMixin {
        @Redirect(
            method = "readName()Ljava/lang/String;",
            at = At(value = InjectionPoint.FIELD, target = "FieldPointTarget.name:Ljava/lang/String;"),
        )
        fun redirect(target: Any): String {
            target.hashCode()
            return "object-field"
        }
    }

    @AsmMixin("FieldPointTarget")
    object FieldReadNameOnlyRedirectMixin {
        @Redirect(
            method = "readName()Ljava/lang/String;",
            at = At(value = InjectionPoint.FIELD, target = "name"),
        )
        @JvmStatic
        fun redirect(target: Any): String = "name-only"
    }

    @AsmMixin("StaticFieldPointTarget")
    object StaticFieldReadRedirectMixin {
        @Redirect(
            method = "readName()Ljava/lang/String;",
            at = At(value = InjectionPoint.FIELD, target = "StaticFieldPointTarget.name:Ljava/lang/String;"),
        )
        @JvmStatic
        fun redirect(): String = "static-redirected"
    }

    @AsmMixin("StaticFieldPointTarget")
    object ObjectInstanceStaticFieldReadRedirectMixin {
        @Redirect(
            method = "readName()Ljava/lang/String;",
            at = At(value = InjectionPoint.FIELD, target = "StaticFieldPointTarget.name:Ljava/lang/String;"),
        )
        fun redirect(): String = "object-static-field"
    }

    @AsmMixin("FieldParamTarget")
    object FieldReadWithTargetParamsMixin {
        @Redirect(
            method = "readName(Ljava/lang/String;I)Ljava/lang/String;",
            at = At(value = InjectionPoint.FIELD, target = "FieldParamTarget.name:Ljava/lang/String;"),
        )
        @JvmStatic
        fun redirect(
            target: Any,
            suffix: String,
            count: Int,
        ): String {
            target.hashCode()
            return "field-$suffix$count"
        }
    }

    @AsmMixin("StaticFieldParamTarget")
    object StaticFieldReadWithTargetParamsMixin {
        @Redirect(
            method = "readName(Ljava/lang/String;I)Ljava/lang/String;",
            at = At(value = InjectionPoint.FIELD, target = "StaticFieldParamTarget.name:Ljava/lang/String;"),
        )
        @JvmStatic
        fun redirect(
            suffix: String,
            count: Int,
        ): String = "static-field-$suffix$count"
    }

    @AsmMixin("FieldPointTarget")
    object FieldAssignRedirectMixin {
        var lastValue: String? = null

        @Redirect(
            method = "writeName(Ljava/lang/String;)V",
            at = At(value = InjectionPoint.FIELD_ASSIGN, target = "FieldPointTarget.name:Ljava/lang/String;"),
        )
        @JvmStatic
        fun redirect(
            target: Any,
            value: String,
        ) {
            target.hashCode()
            lastValue = value
        }
    }

    @AsmMixin("FieldPointTarget")
    object ObjectInstanceFieldAssignRedirectMixin {
        var lastValue: String? = null

        @Redirect(
            method = "writeName(Ljava/lang/String;)V",
            at = At(value = InjectionPoint.FIELD_ASSIGN, target = "FieldPointTarget.name:Ljava/lang/String;"),
        )
        fun redirect(
            target: Any,
            value: String,
        ) {
            target.hashCode()
            lastValue = "object-$value"
        }
    }

    @AsmMixin("StaticFieldPointTarget")
    object StaticFieldAssignRedirectMixin {
        var lastValue: String? = null

        @Redirect(
            method = "writeName(Ljava/lang/String;)V",
            at = At(value = InjectionPoint.FIELD_ASSIGN, target = "StaticFieldPointTarget.name:Ljava/lang/String;"),
        )
        @JvmStatic
        fun redirect(value: String) {
            lastValue = value
        }
    }

    @AsmMixin("StaticFieldPointTarget")
    object ObjectInstanceStaticFieldAssignRedirectMixin {
        var lastValue: String? = null

        @Redirect(
            method = "writeName(Ljava/lang/String;)V",
            at = At(value = InjectionPoint.FIELD_ASSIGN, target = "StaticFieldPointTarget.name:Ljava/lang/String;"),
        )
        fun redirect(value: String) {
            lastValue = "object-$value"
        }
    }

    @AsmMixin("FieldParamTarget")
    object FieldAssignWithTargetParamsMixin {
        var lastValue: String? = null

        @Redirect(
            method = "writeName(Ljava/lang/String;Ljava/lang/String;I)V",
            at = At(value = InjectionPoint.FIELD_ASSIGN, target = "FieldParamTarget.name:Ljava/lang/String;"),
        )
        @JvmStatic
        fun redirect(
            target: Any,
            value: String,
            targetValue: String,
            suffix: String,
            count: Int,
        ) {
            target.hashCode()
            if (value != targetValue) {
                throw IllegalStateException("Unexpected target value: $targetValue")
            }
            lastValue = "$value-$suffix$count"
        }
    }

    @AsmMixin("StaticFieldParamTarget")
    object StaticFieldAssignWithTargetParamsMixin {
        var lastValue: String? = null

        @Redirect(
            method = "writeName(Ljava/lang/String;Ljava/lang/String;I)V",
            at = At(value = InjectionPoint.FIELD_ASSIGN, target = "StaticFieldParamTarget.name:Ljava/lang/String;"),
        )
        @JvmStatic
        fun redirect(
            value: String,
            targetValue: String,
            suffix: String,
            count: Int,
        ) {
            if (value != targetValue) {
                throw IllegalStateException("Unexpected target value: $targetValue")
            }
            lastValue = "$value-$suffix$count"
        }
    }

    @AsmMixin("ArrayAccessTarget")
    object ArrayReadRedirectMixin {
        @Redirect(
            method = "readName(I)Ljava/lang/String;",
            at = At(
                value = InjectionPoint.FIELD,
                target = "ArrayAccessTarget.names:[Ljava/lang/String;",
                args = ["array=get"],
            ),
        )
        @JvmStatic
        fun redirect(
            array: Array<String>,
            index: Int,
        ): String = "redirected-${array[index]}"
    }

    @AsmMixin("ArrayAccessTarget")
    object ArrayWriteRedirectMixin {
        @Redirect(
            method = "writeName(ILjava/lang/String;)V",
            at = At(
                value = InjectionPoint.FIELD,
                target = "ArrayAccessTarget.names:[Ljava/lang/String;",
                args = ["array=set"],
            ),
        )
        @JvmStatic
        fun redirect(
            array: Array<String>,
            index: Int,
            value: String,
        ) {
            array[index] = "written-$value"
        }
    }

    @AsmMixin("PrimitiveArrayAccessTarget")
    object PrimitiveArrayReadRedirectMixin {
        @Redirect(
            method = "readScore(I)I",
            at = At(
                value = InjectionPoint.FIELD,
                target = "PrimitiveArrayAccessTarget.scores:[I",
                args = ["array=get"],
            ),
        )
        @JvmStatic
        fun redirect(
            array: IntArray,
            index: Int,
        ): Int = array[index] + 2
    }

    @AsmMixin("PrimitiveArrayAccessTarget")
    object PrimitiveArrayWriteRedirectMixin {
        @Redirect(
            method = "writeScore(II)V",
            at = At(
                value = InjectionPoint.FIELD,
                target = "PrimitiveArrayAccessTarget.scores:[I",
                args = ["array=set"],
            ),
        )
        @JvmStatic
        fun redirect(
            array: IntArray,
            index: Int,
            value: Int,
        ) {
            array[index] = value + 2
        }
    }

    @AsmMixin("ArrayParamTarget")
    object ArrayReadWithTargetParamsRedirectMixin {
        @Redirect(
            method = "readName(ILjava/lang/String;)Ljava/lang/String;",
            at = At(
                value = InjectionPoint.FIELD,
                target = "ArrayParamTarget.names:[Ljava/lang/String;",
                args = ["array=get"],
            ),
        )
        @JvmStatic
        fun redirect(
            array: Array<String>,
            index: Int,
            targetIndex: Int,
            suffix: String,
        ): String {
            if (index != targetIndex) {
                throw IllegalStateException("Unexpected array index: $index")
            }
            return "${array[index]}-$suffix"
        }
    }

    @AsmMixin("ArrayAccessTarget")
    object ArrayLengthRedirectMixin {
        @Redirect(
            method = "nameCount()I",
            at = At(
                value = InjectionPoint.FIELD,
                target = "ArrayAccessTarget.names:[Ljava/lang/String;",
                args = ["array=length"],
            ),
        )
        @JvmStatic
        fun redirect(array: Array<String>): Int = array.size + 5
    }

    @AsmMixin("ArrayAccessTarget")
    object ArrayLengthWithTargetParamsRedirectMixin {
        @Redirect(
            method = "nameCount(I)I",
            at = At(
                value = InjectionPoint.FIELD,
                target = "ArrayAccessTarget.names:[Ljava/lang/String;",
                args = ["array=length"],
            ),
        )
        @JvmStatic
        fun redirect(
            array: Array<String>,
            bonus: Int,
        ): Int = array.size + bonus
    }

    @AsmMixin("ArrayAccessTarget")
    object MismatchedArrayLengthRedirectMixin {
        @Redirect(
            method = "nameCount()I",
            at = At(
                value = InjectionPoint.FIELD,
                target = "ArrayAccessTarget.names:[Ljava/lang/String;",
                args = ["array=length"],
            ),
        )
        @JvmStatic
        fun redirect(array: Array<String>): String = array.size.toString()
    }

    @AsmMixin("ArrayAccessTarget")
    object MismatchedArrayReadRedirectMixin {
        @Redirect(
            method = "readName(I)Ljava/lang/String;",
            at = At(
                value = InjectionPoint.FIELD,
                target = "ArrayAccessTarget.names:[Ljava/lang/String;",
                args = ["array=get"],
            ),
        )
        @JvmStatic
        fun redirect(
            array: Array<String>,
            index: String,
        ): String = array[index.length]
    }

    @AsmMixin("RedirectOrdinalTarget")
    object RedirectOrdinalTrimMixin {
        @Redirect(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.INVOKE, target = "java/lang/String.trim()Ljava/lang/String;"),
            ordinal = 1,
        )
        @JvmStatic
        fun redirect(value: String): String = "redirected"
    }

    @AsmMixin("RedirectOrdinalTarget")
    object RequireThreeRedirectMixin {
        @Redirect(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.INVOKE, target = "java/lang/String.trim()Ljava/lang/String;"),
            require = 3,
        )
        @JvmStatic
        fun redirect(value: String): String = value.trim()
    }

    @AsmMixin("RedirectOrdinalTarget")
    object AllowOneRedirectMixin {
        @Redirect(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.INVOKE, target = "java/lang/String.trim()Ljava/lang/String;"),
            allow = 1,
        )
        @JvmStatic
        fun redirect(value: String): String = value.trim()
    }

    @AsmMixin("RedirectOrdinalTarget")
    object ExpectThreeRedirectMixin {
        @Redirect(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.INVOKE, target = "java/lang/String.trim()Ljava/lang/String;"),
            expect = 3,
        )
        @JvmStatic
        fun redirect(value: String): String = value.trim()
    }

    @AsmMixin("RedirectSliceTarget")
    object RedirectSliceTrimMixin {
        @Redirect(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.INVOKE, target = "java/lang/String.trim()Ljava/lang/String;"),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
                to = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
            ),
        )
        @JvmStatic
        fun redirect(value: String): String = "redirected"
    }

    @AsmMixin("FieldReadOrdinalTarget")
    object FieldReadRedirectOrdinalMixin {
        @Redirect(
            method = "readBoth()Ljava/lang/String;",
            at = At(value = InjectionPoint.FIELD, target = "FieldReadOrdinalTarget.name:Ljava/lang/String;"),
            ordinal = 1,
        )
        @JvmStatic
        fun redirect(target: Any): String {
            target.hashCode()
            return "redirected"
        }
    }

    @AsmMixin("FieldAssignOrdinalTarget")
    object FieldAssignRedirectOrdinalMixin {
        var lastValue: String? = null

        @Redirect(
            method = "writeBoth(Ljava/lang/String;Ljava/lang/String;)V",
            at = At(value = InjectionPoint.FIELD_ASSIGN, target = "FieldAssignOrdinalTarget.name:Ljava/lang/String;"),
            ordinal = 1,
        )
        @JvmStatic
        fun redirect(
            target: Any,
            value: String,
        ) {
            target.hashCode()
            lastValue = value
        }
    }

    @AsmMixin("FieldPointTarget")
    object FieldAssignInjectMixin {
        @AsmInject(
            method = "writeName(Ljava/lang/String;)V",
            target = InjectionPoint.FIELD_ASSIGN,
            at = At(value = InjectionPoint.FIELD_ASSIGN, target = "FieldPointTarget.name:Ljava/lang/String;"),
        )
        @JvmStatic
        fun inject() {
        }
    }

    @AsmMixin("SliceFieldAssignTarget")
    object FieldAssignSliceMixin {
        var injectCount: Int = 0

        @AsmInject(
            method = "writeSelected(Ljava/lang/String;Ljava/lang/String;)V",
            target = InjectionPoint.FIELD_ASSIGN,
            at = At(value = InjectionPoint.FIELD_ASSIGN, target = "SliceFieldAssignTarget.name:Ljava/lang/String;"),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
                to = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun inject() {
            injectCount++
        }
    }

    @AsmMixin("NewInstructionTarget")
    object NewInstructionInjectMixin {
        @AsmInject(
            method = "create()Ljava/lang/StringBuilder;",
            target = InjectionPoint.NEW,
            at = At(value = InjectionPoint.NEW, target = "java/lang/StringBuilder"),
        )
        @JvmStatic
        fun inject() {
        }
    }

    @AsmMixin("CastInstructionTarget")
    object CastInstructionInjectMixin {
        @AsmInject(
            method = "cast(Ljava/lang/Object;)Ljava/lang/String;",
            target = InjectionPoint.CAST,
            at = At(value = InjectionPoint.CAST, target = "java/lang/String"),
        )
        @JvmStatic
        fun inject() {
        }
    }

    @AsmMixin("SliceCastInstructionTarget")
    object CastInstructionSliceMixin {
        var injectCount: Int = 0

        @AsmInject(
            method = "castSelected(Ljava/lang/Object;)Ljava/lang/String;",
            target = InjectionPoint.CAST,
            at = At(value = InjectionPoint.CAST, target = "java/lang/String"),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
                to = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun inject() {
            injectCount++
        }
    }

    @AsmMixin("NewInstructionTarget")
    object NewInstructionAfterInjectMixin {
        @AsmInject(
            method = "create()Ljava/lang/StringBuilder;",
            target = InjectionPoint.NEW,
            at = At(value = InjectionPoint.NEW, target = "java/lang/StringBuilder", shift = Shift.AFTER),
        )
        @JvmStatic
        fun inject() {
        }
    }

    @AsmMixin("ThrowPointTarget")
    object ThrowInstructionInjectMixin {
        @AsmInject(method = "fail()V", target = InjectionPoint.THROW)
        @JvmStatic
        fun inject() {
        }
    }

    @AsmMixin("SliceThrowInstructionTarget")
    object ThrowInstructionSliceMixin {
        @AsmInject(
            method = "failSelected()V",
            target = InjectionPoint.THROW,
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun inject() {
        }
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

    private fun redirectParamTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "RedirectParamTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "(Ljava/lang/String;I)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn(" base ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "trim", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 3)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun staticRedirectParamTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "StaticRedirectParamTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "value",
            "(Ljava/lang/String;I)Ljava/lang/String;",
            null,
            null,
        ).apply {
            visitCode()
            visitIntInsn(Opcodes.BIPUSH, 42)
            visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "toString", "(I)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 2)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun multiInvokeTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "MultiInvokeTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "call", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn(" first ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "trim", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitLdcInsn(" second ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "trim", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun sliceInvokeTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "SliceInvokeTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "call", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn(" pre ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "trim", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitLdcInsn(" start ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitLdcInsn(" inside ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "trim", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitLdcInsn(" end ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitLdcInsn(" outside ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "trim", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun redirectOrdinalTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "RedirectOrdinalTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn(" first ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "trim", "()Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ASTORE, 1)
            visitLdcInsn(" second ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "trim", "()Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ASTORE, 2)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitLdcInsn(":")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 3)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun redirectSliceTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "RedirectSliceTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn(" pre ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "trim", "()Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ASTORE, 1)
            visitLdcInsn(" start ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitLdcInsn(" inside ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "trim", "()Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ASTORE, 2)
            visitLdcInsn(" end ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitLdcInsn(" outside ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "trim", "()Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ASTORE, 3)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitLdcInsn(":")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitLdcInsn(":")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 3)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 4)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun fieldReadOrdinalTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "FieldReadOrdinalTarget", null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PUBLIC, "name", "Ljava/lang/String;", null, null).visitEnd()
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "readBoth", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "FieldReadOrdinalTarget", "name", "Ljava/lang/String;")
            visitVarInsn(Opcodes.ASTORE, 1)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "FieldReadOrdinalTarget", "name", "Ljava/lang/String;")
            visitVarInsn(Opcodes.ASTORE, 2)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitLdcInsn(":")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 3)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun fieldAssignOrdinalTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "FieldAssignOrdinalTarget", null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PUBLIC, "name", "Ljava/lang/String;", null, null).visitEnd()
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "writeBoth", "(Ljava/lang/String;Ljava/lang/String;)V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitFieldInsn(Opcodes.PUTFIELD, "FieldAssignOrdinalTarget", "name", "Ljava/lang/String;")
            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitFieldInsn(Opcodes.PUTFIELD, "FieldAssignOrdinalTarget", "name", "Ljava/lang/String;")
            visitInsn(Opcodes.RETURN)
            visitMaxs(2, 3)
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

    private fun variableTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "VariableTarget", null, "java/lang/Object", null)
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

    private fun staticVariableTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "StaticVariableTarget", null, "java/lang/Object", null)
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

    private fun ordinalVariableTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "OrdinalVariableTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(
            Opcodes.ACC_PUBLIC,
            "combine",
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
            null,
            null,
        ).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 1)
            visitLdcInsn(":")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 3)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun storeVariableTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "StoreVariableTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("local")
            visitVarInsn(Opcodes.ASTORE, 1)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 2)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun sliceStoreVariableTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "SliceStoreVariableTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("pre")
            visitVarInsn(Opcodes.ASTORE, 1)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitVarInsn(Opcodes.ASTORE, 2)
            visitLdcInsn(" start ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitLdcInsn("inside")
            visitVarInsn(Opcodes.ASTORE, 1)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitVarInsn(Opcodes.ASTORE, 3)
            visitLdcInsn(" end ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitLdcInsn("outside")
            visitVarInsn(Opcodes.ASTORE, 1)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitVarInsn(Opcodes.ASTORE, 4)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitLdcInsn(":")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 3)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitLdcInsn(":")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 4)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 5)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun storeOrdinalVariableTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "StoreOrdinalVariableTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("first")
            visitVarInsn(Opcodes.ASTORE, 1)
            visitLdcInsn("second")
            visitVarInsn(Opcodes.ASTORE, 2)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitLdcInsn(":")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 3)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun storeVariableParamTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "StoreVariableParamTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "(Ljava/lang/String;I)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("local")
            visitVarInsn(Opcodes.ASTORE, 3)
            visitVarInsn(Opcodes.ALOAD, 3)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 4)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun loadVariableTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "LoadVariableTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("local")
            visitVarInsn(Opcodes.ASTORE, 1)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 2)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun loadVariableParamTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "LoadVariableParamTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "(Ljava/lang/String;I)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("local")
            visitVarInsn(Opcodes.ASTORE, 3)
            visitVarInsn(Opcodes.ALOAD, 3)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 4)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun loadOrdinalVariableTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "LoadOrdinalVariableTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("first")
            visitVarInsn(Opcodes.ASTORE, 1)
            visitLdcInsn("second")
            visitVarInsn(Opcodes.ASTORE, 2)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitLdcInsn(":")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 3)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun sliceLoadVariableTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "SliceLoadVariableTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("pre")
            visitVarInsn(Opcodes.ASTORE, 1)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitVarInsn(Opcodes.ASTORE, 2)
            visitLdcInsn(" start ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitLdcInsn("inside")
            visitVarInsn(Opcodes.ASTORE, 1)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitVarInsn(Opcodes.ASTORE, 3)
            visitLdcInsn(" end ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitLdcInsn("outside")
            visitVarInsn(Opcodes.ASTORE, 1)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitVarInsn(Opcodes.ASTORE, 4)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitLdcInsn(":")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 3)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitLdcInsn(":")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 4)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 5)
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

    private fun charReturnTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "CharReturnTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()C", null, null).apply {
            visitCode()
            visitIntInsn(Opcodes.BIPUSH, 'a'.code)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun multiReturnTargetBytes(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "MultiReturnTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "(Z)Ljava/lang/String;", null, null).apply {
            val secondReturn = org.objectweb.asm.Label()
            visitCode()
            visitVarInsn(Opcodes.ILOAD, 1)
            visitJumpInsn(Opcodes.IFEQ, secondReturn)
            visitLdcInsn("first")
            visitInsn(Opcodes.ARETURN)
            visitLabel(secondReturn)
            visitLdcInsn("second")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun staticInvokeArgTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "StaticInvokeArgTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitIntInsn(Opcodes.BIPUSH, 42)
            visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "toString", "(I)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun wrapMethodStaticTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "WrapMethodStaticTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "value",
            "(Ljava/lang/String;I)Ljava/lang/String;",
            null,
            null,
        ).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder")
            visitInsn(Opcodes.DUP)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                false,
            )
            visitVarInsn(Opcodes.ILOAD, 1)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", false)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 2)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun wrapMethodInstanceTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "WrapMethodInstanceTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(
            Opcodes.ACC_PUBLIC,
            "value",
            "(Ljava/lang/String;I)Ljava/lang/String;",
            null,
            null,
        ).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder")
            visitInsn(Opcodes.DUP)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false)
            visitLdcInsn("instance:")
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                false,
            )
            visitVarInsn(Opcodes.ALOAD, 1)
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                false,
            )
            visitVarInsn(Opcodes.ILOAD, 2)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", false)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 3)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun wideInvokeArgTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "WideInvokeArgTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn(1.5)
            visitIntInsn(Opcodes.BIPUSH, 7)
            visitMethodInsn(Opcodes.INVOKESTATIC, "WideInvokeArgTarget", "combine", "(DI)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(3, 1)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "combine", "(DI)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("ok")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 3)
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

    private fun invokeModifyArgTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "InvokeModifyArgTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("prefix-")
            visitLdcInsn("original")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun invokeModifyArgParamTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "InvokeModifyArgParamTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "(Ljava/lang/String;I)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("prefix-")
            visitLdcInsn("original")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 3)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun multiInvokeModifyArgTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "MultiInvokeModifyArgTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("first-")
            visitLdcInsn("original")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ASTORE, 1)
            visitLdcInsn("second-")
            visitLdcInsn("original")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ASTORE, 2)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitLdcInsn(":")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 3)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun modifyArgContractTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "ModifyArgContractTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("first-")
            visitLdcInsn("original")
            visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "ModifyArgContractTarget",
                "combine",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                false,
            )
            visitVarInsn(Opcodes.ASTORE, 1)
            visitLdcInsn("second-")
            visitLdcInsn("original")
            visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "ModifyArgContractTarget",
                "combine",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                false,
            )
            visitVarInsn(Opcodes.ASTORE, 2)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/String",
                "concat",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false,
            )
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 3)
            visitEnd()
        }
        cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "combine",
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
            null,
            null,
        ).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/String",
                "concat",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false,
            )
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 2)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun sliceInvokeModifyArgTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "SliceInvokeModifyArgTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("pre-")
            visitLdcInsn("original")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ASTORE, 1)
            visitLdcInsn(" start ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitLdcInsn("inside-")
            visitLdcInsn("original")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ASTORE, 2)
            visitLdcInsn(" end ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitLdcInsn("outside-")
            visitLdcInsn("original")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ASTORE, 3)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitLdcInsn(":")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitLdcInsn(":")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 3)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 4)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun constructorModifyArgTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "ConstructorModifyArgTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder")
            visitInsn(Opcodes.DUP)
            visitLdcInsn("raw")
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(3, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun modifyArgsTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "ModifyArgsTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("hello raw")
            visitLdcInsn("missing")
            visitLdcInsn("bad")
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/String",
                "replace",
                "(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;",
                false,
            )
            visitInsn(Opcodes.ARETURN)
            visitMaxs(3, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun modifyArgsParamTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "ModifyArgsParamTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "(Ljava/lang/String;I)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("left")
            visitLdcInsn("unused")
            visitInsn(Opcodes.ICONST_0)
            visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "ModifyArgsParamTarget",
                "join",
                "(Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/String;",
                false,
            )
            visitInsn(Opcodes.ARETURN)
            visitMaxs(3, 3)
            visitEnd()
        }
        cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "join",
            "(Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/String;",
            null,
            null,
        ).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitLdcInsn("-")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitLdcInsn("-")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ILOAD, 2)
            visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "toString", "(I)Ljava/lang/String;", false)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 3)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun multiModifyArgsTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "MultiModifyArgsTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("first raw")
            visitLdcInsn("missing")
            visitLdcInsn("bad")
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/String",
                "replace",
                "(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;",
                false,
            )
            visitVarInsn(Opcodes.ASTORE, 1)
            visitLdcInsn("second raw")
            visitLdcInsn("missing")
            visitLdcInsn("bad")
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/String",
                "replace",
                "(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;",
                false,
            )
            visitVarInsn(Opcodes.ASTORE, 2)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitLdcInsn(":")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(3, 3)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun sliceModifyArgsTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "SliceModifyArgsTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("pre raw")
            visitLdcInsn("missing")
            visitLdcInsn("bad")
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/String",
                "replace",
                "(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;",
                false,
            )
            visitVarInsn(Opcodes.ASTORE, 1)
            visitLdcInsn(" start ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitLdcInsn("inside raw")
            visitLdcInsn("missing")
            visitLdcInsn("bad")
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/String",
                "replace",
                "(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;",
                false,
            )
            visitVarInsn(Opcodes.ASTORE, 2)
            visitLdcInsn(" end ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitLdcInsn("outside raw")
            visitLdcInsn("missing")
            visitLdcInsn("bad")
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/String",
                "replace",
                "(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;",
                false,
            )
            visitVarInsn(Opcodes.ASTORE, 3)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitLdcInsn(":")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitLdcInsn(":")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 3)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(3, 4)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun wrapConditionStaticTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "WrapConditionStaticTarget", null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC, "last", "Ljava/lang/String;", null, null).visitEnd()
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "run", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("raw")
            visitMethodInsn(Opcodes.INVOKESTATIC, "WrapConditionStaticTarget", "record", "(Ljava/lang/String;)V", false)
            visitFieldInsn(Opcodes.GETSTATIC, "WrapConditionStaticTarget", "last", "Ljava/lang/String;")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 0)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "record", "(Ljava/lang/String;)V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.PUTSTATIC, "WrapConditionStaticTarget", "last", "Ljava/lang/String;")
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun wrapConditionInstanceTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "WrapConditionInstanceTarget", null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE, "last", "Ljava/lang/String;", null, null).visitEnd()
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "run", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitLdcInsn("raw")
            visitInsn(Opcodes.ICONST_3)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "WrapConditionInstanceTarget", "record", "(Ljava/lang/String;I)V", false)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "WrapConditionInstanceTarget", "last", "Ljava/lang/String;")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(3, 1)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "record", "(Ljava/lang/String;I)V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitVarInsn(Opcodes.ILOAD, 2)
            visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "toString", "(I)Ljava/lang/String;", false)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitFieldInsn(Opcodes.PUTFIELD, "WrapConditionInstanceTarget", "last", "Ljava/lang/String;")
            visitInsn(Opcodes.RETURN)
            visitMaxs(3, 3)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun wrapConditionParamTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "WrapConditionParamTarget", null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC, "last", "Ljava/lang/String;", null, null).visitEnd()
        addDefaultConstructor(cw)
        cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "run",
            "(Ljava/lang/String;I)Ljava/lang/String;",
            null,
            null,
        ).apply {
            visitCode()
            visitLdcInsn("raw")
            visitMethodInsn(Opcodes.INVOKESTATIC, "WrapConditionParamTarget", "record", "(Ljava/lang/String;)V", false)
            visitFieldInsn(Opcodes.GETSTATIC, "WrapConditionParamTarget", "last", "Ljava/lang/String;")
            visitLdcInsn("-")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ILOAD, 1)
            visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "toString", "(I)Ljava/lang/String;", false)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 2)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "record", "(Ljava/lang/String;)V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.PUTSTATIC, "WrapConditionParamTarget", "last", "Ljava/lang/String;")
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun multiWrapConditionTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "MultiWrapConditionTarget", null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC, "last", "Ljava/lang/String;", null, null).visitEnd()
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "run", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("first")
            visitMethodInsn(Opcodes.INVOKESTATIC, "MultiWrapConditionTarget", "record", "(Ljava/lang/String;)V", false)
            visitLdcInsn("second")
            visitMethodInsn(Opcodes.INVOKESTATIC, "MultiWrapConditionTarget", "record", "(Ljava/lang/String;)V", false)
            visitFieldInsn(Opcodes.GETSTATIC, "MultiWrapConditionTarget", "last", "Ljava/lang/String;")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 0)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "record", "(Ljava/lang/String;)V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.PUTSTATIC, "MultiWrapConditionTarget", "last", "Ljava/lang/String;")
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun sliceWrapConditionTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "SliceWrapConditionTarget", null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC, "last", "Ljava/lang/String;", null, null).visitEnd()
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "run", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("")
            visitFieldInsn(Opcodes.PUTSTATIC, "SliceWrapConditionTarget", "last", "Ljava/lang/String;")
            visitLdcInsn("pre")
            visitMethodInsn(Opcodes.INVOKESTATIC, "SliceWrapConditionTarget", "record", "(Ljava/lang/String;)V", false)
            visitLdcInsn(" start ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitLdcInsn("inside")
            visitMethodInsn(Opcodes.INVOKESTATIC, "SliceWrapConditionTarget", "record", "(Ljava/lang/String;)V", false)
            visitLdcInsn(" end ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitLdcInsn("outside")
            visitMethodInsn(Opcodes.INVOKESTATIC, "SliceWrapConditionTarget", "record", "(Ljava/lang/String;)V", false)
            visitFieldInsn(Opcodes.GETSTATIC, "SliceWrapConditionTarget", "last", "Ljava/lang/String;")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 0)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "record", "(Ljava/lang/String;)V", null, null).apply {
            visitCode()
            visitFieldInsn(Opcodes.GETSTATIC, "SliceWrapConditionTarget", "last", "Ljava/lang/String;")
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitFieldInsn(Opcodes.PUTSTATIC, "SliceWrapConditionTarget", "last", "Ljava/lang/String;")
            visitInsn(Opcodes.RETURN)
            visitMaxs(2, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun expressionValueTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "ExpressionValueTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn(" raw ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "trim", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun expressionValueParamTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "ExpressionValueParamTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "(Ljava/lang/String;I)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn(" raw ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "trim", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 3)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun multiExpressionValueTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "MultiExpressionValueTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn(" first ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "trim", "()Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ASTORE, 1)
            visitLdcInsn(" second ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "trim", "()Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ASTORE, 2)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitLdcInsn(":")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 3)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun sliceExpressionValueTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "SliceExpressionValueTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn(" pre ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "trim", "()Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ASTORE, 1)
            visitLdcInsn(" start ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitLdcInsn(" inside ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "trim", "()Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ASTORE, 2)
            visitLdcInsn(" end ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitLdcInsn(" outside ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "trim", "()Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ASTORE, 3)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitLdcInsn(":")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitLdcInsn(":")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 3)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 4)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun modifyReceiverTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "ModifyReceiverTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("original")
            visitLdcInsn("-call")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun sliceModifyReceiverFieldTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "SliceModifyReceiverFieldTarget", null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE, "value", "Ljava/lang/String;", null, null).visitEnd()
        cw.visitField(
            Opcodes.ACC_PRIVATE,
            "replacement",
            "LSliceModifyReceiverFieldTarget;",
            null,
            null,
        ).visitEnd()
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitLdcInsn("primary")
            visitFieldInsn(Opcodes.PUTFIELD, "SliceModifyReceiverFieldTarget", "value", "Ljava/lang/String;")
            visitVarInsn(Opcodes.ALOAD, 0)
            visitTypeInsn(Opcodes.NEW, "SliceModifyReceiverFieldTarget")
            visitInsn(Opcodes.DUP)
            visitInsn(Opcodes.ICONST_1)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "SliceModifyReceiverFieldTarget", "<init>", "(Z)V", false)
            visitFieldInsn(
                Opcodes.PUTFIELD,
                "SliceModifyReceiverFieldTarget",
                "replacement",
                "LSliceModifyReceiverFieldTarget;",
            )
            visitInsn(Opcodes.RETURN)
            visitMaxs(3, 1)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "(Z)V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitLdcInsn("replacement")
            visitFieldInsn(Opcodes.PUTFIELD, "SliceModifyReceiverFieldTarget", "value", "Ljava/lang/String;")
            visitInsn(Opcodes.RETURN)
            visitMaxs(2, 2)
            visitEnd()
        }
        cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "readReplacement",
            "(Ljava/lang/Object;)Ljava/lang/Object;",
            null,
            null,
        ).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitTypeInsn(Opcodes.CHECKCAST, "SliceModifyReceiverFieldTarget")
            visitFieldInsn(
                Opcodes.GETFIELD,
                "SliceModifyReceiverFieldTarget",
                "replacement",
                "LSliceModifyReceiverFieldTarget;",
            )
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "writeReplacement",
            "(Ljava/lang/Object;)Ljava/lang/Object;",
            null,
            null,
        ).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitTypeInsn(Opcodes.CHECKCAST, "SliceModifyReceiverFieldTarget")
            visitFieldInsn(
                Opcodes.GETFIELD,
                "SliceModifyReceiverFieldTarget",
                "replacement",
                "LSliceModifyReceiverFieldTarget;",
            )
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "readSelected", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "SliceModifyReceiverFieldTarget", "value", "Ljava/lang/String;")
            visitInsn(Opcodes.POP)
            visitLdcInsn(" start ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "SliceModifyReceiverFieldTarget", "value", "Ljava/lang/String;")
            visitVarInsn(Opcodes.ASTORE, 1)
            visitLdcInsn(" end ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "SliceModifyReceiverFieldTarget", "value", "Ljava/lang/String;")
            visitLdcInsn(":")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 2)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "writeSelected", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitLdcInsn("outside")
            visitFieldInsn(Opcodes.PUTFIELD, "SliceModifyReceiverFieldTarget", "value", "Ljava/lang/String;")
            visitLdcInsn(" start ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitLdcInsn("inside")
            visitFieldInsn(Opcodes.PUTFIELD, "SliceModifyReceiverFieldTarget", "value", "Ljava/lang/String;")
            visitLdcInsn(" end ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "SliceModifyReceiverFieldTarget", "value", "Ljava/lang/String;")
            visitLdcInsn(":")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(
                Opcodes.GETFIELD,
                "SliceModifyReceiverFieldTarget",
                "replacement",
                "LSliceModifyReceiverFieldTarget;",
            )
            visitFieldInsn(Opcodes.GETFIELD, "SliceModifyReceiverFieldTarget", "value", "Ljava/lang/String;")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun modifyReceiverParamTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "ModifyReceiverParamTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "(Ljava/lang/String;I)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("original")
            visitLdcInsn("-call")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 3)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun modifyReceiverContractTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "ModifyReceiverContractTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("first")
            visitLdcInsn("-a")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitLdcInsn("second")
            visitLdcInsn("-b")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun multiModifyReceiverTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "MultiModifyReceiverTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("first")
            visitLdcInsn("-a")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ASTORE, 1)
            visitLdcInsn("second")
            visitLdcInsn("-b")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ASTORE, 2)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitLdcInsn(":")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 3)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun sliceModifyReceiverTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "SliceModifyReceiverTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("pre")
            visitLdcInsn("-a")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ASTORE, 1)
            visitLdcInsn(" start ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitLdcInsn("inside")
            visitLdcInsn("-b")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ASTORE, 2)
            visitLdcInsn(" end ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitLdcInsn("outside")
            visitLdcInsn("-c")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ASTORE, 3)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitLdcInsn(":")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitLdcInsn(":")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 3)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 4)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun sliceWrapOperationTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "SliceWrapOperationTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("pre")
            visitLdcInsn("-raw")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ASTORE, 1)
            visitLdcInsn(" start ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitLdcInsn("inside")
            visitLdcInsn("-raw")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ASTORE, 2)
            visitLdcInsn(" end ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitLdcInsn("outside")
            visitLdcInsn("-raw")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ASTORE, 3)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitLdcInsn(":")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitLdcInsn(":")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 3)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 4)
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

    private fun uniqueCopyTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "UniqueCopyTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "helper", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("target")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun uniqueCopyOverwriteTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "UniqueCopyOverwriteTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "entry", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("original")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "helper", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("target")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun uniqueCopyInlineTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "UniqueCopyInlineTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null).apply {
            visitCode()
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 1)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "helper", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("target")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 0)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun fieldInferenceTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "FieldInferenceTarget", null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE, "name", "Ljava/lang/String;", null, null).visitEnd()
        cw.visitField(Opcodes.ACC_PRIVATE, "score", "I", null, null).visitEnd()
        cw.visitField(Opcodes.ACC_PRIVATE, "active", "Z", null, null).visitEnd()
        addDefaultConstructor(cw)
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun finalFieldTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "FinalFieldTarget", null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL, "name", "Ljava/lang/String;", null, null).visitEnd()
        addDefaultConstructor(cw)
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun interfaceTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "InterfaceTarget", null, "java/lang/Object", arrayOf("java/lang/Runnable"))
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

    private fun multiInterfaceTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(
            Opcodes.V11,
            Opcodes.ACC_PUBLIC,
            "MultiInterfaceTarget",
            null,
            "java/lang/Object",
            arrayOf("java/lang/Runnable", "java/lang/Cloneable", "java/io/Serializable"),
        )
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

    private fun fieldPointTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "FieldPointTarget", null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE, "name", "Ljava/lang/String;", null, null).visitEnd()
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "readName", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "FieldPointTarget", "name", "Ljava/lang/String;")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "writeName", "(Ljava/lang/String;)V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitFieldInsn(Opcodes.PUTFIELD, "FieldPointTarget", "name", "Ljava/lang/String;")
            visitInsn(Opcodes.RETURN)
            visitMaxs(2, 2)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun sliceFieldReadTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "SliceFieldReadTarget", null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE, "name", "Ljava/lang/String;", null, null).visitEnd()
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "readSelected", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "SliceFieldReadTarget", "name", "Ljava/lang/String;")
            visitInsn(Opcodes.POP)
            visitLdcInsn(" start ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "SliceFieldReadTarget", "name", "Ljava/lang/String;")
            visitVarInsn(Opcodes.ASTORE, 1)
            visitLdcInsn(" end ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 2)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "writeName", "(Ljava/lang/String;)V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitFieldInsn(Opcodes.PUTFIELD, "SliceFieldReadTarget", "name", "Ljava/lang/String;")
            visitInsn(Opcodes.RETURN)
            visitMaxs(2, 2)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun sliceFieldAssignTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "SliceFieldAssignTarget", null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PUBLIC, "name", "Ljava/lang/String;", null, null).visitEnd()
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "writeSelected", "(Ljava/lang/String;Ljava/lang/String;)V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitFieldInsn(Opcodes.PUTFIELD, "SliceFieldAssignTarget", "name", "Ljava/lang/String;")
            visitLdcInsn(" start ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitFieldInsn(Opcodes.PUTFIELD, "SliceFieldAssignTarget", "name", "Ljava/lang/String;")
            visitLdcInsn(" end ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
            visitMaxs(2, 3)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun primitiveFieldPointTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "PrimitiveFieldPointTarget", null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE, "score", "I", null, null).visitEnd()
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "readScore", "()I", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "PrimitiveFieldPointTarget", "score", "I")
            visitInsn(Opcodes.IRETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "writeScore", "(I)V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ILOAD, 1)
            visitFieldInsn(Opcodes.PUTFIELD, "PrimitiveFieldPointTarget", "score", "I")
            visitInsn(Opcodes.RETURN)
            visitMaxs(2, 2)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun staticFieldPointTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "StaticFieldPointTarget", null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC, "name", "Ljava/lang/String;", null, null).visitEnd()
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "readName", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitFieldInsn(Opcodes.GETSTATIC, "StaticFieldPointTarget", "name", "Ljava/lang/String;")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 0)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "writeName", "(Ljava/lang/String;)V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.PUTSTATIC, "StaticFieldPointTarget", "name", "Ljava/lang/String;")
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun fieldParamTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "FieldParamTarget", null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE, "name", "Ljava/lang/String;", null, null).visitEnd()
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "readName", "(Ljava/lang/String;I)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "FieldParamTarget", "name", "Ljava/lang/String;")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 3)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "writeName", "(Ljava/lang/String;Ljava/lang/String;I)V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitFieldInsn(Opcodes.PUTFIELD, "FieldParamTarget", "name", "Ljava/lang/String;")
            visitInsn(Opcodes.RETURN)
            visitMaxs(2, 4)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun staticFieldParamTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "StaticFieldParamTarget", null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC, "name", "Ljava/lang/String;", null, null).visitEnd()
        addDefaultConstructor(cw)
        cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "readName",
            "(Ljava/lang/String;I)Ljava/lang/String;",
            null,
            null,
        ).apply {
            visitCode()
            visitFieldInsn(Opcodes.GETSTATIC, "StaticFieldParamTarget", "name", "Ljava/lang/String;")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 2)
            visitEnd()
        }
        cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "writeName",
            "(Ljava/lang/String;Ljava/lang/String;I)V",
            null,
            null,
        ).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.PUTSTATIC, "StaticFieldParamTarget", "name", "Ljava/lang/String;")
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 3)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun arrayAccessTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "ArrayAccessTarget", null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE, "names", "[Ljava/lang/String;", null, null).visitEnd()
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitInsn(Opcodes.ICONST_1)
            visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
            visitInsn(Opcodes.DUP)
            visitInsn(Opcodes.ICONST_0)
            visitLdcInsn("raw")
            visitInsn(Opcodes.AASTORE)
            visitFieldInsn(Opcodes.PUTFIELD, "ArrayAccessTarget", "names", "[Ljava/lang/String;")
            visitInsn(Opcodes.RETURN)
            visitMaxs(5, 1)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "readName", "(I)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "ArrayAccessTarget", "names", "[Ljava/lang/String;")
            visitVarInsn(Opcodes.ILOAD, 1)
            visitInsn(Opcodes.AALOAD)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 2)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "writeName", "(ILjava/lang/String;)V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "ArrayAccessTarget", "names", "[Ljava/lang/String;")
            visitVarInsn(Opcodes.ILOAD, 1)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitInsn(Opcodes.AASTORE)
            visitInsn(Opcodes.RETURN)
            visitMaxs(3, 3)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "nameCount", "()I", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "ArrayAccessTarget", "names", "[Ljava/lang/String;")
            visitInsn(Opcodes.ARRAYLENGTH)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "nameCount", "(I)I", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "ArrayAccessTarget", "names", "[Ljava/lang/String;")
            visitInsn(Opcodes.ARRAYLENGTH)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(1, 2)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun sliceArrayExpressionValueTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "SliceArrayExpressionValueTarget", null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE, "names", "[Ljava/lang/String;", null, null).visitEnd()
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitInsn(Opcodes.ICONST_1)
            visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
            visitInsn(Opcodes.DUP)
            visitInsn(Opcodes.ICONST_0)
            visitLdcInsn("raw")
            visitInsn(Opcodes.AASTORE)
            visitFieldInsn(Opcodes.PUTFIELD, "SliceArrayExpressionValueTarget", "names", "[Ljava/lang/String;")
            visitInsn(Opcodes.RETURN)
            visitMaxs(5, 1)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "readSelected", "(I)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "SliceArrayExpressionValueTarget", "names", "[Ljava/lang/String;")
            visitVarInsn(Opcodes.ILOAD, 1)
            visitInsn(Opcodes.AALOAD)
            visitInsn(Opcodes.POP)
            visitLdcInsn(" start ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "SliceArrayExpressionValueTarget", "names", "[Ljava/lang/String;")
            visitVarInsn(Opcodes.ILOAD, 1)
            visitInsn(Opcodes.AALOAD)
            visitVarInsn(Opcodes.ASTORE, 2)
            visitLdcInsn(" end ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 3)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "countSelected", "()I", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "SliceArrayExpressionValueTarget", "names", "[Ljava/lang/String;")
            visitInsn(Opcodes.ARRAYLENGTH)
            visitInsn(Opcodes.POP)
            visitLdcInsn(" start ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "SliceArrayExpressionValueTarget", "names", "[Ljava/lang/String;")
            visitInsn(Opcodes.ARRAYLENGTH)
            visitVarInsn(Opcodes.ISTORE, 1)
            visitLdcInsn(" end ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitVarInsn(Opcodes.ILOAD, 1)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(1, 2)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun primitiveArrayAccessTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "PrimitiveArrayAccessTarget", null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE, "scores", "[I", null, null).visitEnd()
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitInsn(Opcodes.ICONST_1)
            visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT)
            visitInsn(Opcodes.DUP)
            visitInsn(Opcodes.ICONST_0)
            visitIntInsn(Opcodes.BIPUSH, 40)
            visitInsn(Opcodes.IASTORE)
            visitFieldInsn(Opcodes.PUTFIELD, "PrimitiveArrayAccessTarget", "scores", "[I")
            visitInsn(Opcodes.RETURN)
            visitMaxs(5, 1)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "readScore", "(I)I", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "PrimitiveArrayAccessTarget", "scores", "[I")
            visitVarInsn(Opcodes.ILOAD, 1)
            visitInsn(Opcodes.IALOAD)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(2, 2)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "writeScore", "(II)V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "PrimitiveArrayAccessTarget", "scores", "[I")
            visitVarInsn(Opcodes.ILOAD, 1)
            visitVarInsn(Opcodes.ILOAD, 2)
            visitInsn(Opcodes.IASTORE)
            visitInsn(Opcodes.RETURN)
            visitMaxs(3, 3)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun arrayParamTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "ArrayParamTarget", null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE, "names", "[Ljava/lang/String;", null, null).visitEnd()
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitInsn(Opcodes.ICONST_1)
            visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String")
            visitInsn(Opcodes.DUP)
            visitInsn(Opcodes.ICONST_0)
            visitLdcInsn("raw")
            visitInsn(Opcodes.AASTORE)
            visitFieldInsn(Opcodes.PUTFIELD, "ArrayParamTarget", "names", "[Ljava/lang/String;")
            visitInsn(Opcodes.RETURN)
            visitMaxs(5, 1)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "readName", "(ILjava/lang/String;)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "ArrayParamTarget", "names", "[Ljava/lang/String;")
            visitVarInsn(Opcodes.ILOAD, 1)
            visitInsn(Opcodes.AALOAD)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 3)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "writeName", "(ILjava/lang/String;Ljava/lang/String;)V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "ArrayParamTarget", "names", "[Ljava/lang/String;")
            visitVarInsn(Opcodes.ILOAD, 1)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitInsn(Opcodes.AASTORE)
            visitInsn(Opcodes.RETURN)
            visitMaxs(3, 4)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun multiFieldReadTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "MultiFieldReadTarget", null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE, "name", "Ljava/lang/String;", null, null).visitEnd()
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "readTwice", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "MultiFieldReadTarget", "name", "Ljava/lang/String;")
            visitInsn(Opcodes.POP)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "MultiFieldReadTarget", "name", "Ljava/lang/String;")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "writeName", "(Ljava/lang/String;)V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitFieldInsn(Opcodes.PUTFIELD, "MultiFieldReadTarget", "name", "Ljava/lang/String;")
            visitInsn(Opcodes.RETURN)
            visitMaxs(2, 2)
            visitEnd()
        }
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

    private fun throwPointTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "ThrowPointTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "fail", "()V", null, null).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException")
            visitInsn(Opcodes.DUP)
            visitLdcInsn("failed")
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false)
            visitInsn(Opcodes.ATHROW)
            visitMaxs(3, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun sliceThrowInstructionTargetBytes(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "SliceThrowInstructionTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "failSelected", "()V", null, null).apply {
            val insideThrow = org.objectweb.asm.Label()
            visitCode()
            visitInsn(Opcodes.ICONST_0)
            visitJumpInsn(Opcodes.IFEQ, insideThrow)
            visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException")
            visitInsn(Opcodes.DUP)
            visitLdcInsn("outside")
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false)
            visitInsn(Opcodes.ATHROW)
            visitLabel(insideThrow)
            visitLdcInsn(" start ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException")
            visitInsn(Opcodes.DUP)
            visitLdcInsn("inside")
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false)
            visitInsn(Opcodes.ATHROW)
            visitMaxs(0, 0)
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

    private fun constructorInvokerTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "ConstructorInvokerTarget", null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE, "value", "Ljava/lang/String;", null, null).visitEnd()
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/String;)V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitFieldInsn(Opcodes.PUTFIELD, "ConstructorInvokerTarget", "value", "Ljava/lang/String;")
            visitInsn(Opcodes.RETURN)
            visitMaxs(2, 2)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "ConstructorInvokerTarget", "value", "Ljava/lang/String;")
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
            visitInsn(Opcodes.NOP)
            visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder")
            visitInsn(Opcodes.DUP)
            visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "java/lang/StringBuilder",
                "<init>",
                "()V",
                false,
            )
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun sliceNewExpressionValueTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "SliceNewExpressionValueTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "createSelected", "()Ljava/lang/StringBuilder;", null, null).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder")
            visitInsn(Opcodes.DUP)
            visitLdcInsn("outside")
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false)
            visitInsn(Opcodes.POP)
            visitLdcInsn(" start ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder")
            visitInsn(Opcodes.DUP)
            visitLdcInsn("inside")
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false)
            visitVarInsn(Opcodes.ASTORE, 1)
            visitLdcInsn(" end ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(3, 2)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun newParamTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "NewParamTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(
            Opcodes.ACC_PUBLIC,
            "create",
            "(Ljava/lang/String;I)Ljava/lang/StringBuilder;",
            null,
            null,
        ).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder")
            visitInsn(Opcodes.DUP)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 3)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun multiNewTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "MultiNewTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder")
            visitInsn(Opcodes.DUP)
            visitLdcInsn("first")
            visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "java/lang/StringBuilder",
                "<init>",
                "(Ljava/lang/String;)V",
                false,
            )
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "toString",
                "()Ljava/lang/String;",
                false,
            )
            visitLdcInsn(":")
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/String",
                "concat",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false,
            )
            visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder")
            visitInsn(Opcodes.DUP)
            visitLdcInsn("second")
            visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "java/lang/StringBuilder",
                "<init>",
                "(Ljava/lang/String;)V",
                false,
            )
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "toString",
                "()Ljava/lang/String;",
                false,
            )
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/String",
                "concat",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false,
            )
            visitInsn(Opcodes.ARETURN)
            visitMaxs(3, 1)
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

    private fun sliceCastInstructionTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "SliceCastInstructionTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "castSelected", "(Ljava/lang/Object;)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 1)
            visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String")
            visitInsn(Opcodes.POP)
            visitLdcInsn(" start ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String")
            visitVarInsn(Opcodes.ASTORE, 2)
            visitLdcInsn(" end ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 3)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun instanceofTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "InstanceofTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "isString", "(Ljava/lang/Object;Z)Z", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 1)
            visitTypeInsn(Opcodes.INSTANCEOF, "java/lang/String")
            visitInsn(Opcodes.IRETURN)
            visitMaxs(1, 3)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun sliceInstanceofExpressionValueTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "SliceInstanceofExpressionValueTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "isSelected", "(Ljava/lang/Object;)Z", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 1)
            visitTypeInsn(Opcodes.INSTANCEOF, "java/lang/String")
            visitInsn(Opcodes.POP)
            visitLdcInsn(" start ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitTypeInsn(Opcodes.INSTANCEOF, "java/lang/String")
            visitVarInsn(Opcodes.ISTORE, 2)
            visitLdcInsn(" end ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitVarInsn(Opcodes.ILOAD, 2)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(1, 3)
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

    private fun shadowAliasTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "ShadowAliasTarget", null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE, "actualName", "Ljava/lang/String;", null, null).visitEnd()
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitLdcInsn("seed")
            visitFieldInsn(Opcodes.PUTFIELD, "ShadowAliasTarget", "actualName", "Ljava/lang/String;")
            visitInsn(Opcodes.RETURN)
            visitMaxs(2, 1)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("original")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PRIVATE, "actualLookup", "(Ljava/lang/String;)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("actual:")
            visitVarInsn(Opcodes.ALOAD, 1)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 2)
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

    private fun sliceConstantTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "SliceConstantTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("target")
            visitVarInsn(Opcodes.ASTORE, 1)
            visitLdcInsn(" start ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitLdcInsn("target")
            visitVarInsn(Opcodes.ASTORE, 2)
            visitLdcInsn(" end ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitLdcInsn("target")
            visitVarInsn(Opcodes.ASTORE, 3)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitLdcInsn(":")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitLdcInsn(":")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 3)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 4)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun constantParamTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "ConstantParamTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "(Ljava/lang/String;I)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("base-")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 3)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun staticConstantParamTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "StaticConstantParamTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "value",
            "(Ljava/lang/String;I)Ljava/lang/String;",
            null,
            null,
        ).apply {
            visitCode()
            visitLdcInsn("static-")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 2)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun nullConstantTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "NullConstantTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/Object;", null, null).apply {
            visitCode()
            visitInsn(Opcodes.ACONST_NULL)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun bipushConstantTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "BipushConstantTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()I", null, null).apply {
            visitCode()
            visitIntInsn(Opcodes.BIPUSH, 7)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun sipushConstantTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "SipushConstantTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()I", null, null).apply {
            visitCode()
            visitIntInsn(Opcodes.SIPUSH, 300)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun multiIntConstantTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "MultiIntConstantTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()I", null, null).apply {
            visitCode()
            visitIntInsn(Opcodes.BIPUSH, 7)
            visitVarInsn(Opcodes.ISTORE, 1)
            visitIntInsn(Opcodes.BIPUSH, 7)
            visitVarInsn(Opcodes.ISTORE, 2)
            visitVarInsn(Opcodes.ILOAD, 1)
            visitVarInsn(Opcodes.ILOAD, 2)
            visitInsn(Opcodes.IADD)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(2, 3)
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

    private fun handlerCallIndex(
        instructions: Array<org.objectweb.asm.tree.AbstractInsnNode>,
        owner: Class<*>,
        methodName: String,
    ): Int {
        val ownerName = org.objectweb.asm.Type.getInternalName(owner)
        return instructions.indexOfFirst {
            it is org.objectweb.asm.tree.MethodInsnNode &&
                it.owner == ownerName &&
                it.name == methodName
        }
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




