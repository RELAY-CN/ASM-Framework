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
import kim.der.asm.api.annotation.CallbackInfoReturnable
import kim.der.asm.api.annotation.Group
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
import kim.der.asm.api.annotation.Final
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
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
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
    fun legacyRedirectionManagerAndListenerApisAreRemoved() {
        val sourceRoot = Path.of("src", "main", "kotlin")
        val sources =
            Files
                .walk(sourceRoot)
                .use { paths ->
                    paths
                        .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                        .map { path -> path to Files.readString(path) }
                        .toList()
                }

        val removedFiles =
            listOf(
                Path.of("kim", "der", "asm", "api", "listener", "RedirectionListener.kt"),
                Path.of("kim", "der", "asm", "api", "replace", "RedirectionReplace.kt"),
                Path.of("kim", "der", "asm", "data", "MethodTypeInfoValue.kt"),
                Path.of("kim", "der", "asm", "injector", "impl", "RedirectionReplaceApi.kt"),
                Path.of("kim", "der", "asm", "injector", "impl", "RedirectionReplaceManager.kt"),
                Path.of("kim", "der", "asm", "injector", "impl", "RedirectionManagerImpl.kt"),
                Path.of("kim", "der", "asm", "injector", "impl", "RedirectionIgnoreManagerImpl.kt"),
                Path.of("kim", "der", "asm", "injector", "impl", "AbstractRedirectionManagerImpl.kt"),
                Path.of("kim", "der", "asm", "AsmListener.kt"),
                Path.of("kim", "der", "asm", "AsmReplace.kt"),
            )
        val remainingRemovedFiles =
            sources
                .map { (path, _) -> sourceRoot.relativize(path) }
                .filter { relative -> removedFiles.any(relative::endsWith) }
                .map { it.toString() }
                .sorted()

        assertEquals(emptyList<String>(), remainingRemovedFiles)

        val forbiddenText =
            listOf(
                "RedirectionReplaceApi",
                "RedirectionReplaceManager",
                "RedirectionManagerImpl",
                "RedirectionIgnoreManagerImpl",
                "RedirectionListener",
                "MethodTypeInfoValue",
                "AsmListener",
                "AsmReplace",
                "api.replace.RedirectionReplace",
                "api.listener.RedirectionListener",
            )

        forbiddenText.forEach { forbidden ->
            val offenders =
                sources
                    .filter { (_, text) -> forbidden in text }
                    .map { (path, _) -> path.toString() }
                    .sorted()

            assertEquals(emptyList<String>(), offenders, "$forbidden should not remain in source")
        }

        val defaultValueSource =
            sources.single { (path, _) ->
                path.endsWith(Path.of("kim", "der", "asm", "injector", "impl", "DefaultReturnValueProvider.kt"))
            }

        assertEquals(true, "package kim.der.asm.injector.impl" in defaultValueSource.second)
    }

    @Test
    @DisplayName("公开文档应保持数组定位与条件包裹注入点契约一致")
    fun documentationContractsKeepAnnotationPointMappingsAligned() {
        // Given
        val api = Files.readString(Path.of("API.md"))
        val guide = Files.readString(Path.of("GUIDE.md"))
        val asmMixinKDoc =
            Files.readString(Path.of("src", "main", "kotlin", "kim", "der", "asm", "api", "annotation", "AsmMixin.kt"))
        val asmInjectKDoc =
            Files.readString(Path.of("src", "main", "kotlin", "kim", "der", "asm", "api", "annotation", "AsmInject.kt"))
        val redirectArraySection =
            api
                .substringAfter("`@Redirect` 可在")
                .substringBefore("`@WrapOperation`")
        val redirectParameterSection =
            api
                .substringAfter("### @Redirect")
                .substringBefore("### @RedirectAllMethods")
        val redirectKDocSection =
            asmMixinKDoc
                .substringAfter("* 重定向方法调用、")
                .substringBefore("annotation class Redirect")
        val atRedirectArraySection =
            asmInjectKDoc
                .substringAfter("* - [Redirect] 可通过")
                .substringBefore("* - [WrapOperation]")
        val wrapOperationSupportIntro =
            asmMixinKDoc
                .substringAfter("* 包裹原始操作注解。")
                .substringBefore("annotation class WrapOperation")
                .substringAfter("* 当前实现支持")
                .substringBefore("* [InjectionPoint.INVOKE] 省略")
        val wrapConditionSliceSummary =
            guide
                .substringAfter("引用类型参数可使用精确类型")
                .substringBefore("\n\n### 场景 10")

        // Then
        assertThat(redirectArraySection)
            .`as`("Then: @Redirect 数组写入必须绑定 FIELD_ASSIGN，避免把 array=set 误导为 FIELD 定位")
            .contains("`FIELD_ASSIGN` 目标上使用 `args = [\"array=set\"]`")
            .doesNotContain("`FIELD` 目标上使用 `args = [\"array=get\"]`、`args = [\"array=set\"]`")
        assertThat(redirectParameterSection)
            .`as`("Then: @Redirect 参数说明也必须区分 FIELD 数组读取/长度与 FIELD_ASSIGN 数组写入")
            .contains(
                "`FIELD` 时按字段读取语义匹配，配合 `at.args = [\"array=get\"]` / `[\"array=length\"]` 可匹配数组元素读取或数组长度读取",
                "`FIELD_ASSIGN` 时按字段写入语义匹配，配合 `at.args = [\"array=set\"]` 可匹配数组元素写入",
            )
            .doesNotContain("`FIELD` 时按字段读取语义匹配，配合 `at.args = [\"array=get\"]` / `[\"array=set\"]`")
        assertThat(redirectKDocSection)
            .`as`("Then: @Redirect KDoc 应避免把 array=set 描述成 FIELD 数组定位")
            .contains(
                "数组元素读取与数组长度重定向通过 [InjectionPoint.FIELD] 与",
                "`array=get` / `array=length` 指定",
                "数组元素写入重定向通过 [InjectionPoint.FIELD_ASSIGN] 与 `array=set`",
            )
            .doesNotContain("`array=get`、`array=set` 或 `array=length` 区分读取、写入与长度读取")
        assertThat(atRedirectArraySection)
            .`as`("Then: At KDoc 的 Redirect 数组 args 说明应保持同一归属")
            .contains(
                "[InjectionPoint.FIELD] 与 `array=get` / `array=length`",
                "[InjectionPoint.FIELD_ASSIGN] 与 `array=set`",
            )
            .doesNotContain("把 [InjectionPoint.FIELD] 目标解释为数组元素读取、写入或数组长度读取")
        assertThat(wrapOperationSupportIntro)
            .`as`("Then: @WrapOperation KDoc 的支持范围首段应覆盖当前全部可包裹表达式")
            .contains(
                "[InjectionPoint.NEW]",
                "[InjectionPoint.CAST]",
                "[InjectionPoint.INSTANCEOF]",
                "[InjectionPoint.LOAD]",
                "[InjectionPoint.STORE]",
                "[InjectionPoint.JUMP]",
                "[InjectionPoint.SWITCH]",
                "[InjectionPoint.CONSTANT]",
                "[InjectionPoint.THROW]",
            )
        assertThat(wrapConditionSliceSummary)
            .`as`("Then: GUIDE 的 Slice 总结应使用明确 InjectionPoint 名称并说明数组模式归属")
            .contains(
                "`INVOKE`",
                "`INVOKE_ASSIGN`",
                "`FIELD`",
                "`FIELD_ASSIGN`",
                "`LOAD`",
                "`STORE`",
                "`CONSTANT`",
                "`JUMP`",
                "`SWITCH`",
                "`THROW`",
                "`array=set` 跟随 `FIELD_ASSIGN`",
            )
    }

    @Test
    @DisplayName("公开文档应保持 ModifyReceiver 省略 method 与 target 的推断契约一致")
    fun documentationContractsKeepModifyReceiverInferenceAligned() {
        // Given
        val api = Files.readString(Path.of("API.md"))
        val guide = Files.readString(Path.of("GUIDE.md"))
        val asmMixinKDoc =
            Files.readString(Path.of("src", "main", "kotlin", "kim", "der", "asm", "api", "annotation", "AsmMixin.kt"))
        val asmInjectKDoc =
            Files.readString(Path.of("src", "main", "kotlin", "kim", "der", "asm", "api", "annotation", "AsmInject.kt"))
        val apiModifyReceiverSection =
            api
                .substringAfter("### @ModifyReceiver")
                .substringBefore("### @WrapOperation")
        val guideModifyReceiverSection =
            guide
                .substringAfter("`@ModifyReceiver` 用于只替换")
                .substringBefore("`@WrapOperation` 用于")
        val kdocModifyReceiverSection =
            asmMixinKDoc
                .substringAfter("* 修改调用 receiver 注解。")
                .substringBefore("annotation class ModifyReceiver")
        val atKDocSection =
            asmInjectKDoc
                .substringAfter("* 调用点定位信息。")
                .substringBefore("annotation class At")

        // Then
        assertThat(apiModifyReceiverSection)
            .`as`("Then: API 应说明 method 省略和三类 target 省略都按兼容 receiver 候选推断")
            .contains(
                "省略 `method`",
                "`At.target` 为空的 `INVOKE`、`FIELD` 或 `FIELD_ASSIGN`",
                "不兼容候选不计入",
                "字段写入会保留原待写入值并写入新的 receiver",
            )
        assertThat(guideModifyReceiverSection)
            .`as`("Then: GUIDE 应用业务语言说明三类 receiver 推断和无 receiver 候选的失败边界")
            .contains(
                "省略 `method`",
                "省略 `INVOKE`、`FIELD` 或 `FIELD_ASSIGN` 目标",
                "静态调用、构造器调用和不兼容实例调用不计入",
                "静态字段和不兼容字段读取不计入",
                "`FIELD_ASSIGN` 会把原待写入值写到新 receiver",
            )
        assertThat(kdocModifyReceiverSection)
            .`as`("Then: ModifyReceiver KDoc 应把 public API 的省略推断边界同步给 IDE 用户")
            .contains(
                "可省略 [method]",
                "[At.target] 为空时会使用实际可兼容的 receiver 候选参与推断",
                "静态调用、构造器调用和 handler 不兼容的实例调用不计入 [ordinal] 或命中数",
                "静态字段和 handler 不兼容的字段读取不计入 [ordinal] 或命中数",
                "静态字段和 handler 不兼容的字段写入不计入 [ordinal] 或命中数",
            )
        assertThat(atKDocSection)
            .`as`("Then: 通用 At KDoc 只说明显式 target 格式，不应覆盖 ModifyReceiver 的省略推断例外")
            .contains("FIELD/FIELD_ASSIGN 目标格式为 `Owner.field:Desc`，owner 与 desc 均可省略。")
    }

    @Test
    @DisplayName("公开文档应保持 ModifyExpressionValue 常量表达式契约一致")
    fun documentationContractsKeepModifyExpressionValueConstantSupportAligned() {
        // Given
        val api = Files.readString(Path.of("API.md"))
        val injectorKDoc =
            Files.readString(
                Path.of("src", "main", "kotlin", "kim", "der", "asm", "injector", "impl", "ModifyExpressionValueInjector.kt"),
            )
        val apiModifyExpressionValueIntro =
            api
                .substringAfter("### @ModifyExpressionValue")
                .substringBefore("**参数：**")
        val injectorIntro =
            injectorKDoc
                .substringAfter("* ModifyExpressionValue 注入器。")
                .substringBefore("* @param at")
        val injectorAtParameter =
            injectorKDoc
                .substringAfter("* @param at 表达式定位；")
                .substringBefore("* @param ordinal")
        val injectorSliceParameter =
            injectorKDoc
                .substringAfter("* @param slice 切片范围；")
                .substringBefore("* @author Dr")

        // Then
        assertThat(apiModifyExpressionValueIntro)
            .`as`("Then: API 首段应列出 CONSTANT 产生的常量表达式值，避免公开能力与实现支持脱节")
            .contains("常量表达式值")
        assertThat(injectorIntro)
            .`as`("Then: 注入器 KDoc 总述应把常量加载纳入表达式改写范围")
            .contains("常量表达式值")
        assertThat(injectorAtParameter)
            .`as`("Then: 注入器 at 参数 KDoc 应显式列出 CONSTANT 定位点")
            .contains("[InjectionPoint.CONSTANT]")
        assertThat(injectorSliceParameter)
            .`as`("Then: 注入器 slice 参数 KDoc 应说明 CONSTANT 也支持 INVOKE 边界切片")
            .contains("[InjectionPoint.CONSTANT]")
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
    fun asmMixinDefaultPriorityIsCompatibleWithMixinStyleDefault() {
        val annotation = DefaultPriorityMixin::class.java.getAnnotation(AsmMixin::class.java)

        assertEquals(1000, annotation.priority)
    }

    @Test
    fun registryOrdersExactMixinsByPriorityBeforeRegistrationOrder() {
        AsmRegistry.register(LowPriorityExactMixin::class.java)
        AsmRegistry.register(HighPriorityExactMixin::class.java)

        val mixins = AsmRegistry.getForTarget("PriorityTarget").map { it.asmClass }

        assertEquals(listOf(HighPriorityExactMixin::class.java, LowPriorityExactMixin::class.java), mixins)
    }

    @Test
    fun registryKeepsRegistrationOrderWhenPriorityTies() {
        AsmRegistry.register(FirstTiePriorityMixin::class.java)
        AsmRegistry.register(SecondTiePriorityMixin::class.java)

        val mixins = AsmRegistry.getForTarget("PriorityTarget").map { it.asmClass }

        assertEquals(listOf(FirstTiePriorityMixin::class.java, SecondTiePriorityMixin::class.java), mixins)
    }

    @Test
    fun registryOrdersPathMatcherMixinsByPriorityWithinPathGroup() {
        AsmRegistry.registerWithPathMatcher(LowPriorityPathMixin::class.java) { it == "PriorityTarget" }
        AsmRegistry.registerWithPathMatcher(HighPriorityPathMixin::class.java) { it == "PriorityTarget" }

        val mixins = AsmRegistry.getForTarget("PriorityTarget").map { it.asmClass }

        assertEquals(listOf(HighPriorityPathMixin::class.java, LowPriorityPathMixin::class.java), mixins)
    }

    @Test
    fun registryKeepsPathGroupBeforeExactGroupEvenWhenExactPriorityIsHigher() {
        AsmRegistry.registerWithPathMatcher(LowPriorityPathMixin::class.java) { it == "PriorityTarget" }
        AsmRegistry.register(HighPriorityExactMixin::class.java)

        val mixins = AsmRegistry.getForTarget("PriorityTarget").map { it.asmClass }

        assertEquals(listOf(LowPriorityPathMixin::class.java, HighPriorityExactMixin::class.java), mixins)
    }

    @Test
    fun pathMatcherWithoutAsmMixinUsesDefaultPriority() {
        AsmRegistry.registerWithPathMatcher(LowPriorityPathMixin::class.java) { it == "PriorityTarget" }
        AsmRegistry.registerWithPathMatcher(UnannotatedPathMixin::class.java) { it == "PriorityTarget" }

        val mixins = AsmRegistry.getForTarget("PriorityTarget").map { it.asmClass }

        assertEquals(listOf(UnannotatedPathMixin::class.java, LowPriorityPathMixin::class.java), mixins)
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
    fun redirectVoidMethodCallWithNonVoidHandlerFailsDuringTransform() {
        AsmRegistry.register(NonVoidRedirectForVoidCallMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("WrapConditionStaticTarget", wrapConditionStaticTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("return type mismatch") == true,
        )
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
    fun injectionPointExposesConstantExpressionPoint() {
        val names = InjectionPoint.entries.map { it.name }

        assertEquals(true, "CONSTANT" in names)
    }

    @Test
    fun injectionPointExposesJumpInstructionPoint() {
        val names = InjectionPoint.entries.map { it.name }

        assertEquals(true, "JUMP" in names)
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
    fun nonCancellableHeadCancelFailsDuringInvocation() {
        AsmRegistry.register(NonCancellableHeadCancelMixin::class.java)

        val transformed = AsmProcessor().transform("ReturnTarget", returnTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ReturnTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val exception =
            assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
                clazz.getMethod("value").invoke(instance)
            }

        assertEquals(true, exception.cause is IllegalStateException)
        assertEquals(true, exception.cause?.message?.contains("not cancellable") == true)
    }

    @Test
    fun cancellableHeadCancelReturnsCallbackValue() {
        AsmRegistry.register(CancellableHeadCancelReturnMixin::class.java)

        val transformed = AsmProcessor().transform("ReturnTarget", returnTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ReturnTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("cancelled", result)
    }

    @Test
    fun cancellableHeadSetReturnValueReturnsCallbackValue() {
        AsmRegistry.register(CancellableHeadSetReturnValueMixin::class.java)

        val transformed = AsmProcessor().transform("ReturnTarget", returnTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ReturnTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("set-only", result)
    }

    @Test
    fun cancellableHeadSupportsCallbackInfoReturnable() {
        AsmRegistry.register(CancellableHeadCallbackInfoReturnableMixin::class.java)

        val transformed = AsmProcessor().transform("ReturnTarget", returnTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ReturnTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("typed-head", result)
    }

    @Test
    fun returnInjectionCanReplaceReferenceReturnValueWithNull() {
        AsmRegistry.register(ReturnSetNullMixin::class.java)

        val transformed = AsmProcessor().transform("ReturnTarget", returnTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ReturnTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals(null, result)
    }

    @Test
    fun returnInjectionSupportsCallbackInfoReturnable() {
        AsmRegistry.register(ReturnCallbackInfoReturnableMixin::class.java)

        val transformed = AsmProcessor().transform("ReturnTarget", returnTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ReturnTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("value-typed", result)
    }

    @Test
    fun tailInjectionSupportsCallbackInfoReturnable() {
        AsmRegistry.register(TailCallbackInfoReturnableMixin::class.java)

        val transformed = AsmProcessor().transform("ReturnTarget", returnTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ReturnTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("value-tail", result)
    }

    @Test
    fun cancellableTailSetReturnValueReturnsCallbackValue() {
        AsmRegistry.register(CancellableTailSetReturnValueMixin::class.java)

        val transformed = AsmProcessor().transform("ReturnTarget", returnTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ReturnTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("tail-cancelled", result)
    }

    @Test
    fun callbackInfoReturnableSupportsTypedSetAndPrimitiveGetters() {
        val callback = CallbackInfoReturnable(41, cancellable = true)

        callback.setTypedReturnValue(callback.getReturnValueI() + 1)

        assertEquals(42, callback.getTypedReturnValue())
        assertEquals(42, callback.getReturnValueI())
        assertEquals(42L, callback.getReturnValueJ())
        assertEquals(42.toChar(), callback.getReturnValueC())
        assertEquals(true, callback.isCancelled())
    }

    @Test
    fun callbackInfoReturnableSupportsValueProperty() {
        val callback = CallbackInfoReturnable("raw", cancellable = true)

        callback.value = "${callback.value}-property"

        assertEquals("raw-property", callback.getTypedReturnValue())
        assertEquals(true, callback.isCancelled())
    }

    @Test
    fun callbackInfoExposesCancellableState() {
        val cancellable = CallbackInfo(cancellable = true)
        val nonCancellable = CallbackInfo()

        assertEquals(true, cancellable.isCancellable())
        assertEquals(false, nonCancellable.isCancellable())
    }

    @Test
    fun argsSetAllRejectsMismatchedValueCountWithoutPartialWrite() {
        val args = Args(arrayOf("old", "value"))

        assertThrows(IllegalArgumentException::class.java) {
            args.setAll("new")
        }

        assertEquals("old", args.get<String>(0))
        assertEquals("value", args.get<String>(1))
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
    fun modifyReturnValueAcceptsObjectHandlerParameter() {
        AsmRegistry.register(ModifyReturnValueObjectParameterMixin::class.java)

        val transformed = AsmProcessor().transform("ReturnTarget", returnTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ReturnTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("value-any", result)
    }

    @Test
    fun modifyReturnValueAcceptsAssignableParentParameter() {
        AsmRegistry.register(ModifyReturnValueAssignableParentMixin::class.java)

        val transformed = AsmProcessor().transform("ReturnTarget", returnTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ReturnTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("value-parent", result)
    }

    @Test
    fun modifyReturnValueAcceptsAssignableObjectReturnType() {
        AsmRegistry.register(ModifyReturnValueAssignableReturnMixin::class.java)

        val transformed = AsmProcessor().transform(
            "CharSequenceReturnTarget",
            charSequenceReturnTargetBytes(),
            javaClass.classLoader,
        )
        val clazz = loadClass("CharSequenceReturnTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("value-subtype", result)
    }

    @Test
    fun modifyReturnValueAcceptsGenericObjectReturnType() {
        AsmRegistry.register(ModifyReturnValueGenericReturnMixin::class.java)

        val transformed = AsmProcessor().transform("ReturnTarget", returnTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ReturnTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("value-object", result)
    }

    @Test
    fun modifyReturnValueAcceptsZeroParameterInstanceHandler() {
        AsmRegistry.register(ModifyReturnValueZeroParameterInstanceMixin::class.java)

        val transformed = AsmProcessor().transform("ReturnTarget", returnTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ReturnTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("constant", result)
    }

    @Test
    fun modifyReturnValueCanUseTargetMethodParameters() {
        AsmRegistry.register(ModifyReturnValueWithTargetParamsMixin::class.java)

        val transformed = AsmProcessor().transform("RedirectParamTarget", redirectParamTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("RedirectParamTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value", String::class.java, Int::class.javaPrimitiveType).invoke(instance, "suffix", 4)

        assertEquals("base-suffix4", result)
    }

    @Test
    fun modifyReturnValueInfersTargetWhenMethodIsOmitted() {
        AsmRegistry.register(InferredModifyReturnValueMixin::class.java)

        val transformed = AsmProcessor().transform("ReturnTarget", returnTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ReturnTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("value-inferred", result)
    }

    @Test
    fun modifyReturnValueInfersTargetFromOrdinalCandidateWhenMethodIsOmitted() {
        AsmRegistry.register(InferredOrdinalModifyReturnValueMixin::class.java)

        val transformed = AsmProcessor().transform(
            "OrdinalReturnInferenceTarget",
            ordinalReturnInferenceTargetBytes(),
            javaClass.classLoader,
        )
        val clazz = loadClass("OrdinalReturnInferenceTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        assertEquals("single", clazz.getMethod("value").invoke(instance))
        assertEquals("first", clazz.getMethod("value", Boolean::class.javaPrimitiveType).invoke(instance, true))
        assertEquals("ordinal-second", clazz.getMethod("value", Boolean::class.javaPrimitiveType).invoke(instance, false))
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
    fun modifyReturnValueSliceLimitsReturnsBetweenFromAndTo() {
        AsmRegistry.register(ModifyReturnValueSliceMixin::class.java)

        val transformed = AsmProcessor().transform("SliceReturnValueTarget", sliceReturnValueTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("SliceReturnValueTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val method = clazz.getMethod("value", Int::class.javaPrimitiveType)

        assertEquals("before", method.invoke(instance, 0))
        assertEquals("modified-inside", method.invoke(instance, 1))
        assertEquals("after", method.invoke(instance, 2))
    }

    @Test
    fun modifyReturnValueSliceSupportsInvokeDynamicBoundaries() {
        AsmRegistry.register(ModifyReturnValueInvokeDynamicSliceMixin::class.java)

        val transformed =
            AsmProcessor().transform(
                "InvokeDynamicSliceReturnValueTarget",
                invokeDynamicSliceReturnValueTargetBytes(),
                javaClass.classLoader,
            )
        val clazz = loadClass("InvokeDynamicSliceReturnValueTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val method = clazz.getMethod("value", Int::class.javaPrimitiveType, String::class.java)

        assertEquals("before", method.invoke(instance, 0, "marker"))
        assertEquals("modified-inside", method.invoke(instance, 1, "marker"))
        assertEquals("after", method.invoke(instance, 2, "marker"))
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
    fun invokeReplaceAtMethodCallReplacesCall() {
        AsmRegistry.register(InvokeReplaceTrimMixin::class.java)

        val transformed = AsmProcessor().transform("RedirectTarget", redirectTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("RedirectTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("call").invoke(instance)

        assertEquals("replaced-trim", result)
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
    fun cancellableInvokeBeforeReturnsCallbackValue() {
        AsmRegistry.register(CancellableInvokeBeforeMixin::class.java)

        val transformed = AsmProcessor().transform("RedirectTarget", redirectTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("RedirectTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("call").invoke(instance)

        assertEquals("invoke-cancelled", result)
    }

    @Test
    fun cancellableInvokeAssignAfterReturnsCallbackValue() {
        AsmRegistry.register(CancellableInvokeAssignAfterMixin::class.java)

        val transformed = AsmProcessor().transform("RedirectTarget", redirectTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("RedirectTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("call").invoke(instance)

        assertEquals("invoke-assign-cancelled", result)
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
    fun headInjectionDropsUnusedHandlerReturnValueOnNonVoidTarget() {
        AsmRegistry.register(HeadReturningHandlerOnReturnTargetMixin::class.java)

        val transformed = AsmProcessor().transform("ReturnTarget", returnTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ReturnTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("value", result)
    }

    @Test
    fun headInjectionEmitsPopForUnusedHandlerReturnValueOnNonVoidTarget() {
        AsmRegistry.register(HeadReturningHandlerOnReturnTargetMixin::class.java)

        val transformed = AsmProcessor().transform("ReturnTarget", returnTargetBytes(), javaClass.classLoader)
        val method = readClass(transformed).methods.single { it.name == "value" && it.desc == "()Ljava/lang/String;" }
        val mixinOwner = org.objectweb.asm.Type.getInternalName(HeadReturningHandlerOnReturnTargetMixin::class.java)
        val instructions = method.instructions.toArray()
        val handlerCallIndex = instructions.indexOfFirst {
            it is org.objectweb.asm.tree.MethodInsnNode && it.owner == mixinOwner && it.name == "inject"
        }

        assertEquals(true, handlerCallIndex >= 0)
        assertEquals(Opcodes.POP2, instructions[handlerCallIndex + 1].opcode)
    }

    @Test
    fun tailInjectionDropsUnusedHandlerReturnValueOnNonVoidTarget() {
        AsmRegistry.register(TailReturningHandlerOnReturnTargetMixin::class.java)

        val transformed = AsmProcessor().transform("ReturnTarget", returnTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ReturnTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("value", result)
    }

    @Test
    fun returnInjectionDropsUnusedHandlerReturnValueOnNonVoidTarget() {
        AsmRegistry.register(ReturnReturningHandlerOnReturnTargetMixin::class.java)

        val transformed = AsmProcessor().transform("ReturnTarget", returnTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ReturnTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("value", result)
    }

    @Test
    fun returnInjectionEmitsPopForUnusedHandlerReturnValueOnNonVoidTarget() {
        AsmRegistry.register(ReturnReturningHandlerOnReturnTargetMixin::class.java)

        val transformed = AsmProcessor().transform("ReturnTarget", returnTargetBytes(), javaClass.classLoader)
        val method = readClass(transformed).methods.single { it.name == "value" && it.desc == "()Ljava/lang/String;" }
        val mixinOwner = org.objectweb.asm.Type.getInternalName(ReturnReturningHandlerOnReturnTargetMixin::class.java)
        val instructions = method.instructions.toArray()
        val handlerCallIndex = instructions.indexOfFirst {
            it is org.objectweb.asm.tree.MethodInsnNode && it.owner == mixinOwner && it.name == "inject"
        }

        assertEquals(true, handlerCallIndex >= 0)
        assertEquals(Opcodes.POP2, instructions[handlerCallIndex + 1].opcode)
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
    fun invokeBeforeInjectionAcceptsAssignableCallArgumentType() {
        AsmRegistry.register(InvokeBeforeAssignableCallArgumentMixin::class.java)

        val transformed = AsmProcessor().transform("InvokeModifyArgTarget", invokeModifyArgTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("InvokeModifyArgTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("prefix-original", result)
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
    fun modifyArgInfersTargetWhenMethodIsOmitted() {
        AsmRegistry.register(InferredModifyArgTargetMixin::class.java)

        val transformed = AsmProcessor().transform("ArgTarget", argTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ArgTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("echo", String::class.java).invoke(instance, "value")

        assertEquals("inferred-value", result)
    }

    @Test
    fun modifyArgAtMethodStartInfersIndexWhenSingleParameterMatches() {
        AsmRegistry.register(InferredModifyArgIndexMixin::class.java)

        val transformed = AsmProcessor().transform("MixedArgTarget", mixedArgTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("MixedArgTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz
            .getMethod("echo", Int::class.javaPrimitiveType, String::class.java)
            .invoke(instance, 7, "value")

        assertEquals("7:inferred-index-value", result)
    }

    @Test
    fun modifyArgAtMethodStartAcceptsGenericObjectReturnType() {
        AsmRegistry.register(ModifyArgGenericReturnMixin::class.java)

        val transformed = AsmProcessor().transform("ArgTarget", argTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ArgTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("echo", String::class.java).invoke(instance, "value")

        assertEquals("value-generic", result)
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
    fun modifyArgAtInvokeInfersCallTargetByHandlerSignature() {
        AsmRegistry.register(InferredInvokeModifyArgMixin::class.java)

        val transformed = AsmProcessor().transform("InvokeModifyArgTarget", invokeModifyArgTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("InvokeModifyArgTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("prefix-inferred", result)
    }

    @Test
    fun modifyArgAtInvokeInfersIndexWhenSingleCallParameterMatches() {
        AsmRegistry.register(InferredInvokeModifyArgIndexMixin::class.java)

        val transformed = AsmProcessor().transform("InvokeModifyArgTarget", invokeModifyArgTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("InvokeModifyArgTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("prefix-inferred-index", result)
    }

    @Test
    fun modifyArgAtInvokeInfersMethodAndCallTargetByHandlerSignature() {
        AsmRegistry.register(InferredMethodAndInvokeModifyArgMixin::class.java)

        val transformed = AsmProcessor().transform("InvokeModifyArgTarget", invokeModifyArgTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("InvokeModifyArgTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("prefix-inferred-method", result)
    }

    @Test
    fun modifyArgAtInvokeAcceptsObjectHandlerParameter() {
        AsmRegistry.register(InvokeModifyArgObjectParameterMixin::class.java)

        val transformed = AsmProcessor().transform("InvokeModifyArgTarget", invokeModifyArgTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("InvokeModifyArgTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("prefix-original-any", result)
    }

    @Test
    fun modifyArgAtInvokeAcceptsAssignableParentParameter() {
        AsmRegistry.register(InvokeModifyArgParentParameterMixin::class.java)

        val transformed = AsmProcessor().transform("InvokeModifyArgTarget", invokeModifyArgTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("InvokeModifyArgTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("prefix-original-parent", result)
    }

    @Test
    fun modifyArgAtInvokeAcceptsAssignableObjectReturnType() {
        AsmRegistry.register(InvokeModifyArgAssignableReturnMixin::class.java)

        val transformed = AsmProcessor().transform("ModifyArgsTarget", modifyArgsTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ModifyArgsTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("hello bad", result)
    }

    @Test
    fun modifyArgAtInvokeAcceptsGenericObjectReturnType() {
        AsmRegistry.register(InvokeModifyArgGenericReturnMixin::class.java)

        val transformed = AsmProcessor().transform("InvokeModifyArgTarget", invokeModifyArgTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("InvokeModifyArgTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("prefix-original-generic", result)
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
    fun modifyArgAtInvokeRewritesInvokeDynamicArgument() {
        AsmRegistry.register(InvokeDynamicModifyArgMixin::class.java)

        val transformed =
            AsmProcessor().transform("InvokeDynamicExpressionValueTarget", invokeDynamicExpressionValueTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("InvokeDynamicExpressionValueTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value", String::class.java, Int::class.javaPrimitiveType).invoke(instance, "raw", 7)

        assertEquals("changed-7", result)
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
    fun modifyArgSliceSupportsInvokeDynamicBoundaries() {
        AsmRegistry.register(InvokeDynamicSliceModifyArgMixin::class.java)

        val transformed =
            AsmProcessor().transform(
                "InvokeDynamicSliceModifyArgTarget",
                invokeDynamicSliceModifyArgTargetBytes(),
                javaClass.classLoader,
            )
        val clazz = loadClass("InvokeDynamicSliceModifyArgTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value", String::class.java).invoke(instance, "marker")

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
    fun modifyArgsSupportsKotlinIndexedAccess() {
        AsmRegistry.register(ModifyArgsIndexedAccessMixin::class.java)

        val transformed = AsmProcessor().transform("ModifyArgsTarget", modifyArgsTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ModifyArgsTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("hello indexed", result)
    }

    @Test
    fun modifyArgsSupportsKotlinSizePropertyAndIteration() {
        AsmRegistry.register(ModifyArgsIterationMixin::class.java)

        val transformed = AsmProcessor().transform("ModifyArgsTarget", modifyArgsTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ModifyArgsTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("hello iterated", result)
    }

    @Test
    fun modifyArgsSupportsIterableExtensions() {
        AsmRegistry.register(ModifyArgsIterableExtensionMixin::class.java)

        val transformed = AsmProcessor().transform("ModifyArgsTarget", modifyArgsTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ModifyArgsTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("hello joined", result)
    }

    @Test
    fun modifyArgsSupportsSetAll() {
        AsmRegistry.register(ModifyArgsSetAllMixin::class.java)

        val transformed = AsmProcessor().transform("ModifyArgsTarget", modifyArgsTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ModifyArgsTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("hello bulk", result)
    }

    @Test
    fun modifyArgsAtInvokeInfersCallTargetByHandlerSignature() {
        AsmRegistry.register(InferredInvokeModifyArgsMixin::class.java)

        val transformed = AsmProcessor().transform("ModifyArgsTarget", modifyArgsTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ModifyArgsTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("hello inferred", result)
    }

    @Test
    fun modifyArgsAtInvokeInfersMethodAndCallTargetByHandlerSignature() {
        AsmRegistry.register(InferredMethodAndInvokeModifyArgsMixin::class.java)

        val transformed = AsmProcessor().transform("ModifyArgsTarget", modifyArgsTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ModifyArgsTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("hello inferred-method", result)
    }

    @Test
    fun modifyArgsInfersTargetWhenMethodIsOmitted() {
        AsmRegistry.register(InferredModifyArgsTargetMixin::class.java)

        val transformed = AsmProcessor().transform("ModifyArgsTarget", modifyArgsTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ModifyArgsTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("hello inferred", result)
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
    fun modifyArgsAtInvokeAcceptsAssignableTargetParameter() {
        AsmRegistry.register(ModifyArgsParentTargetParamsMixin::class.java)

        val transformed = AsmProcessor().transform("ModifyArgsParamTarget", modifyArgsParamTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ModifyArgsParamTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value", String::class.java, Int::class.javaPrimitiveType).invoke(instance, "suffix", 7)

        assertEquals("left-suffix-right-7", result)
    }

    @Test
    fun modifyArgsAtConstructorInvokeRewritesConstructorArguments() {
        AsmRegistry.register(ConstructorModifyArgsMixin::class.java)

        val transformed = AsmProcessor().transform("ConstructorModifyArgsTarget", constructorModifyArgsTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ConstructorModifyArgsTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("bc", result)
    }

    @Test
    fun modifyArgsAtInvokeRewritesInvokeDynamicArguments() {
        AsmRegistry.register(InvokeDynamicModifyArgsMixin::class.java)

        val transformed =
            AsmProcessor().transform("InvokeDynamicExpressionValueTarget", invokeDynamicExpressionValueTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("InvokeDynamicExpressionValueTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value", String::class.java, Int::class.javaPrimitiveType).invoke(instance, "raw", 7)

        assertEquals("changed-9", result)
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
    fun modifyArgsSliceSupportsInvokeDynamicBoundaries() {
        AsmRegistry.register(ModifyArgsInvokeDynamicSliceMixin::class.java)

        val transformed =
            AsmProcessor().transform(
                "InvokeDynamicSliceModifyArgsTarget",
                invokeDynamicSliceModifyArgsTargetBytes(),
                javaClass.classLoader,
            )
        val clazz = loadClass("InvokeDynamicSliceModifyArgsTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value", String::class.java).invoke(instance, "marker")

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
    fun wrapWithConditionInfersTargetWhenMethodIsOmitted() {
        AsmRegistry.register(InferredWrapConditionStaticTargetMixin::class.java)

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
    fun wrapWithConditionAtInvokeInfersTargetByCompatibleVoidSignature() {
        AsmRegistry.register(WrapConditionInferredInvokeTargetMixin::class.java)

        val transformed =
            AsmProcessor().transform(
                "MixedWrapConditionTarget",
                mixedWrapConditionTargetBytes(),
                javaClass.classLoader,
            )
        val clazz = loadClass("MixedWrapConditionTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        val result = clazz.getMethod("run").invoke(instance)

        assertEquals(null, result)
    }

    @Test
    fun wrapWithConditionAcceptsAssignableParentCallParameter() {
        AsmRegistry.register(WrapConditionAssignableParentParameterMixin::class.java)

        val transformed = AsmProcessor().transform("WrapConditionStaticTarget", wrapConditionStaticTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("WrapConditionStaticTarget", transformed)
        val result = clazz.getMethod("run").invoke(null)

        assertEquals("raw", result)
    }

    @Test
    fun wrapWithConditionAtInvokeControlsInvokeDynamicVoidCall() {
        AsmRegistry.register(WrapConditionInvokeDynamicMixin::class.java)

        val transformed =
            AsmProcessor().transform("WrapConditionInvokeDynamicTarget", wrapConditionInvokeDynamicTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("WrapConditionInvokeDynamicTarget", transformed)
        val method = clazz.getMethod("run", String::class.java, Int::class.javaPrimitiveType)

        assertEquals(null, method.invoke(null, "skip", 7))
        assertEquals("raw7", method.invoke(null, "raw", 7))
    }

    @Test
    fun wrapWithConditionAtInvokeUsesDefaultReturnForNonVoidInvokeDynamicCall() {
        AsmRegistry.register(WrapConditionNonVoidInvokeDynamicMixin::class.java)

        val transformed =
            AsmProcessor().transform("InvokeDynamicExpressionValueTarget", invokeDynamicExpressionValueTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("InvokeDynamicExpressionValueTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value", String::class.java, Int::class.javaPrimitiveType).invoke(instance, "raw", 7)

        assertEquals("", result)
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
    fun wrapWithConditionAtInvokeUsesDefaultReturnForNonVoidCallInTestClass() {
        AsmRegistry.register(WrapConditionNonVoidTestCallMixin::class.java)

        val fixtureLoader = testFixtureClassLoader("Test", "TestParent", "TestInterface", "TestFunctionalInterface")
        val transformed = AsmProcessor().transform("Test", testFixtureClassBytes("Test"), fixtureLoader)
        val clazz =
            loadClasses(
                "Test",
                mapOf(
                    "Test" to transformed,
                    "TestParent" to testFixtureClassBytes("TestParent"),
                    "TestInterface" to testFixtureClassBytes("TestInterface"),
                    "TestFunctionalInterface" to testFixtureClassBytes("TestFunctionalInterface"),
                    "Test\$CustomException" to testFixtureClassBytes("Test\$CustomException"),
                    "Test\$InnerClass" to testFixtureClassBytes("Test\$InnerClass"),
                    "Test\$StaticInnerClass" to testFixtureClassBytes("Test\$StaticInnerClass"),
                    "Test\$TestEnum" to testFixtureClassBytes("Test\$TestEnum"),
                ),
            )

        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("comprehensiveTest").invoke(instance) as String

        assertEquals("", result.substringBefore("|"))
        assertEquals(true, result.startsWith("|DefaultConstructor|ParentMethod|"))
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
    fun wrapWithConditionFieldAssignSliceLimitsWritesBetweenFromAndTo() {
        AsmRegistry.register(WrapConditionFieldAssignSliceMixin::class.java)

        val transformed =
            AsmProcessor().transform("SliceWrapConditionFieldTarget", sliceWrapConditionFieldTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("SliceWrapConditionFieldTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("writeSelected").invoke(instance)

        assertEquals("pre:pre:outside", result)
    }

    @Test
    fun wrapWithConditionArrayWriteSliceLimitsStoresBetweenFromAndTo() {
        AsmRegistry.register(WrapConditionArrayWriteSliceMixin::class.java)

        val transformed =
            AsmProcessor().transform("SliceWrapConditionArrayTarget", sliceWrapConditionArrayTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("SliceWrapConditionArrayTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("writeSelected").invoke(instance)

        assertEquals("pre:pre:outside", result)
    }

    @Test
    fun wrapWithConditionLoadSliceLimitsLocalLoadsBetweenFromAndTo() {
        AsmRegistry.register(WrapConditionLoadSliceMixin::class.java)

        val transformed =
            AsmProcessor().transform("SliceLoadVariableTarget", sliceLoadVariableTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("SliceLoadVariableTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("pre::outside", result)
    }

    @Test
    fun wrapWithConditionAtConstantUsesDefaultValueWhenFalse() {
        AsmRegistry.register(WrapConditionConstantDenyMixin::class.java)

        val transformed = AsmProcessor().transform("ConstantParamTarget", constantParamTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ConstantParamTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value", String::class.java, Int::class.javaPrimitiveType).invoke(instance, "suffix", 3)

        assertEquals("", result)
    }

    @Test
    fun wrapWithConditionAtConstantKeepsOriginalValueWhenTrue() {
        AsmRegistry.register(WrapConditionConstantAllowMixin::class.java)

        val transformed = AsmProcessor().transform("ConstantParamTarget", constantParamTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ConstantParamTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value", String::class.java, Int::class.javaPrimitiveType).invoke(instance, "suffix", 3)

        assertEquals("base-", result)
    }

    @Test
    fun wrapWithConditionAtConstantInfersBooleanWhenTargetOmitted() {
        AsmRegistry.register(WrapConditionConstantBooleanDenyMixin::class.java)

        val transformed =
            AsmProcessor().transform("TrueBooleanConstantTarget", trueBooleanConstantTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("TrueBooleanConstantTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals(false, result)
    }

    @Test
    fun wrapWithConditionAtInvokeUsesDefaultReturnForNonVoidCall() {
        AsmRegistry.register(WrapConditionNonVoidCallMixin::class.java)

        val transformed = AsmProcessor().transform("ExpressionValueTarget", expressionValueTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ExpressionValueTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("", result)
    }

    @Test
    fun wrapWithConditionRejectsConstructorInvokeCall() {
        AsmRegistry.register(WrapConditionConstructorCallMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("NewInstructionTarget", newInstructionTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("does not support constructor calls") == true,
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
    fun wrapWithConditionAtFieldUsesDefaultValueWhenFalse() {
        AsmRegistry.register(WrapConditionFieldReadDenyMixin::class.java)

        val transformed = AsmProcessor().transform("FieldPointTarget", fieldPointTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("FieldPointTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeName", String::class.java).invoke(instance, "blocked")
        val result = clazz.getMethod("readName").invoke(instance)

        assertEquals("", result)
    }

    @Test
    fun wrapWithConditionAtFieldKeepsOriginalValueWhenTrue() {
        AsmRegistry.register(WrapConditionFieldReadAllowMixin::class.java)

        val transformed = AsmProcessor().transform("FieldPointTarget", fieldPointTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("FieldPointTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeName", String::class.java).invoke(instance, "allowed")
        val result = clazz.getMethod("readName").invoke(instance)

        assertEquals("allowed", result)
    }

    @Test
    fun wrapWithConditionAtFieldPrimitiveUsesDefaultValueWhenFalse() {
        AsmRegistry.register(WrapConditionPrimitiveFieldReadDenyMixin::class.java)

        val transformed =
            AsmProcessor().transform("PrimitiveFieldPointTarget", primitiveFieldPointTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("PrimitiveFieldPointTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeScore", Int::class.javaPrimitiveType).invoke(instance, 42)
        val result = clazz.getMethod("readScore").invoke(instance)

        assertEquals(0, result)
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
    fun wrapWithConditionAtFieldAssignInfersTargetInTestClassConstructor() {
        AsmRegistry.register(WrapConditionInferredTestFieldAssignMixin::class.java)

        val fixtureLoader = testFixtureClassLoader("Test", "TestParent", "TestInterface")
        val transformed = AsmProcessor().transform("Test", testFixtureClassBytes("Test"), fixtureLoader)
        val clazz =
            loadClasses(
                "Test",
                mapOf(
                    "Test" to transformed,
                    "TestParent" to testFixtureClassBytes("TestParent"),
                    "TestInterface" to testFixtureClassBytes("TestInterface"),
                    "TestFunctionalInterface" to testFixtureClassBytes("TestFunctionalInterface"),
                    "Test\$CustomException" to testFixtureClassBytes("Test\$CustomException"),
                    "Test\$InnerClass" to testFixtureClassBytes("Test\$InnerClass"),
                    "Test\$StaticInnerClass" to testFixtureClassBytes("Test\$StaticInnerClass"),
                    "Test\$TestEnum" to testFixtureClassBytes("Test\$TestEnum"),
                ),
            )

        val instance = clazz.getDeclaredConstructor(String::class.java).newInstance("blocked")
        val result = clazz.getMethod("testA0").invoke(instance)

        assertEquals("DynamicString", result)
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
    fun wrapWithConditionAtArrayReadUsesDefaultValueWhenFalse() {
        AsmRegistry.register(WrapConditionArrayReadDenyMixin::class.java)

        val transformed = AsmProcessor().transform("ArrayAccessTarget", arrayAccessTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ArrayAccessTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("readName", Int::class.javaPrimitiveType).invoke(instance, 0)

        assertEquals("", result)
    }

    @Test
    fun wrapWithConditionAtArrayReadKeepsOriginalValueWhenTrue() {
        AsmRegistry.register(WrapConditionArrayReadAllowMixin::class.java)

        val transformed = AsmProcessor().transform("ArrayAccessTarget", arrayAccessTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ArrayAccessTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("readName", Int::class.javaPrimitiveType).invoke(instance, 0)

        assertEquals("raw", result)
    }

    @Test
    fun wrapWithConditionAtArrayReadPrimitiveUsesDefaultValueWhenFalse() {
        AsmRegistry.register(WrapConditionPrimitiveArrayReadDenyMixin::class.java)

        val transformed =
            AsmProcessor().transform(
                "PrimitiveArrayAccessTarget",
                primitiveArrayAccessTargetBytes(),
                javaClass.classLoader,
            )
        val clazz = loadClass("PrimitiveArrayAccessTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("readScore", Int::class.javaPrimitiveType).invoke(instance, 0)

        assertEquals(0, result)
    }

    @Test
    fun wrapWithConditionAtArrayLengthUsesDefaultValueWhenFalse() {
        AsmRegistry.register(WrapConditionArrayLengthDenyMixin::class.java)

        val transformed = AsmProcessor().transform("ArrayAccessTarget", arrayAccessTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ArrayAccessTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("nameCount").invoke(instance)

        assertEquals(0, result)
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
    fun wrapWithConditionAtStoreSkipsLocalWriteWhenFalse() {
        AsmRegistry.register(WrapConditionStoreDenyMixin::class.java)

        val transformed =
            AsmProcessor().transform("ConditionalStoreTarget", conditionalStoreTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ConditionalStoreTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("initial", result)
    }

    @Test
    fun wrapWithConditionAtStoreArgsNameLimitsLocalVariableName() {
        AsmRegistry.register(WrapConditionStoreNameMixin::class.java)

        val transformed =
            AsmProcessor().transform("NamedConditionalStoreTarget", namedConditionalStoreTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("NamedConditionalStoreTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("blocked-other:second", result)
    }

    @Test
    fun wrapWithConditionAtStoreCanUseTargetMethodParameters() {
        AsmRegistry.register(WrapConditionStoreWithTargetParamsMixin::class.java)

        val transformed =
            AsmProcessor().transform("ConditionalStoreParamTarget", conditionalStoreParamTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ConditionalStoreParamTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value", String::class.java).invoke(instance, "allow")

        assertEquals("target", result)
    }

    @Test
    fun wrapWithConditionAtLoadSkipsLocalReadWhenFalse() {
        AsmRegistry.register(WrapConditionLoadDenyMixin::class.java)

        val fixtureLoader = testFixtureClassLoader("Test", "TestParent", "TestInterface", "TestFunctionalInterface")
        val transformed = AsmProcessor().transform("Test", testFixtureClassBytes("Test"), fixtureLoader)
        val clazz =
            loadClasses(
                "Test",
                mapOf(
                    "Test" to transformed,
                    "TestParent" to testFixtureClassBytes("TestParent"),
                    "TestInterface" to testFixtureClassBytes("TestInterface"),
                    "TestFunctionalInterface" to testFixtureClassBytes("TestFunctionalInterface"),
                    "Test\$CustomException" to testFixtureClassBytes("Test\$CustomException"),
                    "Test\$InnerClass" to testFixtureClassBytes("Test\$InnerClass"),
                    "Test\$StaticInnerClass" to testFixtureClassBytes("Test\$StaticInnerClass"),
                    "Test\$TestEnum" to testFixtureClassBytes("Test\$TestEnum"),
                ),
            )

        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("localNameDiscriminatorTest", String::class.java).invoke(instance, "raw") as String

        assertEquals(":raw-second", result)
    }

    @Test
    fun wrapWithConditionAtLoadIndexUsesCurrentLocalVariableScopeWhenSlotIsReused() {
        AsmRegistry.register(WrapConditionLoadReusedSlotMixin::class.java)

        val transformed = AsmProcessor().transform("ReusedLoadSlotTarget", reusedLoadSlotTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ReusedLoadSlotTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("", result)
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
    fun wrapWithConditionAtJumpSkipsOriginalBranchWhenFalse() {
        AsmRegistry.register(WrapConditionJumpMixin::class.java)

        val transformed = AsmProcessor().transform("JumpOperationTarget", jumpOperationTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("JumpOperationTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val method = clazz.getMethod("choose", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)

        assertEquals("positive", method.invoke(instance, -1, false))
        assertEquals("negative", method.invoke(instance, -1, true))
        assertEquals("positive", method.invoke(instance, 1, true))
    }

    @Test
    fun wrapWithConditionRejectsUnconditionalJumpTarget() {
        AsmRegistry.register(WrapConditionGotoMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("JumpOperationTarget", jumpOperationTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("conditional JVM jump opcode") == true,
        )
    }

    @Test
    fun wrapWithConditionAtThrowSkipsOriginalThrowWhenFalse() {
        AsmRegistry.register(WrapConditionThrowMixin::class.java)

        val transformed =
            AsmProcessor().transform("ConditionalThrowTarget", conditionalThrowTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ConditionalThrowTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val method =
            clazz.getMethod(
                "choose",
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            )

        assertEquals("after", method.invoke(instance, false, false))
        assertEquals("after", method.invoke(instance, true, true))

        val exception =
            assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
                method.invoke(instance, false, true)
            }

        assertEquals(true, exception.cause is IllegalStateException)
        assertEquals("state", exception.cause?.message)
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
    fun modifyExpressionValueAtInvokeInfersTargetByCompatibleReturnType() {
        AsmRegistry.register(ModifyExpressionValueInferredInvokeTargetMixin::class.java)

        val transformed =
            AsmProcessor().transform(
                "InferredInvokeExpressionValueTarget",
                inferredInvokeExpressionValueTargetBytes(),
                javaClass.classLoader,
            )
        val clazz = loadClass("InferredInvokeExpressionValueTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("raw-inferred", result)
    }

    @Test
    fun modifyExpressionValueInfersTargetWhenMethodIsOmitted() {
        AsmRegistry.register(InferredModifyExpressionValueTargetMixin::class.java)

        val transformed = AsmProcessor().transform("ExpressionValueTarget", expressionValueTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ExpressionValueTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("raw-inferred", result)
    }

    @Test
    fun modifyExpressionValueAtInvokeAcceptsObjectHandlerParameter() {
        AsmRegistry.register(ModifyExpressionValueObjectParamMixin::class.java)

        val transformed = AsmProcessor().transform("ExpressionValueTarget", expressionValueTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ExpressionValueTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("raw-object", result)
    }

    @Test
    fun modifyExpressionValueAtInvokeAcceptsAssignableParentParameter() {
        AsmRegistry.register(ModifyExpressionValueParentParamMixin::class.java)

        val transformed = AsmProcessor().transform("ExpressionValueTarget", expressionValueTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ExpressionValueTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("raw-parent", result)
    }

    @Test
    fun modifyExpressionValueAtInvokeAcceptsAssignableObjectReturnType() {
        AsmRegistry.register(ModifyExpressionValueAssignableReturnMixin::class.java)

        val transformed = AsmProcessor().transform(
            "CharSequenceExpressionValueTarget",
            charSequenceExpressionValueTargetBytes(),
            javaClass.classLoader,
        )
        val clazz = loadClass("CharSequenceExpressionValueTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance).toString()

        assertEquals("raw-builder", result)
    }

    @Test
    fun modifyExpressionValueAtInvokeAcceptsGenericObjectReturnType() {
        AsmRegistry.register(ModifyExpressionValueGenericReturnMixin::class.java)

        val transformed = AsmProcessor().transform("ExpressionValueTarget", expressionValueTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ExpressionValueTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("raw-generic", result)
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
    fun modifyExpressionValueAtLoadRewritesSingleReadWithoutWritingBackSlot() {
        AsmRegistry.register(ModifyExpressionValueLoadMixin::class.java)

        val transformed =
            AsmProcessor().transform("LoadExpressionValueTarget", loadExpressionValueTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("LoadExpressionValueTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("expr-raw:raw", result)
    }

    @Test
    fun modifyExpressionValueAtStoreRewritesStoredExpressionValue() {
        AsmRegistry.register(ModifyExpressionValueStoreMixin::class.java)

        val transformed =
            AsmProcessor().transform("StoreExpressionValueTarget", storeExpressionValueTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("StoreExpressionValueTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("store-raw", result)
    }

    @Test
    fun modifyExpressionValueAtLoadArgsNameLimitsLocalVariableName() {
        AsmRegistry.register(ModifyExpressionValueLoadNameMixin::class.java)

        val transformed =
            AsmProcessor().transform("NamedLoadVariableTarget", namedLoadVariableTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("NamedLoadVariableTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("first:expr-second", result)
    }

    @Test
    fun modifyExpressionValueAtStoreArgsNameLimitsLocalVariableName() {
        AsmRegistry.register(ModifyExpressionValueStoreNameMixin::class.java)

        val transformed =
            AsmProcessor().transform("NamedStoreVariableTarget", namedStoreVariableTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("NamedStoreVariableTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("first:store-second", result)
    }

    @Test
    fun redirectAtLoadRewritesSingleReadWithoutWritingBackSlot() {
        AsmRegistry.register(RedirectLoadMixin::class.java)

        val transformed =
            AsmProcessor().transform("LoadExpressionValueTarget", loadExpressionValueTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("LoadExpressionValueTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("redirect-raw:raw", result)
    }

    @Test
    fun redirectAtStoreRewritesStoredExpressionValue() {
        AsmRegistry.register(RedirectStoreMixin::class.java)

        val transformed =
            AsmProcessor().transform("StoreExpressionValueTarget", storeExpressionValueTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("StoreExpressionValueTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("redirect-store-raw", result)
    }

    @Test
    fun redirectAtLoadArgsNameLimitsLocalVariableName() {
        AsmRegistry.register(RedirectLoadNameMixin::class.java)

        val transformed =
            AsmProcessor().transform("NamedLoadVariableTarget", namedLoadVariableTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("NamedLoadVariableTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("first:redirect-second", result)
    }

    @Test
    fun redirectAtStoreArgsNameLimitsLocalVariableName() {
        AsmRegistry.register(RedirectStoreNameMixin::class.java)

        val transformed =
            AsmProcessor().transform("NamedStoreVariableTarget", namedStoreVariableTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("NamedStoreVariableTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("first:redirect-store-second", result)
    }

    @Test
    fun modifyExpressionValueAtInvokeRewritesInvokeDynamicReturnValue() {
        AsmRegistry.register(ModifyExpressionValueInvokeDynamicMixin::class.java)

        val transformed =
            AsmProcessor().transform("InvokeDynamicExpressionValueTarget", invokeDynamicExpressionValueTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("InvokeDynamicExpressionValueTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value", String::class.java, Int::class.javaPrimitiveType).invoke(instance, "raw", 7)

        assertEquals("raw-7-dynamic-raw7", result)
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
    fun modifyExpressionValueSliceSupportsInvokeDynamicBoundaries() {
        AsmRegistry.register(ModifyExpressionValueInvokeDynamicSliceMixin::class.java)

        val transformed =
            AsmProcessor().transform(
                "InvokeDynamicSliceExpressionValueTarget",
                invokeDynamicSliceExpressionValueTargetBytes(),
                javaClass.classLoader,
            )
        val clazz = loadClass("InvokeDynamicSliceExpressionValueTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value", String::class.java).invoke(instance, "marker")

        assertEquals("pre:inside-changed:outside", result)
    }

    @Test
    @DisplayName("ModifyExpressionValue 显式 INVOKE 空 Slice 边界应快速失败")
    fun modifyExpressionValueEmptyInvokeSliceBoundaryFailsFast() {
        // Given
        AsmRegistry.register(ModifyExpressionValueEmptyInvokeSliceBoundaryMixin::class.java)

        // When / Then
        assertThatThrownBy {
            AsmProcessor().transform("SliceExpressionValueTarget", sliceExpressionValueTargetBytes(), javaClass.classLoader)
        }
            .`as`("Then: 显式声明 Slice INVOKE 边界但遗漏 target 时，应暴露配置错误而不是扩大到全方法")
            .isInstanceOf(AsmTransformException::class.java)
            .hasRootCauseMessage(
                "Invalid @ModifyExpressionValue slice boundary INVOKE target: target must not be empty",
            )
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
    fun modifyExpressionValueFieldAssignSliceLimitsFieldWritesBetweenFromAndTo() {
        AsmRegistry.register(ModifyExpressionValueFieldAssignSliceMixin::class.java)

        val transformed = AsmProcessor().transform("SliceFieldAssignTarget", sliceFieldAssignTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("SliceFieldAssignTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeSelected", String::class.java, String::class.java).invoke(instance, "outside", "inside")
        val result = clazz.getField("name").get(instance)

        assertEquals("inside-field-assign-slice", result)
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
    fun modifyExpressionValueAtFieldInfersTargetByCompatibleValueType() {
        AsmRegistry.register(ModifyExpressionValueInferredFieldReadMixin::class.java)

        val transformed =
            AsmProcessor().transform(
                "MixedFieldExpressionValueTarget",
                mixedFieldExpressionValueTargetBytes(),
                javaClass.classLoader,
            )
        val clazz = loadClass("MixedFieldExpressionValueTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeValues", String::class.java, Int::class.javaPrimitiveType)
            .invoke(instance, "raw", 7)
        val result = clazz.getMethod("readSelected").invoke(instance)

        assertEquals("raw-inferred-field", result)
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
    fun modifyExpressionValueAtFieldAssignRewritesPutFieldValue() {
        AsmRegistry.register(ModifyExpressionValueFieldAssignMixin::class.java)

        val transformed = AsmProcessor().transform("FieldPointTarget", fieldPointTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("FieldPointTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeName", String::class.java).invoke(instance, "raw")
        val result = clazz.getMethod("readName").invoke(instance)

        assertEquals("raw-assigned", result)
    }

    @Test
    fun modifyExpressionValueAtStaticFieldAssignRewritesPutStaticValue() {
        AsmRegistry.register(ModifyExpressionValueStaticFieldAssignMixin::class.java)

        val transformed = AsmProcessor().transform("StaticFieldPointTarget", staticFieldPointTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("StaticFieldPointTarget", transformed)

        clazz.getMethod("writeName", String::class.java).invoke(null, "raw")
        val result = clazz.getMethod("readName").invoke(null)

        assertEquals("raw-static-assigned", result)
    }

    @Test
    fun modifyExpressionValueAtPrimitiveFieldAssignRewritesPutFieldValue() {
        AsmRegistry.register(ModifyExpressionValuePrimitiveFieldAssignMixin::class.java)

        val transformed = AsmProcessor().transform("PrimitiveFieldPointTarget", primitiveFieldPointTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("PrimitiveFieldPointTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeScore", Int::class.javaPrimitiveType).invoke(instance, 7)
        val result = clazz.getMethod("readScore").invoke(instance)

        assertEquals(10, result)
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
    fun modifyExpressionValueAtArrayWriteRewritesObjectArrayElementValue() {
        AsmRegistry.register(ModifyExpressionValueArrayWriteMixin::class.java)

        val transformed = AsmProcessor().transform("ArrayAccessTarget", arrayAccessTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ArrayAccessTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeName", Int::class.javaPrimitiveType, String::class.java).invoke(instance, 0, "raw")
        val result = clazz.getMethod("readName", Int::class.javaPrimitiveType).invoke(instance, 0)

        assertEquals("raw-array-write", result)
    }

    @Test
    fun modifyExpressionValueAtArrayWriteRewritesPrimitiveArrayElementValue() {
        AsmRegistry.register(ModifyExpressionValuePrimitiveArrayWriteMixin::class.java)

        val transformed =
            AsmProcessor().transform("PrimitiveArrayAccessTarget", primitiveArrayAccessTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("PrimitiveArrayAccessTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeScore", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(instance, 0, 7)
        val result = clazz.getMethod("readScore", Int::class.javaPrimitiveType).invoke(instance, 0)

        assertEquals(10, result)
    }

    @Test
    fun modifyExpressionValueAtArrayWriteCanUseTargetMethodParameters() {
        AsmRegistry.register(ModifyExpressionValueArrayWriteWithTargetParamsMixin::class.java)

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
    fun modifyExpressionValueNewWithInferredTargetSkipsIncompatibleConstructions() {
        AsmRegistry.register(ModifyExpressionValueNewInferredTargetMixin::class.java)

        val transformed = AsmProcessor().transform(
            "MixedNewExpressionValueTarget",
            mixedNewExpressionValueTargetBytes(),
            javaClass.classLoader,
        )
        val clazz = loadClass("MixedNewExpressionValueTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("changed", result)
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
    fun modifyExpressionValueAtCastWithoutTargetSkipsIncompatibleCheckcasts() {
        AsmRegistry.register(AnyCastModifyExpressionValueMixin::class.java)

        val transformed = AsmProcessor().transform("MultiCastInstructionTarget", multiCastInstructionTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("MultiCastInstructionTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val method = clazz.getMethod("cast", Any::class.java, Any::class.java)

        assertEquals("raw-modified", method.invoke(instance, StringBuilder("ignored"), "raw"))
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
    fun modifyExpressionValueAtThrowRewritesThrownException() {
        AsmRegistry.register(ModifyExpressionValueThrowMixin::class.java)

        val transformed = AsmProcessor().transform("ThrowPointTarget", throwPointTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ThrowPointTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val exception =
            assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
                clazz.getMethod("fail").invoke(instance)
            }

        assertEquals(true, exception.cause is IllegalArgumentException)
        assertEquals("modified-failed", exception.cause?.message)
    }

    @Test
    fun modifyExpressionValueAtConstantRewritesConstantExpression() {
        AsmRegistry.register(ModifyExpressionValueConstantMixin::class.java)

        val transformed = AsmProcessor().transform("MixedConstantTarget", mixedConstantTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("MixedConstantTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("expression-original", result)
    }

    @Test
    fun modifyExpressionValueAtConstantInfersTargetByCompatibleConstantType() {
        AsmRegistry.register(ModifyExpressionValueInferredConstantMixin::class.java)

        val transformed = AsmProcessor().transform("MixedConstantTarget", mixedConstantTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("MixedConstantTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("inferred-original", result)
    }

    @Test
    fun redirectAtConstantReplacesConstantExpression() {
        AsmRegistry.register(RedirectConstantMixin::class.java)

        val transformed = AsmProcessor().transform("MixedConstantTarget", mixedConstantTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("MixedConstantTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("redirect-original", result)
    }

    @Test
    fun redirectAtConstantInfersTargetByCompatibleConstantType() {
        AsmRegistry.register(RedirectInferredConstantMixin::class.java)

        val transformed = AsmProcessor().transform("MixedConstantTarget", mixedConstantTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("MixedConstantTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("redirect-inferred-original", result)
    }

    @Test
    fun modifyExpressionValueAtJumpRewritesBranchDecisionInTestClass() {
        AsmRegistry.register(ModifyExpressionValueJumpMixin::class.java)

        val fixtureLoader = testFixtureClassLoader("Test", "TestParent", "TestInterface")
        val transformed = AsmProcessor().transform("Test", testFixtureClassBytes("Test"), fixtureLoader)
        val clazz =
            loadClasses(
                "Test",
                mapOf(
                    "Test" to transformed,
                    "TestParent" to testFixtureClassBytes("TestParent"),
                    "TestInterface" to testFixtureClassBytes("TestInterface"),
                    "TestFunctionalInterface" to testFixtureClassBytes("TestFunctionalInterface"),
                    "Test\$CustomException" to testFixtureClassBytes("Test\$CustomException"),
                    "Test\$InnerClass" to testFixtureClassBytes("Test\$InnerClass"),
                    "Test\$StaticInnerClass" to testFixtureClassBytes("Test\$StaticInnerClass"),
                    "Test\$TestEnum" to testFixtureClassBytes("Test\$TestEnum"),
                ),
            )
        val instance = clazz.getDeclaredConstructor(String::class.java).newInstance("raw")
        val result = clazz.getMethod("recursiveMethod", Int::class.javaPrimitiveType).invoke(instance, 5)

        assertEquals(1, result)
    }

    @Test
    fun modifyExpressionValueAtSwitchRewritesTableSwitchSelector() {
        AsmRegistry.register(ModifyExpressionValueSwitchMixin::class.java)

        val transformed = AsmProcessor().transform("SwitchSelectorTarget", switchSelectorTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("SwitchSelectorTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val method = clazz.getMethod("choose", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)

        assertEquals("one", method.invoke(instance, 0, false))
        assertEquals("two", method.invoke(instance, 1, false))
        assertEquals("fallback", method.invoke(instance, 9, false))
        assertEquals("two", method.invoke(instance, 0, true))
    }

    @Test
    fun modifyExpressionValueAtSwitchRewritesLookupSwitchSelector() {
        AsmRegistry.register(ModifyExpressionValueLookupSwitchMixin::class.java)

        val transformed =
            AsmProcessor().transform(
                "LookupSwitchSelectorTarget",
                lookupSwitchSelectorTargetBytes(),
                javaClass.classLoader,
            )
        val clazz = loadClass("LookupSwitchSelectorTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val method = clazz.getMethod("choose", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)

        assertEquals("ten", method.invoke(instance, 0, false))
        assertEquals("thirty", method.invoke(instance, 20, false))
        assertEquals("fallback", method.invoke(instance, 90, false))
        assertEquals("thirty", method.invoke(instance, 0, true))
    }

    @Test
    fun wrapOperationAtSwitchWrapsTableSwitchSelector() {
        AsmRegistry.register(WrapOperationSwitchMixin::class.java)

        val transformed = AsmProcessor().transform("SwitchSelectorTarget", switchSelectorTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("SwitchSelectorTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val method = clazz.getMethod("choose", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)

        assertEquals("one", method.invoke(instance, 0, false))
        assertEquals("two", method.invoke(instance, 1, false))
        assertEquals("two", method.invoke(instance, 0, true))
    }

    @Test
    fun wrapOperationAtSwitchWrapsLookupSwitchSelector() {
        AsmRegistry.register(WrapOperationLookupSwitchMixin::class.java)

        val transformed =
            AsmProcessor().transform(
                "LookupSwitchSelectorTarget",
                lookupSwitchSelectorTargetBytes(),
                javaClass.classLoader,
            )
        val clazz = loadClass("LookupSwitchSelectorTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val method = clazz.getMethod("choose", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)

        assertEquals("ten", method.invoke(instance, 0, false))
        assertEquals("thirty", method.invoke(instance, 20, false))
        assertEquals("thirty", method.invoke(instance, 0, true))
    }

    @Test
    fun redirectAtSwitchReplacesTableSwitchSelector() {
        AsmRegistry.register(RedirectSwitchMixin::class.java)

        val transformed = AsmProcessor().transform("SwitchSelectorTarget", switchSelectorTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("SwitchSelectorTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val method = clazz.getMethod("choose", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)

        assertEquals("one", method.invoke(instance, 0, false))
        assertEquals("two", method.invoke(instance, 1, false))
        assertEquals("two", method.invoke(instance, 0, true))
    }

    @Test
    fun redirectAtSwitchReplacesLookupSwitchSelector() {
        AsmRegistry.register(RedirectLookupSwitchMixin::class.java)

        val transformed =
            AsmProcessor().transform(
                "LookupSwitchSelectorTarget",
                lookupSwitchSelectorTargetBytes(),
                javaClass.classLoader,
            )
        val clazz = loadClass("LookupSwitchSelectorTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val method = clazz.getMethod("choose", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)

        assertEquals("ten", method.invoke(instance, 0, false))
        assertEquals("thirty", method.invoke(instance, 20, false))
        assertEquals("thirty", method.invoke(instance, 0, true))
    }

    @Test
    fun redirectAtThrowCanReplaceThrowable() {
        AsmRegistry.register(RedirectThrowMixin::class.java)

        val transformed = AsmProcessor().transform("ThrowPointTarget", throwPointTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ThrowPointTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val exception =
            assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
                clazz.getMethod("fail").invoke(instance)
            }

        assertEquals(true, exception.cause is IllegalArgumentException)
        assertEquals("redirected-failed", exception.cause?.message)
    }

    @Test
    fun modifyExpressionValueAtThrowAcceptsSpecificThrowableReturnType() {
        AsmRegistry.register(ModifyExpressionValueSpecificThrowableMixin::class.java)

        val transformed = AsmProcessor().transform("ThrowPointTarget", throwPointTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ThrowPointTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val exception =
            assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
                clazz.getMethod("fail").invoke(instance)
            }

        assertEquals(true, exception.cause is IllegalArgumentException)
        assertEquals("specific-failed", exception.cause?.message)
    }

    @Test
    fun modifyExpressionValueThrowTargetFiltersDirectlyConstructedThrowable() {
        AsmRegistry.register(ModifyExpressionValueTargetedThrowMixin::class.java)

        val transformed =
            AsmProcessor().transform("TargetedThrowPointTarget", targetedThrowPointTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("TargetedThrowPointTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val method = clazz.getMethod("fail", Boolean::class.javaPrimitiveType)
        val selectedException =
            assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
                method.invoke(instance, true)
            }
        val skippedException =
            assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
                method.invoke(instance, false)
            }

        assertEquals(true, selectedException.cause is IllegalArgumentException)
        assertEquals("modified-state", selectedException.cause?.message)
        assertEquals(true, skippedException.cause is UnsupportedOperationException)
        assertEquals("unsupported", skippedException.cause?.message)
    }

    @Test
    fun modifyExpressionValueAtThrowCanUseTargetMethodParameters() {
        AsmRegistry.register(ModifyExpressionValueThrowWithTargetParamsMixin::class.java)

        val transformed = AsmProcessor().transform("ThrowPointTarget", throwPointTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ThrowPointTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val exception =
            assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
                clazz.getMethod("failWithParams", String::class.java, Int::class.javaPrimitiveType)
                    .invoke(instance, "prefix", 7)
            }

        assertEquals(true, exception.cause is IllegalArgumentException)
        assertEquals("prefix-7-failed", exception.cause?.message)
    }

    @Test
    fun wrapOperationAtThrowCanReplaceThrowableAndCallOriginalOperation() {
        AsmRegistry.register(WrapOperationThrowMixin::class.java)

        val transformed = AsmProcessor().transform("ThrowPointTarget", throwPointTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ThrowPointTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val exception =
            assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
                clazz.getMethod("fail").invoke(instance)
            }

        assertEquals(true, exception.cause is IllegalArgumentException)
        assertEquals("wrapped-failed", exception.cause?.message)
    }

    @Test
    fun wrapOperationThrowTargetFiltersDirectlyConstructedThrowable() {
        AsmRegistry.register(WrapOperationTargetedThrowMixin::class.java)

        val transformed =
            AsmProcessor().transform("TargetedThrowPointTarget", targetedThrowPointTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("TargetedThrowPointTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val method = clazz.getMethod("fail", Boolean::class.javaPrimitiveType)
        val selectedException =
            assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
                method.invoke(instance, true)
            }
        val skippedException =
            assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
                method.invoke(instance, false)
            }

        assertEquals(true, selectedException.cause is IllegalArgumentException)
        assertEquals("wrapped-state", selectedException.cause?.message)
        assertEquals(true, skippedException.cause is UnsupportedOperationException)
        assertEquals("unsupported", skippedException.cause?.message)
    }

    @Test
    fun modifyExpressionValueThrowSliceLimitsThrowsAfterFrom() {
        AsmRegistry.register(ModifyExpressionValueThrowSliceMixin::class.java)

        val transformed =
            AsmProcessor().transform("SliceThrowInstructionTarget", sliceThrowInstructionTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("SliceThrowInstructionTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val exception =
            assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
                clazz.getMethod("failSelected").invoke(instance)
            }

        assertEquals(true, exception.cause is IllegalArgumentException)
        assertEquals("modified-inside", exception.cause?.message)
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
    fun modifyReceiverInfersTargetWhenMethodIsOmitted() {
        AsmRegistry.register(InferredModifyReceiverTargetMixin::class.java)

        val transformed = AsmProcessor().transform("ModifyReceiverTarget", modifyReceiverTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ModifyReceiverTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("inferred-call", result)
    }

    @Test
    fun modifyReceiverAtInvokeInfersTargetByCompatibleReceiverType() {
        AsmRegistry.register(ModifyReceiverInferredInvokeTargetMixin::class.java)

        val transformed =
            AsmProcessor().transform(
                "MixedModifyReceiverTarget",
                mixedModifyReceiverTargetBytes(),
                javaClass.classLoader,
            )
        val clazz = loadClass("MixedModifyReceiverTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("changed-call", result)
    }

    @Test
    @DisplayName("省略 method 与 INVOKE target 时应按兼容 receiver 推断唯一业务调用")
    fun modifyReceiverInfersMethodAndInvokeTargetByCompatibleReceiverType() {
        // Given
        AsmRegistry.register(ModifyReceiverInferredMethodAndInvokeTargetMixin::class.java)

        // When
        val transformed =
            AsmProcessor().transform(
                "MixedModifyReceiverTarget",
                mixedModifyReceiverTargetBytes(),
                javaClass.classLoader,
            )
        val clazz = loadClass("MixedModifyReceiverTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        // Then
        assertThat(result)
            .`as`("Then: String handler 只能匹配 String.concat receiver，不能误改 StringBuilder.toString receiver")
            .isEqualTo("both-inferred-call")
    }

    @Test
    fun modifyReceiverAtInvokeAcceptsAssignableParentParameter() {
        AsmRegistry.register(ModifyReceiverParentParamMixin::class.java)

        val transformed = AsmProcessor().transform("ModifyReceiverTarget", modifyReceiverTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ModifyReceiverTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("parent-call", result)
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
    fun modifyReceiverSliceSupportsInvokeDynamicBoundaries() {
        AsmRegistry.register(ModifyReceiverInvokeDynamicSliceMixin::class.java)

        val transformed =
            AsmProcessor().transform(
                "InvokeDynamicSliceModifyReceiverTarget",
                invokeDynamicSliceModifyReceiverTargetBytes(),
                javaClass.classLoader,
            )
        val clazz = loadClass("InvokeDynamicSliceModifyReceiverTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value", String::class.java).invoke(instance, "marker")

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
    fun modifyReceiverWithIncompatibleReturnTypeFailsDuringTransform() {
        AsmRegistry.register(IncompatibleModifyReceiverReturnMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("ModifyReceiverTarget", modifyReceiverTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("return type") == true &&
                exception.cause?.message?.contains("receiver type") == true,
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
    fun modifyReceiverAtFieldReadInfersTargetInTestClass() {
        AsmRegistry.register(ModifyReceiverInferredTestFieldReadMixin::class.java)

        val fixtureLoader = testFixtureClassLoader("Test", "TestParent", "TestInterface")
        val transformed = AsmProcessor().transform("Test", testFixtureClassBytes("Test"), fixtureLoader)
        val clazz =
            loadClasses(
                "Test",
                mapOf(
                    "Test" to transformed,
                    "TestParent" to testFixtureClassBytes("TestParent"),
                    "TestInterface" to testFixtureClassBytes("TestInterface"),
                    "TestFunctionalInterface" to testFixtureClassBytes("TestFunctionalInterface"),
                    "Test\$CustomException" to testFixtureClassBytes("Test\$CustomException"),
                    "Test\$InnerClass" to testFixtureClassBytes("Test\$InnerClass"),
                    "Test\$StaticInnerClass" to testFixtureClassBytes("Test\$StaticInnerClass"),
                    "Test\$TestEnum" to testFixtureClassBytes("Test\$TestEnum"),
                ),
            )
        val original = clazz.getDeclaredConstructor(String::class.java).newInstance("original")
        val replacement = clazz.getDeclaredConstructor(String::class.java).newInstance("replacement")

        try {
            ModifyReceiverInferredTestFieldReadMixin.replacement = replacement

            val result = clazz.getMethod("testA0").invoke(original)

            assertEquals("replacement", result)
        } finally {
            ModifyReceiverInferredTestFieldReadMixin.replacement = null
        }
    }

    @Test
    fun modifyReceiverInfersMethodAndFieldReadTargetInTestClass() {
        AsmRegistry.register(ModifyReceiverInferredMethodAndTestFieldReadMixin::class.java)

        val fixtureLoader = testFixtureClassLoader("Test", "TestParent", "TestInterface")
        val transformed = AsmProcessor().transform("Test", testFixtureClassBytes("Test"), fixtureLoader)
        val clazz =
            loadClasses(
                "Test",
                mapOf(
                    "Test" to transformed,
                    "TestParent" to testFixtureClassBytes("TestParent"),
                    "TestInterface" to testFixtureClassBytes("TestInterface"),
                    "TestFunctionalInterface" to testFixtureClassBytes("TestFunctionalInterface"),
                    "Test\$CustomException" to testFixtureClassBytes("Test\$CustomException"),
                    "Test\$InnerClass" to testFixtureClassBytes("Test\$InnerClass"),
                    "Test\$StaticInnerClass" to testFixtureClassBytes("Test\$StaticInnerClass"),
                    "Test\$TestEnum" to testFixtureClassBytes("Test\$TestEnum"),
                ),
            )
        val original = clazz.getDeclaredConstructor(String::class.java).newInstance("original")
        val replacement = clazz.getDeclaredConstructor(String::class.java).newInstance("replacement")

        try {
            ModifyReceiverInferredMethodAndTestFieldReadMixin.replacement = replacement

            val result = clazz.getMethod("testA0").invoke(original)

            assertEquals("replacement", result)
        } finally {
            ModifyReceiverInferredMethodAndTestFieldReadMixin.replacement = null
        }
    }

    @Test
    @DisplayName("省略 method 与 FIELD target 时应按兼容 receiver 推断字段读取")
    fun modifyReceiverInfersMethodAndFieldReadTargetByCompatibleReceiverType() {
        // Given
        AsmRegistry.register(ModifyReceiverInferredMethodAndFieldReadMixin::class.java)
        val transformed = AsmProcessor().transform("FieldPointTarget", fieldPointTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("FieldPointTarget", transformed)
        val original = clazz.getDeclaredConstructor().newInstance()
        val replacement = clazz.getDeclaredConstructor().newInstance()

        try {
            clazz.getMethod("writeName", String::class.java).invoke(original, "original")
            clazz.getMethod("writeName", String::class.java).invoke(replacement, "replacement")
            ModifyReceiverInferredMethodAndFieldReadMixin.replacement = replacement

            // When
            val result = clazz.getMethod("readName").invoke(original)

            // Then
            assertThat(result)
                .`as`("Then: 省略 method 与字段 target 后，应按 handler 签名推断 readName 的实例字段 receiver")
                .isEqualTo("replacement")
        } finally {
            ModifyReceiverInferredMethodAndFieldReadMixin.replacement = null
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
    fun modifyReceiverAtFieldAssignInfersTargetInTestClassConstructor() {
        AsmRegistry.register(ModifyReceiverInferredTestFieldAssignMixin::class.java)

        val fixtureLoader = testFixtureClassLoader("Test", "TestParent", "TestInterface")
        val transformed = AsmProcessor().transform("Test", testFixtureClassBytes("Test"), fixtureLoader)
        val clazz =
            loadClasses(
                "Test",
                mapOf(
                    "Test" to transformed,
                    "TestParent" to testFixtureClassBytes("TestParent"),
                    "TestInterface" to testFixtureClassBytes("TestInterface"),
                    "TestFunctionalInterface" to testFixtureClassBytes("TestFunctionalInterface"),
                    "Test\$CustomException" to testFixtureClassBytes("Test\$CustomException"),
                    "Test\$InnerClass" to testFixtureClassBytes("Test\$InnerClass"),
                    "Test\$StaticInnerClass" to testFixtureClassBytes("Test\$StaticInnerClass"),
                    "Test\$TestEnum" to testFixtureClassBytes("Test\$TestEnum"),
                ),
            )
        val replacement = clazz.getDeclaredConstructor(String::class.java).newInstance("replacement")

        try {
            ModifyReceiverInferredTestFieldAssignMixin.replacement = replacement

            val original = clazz.getDeclaredConstructor(String::class.java).newInstance("original")

            assertEquals(null, clazz.getMethod("testA0").invoke(original))
            assertEquals("original", clazz.getMethod("testA0").invoke(replacement))
            assertEquals("original", ModifyReceiverInferredTestFieldAssignMixin.lastValue)
        } finally {
            ModifyReceiverInferredTestFieldAssignMixin.replacement = null
            ModifyReceiverInferredTestFieldAssignMixin.lastValue = null
        }
    }

    @Test
    @DisplayName("省略 method 与 FIELD_ASSIGN target 时应按兼容 receiver 推断字段写入")
    fun modifyReceiverInfersMethodAndFieldAssignTargetByCompatibleReceiverType() {
        // Given
        AsmRegistry.register(ModifyReceiverInferredMethodAndFieldAssignMixin::class.java)
        val transformed = AsmProcessor().transform("FieldPointTarget", fieldPointTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("FieldPointTarget", transformed)
        val original = clazz.getDeclaredConstructor().newInstance()
        val replacement = clazz.getDeclaredConstructor().newInstance()

        try {
            ModifyReceiverInferredMethodAndFieldAssignMixin.replacement = replacement

            // When
            clazz.getMethod("writeName", String::class.java).invoke(original, "redirected")

            // Then
            assertThat(clazz.getMethod("readName").invoke(original))
                .`as`("Then: 原 receiver 不应接收字段写入值")
                .isNull()
            assertThat(clazz.getMethod("readName").invoke(replacement))
                .`as`("Then: 兼容推断出的 replacement receiver 应保留原待写入字段值")
                .isEqualTo("redirected")
            assertThat(ModifyReceiverInferredMethodAndFieldAssignMixin.lastValue)
                .`as`("Then: handler 后续参数仍应按目标方法参数前缀传入，便于真实业务校验上下文")
                .isEqualTo("redirected")
        } finally {
            ModifyReceiverInferredMethodAndFieldAssignMixin.replacement = null
            ModifyReceiverInferredMethodAndFieldAssignMixin.lastValue = null
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
    fun wrapOperationSupportsKotlinInvokeSyntax() {
        AsmRegistry.register(WrapOperationInvokeSyntaxMixin::class.java)

        val transformed = AsmProcessor().transform("ModifyReceiverTarget", modifyReceiverTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ModifyReceiverTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("original-invoke-call", result)
    }

    @Test
    fun wrapOperationInfersTargetWhenMethodIsOmitted() {
        AsmRegistry.register(InferredWrapOperationTargetMixin::class.java)

        val transformed = AsmProcessor().transform("ModifyReceiverTarget", modifyReceiverTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ModifyReceiverTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("original-inferred-call", result)
    }

    @Test
    fun wrapOperationAtInvokeInfersTargetByCompatibleSignature() {
        AsmRegistry.register(WrapOperationInferredInvokeTargetMixin::class.java)

        val transformed =
            AsmProcessor().transform(
                "InferredInvokeExpressionValueTarget",
                inferredInvokeExpressionValueTargetBytes(),
                javaClass.classLoader,
            )
        val clazz = loadClass("InferredInvokeExpressionValueTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("raw-wrapped", result)
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
    fun wrapOperationAtInvokeCanCallOriginalInvokeDynamic() {
        AsmRegistry.register(WrapOperationInvokeDynamicMixin::class.java)

        val transformed =
            AsmProcessor().transform("InvokeDynamicExpressionValueTarget", invokeDynamicExpressionValueTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("InvokeDynamicExpressionValueTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value", String::class.java, Int::class.javaPrimitiveType).invoke(instance, "raw", 7)

        assertEquals("RAW-8-wrapped", result)
    }

    @Test
    fun wrapOperationAtLoadCanCallOriginalReadWithoutWritingBackSlot() {
        AsmRegistry.register(WrapOperationLoadMixin::class.java)

        val transformed =
            AsmProcessor().transform("LoadExpressionValueTarget", loadExpressionValueTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("LoadExpressionValueTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("wrap-raw:raw", result)
    }

    @Test
    fun wrapOperationAtStoreCanCallOriginalWriteValue() {
        AsmRegistry.register(WrapOperationStoreMixin::class.java)

        val transformed =
            AsmProcessor().transform("StoreExpressionValueTarget", storeExpressionValueTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("StoreExpressionValueTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("wrap-store-raw", result)
    }

    @Test
    fun wrapOperationAtLoadArgsNameLimitsLocalVariableName() {
        AsmRegistry.register(WrapOperationLoadNameMixin::class.java)

        val transformed =
            AsmProcessor().transform("NamedLoadVariableTarget", namedLoadVariableTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("NamedLoadVariableTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("first:wrap-second", result)
    }

    @Test
    fun wrapOperationAtStoreArgsNameLimitsLocalVariableName() {
        AsmRegistry.register(WrapOperationStoreNameMixin::class.java)

        val transformed =
            AsmProcessor().transform("NamedStoreVariableTarget", namedStoreVariableTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("NamedStoreVariableTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("first:wrap-store-second", result)
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
    fun wrapOperationAtInvokeAcceptsAssignableParentParameter() {
        AsmRegistry.register(WrapOperationParentParamMixin::class.java)

        val transformed = AsmProcessor().transform("ModifyReceiverTarget", modifyReceiverTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ModifyReceiverTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("original-call-parent", result)
    }

    @Test
    fun wrapOperationAtCastCanCallOriginalCastWithChangedValue() {
        AsmRegistry.register(WrapOperationCastMixin::class.java)

        val transformed = AsmProcessor().transform("CastInstructionTarget", castInstructionTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("CastInstructionTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("cast", Any::class.java).invoke(instance, StringBuilder("raw"))

        assertEquals("wrapped-raw-true", result)
    }

    @Test
    fun wrapOperationAtCastWithoutTargetUsesHandlerTypeCompatibleCheckcast() {
        AsmRegistry.register(AnyCastWrapOperationMixin::class.java)

        val transformed = AsmProcessor().transform("MultiCastInstructionTarget", multiCastInstructionTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("MultiCastInstructionTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val method = clazz.getMethod("cast", Any::class.java, Any::class.java)

        assertEquals("wrapped-raw-true", method.invoke(instance, StringBuilder("ignored"), "raw"))
    }

    @Test
    fun wrapOperationAtInstanceofCanCallOriginalCheckWithChangedValue() {
        AsmRegistry.register(WrapOperationInstanceofMixin::class.java)

        val transformed = AsmProcessor().transform("InstanceofTarget", instanceofTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("InstanceofTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        assertEquals(true, clazz.getMethod("isString", Any::class.java, Boolean::class.javaPrimitiveType).invoke(instance, StringBuilder("raw"), false))
        assertEquals(false, clazz.getMethod("isString", Any::class.java, Boolean::class.javaPrimitiveType).invoke(instance, StringBuilder("raw"), true))
    }

    @Test
    fun wrapOperationAtInstanceofWithoutTargetWrapsTypeChecks() {
        AsmRegistry.register(AnyInstanceofWrapOperationMixin::class.java)

        val transformed = AsmProcessor().transform("MultiInstanceofTarget", multiInstanceofTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("MultiInstanceofTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val method = clazz.getMethod("isString", Any::class.java, Any::class.java)

        assertEquals(true, method.invoke(instance, StringBuilder("ignored"), StringBuilder("raw")))
        assertEquals(false, method.invoke(instance, StringBuilder("ignored"), "raw"))
    }

    @Test
    fun wrapOperationAtConstantCanCallOriginalConstant() {
        AsmRegistry.register(WrapOperationConstantMixin::class.java)

        val transformed = AsmProcessor().transform("MixedConstantTarget", mixedConstantTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("MixedConstantTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("wrapped-original-original", result)
    }

    @Test
    fun wrapOperationAtConstantInfersTargetByCompatibleConstantType() {
        AsmRegistry.register(WrapOperationInferredConstantMixin::class.java)

        val transformed = AsmProcessor().transform("MixedConstantTarget", mixedConstantTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("MixedConstantTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("inferred-original", result)
    }

    @Test
    fun wrapOperationAtJumpCanCallOriginalBranchDecisionAndOverrideIt() {
        AsmRegistry.register(WrapOperationJumpMixin::class.java)

        val transformed = AsmProcessor().transform("JumpOperationTarget", jumpOperationTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("JumpOperationTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val method = clazz.getMethod("choose", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)

        assertEquals("positive", method.invoke(instance, 5, false))
        assertEquals("negative", method.invoke(instance, -1, false))
        assertEquals("negative", method.invoke(instance, 5, true))
    }

    @Test
    fun operationConstantCallReturnsOriginalValueWithoutArguments() {
        val operation: Operation<String> = Operation("original", String::class.java)

        assertEquals("original", operation.call())
        assertEquals(
            "Operation constant java.lang.String expects 0 argument(s), actual 1",
            assertThrows(IllegalArgumentException::class.java) {
                operation.call("unused")
            }.message,
        )
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
    fun wrapMethodAcceptsAssignableTargetParameterType() {
        AsmRegistry.register(WrapMethodAssignabilityTargetMixin::class.java)

        val transformed = AsmProcessor().transform("WrapMethodAssignabilityTarget", wrapMethodAssignabilityTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("WrapMethodAssignabilityTarget", transformed)
        val result = clazz.getMethod("value", String::class.java).invoke(null, "raw")

        assertEquals("wrapped:raw!", result)
    }

    @Test
    fun wrapMethodInfersTargetWhenHandlerUsesAssignableParameterType() {
        AsmRegistry.register(InferredWrapMethodAssignabilityTargetMixin::class.java)

        val transformed = AsmProcessor().transform("WrapMethodAssignabilityTarget", wrapMethodAssignabilityTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("WrapMethodAssignabilityTarget", transformed)
        val result = clazz.getMethod("value", String::class.java).invoke(null, "raw")

        assertEquals("inferred:raw!", result)
    }

    @Test
    fun wrapMethodInfersTargetWhenHandlerUsesGenericReferenceReturn() {
        AsmRegistry.register(InferredWrapMethodGenericReturnMixin::class.java)

        val transformed = AsmProcessor().transform("WrapMethodAssignabilityTarget", wrapMethodAssignabilityTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("WrapMethodAssignabilityTarget", transformed)
        val result = clazz.getMethod("value", String::class.java).invoke(null, "raw")

        assertEquals("generic:raw!", result)
    }

    @Test
    fun wrapMethodInferenceFailsWhenCompatibleOverloadsAreAmbiguous() {
        AsmRegistry.register(AmbiguousWrapMethodInferenceMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform(
                    "AmbiguousWrapMethodTarget",
                    ambiguousWrapMethodTargetBytes(),
                    javaClass.classLoader,
                )
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("matches multiple target methods") == true,
        )
        assertEquals(
            true,
            exception.cause?.message?.contains("Specify method explicitly to disambiguate") == true,
        )
    }

    @Test
    fun wrapMethodRequireGreaterThanMatchedCountFailsDuringTransform() {
        AsmRegistry.register(RequireTwoWrapMethodMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("WrapMethodStaticTarget", wrapMethodStaticTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("requires at least 2 injection(s), actual 1") == true,
        )
    }

    @Test
    fun wrapMethodAllowLessThanMatchedCountFailsDuringTransform() {
        AsmRegistry.register(AllowZeroWrapMethodMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("WrapMethodStaticTarget", wrapMethodStaticTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("allows at most 0 injection(s), actual 1") == true,
        )
    }

    @Test
    fun wrapMethodExpectMismatchReportsWarningWithoutFailingTransform() {
        AsmRegistry.register(ExpectTwoWrapMethodMixin::class.java)
        val originalErr = System.err
        val output = ByteArrayOutputStream()

        try {
            PrintStream(output, true, Charsets.UTF_8.name()).use { capture ->
                System.setErr(capture)
                AsmProcessor().transform("WrapMethodStaticTarget", wrapMethodStaticTargetBytes(), javaClass.classLoader)
            }
        } finally {
            System.setErr(originalErr)
        }

        assertEquals(
            true,
            output.toString(Charsets.UTF_8.name()).contains("expected 2 injection(s), actual 1"),
        )
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
    @DisplayName("WrapOperation 显式 INVOKE 空 Slice 边界应快速失败")
    fun wrapOperationEmptyInvokeSliceBoundaryFailsFast() {
        // Given
        AsmRegistry.register(WrapOperationEmptyInvokeSliceBoundaryMixin::class.java)

        // When / Then
        assertThatThrownBy {
            AsmProcessor().transform("SliceWrapOperationTarget", sliceWrapOperationTargetBytes(), javaClass.classLoader)
        }
            .`as`("Then: 显式声明 WrapOperation Slice INVOKE 边界但遗漏 target 时，应暴露配置错误而不是扩大到全方法")
            .isInstanceOf(AsmTransformException::class.java)
            .hasRootCauseMessage(
                "Invalid @WrapOperation slice boundary INVOKE target: target must not be empty",
            )
    }

    @Test
    @DisplayName("显式 INVOKE 空 Slice 边界应在所有注解注入器中快速失败")
    fun explicitEmptyInvokeSliceBoundaryFailsFastAcrossAnnotationInjectors() {
        // Given
        data class BoundaryCase(
            val context: String,
            val mixin: Class<*>,
            val targetClass: String,
            val targetBytes: () -> ByteArray,
        )

        val cases =
            listOf(
                BoundaryCase(
                    "@AsmInject(INVOKE/INVOKE_ASSIGN)",
                    EmptyInvokeSliceAsmInjectMixin::class.java,
                    "SliceInvokeTarget",
                ) { sliceInvokeTargetBytes() },
                BoundaryCase(
                    "@AsmInject instruction points",
                    EmptyInvokeSliceInstructionPointMixin::class.java,
                    "SliceFieldReadTarget",
                ) { sliceFieldReadTargetBytes() },
                BoundaryCase(
                    "@ModifyArg(INVOKE)",
                    EmptyInvokeSliceModifyArgMixin::class.java,
                    "SliceInvokeModifyArgTarget",
                ) { sliceInvokeModifyArgTargetBytes() },
                BoundaryCase(
                    "@ModifyArgs(INVOKE)",
                    EmptyInvokeSliceModifyArgsMixin::class.java,
                    "SliceModifyArgsTarget",
                ) { sliceModifyArgsTargetBytes() },
                BoundaryCase(
                    "@ModifyReceiver",
                    EmptyInvokeSliceModifyReceiverMixin::class.java,
                    "SliceModifyReceiverTarget",
                ) { sliceModifyReceiverTargetBytes() },
                BoundaryCase(
                    "@ModifyVariable(LOAD/STORE)",
                    EmptyInvokeSliceModifyVariableMixin::class.java,
                    "SliceLoadVariableTarget",
                ) { sliceLoadVariableTargetBytes() },
                BoundaryCase(
                    "@ModifyReturnValue",
                    EmptyInvokeSliceModifyReturnValueMixin::class.java,
                    "SliceReturnValueTarget",
                ) { sliceReturnValueTargetBytes() },
                BoundaryCase(
                    "@ModifyExpressionValue",
                    ModifyExpressionValueEmptyInvokeSliceBoundaryMixin::class.java,
                    "SliceExpressionValueTarget",
                ) { sliceExpressionValueTargetBytes() },
                BoundaryCase(
                    "@Redirect",
                    EmptyInvokeSliceRedirectMixin::class.java,
                    "RedirectSliceTarget",
                ) { redirectSliceTargetBytes() },
                BoundaryCase(
                    "@WrapOperation",
                    WrapOperationEmptyInvokeSliceBoundaryMixin::class.java,
                    "SliceWrapOperationTarget",
                ) { sliceWrapOperationTargetBytes() },
                BoundaryCase(
                    "@WrapWithCondition",
                    EmptyInvokeSliceWrapWithConditionMixin::class.java,
                    "SliceWrapConditionTarget",
                ) { sliceWrapConditionTargetBytes() },
            )

        cases.forEach { case ->
            // Given
            AsmRegistry.clear()
            AsmRegistry.register(case.mixin)

            // When / Then
            assertThatThrownBy {
                AsmProcessor().transform(case.targetClass, case.targetBytes(), javaClass.classLoader)
            }
                .`as`("Then: ${case.context} 显式声明 INVOKE 边界但遗漏 target 时，应暴露配置错误")
                .isInstanceOf(AsmTransformException::class.java)
                .hasRootCauseMessage(
                    "Invalid ${case.context} slice boundary INVOKE target: target must not be empty",
                )
        }
    }

    @Test
    @DisplayName("自动目标推断时显式 INVOKE 空 Slice 边界应保留配置错误")
    fun inferredTargetEmptyInvokeSliceBoundaryPreservesConfigurationError() {
        // Given
        data class BoundaryCase(
            val context: String,
            val mixin: Class<*>,
            val targetClass: String,
            val targetBytes: () -> ByteArray,
        )

        val cases =
            listOf(
                BoundaryCase(
                    "@AsmInject(INVOKE/INVOKE_ASSIGN)",
                    InferredTargetEmptyInvokeSliceAsmInjectMixin::class.java,
                    "SliceInvokeTarget",
                ) { sliceInvokeTargetBytes() },
                BoundaryCase(
                    "@AsmInject instruction points",
                    InferredTargetEmptyInvokeSliceInstructionPointMixin::class.java,
                    "SliceFieldReadTarget",
                ) { sliceFieldReadTargetBytes() },
                BoundaryCase(
                    "@ModifyArg(INVOKE)",
                    InferredTargetEmptyInvokeSliceModifyArgMixin::class.java,
                    "SliceInvokeModifyArgTarget",
                ) { sliceInvokeModifyArgTargetBytes() },
                BoundaryCase(
                    "@ModifyArgs(INVOKE)",
                    InferredTargetEmptyInvokeSliceModifyArgsMixin::class.java,
                    "SliceModifyArgsTarget",
                ) { sliceModifyArgsTargetBytes() },
                BoundaryCase(
                    "@ModifyReceiver",
                    InferredTargetEmptyInvokeSliceModifyReceiverMixin::class.java,
                    "SliceModifyReceiverTarget",
                ) { sliceModifyReceiverTargetBytes() },
                BoundaryCase(
                    "@ModifyVariable(LOAD/STORE)",
                    InferredTargetEmptyInvokeSliceModifyVariableMixin::class.java,
                    "SliceLoadVariableTarget",
                ) { sliceLoadVariableTargetBytes() },
                BoundaryCase(
                    "@ModifyReturnValue",
                    InferredTargetEmptyInvokeSliceModifyReturnValueMixin::class.java,
                    "SliceReturnValueTarget",
                ) { sliceReturnValueTargetBytes() },
                BoundaryCase(
                    "@ModifyExpressionValue",
                    InferredTargetEmptyInvokeSliceModifyExpressionValueMixin::class.java,
                    "SliceExpressionValueTarget",
                ) { sliceExpressionValueTargetBytes() },
                BoundaryCase(
                    "@Redirect",
                    InferredTargetEmptyInvokeSliceRedirectMixin::class.java,
                    "RedirectSliceTarget",
                ) { redirectSliceTargetBytes() },
                BoundaryCase(
                    "@WrapOperation",
                    InferredTargetEmptyInvokeSliceWrapOperationMixin::class.java,
                    "SliceWrapOperationTarget",
                ) { sliceWrapOperationTargetBytes() },
                BoundaryCase(
                    "@WrapWithCondition",
                    InferredTargetEmptyInvokeSliceWrapWithConditionMixin::class.java,
                    "SliceWrapConditionTarget",
                ) { sliceWrapConditionTargetBytes() },
            )

        cases.forEach { case ->
            // Given
            AsmRegistry.clear()
            AsmRegistry.register(case.mixin)

            // When / Then
            assertThatThrownBy {
                AsmProcessor().transform(case.targetClass, case.targetBytes(), javaClass.classLoader)
            }
                .`as`("Then: ${case.context} 自动推断目标方法时，也应保留显式空 INVOKE Slice 边界的配置错误")
                .isInstanceOf(AsmTransformException::class.java)
                .hasRootCauseMessage(
                    "Invalid ${case.context} slice boundary INVOKE target: target must not be empty",
                )
        }
    }

    @Test
    fun wrapOperationFieldSliceLimitsFieldReadsBetweenFromAndTo() {
        AsmRegistry.register(WrapOperationFieldReadSliceMixin::class.java)

        val transformed = AsmProcessor().transform("SliceFieldReadTarget", sliceFieldReadTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("SliceFieldReadTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeName", String::class.java).invoke(instance, "raw")
        val result = clazz.getMethod("readSelected").invoke(instance)

        assertEquals("raw-wrapped", result)
    }

    @Test
    fun wrapOperationFieldAssignSliceLimitsFieldWritesBetweenFromAndTo() {
        AsmRegistry.register(WrapOperationFieldAssignSliceMixin::class.java)

        val transformed = AsmProcessor().transform("SliceFieldAssignTarget", sliceFieldAssignTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("SliceFieldAssignTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeSelected", String::class.java, String::class.java).invoke(instance, "outside", "inside")
        val result = clazz.getField("name").get(instance)

        assertEquals("wrapped-inside", result)
    }

    @Test
    fun wrapOperationArrayReadSliceLimitsLoadsBetweenFromAndTo() {
        AsmRegistry.register(WrapOperationArrayReadSliceMixin::class.java)

        val transformed =
            AsmProcessor().transform("SliceArrayExpressionValueTarget", sliceArrayExpressionValueTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("SliceArrayExpressionValueTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("readSelected", Int::class.javaPrimitiveType).invoke(instance, 0)

        assertEquals("wrapped-raw", result)
    }

    @Test
    fun wrapOperationArrayLengthSliceLimitsLengthsBetweenFromAndTo() {
        AsmRegistry.register(WrapOperationArrayLengthSliceMixin::class.java)

        val transformed =
            AsmProcessor().transform("SliceArrayExpressionValueTarget", sliceArrayExpressionValueTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("SliceArrayExpressionValueTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("countSelected").invoke(instance)

        assertEquals(6, result)
    }

    @Test
    fun wrapOperationArrayWriteSliceLimitsStoresBetweenFromAndTo() {
        AsmRegistry.register(WrapOperationArrayWriteSliceMixin::class.java)

        val transformed =
            AsmProcessor().transform("SliceWrapConditionArrayTarget", sliceWrapConditionArrayTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("SliceWrapConditionArrayTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("writeSelected").invoke(instance)

        assertEquals("pre:wrapped-inside:outside", result)
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
    fun wrapOperationAtNewCanCallOriginalConstructorWithChangedArguments() {
        AsmRegistry.register(WrapOperationNewConstructorMixin::class.java)

        val transformed =
            AsmProcessor().transform("ConstructorModifyArgTarget", constructorModifyArgTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ConstructorModifyArgTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("new-raw", result)
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
    fun wrapOperationAtFieldInfersTargetByCompatibleOperationType() {
        AsmRegistry.register(WrapOperationInferredFieldReadMixin::class.java)

        val transformed =
            AsmProcessor().transform(
                "MixedFieldExpressionValueTarget",
                mixedFieldExpressionValueTargetBytes(),
                javaClass.classLoader,
            )
        val clazz = loadClass("MixedFieldExpressionValueTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeValues", String::class.java, Int::class.javaPrimitiveType)
            .invoke(instance, "raw", 7)
        val result = clazz.getMethod("readSelected").invoke(instance)

        assertEquals("wrapped-inferred-raw", result)
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
    fun wrapOperationAtFieldAssignInfersTargetInTestClassConstructor() {
        AsmRegistry.register(WrapOperationInferredTestFieldAssignMixin::class.java)

        val fixtureLoader = testFixtureClassLoader("Test", "TestParent", "TestInterface")
        val transformed = AsmProcessor().transform("Test", testFixtureClassBytes("Test"), fixtureLoader)
        val clazz =
            loadClasses(
                "Test",
                mapOf(
                    "Test" to transformed,
                    "TestParent" to testFixtureClassBytes("TestParent"),
                    "TestInterface" to testFixtureClassBytes("TestInterface"),
                    "TestFunctionalInterface" to testFixtureClassBytes("TestFunctionalInterface"),
                    "Test\$CustomException" to testFixtureClassBytes("Test\$CustomException"),
                    "Test\$InnerClass" to testFixtureClassBytes("Test\$InnerClass"),
                    "Test\$StaticInnerClass" to testFixtureClassBytes("Test\$StaticInnerClass"),
                    "Test\$TestEnum" to testFixtureClassBytes("Test\$TestEnum"),
                ),
            )

        val instance = clazz.getDeclaredConstructor(String::class.java).newInstance("raw")
        val result = clazz.getMethod("testA0").invoke(instance)

        assertEquals("wrapped-test-raw", result)
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
    fun wrapOperationArrayReadAcceptsGenericObjectReturnType() {
        AsmRegistry.register(WrapOperationArrayReadObjectReturnMixin::class.java)

        val transformed = AsmProcessor().transform("ArrayAccessTarget", arrayAccessTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ArrayAccessTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("readName", Int::class.javaPrimitiveType).invoke(instance, 0)

        assertEquals("object-raw", result)
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
    fun wrapOperationArrayReadWithIncompatibleReturnTypeFailsDuringTransform() {
        AsmRegistry.register(IncompatibleWrapOperationArrayReadReturnMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("ArrayAccessTarget", arrayAccessTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("return type mismatch") == true,
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
    fun modifyConstantInfersTargetWhenMethodIsOmitted() {
        AsmRegistry.register(InferredModifyConstantTargetMixin::class.java)

        val transformed = AsmProcessor().transform("MixedConstantTarget", mixedConstantTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("MixedConstantTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("inferred-original", result)
    }

    @Test
    fun modifyConstantAcceptsGenericObjectReturnTypeForStringConstant() {
        AsmRegistry.register(StringConstantGenericReturnMixin::class.java)

        val transformed = AsmProcessor().transform("MixedConstantTarget", mixedConstantTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("MixedConstantTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("generic-original", result)
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
    fun modifyConstantSliceSupportsInvokeDynamicBoundaries() {
        AsmRegistry.register(ModifyConstantInvokeDynamicSliceMixin::class.java)

        val transformed =
            AsmProcessor().transform(
                "InvokeDynamicSliceConstantTarget",
                invokeDynamicSliceConstantTargetBytes(),
                javaClass.classLoader,
            )
        val clazz = loadClass("InvokeDynamicSliceConstantTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value", String::class.java).invoke(instance, "marker")

        assertEquals("target:changed:target", result)
    }

    @Nested
    @DisplayName("@AsmInject INVOKE_STRING 字符串调用点场景")
    inner class AsmInjectInvokeStringScenarios {
        @Test
        @DisplayName("直接字符串实参应只命中包含 marker 的目标调用")
        fun directStringArgumentMatchesOnlyMarkedInvocation() {
            // Given
            AsmRegistry.register(InvokeStringMarkerMixin::class.java)

            // When
            val transformed = AsmProcessor().transform("InvokeStringTarget", invokeStringTargetBytes(), javaClass.classLoader)
            val classNode = readClass(transformed)
            val method = classNode.methods.single { it.name == "run" && it.desc == "()V" }
            val instructions = method.instructions.toArray()
            val mixinOwner = org.objectweb.asm.Type.getInternalName(InvokeStringMarkerMixin::class.java)
            val handlerCallIndexes = instructions.mapIndexedNotNull { index, insn ->
                if (insn is org.objectweb.asm.tree.MethodInsnNode && insn.owner == mixinOwner && insn.name == "inject") {
                    index
                } else {
                    null
                }
            }
            val targetCallIndexes = instructions.mapIndexedNotNull { index, insn ->
                if (
                    insn is org.objectweb.asm.tree.MethodInsnNode &&
                    insn.owner == "InvokeStringTarget" &&
                    insn.name == "target" &&
                    insn.desc == "(Ljava/lang/String;)V"
                ) {
                    index
                } else {
                    null
                }
            }
            fun previousNonHandlerRealInstructionIndex(callIndex: Int): Int {
                for (index in callIndex - 1 downTo 0) {
                    val insn = instructions[index]
                    val isHandlerCall =
                        insn is org.objectweb.asm.tree.MethodInsnNode &&
                            insn.owner == mixinOwner &&
                            insn.name == "inject"
                    if (insn.opcode >= 0 && !isHandlerCall) {
                        return index
                    }
                }
                return -1
            }
            val directMarkerCallIndexes = targetCallIndexes.filter { callIndex ->
                val previousIndex = previousNonHandlerRealInstructionIndex(callIndex)
                val previous = instructions.getOrNull(previousIndex)
                previous is org.objectweb.asm.tree.LdcInsnNode && previous.cst == "marker"
            }

            // Then
            assertThat(targetCallIndexes)
                .`as`("Given: 目标方法存在三次相同调用，覆盖非 marker、直接 marker 与同值局部变量三种业务形态")
                .hasSize(3)
            assertThat(directMarkerCallIndexes)
                .`as`("Given: 只有一次目标调用的上一个业务指令是直接 LDC 字符串 marker")
                .hasSize(1)
            assertThat(handlerCallIndexes)
                .`as`("Then: INVOKE_STRING 只应在包含 marker 直接字符串实参的调用点前插入 handler")
                .hasSize(1)
            val directMarkerCallIndex = directMarkerCallIndexes.single()
            val directMarkerLoadIndex = previousNonHandlerRealInstructionIndex(directMarkerCallIndex)
            val handlerCallIndex = handlerCallIndexes.single()
            assertThat(handlerCallIndex)
                .`as`("Then: handler 应位于直接 marker 常量加载之后、目标调用之前，不应命中同值局部变量调用")
                .isGreaterThan(directMarkerLoadIndex)
                .isLessThan(directMarkerCallIndex)
        }

        @Test
        @DisplayName("缺少 ldc 参数时应在转换阶段暴露配置错误")
        fun missingLdcArgumentFailsWithClearConfigurationError() {
            // Given
            AsmRegistry.register(MissingInvokeStringArgumentMixin::class.java)

            // When / Then
            assertThatThrownBy {
                AsmProcessor().transform("InvokeStringTarget", invokeStringTargetBytes(), javaClass.classLoader)
            }
                .`as`("Then: INVOKE_STRING 必须显式声明字符串实参过滤，避免退化成宽泛 INVOKE 匹配")
                .isInstanceOf(AsmTransformException::class.java)
                .hasRootCauseMessage("@AsmInject(INVOKE_STRING) requires at.args entry ldc=<string> or string=<string>")
        }

        @Test
        @DisplayName("额外 args 参数应在转换阶段暴露配置错误")
        fun extraInvokeStringArgumentFailsWithClearConfigurationError() {
            // Given
            AsmRegistry.register(ExtraInvokeStringArgumentMixin::class.java)

            // When / Then
            assertThatThrownBy {
                AsmProcessor().transform("InvokeStringTarget", invokeStringTargetBytes(), javaClass.classLoader)
            }
                .`as`("Then: INVOKE_STRING 只允许一个 ldc/string 过滤参数，避免误配置被静默忽略")
                .isInstanceOf(AsmTransformException::class.java)
                .hasRootCauseMessage(
                    "@AsmInject(INVOKE_STRING) supports only one at.args entry ldc=<string> or string=<string>",
                )
        }

        @Test
        @DisplayName("缺少直接 marker 字符串调用点时应按命中数契约失败")
        fun missingDirectMarkerArgumentFailsByInjectionCountContract() {
            // Given
            AsmRegistry.register(InvokeStringMarkerMixin::class.java)

            // When / Then
            assertThatThrownBy {
                AsmProcessor().transform(
                    "InvokeStringTarget",
                    invokeStringTargetBytes(includeDirectMarker = false),
                    javaClass.classLoader,
                )
            }
                .`as`("Then: INVOKE_STRING 只能匹配直接字符串常量，不能退回同值局部变量或普通 INVOKE")
                .isInstanceOf(AsmTransformException::class.java)
                .hasRootCauseMessage(
                    "@AsmInject handler inject requires at least 1 injection(s), " +
                        "actual 0 in target method run()V of class InvokeStringTarget",
                )
        }

        @Test
        @DisplayName("省略 owner 的 target 应在转换阶段暴露配置错误")
        fun ownerlessTargetFailsWithClearConfigurationError() {
            // Given
            AsmRegistry.register(OwnerlessInvokeStringTargetMixin::class.java)

            // When / Then
            assertThatThrownBy {
                AsmProcessor().transform("InvokeStringTarget", invokeStringTargetBytes(), javaClass.classLoader)
            }
                .`as`("Then: INVOKE_STRING 的目标调用必须精确到 owner，避免同名同签名调用误命中")
                .isInstanceOf(AsmTransformException::class.java)
                .hasRootCauseMessage(
                    "@AsmInject(INVOKE_STRING) requires at.target owner.name(desc): target(Ljava/lang/String;)V",
                )
        }

        @Test
        @DisplayName("包含 long 参数的调用仍应命中直接字符串实参")
        fun longArgumentBeforeDirectStringArgumentStillMatches() {
            // Given
            AsmRegistry.register(InvokeStringLongArgumentMixin::class.java)

            // When
            val transformed = AsmProcessor().transform(
                "InvokeStringLongArgumentTarget",
                invokeStringLongArgumentTargetBytes(),
                javaClass.classLoader,
            )
            val classNode = readClass(transformed)
            val method = classNode.methods.single { it.name == "run" && it.desc == "()V" }
            val mixinOwner = org.objectweb.asm.Type.getInternalName(InvokeStringLongArgumentMixin::class.java)
            val handlerCallCount = method.instructions.toArray().count { insn ->
                insn is org.objectweb.asm.tree.MethodInsnNode &&
                    insn.owner == mixinOwner &&
                    insn.name == "inject"
            }

            // Then
            assertThat(handlerCallCount)
                .`as`("Then: ASM Frame 按值而非 JVM slot 计数，long 参数不应导致 marker 实参漏匹配")
                .isEqualTo(1)
        }
    }

    @Nested
    @DisplayName("@WrapWithCondition INVOKE_ASSIGN 调用返回值场景")
    inner class WrapWithConditionInvokeAssignScenarios {
        @Test
        @DisplayName("handler 拒绝时应保留原调用副作用并替换返回表达式")
        fun wrapWithConditionAtInvokeAssignKeepsCallSideEffectAndUsesDefaultWhenFalse() {
            // Given
            AsmRegistry.register(WrapConditionInvokeAssignByTargetParamsMixin::class.java)

            // When
            val transformed =
                AsmProcessor().transform(
                    "InvokeAssignConditionTarget",
                    invokeAssignConditionTargetBytes(),
                    javaClass.classLoader,
                )
            val clazz = loadClass("InvokeAssignConditionTarget", transformed)
            val instance = clazz.getDeclaredConstructor().newInstance()
            val method = clazz.getMethod("value", Boolean::class.javaPrimitiveType, String::class.java)
            val result = method.invoke(instance, false, "deny")

            // Then
            assertThat(result)
                .`as`("Then: INVOKE_ASSIGN false 分支只替换调用完成后的 String 返回值，不应跳过后续 concat")
                .isEqualTo("-done")
            assertThat(clazz.getField("counter").getInt(instance))
                .`as`("Then: 原 produce 调用必须已经执行，副作用计数应保留下来")
                .isEqualTo(1)
        }

        @Test
        @DisplayName("handler 放行时可使用目标方法参数前缀保留返回表达式")
        fun wrapWithConditionAtInvokeAssignCanUseTargetMethodParameters() {
            // Given
            AsmRegistry.register(WrapConditionInvokeAssignByTargetParamsMixin::class.java)

            // When
            val transformed =
                AsmProcessor().transform(
                    "InvokeAssignConditionTarget",
                    invokeAssignConditionTargetBytes(),
                    javaClass.classLoader,
                )
            val clazz = loadClass("InvokeAssignConditionTarget", transformed)
            val instance = clazz.getDeclaredConstructor().newInstance()
            val method = clazz.getMethod("value", Boolean::class.javaPrimitiveType, String::class.java)
            val result = method.invoke(instance, true, "keep")

            // Then
            assertThat(result)
                .`as`("Then: 目标方法参数满足业务条件时应保留 produce 的真实返回值")
                .isEqualTo("keep-1-done")
            assertThat(clazz.getField("counter").getInt(instance))
                .`as`("Then: handler 放行不应重复执行或跳过原调用")
                .isEqualTo(1)
        }

        @Test
        @DisplayName("省略 target 时应只包裹兼容的非 void 调用返回值")
        fun wrapWithConditionAtInvokeAssignWithoutTargetSkipsIncompatibleReturnTypes() {
            // Given
            AsmRegistry.register(WrapConditionInvokeAssignInferredStringMixin::class.java)

            // When
            val transformed =
                AsmProcessor().transform(
                    "InferredInvokeExpressionValueTarget",
                    inferredInvokeExpressionValueTargetBytes(),
                    javaClass.classLoader,
                )
            val classNode = readClass(transformed)
            val methodNode = classNode.methods.single { it.name == "value" && it.desc == "()Ljava/lang/String;" }
            val mixinOwner = org.objectweb.asm.Type.getInternalName(WrapConditionInvokeAssignInferredStringMixin::class.java)
            val handlerCallCount = methodNode.instructions.toArray().count { insn ->
                insn is org.objectweb.asm.tree.MethodInsnNode &&
                    insn.owner == mixinOwner &&
                    insn.name == "shouldKeep"
            }
            val clazz = loadClass("InferredInvokeExpressionValueTarget", transformed)
            val instance = clazz.getDeclaredConstructor().newInstance()
            val result = clazz.getMethod("value").invoke(instance)

            // Then
            assertThat(handlerCallCount)
                .`as`("Then: handler 首参为 String 时不应包裹 StringBuilder 返回值候选")
                .isEqualTo(1)
            assertThat(result)
                .`as`("Then: 唯一兼容的 String 调用返回值被拒绝后应替换为默认空字符串")
                .isEqualTo("")
        }

        @Test
        @DisplayName("显式命中 void 调用时应暴露配置错误")
        fun wrapWithConditionAtInvokeAssignRejectsVoidCall() {
            // Given
            AsmRegistry.register(WrapConditionInvokeAssignVoidCallMixin::class.java)

            // When / Then
            assertThatThrownBy {
                AsmProcessor().transform("WrapConditionStaticTarget", wrapConditionStaticTargetBytes(), javaClass.classLoader)
            }
                .`as`("Then: INVOKE_ASSIGN 表示调用返回表达式，不能用于没有返回值的 void 调用")
                .isInstanceOf(AsmTransformException::class.java)
                .hasRootCauseMessage(
                    "@WrapWithCondition INVOKE_ASSIGN cannot conditionally keep void call " +
                        "WrapConditionStaticTarget.record(Ljava/lang/String;)V",
                )
        }
    }

    @Nested
    @DisplayName("@WrapWithCondition NEW 构造结果场景")
    inner class WrapWithConditionNewScenarios {
        @Test
        @DisplayName("handler 放行时应保留构造完成后的对象引用")
        fun wrapWithConditionAtNewKeepsConstructedObjectWhenTrue() {
            // Given
            AsmRegistry.register(WrapConditionNewAllowMixin::class.java)

            // When
            val transformed = AsmProcessor().transform("NewInstructionTarget", newInstructionTargetBytes(), javaClass.classLoader)
            val clazz = loadClass("NewInstructionTarget", transformed)
            val instance = clazz.getDeclaredConstructor().newInstance()
            val method = clazz.getMethod("create")

            // Then
            val result = method.invoke(instance)
            assertThat(result)
                .`as`("Then: handler 返回 true 时 NEW 条件包裹应保留已初始化的 StringBuilder 对象")
                .isInstanceOf(StringBuilder::class.java)
            assertThat(result.toString())
                .`as`("Then: handler 接收到已初始化对象后产生的状态修改应保留在原对象上")
                .isEqualTo("kept")
        }

        @Test
        @DisplayName("handler 拒绝时应把构造表达式替换为 null")
        fun wrapWithConditionAtNewUsesNullWhenHandlerReturnsFalse() {
            // Given
            AsmRegistry.register(WrapConditionNewDenyMixin::class.java)

            // When
            val transformed = AsmProcessor().transform("NewInstructionTarget", newInstructionTargetBytes(), javaClass.classLoader)
            val clazz = loadClass("NewInstructionTarget", transformed)
            val instance = clazz.getDeclaredConstructor().newInstance()
            val method = clazz.getMethod("create")

            // Then
            assertThat(method.invoke(instance))
                .`as`("Then: NEW 构造结果是引用表达式，handler 返回 false 时应留下默认值 null")
                .isNull()
        }

        @Test
        @DisplayName("handler 可追加接收目标方法参数前缀")
        fun wrapWithConditionAtNewCanUseTargetMethodParameters() {
            // Given
            AsmRegistry.register(WrapConditionNewTargetParamsMixin::class.java)

            // When
            val transformed = AsmProcessor().transform("NewParamTarget", newParamTargetBytes(), javaClass.classLoader)
            val clazz = loadClass("NewParamTarget", transformed)
            val instance = clazz.getDeclaredConstructor().newInstance()
            val method = clazz.getMethod("create", String::class.java, Int::class.javaPrimitiveType)

            // Then
            assertThat(method.invoke(instance, "prefix", 6).toString())
                .`as`("Then: handler 应能用目标方法参数决定是否保留构造完成后的对象")
                .isEqualTo("prefix-6")
            assertThat(method.invoke(instance, "prefix", 7))
                .`as`("Then: 目标方法参数不满足业务条件时应把构造表达式替换为 null")
                .isNull()
        }

        @Test
        @DisplayName("省略 target 时应只包裹 handler 兼容的 NEW")
        fun wrapWithConditionAtNewWithoutTargetSkipsIncompatibleConstructions() {
            // Given
            AsmRegistry.register(WrapConditionNewInferredAllowMixin::class.java)

            // When
            val transformed =
                AsmProcessor().transform(
                    "MixedNewExpressionValueTarget",
                    mixedNewExpressionValueTargetBytes(),
                    javaClass.classLoader,
                )
            val classNode = readClass(transformed)
            val methodNode = classNode.methods.single { it.name == "value" && it.desc == "()Ljava/lang/String;" }
            val mixinOwner = org.objectweb.asm.Type.getInternalName(WrapConditionNewInferredAllowMixin::class.java)
            val handlerCallCount = methodNode.instructions.toArray().count { insn ->
                insn is org.objectweb.asm.tree.MethodInsnNode &&
                    insn.owner == mixinOwner &&
                    insn.name == "shouldKeep"
            }
            val clazz = loadClass("MixedNewExpressionValueTarget", transformed)
            val instance = clazz.getDeclaredConstructor().newInstance()
            val method = clazz.getMethod("value")

            // Then
            assertThat(handlerCallCount)
                .`as`("Then: handler 首参为 StringBuilder 时不应包裹 StringBuffer 的 NEW 候选")
                .isEqualTo(1)
            assertThat(method.invoke(instance))
                .`as`("Then: 唯一兼容的 StringBuilder NEW 被放行后应保留原对象状态")
                .isEqualTo("original-kept")
        }

        @Test
        @DisplayName("Slice 应只包裹边界内的构造表达式")
        fun wrapWithConditionAtNewRespectsSliceBoundary() {
            // Given
            AsmRegistry.register(WrapConditionNewSliceDenyMixin::class.java)

            // When
            val transformed =
                AsmProcessor().transform(
                    "SliceNewExpressionValueTarget",
                    sliceNewExpressionValueTargetBytes(),
                    javaClass.classLoader,
                )
            val classNode = readClass(transformed)
            val methodNode = classNode.methods.single { it.name == "createSelected" && it.desc == "()Ljava/lang/StringBuilder;" }
            val mixinOwner = org.objectweb.asm.Type.getInternalName(WrapConditionNewSliceDenyMixin::class.java)
            val handlerCallCount = methodNode.instructions.toArray().count { insn ->
                insn is org.objectweb.asm.tree.MethodInsnNode &&
                    insn.owner == mixinOwner &&
                    insn.name == "shouldKeep"
            }
            val clazz = loadClass("SliceNewExpressionValueTarget", transformed)
            val instance = clazz.getDeclaredConstructor().newInstance()
            val method = clazz.getMethod("createSelected")

            // Then
            assertThat(handlerCallCount)
                .`as`("Then: Slice 边界内只有第二个 StringBuilder NEW，应只插入一次条件包裹")
                .isEqualTo(1)
            assertThat(method.invoke(instance))
                .`as`("Then: 边界内 NEW 被拒绝后应返回 null，边界外 POP 掉的构造不应被包裹")
                .isNull()
        }

        @Test
        @DisplayName("handler 首参类型不兼容时应暴露签名错误")
        fun mismatchedWrapWithConditionAtNewFailsWithClearMessage() {
            // Given
            AsmRegistry.register(MismatchedWrapConditionNewMixin::class.java)

            // When / Then
            assertThatThrownBy {
                AsmProcessor().transform("NewInstructionTarget", newInstructionTargetBytes(), javaClass.classLoader)
            }
                .`as`("Then: NEW 条件包裹必须接收构造完成后的对象类型，不能用不兼容的类型误接")
                .isInstanceOf(AsmTransformException::class.java)
                .hasRootCauseMessage(
                    "@WrapWithCondition NEW handler shouldKeep parameter #0 mismatch: " +
                        "expected Ljava/lang/StringBuilder;, actual Ljava/lang/String;",
                )
        }
    }

    @Nested
    @DisplayName("@WrapWithCondition CAST 类型转换场景")
    inner class WrapWithConditionCastScenarios {
        @Test
        @DisplayName("handler 放行时应保留 CHECKCAST 后的引用")
        fun wrapWithConditionAtCastKeepsCastedValueWhenTrue() {
            // Given
            AsmRegistry.register(WrapConditionCastAllowMixin::class.java)

            // When
            val transformed = AsmProcessor().transform("CastInstructionTarget", castInstructionTargetBytes(), javaClass.classLoader)
            val clazz = loadClass("CastInstructionTarget", transformed)
            val instance = clazz.getDeclaredConstructor().newInstance()
            val method = clazz.getMethod("cast", Any::class.java)

            // Then
            assertThat(method.invoke(instance, "raw"))
                .`as`("Then: handler 返回 true 时 CAST 条件包裹应恢复原始转换后的 String 引用")
                .isEqualTo("raw")
        }

        @Test
        @DisplayName("handler 拒绝时应把转换表达式替换为 null")
        fun wrapWithConditionAtCastUsesNullWhenHandlerReturnsFalse() {
            // Given
            AsmRegistry.register(WrapConditionCastDenyMixin::class.java)

            // When
            val transformed = AsmProcessor().transform("CastInstructionTarget", castInstructionTargetBytes(), javaClass.classLoader)
            val clazz = loadClass("CastInstructionTarget", transformed)
            val instance = clazz.getDeclaredConstructor().newInstance()
            val method = clazz.getMethod("cast", Any::class.java)

            // Then
            assertThat(method.invoke(instance, "raw"))
                .`as`("Then: CAST 是引用表达式，handler 返回 false 时应留下默认值 null")
                .isNull()
        }

        @Test
        @DisplayName("省略 target 时应只包裹 handler 兼容的 CHECKCAST")
        fun wrapWithConditionAtCastWithoutTargetSkipsIncompatibleCheckcasts() {
            // Given
            AsmRegistry.register(WrapConditionAnyCastDenyMixin::class.java)

            // When
            val transformed = AsmProcessor().transform("MultiCastInstructionTarget", multiCastInstructionTargetBytes(), javaClass.classLoader)
            val classNode = readClass(transformed)
            val methodNode = classNode.methods.single { it.name == "cast" && it.desc == "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/String;" }
            val mixinOwner = org.objectweb.asm.Type.getInternalName(WrapConditionAnyCastDenyMixin::class.java)
            val handlerCallCount = methodNode.instructions.toArray().count { insn ->
                insn is org.objectweb.asm.tree.MethodInsnNode &&
                    insn.owner == mixinOwner &&
                    insn.name == "shouldKeep"
            }
            val clazz = loadClass("MultiCastInstructionTarget", transformed)
            val instance = clazz.getDeclaredConstructor().newInstance()
            val method = clazz.getMethod("cast", Any::class.java, Any::class.java)

            // Then
            assertThat(handlerCallCount)
                .`as`("Then: handler 首参为 String 时不应包裹 StringBuilder 的 CHECKCAST 候选")
                .isEqualTo(1)
            assertThat(method.invoke(instance, StringBuilder("ignored"), "raw"))
                .`as`("Then: 唯一兼容的 String CAST 被拒绝后，方法应返回 null")
                .isNull()
        }

        @Test
        @DisplayName("Slice 应只包裹边界内的类型转换")
        fun wrapWithConditionAtCastRespectsSliceBoundary() {
            // Given
            AsmRegistry.register(WrapConditionCastSliceDenyMixin::class.java)

            // When
            val transformed =
                AsmProcessor().transform(
                    "SliceCastInstructionTarget",
                    sliceCastInstructionTargetBytes(),
                    javaClass.classLoader,
                )
            val classNode = readClass(transformed)
            val methodNode = classNode.methods.single { it.name == "castSelected" && it.desc == "(Ljava/lang/Object;)Ljava/lang/String;" }
            val mixinOwner = org.objectweb.asm.Type.getInternalName(WrapConditionCastSliceDenyMixin::class.java)
            val handlerCallCount = methodNode.instructions.toArray().count { insn ->
                insn is org.objectweb.asm.tree.MethodInsnNode &&
                    insn.owner == mixinOwner &&
                    insn.name == "shouldKeep"
            }
            val clazz = loadClass("SliceCastInstructionTarget", transformed)
            val instance = clazz.getDeclaredConstructor().newInstance()
            val method = clazz.getMethod("castSelected", Any::class.java)

            // Then
            assertThat(handlerCallCount)
                .`as`("Then: Slice 边界内只有第二个 String CHECKCAST，应只插入一次条件包裹")
                .isEqualTo(1)
            assertThat(method.invoke(instance, "raw"))
                .`as`("Then: 边界内 CAST 被拒绝后应返回 null，边界外 POP 掉的 CAST 不应被包裹")
                .isNull()
        }

        @Test
        @DisplayName("handler 首参类型不兼容时应暴露签名错误")
        fun mismatchedWrapWithConditionAtCastFailsWithClearMessage() {
            // Given
            AsmRegistry.register(MismatchedWrapConditionCastMixin::class.java)

            // When / Then
            assertThatThrownBy {
                AsmProcessor().transform("CastInstructionTarget", castInstructionTargetBytes(), javaClass.classLoader)
            }
                .`as`("Then: CAST 条件包裹必须接收转换后的引用类型，不能用不兼容的基本类型误接")
                .isInstanceOf(AsmTransformException::class.java)
                .hasRootCauseMessage(
                    "@WrapWithCondition CAST handler shouldKeep parameter #0 mismatch: " +
                        "expected Ljava/lang/String;, actual I",
                )
        }
    }

    @Nested
    @DisplayName("@WrapWithCondition INSTANCEOF 类型判断场景")
    inner class WrapWithConditionInstanceofScenarios {
        @Test
        @DisplayName("handler 放行时应保留原始类型判断结果")
        fun wrapWithConditionAtInstanceofKeepsOriginalResultWhenTrue() {
            // Given
            AsmRegistry.register(WrapConditionInstanceofAllowMixin::class.java)

            // When
            val transformed = AsmProcessor().transform("InstanceofTarget", instanceofTargetBytes(), javaClass.classLoader)
            val clazz = loadClass("InstanceofTarget", transformed)
            val instance = clazz.getDeclaredConstructor().newInstance()
            val method = clazz.getMethod("isString", Any::class.java, Boolean::class.javaPrimitiveType)

            // Then
            assertThat(method.invoke(instance, "raw", false))
                .`as`("Then: handler 返回 true 时应保留 String 的原始 INSTANCEOF=true")
                .isEqualTo(true)
            assertThat(method.invoke(instance, 42, false))
                .`as`("Then: handler 返回 true 时也应保留非 String 的原始 INSTANCEOF=false")
                .isEqualTo(false)
        }

        @Test
        @DisplayName("handler 拒绝时应把类型判断结果替换为 false")
        fun wrapWithConditionAtInstanceofForcesFalseWhenHandlerReturnsFalse() {
            // Given
            AsmRegistry.register(WrapConditionInstanceofDenyMixin::class.java)

            // When
            val transformed = AsmProcessor().transform("InstanceofTarget", instanceofTargetBytes(), javaClass.classLoader)
            val clazz = loadClass("InstanceofTarget", transformed)
            val instance = clazz.getDeclaredConstructor().newInstance()
            val method = clazz.getMethod("isString", Any::class.java, Boolean::class.javaPrimitiveType)

            // Then
            assertThat(method.invoke(instance, "raw", false))
                .`as`("Then: 即使原始 INSTANCEOF=true，handler 返回 false 也应让业务分支看到 false")
                .isEqualTo(false)
            assertThat(method.invoke(instance, 42, false))
                .`as`("Then: 原始 INSTANCEOF=false 时拒绝结果仍保持 false")
                .isEqualTo(false)
        }

        @Test
        @DisplayName("省略 target 时应匹配方法内兼容的类型判断")
        fun wrapWithConditionAtInstanceofWithoutTargetWrapsCompatibleChecks() {
            // Given
            AsmRegistry.register(WrapConditionAnyInstanceofDenyMixin::class.java)

            // When
            val transformed = AsmProcessor().transform("MultiInstanceofTarget", multiInstanceofTargetBytes(), javaClass.classLoader)
            val classNode = readClass(transformed)
            val methodNode = classNode.methods.single { it.name == "isString" && it.desc == "(Ljava/lang/Object;Ljava/lang/Object;)Z" }
            val mixinOwner = org.objectweb.asm.Type.getInternalName(WrapConditionAnyInstanceofDenyMixin::class.java)
            val handlerCallCount = methodNode.instructions.toArray().count { insn ->
                insn is org.objectweb.asm.tree.MethodInsnNode &&
                    insn.owner == mixinOwner &&
                    insn.name == "shouldKeep"
            }
            val clazz = loadClass("MultiInstanceofTarget", transformed)
            val instance = clazz.getDeclaredConstructor().newInstance()
            val method = clazz.getMethod("isString", Any::class.java, Any::class.java)

            // Then
            assertThat(handlerCallCount)
                .`as`("Then: 省略 target 时两个 INSTANCEOF 都应作为兼容候选被条件包裹")
                .isEqualTo(2)
            assertThat(method.invoke(instance, StringBuilder("ignored"), "raw"))
                .`as`("Then: 最终 String 判断被 handler 拒绝后应返回 false，而不是继续返回原始 true")
                .isEqualTo(false)
        }

        @Test
        @DisplayName("显式 target 应只包裹指定类型判断")
        fun wrapWithConditionAtInstanceofHonorsExplicitTargetFilter() {
            // Given
            AsmRegistry.register(WrapConditionTargetedStringInstanceofDenyMixin::class.java)

            // When
            val transformed = AsmProcessor().transform("MultiInstanceofTarget", multiInstanceofTargetBytes(), javaClass.classLoader)
            val classNode = readClass(transformed)
            val methodNode = classNode.methods.single { it.name == "isString" && it.desc == "(Ljava/lang/Object;Ljava/lang/Object;)Z" }
            val mixinOwner = org.objectweb.asm.Type.getInternalName(WrapConditionTargetedStringInstanceofDenyMixin::class.java)
            val handlerCallCount = methodNode.instructions.toArray().count { insn ->
                insn is org.objectweb.asm.tree.MethodInsnNode &&
                    insn.owner == mixinOwner &&
                    insn.name == "shouldKeep"
            }
            val clazz = loadClass("MultiInstanceofTarget", transformed)
            val instance = clazz.getDeclaredConstructor().newInstance()
            val method = clazz.getMethod("isString", Any::class.java, Any::class.java)

            // Then
            assertThat(handlerCallCount)
                .`as`("Then: target=java.lang.String 时只应包裹 String 判断，不能误包裹 StringBuilder 判断")
                .isEqualTo(1)
            assertThat(method.invoke(instance, StringBuilder("ignored"), "raw"))
                .`as`("Then: StringBuilder 判断未被拒绝，但最终 String 判断被拒绝后应返回 false")
                .isEqualTo(false)
        }

        @Test
        @DisplayName("handler 可追加接收目标方法参数前缀")
        fun wrapWithConditionAtInstanceofCanUseTargetMethodParameters() {
            // Given
            AsmRegistry.register(WrapConditionInstanceofTargetParamsMixin::class.java)

            // When
            val transformed = AsmProcessor().transform("InstanceofTarget", instanceofTargetBytes(), javaClass.classLoader)
            val clazz = loadClass("InstanceofTarget", transformed)
            val instance = clazz.getDeclaredConstructor().newInstance()
            val method = clazz.getMethod("isString", Any::class.java, Boolean::class.javaPrimitiveType)

            // Then
            assertThat(method.invoke(instance, "raw", true))
                .`as`("Then: handler 同时收到原始 boolean、业务对象和 force 参数时应允许保留 true")
                .isEqualTo(true)
            assertThat(method.invoke(instance, "raw", false))
                .`as`("Then: force=false 时 handler 可拒绝原始 true 并让结果变为 false")
                .isEqualTo(false)
            assertThat(method.invoke(instance, StringBuilder("raw"), true))
                .`as`("Then: 原始类型判断为 false 时 handler 放行也只能保留 false")
                .isEqualTo(false)
        }

        @Test
        @DisplayName("Slice 应只包裹边界内的类型判断")
        fun wrapWithConditionAtInstanceofRespectsSliceBoundary() {
            // Given
            AsmRegistry.register(WrapConditionInstanceofSliceDenyMixin::class.java)

            // When
            val transformed = AsmProcessor().transform(
                "SliceInstanceofExpressionValueTarget",
                sliceInstanceofExpressionValueTargetBytes(),
                javaClass.classLoader,
            )
            val classNode = readClass(transformed)
            val methodNode = classNode.methods.single { it.name == "isSelected" && it.desc == "(Ljava/lang/Object;)Z" }
            val mixinOwner = org.objectweb.asm.Type.getInternalName(WrapConditionInstanceofSliceDenyMixin::class.java)
            val handlerCallCount = methodNode.instructions.toArray().count { insn ->
                insn is org.objectweb.asm.tree.MethodInsnNode &&
                    insn.owner == mixinOwner &&
                    insn.name == "shouldKeep"
            }
            val clazz = loadClass("SliceInstanceofExpressionValueTarget", transformed)
            val instance = clazz.getDeclaredConstructor().newInstance()
            val method = clazz.getMethod("isSelected", Any::class.java)

            // Then
            assertThat(handlerCallCount)
                .`as`("Then: Slice 边界内只有第二个 String INSTANCEOF，应只插入一次条件包裹")
                .isEqualTo(1)
            assertThat(method.invoke(instance, "raw"))
                .`as`("Then: 边界内判断被拒绝后应返回 false，边界外 POP 掉的判断不应影响结果")
                .isEqualTo(false)
        }

        @Test
        @DisplayName("handler 首参不是 boolean 时应暴露签名错误")
        fun mismatchedWrapWithConditionAtInstanceofFailsWithClearMessage() {
            // Given
            AsmRegistry.register(MismatchedWrapConditionInstanceofMixin::class.java)

            // When / Then
            assertThatThrownBy {
                AsmProcessor().transform("InstanceofTarget", instanceofTargetBytes(), javaClass.classLoader)
            }
                .`as`("Then: INSTANCEOF 条件包裹必须显式接收原始 boolean 判断结果，避免误把待判断对象当首参")
                .isInstanceOf(AsmTransformException::class.java)
                .hasRootCauseMessage(
                    "@WrapWithCondition INSTANCEOF handler shouldKeep parameter #0 mismatch: " +
                        "expected Z, actual Ljava/lang/String;",
                )
        }
    }

    @Nested
    @DisplayName("@WrapWithCondition SWITCH 分支选择场景")
    inner class WrapWithConditionSwitchScenarios {
        @Test
        @DisplayName("table switch handler 放行时保留原 selector，拒绝时使用默认 selector")
        fun wrapWithConditionAtSwitchControlsTableSwitchSelector() {
            // Given
            AsmRegistry.register(WrapConditionSwitchDenyMixin::class.java)

            // When
            val transformed = AsmProcessor().transform("SwitchSelectorTarget", switchSelectorTargetBytes(), javaClass.classLoader)
            val clazz = loadClass("SwitchSelectorTarget", transformed)
            val instance = clazz.getDeclaredConstructor().newInstance()
            val method = clazz.getMethod("choose", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)

            // Then
            assertThat(method.invoke(instance, 1, false))
                .`as`("Then: handler 返回 true 时 table switch 应继续使用业务传入的 selector=1")
                .isEqualTo("one")
            assertThat(method.invoke(instance, 2, true))
                .`as`("Then: handler 返回 false 时 table switch 应使用 Int 默认值 0 并进入 zero 分支")
                .isEqualTo("zero")
        }

        @Test
        @DisplayName("lookup switch handler 拒绝时默认 selector 应进入 fallback")
        fun wrapWithConditionAtSwitchControlsLookupSwitchSelector() {
            // Given
            AsmRegistry.register(WrapConditionLookupSwitchDenyMixin::class.java)

            // When
            val transformed =
                AsmProcessor().transform(
                    "LookupSwitchSelectorTarget",
                    lookupSwitchSelectorTargetBytes(),
                    javaClass.classLoader,
                )
            val clazz = loadClass("LookupSwitchSelectorTarget", transformed)
            val instance = clazz.getDeclaredConstructor().newInstance()
            val method = clazz.getMethod("choose", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)

            // Then
            assertThat(method.invoke(instance, 20, false))
                .`as`("Then: handler 返回 true 时 lookup switch 应继续进入原始 twenty 分支")
                .isEqualTo("twenty")
            assertThat(method.invoke(instance, 30, true))
                .`as`("Then: handler 返回 false 时 lookup switch 使用默认 selector=0，目标表无 0 时应进入 fallback")
                .isEqualTo("fallback")
        }

        @Test
        @DisplayName("Slice 应只包裹边界内的 switch selector")
        fun wrapWithConditionAtSwitchRespectsSliceBoundary() {
            // Given
            AsmRegistry.register(WrapConditionSwitchSliceDenyMixin::class.java)

            // When
            val transformed =
                AsmProcessor().transform(
                    "SliceSwitchSelectorTarget",
                    sliceSwitchSelectorTargetBytes(),
                    javaClass.classLoader,
                )
            val classNode = readClass(transformed)
            val methodNode = classNode.methods.single { it.name == "choose" && it.desc == "(IZ)Ljava/lang/String;" }
            val mixinOwner = org.objectweb.asm.Type.getInternalName(WrapConditionSwitchSliceDenyMixin::class.java)
            val handlerCallCount = methodNode.instructions.toArray().count { insn ->
                insn is org.objectweb.asm.tree.MethodInsnNode &&
                    insn.owner == mixinOwner &&
                    insn.name == "shouldKeep"
            }
            val clazz = loadClass("SliceSwitchSelectorTarget", transformed)
            val instance = clazz.getDeclaredConstructor().newInstance()
            val method = clazz.getMethod("choose", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)

            // Then
            assertThat(handlerCallCount)
                .`as`("Then: Slice 边界内只有第二个 switch，应只插入一次条件包裹")
                .isEqualTo(1)
            assertThat(method.invoke(instance, 1, false))
                .`as`("Then: handler 放行时边界内外 switch 都应保留 selector=1")
                .isEqualTo("one:one")
            assertThat(method.invoke(instance, 1, true))
                .`as`("Then: handler 拒绝时只应把边界内 switch selector 替换为 0，边界外结果保持 one")
                .isEqualTo("one:zero")
        }

        @Test
        @DisplayName("SWITCH 指令不允许声明 target")
        fun wrapWithConditionAtSwitchRejectsTargetFilter() {
            // Given
            AsmRegistry.register(TargetedWrapConditionSwitchMixin::class.java)

            // When / Then
            assertThatThrownBy {
                AsmProcessor().transform("SwitchSelectorTarget", switchSelectorTargetBytes(), javaClass.classLoader)
            }
                .`as`("Then: switch selector 没有 owner/name/desc，声明 target 应作为配置错误暴露")
                .isInstanceOf(AsmTransformException::class.java)
                .hasRootCauseMessage("@WrapWithCondition SWITCH does not support at.target")
        }
    }

    @Nested
    @DisplayName("@ModifyConstant Slice 边界场景")
    inner class ModifyConstantSliceBoundaryScenarios {
        @Test
        @DisplayName("常量边界应只修改业务片段内的目标常量")
        fun constantBoundaryLimitsBusinessSegment() {
            // Given
            AsmRegistry.register(ConstantBoundaryModifyConstantMixin::class.java)

            // When
            val transformed =
                AsmProcessor().transform(
                    "ConstantBoundarySliceConstantTarget",
                    constantBoundarySliceConstantTargetBytes(),
                    javaClass.classLoader,
                )
            val clazz = loadClass("ConstantBoundarySliceConstantTarget", transformed)
            val instance = clazz.getDeclaredConstructor().newInstance()
            val result = clazz.getMethod("value").invoke(instance)

            // Then
            assertThat(result)
                .`as`("Then: CONSTANT 边界只应放行哨兵常量之间的业务常量，边界外重复值必须保持原样")
                .isEqualTo("target:changed:target")
        }

        @Test
        @DisplayName("字段边界应只修改字段读取之间的目标常量")
        fun fieldBoundaryLimitsBusinessSegment() {
            // Given
            AsmRegistry.register(FieldBoundaryModifyConstantMixin::class.java)

            // When
            val transformed =
                AsmProcessor().transform(
                    "FieldBoundarySliceConstantTarget",
                    fieldBoundarySliceConstantTargetBytes(),
                    javaClass.classLoader,
                )
            val clazz = loadClass("FieldBoundarySliceConstantTarget", transformed)
            val instance = clazz.getDeclaredConstructor().newInstance()
            val result = clazz.getMethod("value").invoke(instance)

            // Then
            assertThat(result)
                .`as`("Then: FIELD 边界只应放行两次字段读取之间的业务常量，避免同值常量跨片段误改")
                .isEqualTo("target:changed:target")
        }

        @Test
        @DisplayName("字段写入边界应只修改写入之间的目标常量")
        fun fieldAssignBoundaryLimitsBusinessSegment() {
            // Given
            AsmRegistry.register(FieldAssignBoundaryModifyConstantMixin::class.java)

            // When
            val transformed =
                AsmProcessor().transform(
                    "FieldAssignBoundarySliceConstantTarget",
                    fieldAssignBoundarySliceConstantTargetBytes(),
                    javaClass.classLoader,
                )
            val clazz = loadClass("FieldAssignBoundarySliceConstantTarget", transformed)
            val instance = clazz.getDeclaredConstructor().newInstance()
            val result = clazz.getMethod("value").invoke(instance)

            // Then
            assertThat(result)
                .`as`("Then: FIELD_ASSIGN 边界只应按 PUTFIELD/PUTSTATIC 指令位置限制业务常量")
                .isEqualTo("target:changed:target")
        }

        @Test
        @DisplayName("字段写入 to 边界前的写入值应按真实字节码位置参与常量修改")
        fun fieldAssignToBoundaryValueUsesBytecodePosition() {
            // Given
            AsmRegistry.register(FieldAssignToValueBoundaryModifyConstantMixin::class.java)

            // When
            val transformed =
                AsmProcessor().transform(
                    "FieldAssignToValueBoundarySliceConstantTarget",
                    fieldAssignToValueBoundarySliceConstantTargetBytes(),
                    javaClass.classLoader,
                )
            val clazz = loadClass("FieldAssignToValueBoundarySliceConstantTarget", transformed)
            val instance = clazz.getDeclaredConstructor().newInstance()
            val result = clazz.getMethod("value").invoke(instance)
            val marker =
                clazz.getDeclaredField("marker").apply {
                    isAccessible = true
                }.get(instance)

            // Then
            assertThat(result)
                .`as`("Then: 业务片段中的目标常量仍应被修改，边界后的同值常量必须保持原样")
                .isEqualTo("target:changed:target")
            assertThat(marker)
                .`as`("Then: FIELD_ASSIGN 的 to 边界锚点是写入指令，位于该指令前的待写入常量按切片内候选处理")
                .isEqualTo("changed")
        }

        @Test
        @DisplayName("常量边界缺失时应保持空切片并触发命中数契约")
        fun missingConstantBoundaryKeepsSliceEmpty() {
            // Given
            AsmRegistry.register(MissingConstantBoundaryModifyConstantMixin::class.java)

            // When / Then
            assertThatThrownBy {
                AsmProcessor().transform(
                    "ConstantBoundarySliceConstantTarget",
                    constantBoundarySliceConstantTargetBytes(),
                    javaClass.classLoader,
                )
            }
                .`as`("Then: 目标字节码漂移导致边界常量缺失时，不应退回全方法误改同值常量")
                .isInstanceOf(AsmTransformException::class.java)
                .hasRootCauseMessage(
                    "@ModifyConstant handler modify requires at least 1 injection(s), " +
                        "actual 0 in target method value()Ljava/lang/String; of class ConstantBoundarySliceConstantTarget",
                )
        }

        @Test
        @DisplayName("空常量边界目标应直接报错避免退回全方法")
        fun emptyConstantBoundaryTargetFailsBeforeWholeMethodFallback() {
            // Given
            AsmRegistry.register(EmptyConstantBoundaryModifyConstantMixin::class.java)

            // When / Then
            assertThatThrownBy {
                AsmProcessor().transform(
                    "ConstantBoundarySliceConstantTarget",
                    constantBoundarySliceConstantTargetBytes(),
                    javaClass.classLoader,
                )
            }
                .`as`("Then: 显式声明 CONSTANT 边界却遗漏 target 时，应暴露配置错误而不是扩大到全方法")
                .isInstanceOf(AsmTransformException::class.java)
                .hasRootCauseMessage(
                    "Invalid @ModifyConstant slice boundary CONSTANT target: target must not be empty",
                )
        }

        @Test
        @DisplayName("省略目标方法推断时也应保留空边界配置错误")
        fun inferredMethodKeepsEmptyBoundaryConfigurationError() {
            // Given
            AsmRegistry.register(InferredEmptyConstantBoundaryModifyConstantMixin::class.java)

            // When / Then
            assertThatThrownBy {
                AsmProcessor().transform(
                    "ConstantBoundarySliceConstantTarget",
                    constantBoundarySliceConstantTargetBytes(),
                    javaClass.classLoader,
                )
            }
                .`as`("Then: method 省略时边界解析错误仍应向调用方暴露，避免被目标方法推断降级成 missing method")
                .isInstanceOf(AsmTransformException::class.java)
                .hasRootCauseMessage(
                    "Invalid @ModifyConstant slice boundary CONSTANT target: target must not be empty",
                )
        }

        @Test
        @DisplayName("字段边界缺少字段名时应直接报错")
        fun fieldBoundaryWithoutNameFailsBeforeDescriptorWideMatch() {
            // Given
            AsmRegistry.register(FieldBoundaryWithoutNameModifyConstantMixin::class.java)

            // When / Then
            assertThatThrownBy {
                AsmProcessor().transform(
                    "FieldBoundarySliceConstantTarget",
                    fieldBoundarySliceConstantTargetBytes(),
                    javaClass.classLoader,
                )
            }
                .`as`("Then: FIELD 边界不能只靠 descriptor 宽匹配，否则可能把切片锚到错误字段读取")
                .isInstanceOf(AsmTransformException::class.java)
                .hasRootCauseMessage(
                    "Invalid @ModifyConstant slice boundary FIELD target: field name must not be empty",
                )
        }

        @Test
        @DisplayName("字段写入边界缺少字段名时应直接报错")
        fun fieldAssignBoundaryWithoutNameFailsBeforeDescriptorWideMatch() {
            // Given
            AsmRegistry.register(FieldAssignBoundaryWithoutNameModifyConstantMixin::class.java)

            // When / Then
            assertThatThrownBy {
                AsmProcessor().transform(
                    "FieldAssignBoundarySliceConstantTarget",
                    fieldAssignBoundarySliceConstantTargetBytes(),
                    javaClass.classLoader,
                )
            }
                .`as`("Then: FIELD_ASSIGN 边界不能只靠 descriptor 宽匹配，否则可能把切片锚到错误字段写入")
                .isInstanceOf(AsmTransformException::class.java)
                .hasRootCauseMessage(
                    "Invalid @ModifyConstant slice boundary FIELD_ASSIGN target: field name must not be empty",
                )
        }
    }

    @Nested
    @DisplayName("@Group 组级命中数场景")
    inner class GroupInjectionCountScenarios {
        @Test
        @DisplayName("多版本候选中至少一个命中时应允许同组候选未命中")
        fun groupedModifyConstantAllowsFallbackCandidate() {
            // Given
            AsmRegistry.register(GroupedConstructorFallbackMixin::class.java)

            // When
            val clazz = transformAndLoadTestFixture()
            val instance = clazz.getDeclaredConstructor().newInstance()
            val result = clazz.getMethod("testA0").invoke(instance)

            // Then
            assertThat(result)
                .`as`("Then: 当前版本构造器常量命中后，旧版本候选未命中不应阻断同组多版本适配")
                .isEqualTo("GroupedConstructor")
        }

        @Test
        @DisplayName("同组候选全部未命中时应按组级最小命中数失败")
        fun groupedModifyConstantFailsWhenAllCandidatesMiss() {
            // Given
            AsmRegistry.register(GroupedMissingConstructorConstantsMixin::class.java)

            // When / Then
            assertThatThrownBy {
                transformAndLoadTestFixture()
            }
                .`as`("Then: 目标版本漂移到所有候选都未命中时，应暴露组级最小命中数失败")
                .isInstanceOf(AsmTransformException::class.java)
                .hasRootCauseMessage(
                    "@Group constructorName requires at least 1 injection(s), actual 0 in class Test",
                )
        }

        @Test
        @DisplayName("同组候选显式声明 require 时应先执行单处理器命中数校验")
        fun groupedModifyConstantWithExplicitRequireStillFailsPerHandlerCount() {
            // Given
            AsmRegistry.register(GroupedRequiredLegacyConstructorConstantMixin::class.java)

            // When / Then
            assertThatThrownBy {
                transformAndLoadTestFixture()
            }
                .`as`("Then: @Group 只能放宽默认候选命中，不能覆盖处理器自己声明的 require 契约")
                .isInstanceOf(AsmTransformException::class.java)
                .hasRootCauseMessage(
                    "@ModifyConstant handler legacyName requires at least 1 injection(s), " +
                        "actual 0 in target method <init>()V of class Test",
                )
        }

        @Test
        @DisplayName("同组候选同时命中超过上限时应按组级最大命中数失败")
        fun groupedModifyConstantFailsWhenMoreThanOneCandidateMatches() {
            // Given
            AsmRegistry.register(GroupedTooManyRuntimeNamesMixin::class.java)

            // When / Then
            assertThatThrownBy {
                transformAndLoadTestFixture()
            }
                .`as`("Then: 多版本二选一补丁在当前版本同时命中多个候选时，应按组级上限阻止误改")
                .isInstanceOf(AsmTransformException::class.java)
                .hasRootCauseMessage(
                    "@Group singleRuntimeName allows at most 1 injection(s), actual 2 in class Test",
                )
        }

        @Test
        @DisplayName("未分组处理器仍应保持默认必须命中的旧契约")
        fun ungroupedModifyConstantStillRequiresOwnMatch() {
            // Given
            AsmRegistry.register(UngroupedMissingConstructorConstantMixin::class.java)

            // When / Then
            assertThatThrownBy {
                transformAndLoadTestFixture()
            }
                .`as`("Then: @Group 只放宽同组候选，不能削弱普通处理器默认必须命中的契约")
                .isInstanceOf(AsmTransformException::class.java)
                .hasRootCauseMessage(
                    "@ModifyConstant handler modify did not match any bytecode in " +
                        "target method <init>()V of class Test",
                )
        }

        @Test
        @DisplayName("未指定常量值的未分组处理器零命中时仍应失败")
        fun ungroupedEmptyModifyConstantStillRequiresOwnMatch() {
            // Given
            AsmRegistry.register(UngroupedEmptyModifyConstantNoConstantTargetMixin::class.java)

            // When / Then
            assertThatThrownBy {
                AsmProcessor().transform("StrictTarget", strictTargetBytes(), javaClass.classLoader)
            }
                .`as`("Then: 未指定 constant 只是表示匹配任意常量，不能让普通处理器在零命中时静默通过")
                .isInstanceOf(AsmTransformException::class.java)
                .hasRootCauseMessage(
                    "@ModifyConstant handler modify did not match any bytecode in " +
                        "target method keep()V of class StrictTarget",
                )
        }

        @Test
        @DisplayName("@AsmInject 同组候选跨 RETURN 与 HEAD 轮次命中后应统一校验")
        fun groupedAsmInjectCountsAcrossProcessingRounds() {
            // Given
            AsmRegistry.register(GroupedReturnAndHeadInjectMixin::class.java)

            // When
            val transformed = AsmProcessor().transform("ReturnTarget", returnTargetBytes(), javaClass.classLoader)
            val clazz = loadClass("ReturnTarget", transformed)
            val instance = clazz.getDeclaredConstructor().newInstance()
            val result = clazz.getMethod("value").invoke(instance)

            // Then
            assertThat(result)
                .`as`("Then: RETURN 与 HEAD 分轮处理的注入都应先累计到同组，再按组级命中数统一放行")
                .isEqualTo("value")
        }

        @Test
        @DisplayName("@RedirectAllMethods 同组重定向超过上限时应按组级最大命中数失败")
        fun groupedRedirectAllMethodsFailsWhenMoreThanOneMethodMatches() {
            // Given
            AsmRegistry.register(GroupedRedirectAllAllowOneTrimMixin::class.java)

            // When / Then
            assertThatThrownBy {
                AsmProcessor().transform("RedirectAllMultiTarget", redirectAllMultiTargetBytes(), javaClass.classLoader)
            }
                .`as`("Then: 全类重定向在多个方法同时命中时，应由 @Group(max = 1) 暴露多版本候选冲突")
                .isInstanceOf(AsmTransformException::class.java)
                .hasRootCauseMessage(
                    "@Group redirectAllTrim allows at most 1 injection(s), actual 2 in class RedirectAllMultiTarget",
                )
        }
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
    fun modifyConstantCanUseAssignableReferenceParameters() {
        AsmRegistry.register(ConstantWithAssignableTargetParamsMixin::class.java)

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
    fun modifyConstantAcceptsGenericObjectReturnTypeForTypedNullConstant() {
        AsmRegistry.register(TypedNullConstantGenericReturnMixin::class.java)

        val transformed = AsmProcessor().transform("TypedNullConstantTarget", typedNullConstantTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("TypedNullConstantTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("generic-null", result)
    }

    @Test
    fun modifyConstantAcceptsAssignableObjectReturnTypeForNullConstant() {
        AsmRegistry.register(NullConstantAssignableReturnMixin::class.java)

        val transformed = AsmProcessor().transform("NullConstantTarget", nullConstantTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("NullConstantTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("changed", result)
    }

    @Test
    fun modifyConstantAcceptsTypedReferenceParameterForExplicitNullConstant() {
        AsmRegistry.register(NullConstantTypedReferenceParameterMixin::class.java)

        val transformed = AsmProcessor().transform("NullConstantTarget", nullConstantTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("NullConstantTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("typed-null", result)
    }

    @Test
    fun modifyConstantWithoutExplicitValueAcceptsTypedReferenceParameterForNullConstant() {
        AsmRegistry.register(NullConstantTypedReferenceParameterWithoutValueMixin::class.java)

        val transformed = AsmProcessor().transform("NullConstantTarget", nullConstantTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("NullConstantTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("typed-null", result)
    }

    @Test
    fun modifyConstantMatchesExplicitTrueBooleanConstant() {
        AsmRegistry.register(TrueBooleanModifyConstantMixin::class.java)

        val transformed = AsmProcessor().transform("TrueBooleanConstantTarget", trueBooleanConstantTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("TrueBooleanConstantTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals(false, result)
    }

    @Test
    fun modifyConstantMatchesExplicitFalseBooleanConstant() {
        AsmRegistry.register(FalseBooleanModifyConstantMixin::class.java)

        val transformed =
            AsmProcessor().transform("FalseBooleanConstantTarget", falseBooleanConstantTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("FalseBooleanConstantTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals(true, result)
    }

    @Test
    fun modifyConstantMatchesClassLiteralConstant() {
        AsmRegistry.register(ClassLiteralModifyConstantMixin::class.java)

        val transformed =
            AsmProcessor().transform("ClassLiteralConstantTarget", classLiteralConstantTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ClassLiteralConstantTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals(StringBuilder::class.java, result)
    }

    @Test
    fun modifyConstantMatchesMethodTypeConstant() {
        AsmRegistry.register(MethodTypeModifyConstantMixin::class.java)

        val transformed =
            AsmProcessor().transform("MethodTypeConstantTarget", methodTypeConstantTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("MethodTypeConstantTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals(java.lang.invoke.MethodType.methodType(StringBuilder::class.java, Int::class.javaPrimitiveType), result)
    }

    @Test
    fun modifyConstantMatchesMethodHandleConstant() {
        AsmRegistry.register(MethodHandleModifyConstantMixin::class.java)

        val transformed =
            AsmProcessor().transform("MethodHandleConstantTarget", methodHandleConstantTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("MethodHandleConstantTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance) as java.lang.invoke.MethodHandle

        assertEquals("1a", result.invokeWithArguments(26))
    }

    @Test
    fun modifyConstantMatchesDynamicConstant() {
        AsmRegistry.register(DynamicConstantModifyConstantMixin::class.java)

        val transformed =
            AsmProcessor().transform("DynamicConstantTarget", dynamicConstantTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("DynamicConstantTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("changed:original", result)
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
    fun modifyConstantSkipsSameTextConstantWithIncompatibleJvmType() {
        AsmRegistry.register(MixedNumericModifyConstantMixin::class.java)

        val transformed =
            AsmProcessor().transform("MixedNumericConstantTarget", mixedNumericConstantTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("MixedNumericConstantTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals(42, result)
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
    fun shadowCanReferenceInheritedField() {
        AsmRegistry.register(InheritedShadowFieldMixin::class.java)

        AsmProcessor().transform("InheritedAccessorTarget", inheritedAccessorTargetBytes(), javaClass.classLoader)
    }

    @Test
    fun shadowCanReferenceInheritedMethod() {
        AsmRegistry.register(InheritedShadowMethodMixin::class.java)

        AsmProcessor().transform("InheritedAccessorTarget", inheritedAccessorTargetBytes(), javaClass.classLoader)
    }

    @Test
    fun shadowCanReferenceInterfaceDefaultMethod() {
        AsmRegistry.register(InterfaceDefaultShadowMethodMixin::class.java)

        AsmProcessor().transform("InterfaceDefaultInvokerTarget", interfaceDefaultInvokerTargetBytes(), javaClass.classLoader)
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
    @DisplayName("@Final 标记 Shadow 别名字段时应修改真实目标字段")
    fun finalShadowAliasAddsFinalToTargetField() {
        // Given
        AsmRegistry.register(FinalShadowAliasFieldMixin::class.java)

        // When
        val transformed = AsmProcessor().transform("FieldTarget", fieldTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val field = classNode.fields.single { it.name == "name" }

        // Then
        assertThat(field.access and Opcodes.ACC_FINAL)
            .`as`("Then: @Shadow(\"name\") 的别名字段上标记 @Final 时，应该修改真实目标字段 name")
            .isNotZero()
    }

    @Nested
    @DisplayName("@Accessor setter 场景")
    inner class AccessorSetterScenarios {
        @Test
        @DisplayName("实例字段 setter 应通过公共访问器更新目标对象状态")
        fun instanceFieldSetterUpdatesTargetState() {
            // Given
            AsmRegistry.register(InstanceFieldSetterAccessorMixin::class.java)
            val transformed =
                AsmProcessor().transform("AccessorSetterTarget", accessorSetterTargetBytes(), javaClass.classLoader)
            val clazz = loadClass("AccessorSetterTarget", transformed)
            val instance = clazz.getDeclaredConstructor().newInstance()
            val readName = clazz.getMethod("readName")
            val setName = clazz.getMethod("setName", String::class.java)

            assertThat(readName.invoke(instance))
                .`as`("Given: 目标类应保留原始字段状态，证明 setter 前置状态真实可见")
                .isEqualTo("initial")

            // When
            setName.invoke(instance, "地图-Alpha_01")

            // Then
            assertThat(readName.invoke(instance))
                .`as`("Then: @Accessor 生成的 setter 应写入 private 实例字段并改变目标对象状态")
                .isEqualTo("地图-Alpha_01")
        }

        @Test
        @DisplayName("final 字段 setter 缺少 @Mutable 时应在转换阶段失败")
        fun finalFieldSetterWithoutMutableFailsDuringTransform() {
            // Given
            AsmRegistry.register(FinalFieldSetterWithoutMutableMixin::class.java)

            // When / Then
            assertThatThrownBy {
                AsmProcessor().transform("FinalAccessorSetterTarget", finalAccessorSetterTargetBytes(), javaClass.classLoader)
            }
                .`as`("Then: 未标 @Mutable 的 final 字段 setter 不能生成运行时才失败的写字段字节码")
                .isInstanceOf(AsmTransformException::class.java)
                .hasRootCauseMessage("Accessor setter for final field name requires @Mutable")
        }

        @Test
        @DisplayName("同一 Mixin 先移除 final 后，未标 @Mutable 的 setter 仍应失败")
        fun finalFieldSetterWithoutMutableFailsAfterSameMixinMutableShadow() {
            // Given
            AsmRegistry.register(FinalFieldShadowMutableThenSetterWithoutMutableMixin::class.java)

            // When / Then
            assertThatThrownBy {
                AsmProcessor().transform("FinalAccessorSetterTarget", finalAccessorSetterTargetBytes(), javaClass.classLoader)
            }
                .`as`("Then: setter 是否需要 @Mutable 应基于目标类原始 final 语义，而不是同一 Mixin 里已改写后的状态")
                .isInstanceOf(AsmTransformException::class.java)
                .hasRootCauseMessage("Accessor setter for final field name requires @Mutable")
        }

        @Test
        @DisplayName("前置 Mixin 移除 final 后，后续未标 @Mutable 的 setter 仍应失败")
        fun finalFieldSetterWithoutMutableFailsAfterPreviousMixinMutableShadow() {
            // Given
            AsmRegistry.register(HighPriorityFinalFieldMutableMixin::class.java)
            AsmRegistry.register(LowPriorityFinalFieldSetterWithoutMutableMixin::class.java)

            // When / Then
            assertThatThrownBy {
                AsmProcessor().transform("FinalAccessorSetterTarget", finalAccessorSetterTargetBytes(), javaClass.classLoader)
            }
                .`as`("Then: 多个 Mixin 顺序应用时，后续 setter 也必须遵守原始 final 字段的 @Mutable 契约")
                .isInstanceOf(AsmTransformException::class.java)
                .hasRootCauseMessage("Accessor setter for final field name requires @Mutable")
        }

        @Test
        @DisplayName("@Mutable final 字段 setter 应移除 final 并更新对象状态")
        fun mutableFinalFieldSetterRemovesFinalAndUpdatesTargetState() {
            // Given
            AsmRegistry.register(FinalFieldMutableSetterAccessorMixin::class.java)

            // When
            val transformed =
                AsmProcessor().transform("FinalAccessorSetterTarget", finalAccessorSetterTargetBytes(), javaClass.classLoader)
            val classNode = readClass(transformed)
            val field = classNode.fields.single { it.name == "name" }
            val clazz = loadClass("FinalAccessorSetterTarget", transformed)
            val instance = clazz.getDeclaredConstructor().newInstance()
            val readName = clazz.getMethod("readName")
            val setName = clazz.getMethod("setName", String::class.java)

            setName.invoke(instance, "锁定值-已更新")

            // Then
            assertThat(field.access and Opcodes.ACC_FINAL)
                .`as`("Then: @Mutable setter 必须先移除目标类自身字段的 final 标志")
                .isZero()
            assertThat(readName.invoke(instance))
                .`as`("Then: 移除 final 后，生成的 setter 应能更新目标对象状态")
                .isEqualTo("锁定值-已更新")
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
    fun overwriteInfersTargetWhenMethodIsOmitted() {
        AsmRegistry.register(InferredOverwriteTargetMixin::class.java)

        val transformed = AsmProcessor().transform("ReturnTarget", returnTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ReturnTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("inferred-overwrite", result)
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
    fun replaceAllMethodsUsesDefaultReturnValueProviderForReferenceReturn() {
        AsmRegistry.register(ReplaceAllReferenceReturnMixin::class.java)

        val transformed =
            AsmProcessor().transform("ReferenceReturnTarget", referenceReturnTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ReferenceReturnTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals(true, result is java.util.ArrayList<*>)
        assertEquals(0, (result as java.util.ArrayList<*>).size)
    }

    @Test
    fun replaceAllMethodsUsesJvmDefaultForCharReturn() {
        AsmRegistry.register(ReplaceAllCharReturnMixin::class.java)

        val transformed = AsmProcessor().transform("CharReturnTarget", charReturnTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("CharReturnTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals(0.toChar(), result)
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
    fun invokeInjectAtInvokeDynamicCanUseCallAndTargetMethodParameters() {
        AsmRegistry.register(InvokeDynamicInjectMixin::class.java)
        InvokeDynamicInjectMixin.injectCount = 0
        InvokeDynamicInjectMixin.observed = ""

        val transformed =
            AsmProcessor().transform("InvokeDynamicExpressionValueTarget", invokeDynamicExpressionValueTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("InvokeDynamicExpressionValueTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value", String::class.java, Int::class.javaPrimitiveType).invoke(instance, "raw", 7)

        assertEquals("raw-7", result)
        assertEquals(1, InvokeDynamicInjectMixin.injectCount)
        assertEquals("raw:7:raw:7", InvokeDynamicInjectMixin.observed)
    }

    @Test
    fun invokeReplaceAtInvokeDynamicReplacesCall() {
        AsmRegistry.register(InvokeDynamicReplaceMixin::class.java)

        val transformed =
            AsmProcessor().transform("InvokeDynamicExpressionValueTarget", invokeDynamicExpressionValueTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("InvokeDynamicExpressionValueTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value", String::class.java, Int::class.javaPrimitiveType).invoke(instance, "raw", 7)

        assertEquals("RAW-8-injected", result)
    }

    @Test
    fun invokeInjectInfersTargetWhenMethodIsOmitted() {
        AsmRegistry.register(InferredInvokeInjectTargetMixin::class.java)

        val transformed = AsmProcessor().transform("RedirectTarget", redirectTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val method = classNode.methods.single { it.name == "call" }
        val mixinOwner = org.objectweb.asm.Type.getInternalName(InferredInvokeInjectTargetMixin::class.java)
        val handlerCalls = method.instructions.toArray().filterIsInstance<org.objectweb.asm.tree.MethodInsnNode>().filter {
            it.owner == mixinOwner && it.name == "call"
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
    fun invokeAssignInjectSliceSupportsInvokeDynamicBoundaries() {
        AsmRegistry.register(InvokeAssignDynamicSliceMixin::class.java)
        InvokeAssignDynamicSliceMixin.injectCount = 0
        InvokeAssignDynamicSliceMixin.observed = ""

        val transformed =
            AsmProcessor().transform(
                "InvokeDynamicSliceModifyArgTarget",
                invokeDynamicSliceModifyArgTargetBytes(),
                javaClass.classLoader,
            )
        val classNode = readClass(transformed)
        val method = classNode.methods.single { it.name == "value" && it.desc == "(Ljava/lang/String;)Ljava/lang/String;" }
        val instructions = method.instructions.toArray()
        val handlerCallIndex = handlerCallIndex(instructions, InvokeAssignDynamicSliceMixin::class.java, "inject")
        val concatIndexes = instructions.mapIndexedNotNull { index, insn ->
            if (insn is org.objectweb.asm.tree.MethodInsnNode &&
                insn.owner == "java/lang/String" &&
                insn.name == "concat"
            ) {
                index
            } else {
                null
            }
        }
        val boundaryIndexes = instructions.mapIndexedNotNull { index, insn ->
            if (insn is org.objectweb.asm.tree.InvokeDynamicInsnNode &&
                insn.bsm.owner == "java/lang/invoke/StringConcatFactory"
            ) {
                index
            } else {
                null
            }
        }
        val clazz = loadClass("InvokeDynamicSliceModifyArgTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value", String::class.java).invoke(instance, "marker")

        assertEquals("pre-original:inside-original:outside-original", result)
        assertEquals(1, InvokeAssignDynamicSliceMixin.injectCount)
        assertEquals("original:marker", InvokeAssignDynamicSliceMixin.observed)
        assertEquals(true, concatIndexes.size >= 3)
        assertEquals(2, boundaryIndexes.size)
        assertEquals(true, concatIndexes[0] < boundaryIndexes[0])
        assertEquals(true, concatIndexes[1] > boundaryIndexes[0])
        assertEquals(true, concatIndexes[1] < boundaryIndexes[1])
        assertEquals(true, concatIndexes[2] > boundaryIndexes[1])
        assertEquals(true, handlerCallIndex > concatIndexes[1])
        assertEquals(true, handlerCallIndex > boundaryIndexes[0])
        assertEquals(true, handlerCallIndex < boundaryIndexes[1])
    }

    @Test
    fun invokeAssignSliceBoundaryErrorMentionsInvokeAssign() {
        AsmRegistry.register(InvokeAssignInvalidSliceBoundaryMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("SliceInvokeTarget", sliceInvokeTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("@AsmInject(INVOKE/INVOKE_ASSIGN)") == true,
        )
        assertEquals(true, exception.cause?.message?.contains("FIELD") == true)
    }

    @Test
    fun redirectAllMethodsDoesNotRequireExplicitMethodTarget() {
        AsmRegistry.register(RedirectAllTrimMixin::class.java)

        AsmProcessor().transform("RedirectAllTarget", redirectAllTargetBytes(), javaClass.classLoader)
    }

    @Test
    fun redirectAllMethodsEnforcesRedirectCountContractAcrossTargetClass() {
        AsmRegistry.register(RedirectAllAllowOneTrimMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("RedirectAllMultiTarget", redirectAllMultiTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("allows at most 1 injection(s), actual 2") == true,
        )
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
    fun modifyVariableAtHeadAcceptsObjectHandlerParameterByLocalIndex() {
        AsmRegistry.register(ModifyVariableObjectParameterMixin::class.java)

        val transformed = AsmProcessor().transform("VariableTarget", variableTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("VariableTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("echo", String::class.java).invoke(instance, "value")

        assertEquals("value-any", result)
    }

    @Test
    fun modifyVariableAtHeadAcceptsAssignableObjectReturnType() {
        AsmRegistry.register(ModifyVariableAssignableReturnMixin::class.java)

        val transformed = AsmProcessor().transform("CharSequenceVariableTarget", charSequenceVariableTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("CharSequenceVariableTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("echo", CharSequence::class.java).invoke(instance, "value")

        assertEquals("variable-value", result.toString())
    }

    @Test
    fun modifyVariableAtHeadAcceptsGenericObjectReturnType() {
        AsmRegistry.register(ModifyVariableGenericReturnMixin::class.java)

        val transformed = AsmProcessor().transform("VariableTarget", variableTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("VariableTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("echo", String::class.java).invoke(instance, "value")

        assertEquals("generic-value", result)
    }

    @Test
    fun modifyVariableAtHeadInfersSingleParameterByHandlerType() {
        AsmRegistry.register(ModifyVariableInferredHeadParamMixin::class.java)

        val transformed = AsmProcessor().transform("VariableTarget", variableTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("VariableTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("echo", String::class.java).invoke(instance, "value")

        assertEquals("inferred-value", result)
    }

    @Test
    fun modifyVariableInfersTargetWhenMethodIsOmitted() {
        AsmRegistry.register(InferredModifyVariableTargetMixin::class.java)

        val transformed = AsmProcessor().transform("VariableTarget", variableTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("VariableTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("echo", String::class.java).invoke(instance, "value")

        assertEquals("target-value", result)
    }

    @Test
    fun modifyVariableAtStoreInfersOverloadFromActualStoreCandidate() {
        AsmRegistry.register(InferredStoreModifyVariableMixin::class.java)

        val transformed =
            AsmProcessor().transform(
                "StoreVariableOverloadTarget",
                storeVariableOverloadTargetBytes(),
                javaClass.classLoader,
            )
        val clazz = loadClass("StoreVariableOverloadTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val intResult = clazz.getMethod("value", Int::class.javaPrimitiveType).invoke(instance, 7)
        val stringResult = clazz.getMethod("value", String::class.java).invoke(instance, "raw")

        assertEquals(7, intResult)
        assertEquals("stored-local-raw", stringResult)
    }

    @Test
    fun modifyVariableAtLoadInfersOverloadFromActualLoadCandidate() {
        AsmRegistry.register(InferredLoadModifyVariableMixin::class.java)

        val transformed =
            AsmProcessor().transform(
                "LoadVariableOverloadTarget",
                loadVariableOverloadTargetBytes(),
                javaClass.classLoader,
            )
        val clazz = loadClass("LoadVariableOverloadTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val intResult = clazz.getMethod("value", Int::class.javaPrimitiveType).invoke(instance, 7)
        val stringResult = clazz.getMethod("value", String::class.java).invoke(instance, "raw")

        assertEquals(7, intResult)
        assertEquals("loaded-local-raw", stringResult)
    }

    @Test
    fun modifyVariableAtHeadUsesNameDiscriminatorWhenInferringOverload() {
        AsmRegistry.register(InferredNamedHeadModifyVariableMixin::class.java)

        val transformed =
            AsmProcessor().transform(
                "NamedHeadVariableOverloadTarget",
                namedHeadVariableOverloadTargetBytes(),
                javaClass.classLoader,
            )
        val clazz = loadClass("NamedHeadVariableOverloadTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val oneArgResult = clazz.getMethod("echo", String::class.java).invoke(instance, "value")
        val twoArgResult =
            clazz
                .getMethod("echo", String::class.java, Int::class.javaPrimitiveType)
                .invoke(instance, "value", 7)

        assertEquals("value", oneArgResult)
        assertEquals("named-value:7", twoArgResult)
    }

    @Test
    fun modifyVariableExposesCountContractParameters() {
        val methods = ModifyVariable::class.java.declaredMethods.associateBy { it.name }

        assertEquals(Int::class.javaPrimitiveType, methods["require"]?.returnType)
        assertEquals(Int::class.javaPrimitiveType, methods["expect"]?.returnType)
        assertEquals(Int::class.javaPrimitiveType, methods["allow"]?.returnType)
        assertEquals(Slice::class.java, methods["slice"]?.returnType)
    }

    @Test
    fun modifyVariableExposesNameDiscriminatorParameter() {
        val methods = ModifyVariable::class.java.declaredMethods.associateBy { it.name }

        assertEquals(Array<String>::class.java, methods["name"]?.returnType)
    }

    @Test
    fun modifyVariableExposesArgsOnlyParameter() {
        val methods = ModifyVariable::class.java.declaredMethods.associateBy { it.name }

        assertEquals(Boolean::class.javaPrimitiveType, methods["argsOnly"]?.returnType)
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
    fun modifyVariableAtHeadAcceptsAssignableTargetParameter() {
        AsmRegistry.register(ModifyVariableParentTargetParamMixin::class.java)

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
    fun modifyVariableAtStoreAcceptsGenericObjectReturnType() {
        AsmRegistry.register(ModifyVariableStoreGenericReturnMixin::class.java)

        val transformed = AsmProcessor().transform("StoreVariableTarget", storeVariableTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("StoreVariableTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("stored-generic-local", result)
    }

    @Test
    fun modifyVariableAtStoreAcceptsObjectHandlerParameterByLocalIndex() {
        AsmRegistry.register(ModifyVariableStoreObjectParameterMixin::class.java)

        val transformed = AsmProcessor().transform("StoreVariableTarget", storeVariableTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("StoreVariableTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("stored-object-local", result)
    }

    @Test
    fun modifyVariableAtStoreAcceptsObjectHandlerParameterAndGenericReturnByLocalIndex() {
        AsmRegistry.register(ModifyVariableStoreObjectParameterGenericReturnMixin::class.java)

        val transformed = AsmProcessor().transform("StoreVariableTarget", storeVariableTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("StoreVariableTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("stored-object-generic-local", result)
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
    fun modifyVariableAtStoreSelectsLocalVariableByNameInTestClass() {
        AsmRegistry.register(ModifyVariableNamedStoreTestMixin::class.java)

        val fixtureLoader = testFixtureClassLoader("Test", "TestParent", "TestInterface")
        val transformed = AsmProcessor().transform("Test", testFixtureClassBytes("Test"), fixtureLoader)
        val clazz =
            loadClasses(
                "Test",
                mapOf(
                    "Test" to transformed,
                    "TestParent" to testFixtureClassBytes("TestParent"),
                    "TestInterface" to testFixtureClassBytes("TestInterface"),
                    "TestFunctionalInterface" to testFixtureClassBytes("TestFunctionalInterface"),
                    "Test\$CustomException" to testFixtureClassBytes("Test\$CustomException"),
                    "Test\$InnerClass" to testFixtureClassBytes("Test\$InnerClass"),
                    "Test\$StaticInnerClass" to testFixtureClassBytes("Test\$StaticInnerClass"),
                    "Test\$TestEnum" to testFixtureClassBytes("Test\$TestEnum"),
                ),
            )
        val instance = clazz.getDeclaredConstructor(String::class.java).newInstance("raw")
        val result = clazz.getMethod("localNameDiscriminatorTest", String::class.java).invoke(instance, "value")

        assertEquals("value-first:named-value-second", result)
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
    fun modifyVariableAtLoadAcceptsObjectHandlerParameterByLocalIndex() {
        AsmRegistry.register(ModifyVariableLoadObjectParameterMixin::class.java)

        val transformed = AsmProcessor().transform("LoadVariableTarget", loadVariableTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("LoadVariableTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("loaded-object-local", result)
    }

    @Test
    fun modifyVariableAtLoadAcceptsObjectHandlerParameterAndGenericReturnByLocalIndex() {
        AsmRegistry.register(ModifyVariableLoadObjectParameterGenericReturnMixin::class.java)

        val transformed = AsmProcessor().transform("LoadVariableTarget", loadVariableTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("LoadVariableTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("loaded-object-generic-local", result)
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
    fun modifyVariableAtLoadArgsOnlySkipsNonParameterLocals() {
        AsmRegistry.register(ModifyVariableLoadArgsOnlyMixin::class.java)

        val transformed =
            AsmProcessor().transform("LoadArgsOnlyVariableTarget", loadArgsOnlyVariableTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("LoadArgsOnlyVariableTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value", String::class.java).invoke(instance, "input")

        assertEquals("arg-input:local", result)
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
    fun modifyVariableLoadSliceSupportsInvokeDynamicBoundaries() {
        AsmRegistry.register(ModifyVariableLoadInvokeDynamicSliceMixin::class.java)

        val transformed =
            AsmProcessor().transform(
                "InvokeDynamicSliceLoadVariableTarget",
                invokeDynamicSliceLoadVariableTargetBytes(),
                javaClass.classLoader,
            )
        val clazz = loadClass("InvokeDynamicSliceLoadVariableTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value", String::class.java).invoke(instance, "marker")

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
    fun asmInjectLoadSliceSupportsInvokeDynamicBoundaries() {
        AsmRegistry.register(LoadInjectInvokeDynamicSliceMixin::class.java)
        LoadInjectInvokeDynamicSliceMixin.injectCount = 0

        val transformed =
            AsmProcessor().transform(
                "InvokeDynamicSliceLoadVariableTarget",
                invokeDynamicSliceLoadVariableTargetBytes(),
                javaClass.classLoader,
            )
        val clazz = loadClass("InvokeDynamicSliceLoadVariableTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value", String::class.java).invoke(instance, "marker")

        assertEquals("pre:inside:outside", result)
        assertEquals(1, LoadInjectInvokeDynamicSliceMixin.injectCount)
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
    fun asmInjectLoadArgsNameLimitsLocalVariableName() {
        AsmRegistry.register(LoadInjectNameMixin::class.java)
        LoadInjectNameMixin.injectCount = 0

        val transformed =
            AsmProcessor().transform("NamedLoadVariableTarget", namedLoadVariableTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("NamedLoadVariableTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("first:second", result)
        assertEquals(1, LoadInjectNameMixin.injectCount)
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
    fun asmInjectStoreArgsNameLimitsLocalVariableName() {
        AsmRegistry.register(StoreInjectNameMixin::class.java)
        StoreInjectNameMixin.injectCount = 0

        val transformed =
            AsmProcessor().transform("NamedStoreVariableTarget", namedStoreVariableTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("NamedStoreVariableTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("first:second", result)
        assertEquals(1, StoreInjectNameMixin.injectCount)
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
    @DisplayName("@Copy 应保留 @JvmStatic 辅助方法的静态访问标志")
    fun copyJvmStaticHelperPreservesStaticAccessAndRewritesCall() {
        // Given
        AsmRegistry.register(JvmStaticCopyMixin::class.java)

        // When
        val transformed = AsmProcessor().transform("ReturnTarget", returnTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val copiedMethod = classNode.methods.single { it.name == "copied" && it.desc == "()Ljava/lang/String;" }
        val clazz = loadClass("ReturnTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        // Then
        assertThat(copiedMethod.access and Opcodes.ACC_STATIC)
            .`as`("@Copy 复制 @JvmStatic helper 时应保留 static 标志，否则改写后的 INVOKESTATIC 调用会在运行期失败")
            .isNotZero()
        assertThat(result)
            .`as`("目标方法应通过复制后的静态 helper 返回业务值")
            .isEqualTo("copied")
    }

    @Test
    fun accessorMethodConflictFailsDuringTransform() {
        AsmRegistry.register(ConflictingAccessorMixin::class.java)

        assertThrows(AsmTransformException::class.java) {
            AsmProcessor().transform("AccessorConflictTarget", accessorConflictTargetBytes(), javaClass.classLoader)
        }
    }

    @Test
    fun accessorCanReadInheritedProtectedField() {
        AsmRegistry.register(InheritedProtectedFieldAccessorMixin::class.java)

        val transformed =
            AsmProcessor().transform("InheritedAccessorTarget", inheritedAccessorTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val accessorMethod = classNode.methods.single { it.name == "getModCount" }
        val fieldRead = accessorMethod.instructions.toArray()
            .filterIsInstance<org.objectweb.asm.tree.FieldInsnNode>()
            .single { it.name == "modCount" }

        assertEquals("java/util/AbstractList", fieldRead.owner)
        assertEquals(Opcodes.GETFIELD, fieldRead.opcode)
    }

    @Test
    fun accessorCanReadInheritedStaticField() {
        AsmRegistry.register(InheritedStaticFieldAccessorMixin::class.java)

        val transformed =
            AsmProcessor().transform("InheritedStaticAccessorTarget", inheritedStaticAccessorTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val accessorMethod = classNode.methods.single { it.name == "getEra" }
        val fieldRead = accessorMethod.instructions.toArray()
            .filterIsInstance<org.objectweb.asm.tree.FieldInsnNode>()
            .single { it.name == "ERA" }

        assertEquals("java/util/Calendar", fieldRead.owner)
        assertEquals(Opcodes.GETSTATIC, fieldRead.opcode)
    }

    @Test
    fun accessorCanReadInheritedInterfaceField() {
        AsmRegistry.register(InheritedInterfaceFieldAccessorMixin::class.java)

        val transformed =
            AsmProcessor().transform("InheritedInterfaceAccessorTarget", inheritedInterfaceAccessorTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val accessorMethod = classNode.methods.single { it.name == "getSqlIntegerType" }
        val fieldRead = accessorMethod.instructions.toArray()
            .filterIsInstance<org.objectweb.asm.tree.FieldInsnNode>()
            .single { it.name == "INTEGER" }

        assertEquals("java/sql/Types", fieldRead.owner)
        assertEquals(Opcodes.GETSTATIC, fieldRead.opcode)
    }

    @Test
    fun accessorSetterForInheritedInterfaceFieldFailsDuringTransform() {
        AsmRegistry.register(InterfaceFieldSetterAccessorMixin::class.java)

        assertThrows(AsmTransformException::class.java) {
            AsmProcessor().transform("InheritedInterfaceAccessorTarget", inheritedInterfaceAccessorTargetBytes(), javaClass.classLoader)
        }
    }

    @Test
    fun invokerCanCallInheritedMethod() {
        AsmRegistry.register(InheritedMethodInvokerMixin::class.java)

        val transformed =
            AsmProcessor().transform("InheritedAccessorTarget", inheritedAccessorTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("InheritedAccessorTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        clazz.getMethod("add", Any::class.java).invoke(instance, "entry")
        val result = clazz.getMethod("callSize").invoke(instance)

        assertEquals(1, result)
    }

    @Test
    fun invokerCanCallInterfaceDefaultMethod() {
        AsmRegistry.register(InterfaceDefaultMethodInvokerMixin::class.java)

        val transformed =
            AsmProcessor().transform("InterfaceDefaultInvokerTarget", interfaceDefaultInvokerTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val invokerMethod = classNode.methods.single {
            it.name == "callSpliterator" && it.desc == "()Ljava/util/Spliterator;"
        }
        val defaultCall = invokerMethod.instructions.toArray()
            .filterIsInstance<org.objectweb.asm.tree.MethodInsnNode>()
            .single { it.name == "spliterator" }
        val clazz = loadClass("InterfaceDefaultInvokerTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        @Suppress("UNCHECKED_CAST")
        val spliterator = clazz.getMethod("callSpliterator").invoke(instance) as java.util.Spliterator<Any?>

        assertEquals(Opcodes.INVOKEINTERFACE, defaultCall.opcode)
        assertEquals("java/lang/Iterable", defaultCall.owner)
        assertEquals(true, defaultCall.itf)
        assertEquals(false, spliterator.tryAdvance { _ -> })
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
    fun constructorInvokerCanReturnImplementedInterface() {
        AsmRegistry.register(InterfaceReturnConstructorInvokerMixin::class.java)

        val transformed =
            AsmProcessor().transform("ConstructorInvokerTarget", constructorInvokerTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val method = classNode.methods.single { it.name == "createAsRunnable" }

        assertEquals("(Ljava/lang/String;)Ljava/lang/Runnable;", method.desc)
    }

    @Test
    fun constructorInvokerCanReturnInheritedInterface() {
        AsmRegistry.register(InheritedInterfaceReturnConstructorInvokerMixin::class.java)

        val transformed =
            AsmProcessor().transform("ConstructorInvokerTarget", constructorInvokerTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val method = classNode.methods.single { it.name == "createAsList" }

        assertEquals("(Ljava/lang/String;)Ljava/util/List;", method.desc)
    }

    @Test
    fun invokerUsesInvokespecialForPrivateInterfaceMethod() {
        AsmRegistry.register(PrivateInterfaceInvokerMixin::class.java)

        val transformed =
            AsmProcessor().transform("PrivateInterfaceInvokerTarget", privateInterfaceInvokerTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val invokerMethod = classNode.methods.single {
            it.name == "callSecret" && it.desc == "(Ljava/lang/String;)Ljava/lang/String;"
        }
        val secretCall = invokerMethod.instructions.toArray()
            .filterIsInstance<org.objectweb.asm.tree.MethodInsnNode>()
            .single { it.name == "secret" }

        assertEquals(Opcodes.INVOKESPECIAL, secretCall.opcode)
        assertEquals(true, secretCall.itf)
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
    fun fieldInjectByMovesHandlerForwardFromMatchedFieldRead() {
        AsmRegistry.register(FieldReadByForwardMixin::class.java)

        val transformed = AsmProcessor().transform("FieldPointTarget", fieldPointTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val method = classNode.methods.single { it.name == "readName" }
        val instructions = method.instructions.toArray()
        val handlerCallIndex = handlerCallIndex(instructions, FieldReadByForwardMixin::class.java, "inject")
        val fieldReadIndex = instructions.indexOfFirst {
            it is org.objectweb.asm.tree.FieldInsnNode &&
                it.opcode == Opcodes.GETFIELD &&
                it.owner == "FieldPointTarget" &&
                it.name == "name"
        }
        val returnIndex = instructions.indexOfFirst { it.opcode == Opcodes.ARETURN }

        assertEquals(true, handlerCallIndex >= 0)
        assertEquals(true, fieldReadIndex >= 0)
        assertEquals(true, returnIndex >= 0)
        assertEquals(true, handlerCallIndex > fieldReadIndex)
        assertEquals(returnIndex - 1, handlerCallIndex)
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
    fun redirectMethodCallAcceptsAssignableParentParameter() {
        AsmRegistry.register(AssignableParentParameterRedirectMixin::class.java)

        val transformed = AsmProcessor().transform("RedirectTarget", redirectTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("RedirectTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("call").invoke(instance)

        assertEquals("parent-value", result)
    }

    @Test
    fun redirectMethodCallAcceptsGenericObjectReturnType() {
        AsmRegistry.register(GenericObjectReturnRedirectMixin::class.java)

        val transformed = AsmProcessor().transform("RedirectTarget", redirectTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("RedirectTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("call").invoke(instance)

        assertEquals("object-value", result)
    }

    @Test
    fun redirectInfersTargetWhenMethodIsOmitted() {
        AsmRegistry.register(InferredRedirectTargetMixin::class.java)

        val transformed = AsmProcessor().transform("RedirectTarget", redirectTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("RedirectTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("call").invoke(instance)

        assertEquals("inferred-value", result)
    }

    @Test
    fun redirectAtInvokeInfersCallTargetByHandlerSignature() {
        AsmRegistry.register(InferredInvokeRedirectTargetMixin::class.java)

        val transformed = AsmProcessor().transform("RedirectTarget", redirectTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("RedirectTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("call").invoke(instance)

        assertEquals("inferred-call-value", result)
    }

    @Test
    fun redirectAtInvokeInfersMethodAndCallTargetByHandlerSignature() {
        AsmRegistry.register(InferredMethodAndInvokeRedirectTargetMixin::class.java)

        val transformed = AsmProcessor().transform("RedirectTarget", redirectTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("RedirectTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("call").invoke(instance)

        assertEquals("inferred-method-value", result)
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
    fun redirectAtInvokeReplacesInvokeDynamicCall() {
        AsmRegistry.register(RedirectInvokeDynamicMixin::class.java)

        val transformed =
            AsmProcessor().transform("InvokeDynamicExpressionValueTarget", invokeDynamicExpressionValueTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("InvokeDynamicExpressionValueTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value", String::class.java, Int::class.javaPrimitiveType).invoke(instance, "raw", 7)

        assertEquals("RAW-8-redirected", result)
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
    fun redirectNewReplacesNewObjectExpression() {
        AsmRegistry.register(NewConstructorRedirectMixin::class.java)

        val transformed =
            AsmProcessor().transform("ConstructorModifyArgTarget", constructorModifyArgTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ConstructorModifyArgTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals("new-redirected-raw", result)
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
    fun redirectConstructorCallAcceptsAssignableSubtypeReturn() {
        AsmRegistry.register(ConstructorRedirectAssignableReturnMixin::class.java)

        val transformed =
            AsmProcessor().transform(
                "RuntimeExceptionConstructorTarget",
                runtimeExceptionConstructorTargetBytes(),
                javaClass.classLoader,
            )
        val clazz = loadClass("RuntimeExceptionConstructorTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("message").invoke(instance)

        assertEquals("child-raw", result)
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
    fun redirectAtCastReplacesCheckcastValue() {
        AsmRegistry.register(CastRedirectMixin::class.java)

        val transformed = AsmProcessor().transform("CastInstructionTarget", castInstructionTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("CastInstructionTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val method = clazz.getMethod("cast", Any::class.java)

        assertEquals("redirect-raw-true", method.invoke(instance, StringBuilder("raw")))
    }

    @Test
    fun redirectAtCastWithoutTargetUsesHandlerTypeCompatibleCheckcast() {
        AsmRegistry.register(AnyCastRedirectMixin::class.java)

        val transformed = AsmProcessor().transform("MultiCastInstructionTarget", multiCastInstructionTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("MultiCastInstructionTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val method = clazz.getMethod("cast", Any::class.java, Any::class.java)

        assertEquals("any-raw", method.invoke(instance, StringBuilder("ignored"), "raw"))
    }

    @Test
    fun redirectAtInstanceofReplacesTypeCheckResult() {
        AsmRegistry.register(InstanceofRedirectMixin::class.java)

        val transformed = AsmProcessor().transform("InstanceofTarget", instanceofTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("InstanceofTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val method = clazz.getMethod("isString", Any::class.java, Boolean::class.javaPrimitiveType)

        assertEquals(true, method.invoke(instance, 42, false))
        assertEquals(false, method.invoke(instance, "raw", false))
        assertEquals(true, method.invoke(instance, "raw", true))
    }

    @Test
    fun redirectAtInstanceofWithoutTargetReplacesTypeChecks() {
        AsmRegistry.register(AnyInstanceofRedirectMixin::class.java)

        val transformed = AsmProcessor().transform("MultiInstanceofTarget", multiInstanceofTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("MultiInstanceofTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val method = clazz.getMethod("isString", Any::class.java, Any::class.java)

        assertEquals(true, method.invoke(instance, StringBuilder("ignored"), StringBuilder("raw")))
        assertEquals(false, method.invoke(instance, StringBuilder("ignored"), "raw"))
    }

    @Test
    fun redirectAtJumpReplacesBranchDecision() {
        AsmRegistry.register(JumpRedirectMixin::class.java)

        val transformed = AsmProcessor().transform("JumpOperationTarget", jumpOperationTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("JumpOperationTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val method = clazz.getMethod("choose", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)

        assertEquals("positive", method.invoke(instance, 5, false))
        assertEquals("positive", method.invoke(instance, -1, false))
        assertEquals("negative", method.invoke(instance, 5, true))
    }

    @Test
    @DisplayName("省略 JUMP target 时应替换兼容条件跳转结果")
    fun redirectAtJumpWithoutTargetReplacesBranchDecision() {
        // Given
        AsmRegistry.register(UntargetedJumpRedirectMixin::class.java)

        // When
        val transformed = AsmProcessor().transform("JumpOperationTarget", jumpOperationTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("JumpOperationTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val method = clazz.getMethod("choose", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)

        // Then
        assertThat(method.invoke(instance, 5, false))
            .`as`("Then: 正数原本不跳转，省略 target 的 JUMP redirect 取反后应进入 negative 分支")
            .isEqualTo("negative")
        assertThat(method.invoke(instance, -1, false))
            .`as`("Then: 负数原本跳转，取反后应继续走 positive 分支")
            .isEqualTo("positive")
    }

    @Test
    fun redirectAtJumpSupportsKotlinObjectHandler() {
        AsmRegistry.register(ObjectInstanceJumpRedirectMixin::class.java)

        val transformed = AsmProcessor().transform("JumpOperationTarget", jumpOperationTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("JumpOperationTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val method = clazz.getMethod("choose", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)

        assertEquals("negative", method.invoke(instance, 5, false))
        assertEquals("positive", method.invoke(instance, -1, false))
        assertEquals("negative", method.invoke(instance, 5, true))
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
    fun redirectArrayReadAcceptsGenericObjectReturnType() {
        AsmRegistry.register(ArrayReadObjectReturnRedirectMixin::class.java)

        val transformed = AsmProcessor().transform("ArrayAccessTarget", arrayAccessTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ArrayAccessTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("readName", Int::class.javaPrimitiveType).invoke(instance, 0)

        assertEquals("object-raw", result)
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
    @DisplayName("数组写入重定向必须使用 FIELD_ASSIGN，旧的 FIELD + array=set 应快速失败")
    fun redirectArraySetRequiresFieldAssignInjectionPoint() {
        // Given
        AsmRegistry.register(FieldArraySetRedirectMixin::class.java)

        // When / Then
        assertThatThrownBy {
            AsmProcessor().transform("ArrayAccessTarget", arrayAccessTargetBytes(), javaClass.classLoader)
        }
            .`as`("Then: FIELD + array=set 会把写入误描述成字段读取，应在转换阶段暴露配置错误")
            .isInstanceOf(AsmTransformException::class.java)
            .hasRootCauseMessage("@Redirect array=set requires FIELD_ASSIGN injection point")
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
    fun redirectArrayReadWithIncompatibleReturnTypeFailsDuringTransform() {
        AsmRegistry.register(IncompatibleArrayReadRedirectReturnMixin::class.java)

        val exception =
            assertThrows(AsmTransformException::class.java) {
                AsmProcessor().transform("ArrayAccessTarget", arrayAccessTargetBytes(), javaClass.classLoader)
            }

        assertEquals(
            true,
            exception.cause?.message?.contains("return type mismatch") == true,
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
    fun redirectFieldSliceLimitsFieldReadsBetweenFromAndTo() {
        AsmRegistry.register(RedirectFieldReadSliceMixin::class.java)

        val transformed = AsmProcessor().transform("SliceFieldReadTarget", sliceFieldReadTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("SliceFieldReadTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeName", String::class.java).invoke(instance, "raw")
        val result = clazz.getMethod("readSelected").invoke(instance)

        assertEquals("redirected", result)
    }

    @Test
    fun redirectFieldAssignSliceLimitsFieldWritesBetweenFromAndTo() {
        RedirectFieldAssignSliceMixin.lastValue = null
        AsmRegistry.register(RedirectFieldAssignSliceMixin::class.java)

        val transformed = AsmProcessor().transform("SliceFieldAssignTarget", sliceFieldAssignTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("SliceFieldAssignTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()

        clazz.getMethod("writeSelected", String::class.java, String::class.java).invoke(instance, "outside", "inside")
        val result = clazz.getField("name").get(instance)

        assertEquals("outside", result)
        assertEquals("inside", RedirectFieldAssignSliceMixin.lastValue)
    }

    @Test
    fun redirectArrayReadSliceLimitsLoadsBetweenFromAndTo() {
        AsmRegistry.register(RedirectArrayReadSliceMixin::class.java)

        val transformed =
            AsmProcessor().transform("SliceArrayExpressionValueTarget", sliceArrayExpressionValueTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("SliceArrayExpressionValueTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("readSelected", Int::class.javaPrimitiveType).invoke(instance, 0)

        assertEquals("redirected-raw", result)
    }

    @Test
    fun redirectArrayLengthSliceLimitsLengthsBetweenFromAndTo() {
        AsmRegistry.register(RedirectArrayLengthSliceMixin::class.java)

        val transformed =
            AsmProcessor().transform("SliceArrayExpressionValueTarget", sliceArrayExpressionValueTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("SliceArrayExpressionValueTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("countSelected").invoke(instance)

        assertEquals(6, result)
    }

    @Test
    fun redirectArrayWriteSliceLimitsStoresBetweenFromAndTo() {
        AsmRegistry.register(RedirectArrayWriteSliceMixin::class.java)

        val transformed =
            AsmProcessor().transform("SliceWrapConditionArrayTarget", sliceWrapConditionArrayTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("SliceWrapConditionArrayTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("writeSelected").invoke(instance)

        assertEquals("pre:redirected-inside:outside", result)
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
    fun fieldAssignInjectByMovesHandlerBackwardFromMatchedFieldWrite() {
        AsmRegistry.register(FieldAssignByBackwardMixin::class.java)

        val transformed = AsmProcessor().transform("FieldPointTarget", fieldPointTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val method = classNode.methods.single { it.name == "writeName" }
        val instructions = method.instructions.toArray()
        val handlerCallIndex = handlerCallIndex(instructions, FieldAssignByBackwardMixin::class.java, "inject")
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
    fun newInjectSliceLimitsConstructionsAfterFrom() {
        AsmRegistry.register(NewInstructionSliceMixin::class.java)

        val transformed = AsmProcessor().transform("MultiNewTarget", multiNewTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val method = classNode.methods.single { it.name == "value" }
        val instructions = method.instructions.toArray()
        val mixinOwner = org.objectweb.asm.Type.getInternalName(NewInstructionSliceMixin::class.java)
        val handlerCallIndexes = instructions.mapIndexedNotNull { index, insn ->
            if (insn is org.objectweb.asm.tree.MethodInsnNode && insn.owner == mixinOwner && insn.name == "inject") {
                index
            } else {
                null
            }
        }
        val boundaryIndexes = instructions.mapIndexedNotNull { index, insn ->
            if (insn is org.objectweb.asm.tree.MethodInsnNode &&
                insn.owner == "java/lang/String" &&
                insn.name == "concat"
            ) {
                index
            } else {
                null
            }
        }
        val newIndexes = instructions.mapIndexedNotNull { index, insn ->
            if (insn is org.objectweb.asm.tree.TypeInsnNode &&
                insn.opcode == Opcodes.NEW &&
                insn.desc == "java/lang/StringBuilder"
            ) {
                index
            } else {
                null
            }
        }

        assertEquals(1, handlerCallIndexes.size)
        assertEquals(true, boundaryIndexes.isNotEmpty())
        val inSliceNewIndexes = newIndexes.filter { it > boundaryIndexes.first() }
        assertEquals(1, inSliceNewIndexes.size)
        assertEquals(inSliceNewIndexes.single() - 1, handlerCallIndexes.single())
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
    fun instanceofInjectInsertsHandlerBeforeMatchedInstanceofInstruction() {
        AsmRegistry.register(InstanceofInstructionInjectMixin::class.java)

        val transformed = AsmProcessor().transform("InstanceofTarget", instanceofTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val method = classNode.methods.single { it.name == "isString" }
        val instructions = method.instructions.toArray()
        val handlerCallIndex = handlerCallIndex(instructions, InstanceofInstructionInjectMixin::class.java, "inject")
        val instanceofIndex = instructions.indexOfFirst {
            it is org.objectweb.asm.tree.TypeInsnNode &&
                it.opcode == Opcodes.INSTANCEOF &&
                it.desc == "java/lang/String"
        }

        assertEquals(true, handlerCallIndex >= 0)
        assertEquals(true, instanceofIndex >= 0)
        assertEquals(instanceofIndex - 1, handlerCallIndex)
    }

    @Test
    fun jumpInjectInsertsHandlerBeforeMatchedJumpInstructionInTestClass() {
        AsmRegistry.register(JumpInstructionInjectMixin::class.java)

        val fixtureLoader = testFixtureClassLoader("Test", "TestParent", "TestInterface")
        val transformed = AsmProcessor().transform("Test", testFixtureClassBytes("Test"), fixtureLoader)
        val classNode = readClass(transformed)
        val method = classNode.methods.single { it.name == "exceptionTest" && it.desc == "(Z)Ljava/lang/String;" }
        val instructions = method.instructions.toArray()
        val handlerCallIndex = handlerCallIndex(instructions, JumpInstructionInjectMixin::class.java, "inject")
        val jumpIndex = instructions.indexOfFirst {
            it is org.objectweb.asm.tree.JumpInsnNode && it.opcode == Opcodes.IFEQ
        }

        assertEquals(true, handlerCallIndex >= 0)
        assertEquals(true, jumpIndex >= 0)
        assertEquals(jumpIndex - 1, handlerCallIndex)
    }

    @Test
    fun jumpInjectTargetAcceptsNumericOpcodeInTestClass() {
        AsmRegistry.register(JumpInstructionNumericTargetMixin::class.java)

        val fixtureLoader = testFixtureClassLoader("Test", "TestParent", "TestInterface")
        val transformed = AsmProcessor().transform("Test", testFixtureClassBytes("Test"), fixtureLoader)
        val classNode = readClass(transformed)
        val method = classNode.methods.single { it.name == "recursiveMethod" && it.desc == "(I)I" }
        val instructions = method.instructions.toArray()
        val handlerCallIndex = handlerCallIndex(instructions, JumpInstructionNumericTargetMixin::class.java, "inject")
        val jumpIndex = instructions.indexOfFirst {
            it is org.objectweb.asm.tree.JumpInsnNode && it.opcode == Opcodes.IF_ICMPGT
        }

        assertEquals(true, handlerCallIndex >= 0)
        assertEquals(true, jumpIndex >= 0)
        assertEquals(jumpIndex - 1, handlerCallIndex)
    }

    @Test
    fun switchInjectInsertsHandlerBeforeTableSwitchWithoutConsumingSelector() {
        AsmRegistry.register(SwitchInstructionInjectMixin::class.java)

        val transformed = AsmProcessor().transform("SwitchSelectorTarget", switchSelectorTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val methodNode = classNode.methods.single { it.name == "choose" }
        val instructions = methodNode.instructions.toArray()
        val handlerCallIndex = handlerCallIndex(instructions, SwitchInstructionInjectMixin::class.java, "inject")
        val switchIndex = instructions.indexOfFirst { it is org.objectweb.asm.tree.TableSwitchInsnNode }
        val clazz = loadClass("SwitchSelectorTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val method = clazz.getMethod("choose", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)

        assertEquals(true, handlerCallIndex >= 0)
        assertEquals(true, switchIndex >= 0)
        assertEquals(switchIndex - 1, handlerCallIndex)
        assertEquals("one", method.invoke(instance, 1, false))
    }

    @Test
    fun constantInjectInsertsHandlerBeforeMatchedConstantInTestClass() {
        AsmRegistry.register(ConstantInstructionInjectMixin::class.java)

        val fixtureLoader = testFixtureClassLoader("Test", "TestParent", "TestInterface")
        val transformed = AsmProcessor().transform("Test", testFixtureClassBytes("Test"), fixtureLoader)
        val classNode = readClass(transformed)
        val method = classNode.methods.single { it.name == "testB0" && it.desc == "()Ljava/lang/String;" }
        val instructions = method.instructions.toArray()
        val handlerCallIndex = handlerCallIndex(instructions, ConstantInstructionInjectMixin::class.java, "inject")
        val constantIndex = instructions.indexOfFirst {
            it is org.objectweb.asm.tree.LdcInsnNode && it.cst == "StaticFinalString"
        }

        assertEquals(true, handlerCallIndex >= 0)
        assertEquals(true, constantIndex >= 0)
        assertEquals(constantIndex - 1, handlerCallIndex)
    }

    @Test
    fun constantInjectReplaceUsesHandlerReturnAsConstantValue() {
        AsmRegistry.register(ConstantInstructionReplaceMixin::class.java)

        val transformed = AsmProcessor().transform("ConstantParamTarget", constantParamTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("ConstantParamTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value", String::class.java, Int::class.javaPrimitiveType).invoke(instance, "suffix", 4)

        assertEquals("suffix-4", result)
    }

    @Test
    fun constantInjectReplaceTreatsExplicitBooleanTargetAsBooleanConstant() {
        AsmRegistry.register(BooleanConstantInstructionReplaceMixin::class.java)

        val transformed = AsmProcessor().transform("TrueBooleanConstantTarget", trueBooleanConstantTargetBytes(), javaClass.classLoader)
        val clazz = loadClass("TrueBooleanConstantTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value").invoke(instance)

        assertEquals(false, result)
    }

    @Test
    fun invokeAssignInjectDefaultsToAfterMatchedCallInTestClass() {
        AsmRegistry.register(InvokeAssignInjectMixin::class.java)

        val fixtureLoader = testFixtureClassLoader("Test", "TestParent", "TestInterface")
        val transformed = AsmProcessor().transform("Test", testFixtureClassBytes("Test"), fixtureLoader)
        val classNode = readClass(transformed)
        val method = classNode.methods.single { it.name == "testVoid" && it.desc == "()V" }
        val instructions = method.instructions.toArray()
        val handlerCallIndex = handlerCallIndex(instructions, InvokeAssignInjectMixin::class.java, "inject")
        val printlnIndex = instructions.indexOfFirst {
            it is org.objectweb.asm.tree.MethodInsnNode &&
                it.owner == "java/io/PrintStream" &&
                it.name == "println" &&
                it.desc == "(Ljava/lang/String;)V"
        }

        assertEquals(true, handlerCallIndex >= 0)
        assertEquals(true, printlnIndex >= 0)
        assertEquals(printlnIndex + 1, handlerCallIndex)
    }

    @Test
    fun invokeAssignInjectAtInvokeDynamicDefaultsToAfterMatchedCall() {
        AsmRegistry.register(InvokeAssignDynamicInjectMixin::class.java)
        InvokeAssignDynamicInjectMixin.injectCount = 0
        InvokeAssignDynamicInjectMixin.observed = ""

        val transformed =
            AsmProcessor().transform("InvokeDynamicExpressionValueTarget", invokeDynamicExpressionValueTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val method = classNode.methods.single { it.name == "value" && it.desc == "(Ljava/lang/String;I)Ljava/lang/String;" }
        val instructions = method.instructions.toArray()
        val invokeDynamicIndex = instructions.indexOfFirst {
            it is org.objectweb.asm.tree.InvokeDynamicInsnNode &&
                it.bsm.owner == "java/lang/invoke/StringConcatFactory" &&
                it.name == "makeConcatWithConstants"
        }
        val handlerCallIndex = handlerCallIndex(instructions, InvokeAssignDynamicInjectMixin::class.java, "inject")
        val clazz = loadClass("InvokeDynamicExpressionValueTarget", transformed)
        val instance = clazz.getDeclaredConstructor().newInstance()
        val result = clazz.getMethod("value", String::class.java, Int::class.javaPrimitiveType).invoke(instance, "raw", 7)

        assertEquals(true, invokeDynamicIndex >= 0)
        assertEquals(true, handlerCallIndex > invokeDynamicIndex)
        assertEquals("raw-7", result)
        assertEquals(1, InvokeAssignDynamicInjectMixin.injectCount)
        assertEquals("raw:7", InvokeAssignDynamicInjectMixin.observed)
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
    fun throwInjectTargetFiltersDirectlyConstructedThrowable() {
        AsmRegistry.register(ThrowInstructionTargetedMixin::class.java)

        val transformed =
            AsmProcessor().transform("TargetedThrowPointTarget", targetedThrowPointTargetBytes(), javaClass.classLoader)
        val classNode = readClass(transformed)
        val method = classNode.methods.single { it.name == "fail" }
        val instructions = method.instructions.toArray()
        val mixinOwner = org.objectweb.asm.Type.getInternalName(ThrowInstructionTargetedMixin::class.java)
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

        assertEquals(1, handlerCallIndexes.size)
        assertEquals(true, throwIndexes.size >= 2)
        assertEquals(throwIndexes.first() - 1, handlerCallIndexes.single())
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

    @AsmMixin("RedirectTarget")
    object AssignableParentParameterRedirectMixin {
        @Redirect(
            method = "call()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.trim()Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun redirect(value: CharSequence): String = "parent-${value.trim()}"
    }

    @AsmMixin("RedirectTarget")
    object GenericObjectReturnRedirectMixin {
        @Redirect(
            method = "call()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.trim()Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun redirect(value: String): Any = "object-${value.trim()}"
    }

    @AsmMixin("RedirectTarget")
    object InferredRedirectTargetMixin {
        @Redirect(
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.trim()Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun call(value: String): String = "inferred-${value.trim()}"
    }

    @AsmMixin("RedirectTarget")
    object InferredInvokeRedirectTargetMixin {
        @Redirect(
            method = "call()Ljava/lang/String;",
            at = At(value = InjectionPoint.INVOKE),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun redirect(value: String): String = "inferred-call-${value.trim()}"
    }

    @AsmMixin("RedirectTarget")
    object InferredMethodAndInvokeRedirectTargetMixin {
        @Redirect(
            at = At(value = InjectionPoint.INVOKE),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun call(value: String): String = "inferred-method-${value.trim()}"
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

    @AsmMixin("InvokeDynamicExpressionValueTarget")
    object RedirectInvokeDynamicMixin {
        @Redirect(
            method = "value(Ljava/lang/String;I)Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/invoke/StringConcatFactory.makeConcatWithConstants(Ljava/lang/String;I)Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun redirect(
            prefix: String,
            count: Int,
        ): String = "${prefix.uppercase()}-${count + 1}-redirected"
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

    @AsmMixin("ConstructorModifyArgTarget")
    object NewConstructorRedirectMixin {
        @Redirect(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.NEW,
                target = "java/lang/StringBuilder",
            ),
        )
        @JvmStatic
        fun redirect(value: String): StringBuilder = StringBuilder("new-redirected-$value")
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

    @AsmMixin("RuntimeExceptionConstructorTarget")
    object ConstructorRedirectAssignableReturnMixin {
        @Redirect(
            method = "message()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/RuntimeException.<init>(Ljava/lang/String;)V",
            ),
        )
        @JvmStatic
        fun redirect(value: String): IllegalArgumentException = IllegalArgumentException("child-$value")
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

    @AsmMixin("ReturnTarget")
    object NonCancellableHeadCancelMixin {
        @AsmInject(method = "value()Ljava/lang/String;", target = InjectionPoint.HEAD)
        @JvmStatic
        fun inject(callback: CallbackInfo) {
            callback.cancel()
        }
    }

    @AsmMixin("ReturnTarget")
    object CancellableHeadCancelReturnMixin {
        @AsmInject(method = "value()Ljava/lang/String;", target = InjectionPoint.HEAD, cancellable = true)
        @JvmStatic
        fun inject(callback: CallbackInfo) {
            callback.setReturnValue("cancelled")
            callback.cancel()
        }
    }

    @AsmMixin("ReturnTarget")
    object CancellableHeadSetReturnValueMixin {
        @AsmInject(method = "value()Ljava/lang/String;", target = InjectionPoint.HEAD, cancellable = true)
        @JvmStatic
        fun inject(callback: CallbackInfo) {
            callback.setReturnValue("set-only")
        }
    }

    @AsmMixin("ReturnTarget")
    object CancellableHeadCallbackInfoReturnableMixin {
        @AsmInject(method = "value()Ljava/lang/String;", target = InjectionPoint.HEAD, cancellable = true)
        @JvmStatic
        fun inject(callback: CallbackInfoReturnable<String>) {
            callback.setReturnValue("typed-head")
        }
    }

    @AsmMixin("ReturnTarget")
    object ReturnSetNullMixin {
        @AsmInject(method = "value()Ljava/lang/String;", target = InjectionPoint.RETURN)
        @JvmStatic
        fun inject(callback: CallbackInfo) {
            callback.setReturnValue(null)
        }
    }

    @AsmMixin("ReturnTarget")
    object ReturnCallbackInfoReturnableMixin {
        @AsmInject(method = "value()Ljava/lang/String;", target = InjectionPoint.RETURN)
        @JvmStatic
        fun inject(callback: CallbackInfoReturnable<String>) {
            val original: String? = callback.getReturnValue()
            callback.setReturnValue("${original}-typed")
        }
    }

    @AsmMixin("ReturnTarget")
    object TailCallbackInfoReturnableMixin {
        @AsmInject(method = "value()Ljava/lang/String;", target = InjectionPoint.TAIL)
        @JvmStatic
        fun inject(callback: CallbackInfoReturnable<String>) {
            callback.value = "${callback.value}-tail"
        }
    }

    @AsmMixin("ReturnTarget")
    object CancellableTailSetReturnValueMixin {
        @AsmInject(method = "value()Ljava/lang/String;", target = InjectionPoint.TAIL, cancellable = true)
        @JvmStatic
        fun inject(callback: CallbackInfoReturnable<String>) {
            callback.value = "tail-cancelled"
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
    object ModifyReturnValueObjectParameterMixin {
        @ModifyReturnValue(method = "value()Ljava/lang/String;")
        @JvmStatic
        fun modify(original: Any): String = "$original-any"
    }

    @AsmMixin("ReturnTarget")
    object ModifyReturnValueAssignableParentMixin {
        @ModifyReturnValue(method = "value()Ljava/lang/String;")
        @JvmStatic
        fun modify(original: CharSequence): String = "$original-parent"
    }

    @AsmMixin("CharSequenceReturnTarget")
    object ModifyReturnValueAssignableReturnMixin {
        @ModifyReturnValue(method = "value()Ljava/lang/CharSequence;")
        @JvmStatic
        fun modify(original: CharSequence): String = "$original-subtype"
    }

    @AsmMixin("ReturnTarget")
    object ModifyReturnValueGenericReturnMixin {
        @ModifyReturnValue(method = "value()Ljava/lang/String;")
        @JvmStatic
        fun modify(original: Any): Any = "$original-object"
    }

    @AsmMixin("ReturnTarget")
    object ModifyReturnValueZeroParameterInstanceMixin {
        @ModifyReturnValue(method = "value()Ljava/lang/String;")
        fun modify(): String = "constant"
    }

    @AsmMixin("RedirectParamTarget")
    object ModifyReturnValueWithTargetParamsMixin {
        @ModifyReturnValue(method = "value(Ljava/lang/String;I)Ljava/lang/String;")
        @JvmStatic
        fun modify(
            original: String,
            suffix: String,
            count: Int,
        ): String = "$original-$suffix$count"
    }

    @AsmMixin("ReturnTarget")
    object InferredModifyReturnValueMixin {
        @ModifyReturnValue
        @JvmStatic
        fun value(original: Any): Any = "$original-inferred"
    }

    @AsmMixin("OrdinalReturnInferenceTarget")
    object InferredOrdinalModifyReturnValueMixin {
        @ModifyReturnValue(ordinal = 1)
        @JvmStatic
        fun value(original: String): String = "ordinal-$original"
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

    @AsmMixin("SliceReturnValueTarget")
    object ModifyReturnValueSliceMixin {
        @ModifyReturnValue(
            method = "value(I)Ljava/lang/String;",
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
                to = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun modify(original: String): String = "modified-$original"
    }

    @AsmMixin("SliceReturnValueTarget")
    object EmptyInvokeSliceModifyReturnValueMixin {
        @ModifyReturnValue(
            method = "value(I)Ljava/lang/String;",
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE),
            ),
            require = 1,
        )
        @JvmStatic
        fun modify(original: String): String = "modified-$original"
    }

    @AsmMixin("SliceReturnValueTarget")
    object InferredTargetEmptyInvokeSliceModifyReturnValueMixin {
        @ModifyReturnValue(
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE),
            ),
            require = 1,
        )
        @JvmStatic
        fun value(original: String): String = "modified-$original"
    }

    @AsmMixin("InvokeDynamicSliceReturnValueTarget")
    object ModifyReturnValueInvokeDynamicSliceMixin {
        @ModifyReturnValue(
            method = "value(ILjava/lang/String;)Ljava/lang/String;",
            slice = Slice(
                from = At(
                    value = InjectionPoint.INVOKE,
                    target = "java/lang/invoke/StringConcatFactory.makeConcatWithConstants(Ljava/lang/String;)Ljava/lang/String;",
                ),
                to = At(
                    value = InjectionPoint.INVOKE,
                    target = "java/lang/invoke/StringConcatFactory.makeConcatWithConstants(Ljava/lang/String;)Ljava/lang/String;",
                ),
            ),
            require = 1,
            allow = 1,
        )
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
    object InvokeReplaceTrimMixin {
        @AsmInject(
            method = "call()Ljava/lang/String;",
            target = InjectionPoint.INVOKE,
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.trim()Ljava/lang/String;",
                shift = Shift.REPLACE,
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun replace(): String = "replaced-trim"
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

    @AsmMixin("RedirectTarget")
    object CancellableInvokeBeforeMixin {
        @AsmInject(
            method = "call()Ljava/lang/String;",
            target = InjectionPoint.INVOKE,
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.trim()Ljava/lang/String;",
                shift = Shift.BEFORE,
            ),
            cancellable = true,
        )
        @JvmStatic
        fun inject(callback: CallbackInfo) {
            callback.setReturnValue("invoke-cancelled")
        }
    }

    @AsmMixin("RedirectTarget")
    object CancellableInvokeAssignAfterMixin {
        @AsmInject(
            method = "call()Ljava/lang/String;",
            target = InjectionPoint.INVOKE_ASSIGN,
            at = At(
                value = InjectionPoint.INVOKE_ASSIGN,
                target = "java/lang/String.trim()Ljava/lang/String;",
                shift = Shift.AFTER,
            ),
            cancellable = true,
        )
        @JvmStatic
        fun inject(callback: CallbackInfo) {
            callback.setReturnValue("invoke-assign-cancelled")
        }
    }

    @AsmMixin("InvokeDynamicExpressionValueTarget")
    object InvokeDynamicInjectMixin {
        var injectCount: Int = 0
        var observed: String = ""

        @AsmInject(
            method = "value(Ljava/lang/String;I)Ljava/lang/String;",
            target = InjectionPoint.INVOKE,
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/invoke/StringConcatFactory.makeConcatWithConstants(Ljava/lang/String;I)Ljava/lang/String;",
                shift = Shift.BEFORE,
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun inject(
            dynamicPrefix: String,
            dynamicCount: Int,
            targetPrefix: String,
            targetCount: Int,
        ) {
            injectCount++
            observed = "$dynamicPrefix:$dynamicCount:$targetPrefix:$targetCount"
        }
    }

    @AsmMixin("InvokeDynamicExpressionValueTarget")
    object InvokeDynamicReplaceMixin {
        @AsmInject(
            method = "value(Ljava/lang/String;I)Ljava/lang/String;",
            target = InjectionPoint.INVOKE,
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/invoke/StringConcatFactory.makeConcatWithConstants(Ljava/lang/String;I)Ljava/lang/String;",
                shift = Shift.REPLACE,
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun replace(
            dynamicPrefix: String,
            dynamicCount: Int,
        ): String = "${dynamicPrefix.uppercase()}-${dynamicCount + 1}-injected"
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

    @AsmMixin("ReturnTarget")
    object HeadReturningHandlerOnReturnTargetMixin {
        @AsmInject(method = "value()Ljava/lang/String;", target = InjectionPoint.HEAD)
        @JvmStatic
        fun inject(): Long = 1L
    }

    @AsmMixin("ReturnTarget")
    object TailReturningHandlerOnReturnTargetMixin {
        @AsmInject(method = "value()Ljava/lang/String;", target = InjectionPoint.TAIL)
        @JvmStatic
        fun inject(): Double = 1.0
    }

    @AsmMixin("ReturnTarget")
    object ReturnReturningHandlerOnReturnTargetMixin {
        @AsmInject(method = "value()Ljava/lang/String;", target = InjectionPoint.RETURN)
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

    @AsmMixin("InvokeModifyArgTarget")
    object InvokeBeforeAssignableCallArgumentMixin {
        @AsmInject(
            method = "value()Ljava/lang/String;",
            target = InjectionPoint.INVOKE,
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
                shift = Shift.BEFORE,
            ),
        )
        @JvmStatic
        fun inject(value: CharSequence) {
            if (value != "original") {
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

    @AsmMixin("ArgTarget")
    object InferredModifyArgTargetMixin {
        @ModifyArg(index = 0)
        @JvmStatic
        fun echo(original: String): String = "inferred-$original"
    }

    @AsmMixin("MixedArgTarget")
    object InferredModifyArgIndexMixin {
        @ModifyArg
        @JvmStatic
        fun echo(original: String): String = "inferred-index-$original"
    }

    @AsmMixin("ArgTarget")
    object ModifyArgGenericReturnMixin {
        @ModifyArg(method = "echo(Ljava/lang/String;)Ljava/lang/String;", index = 0)
        @JvmStatic
        fun modify(original: String): Any = "$original-generic"
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

    @AsmMixin("InvokeModifyArgTarget")
    object InferredInvokeModifyArgMixin {
        @ModifyArg(
            method = "value()Ljava/lang/String;",
            index = 0,
            at = At(value = InjectionPoint.INVOKE),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun modify(original: String): String = "inferred"
    }

    @AsmMixin("InvokeModifyArgTarget")
    object InferredInvokeModifyArgIndexMixin {
        @ModifyArg(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.INVOKE),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun modify(original: String): String = "inferred-index"
    }

    @AsmMixin("InvokeModifyArgTarget")
    object InferredMethodAndInvokeModifyArgMixin {
        @ModifyArg(
            index = 0,
            at = At(value = InjectionPoint.INVOKE),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun value(original: String): String = "inferred-method"
    }

    @AsmMixin("InvokeModifyArgTarget")
    object InvokeModifyArgObjectParameterMixin {
        @ModifyArg(
            method = "value()Ljava/lang/String;",
            index = 0,
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun modify(original: Any): String = "$original-any"
    }

    @AsmMixin("InvokeModifyArgTarget")
    object InvokeModifyArgParentParameterMixin {
        @ModifyArg(
            method = "value()Ljava/lang/String;",
            index = 0,
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun modify(original: CharSequence): String = "$original-parent"
    }

    @AsmMixin("ModifyArgsTarget")
    object InvokeModifyArgAssignableReturnMixin {
        @ModifyArg(
            method = "value()Ljava/lang/String;",
            index = 0,
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.replace(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun modify(original: CharSequence): StringBuilder = StringBuilder("raw")
    }

    @AsmMixin("InvokeModifyArgTarget")
    object InvokeModifyArgGenericReturnMixin {
        @ModifyArg(
            method = "value()Ljava/lang/String;",
            index = 0,
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun modify(original: String): Any = "$original-generic"
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

    @AsmMixin("InvokeDynamicExpressionValueTarget")
    object InvokeDynamicModifyArgMixin {
        @ModifyArg(
            method = "value(Ljava/lang/String;I)Ljava/lang/String;",
            index = 0,
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/invoke/StringConcatFactory.makeConcatWithConstants(Ljava/lang/String;I)Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun modify(original: String): String {
            original.length
            return "changed"
        }
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

    @AsmMixin("SliceInvokeModifyArgTarget")
    object EmptyInvokeSliceModifyArgMixin {
        @ModifyArg(
            method = "value()Ljava/lang/String;",
            index = 0,
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
            ),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE),
            ),
            require = 1,
        )
        @JvmStatic
        fun modify(original: String): String = "modified"
    }

    @AsmMixin("SliceInvokeModifyArgTarget")
    object InferredTargetEmptyInvokeSliceModifyArgMixin {
        @ModifyArg(
            index = 0,
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
            ),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE),
            ),
            require = 1,
        )
        @JvmStatic
        fun value(original: String): String = "modified"
    }

    @AsmMixin("InvokeDynamicSliceModifyArgTarget")
    object InvokeDynamicSliceModifyArgMixin {
        @ModifyArg(
            method = "value(Ljava/lang/String;)Ljava/lang/String;",
            index = 0,
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
            ),
            slice = Slice(
                from = At(
                    value = InjectionPoint.INVOKE,
                    target = "java/lang/invoke/StringConcatFactory.makeConcatWithConstants(Ljava/lang/String;)Ljava/lang/String;",
                ),
                to = At(
                    value = InjectionPoint.INVOKE,
                    target = "java/lang/invoke/StringConcatFactory.makeConcatWithConstants(Ljava/lang/String;)Ljava/lang/String;",
                ),
            ),
            require = 1,
            allow = 1,
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

    @AsmMixin("ModifyArgsTarget")
    object ModifyArgsIndexedAccessMixin {
        @ModifyArgs(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.replace(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun modify(args: Args) {
            val needle: String = args[0]
            args[0] = needle.replace("missing", "raw")
            args[1] = "indexed"
        }
    }

    @AsmMixin("ModifyArgsTarget")
    object ModifyArgsIterationMixin {
        @ModifyArgs(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.replace(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun modify(args: Args) {
            check(args.size == 2)
            val values = mutableListOf<String>()
            for (value in args) {
                values += value.toString()
            }
            args[0] = values[0].replace("missing", "raw")
            args[1] = "iterated"
        }
    }

    @AsmMixin("ModifyArgsTarget")
    object ModifyArgsIterableExtensionMixin {
        @ModifyArgs(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.replace(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun modify(args: Args) {
            val joined = args.joinToString(separator = "|")
            args[0] = joined.substringBefore('|').replace("missing", "raw")
            args[1] = "joined"
        }
    }

    @AsmMixin("ModifyArgsTarget")
    object ModifyArgsSetAllMixin {
        @ModifyArgs(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.replace(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun modify(args: Args) {
            args.setAll(args.get<String>(0).replace("missing", "raw"), "bulk")
        }
    }

    @AsmMixin("ModifyArgsTarget")
    object InferredInvokeModifyArgsMixin {
        @ModifyArgs(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.INVOKE),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun modify(args: Args) {
            args.set(0, "raw")
            args.set(1, "inferred")
        }
    }

    @AsmMixin("ModifyArgsTarget")
    object InferredMethodAndInvokeModifyArgsMixin {
        @ModifyArgs(
            at = At(value = InjectionPoint.INVOKE),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun value(args: Args) {
            args.set(0, "raw")
            args.set(1, "inferred-method")
        }
    }

    @AsmMixin("ModifyArgsTarget")
    object InferredModifyArgsTargetMixin {
        @ModifyArgs(
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.replace(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun value(args: Args) {
            args.set(0, "raw")
            args.set(1, "inferred")
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

    @AsmMixin("ModifyArgsParamTarget")
    object ModifyArgsParentTargetParamsMixin {
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
            suffix: CharSequence,
            count: Int,
        ) {
            args.set(0, "${args.get<String>(0)}-$suffix")
            args.set(1, "right")
            args.set(2, count)
        }
    }

    @AsmMixin("ConstructorModifyArgsTarget")
    object ConstructorModifyArgsMixin {
        @ModifyArgs(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.<init>([CII)V",
            ),
        )
        @JvmStatic
        fun modify(args: Args) {
            args.set(1, 1)
            args.set(2, 2)
        }
    }

    @AsmMixin("InvokeDynamicExpressionValueTarget")
    object InvokeDynamicModifyArgsMixin {
        @ModifyArgs(
            method = "value(Ljava/lang/String;I)Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/invoke/StringConcatFactory.makeConcatWithConstants(Ljava/lang/String;I)Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun modify(args: Args) {
            args.set(0, "changed")
            args.set(1, 9)
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

    @AsmMixin("SliceModifyArgsTarget")
    object EmptyInvokeSliceModifyArgsMixin {
        @ModifyArgs(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.replace(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;",
            ),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE),
            ),
            require = 1,
        )
        @JvmStatic
        fun modify(args: Args) {
            args.set(0, "raw")
            args.set(1, "changed")
        }
    }

    @AsmMixin("SliceModifyArgsTarget")
    object InferredTargetEmptyInvokeSliceModifyArgsMixin {
        @ModifyArgs(
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.replace(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;",
            ),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE),
            ),
            require = 1,
        )
        @JvmStatic
        fun value(args: Args) {
            args.set(0, "raw")
            args.set(1, "changed")
        }
    }

    @AsmMixin("InvokeDynamicSliceModifyArgsTarget")
    object ModifyArgsInvokeDynamicSliceMixin {
        @ModifyArgs(
            method = "value(Ljava/lang/String;)Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.replace(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;",
            ),
            slice = Slice(
                from = At(
                    value = InjectionPoint.INVOKE,
                    target = "java/lang/invoke/StringConcatFactory.makeConcatWithConstants(Ljava/lang/String;)Ljava/lang/String;",
                ),
                to = At(
                    value = InjectionPoint.INVOKE,
                    target = "java/lang/invoke/StringConcatFactory.makeConcatWithConstants(Ljava/lang/String;)Ljava/lang/String;",
                ),
            ),
            require = 1,
            allow = 1,
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
    object InferredWrapConditionStaticTargetMixin {
        @WrapWithCondition(
            at = At(
                value = InjectionPoint.INVOKE,
                target = "WrapConditionStaticTarget.record(Ljava/lang/String;)V",
            ),
        )
        @JvmStatic
        fun run(value: String): Boolean {
            value.length
            return false
        }
    }

    @AsmMixin("WrapConditionStaticTarget")
    object NonVoidRedirectForVoidCallMixin {
        @Redirect(
            method = "run()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "WrapConditionStaticTarget.record(Ljava/lang/String;)V",
            ),
        )
        @JvmStatic
        fun redirect(value: String): String = value
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

    @AsmMixin("MixedWrapConditionTarget")
    object WrapConditionInferredInvokeTargetMixin {
        @WrapWithCondition(
            method = "run()Ljava/lang/String;",
            at = At(value = InjectionPoint.INVOKE),
        )
        @JvmStatic
        fun shouldRun(
            target: Any,
            value: String,
        ): Boolean {
            target.hashCode()
            return value != "raw"
        }
    }

    @AsmMixin("WrapConditionStaticTarget")
    object WrapConditionAssignableParentParameterMixin {
        @WrapWithCondition(
            method = "run()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "WrapConditionStaticTarget.record(Ljava/lang/String;)V",
            ),
        )
        @JvmStatic
        fun shouldRun(value: CharSequence): Boolean = value == "raw"
    }

    @AsmMixin("WrapConditionInvokeDynamicTarget")
    object WrapConditionInvokeDynamicMixin {
        @WrapWithCondition(
            method = "run(Ljava/lang/String;I)Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "kim/der/asm/FrameworkReliabilityTest.record(Ljava/lang/String;I)V",
            ),
        )
        @JvmStatic
        fun shouldRun(
            value: String,
            count: Int,
            targetValue: String,
            targetCount: Int,
        ): Boolean = value != "skip" && value == targetValue && count == targetCount
    }

    @AsmMixin("InvokeDynamicExpressionValueTarget")
    object WrapConditionNonVoidInvokeDynamicMixin {
        @WrapWithCondition(
            method = "value(Ljava/lang/String;I)Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/invoke/StringConcatFactory.makeConcatWithConstants(Ljava/lang/String;I)Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun shouldRun(
            value: String,
            count: Int,
        ): Boolean {
            value.length
            count.toString()
            return false
        }
    }

    @AsmMixin("InvokeAssignConditionTarget")
    object WrapConditionInvokeAssignByTargetParamsMixin {
        @WrapWithCondition(
            method = "value(ZLjava/lang/String;)Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE_ASSIGN,
                target = "InvokeAssignConditionTarget.produce(Ljava/lang/String;)Ljava/lang/String;",
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun shouldKeep(
            value: String,
            keep: Boolean,
            prefix: String,
        ): Boolean = keep && value.startsWith(prefix)
    }

    @AsmMixin("InferredInvokeExpressionValueTarget")
    object WrapConditionInvokeAssignInferredStringMixin {
        @WrapWithCondition(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.INVOKE_ASSIGN),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun shouldKeep(value: String): Boolean {
            value.length
            return false
        }
    }

    @AsmMixin("WrapConditionStaticTarget")
    object WrapConditionInvokeAssignVoidCallMixin {
        @WrapWithCondition(
            method = "run()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE_ASSIGN,
                target = "WrapConditionStaticTarget.record(Ljava/lang/String;)V",
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun shouldKeep(value: String): Boolean = value.isNotEmpty()
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

    @AsmMixin("Test")
    object WrapConditionNonVoidTestCallMixin {
        @WrapWithCondition(
            method = "comprehensiveTest()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "Test.testB0()Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun shouldRun(): Boolean = false
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

    @AsmMixin("SliceWrapConditionTarget")
    object EmptyInvokeSliceWrapWithConditionMixin {
        @WrapWithCondition(
            method = "run()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "SliceWrapConditionTarget.record(Ljava/lang/String;)V",
            ),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE),
            ),
            require = 1,
        )
        @JvmStatic
        fun shouldRun(value: String): Boolean {
            value.length
            return false
        }
    }

    @AsmMixin("SliceWrapConditionTarget")
    object InferredTargetEmptyInvokeSliceWrapWithConditionMixin {
        @WrapWithCondition(
            at = At(
                value = InjectionPoint.INVOKE,
                target = "SliceWrapConditionTarget.record(Ljava/lang/String;)V",
            ),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE),
            ),
            require = 1,
        )
        @JvmStatic
        fun run(value: String): Boolean {
            value.length
            return false
        }
    }

    @AsmMixin("SliceWrapConditionFieldTarget")
    object WrapConditionFieldAssignSliceMixin {
        @WrapWithCondition(
            method = "writeSelected()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.FIELD_ASSIGN,
                target = "SliceWrapConditionFieldTarget.name:Ljava/lang/String;",
            ),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
                to = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
            ),
            require = 1,
            allow = 1,
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

    @AsmMixin("SliceWrapConditionArrayTarget")
    object WrapConditionArrayWriteSliceMixin {
        @WrapWithCondition(
            method = "writeSelected()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.FIELD_ASSIGN,
                target = "SliceWrapConditionArrayTarget.names:[Ljava/lang/String;",
                args = ["array=set"],
            ),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
                to = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
            ),
            require = 1,
            allow = 1,
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

    @AsmMixin("SliceLoadVariableTarget")
    object WrapConditionLoadSliceMixin {
        @WrapWithCondition(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.LOAD, args = ["index=1"]),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
                to = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun shouldLoad(value: String): Boolean {
            value.length
            return false
        }
    }

    @AsmMixin("ConstantParamTarget")
    object WrapConditionConstantDenyMixin {
        @WrapWithCondition(
            method = "value(Ljava/lang/String;I)Ljava/lang/String;",
            at = At(value = InjectionPoint.CONSTANT, target = "base-"),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun shouldKeep(value: String): Boolean = false
    }

    @AsmMixin("ConstantParamTarget")
    object WrapConditionConstantAllowMixin {
        @WrapWithCondition(
            method = "value(Ljava/lang/String;I)Ljava/lang/String;",
            at = At(value = InjectionPoint.CONSTANT, target = "base-"),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun shouldKeep(value: String): Boolean = true
    }

    @AsmMixin("TrueBooleanConstantTarget")
    object WrapConditionConstantBooleanDenyMixin {
        @WrapWithCondition(
            method = "value()Z",
            at = At(value = InjectionPoint.CONSTANT),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun shouldKeep(value: Boolean): Boolean = !value
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

    @AsmMixin("NewInstructionTarget")
    object WrapConditionConstructorCallMixin {
        @WrapWithCondition(
            method = "create()Ljava/lang/StringBuilder;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/StringBuilder.<init>()V",
            ),
        )
        @JvmStatic
        fun shouldRun(target: StringBuilder): Boolean {
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
    object WrapConditionFieldReadDenyMixin {
        @WrapWithCondition(
            method = "readName()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.FIELD,
                target = "FieldPointTarget.name:Ljava/lang/String;",
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun shouldRead(value: String): Boolean {
            value.length
            return false
        }
    }

    @AsmMixin("FieldPointTarget")
    object WrapConditionFieldReadAllowMixin {
        @WrapWithCondition(
            method = "readName()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.FIELD,
                target = "FieldPointTarget.name:Ljava/lang/String;",
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun shouldRead(value: String): Boolean = value == "allowed"
    }

    @AsmMixin("PrimitiveFieldPointTarget")
    object WrapConditionPrimitiveFieldReadDenyMixin {
        @WrapWithCondition(
            method = "readScore()I",
            at = At(
                value = InjectionPoint.FIELD,
                target = "PrimitiveFieldPointTarget.score:I",
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun shouldRead(value: Int): Boolean = value < 0
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

    @AsmMixin("Test")
    object WrapConditionInferredTestFieldAssignMixin {
        @WrapWithCondition(
            method = "<init>(Ljava/lang/String;)V",
            at = At(value = InjectionPoint.FIELD_ASSIGN),
            require = 2,
            allow = 2,
        )
        @JvmStatic
        fun shouldWrite(
            target: Any,
            value: String,
        ): Boolean {
            target.hashCode()
            return value == "DynamicString"
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

    @AsmMixin("ArrayAccessTarget")
    object WrapConditionArrayReadDenyMixin {
        @WrapWithCondition(
            method = "readName(I)Ljava/lang/String;",
            at = At(
                value = InjectionPoint.FIELD,
                target = "ArrayAccessTarget.names:[Ljava/lang/String;",
                args = ["array=get"],
            ),
        )
        @JvmStatic
        fun shouldRead(value: String): Boolean {
            value.length
            return false
        }
    }

    @AsmMixin("ArrayAccessTarget")
    object WrapConditionArrayReadAllowMixin {
        @WrapWithCondition(
            method = "readName(I)Ljava/lang/String;",
            at = At(
                value = InjectionPoint.FIELD,
                target = "ArrayAccessTarget.names:[Ljava/lang/String;",
                args = ["array=get"],
            ),
        )
        @JvmStatic
        fun shouldRead(value: String): Boolean = value == "raw"
    }

    @AsmMixin("PrimitiveArrayAccessTarget")
    object WrapConditionPrimitiveArrayReadDenyMixin {
        @WrapWithCondition(
            method = "readScore(I)I",
            at = At(
                value = InjectionPoint.FIELD,
                target = "PrimitiveArrayAccessTarget.scores:[I",
                args = ["array=get"],
            ),
        )
        @JvmStatic
        fun shouldRead(value: Int): Boolean {
            value.toString()
            return false
        }
    }

    @AsmMixin("ArrayAccessTarget")
    object WrapConditionArrayLengthDenyMixin {
        @WrapWithCondition(
            method = "nameCount()I",
            at = At(
                value = InjectionPoint.FIELD,
                target = "ArrayAccessTarget.names:[Ljava/lang/String;",
                args = ["array=length"],
            ),
        )
        @JvmStatic
        fun shouldRead(length: Int): Boolean {
            length.toString()
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

    @AsmMixin("ConditionalStoreTarget")
    object WrapConditionStoreDenyMixin {
        @WrapWithCondition(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.STORE, args = ["index=1"]),
            ordinal = 1,
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun shouldStore(value: String): Boolean {
            value.length
            return false
        }
    }

    @AsmMixin("NamedConditionalStoreTarget")
    object WrapConditionStoreNameMixin {
        @WrapWithCondition(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.STORE, args = ["name=target"]),
            ordinal = 1,
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun shouldStore(value: String): Boolean {
            value.length
            return false
        }
    }

    @AsmMixin("ConditionalStoreParamTarget")
    object WrapConditionStoreWithTargetParamsMixin {
        @WrapWithCondition(
            method = "value(Ljava/lang/String;)Ljava/lang/String;",
            at = At(value = InjectionPoint.STORE, args = ["index=2"]),
            ordinal = 1,
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun shouldStore(
            value: String,
            flag: String,
        ): Boolean = value == "target" && flag == "allow"
    }

    @AsmMixin("Test")
    object WrapConditionLoadDenyMixin {
        @WrapWithCondition(
            method = "localNameDiscriminatorTest(Ljava/lang/String;)Ljava/lang/String;",
            at = At(value = InjectionPoint.LOAD, args = ["name=first"]),
            ordinal = 0,
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun shouldLoad(value: String): Boolean {
            value.length
            return false
        }
    }

    @AsmMixin("ReusedLoadSlotTarget")
    object WrapConditionLoadReusedSlotMixin {
        @WrapWithCondition(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.LOAD, args = ["index=1"]),
            ordinal = 1,
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun shouldLoad(value: Any): Boolean {
            value.hashCode()
            return false
        }
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

    @AsmMixin("JumpOperationTarget")
    object WrapConditionJumpMixin {
        @WrapWithCondition(
            method = "choose(IZ)Ljava/lang/String;",
            at = At(value = InjectionPoint.JUMP, target = "IFLE"),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun shouldJump(
            original: Boolean,
            value: Int,
            allowNegative: Boolean,
        ): Boolean {
            original.toString()
            value.hashCode()
            return allowNegative
        }
    }

    @AsmMixin("JumpOperationTarget")
    object WrapConditionGotoMixin {
        @WrapWithCondition(
            method = "choose(IZ)Ljava/lang/String;",
            at = At(value = InjectionPoint.JUMP, target = "GOTO"),
        )
        @JvmStatic
        fun shouldJump(original: Boolean): Boolean = original
    }

    @AsmMixin("ConditionalThrowTarget")
    object WrapConditionThrowMixin {
        @WrapWithCondition(
            method = "choose(ZZ)Ljava/lang/String;",
            at = At(value = InjectionPoint.THROW, target = "java/lang/IllegalStateException"),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun shouldThrow(
            original: Throwable,
            skipOriginalThrow: Boolean,
            allowThrow: Boolean,
        ): Boolean {
            original.message.hashCode()
            skipOriginalThrow.toString()
            return allowThrow
        }
    }

    @AsmMixin("InstanceofTarget")
    object WrapConditionInstanceofAllowMixin {
        @WrapWithCondition(
            method = "isString(Ljava/lang/Object;Z)Z",
            at = At(value = InjectionPoint.INSTANCEOF, target = "java.lang.String"),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun shouldKeep(original: Boolean): Boolean {
            original.toString()
            return true
        }
    }

    @AsmMixin("InstanceofTarget")
    object WrapConditionInstanceofDenyMixin {
        @WrapWithCondition(
            method = "isString(Ljava/lang/Object;Z)Z",
            at = At(value = InjectionPoint.INSTANCEOF, target = "java/lang/String"),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun shouldKeep(original: Boolean): Boolean {
            original.toString()
            return false
        }
    }

    @AsmMixin("MultiInstanceofTarget")
    object WrapConditionAnyInstanceofDenyMixin {
        @WrapWithCondition(
            method = "isString(Ljava/lang/Object;Ljava/lang/Object;)Z",
            at = At(value = InjectionPoint.INSTANCEOF),
            require = 2,
            allow = 2,
        )
        @JvmStatic
        fun shouldKeep(
            original: Boolean,
            ignored: Any,
            raw: Any,
        ): Boolean {
            original.toString()
            ignored.hashCode()
            raw.hashCode()
            return false
        }
    }

    @AsmMixin("MultiInstanceofTarget")
    object WrapConditionTargetedStringInstanceofDenyMixin {
        @WrapWithCondition(
            method = "isString(Ljava/lang/Object;Ljava/lang/Object;)Z",
            at = At(value = InjectionPoint.INSTANCEOF, target = "java.lang.String"),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun shouldKeep(original: Boolean): Boolean {
            original.toString()
            return false
        }
    }

    @AsmMixin("InstanceofTarget")
    object WrapConditionInstanceofTargetParamsMixin {
        @WrapWithCondition(
            method = "isString(Ljava/lang/Object;Z)Z",
            at = At(value = InjectionPoint.INSTANCEOF, target = "java/lang/String"),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun shouldKeep(
            original: Boolean,
            value: Any,
            force: Boolean,
        ): Boolean = original && value is String && force
    }

    @AsmMixin("SliceInstanceofExpressionValueTarget")
    object WrapConditionInstanceofSliceDenyMixin {
        @WrapWithCondition(
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
        fun shouldKeep(original: Boolean): Boolean {
            original.toString()
            return false
        }
    }

    @AsmMixin("InstanceofTarget")
    object MismatchedWrapConditionInstanceofMixin {
        @WrapWithCondition(
            method = "isString(Ljava/lang/Object;Z)Z",
            at = At(value = InjectionPoint.INSTANCEOF, target = "java/lang/String"),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun shouldKeep(original: String): Boolean = original.isNotEmpty()
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

    @AsmMixin("InferredInvokeExpressionValueTarget")
    object ModifyExpressionValueInferredInvokeTargetMixin {
        @ModifyExpressionValue(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.INVOKE),
        )
        @JvmStatic
        fun modify(original: String): String = "$original-inferred"
    }

    @AsmMixin("ExpressionValueTarget")
    object InferredModifyExpressionValueTargetMixin {
        @ModifyExpressionValue(
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.trim()Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun value(original: String): String = "$original-inferred"
    }

    @AsmMixin("ExpressionValueTarget")
    object ModifyExpressionValueObjectParamMixin {
        @ModifyExpressionValue(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.trim()Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun modify(original: Any): String = "$original-object"
    }

    @AsmMixin("ExpressionValueTarget")
    object ModifyExpressionValueParentParamMixin {
        @ModifyExpressionValue(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.trim()Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun modify(original: CharSequence): String = "$original-parent"
    }

    @AsmMixin("CharSequenceExpressionValueTarget")
    object ModifyExpressionValueAssignableReturnMixin {
        @ModifyExpressionValue(
            method = "value()Ljava/lang/CharSequence;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "CharSequenceExpressionValueTarget.sequence()Ljava/lang/CharSequence;",
            ),
        )
        @JvmStatic
        fun modify(original: CharSequence): StringBuilder = StringBuilder("$original-builder")
    }

    @AsmMixin("ExpressionValueTarget")
    object ModifyExpressionValueGenericReturnMixin {
        @ModifyExpressionValue(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.trim()Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun modify(original: String): Any = "$original-generic"
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

    @AsmMixin("LoadExpressionValueTarget")
    object ModifyExpressionValueLoadMixin {
        @ModifyExpressionValue(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.LOAD, args = ["index=1"]),
            ordinal = 0,
        )
        @JvmStatic
        fun modify(original: String): String = "expr-$original"
    }

    @AsmMixin("StoreExpressionValueTarget")
    object ModifyExpressionValueStoreMixin {
        @ModifyExpressionValue(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.STORE, args = ["index=1"]),
            ordinal = 0,
        )
        @JvmStatic
        fun modify(original: String): String = "store-$original"
    }

    @AsmMixin("NamedLoadVariableTarget")
    object ModifyExpressionValueLoadNameMixin {
        @ModifyExpressionValue(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.LOAD, args = ["name=target"]),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun modify(original: String): String = "expr-$original"
    }

    @AsmMixin("NamedStoreVariableTarget")
    object ModifyExpressionValueStoreNameMixin {
        @ModifyExpressionValue(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.STORE, args = ["name=target"]),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun modify(original: String): String = "store-$original"
    }

    @AsmMixin("LoadExpressionValueTarget")
    object RedirectLoadMixin {
        @Redirect(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.LOAD, args = ["index=1"]),
            ordinal = 0,
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun redirect(original: String): String = "redirect-$original"
    }

    @AsmMixin("StoreExpressionValueTarget")
    object RedirectStoreMixin {
        @Redirect(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.STORE, args = ["index=1"]),
            ordinal = 0,
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun redirect(original: String): String = "redirect-store-$original"
    }

    @AsmMixin("NamedLoadVariableTarget")
    object RedirectLoadNameMixin {
        @Redirect(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.LOAD, args = ["name=target"]),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun redirect(original: String): String = "redirect-$original"
    }

    @AsmMixin("NamedStoreVariableTarget")
    object RedirectStoreNameMixin {
        @Redirect(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.STORE, args = ["name=target"]),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun redirect(original: String): String = "redirect-store-$original"
    }

    @AsmMixin("InvokeDynamicExpressionValueTarget")
    object ModifyExpressionValueInvokeDynamicMixin {
        @ModifyExpressionValue(
            method = "value(Ljava/lang/String;I)Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/invoke/StringConcatFactory.makeConcatWithConstants(Ljava/lang/String;I)Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun modify(
            original: String,
            prefix: String,
            count: Int,
        ): String = "$original-dynamic-$prefix$count"
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

    @AsmMixin("InvokeDynamicSliceExpressionValueTarget")
    object ModifyExpressionValueInvokeDynamicSliceMixin {
        @ModifyExpressionValue(
            method = "value(Ljava/lang/String;)Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.trim()Ljava/lang/String;",
            ),
            slice = Slice(
                from = At(
                    value = InjectionPoint.INVOKE,
                    target = "java/lang/invoke/StringConcatFactory.makeConcatWithConstants(Ljava/lang/String;)Ljava/lang/String;",
                ),
                to = At(
                    value = InjectionPoint.INVOKE,
                    target = "java/lang/invoke/StringConcatFactory.makeConcatWithConstants(Ljava/lang/String;)Ljava/lang/String;",
                ),
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun modify(original: String): String = "$original-changed"
    }

    @AsmMixin("SliceExpressionValueTarget")
    object ModifyExpressionValueEmptyInvokeSliceBoundaryMixin {
        @ModifyExpressionValue(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.trim()Ljava/lang/String;",
            ),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE),
            ),
            require = 1,
        )
        @JvmStatic
        fun modify(original: String): String = "$original-changed"
    }

    @AsmMixin("SliceExpressionValueTarget")
    object InferredTargetEmptyInvokeSliceModifyExpressionValueMixin {
        @ModifyExpressionValue(
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.trim()Ljava/lang/String;",
            ),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE),
            ),
            require = 1,
        )
        @JvmStatic
        fun value(original: String): String = "$original-changed"
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

    @AsmMixin("SliceFieldAssignTarget")
    object ModifyExpressionValueFieldAssignSliceMixin {
        @ModifyExpressionValue(
            method = "writeSelected(Ljava/lang/String;Ljava/lang/String;)V",
            at = At(value = InjectionPoint.FIELD_ASSIGN, target = "SliceFieldAssignTarget.name:Ljava/lang/String;"),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
                to = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun modify(original: String): String = "$original-field-assign-slice"
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

    @AsmMixin("MixedFieldExpressionValueTarget")
    object ModifyExpressionValueInferredFieldReadMixin {
        @ModifyExpressionValue(
            method = "readSelected()Ljava/lang/String;",
            at = At(value = InjectionPoint.FIELD),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun modify(original: String): String = "$original-inferred-field"
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

    @AsmMixin("FieldPointTarget")
    object ModifyExpressionValueFieldAssignMixin {
        @ModifyExpressionValue(
            method = "writeName(Ljava/lang/String;)V",
            at = At(value = InjectionPoint.FIELD_ASSIGN, target = "FieldPointTarget.name:Ljava/lang/String;"),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun modify(original: String): String = "$original-assigned"
    }

    @AsmMixin("StaticFieldPointTarget")
    object ModifyExpressionValueStaticFieldAssignMixin {
        @ModifyExpressionValue(
            method = "writeName(Ljava/lang/String;)V",
            at = At(value = InjectionPoint.FIELD_ASSIGN, target = "StaticFieldPointTarget.name:Ljava/lang/String;"),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun modify(original: String): String = "$original-static-assigned"
    }

    @AsmMixin("PrimitiveFieldPointTarget")
    object ModifyExpressionValuePrimitiveFieldAssignMixin {
        @ModifyExpressionValue(
            method = "writeScore(I)V",
            at = At(value = InjectionPoint.FIELD_ASSIGN, target = "PrimitiveFieldPointTarget.score:I"),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun modify(original: Int): Int = original + 3
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

    @AsmMixin("ArrayAccessTarget")
    object ModifyExpressionValueArrayWriteMixin {
        @ModifyExpressionValue(
            method = "writeName(ILjava/lang/String;)V",
            at = At(
                value = InjectionPoint.FIELD_ASSIGN,
                target = "ArrayAccessTarget.names:[Ljava/lang/String;",
                args = ["array=set"],
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun modify(original: String): String = "$original-array-write"
    }

    @AsmMixin("PrimitiveArrayAccessTarget")
    object ModifyExpressionValuePrimitiveArrayWriteMixin {
        @ModifyExpressionValue(
            method = "writeScore(II)V",
            at = At(
                value = InjectionPoint.FIELD_ASSIGN,
                target = "PrimitiveArrayAccessTarget.scores:[I",
                args = ["array=set"],
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun modify(original: Int): Int = original + 3
    }

    @AsmMixin("ArrayParamTarget")
    object ModifyExpressionValueArrayWriteWithTargetParamsMixin {
        @ModifyExpressionValue(
            method = "writeName(ILjava/lang/String;Ljava/lang/String;)V",
            at = At(
                value = InjectionPoint.FIELD_ASSIGN,
                target = "ArrayParamTarget.names:[Ljava/lang/String;",
                args = ["array=set"],
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun modify(
            original: String,
            index: Int,
            value: String,
            suffix: String,
        ): String {
            assertEquals(0, index)
            assertEquals(original, value)
            return "$original-$suffix"
        }
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

    @AsmMixin("MixedNewExpressionValueTarget")
    object ModifyExpressionValueNewInferredTargetMixin {
        @ModifyExpressionValue(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.NEW),
        )
        @JvmStatic
        fun modify(original: StringBuilder): StringBuilder {
            original.length
            return StringBuilder("changed")
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

    @AsmMixin("NewInstructionTarget")
    object WrapConditionNewAllowMixin {
        @WrapWithCondition(
            method = "create()Ljava/lang/StringBuilder;",
            at = At(value = InjectionPoint.NEW, target = "java/lang/StringBuilder"),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun shouldKeep(original: StringBuilder): Boolean {
            original.append("kept")
            return true
        }
    }

    @AsmMixin("NewInstructionTarget")
    object WrapConditionNewDenyMixin {
        @WrapWithCondition(
            method = "create()Ljava/lang/StringBuilder;",
            at = At(value = InjectionPoint.NEW, target = "java/lang/StringBuilder"),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun shouldKeep(original: StringBuilder): Boolean {
            original.append("discarded")
            return false
        }
    }

    @AsmMixin("NewParamTarget")
    object WrapConditionNewTargetParamsMixin {
        @WrapWithCondition(
            method = "create(Ljava/lang/String;I)Ljava/lang/StringBuilder;",
            at = At(value = InjectionPoint.NEW, target = "java/lang/StringBuilder"),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun shouldKeep(
            original: StringBuilder,
            prefix: String,
            count: Int,
        ): Boolean {
            original.append(prefix).append('-').append(count)
            return prefix.length == count
        }
    }

    @AsmMixin("MixedNewExpressionValueTarget")
    object WrapConditionNewInferredAllowMixin {
        @WrapWithCondition(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.NEW),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun shouldKeep(original: StringBuilder): Boolean {
            original.append("-kept")
            return true
        }
    }

    @AsmMixin("SliceNewExpressionValueTarget")
    object WrapConditionNewSliceDenyMixin {
        @WrapWithCondition(
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
        fun shouldKeep(original: StringBuilder): Boolean {
            original.append("-discarded")
            return false
        }
    }

    @AsmMixin("NewInstructionTarget")
    object MismatchedWrapConditionNewMixin {
        @WrapWithCondition(
            method = "create()Ljava/lang/StringBuilder;",
            at = At(value = InjectionPoint.NEW, target = "java/lang/StringBuilder"),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun shouldKeep(original: String): Boolean = original.isNotEmpty()
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

    @AsmMixin("MultiCastInstructionTarget")
    object AnyCastModifyExpressionValueMixin {
        @ModifyExpressionValue(
            method = "cast(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/String;",
            at = At(value = InjectionPoint.CAST),
        )
        @JvmStatic
        fun modify(original: String): String = "$original-modified"
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

    @AsmMixin("CastInstructionTarget")
    object WrapConditionCastAllowMixin {
        @WrapWithCondition(
            method = "cast(Ljava/lang/Object;)Ljava/lang/String;",
            at = At(value = InjectionPoint.CAST, target = "java/lang/String"),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun shouldKeep(
            original: String,
            input: Any,
        ): Boolean = original === input
    }

    @AsmMixin("CastInstructionTarget")
    object WrapConditionCastDenyMixin {
        @WrapWithCondition(
            method = "cast(Ljava/lang/Object;)Ljava/lang/String;",
            at = At(value = InjectionPoint.CAST, target = "java/lang/String"),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun shouldKeep(original: String): Boolean {
            original.length
            return false
        }
    }

    @AsmMixin("MultiCastInstructionTarget")
    object WrapConditionAnyCastDenyMixin {
        @WrapWithCondition(
            method = "cast(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/String;",
            at = At(value = InjectionPoint.CAST),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun shouldKeep(original: String): Boolean {
            original.length
            return false
        }
    }

    @AsmMixin("SliceCastInstructionTarget")
    object WrapConditionCastSliceDenyMixin {
        @WrapWithCondition(
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
        fun shouldKeep(original: String): Boolean {
            original.length
            return false
        }
    }

    @AsmMixin("CastInstructionTarget")
    object MismatchedWrapConditionCastMixin {
        @WrapWithCondition(
            method = "cast(Ljava/lang/Object;)Ljava/lang/String;",
            at = At(value = InjectionPoint.CAST, target = "java/lang/String"),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun shouldKeep(original: Int): Boolean = original > 0
    }

    @AsmMixin("CastInstructionTarget")
    object CastRedirectMixin {
        @Redirect(
            method = "cast(Ljava/lang/Object;)Ljava/lang/String;",
            at = At(value = InjectionPoint.CAST, target = "java/lang/String"),
        )
        @JvmStatic
        fun redirect(
            value: Any,
            input: Any,
        ): String = "redirect-$value-${value === input}"
    }

    @AsmMixin("MultiCastInstructionTarget")
    object AnyCastRedirectMixin {
        @Redirect(
            method = "cast(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/String;",
            at = At(value = InjectionPoint.CAST),
        )
        @JvmStatic
        fun redirect(
            value: Any,
            ignored: Any,
            raw: Any,
        ): String = "any-$value"
    }

    @AsmMixin("CastInstructionTarget")
    object WrapOperationCastMixin {
        @WrapOperation(
            method = "cast(Ljava/lang/Object;)Ljava/lang/String;",
            at = At(value = InjectionPoint.CAST, target = "java/lang/String"),
        )
        @JvmStatic
        fun wrap(
            value: Any,
            operation: Operation<String>,
            input: Any,
        ): String = "wrapped-${operation.call(value.toString())}-${value === input}"
    }

    @AsmMixin("MultiCastInstructionTarget")
    object AnyCastWrapOperationMixin {
        @WrapOperation(
            method = "cast(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/String;",
            at = At(value = InjectionPoint.CAST),
        )
        @JvmStatic
        fun wrap(
            value: Any,
            operation: Operation<String>,
            ignored: Any,
            raw: Any,
        ): String {
            ignored.hashCode()
            return "wrapped-${operation.call(value)}-${value === raw}"
        }
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

    @AsmMixin("MixedConstantTarget")
    object ModifyExpressionValueConstantMixin {
        @ModifyExpressionValue(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.CONSTANT, target = "original"),
        )
        @JvmStatic
        fun modify(original: String): String = "expression-$original"
    }

    @AsmMixin("MixedConstantTarget")
    object ModifyExpressionValueInferredConstantMixin {
        @ModifyExpressionValue(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.CONSTANT),
        )
        @JvmStatic
        fun modify(original: String): String = "inferred-$original"
    }

    @AsmMixin("MixedConstantTarget")
    object RedirectConstantMixin {
        @Redirect(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.CONSTANT, target = "original"),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun redirect(original: String): String = "redirect-$original"
    }

    @AsmMixin("MixedConstantTarget")
    object RedirectInferredConstantMixin {
        @Redirect(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.CONSTANT),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun redirect(original: String): String = "redirect-inferred-$original"
    }

    @AsmMixin("Test")
    object ModifyExpressionValueJumpMixin {
        @ModifyExpressionValue(
            method = "recursiveMethod(I)I",
            at = At(value = InjectionPoint.JUMP, target = "IF_ICMPGT"),
        )
        @JvmStatic
        fun modify(
            original: Boolean,
            n: Int,
        ): Boolean {
            n.hashCode()
            return false && original
        }
    }

    @AsmMixin("SwitchSelectorTarget")
    object ModifyExpressionValueSwitchMixin {
        @ModifyExpressionValue(
            method = "choose(IZ)Ljava/lang/String;",
            at = At(value = InjectionPoint.SWITCH),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun modify(
            original: Int,
            value: Int,
            forceTwo: Boolean,
        ): Int {
            value.hashCode()
            return if (forceTwo) 2 else original + 1
        }
    }

    @AsmMixin("LookupSwitchSelectorTarget")
    object ModifyExpressionValueLookupSwitchMixin {
        @ModifyExpressionValue(
            method = "choose(IZ)Ljava/lang/String;",
            at = At(value = InjectionPoint.SWITCH),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun modify(
            original: Int,
            value: Int,
            forceThirty: Boolean,
        ): Int {
            value.hashCode()
            return if (forceThirty) 30 else original + 10
        }
    }

    @AsmMixin("SwitchSelectorTarget")
    object WrapOperationSwitchMixin {
        @WrapOperation(
            method = "choose(IZ)Ljava/lang/String;",
            at = At(value = InjectionPoint.SWITCH),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun wrap(
            selector: Int,
            operation: Operation<Int>,
            value: Int,
            forceTwo: Boolean,
        ): Int {
            value.hashCode()
            val original = operation.call(selector)
            return if (forceTwo) 2 else original + 1
        }
    }

    @AsmMixin("LookupSwitchSelectorTarget")
    object WrapOperationLookupSwitchMixin {
        @WrapOperation(
            method = "choose(IZ)Ljava/lang/String;",
            at = At(value = InjectionPoint.SWITCH),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun wrap(
            selector: Int,
            operation: Operation<Int>,
            value: Int,
            forceThirty: Boolean,
        ): Int {
            value.hashCode()
            val original = operation.call(selector)
            return if (forceThirty) 30 else original + 10
        }
    }

    @AsmMixin("SwitchSelectorTarget")
    object RedirectSwitchMixin {
        @Redirect(
            method = "choose(IZ)Ljava/lang/String;",
            at = At(value = InjectionPoint.SWITCH),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun redirect(
            selector: Int,
            value: Int,
            forceTwo: Boolean,
        ): Int {
            value.hashCode()
            return if (forceTwo) 2 else selector + 1
        }
    }

    @AsmMixin("LookupSwitchSelectorTarget")
    object RedirectLookupSwitchMixin {
        @Redirect(
            method = "choose(IZ)Ljava/lang/String;",
            at = At(value = InjectionPoint.SWITCH),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun redirect(
            selector: Int,
            value: Int,
            forceThirty: Boolean,
        ): Int {
            value.hashCode()
            return if (forceThirty) 30 else selector + 10
        }
    }

    @AsmMixin("SwitchSelectorTarget")
    object WrapConditionSwitchDenyMixin {
        @WrapWithCondition(
            method = "choose(IZ)Ljava/lang/String;",
            at = At(value = InjectionPoint.SWITCH),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun shouldKeep(
            selector: Int,
            value: Int,
            forceDefault: Boolean,
        ): Boolean {
            selector.hashCode()
            value.hashCode()
            return !forceDefault
        }
    }

    @AsmMixin("LookupSwitchSelectorTarget")
    object WrapConditionLookupSwitchDenyMixin {
        @WrapWithCondition(
            method = "choose(IZ)Ljava/lang/String;",
            at = At(value = InjectionPoint.SWITCH),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun shouldKeep(
            selector: Int,
            value: Int,
            forceDefault: Boolean,
        ): Boolean {
            selector.hashCode()
            value.hashCode()
            return !forceDefault
        }
    }

    @AsmMixin("SliceSwitchSelectorTarget")
    object WrapConditionSwitchSliceDenyMixin {
        @WrapWithCondition(
            method = "choose(IZ)Ljava/lang/String;",
            at = At(value = InjectionPoint.SWITCH),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
                to = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun shouldKeep(
            selector: Int,
            value: Int,
            forceDefault: Boolean,
        ): Boolean {
            selector.hashCode()
            value.hashCode()
            return !forceDefault
        }
    }

    @AsmMixin("SwitchSelectorTarget")
    object TargetedWrapConditionSwitchMixin {
        @WrapWithCondition(
            method = "choose(IZ)Ljava/lang/String;",
            at = At(value = InjectionPoint.SWITCH, target = "0"),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun shouldKeep(selector: Int): Boolean = selector >= 0
    }

    @AsmMixin("InstanceofTarget")
    object InstanceofRedirectMixin {
        @Redirect(
            method = "isString(Ljava/lang/Object;Z)Z",
            at = At(value = InjectionPoint.INSTANCEOF, target = "java/lang/String"),
        )
        @JvmStatic
        fun redirect(
            value: Any,
            original: Any,
            force: Boolean,
        ): Boolean {
            original.hashCode()
            return value is Number || force
        }
    }

    @AsmMixin("MultiInstanceofTarget")
    object AnyInstanceofRedirectMixin {
        @Redirect(
            method = "isString(Ljava/lang/Object;Ljava/lang/Object;)Z",
            at = At(value = InjectionPoint.INSTANCEOF),
        )
        @JvmStatic
        fun redirect(
            value: Any,
            ignored: Any,
            raw: Any,
        ): Boolean {
            ignored.hashCode()
            raw.hashCode()
            return value is StringBuilder
        }
    }

    @AsmMixin("JumpOperationTarget")
    object JumpRedirectMixin {
        @Redirect(
            method = "choose(IZ)Ljava/lang/String;",
            at = At(value = InjectionPoint.JUMP, target = "IFLE"),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun redirect(
            original: Boolean,
            value: Int,
            forceNegative: Boolean,
        ): Boolean {
            original.hashCode()
            value.hashCode()
            return forceNegative
        }
    }

    @AsmMixin("JumpOperationTarget")
    object UntargetedJumpRedirectMixin {
        @Redirect(
            method = "choose(IZ)Ljava/lang/String;",
            at = At(value = InjectionPoint.JUMP),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun redirect(
            original: Boolean,
            value: Int,
            forceNegative: Boolean,
        ): Boolean {
            value.hashCode()
            forceNegative.hashCode()
            return !original
        }
    }

    @AsmMixin("JumpOperationTarget")
    object ObjectInstanceJumpRedirectMixin {
        @Redirect(
            method = "choose(IZ)Ljava/lang/String;",
            at = At(value = InjectionPoint.JUMP, target = "IFLE"),
            require = 1,
            allow = 1,
        )
        fun redirect(
            original: Boolean,
            value: Int,
            forceNegative: Boolean,
        ): Boolean = (!original && value > 0) || forceNegative
    }

    @AsmMixin("InstanceofTarget")
    object WrapOperationInstanceofMixin {
        @WrapOperation(
            method = "isString(Ljava/lang/Object;Z)Z",
            at = At(value = InjectionPoint.INSTANCEOF, target = "java/lang/String"),
        )
        @JvmStatic
        fun wrap(
            value: Any,
            operation: Operation<Boolean>,
            original: Any,
            force: Boolean,
        ): Boolean = operation.call(value.toString()) && value === original && !force
    }

    @AsmMixin("MultiInstanceofTarget")
    object AnyInstanceofWrapOperationMixin {
        @WrapOperation(
            method = "isString(Ljava/lang/Object;Ljava/lang/Object;)Z",
            at = At(value = InjectionPoint.INSTANCEOF),
        )
        @JvmStatic
        fun wrap(
            value: Any,
            operation: Operation<Boolean>,
            ignored: Any,
            raw: Any,
        ): Boolean {
            ignored.hashCode()
            return !operation.call(value)
        }
    }

    @AsmMixin("MixedConstantTarget")
    object WrapOperationConstantMixin {
        @WrapOperation(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.CONSTANT, target = "original"),
        )
        @JvmStatic
        fun wrap(
            value: String,
            operation: Operation<String>,
        ): String = "wrapped-$value-${operation.call()}"
    }

    @AsmMixin("MixedConstantTarget")
    object WrapOperationInferredConstantMixin {
        @WrapOperation(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.CONSTANT),
        )
        @JvmStatic
        fun wrap(
            value: String,
            operation: Operation<String>,
        ): String = "inferred-${operation.call()}"
    }

    @AsmMixin("JumpOperationTarget")
    object WrapOperationJumpMixin {
        @WrapOperation(
            method = "choose(IZ)Ljava/lang/String;",
            at = At(value = InjectionPoint.JUMP, target = "IFLE"),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun wrap(
            original: Boolean,
            operation: Operation<Boolean>,
            value: Int,
            forceNegative: Boolean,
        ): Boolean {
            value.hashCode()
            return forceNegative || operation.call(original)
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

    @AsmMixin("ModifyReceiverTarget")
    object InferredModifyReceiverTargetMixin {
        @ModifyReceiver(
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun value(original: String): String {
            original.length
            return "inferred"
        }
    }

    @AsmMixin("MixedModifyReceiverTarget")
    object ModifyReceiverInferredInvokeTargetMixin {
        @ModifyReceiver(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.INVOKE),
        )
        @JvmStatic
        fun modify(original: String): String {
            original.length
            return "changed"
        }
    }

    @AsmMixin("MixedModifyReceiverTarget")
    object ModifyReceiverInferredMethodAndInvokeTargetMixin {
        @ModifyReceiver(
            at = At(value = InjectionPoint.INVOKE),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun value(original: String): String {
            original.length
            return "both-inferred"
        }
    }

    @AsmMixin("ModifyReceiverTarget")
    object ModifyReceiverParentParamMixin {
        @ModifyReceiver(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun modify(original: CharSequence): String {
            original.length
            return "parent"
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

    @AsmMixin("SliceModifyReceiverTarget")
    object EmptyInvokeSliceModifyReceiverMixin {
        @ModifyReceiver(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
            ),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE),
            ),
            require = 1,
        )
        @JvmStatic
        fun modify(original: String): String {
            original.length
            return "changed"
        }
    }

    @AsmMixin("SliceModifyReceiverTarget")
    object InferredTargetEmptyInvokeSliceModifyReceiverMixin {
        @ModifyReceiver(
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
            ),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE),
            ),
            require = 1,
        )
        @JvmStatic
        fun value(original: String): String {
            original.length
            return "changed"
        }
    }

    @AsmMixin("InvokeDynamicSliceModifyReceiverTarget")
    object ModifyReceiverInvokeDynamicSliceMixin {
        @ModifyReceiver(
            method = "value(Ljava/lang/String;)Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
            ),
            slice = Slice(
                from = At(
                    value = InjectionPoint.INVOKE,
                    target = "java/lang/invoke/StringConcatFactory.makeConcatWithConstants(Ljava/lang/String;)Ljava/lang/String;",
                ),
                to = At(
                    value = InjectionPoint.INVOKE,
                    target = "java/lang/invoke/StringConcatFactory.makeConcatWithConstants(Ljava/lang/String;)Ljava/lang/String;",
                ),
            ),
            require = 1,
            allow = 1,
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

    @AsmMixin("ModifyReceiverTarget")
    object IncompatibleModifyReceiverReturnMixin {
        @ModifyReceiver(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun modify(original: String): StringBuilder = StringBuilder(original)
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

    @AsmMixin("Test")
    object ModifyReceiverInferredTestFieldReadMixin {
        var replacement: Any? = null

        @ModifyReceiver(
            method = "testA0()Ljava/lang/String;",
            at = At(value = InjectionPoint.FIELD),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun modify(original: Any): Any = replacement ?: original
    }

    @AsmMixin("Test")
    object ModifyReceiverInferredMethodAndTestFieldReadMixin {
        var replacement: Any? = null

        @ModifyReceiver(
            at = At(value = InjectionPoint.FIELD),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun testA0(original: Any): Any = replacement ?: original
    }

    @AsmMixin("FieldPointTarget")
    object ModifyReceiverInferredMethodAndFieldReadMixin {
        var replacement: Any? = null

        @ModifyReceiver(
            at = At(value = InjectionPoint.FIELD),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun readName(original: Any): Any = replacement ?: original
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

    @AsmMixin("Test")
    object ModifyReceiverInferredTestFieldAssignMixin {
        var replacement: Any? = null
        var lastValue: String? = null

        @ModifyReceiver(
            method = "<init>(Ljava/lang/String;)V",
            at = At(value = InjectionPoint.FIELD_ASSIGN),
            require = 4,
            allow = 4,
        )
        @JvmStatic
        fun modify(
            original: Any,
            value: String,
        ): Any {
            lastValue = value
            return replacement ?: original
        }
    }

    @AsmMixin("FieldPointTarget")
    object ModifyReceiverInferredMethodAndFieldAssignMixin {
        var replacement: Any? = null
        var lastValue: String? = null

        @ModifyReceiver(
            at = At(value = InjectionPoint.FIELD_ASSIGN),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun writeName(
            original: Any,
            value: String,
        ): Any {
            lastValue = value
            return replacement ?: original
        }
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
    object WrapOperationInvokeSyntaxMixin {
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
            return operation(target, "-invoke-call")
        }
    }

    @AsmMixin("ModifyReceiverTarget")
    object InferredWrapOperationTargetMixin {
        @WrapOperation(
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun value(
            target: String,
            value: String,
            operation: Operation<String>,
        ): String {
            target.length
            value.length
            return operation.call(target, "-inferred-call")
        }
    }

    @AsmMixin("InferredInvokeExpressionValueTarget")
    object WrapOperationInferredInvokeTargetMixin {
        @WrapOperation(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.INVOKE),
        )
        @JvmStatic
        fun wrap(
            target: Any,
            operation: Operation<String>,
        ): String = "${operation.call(target)}-wrapped"
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

    @AsmMixin("InvokeDynamicExpressionValueTarget")
    object WrapOperationInvokeDynamicMixin {
        @WrapOperation(
            method = "value(Ljava/lang/String;I)Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/invoke/StringConcatFactory.makeConcatWithConstants(Ljava/lang/String;I)Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun wrap(
            prefix: String,
            count: Int,
            operation: Operation<String>,
        ): String = "${operation.call(prefix.uppercase(), count + 1)}-wrapped"
    }

    @AsmMixin("LoadExpressionValueTarget")
    object WrapOperationLoadMixin {
        @WrapOperation(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.LOAD, args = ["index=1"]),
            ordinal = 0,
        )
        @JvmStatic
        fun wrap(
            original: String,
            operation: Operation<String>,
        ): String = "wrap-${operation.call(original)}"
    }

    @AsmMixin("StoreExpressionValueTarget")
    object WrapOperationStoreMixin {
        @WrapOperation(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.STORE, args = ["index=1"]),
            ordinal = 0,
        )
        @JvmStatic
        fun wrap(
            original: String,
            operation: Operation<String>,
        ): String = operation.call("wrap-store-$original")
    }

    @AsmMixin("NamedLoadVariableTarget")
    object WrapOperationLoadNameMixin {
        @WrapOperation(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.LOAD, args = ["name=target"]),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun wrap(
            original: String,
            operation: Operation<String>,
        ): String = "wrap-${operation.call(original)}"
    }

    @AsmMixin("NamedStoreVariableTarget")
    object WrapOperationStoreNameMixin {
        @WrapOperation(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.STORE, args = ["name=target"]),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun wrap(
            original: String,
            operation: Operation<String>,
        ): String = operation.call("wrap-store-$original")
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

    @AsmMixin("WrapMethodAssignabilityTarget")
    object WrapMethodAssignabilityTargetMixin {
        @WrapMethod(method = "value(Ljava/lang/String;)Ljava/lang/String;")
        @JvmStatic
        fun wrap(
            prefix: CharSequence,
            operation: Operation<String>,
        ): String = "wrapped:${operation.call(prefix.toString())}"
    }

    @AsmMixin("WrapMethodAssignabilityTarget")
    object InferredWrapMethodAssignabilityTargetMixin {
        @WrapMethod
        @JvmStatic
        fun value(
            prefix: CharSequence,
            operation: Operation<String>,
        ): String = "inferred:${operation.call(prefix.toString())}"
    }

    @AsmMixin("WrapMethodAssignabilityTarget")
    object InferredWrapMethodGenericReturnMixin {
        @WrapMethod
        @JvmStatic
        fun value(
            prefix: String,
            operation: Operation<String>,
        ): Any = "generic:${operation.call(prefix)}"
    }

    @AsmMixin("AmbiguousWrapMethodTarget")
    object AmbiguousWrapMethodInferenceMixin {
        @WrapMethod
        @JvmStatic
        fun value(
            prefix: CharSequence,
            operation: Operation<String>,
        ): String = operation.call(prefix)
    }

    @AsmMixin("WrapMethodStaticTarget")
    object RequireTwoWrapMethodMixin {
        @WrapMethod(method = "value(Ljava/lang/String;I)Ljava/lang/String;", require = 2)
        @JvmStatic
        fun wrap(
            prefix: String,
            count: Int,
            operation: Operation<String>,
        ): String = operation.call(prefix, count)
    }

    @AsmMixin("WrapMethodStaticTarget")
    object AllowZeroWrapMethodMixin {
        @WrapMethod(method = "value(Ljava/lang/String;I)Ljava/lang/String;", allow = 0)
        @JvmStatic
        fun wrap(
            prefix: String,
            count: Int,
            operation: Operation<String>,
        ): String = operation.call(prefix, count)
    }

    @AsmMixin("WrapMethodStaticTarget")
    object ExpectTwoWrapMethodMixin {
        @WrapMethod(method = "value(Ljava/lang/String;I)Ljava/lang/String;", expect = 2)
        @JvmStatic
        fun wrap(
            prefix: String,
            count: Int,
            operation: Operation<String>,
        ): String = operation.call(prefix, count)
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

    @AsmMixin("ModifyReceiverTarget")
    object WrapOperationParentParamMixin {
        @WrapOperation(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun wrap(
            target: CharSequence,
            value: String,
            operation: Operation<String>,
        ): String {
            target.length
            value.length
            return "${operation.call(target.toString(), value)}-parent"
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

    @AsmMixin("SliceWrapOperationTarget")
    object WrapOperationEmptyInvokeSliceBoundaryMixin {
        @WrapOperation(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
            ),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE),
            ),
            require = 1,
        )
        @JvmStatic
        fun wrap(
            target: String,
            value: String,
            operation: Operation<String>,
        ): String = operation.call(target, value)
    }

    @AsmMixin("SliceWrapOperationTarget")
    object InferredTargetEmptyInvokeSliceWrapOperationMixin {
        @WrapOperation(
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
            ),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE),
            ),
            require = 1,
        )
        @JvmStatic
        fun value(
            target: String,
            value: String,
            operation: Operation<String>,
        ): String = operation.call(target, value)
    }

    @AsmMixin("SliceFieldReadTarget")
    object WrapOperationFieldReadSliceMixin {
        @WrapOperation(
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
        fun wrap(
            target: Any,
            operation: Operation<String>,
        ): String {
            target.hashCode()
            return "${operation.call(target)}-wrapped"
        }
    }

    @AsmMixin("SliceFieldAssignTarget")
    object WrapOperationFieldAssignSliceMixin {
        @WrapOperation(
            method = "writeSelected(Ljava/lang/String;Ljava/lang/String;)V",
            at = At(value = InjectionPoint.FIELD_ASSIGN, target = "SliceFieldAssignTarget.name:Ljava/lang/String;"),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
                to = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun wrap(
            target: Any,
            value: String,
            operation: Operation<Unit>,
        ) {
            target.hashCode()
            operation.call(target, "wrapped-$value")
        }
    }

    @AsmMixin("SliceArrayExpressionValueTarget")
    object WrapOperationArrayReadSliceMixin {
        @WrapOperation(
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
        fun wrap(
            array: Array<String>,
            index: Int,
            operation: Operation<String>,
        ): String = "wrapped-${operation.call(array, index)}"
    }

    @AsmMixin("SliceArrayExpressionValueTarget")
    object WrapOperationArrayLengthSliceMixin {
        @WrapOperation(
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
        fun wrap(
            array: Array<String>,
            operation: Operation<Int>,
        ): Int = operation.call(array) + 5
    }

    @AsmMixin("SliceWrapConditionArrayTarget")
    object WrapOperationArrayWriteSliceMixin {
        @WrapOperation(
            method = "writeSelected()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.FIELD_ASSIGN,
                target = "SliceWrapConditionArrayTarget.names:[Ljava/lang/String;",
                args = ["array=set"],
            ),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
                to = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
            ),
            require = 1,
            allow = 1,
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

    @AsmMixin("ConstructorModifyArgTarget")
    object WrapOperationNewConstructorMixin {
        @WrapOperation(
            method = "value()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.NEW,
                target = "java/lang/StringBuilder",
            ),
        )
        @JvmStatic
        fun wrap(
            value: String,
            operation: Operation<StringBuilder>,
        ): StringBuilder = operation.call("new-$value")
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

    @AsmMixin("MixedFieldExpressionValueTarget")
    object WrapOperationInferredFieldReadMixin {
        @WrapOperation(
            method = "readSelected()Ljava/lang/String;",
            at = At(value = InjectionPoint.FIELD),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun wrap(
            target: Any,
            operation: Operation<String>,
        ): String = "wrapped-inferred-${operation.call(target)}"
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

    @AsmMixin("Test")
    object WrapOperationInferredTestFieldAssignMixin {
        @WrapOperation(
            method = "<init>(Ljava/lang/String;)V",
            at = At(value = InjectionPoint.FIELD_ASSIGN),
            require = 2,
            allow = 2,
        )
        @JvmStatic
        fun wrap(
            target: Any,
            value: String,
            operation: Operation<Unit>,
        ) {
            operation.call(target, "wrapped-test-$value")
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

    @AsmMixin("ArrayAccessTarget")
    object WrapOperationArrayReadObjectReturnMixin {
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
        ): Any = "object-${operation.call(array, index)}"
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

    @AsmMixin("ArrayAccessTarget")
    object IncompatibleWrapOperationArrayReadReturnMixin {
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
        ): StringBuilder = StringBuilder(operation.call(array, index))
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

    @AsmMixin("MixedConstantTarget")
    object InferredModifyConstantTargetMixin {
        @ModifyConstant(constant = "original")
        @JvmStatic
        fun value(original: String): String = "inferred-$original"
    }

    @AsmMixin("MixedConstantTarget")
    object StringConstantGenericReturnMixin {
        @ModifyConstant(method = "value()Ljava/lang/String;", constant = "original")
        @JvmStatic
        fun modify(original: String): Any = "generic-$original"
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

    @AsmMixin("InvokeDynamicSliceConstantTarget")
    object ModifyConstantInvokeDynamicSliceMixin {
        @ModifyConstant(
            method = "value(Ljava/lang/String;)Ljava/lang/String;",
            constant = "target",
            slice = Slice(
                from = At(
                    value = InjectionPoint.INVOKE,
                    target = "java/lang/invoke/StringConcatFactory.makeConcatWithConstants(Ljava/lang/String;)Ljava/lang/String;",
                ),
                to = At(
                    value = InjectionPoint.INVOKE,
                    target = "java/lang/invoke/StringConcatFactory.makeConcatWithConstants(Ljava/lang/String;)Ljava/lang/String;",
                ),
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun modify(original: String): String = "changed"
    }

    @AsmMixin("ConstantBoundarySliceConstantTarget")
    object ConstantBoundaryModifyConstantMixin {
        @ModifyConstant(
            method = "value()Ljava/lang/String;",
            constant = "target",
            slice = Slice(
                from = At(value = InjectionPoint.CONSTANT, target = "start-boundary"),
                to = At(value = InjectionPoint.CONSTANT, target = "end-boundary"),
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun modify(original: String): String = "changed"
    }

    @AsmMixin("FieldBoundarySliceConstantTarget")
    object FieldBoundaryModifyConstantMixin {
        @ModifyConstant(
            method = "value()Ljava/lang/String;",
            constant = "target",
            slice = Slice(
                from = At(value = InjectionPoint.FIELD, target = "FieldBoundarySliceConstantTarget.marker:Ljava/lang/String;"),
                to = At(value = InjectionPoint.FIELD, target = "FieldBoundarySliceConstantTarget.marker:Ljava/lang/String;"),
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun modify(original: String): String = "changed"
    }

    @AsmMixin("FieldAssignBoundarySliceConstantTarget")
    object FieldAssignBoundaryModifyConstantMixin {
        @ModifyConstant(
            method = "value()Ljava/lang/String;",
            constant = "target",
            slice = Slice(
                from =
                    At(
                        value = InjectionPoint.FIELD_ASSIGN,
                        target = "FieldAssignBoundarySliceConstantTarget.marker:Ljava/lang/String;",
                    ),
                to =
                    At(
                        value = InjectionPoint.FIELD_ASSIGN,
                        target = "FieldAssignBoundarySliceConstantTarget.marker:Ljava/lang/String;",
                    ),
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun modify(original: String): String = "changed"
    }

    @AsmMixin("FieldAssignToValueBoundarySliceConstantTarget")
    object FieldAssignToValueBoundaryModifyConstantMixin {
        @ModifyConstant(
            method = "value()Ljava/lang/String;",
            constant = "target",
            slice = Slice(
                from =
                    At(
                        value = InjectionPoint.FIELD_ASSIGN,
                        target = "FieldAssignToValueBoundarySliceConstantTarget.marker:Ljava/lang/String;",
                    ),
                to =
                    At(
                        value = InjectionPoint.FIELD_ASSIGN,
                        target = "FieldAssignToValueBoundarySliceConstantTarget.marker:Ljava/lang/String;",
                    ),
            ),
            require = 2,
            allow = 2,
        )
        @JvmStatic
        fun modify(original: String): String = "changed"
    }

    @AsmMixin("ConstantBoundarySliceConstantTarget")
    object MissingConstantBoundaryModifyConstantMixin {
        @ModifyConstant(
            method = "value()Ljava/lang/String;",
            constant = "target",
            slice = Slice(
                from = At(value = InjectionPoint.CONSTANT, target = "missing-boundary"),
                to = At(value = InjectionPoint.CONSTANT, target = "end-boundary"),
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun modify(original: String): String = "changed"
    }

    @AsmMixin("ConstantBoundarySliceConstantTarget")
    object EmptyConstantBoundaryModifyConstantMixin {
        @ModifyConstant(
            method = "value()Ljava/lang/String;",
            constant = "target",
            slice = Slice(
                from = At(value = InjectionPoint.CONSTANT),
                to = At(value = InjectionPoint.CONSTANT, target = "end-boundary"),
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun modify(original: String): String = "changed"
    }

    @AsmMixin("ConstantBoundarySliceConstantTarget")
    object InferredEmptyConstantBoundaryModifyConstantMixin {
        @ModifyConstant(
            constant = "target",
            slice = Slice(
                from = At(value = InjectionPoint.CONSTANT),
                to = At(value = InjectionPoint.CONSTANT, target = "end-boundary"),
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun value(original: String): String = "changed"
    }

    @AsmMixin("FieldBoundarySliceConstantTarget")
    object FieldBoundaryWithoutNameModifyConstantMixin {
        @ModifyConstant(
            method = "value()Ljava/lang/String;",
            constant = "target",
            slice = Slice(
                from = At(value = InjectionPoint.FIELD, target = ":Ljava/lang/String;"),
                to = At(value = InjectionPoint.FIELD, target = "FieldBoundarySliceConstantTarget.marker:Ljava/lang/String;"),
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun modify(original: String): String = "changed"
    }

    @AsmMixin("FieldAssignBoundarySliceConstantTarget")
    object FieldAssignBoundaryWithoutNameModifyConstantMixin {
        @ModifyConstant(
            method = "value()Ljava/lang/String;",
            constant = "target",
            slice = Slice(
                from = At(value = InjectionPoint.FIELD_ASSIGN, target = ":Ljava/lang/String;"),
                to =
                    At(
                        value = InjectionPoint.FIELD_ASSIGN,
                        target = "FieldAssignBoundarySliceConstantTarget.marker:Ljava/lang/String;",
                    ),
            ),
            require = 1,
            allow = 1,
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

    @AsmMixin("ConstantParamTarget")
    object ConstantWithAssignableTargetParamsMixin {
        @ModifyConstant(method = "value(Ljava/lang/String;I)Ljava/lang/String;", constant = "base-")
        @JvmStatic
        fun modify(
            original: CharSequence,
            suffix: CharSequence,
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

    @AsmMixin("NullConstantTarget")
    object NullConstantAssignableReturnMixin {
        @ModifyConstant(method = "value()Ljava/lang/Object;", constant = "null")
        @JvmStatic
        fun modify(original: Any?): String = "changed"
    }

    @AsmMixin("TypedNullConstantTarget")
    object TypedNullConstantGenericReturnMixin {
        @ModifyConstant(method = "value()Ljava/lang/String;", constant = "null")
        @JvmStatic
        fun modify(original: String?): Any = "generic-null"
    }

    @AsmMixin("NullConstantTarget")
    object NullConstantTypedReferenceParameterMixin {
        @ModifyConstant(method = "value()Ljava/lang/Object;", constant = "null")
        @JvmStatic
        fun modify(original: String?): String {
            assertEquals(null, original)
            return "typed-null"
        }
    }

    @AsmMixin("NullConstantTarget")
    object NullConstantTypedReferenceParameterWithoutValueMixin {
        @ModifyConstant(method = "value()Ljava/lang/Object;")
        @JvmStatic
        fun modify(original: String?): String {
            assertEquals(null, original)
            return "typed-null"
        }
    }

    @AsmMixin("TrueBooleanConstantTarget")
    object TrueBooleanModifyConstantMixin {
        @ModifyConstant(method = "value()Z", constant = "true")
        @JvmStatic
        fun modify(original: Boolean): Boolean = !original
    }

    @AsmMixin("FalseBooleanConstantTarget")
    object FalseBooleanModifyConstantMixin {
        @ModifyConstant(method = "value()Z", constant = "false")
        @JvmStatic
        fun modify(original: Boolean): Boolean = !original
    }

    @AsmMixin("ClassLiteralConstantTarget")
    object ClassLiteralModifyConstantMixin {
        @ModifyConstant(method = "value()Ljava/lang/Class;", constant = "java.lang.String")
        @JvmStatic
        fun modify(original: Class<*>): Class<*> = StringBuilder::class.java
    }

    @AsmMixin("MethodTypeConstantTarget")
    object MethodTypeModifyConstantMixin {
        @ModifyConstant(method = "value()Ljava/lang/invoke/MethodType;", constant = "(I)Ljava/lang/String;")
        @JvmStatic
        fun modify(original: java.lang.invoke.MethodType): java.lang.invoke.MethodType =
            java.lang.invoke.MethodType.methodType(StringBuilder::class.java, Int::class.javaPrimitiveType)
    }

    @AsmMixin("MethodHandleConstantTarget")
    object MethodHandleModifyConstantMixin {
        @ModifyConstant(
            method = "value()Ljava/lang/invoke/MethodHandle;",
            constant = "java/lang/String.valueOf(I)Ljava/lang/String;",
        )
        @JvmStatic
        fun modify(original: java.lang.invoke.MethodHandle): java.lang.invoke.MethodHandle {
            require(original.invokeWithArguments(10) == "10")
            return java.lang.invoke.MethodHandles.publicLookup().findStatic(
                Integer::class.java,
                "toHexString",
                java.lang.invoke.MethodType.methodType(String::class.java, Int::class.javaPrimitiveType),
            )
        }
    }

    @AsmMixin("DynamicConstantTarget")
    object DynamicConstantModifyConstantMixin {
        @ModifyConstant(method = "value()Ljava/lang/String;", constant = "dynamicText:Ljava/lang/String;")
        @JvmStatic
        fun modify(original: String): String = "changed:$original"
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

    @AsmMixin("MixedNumericConstantTarget")
    object MixedNumericModifyConstantMixin {
        @ModifyConstant(method = "value()I", constant = "1")
        @JvmStatic
        fun modify(original: Int): Int = original + 41
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

    @AsmMixin("Test")
    object GroupedConstructorFallbackMixin {
        @Group(name = "constructorName", min = 1, max = 1)
        @ModifyConstant(method = "<init>()V", constant = "DefaultConstructor")
        @JvmStatic
        fun currentName(original: String): String = "GroupedConstructor"

        @Group(name = "constructorName", min = 1, max = 1)
        @ModifyConstant(method = "<init>()V", constant = "LegacyConstructor")
        @JvmStatic
        fun legacyName(original: String): String = "LegacyGroupedConstructor"
    }

    @AsmMixin("Test")
    object GroupedMissingConstructorConstantsMixin {
        @Group(name = "constructorName", min = 1)
        @ModifyConstant(method = "<init>()V", constant = "LegacyConstructor")
        @JvmStatic
        fun legacyName(original: String): String = "LegacyGroupedConstructor"

        @Group(name = "constructorName", min = 1)
        @ModifyConstant(method = "<init>()V", constant = "ExperimentalConstructor")
        @JvmStatic
        fun experimentalName(original: String): String = "ExperimentalGroupedConstructor"
    }

    @AsmMixin("Test")
    object GroupedRequiredLegacyConstructorConstantMixin {
        @Group(name = "requiredConstructorName", min = 0, max = 1)
        @ModifyConstant(method = "<init>()V", constant = "LegacyConstructor", require = 1)
        @JvmStatic
        fun legacyName(original: String): String = "LegacyGroupedConstructor"
    }

    @AsmMixin("Test")
    object GroupedTooManyRuntimeNamesMixin {
        @Group(name = "singleRuntimeName", min = 1, max = 1)
        @ModifyConstant(method = "<init>()V", constant = "DefaultConstructor")
        @JvmStatic
        fun constructorName(original: String): String = "$original-ctor"

        @Group(name = "singleRuntimeName", min = 1, max = 1)
        @ModifyConstant(method = "testB0()Ljava/lang/String;", constant = "StaticFinalString")
        @JvmStatic
        fun staticName(original: String): String = "$original-static"
    }

    @AsmMixin("Test")
    object UngroupedMissingConstructorConstantMixin {
        @ModifyConstant(method = "<init>()V", constant = "LegacyConstructor")
        @JvmStatic
        fun modify(original: String): String = "LegacyConstructor"
    }

    @AsmMixin("StrictTarget")
    object UngroupedEmptyModifyConstantNoConstantTargetMixin {
        @ModifyConstant(method = "keep()V")
        @JvmStatic
        fun modify(original: String): String = original
    }

    @AsmMixin("ReturnTarget")
    object GroupedReturnAndHeadInjectMixin {
        @Group(name = "returnLifecycle", min = 2, max = 2)
        @AsmInject(method = "value()Ljava/lang/String;", target = InjectionPoint.RETURN)
        @JvmStatic
        fun returnPoint() {
        }

        @Group(name = "returnLifecycle", min = 2, max = 2)
        @AsmInject(method = "value()Ljava/lang/String;", target = InjectionPoint.HEAD)
        @JvmStatic
        fun headPoint() {
        }
    }

    @AsmMixin("RedirectAllMultiTarget")
    @RedirectAllMethods
    object GroupedRedirectAllAllowOneTrimMixin {
        @Group(name = "redirectAllTrim", min = 1, max = 1)
        @Redirect(
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.trim()Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun redirect(value: String): String = value
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

    @AsmMixin("InheritedAccessorTarget")
    class InheritedShadowFieldMixin {
        @Shadow("modCount")
        private val inheritedModCount: Int = 0
    }

    @AsmMixin("InheritedAccessorTarget")
    class InheritedShadowMethodMixin {
        @Shadow("clear")
        private fun inheritedClear() {
            throw UnsupportedOperationException()
        }
    }

    @AsmMixin("InterfaceDefaultInvokerTarget")
    class InterfaceDefaultShadowMethodMixin {
        @Shadow("spliterator")
        private fun inheritedSpliterator(): java.util.Spliterator<*> = throw UnsupportedOperationException()
    }

    @AsmMixin("FinalFieldTarget")
    class MutableFieldOnlyMixin {
        @Shadow
        @Mutable
        private val name: String? = null
    }

    @AsmMixin("FieldTarget")
    class FinalShadowAliasFieldMixin {
        @Shadow("name")
        @Final
        private val aliasName: String? = null
    }

    @AsmMixin("FinalAccessorSetterTarget")
    class FinalFieldSetterWithoutMutableMixin {
        @Accessor("name")
        fun setName(value: String) {
            throw UnsupportedOperationException()
        }
    }

    @AsmMixin("FinalAccessorSetterTarget")
    class FinalFieldShadowMutableThenSetterWithoutMutableMixin {
        @Shadow
        @Mutable
        private val name: String? = null

        @Accessor("name")
        fun setName(value: String) {
            throw UnsupportedOperationException()
        }
    }

    @AsmMixin(value = "FinalAccessorSetterTarget", priority = 1500)
    class HighPriorityFinalFieldMutableMixin {
        @Shadow
        @Mutable
        private val name: String? = null
    }

    @AsmMixin(value = "FinalAccessorSetterTarget", priority = 500)
    class LowPriorityFinalFieldSetterWithoutMutableMixin {
        @Accessor("name")
        fun setName(value: String) {
            throw UnsupportedOperationException()
        }
    }

    @AsmMixin("AccessorSetterTarget")
    class InstanceFieldSetterAccessorMixin {
        @Accessor("name")
        fun setName(value: String) {
            throw UnsupportedOperationException()
        }
    }

    @AsmMixin("FinalAccessorSetterTarget")
    class FinalFieldMutableSetterAccessorMixin {
        @Accessor("name")
        @Mutable
        fun setName(value: String) {
            throw UnsupportedOperationException()
        }
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

    @AsmMixin("ReturnTarget")
    object InferredOverwriteTargetMixin {
        @Overwrite
        @JvmStatic
        fun value(): String = "inferred-overwrite"
    }

    @ReplaceAllMethods
    @AsmMixin("ReturnTarget")
    object ReplaceAllThenOverwriteMixin {
        @Overwrite("value()Ljava/lang/String;")
        @JvmStatic
        fun value(): String = "overwritten"
    }

    @ReplaceAllMethods
    @AsmMixin("ReferenceReturnTarget")
    object ReplaceAllReferenceReturnMixin

    @ReplaceAllMethods
    @AsmMixin("CharReturnTarget")
    object ReplaceAllCharReturnMixin

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

    @AsmMixin("RedirectTarget")
    object InferredInvokeInjectTargetMixin {
        @AsmInject(
            target = InjectionPoint.INVOKE,
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.trim()Ljava/lang/String;",
            ),
        )
        @JvmStatic
        fun call() {
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

    @AsmMixin("SliceInvokeTarget")
    object EmptyInvokeSliceAsmInjectMixin {
        @AsmInject(
            method = "call()Ljava/lang/String;",
            target = InjectionPoint.INVOKE,
            at = At(value = InjectionPoint.INVOKE, target = "java/lang/String.trim()Ljava/lang/String;"),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE),
            ),
            require = 1,
        )
        @JvmStatic
        fun inject() {
        }
    }

    @AsmMixin("SliceInvokeTarget")
    object InferredTargetEmptyInvokeSliceAsmInjectMixin {
        @AsmInject(
            target = InjectionPoint.INVOKE,
            at = At(value = InjectionPoint.INVOKE, target = "java/lang/String.trim()Ljava/lang/String;"),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE),
            ),
            require = 1,
        )
        @JvmStatic
        fun call() {
        }
    }

    @AsmMixin("SliceFieldReadTarget")
    object EmptyInvokeSliceInstructionPointMixin {
        @AsmInject(
            method = "readSelected()Ljava/lang/String;",
            target = InjectionPoint.FIELD,
            at = At(value = InjectionPoint.FIELD, target = "SliceFieldReadTarget.name:Ljava/lang/String;"),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE),
            ),
            require = 1,
        )
        @JvmStatic
        fun inject() {
        }
    }

    @AsmMixin("SliceFieldReadTarget")
    object InferredTargetEmptyInvokeSliceInstructionPointMixin {
        @AsmInject(
            target = InjectionPoint.FIELD,
            at = At(value = InjectionPoint.FIELD, target = "SliceFieldReadTarget.name:Ljava/lang/String;"),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE),
            ),
            require = 1,
        )
        @JvmStatic
        fun readSelected() {
        }
    }

    @AsmMixin("InvokeDynamicSliceModifyArgTarget")
    object InvokeAssignDynamicSliceMixin {
        var injectCount: Int = 0
        var observed: String = ""

        @AsmInject(
            method = "value(Ljava/lang/String;)Ljava/lang/String;",
            target = InjectionPoint.INVOKE_ASSIGN,
            at = At(
                value = InjectionPoint.INVOKE_ASSIGN,
                target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
            ),
            slice = Slice(
                from = At(
                    value = InjectionPoint.INVOKE,
                    target = "java/lang/invoke/StringConcatFactory.makeConcatWithConstants(Ljava/lang/String;)Ljava/lang/String;",
                ),
                to = At(
                    value = InjectionPoint.INVOKE,
                    target = "java/lang/invoke/StringConcatFactory.makeConcatWithConstants(Ljava/lang/String;)Ljava/lang/String;",
                ),
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun inject(
            suffix: String,
            marker: String,
        ) {
            injectCount++
            observed = "$suffix:$marker"
        }
    }

    @AsmMixin("SliceInvokeTarget")
    object InvokeAssignInvalidSliceBoundaryMixin {
        @AsmInject(
            method = "call()Ljava/lang/String;",
            target = InjectionPoint.INVOKE_ASSIGN,
            at = At(
                value = InjectionPoint.INVOKE_ASSIGN,
                target = "java/lang/String.trim()Ljava/lang/String;",
            ),
            slice = Slice(
                from = At(value = InjectionPoint.FIELD, target = "java/lang/String.value:Ljava/lang/String;"),
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

    @AsmMixin("RedirectAllMultiTarget")
    @RedirectAllMethods
    object RedirectAllAllowOneTrimMixin {
        @Redirect(
            at = At(
                value = InjectionPoint.INVOKE,
                target = "java/lang/String.trim()Ljava/lang/String;",
            ),
            allow = 1,
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

    @AsmMixin("VariableTarget")
    object ModifyVariableObjectParameterMixin {
        @ModifyVariable(
            method = "echo(Ljava/lang/String;)Ljava/lang/String;",
            at = At(value = InjectionPoint.HEAD),
            index = 1,
        )
        @JvmStatic
        fun modify(original: Any): String = "$original-any"
    }

    @AsmMixin("CharSequenceVariableTarget")
    object ModifyVariableAssignableReturnMixin {
        @ModifyVariable(
            method = "echo(Ljava/lang/CharSequence;)Ljava/lang/CharSequence;",
            at = At(value = InjectionPoint.HEAD),
            index = 1,
        )
        @JvmStatic
        fun modify(original: CharSequence): StringBuilder = StringBuilder("variable-$original")
    }

    @AsmMixin("VariableTarget")
    object ModifyVariableGenericReturnMixin {
        @ModifyVariable(
            method = "echo(Ljava/lang/String;)Ljava/lang/String;",
            at = At(value = InjectionPoint.HEAD),
            index = 1,
        )
        @JvmStatic
        fun modify(original: String): Any = "generic-$original"
    }

    @AsmMixin("VariableTarget")
    object ModifyVariableInferredHeadParamMixin {
        @ModifyVariable(
            method = "echo(Ljava/lang/String;)Ljava/lang/String;",
            at = At(value = InjectionPoint.HEAD),
        )
        @JvmStatic
        fun modify(original: String): String = "inferred-$original"
    }

    @AsmMixin("VariableTarget")
    object InferredModifyVariableTargetMixin {
        @ModifyVariable(
            at = At(value = InjectionPoint.HEAD),
            index = 1,
        )
        @JvmStatic
        fun echo(original: String): String = "target-$original"
    }

    @AsmMixin("StoreVariableOverloadTarget")
    object InferredStoreModifyVariableMixin {
        @ModifyVariable(
            at = At(value = InjectionPoint.STORE),
            index = 2,
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun value(original: String): String = "stored-$original"
    }

    @AsmMixin("LoadVariableOverloadTarget")
    object InferredLoadModifyVariableMixin {
        @ModifyVariable(
            at = At(value = InjectionPoint.LOAD),
            index = 2,
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun value(original: String): String = "loaded-$original"
    }

    @AsmMixin("NamedHeadVariableOverloadTarget")
    object InferredNamedHeadModifyVariableMixin {
        @ModifyVariable(
            at = At(value = InjectionPoint.HEAD),
            name = ["target"],
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun echo(original: String): String = "named-$original"
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

    @AsmMixin("VariableTarget")
    object ModifyVariableParentTargetParamMixin {
        @ModifyVariable(
            method = "echo(Ljava/lang/String;)Ljava/lang/String;",
            at = At(value = InjectionPoint.HEAD),
            index = 1,
        )
        @JvmStatic
        fun modify(
            original: String,
            targetValue: CharSequence,
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

    @AsmMixin("StoreVariableTarget")
    object ModifyVariableStoreGenericReturnMixin {
        @ModifyVariable(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.STORE),
            index = 1,
        )
        @JvmStatic
        fun modify(original: String): Any = "stored-generic-$original"
    }

    @AsmMixin("StoreVariableTarget")
    object ModifyVariableStoreObjectParameterMixin {
        @ModifyVariable(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.STORE),
            index = 1,
        )
        @JvmStatic
        fun modify(original: Any): String = "stored-object-$original"
    }

    @AsmMixin("StoreVariableTarget")
    object ModifyVariableStoreObjectParameterGenericReturnMixin {
        @ModifyVariable(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.STORE),
            index = 1,
        )
        @JvmStatic
        fun modify(original: Any): Any = "stored-object-generic-$original"
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

    @AsmMixin("Test")
    object ModifyVariableNamedStoreTestMixin {
        @ModifyVariable(
            method = "localNameDiscriminatorTest(Ljava/lang/String;)Ljava/lang/String;",
            at = At(value = InjectionPoint.STORE),
            name = ["second"],
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun modify(original: String): String = "named-$original"
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

    @AsmMixin("LoadVariableTarget")
    object ModifyVariableLoadObjectParameterMixin {
        @ModifyVariable(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.LOAD),
            index = 1,
        )
        @JvmStatic
        fun modify(original: Any): String = "loaded-object-$original"
    }

    @AsmMixin("LoadVariableTarget")
    object ModifyVariableLoadObjectParameterGenericReturnMixin {
        @ModifyVariable(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.LOAD),
            index = 1,
        )
        @JvmStatic
        fun modify(original: Any): Any = "loaded-object-generic-$original"
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

    @AsmMixin("LoadArgsOnlyVariableTarget")
    object ModifyVariableLoadArgsOnlyMixin {
        @ModifyVariable(
            method = "value(Ljava/lang/String;)Ljava/lang/String;",
            at = At(value = InjectionPoint.LOAD),
            argsOnly = true,
        )
        @JvmStatic
        fun modify(original: String): String = "arg-$original"
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

    @AsmMixin("SliceLoadVariableTarget")
    object EmptyInvokeSliceModifyVariableMixin {
        @ModifyVariable(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.LOAD),
            index = 1,
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE),
            ),
            require = 1,
        )
        @JvmStatic
        fun modify(original: String): String = "loaded-$original"
    }

    @AsmMixin("SliceLoadVariableTarget")
    object InferredTargetEmptyInvokeSliceModifyVariableMixin {
        @ModifyVariable(
            at = At(value = InjectionPoint.LOAD),
            index = 1,
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE),
            ),
            require = 1,
        )
        @JvmStatic
        fun value(original: String): String = "loaded-$original"
    }

    @AsmMixin("InvokeDynamicSliceLoadVariableTarget")
    object ModifyVariableLoadInvokeDynamicSliceMixin {
        @ModifyVariable(
            method = "value(Ljava/lang/String;)Ljava/lang/String;",
            at = At(value = InjectionPoint.LOAD),
            index = 2,
            slice = Slice(
                from = At(
                    value = InjectionPoint.INVOKE,
                    target = "java/lang/invoke/StringConcatFactory.makeConcatWithConstants(Ljava/lang/String;)Ljava/lang/String;",
                ),
                to = At(
                    value = InjectionPoint.INVOKE,
                    target = "java/lang/invoke/StringConcatFactory.makeConcatWithConstants(Ljava/lang/String;)Ljava/lang/String;",
                ),
            ),
            require = 1,
            allow = 1,
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

    @AsmMixin("NamedLoadVariableTarget")
    object LoadInjectNameMixin {
        var injectCount: Int = 0

        @AsmInject(
            method = "value()Ljava/lang/String;",
            target = InjectionPoint.LOAD,
            at = At(value = InjectionPoint.LOAD, shift = Shift.BEFORE, args = ["name=target"]),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun inject() {
            injectCount++
        }
    }

    @AsmMixin("InvokeDynamicSliceLoadVariableTarget")
    object LoadInjectInvokeDynamicSliceMixin {
        var injectCount: Int = 0

        @AsmInject(
            method = "value(Ljava/lang/String;)Ljava/lang/String;",
            target = InjectionPoint.LOAD,
            at = At(value = InjectionPoint.LOAD, shift = Shift.BEFORE, args = ["index=2"]),
            slice = Slice(
                from = At(
                    value = InjectionPoint.INVOKE,
                    target = "java/lang/invoke/StringConcatFactory.makeConcatWithConstants(Ljava/lang/String;)Ljava/lang/String;",
                ),
                to = At(
                    value = InjectionPoint.INVOKE,
                    target = "java/lang/invoke/StringConcatFactory.makeConcatWithConstants(Ljava/lang/String;)Ljava/lang/String;",
                ),
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

    @AsmMixin("NamedStoreVariableTarget")
    object StoreInjectNameMixin {
        var injectCount: Int = 0

        @AsmInject(
            method = "value()Ljava/lang/String;",
            target = InjectionPoint.STORE,
            at = At(value = InjectionPoint.STORE, shift = Shift.AFTER, args = ["name=target"]),
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

    @AsmMixin("ReturnTarget")
    object JvmStaticCopyMixin {
        @Overwrite("value()Ljava/lang/String;")
        @JvmStatic
        fun value(): String = copied()

        @Copy("copied()Ljava/lang/String;")
        @JvmStatic
        fun copied(): String = "copied"
    }

    @AsmMixin("AccessorConflictTarget")
    class ConflictingAccessorMixin {
        @Accessor("name")
        fun getName(): String = throw UnsupportedOperationException()
    }

    @AsmMixin("InheritedAccessorTarget")
    class InheritedProtectedFieldAccessorMixin {
        @Accessor("modCount")
        fun getModCount(): Int = throw UnsupportedOperationException()
    }

    @AsmMixin("InheritedStaticAccessorTarget")
    object InheritedStaticFieldAccessorMixin {
        @Accessor("ERA")
        @JvmStatic
        fun getEra(): Int = throw UnsupportedOperationException()
    }

    @AsmMixin("InheritedInterfaceAccessorTarget")
    object InheritedInterfaceFieldAccessorMixin {
        @Accessor("INTEGER")
        @JvmStatic
        fun getSqlIntegerType(): Int = throw UnsupportedOperationException()
    }

    @AsmMixin("InheritedInterfaceAccessorTarget")
    object InterfaceFieldSetterAccessorMixin {
        @Accessor("INTEGER")
        @JvmStatic
        fun setSqlIntegerType(value: Int) {
            throw UnsupportedOperationException()
        }
    }

    @AsmMixin("InheritedAccessorTarget")
    class InheritedMethodInvokerMixin {
        @Invoker("size")
        fun callSize(): Int = throw UnsupportedOperationException()
    }

    @AsmMixin("InterfaceDefaultInvokerTarget")
    class InterfaceDefaultMethodInvokerMixin {
        @Invoker("spliterator")
        fun callSpliterator(): java.util.Spliterator<*> = throw UnsupportedOperationException()
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

    @AsmMixin("ConstructorInvokerTarget")
    object InterfaceReturnConstructorInvokerMixin {
        @Invoker("<init>")
        @JvmStatic
        fun createAsRunnable(value: String): Runnable = throw UnsupportedOperationException()
    }

    @AsmMixin("ConstructorInvokerTarget")
    object InheritedInterfaceReturnConstructorInvokerMixin {
        @Invoker("<init>")
        @JvmStatic
        fun createAsList(value: String): java.util.List<*> = throw UnsupportedOperationException()
    }

    @AsmMixin("PrivateInterfaceInvokerTarget")
    class PrivateInterfaceInvokerMixin {
        @Invoker("secret")
        fun callSecret(value: String): String = throw UnsupportedOperationException()
    }

    @AsmMixin("NewInstructionTarget")
    object ClassConstantModifyMixin {
        @Group(name = "newInstructionIsNotClassConstant", min = 0, max = 0)
        @ModifyConstant(method = "create()Ljava/lang/StringBuilder;")
        @JvmStatic
        fun modify(type: Class<*>): Class<*> = type
    }

    @AsmMixin("CastInstructionTarget")
    object CheckcastConstantModifyMixin {
        @Group(name = "checkcastIsNotClassConstant", min = 0, max = 0)
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

    @AsmMixin("FieldPointTarget")
    object FieldReadByForwardMixin {
        @AsmInject(
            method = "readName()Ljava/lang/String;",
            target = InjectionPoint.FIELD,
            at = At(
                value = InjectionPoint.FIELD,
                target = "FieldPointTarget.name:Ljava/lang/String;",
                shift = Shift.BEFORE,
                by = 1,
            ),
            require = 1,
            allow = 1,
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
    object ArrayReadObjectReturnRedirectMixin {
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
        ): Any = "object-${array[index]}"
    }

    @AsmMixin("ArrayAccessTarget")
    object IncompatibleArrayReadRedirectReturnMixin {
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
        ): StringBuilder = StringBuilder("redirected-${array[index]}")
    }

    @AsmMixin("ArrayAccessTarget")
    object ArrayWriteRedirectMixin {
        @Redirect(
            method = "writeName(ILjava/lang/String;)V",
            at = At(
                value = InjectionPoint.FIELD_ASSIGN,
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

    @AsmMixin("ArrayAccessTarget")
    object FieldArraySetRedirectMixin {
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
            array[index] = "legacy-$value"
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
                value = InjectionPoint.FIELD_ASSIGN,
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

    @AsmMixin("RedirectSliceTarget")
    object EmptyInvokeSliceRedirectMixin {
        @Redirect(
            method = "value()Ljava/lang/String;",
            at = At(value = InjectionPoint.INVOKE, target = "java/lang/String.trim()Ljava/lang/String;"),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE),
            ),
            require = 1,
        )
        @JvmStatic
        fun redirect(value: String): String = "redirected"
    }

    @AsmMixin("RedirectSliceTarget")
    object InferredTargetEmptyInvokeSliceRedirectMixin {
        @Redirect(
            at = At(value = InjectionPoint.INVOKE, target = "java/lang/String.trim()Ljava/lang/String;"),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE),
            ),
            require = 1,
        )
        @JvmStatic
        fun value(value: String): String = "redirected"
    }

    @AsmMixin("SliceFieldReadTarget")
    object RedirectFieldReadSliceMixin {
        @Redirect(
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
        fun redirect(target: Any): String {
            target.hashCode()
            return "redirected"
        }
    }

    @AsmMixin("SliceFieldAssignTarget")
    object RedirectFieldAssignSliceMixin {
        var lastValue: String? = null

        @Redirect(
            method = "writeSelected(Ljava/lang/String;Ljava/lang/String;)V",
            at = At(value = InjectionPoint.FIELD_ASSIGN, target = "SliceFieldAssignTarget.name:Ljava/lang/String;"),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
                to = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
            ),
            require = 1,
            allow = 1,
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

    @AsmMixin("SliceArrayExpressionValueTarget")
    object RedirectArrayReadSliceMixin {
        @Redirect(
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
        fun redirect(
            array: Array<String>,
            index: Int,
        ): String = "redirected-${array[index]}"
    }

    @AsmMixin("SliceArrayExpressionValueTarget")
    object RedirectArrayLengthSliceMixin {
        @Redirect(
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
        fun redirect(array: Array<String>): Int = array.size + 5
    }

    @AsmMixin("SliceWrapConditionArrayTarget")
    object RedirectArrayWriteSliceMixin {
        @Redirect(
            method = "writeSelected()Ljava/lang/String;",
            at = At(
                value = InjectionPoint.FIELD_ASSIGN,
                target = "SliceWrapConditionArrayTarget.names:[Ljava/lang/String;",
                args = ["array=set"],
            ),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
                to = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun redirect(
            array: Array<String>,
            index: Int,
            value: String,
        ) {
            array[index] = "redirected-$value"
        }
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

    @AsmMixin("FieldPointTarget")
    object FieldAssignByBackwardMixin {
        @AsmInject(
            method = "writeName(Ljava/lang/String;)V",
            target = InjectionPoint.FIELD_ASSIGN,
            at = At(
                value = InjectionPoint.FIELD_ASSIGN,
                target = "FieldPointTarget.name:Ljava/lang/String;",
                shift = Shift.AFTER,
                by = -1,
            ),
            require = 1,
            allow = 1,
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

    @AsmMixin("MultiNewTarget")
    object NewInstructionSliceMixin {
        @AsmInject(
            method = "value()Ljava/lang/String;",
            target = InjectionPoint.NEW,
            at = At(value = InjectionPoint.NEW, target = "java/lang/StringBuilder"),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE, target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;"),
            ),
            require = 1,
            allow = 1,
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

    @AsmMixin("InstanceofTarget")
    object InstanceofInstructionInjectMixin {
        @AsmInject(
            method = "isString(Ljava/lang/Object;Z)Z",
            target = InjectionPoint.INSTANCEOF,
            at = At(value = InjectionPoint.INSTANCEOF, target = "java/lang/String"),
        )
        @JvmStatic
        fun inject() {
        }
    }

    @AsmMixin("Test")
    object JumpInstructionInjectMixin {
        @AsmInject(
            method = "exceptionTest(Z)Ljava/lang/String;",
            target = InjectionPoint.JUMP,
            at = At(value = InjectionPoint.JUMP, target = "IFEQ"),
        )
        @JvmStatic
        fun inject() {
        }
    }

    @AsmMixin("Test")
    object JumpInstructionNumericTargetMixin {
        @AsmInject(
            method = "recursiveMethod(I)I",
            target = InjectionPoint.JUMP,
            at = At(value = InjectionPoint.JUMP, target = "${Opcodes.IF_ICMPGT}"),
        )
        @JvmStatic
        fun inject() {
        }
    }

    @AsmMixin("SwitchSelectorTarget")
    object SwitchInstructionInjectMixin {
        @AsmInject(
            method = "choose(IZ)Ljava/lang/String;",
            target = InjectionPoint.SWITCH,
            at = At(value = InjectionPoint.SWITCH),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun inject() {
        }
    }

    @AsmMixin("Test")
    object ConstantInstructionInjectMixin {
        @AsmInject(
            method = "testB0()Ljava/lang/String;",
            target = InjectionPoint.CONSTANT,
            at = At(value = InjectionPoint.CONSTANT, target = "StaticFinalString"),
        )
        @JvmStatic
        fun inject() {
        }
    }

    @AsmMixin("ConstantParamTarget")
    object ConstantInstructionReplaceMixin {
        @AsmInject(
            method = "value(Ljava/lang/String;I)Ljava/lang/String;",
            target = InjectionPoint.CONSTANT,
            at = At(value = InjectionPoint.CONSTANT, target = "base-", shift = Shift.REPLACE),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun inject(
            suffix: String,
            count: Int,
        ): String = "$suffix-$count"
    }

    @AsmMixin("TrueBooleanConstantTarget")
    object BooleanConstantInstructionReplaceMixin {
        @AsmInject(
            method = "value()Z",
            target = InjectionPoint.CONSTANT,
            at = At(value = InjectionPoint.CONSTANT, target = "true", shift = Shift.REPLACE),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun inject(): Boolean = false
    }

    @AsmMixin("InvokeStringTarget")
    object InvokeStringMarkerMixin {
        @AsmInject(
            method = "run()V",
            target = InjectionPoint.INVOKE_STRING,
            at = At(
                value = InjectionPoint.INVOKE_STRING,
                target = "InvokeStringTarget.target(Ljava/lang/String;)V",
                args = ["ldc=marker"],
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun inject() {
        }
    }

    @AsmMixin("InvokeStringTarget")
    object MissingInvokeStringArgumentMixin {
        @AsmInject(
            method = "run()V",
            target = InjectionPoint.INVOKE_STRING,
            at = At(
                value = InjectionPoint.INVOKE_STRING,
                target = "InvokeStringTarget.target(Ljava/lang/String;)V",
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun inject() {
        }
    }

    @AsmMixin("InvokeStringTarget")
    object ExtraInvokeStringArgumentMixin {
        @AsmInject(
            method = "run()V",
            target = InjectionPoint.INVOKE_STRING,
            at = At(
                value = InjectionPoint.INVOKE_STRING,
                target = "InvokeStringTarget.target(Ljava/lang/String;)V",
                args = ["ldc=marker", "var=1"],
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun inject() {
        }
    }

    @AsmMixin("InvokeStringTarget")
    object OwnerlessInvokeStringTargetMixin {
        @AsmInject(
            method = "run()V",
            target = InjectionPoint.INVOKE_STRING,
            at = At(
                value = InjectionPoint.INVOKE_STRING,
                target = "target(Ljava/lang/String;)V",
                args = ["ldc=marker"],
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun inject() {
        }
    }

    @AsmMixin("InvokeStringLongArgumentTarget")
    object InvokeStringLongArgumentMixin {
        @AsmInject(
            method = "run()V",
            target = InjectionPoint.INVOKE_STRING,
            at = At(
                value = InjectionPoint.INVOKE_STRING,
                target = "InvokeStringLongArgumentTarget.target(JLjava/lang/String;)V",
                args = ["ldc=marker"],
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun inject() {
        }
    }

    @AsmMixin("Test")
    object InvokeAssignInjectMixin {
        @AsmInject(
            method = "testVoid()V",
            target = InjectionPoint.INVOKE_ASSIGN,
            at = At(
                value = InjectionPoint.INVOKE_ASSIGN,
                target = "java/io/PrintStream.println(Ljava/lang/String;)V",
            ),
        )
        @JvmStatic
        fun inject() {
        }
    }

    @AsmMixin("InvokeDynamicExpressionValueTarget")
    object InvokeAssignDynamicInjectMixin {
        var injectCount: Int = 0
        var observed: String = ""

        @AsmInject(
            method = "value(Ljava/lang/String;I)Ljava/lang/String;",
            target = InjectionPoint.INVOKE_ASSIGN,
            at = At(
                value = InjectionPoint.INVOKE_ASSIGN,
                target = "java/lang/invoke/StringConcatFactory.makeConcatWithConstants(Ljava/lang/String;I)Ljava/lang/String;",
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun inject(
            dynamicPrefix: String,
            dynamicCount: Int,
        ) {
            injectCount++
            observed = "$dynamicPrefix:$dynamicCount"
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

    @AsmMixin("TargetedThrowPointTarget")
    object ThrowInstructionTargetedMixin {
        @AsmInject(
            method = "fail(Z)V",
            target = InjectionPoint.THROW,
            at = At(value = InjectionPoint.THROW, target = "java/lang/IllegalStateException"),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun inject() {
        }
    }

    @AsmMixin("ThrowPointTarget")
    object ModifyExpressionValueThrowMixin {
        @ModifyExpressionValue(
            method = "fail()V",
            at = At(value = InjectionPoint.THROW),
        )
        @JvmStatic
        fun modify(original: Throwable): Throwable = IllegalArgumentException("modified-${original.message}")
    }

    @AsmMixin("ThrowPointTarget")
    object ModifyExpressionValueSpecificThrowableMixin {
        @ModifyExpressionValue(
            method = "fail()V",
            at = At(value = InjectionPoint.THROW),
        )
        @JvmStatic
        fun modify(original: Throwable): IllegalArgumentException =
            IllegalArgumentException("specific-${original.message}")
    }

    @AsmMixin("TargetedThrowPointTarget")
    object ModifyExpressionValueTargetedThrowMixin {
        @ModifyExpressionValue(
            method = "fail(Z)V",
            at = At(value = InjectionPoint.THROW, target = "java/lang/IllegalStateException"),
        )
        @JvmStatic
        fun modify(original: Throwable): Throwable = IllegalArgumentException("modified-${original.message}")
    }

    @AsmMixin("ThrowPointTarget")
    object ModifyExpressionValueThrowWithTargetParamsMixin {
        @ModifyExpressionValue(
            method = "failWithParams(Ljava/lang/String;I)V",
            at = At(value = InjectionPoint.THROW),
        )
        @JvmStatic
        fun modify(
            original: Throwable,
            prefix: String,
            count: Int,
        ): Throwable = IllegalArgumentException("$prefix-$count-${original.message}")
    }

    @AsmMixin("ThrowPointTarget")
    object WrapOperationThrowMixin {
        @WrapOperation(
            method = "fail()V",
            at = At(value = InjectionPoint.THROW),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun wrap(
            original: Throwable,
            operation: Operation<Throwable>,
        ): Throwable {
            val thrown = operation.call(original)
            return IllegalArgumentException("wrapped-${thrown.message}")
        }
    }

    @AsmMixin("TargetedThrowPointTarget")
    object WrapOperationTargetedThrowMixin {
        @WrapOperation(
            method = "fail(Z)V",
            at = At(value = InjectionPoint.THROW, target = "java/lang/IllegalStateException"),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun wrap(
            original: Throwable,
            operation: Operation<Throwable>,
        ): Throwable = IllegalArgumentException("wrapped-${operation.call(original).message}")
    }

    @AsmMixin("ThrowPointTarget")
    object RedirectThrowMixin {
        @Redirect(
            method = "fail()V",
            at = At(value = InjectionPoint.THROW),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun redirect(original: Throwable): Throwable = IllegalArgumentException("redirected-${original.message}")
    }

    @AsmMixin("SliceThrowInstructionTarget")
    object ModifyExpressionValueThrowSliceMixin {
        @ModifyExpressionValue(
            method = "failSelected()V",
            at = At(value = InjectionPoint.THROW),
            slice = Slice(
                from = At(value = InjectionPoint.INVOKE, target = "java/lang/String.toString()Ljava/lang/String;"),
            ),
            require = 1,
            allow = 1,
        )
        @JvmStatic
        fun modify(original: Throwable): Throwable = IllegalArgumentException("modified-${original.message}")
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

    @AsmMixin("PriorityTarget")
    object DefaultPriorityMixin

    @AsmMixin(value = "PriorityTarget", priority = 500)
    object LowPriorityExactMixin

    @AsmMixin(value = "PriorityTarget", priority = 1500)
    object HighPriorityExactMixin

    @AsmMixin(value = "PriorityTarget", priority = 1000)
    object FirstTiePriorityMixin

    @AsmMixin(value = "PriorityTarget", priority = 1000)
    object SecondTiePriorityMixin

    @AsmMixin(priority = 500)
    object LowPriorityPathMixin

    @AsmMixin(priority = 1500)
    object HighPriorityPathMixin

    object UnannotatedPathMixin

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

    private fun redirectAllMultiTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "RedirectAllMultiTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "first", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn(" first ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "trim", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "second", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn(" second ")
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

    private fun charSequenceVariableTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "CharSequenceVariableTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "echo", "(Ljava/lang/CharSequence;)Ljava/lang/CharSequence;", null, null).apply {
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

    private fun namedHeadVariableOverloadTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(
            Opcodes.V11,
            Opcodes.ACC_PUBLIC,
            "NamedHeadVariableOverloadTarget",
            null,
            "java/lang/Object",
            null,
        )
        addDefaultConstructor(cw)
        cw.visitMethod(
            Opcodes.ACC_PUBLIC,
            "echo",
            "(Ljava/lang/String;)Ljava/lang/String;",
            null,
            null,
        ).apply {
            val start = org.objectweb.asm.Label()
            val end = org.objectweb.asm.Label()
            visitCode()
            visitLabel(start)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitInsn(Opcodes.ARETURN)
            visitLabel(end)
            visitLocalVariable(
                "this",
                "LNamedHeadVariableOverloadTarget;",
                null,
                start,
                end,
                0,
            )
            visitLocalVariable("other", "Ljava/lang/String;", null, start, end, 1)
            visitMaxs(1, 2)
            visitEnd()
        }
        cw.visitMethod(
            Opcodes.ACC_PUBLIC,
            "echo",
            "(Ljava/lang/String;I)Ljava/lang/String;",
            null,
            null,
        ).apply {
            val start = org.objectweb.asm.Label()
            val end = org.objectweb.asm.Label()
            visitCode()
            visitLabel(start)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitLdcInsn(":")
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/String",
                "concat",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false,
            )
            visitVarInsn(Opcodes.ILOAD, 2)
            visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/lang/Integer",
                "toString",
                "(I)Ljava/lang/String;",
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
            visitLabel(end)
            visitLocalVariable(
                "this",
                "LNamedHeadVariableOverloadTarget;",
                null,
                start,
                end,
                0,
            )
            visitLocalVariable("target", "Ljava/lang/String;", null, start, end, 1)
            visitLocalVariable("count", "I", null, start, end, 2)
            visitMaxs(2, 3)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun storeVariableOverloadTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(
            Opcodes.V11,
            Opcodes.ACC_PUBLIC,
            "StoreVariableOverloadTarget",
            null,
            "java/lang/Object",
            null,
        )
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "(I)I", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ILOAD, 1)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(1, 2)
            visitEnd()
        }
        cw.visitMethod(
            Opcodes.ACC_PUBLIC,
            "value",
            "(Ljava/lang/String;)Ljava/lang/String;",
            null,
            null,
        ).apply {
            visitCode()
            visitLdcInsn("local-")
            visitVarInsn(Opcodes.ALOAD, 1)
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/String",
                "concat",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false,
            )
            visitVarInsn(Opcodes.ASTORE, 2)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 3)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun loadVariableOverloadTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(
            Opcodes.V11,
            Opcodes.ACC_PUBLIC,
            "LoadVariableOverloadTarget",
            null,
            "java/lang/Object",
            null,
        )
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "(I)I", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ILOAD, 1)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(1, 2)
            visitEnd()
        }
        cw.visitMethod(
            Opcodes.ACC_PUBLIC,
            "value",
            "(Ljava/lang/String;)Ljava/lang/String;",
            null,
            null,
        ).apply {
            visitCode()
            visitLdcInsn("local-")
            visitVarInsn(Opcodes.ALOAD, 1)
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/String",
                "concat",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false,
            )
            visitVarInsn(Opcodes.ASTORE, 2)
            visitVarInsn(Opcodes.ALOAD, 2)
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

    private fun namedLoadVariableTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "NamedLoadVariableTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            val start = org.objectweb.asm.Label()
            val end = org.objectweb.asm.Label()
            visitCode()
            visitLabel(start)
            visitLdcInsn("first")
            visitVarInsn(Opcodes.ASTORE, 1)
            visitLdcInsn("second")
            visitVarInsn(Opcodes.ASTORE, 2)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitLdcInsn(":")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitLabel(end)
            visitInsn(Opcodes.ARETURN)
            visitLocalVariable("other", "Ljava/lang/String;", null, start, end, 1)
            visitLocalVariable("target", "Ljava/lang/String;", null, start, end, 2)
            visitMaxs(2, 3)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun reusedLoadSlotTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "ReusedLoadSlotTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            val firstStart = org.objectweb.asm.Label()
            val firstEnd = org.objectweb.asm.Label()
            val builderStart = org.objectweb.asm.Label()
            val builderEnd = org.objectweb.asm.Label()
            visitCode()
            visitLabel(firstStart)
            visitLdcInsn("old")
            visitVarInsn(Opcodes.ASTORE, 1)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitInsn(Opcodes.POP)
            visitLabel(firstEnd)
            visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder")
            visitInsn(Opcodes.DUP)
            visitLdcInsn("current")
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false)
            visitVarInsn(Opcodes.ASTORE, 1)
            visitLabel(builderStart)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false)
            visitLabel(builderEnd)
            visitInsn(Opcodes.ARETURN)
            visitLocalVariable("oldValue", "Ljava/lang/String;", null, firstStart, firstEnd, 1)
            visitLocalVariable("builder", "Ljava/lang/StringBuilder;", null, builderStart, builderEnd, 1)
            visitMaxs(3, 2)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun loadExpressionValueTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "LoadExpressionValueTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("raw")
            visitVarInsn(Opcodes.ASTORE, 1)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitVarInsn(Opcodes.ASTORE, 2)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitLdcInsn(":")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 3)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun storeExpressionValueTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "StoreExpressionValueTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("raw")
            visitVarInsn(Opcodes.ASTORE, 1)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 2)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun namedStoreVariableTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "NamedStoreVariableTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            val start = org.objectweb.asm.Label()
            val end = org.objectweb.asm.Label()
            visitCode()
            visitLabel(start)
            visitLdcInsn("first")
            visitVarInsn(Opcodes.ASTORE, 1)
            visitLdcInsn("second")
            visitVarInsn(Opcodes.ASTORE, 2)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitLdcInsn(":")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitLabel(end)
            visitInsn(Opcodes.ARETURN)
            visitLocalVariable("other", "Ljava/lang/String;", null, start, end, 1)
            visitLocalVariable("target", "Ljava/lang/String;", null, start, end, 2)
            visitMaxs(2, 3)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun conditionalStoreTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "ConditionalStoreTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("initial")
            visitVarInsn(Opcodes.ASTORE, 1)
            visitLdcInsn("blocked")
            visitVarInsn(Opcodes.ASTORE, 1)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 2)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun namedConditionalStoreTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "NamedConditionalStoreTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            val start = org.objectweb.asm.Label()
            val end = org.objectweb.asm.Label()
            visitCode()
            visitLabel(start)
            visitLdcInsn("first")
            visitVarInsn(Opcodes.ASTORE, 1)
            visitLdcInsn("second")
            visitVarInsn(Opcodes.ASTORE, 2)
            visitLdcInsn("blocked-other")
            visitVarInsn(Opcodes.ASTORE, 1)
            visitLdcInsn("blocked-target")
            visitVarInsn(Opcodes.ASTORE, 2)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitLdcInsn(":")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitLabel(end)
            visitInsn(Opcodes.ARETURN)
            visitLocalVariable("other", "Ljava/lang/String;", null, start, end, 1)
            visitLocalVariable("target", "Ljava/lang/String;", null, start, end, 2)
            visitMaxs(2, 3)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun conditionalStoreParamTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "ConditionalStoreParamTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "(Ljava/lang/String;)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("initial")
            visitVarInsn(Opcodes.ASTORE, 2)
            visitLdcInsn("target")
            visitVarInsn(Opcodes.ASTORE, 2)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 3)
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

    private fun loadArgsOnlyVariableTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "LoadArgsOnlyVariableTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "(Ljava/lang/String;)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("local")
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

    private fun invokeDynamicSliceLoadVariableTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "InvokeDynamicSliceLoadVariableTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        val concatBootstrap =
            org.objectweb.asm.Handle(
                Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/StringConcatFactory",
                "makeConcatWithConstants",
                "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
                    "Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                false,
            )
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "(Ljava/lang/String;)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("pre")
            visitVarInsn(Opcodes.ASTORE, 2)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitVarInsn(Opcodes.ASTORE, 3)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitInvokeDynamicInsn(
                "makeConcatWithConstants",
                "(Ljava/lang/String;)Ljava/lang/String;",
                concatBootstrap,
                "start-\u0001",
            )
            visitInsn(Opcodes.POP)
            visitLdcInsn("inside")
            visitVarInsn(Opcodes.ASTORE, 2)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitVarInsn(Opcodes.ASTORE, 4)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitInvokeDynamicInsn(
                "makeConcatWithConstants",
                "(Ljava/lang/String;)Ljava/lang/String;",
                concatBootstrap,
                "end-\u0001",
            )
            visitInsn(Opcodes.POP)
            visitLdcInsn("outside")
            visitVarInsn(Opcodes.ASTORE, 2)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitVarInsn(Opcodes.ASTORE, 5)
            visitVarInsn(Opcodes.ALOAD, 3)
            visitLdcInsn(":")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 4)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitLdcInsn(":")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 5)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 6)
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

    private fun charSequenceReturnTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "CharSequenceReturnTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/CharSequence;", null, null).apply {
            visitCode()
            visitLdcInsn("value")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun referenceReturnTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "ReferenceReturnTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/util/ArrayList;", null, null).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, "java/util/ArrayList")
            visitInsn(Opcodes.DUP)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 1)
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

    private fun sliceReturnValueTargetBytes(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "SliceReturnValueTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "(I)Ljava/lang/String;", null, null).apply {
            val afterBeforeReturn = org.objectweb.asm.Label()
            val afterInsideReturn = org.objectweb.asm.Label()
            visitCode()
            visitVarInsn(Opcodes.ILOAD, 1)
            visitJumpInsn(Opcodes.IFNE, afterBeforeReturn)
            visitLdcInsn("before")
            visitInsn(Opcodes.ARETURN)
            visitLabel(afterBeforeReturn)
            visitLdcInsn("from")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitVarInsn(Opcodes.ILOAD, 1)
            visitInsn(Opcodes.ICONST_1)
            visitJumpInsn(Opcodes.IF_ICMPNE, afterInsideReturn)
            visitLdcInsn("inside")
            visitInsn(Opcodes.ARETURN)
            visitLabel(afterInsideReturn)
            visitLdcInsn("to")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitLdcInsn("after")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun invokeDynamicSliceReturnValueTargetBytes(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "InvokeDynamicSliceReturnValueTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        val concatBootstrap =
            org.objectweb.asm.Handle(
                Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/StringConcatFactory",
                "makeConcatWithConstants",
                "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
                    "Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                false,
            )
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "(ILjava/lang/String;)Ljava/lang/String;", null, null).apply {
            val afterBeforeReturn = org.objectweb.asm.Label()
            val afterInsideReturn = org.objectweb.asm.Label()
            visitCode()
            visitVarInsn(Opcodes.ILOAD, 1)
            visitJumpInsn(Opcodes.IFNE, afterBeforeReturn)
            visitLdcInsn("before")
            visitInsn(Opcodes.ARETURN)
            visitLabel(afterBeforeReturn)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitInvokeDynamicInsn(
                "makeConcatWithConstants",
                "(Ljava/lang/String;)Ljava/lang/String;",
                concatBootstrap,
                "start-\u0001",
            )
            visitInsn(Opcodes.POP)
            visitVarInsn(Opcodes.ILOAD, 1)
            visitInsn(Opcodes.ICONST_1)
            visitJumpInsn(Opcodes.IF_ICMPNE, afterInsideReturn)
            visitLdcInsn("inside")
            visitInsn(Opcodes.ARETURN)
            visitLabel(afterInsideReturn)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitInvokeDynamicInsn(
                "makeConcatWithConstants",
                "(Ljava/lang/String;)Ljava/lang/String;",
                concatBootstrap,
                "end-\u0001",
            )
            visitInsn(Opcodes.POP)
            visitLdcInsn("after")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun ordinalReturnInferenceTargetBytes(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "OrdinalReturnInferenceTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("single")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
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

    private fun wrapMethodAssignabilityTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "WrapMethodAssignabilityTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "value",
            "(Ljava/lang/String;)Ljava/lang/String;",
            null,
            null,
        ).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitLdcInsn("!")
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/String",
                "concat",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false,
            )
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun ambiguousWrapMethodTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "AmbiguousWrapMethodTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "value",
            "(Ljava/lang/String;)Ljava/lang/String;",
            null,
            null,
        ).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "value",
            "(Ljava/lang/StringBuilder;)Ljava/lang/String;",
            null,
            null,
        ).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "toString",
                "()Ljava/lang/String;",
                false,
            )
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
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

    private fun mixedArgTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "MixedArgTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "echo", "(ILjava/lang/String;)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ILOAD, 1)
            visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "toString", "(I)Ljava/lang/String;", false)
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

    private fun invokeDynamicSliceModifyArgTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "InvokeDynamicSliceModifyArgTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        val concatBootstrap =
            org.objectweb.asm.Handle(
                Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/StringConcatFactory",
                "makeConcatWithConstants",
                "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
                    "Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                false,
            )
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "(Ljava/lang/String;)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("pre-")
            visitLdcInsn("original")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ASTORE, 2)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitInvokeDynamicInsn(
                "makeConcatWithConstants",
                "(Ljava/lang/String;)Ljava/lang/String;",
                concatBootstrap,
                "start-\u0001",
            )
            visitInsn(Opcodes.POP)
            visitLdcInsn("inside-")
            visitLdcInsn("original")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ASTORE, 3)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitInvokeDynamicInsn(
                "makeConcatWithConstants",
                "(Ljava/lang/String;)Ljava/lang/String;",
                concatBootstrap,
                "end-\u0001",
            )
            visitInsn(Opcodes.POP)
            visitLdcInsn("outside-")
            visitLdcInsn("original")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
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

    private fun runtimeExceptionConstructorTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "RuntimeExceptionConstructorTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "message", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, "java/lang/RuntimeException")
            visitInsn(Opcodes.DUP)
            visitLdcInsn("raw")
            visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "java/lang/RuntimeException",
                "<init>",
                "(Ljava/lang/String;)V",
                false,
            )
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/RuntimeException",
                "getMessage",
                "()Ljava/lang/String;",
                false,
            )
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

    private fun constructorModifyArgsTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "ConstructorModifyArgsTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, "java/lang/String")
            visitInsn(Opcodes.DUP)
            visitInsn(Opcodes.ICONST_3)
            visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_CHAR)
            visitInsn(Opcodes.DUP)
            visitInsn(Opcodes.ICONST_0)
            visitIntInsn(Opcodes.BIPUSH, 'a'.code)
            visitInsn(Opcodes.CASTORE)
            visitInsn(Opcodes.DUP)
            visitInsn(Opcodes.ICONST_1)
            visitIntInsn(Opcodes.BIPUSH, 'b'.code)
            visitInsn(Opcodes.CASTORE)
            visitInsn(Opcodes.DUP)
            visitInsn(Opcodes.ICONST_2)
            visitIntInsn(Opcodes.BIPUSH, 'c'.code)
            visitInsn(Opcodes.CASTORE)
            visitInsn(Opcodes.ICONST_0)
            visitInsn(Opcodes.ICONST_3)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/String", "<init>", "([CII)V", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(6, 1)
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

    private fun invokeDynamicSliceModifyArgsTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "InvokeDynamicSliceModifyArgsTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        val concatBootstrap =
            org.objectweb.asm.Handle(
                Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/StringConcatFactory",
                "makeConcatWithConstants",
                "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
                    "Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                false,
            )
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "(Ljava/lang/String;)Ljava/lang/String;", null, null).apply {
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
            visitVarInsn(Opcodes.ASTORE, 2)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitInvokeDynamicInsn(
                "makeConcatWithConstants",
                "(Ljava/lang/String;)Ljava/lang/String;",
                concatBootstrap,
                "start-\u0001",
            )
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
            visitVarInsn(Opcodes.ASTORE, 3)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitInvokeDynamicInsn(
                "makeConcatWithConstants",
                "(Ljava/lang/String;)Ljava/lang/String;",
                concatBootstrap,
                "end-\u0001",
            )
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
            visitMaxs(3, 5)
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

    private fun mixedWrapConditionTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "MixedWrapConditionTarget", null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE, "last", "Ljava/lang/String;", null, null).visitEnd()
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "run", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "MixedWrapConditionTarget",
                "builder",
                "()Ljava/lang/StringBuilder;",
                false,
            )
            visitInsn(Opcodes.POP)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitLdcInsn("raw")
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "MixedWrapConditionTarget",
                "record",
                "(Ljava/lang/String;)V",
                false,
            )
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "MixedWrapConditionTarget", "last", "Ljava/lang/String;")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 1)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "builder", "()Ljava/lang/StringBuilder;", null, null).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder")
            visitInsn(Opcodes.DUP)
            visitLdcInsn("ignored")
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(3, 1)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "record", "(Ljava/lang/String;)V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitFieldInsn(Opcodes.PUTFIELD, "MixedWrapConditionTarget", "last", "Ljava/lang/String;")
            visitInsn(Opcodes.RETURN)
            visitMaxs(2, 2)
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

    private fun wrapConditionInvokeDynamicTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "WrapConditionInvokeDynamicTarget", null, "java/lang/Object", null)
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
            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ILOAD, 1)
            visitInvokeDynamicInsn(
                "record",
                "(Ljava/lang/String;I)V",
                org.objectweb.asm.Handle(
                    Opcodes.H_INVOKESTATIC,
                    "kim/der/asm/FrameworkReliabilityTest",
                    "bootstrapVoidInvokeDynamic",
                    "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)" +
                        "Ljava/lang/invoke/CallSite;",
                    false,
                ),
            )
            visitFieldInsn(Opcodes.GETSTATIC, "WrapConditionInvokeDynamicTarget", "last", "Ljava/lang/String;")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 2)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "record", "(Ljava/lang/String;I)V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ILOAD, 1)
            visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "toString", "(I)Ljava/lang/String;", false)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitFieldInsn(Opcodes.PUTSTATIC, "WrapConditionInvokeDynamicTarget", "last", "Ljava/lang/String;")
            visitInsn(Opcodes.RETURN)
            visitMaxs(2, 2)
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

    private fun sliceWrapConditionFieldTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "SliceWrapConditionFieldTarget", null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE, "name", "Ljava/lang/String;", null, null).visitEnd()
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitLdcInsn("initial")
            visitFieldInsn(Opcodes.PUTFIELD, "SliceWrapConditionFieldTarget", "name", "Ljava/lang/String;")
            visitInsn(Opcodes.RETURN)
            visitMaxs(2, 1)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "writeSelected", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitLdcInsn("pre")
            visitFieldInsn(Opcodes.PUTFIELD, "SliceWrapConditionFieldTarget", "name", "Ljava/lang/String;")
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "SliceWrapConditionFieldTarget", "name", "Ljava/lang/String;")
            visitVarInsn(Opcodes.ASTORE, 1)
            visitLdcInsn(" start ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitLdcInsn("inside")
            visitFieldInsn(Opcodes.PUTFIELD, "SliceWrapConditionFieldTarget", "name", "Ljava/lang/String;")
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "SliceWrapConditionFieldTarget", "name", "Ljava/lang/String;")
            visitVarInsn(Opcodes.ASTORE, 2)
            visitLdcInsn(" end ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitLdcInsn("outside")
            visitFieldInsn(Opcodes.PUTFIELD, "SliceWrapConditionFieldTarget", "name", "Ljava/lang/String;")
            visitVarInsn(Opcodes.ALOAD, 1)
            visitLdcInsn(":")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitLdcInsn(":")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "SliceWrapConditionFieldTarget", "name", "Ljava/lang/String;")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 3)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun sliceWrapConditionArrayTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "SliceWrapConditionArrayTarget", null, "java/lang/Object", null)
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
            visitLdcInsn("initial")
            visitInsn(Opcodes.AASTORE)
            visitFieldInsn(Opcodes.PUTFIELD, "SliceWrapConditionArrayTarget", "names", "[Ljava/lang/String;")
            visitInsn(Opcodes.RETURN)
            visitMaxs(5, 1)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "writeSelected", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "SliceWrapConditionArrayTarget", "names", "[Ljava/lang/String;")
            visitInsn(Opcodes.ICONST_0)
            visitLdcInsn("pre")
            visitInsn(Opcodes.AASTORE)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "SliceWrapConditionArrayTarget", "names", "[Ljava/lang/String;")
            visitInsn(Opcodes.ICONST_0)
            visitInsn(Opcodes.AALOAD)
            visitVarInsn(Opcodes.ASTORE, 1)
            visitLdcInsn(" start ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "SliceWrapConditionArrayTarget", "names", "[Ljava/lang/String;")
            visitInsn(Opcodes.ICONST_0)
            visitLdcInsn("inside")
            visitInsn(Opcodes.AASTORE)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "SliceWrapConditionArrayTarget", "names", "[Ljava/lang/String;")
            visitInsn(Opcodes.ICONST_0)
            visitInsn(Opcodes.AALOAD)
            visitVarInsn(Opcodes.ASTORE, 2)
            visitLdcInsn(" end ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "SliceWrapConditionArrayTarget", "names", "[Ljava/lang/String;")
            visitInsn(Opcodes.ICONST_0)
            visitLdcInsn("outside")
            visitInsn(Opcodes.AASTORE)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitLdcInsn(":")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitLdcInsn(":")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "SliceWrapConditionArrayTarget", "names", "[Ljava/lang/String;")
            visitInsn(Opcodes.ICONST_0)
            visitInsn(Opcodes.AALOAD)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(3, 3)
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

    private fun invokeAssignConditionTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "InvokeAssignConditionTarget", null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PUBLIC, "counter", "I", null, null).visitEnd()
        addDefaultConstructor(cw)
        cw.visitMethod(
            Opcodes.ACC_PUBLIC,
            "value",
            "(ZLjava/lang/String;)Ljava/lang/String;",
            null,
            null,
        ).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "InvokeAssignConditionTarget",
                "produce",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false,
            )
            visitLdcInsn("-done")
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
        cw.visitMethod(Opcodes.ACC_PUBLIC, "produce", "(Ljava/lang/String;)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitInsn(Opcodes.DUP)
            visitFieldInsn(Opcodes.GETFIELD, "InvokeAssignConditionTarget", "counter", "I")
            visitInsn(Opcodes.ICONST_1)
            visitInsn(Opcodes.IADD)
            visitFieldInsn(Opcodes.PUTFIELD, "InvokeAssignConditionTarget", "counter", "I")
            visitVarInsn(Opcodes.ALOAD, 1)
            visitLdcInsn("-")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "InvokeAssignConditionTarget", "counter", "I")
            visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(I)Ljava/lang/String;", false)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(3, 2)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun inferredInvokeExpressionValueTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "InferredInvokeExpressionValueTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "InferredInvokeExpressionValueTarget",
                "builder",
                "()Ljava/lang/StringBuilder;",
                false,
            )
            visitInsn(Opcodes.POP)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "InferredInvokeExpressionValueTarget",
                "text",
                "()Ljava/lang/String;",
                false,
            )
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "builder", "()Ljava/lang/StringBuilder;", null, null).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder")
            visitInsn(Opcodes.DUP)
            visitLdcInsn("ignored")
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(3, 1)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "text", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("raw")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun charSequenceExpressionValueTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "CharSequenceExpressionValueTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/CharSequence;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "CharSequenceExpressionValueTarget",
                "sequence",
                "()Ljava/lang/CharSequence;",
                false,
            )
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "sequence", "()Ljava/lang/CharSequence;", null, null).apply {
            visitCode()
            visitLdcInsn("raw")
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

    private fun invokeDynamicExpressionValueTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "InvokeDynamicExpressionValueTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "(Ljava/lang/String;I)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 1)
            visitVarInsn(Opcodes.ILOAD, 2)
            visitInvokeDynamicInsn(
                "makeConcatWithConstants",
                "(Ljava/lang/String;I)Ljava/lang/String;",
                org.objectweb.asm.Handle(
                    Opcodes.H_INVOKESTATIC,
                    "java/lang/invoke/StringConcatFactory",
                    "makeConcatWithConstants",
                    "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
                        "Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                    false,
                ),
                "\u0001-\u0001",
            )
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 3)
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

    private fun invokeDynamicSliceExpressionValueTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "InvokeDynamicSliceExpressionValueTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        val concatBootstrap =
            org.objectweb.asm.Handle(
                Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/StringConcatFactory",
                "makeConcatWithConstants",
                "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
                    "Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                false,
            )
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "(Ljava/lang/String;)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn(" pre ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "trim", "()Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ASTORE, 2)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitInvokeDynamicInsn(
                "makeConcatWithConstants",
                "(Ljava/lang/String;)Ljava/lang/String;",
                concatBootstrap,
                "start-\u0001",
            )
            visitInsn(Opcodes.POP)
            visitLdcInsn(" inside ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "trim", "()Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ASTORE, 3)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitInvokeDynamicInsn(
                "makeConcatWithConstants",
                "(Ljava/lang/String;)Ljava/lang/String;",
                concatBootstrap,
                "end-\u0001",
            )
            visitInsn(Opcodes.POP)
            visitLdcInsn(" outside ")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "trim", "()Ljava/lang/String;", false)
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

    private fun mixedModifyReceiverTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "MixedModifyReceiverTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder")
            visitInsn(Opcodes.DUP)
            visitLdcInsn("ignored")
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitLdcInsn("original")
            visitLdcInsn("-call")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(3, 1)
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

    private fun invokeDynamicSliceModifyReceiverTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "InvokeDynamicSliceModifyReceiverTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        val concatBootstrap =
            org.objectweb.asm.Handle(
                Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/StringConcatFactory",
                "makeConcatWithConstants",
                "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
                    "Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                false,
            )
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "(Ljava/lang/String;)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("pre")
            visitLdcInsn("-a")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ASTORE, 2)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitInvokeDynamicInsn(
                "makeConcatWithConstants",
                "(Ljava/lang/String;)Ljava/lang/String;",
                concatBootstrap,
                "start-\u0001",
            )
            visitInsn(Opcodes.POP)
            visitLdcInsn("inside")
            visitLdcInsn("-b")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ASTORE, 3)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitInvokeDynamicInsn(
                "makeConcatWithConstants",
                "(Ljava/lang/String;)Ljava/lang/String;",
                concatBootstrap,
                "end-\u0001",
            )
            visitInsn(Opcodes.POP)
            visitLdcInsn("outside")
            visitLdcInsn("-c")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
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

    private fun mixedFieldExpressionValueTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "MixedFieldExpressionValueTarget", null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE, "score", "I", null, null).visitEnd()
        cw.visitField(Opcodes.ACC_PRIVATE, "name", "Ljava/lang/String;", null, null).visitEnd()
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "readSelected", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "MixedFieldExpressionValueTarget", "score", "I")
            visitInsn(Opcodes.POP)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "MixedFieldExpressionValueTarget", "name", "Ljava/lang/String;")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "writeValues", "(Ljava/lang/String;I)V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ILOAD, 2)
            visitFieldInsn(Opcodes.PUTFIELD, "MixedFieldExpressionValueTarget", "score", "I")
            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitFieldInsn(Opcodes.PUTFIELD, "MixedFieldExpressionValueTarget", "name", "Ljava/lang/String;")
            visitInsn(Opcodes.RETURN)
            visitMaxs(2, 3)
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

    private fun accessorSetterTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "AccessorSetterTarget", null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE, "name", "Ljava/lang/String;", null, null).visitEnd()
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitLdcInsn("initial")
            visitFieldInsn(Opcodes.PUTFIELD, "AccessorSetterTarget", "name", "Ljava/lang/String;")
            visitInsn(Opcodes.RETURN)
            visitMaxs(2, 1)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "readName", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "AccessorSetterTarget", "name", "Ljava/lang/String;")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun finalAccessorSetterTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "FinalAccessorSetterTarget", null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL, "name", "Ljava/lang/String;", null, null).visitEnd()
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitLdcInsn("locked")
            visitFieldInsn(Opcodes.PUTFIELD, "FinalAccessorSetterTarget", "name", "Ljava/lang/String;")
            visitInsn(Opcodes.RETURN)
            visitMaxs(2, 1)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "readName", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "FinalAccessorSetterTarget", "name", "Ljava/lang/String;")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun inheritedAccessorTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "InheritedAccessorTarget", null, "java/util/ArrayList", null)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun inheritedStaticAccessorTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(
            Opcodes.V11,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
            "InheritedStaticAccessorTarget",
            null,
            "java/util/Calendar",
            null,
        )
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun inheritedInterfaceAccessorTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(
            Opcodes.V11,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
            "InheritedInterfaceAccessorTarget",
            null,
            "java/lang/Object",
            arrayOf("java/sql/Types"),
        )
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
        cw.visitMethod(Opcodes.ACC_PUBLIC, "failWithParams", "(Ljava/lang/String;I)V", null, null).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException")
            visitInsn(Opcodes.DUP)
            visitLdcInsn("failed")
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false)
            visitInsn(Opcodes.ATHROW)
            visitMaxs(3, 3)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun targetedThrowPointTargetBytes(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "TargetedThrowPointTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "fail", "(Z)V", null, null).apply {
            val skippedThrow = org.objectweb.asm.Label()
            visitCode()
            visitVarInsn(Opcodes.ILOAD, 1)
            visitJumpInsn(Opcodes.IFEQ, skippedThrow)
            visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException")
            visitInsn(Opcodes.DUP)
            visitLdcInsn("state")
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false)
            visitInsn(Opcodes.ATHROW)
            visitLabel(skippedThrow)
            visitTypeInsn(Opcodes.NEW, "java/lang/UnsupportedOperationException")
            visitInsn(Opcodes.DUP)
            visitLdcInsn("unsupported")
            visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "java/lang/UnsupportedOperationException",
                "<init>",
                "(Ljava/lang/String;)V",
                false,
            )
            visitInsn(Opcodes.ATHROW)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun conditionalThrowTargetBytes(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "ConditionalThrowTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "choose", "(ZZ)Ljava/lang/String;", null, null).apply {
            val afterThrow = org.objectweb.asm.Label()
            visitCode()
            visitVarInsn(Opcodes.ILOAD, 1)
            visitJumpInsn(Opcodes.IFNE, afterThrow)
            visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException")
            visitInsn(Opcodes.DUP)
            visitLdcInsn("state")
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false)
            visitInsn(Opcodes.ATHROW)
            visitLabel(afterThrow)
            visitLdcInsn("after")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun jumpOperationTargetBytes(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "JumpOperationTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "choose", "(IZ)Ljava/lang/String;", null, null).apply {
            val negative = org.objectweb.asm.Label()
            visitCode()
            visitVarInsn(Opcodes.ILOAD, 1)
            visitJumpInsn(Opcodes.IFLE, negative)
            visitLdcInsn("positive")
            visitInsn(Opcodes.ARETURN)
            visitLabel(negative)
            visitLdcInsn("negative")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun switchSelectorTargetBytes(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "SwitchSelectorTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "choose", "(IZ)Ljava/lang/String;", null, null).apply {
            val zero = org.objectweb.asm.Label()
            val one = org.objectweb.asm.Label()
            val two = org.objectweb.asm.Label()
            val fallback = org.objectweb.asm.Label()
            visitCode()
            visitVarInsn(Opcodes.ILOAD, 1)
            visitTableSwitchInsn(0, 2, fallback, zero, one, two)
            visitLabel(zero)
            visitLdcInsn("zero")
            visitInsn(Opcodes.ARETURN)
            visitLabel(one)
            visitLdcInsn("one")
            visitInsn(Opcodes.ARETURN)
            visitLabel(two)
            visitLdcInsn("two")
            visitInsn(Opcodes.ARETURN)
            visitLabel(fallback)
            visitLdcInsn("fallback")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun lookupSwitchSelectorTargetBytes(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "LookupSwitchSelectorTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "choose", "(IZ)Ljava/lang/String;", null, null).apply {
            val ten = org.objectweb.asm.Label()
            val twenty = org.objectweb.asm.Label()
            val thirty = org.objectweb.asm.Label()
            val fallback = org.objectweb.asm.Label()
            visitCode()
            visitVarInsn(Opcodes.ILOAD, 1)
            visitLookupSwitchInsn(fallback, intArrayOf(10, 20, 30), arrayOf(ten, twenty, thirty))
            visitLabel(ten)
            visitLdcInsn("ten")
            visitInsn(Opcodes.ARETURN)
            visitLabel(twenty)
            visitLdcInsn("twenty")
            visitInsn(Opcodes.ARETURN)
            visitLabel(thirty)
            visitLdcInsn("thirty")
            visitInsn(Opcodes.ARETURN)
            visitLabel(fallback)
            visitLdcInsn("fallback")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun sliceSwitchSelectorTargetBytes(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "SliceSwitchSelectorTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "choose", "(IZ)Ljava/lang/String;", null, null).apply {
            val firstZero = org.objectweb.asm.Label()
            val firstOne = org.objectweb.asm.Label()
            val firstFallback = org.objectweb.asm.Label()
            val afterFirst = org.objectweb.asm.Label()
            val secondZero = org.objectweb.asm.Label()
            val secondOne = org.objectweb.asm.Label()
            val secondFallback = org.objectweb.asm.Label()
            val afterSecond = org.objectweb.asm.Label()
            visitCode()
            visitVarInsn(Opcodes.ILOAD, 1)
            visitTableSwitchInsn(0, 1, firstFallback, firstZero, firstOne)
            visitLabel(firstZero)
            visitLdcInsn("zero")
            visitVarInsn(Opcodes.ASTORE, 3)
            visitJumpInsn(Opcodes.GOTO, afterFirst)
            visitLabel(firstOne)
            visitLdcInsn("one")
            visitVarInsn(Opcodes.ASTORE, 3)
            visitJumpInsn(Opcodes.GOTO, afterFirst)
            visitLabel(firstFallback)
            visitLdcInsn("fallback")
            visitVarInsn(Opcodes.ASTORE, 3)
            visitLabel(afterFirst)
            visitLdcInsn("start-boundary")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitVarInsn(Opcodes.ILOAD, 1)
            visitTableSwitchInsn(0, 1, secondFallback, secondZero, secondOne)
            visitLabel(secondZero)
            visitLdcInsn("zero")
            visitVarInsn(Opcodes.ASTORE, 4)
            visitJumpInsn(Opcodes.GOTO, afterSecond)
            visitLabel(secondOne)
            visitLdcInsn("one")
            visitVarInsn(Opcodes.ASTORE, 4)
            visitJumpInsn(Opcodes.GOTO, afterSecond)
            visitLabel(secondFallback)
            visitLdcInsn("fallback")
            visitVarInsn(Opcodes.ASTORE, 4)
            visitLabel(afterSecond)
            visitLdcInsn("end-boundary")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.POP)
            visitVarInsn(Opcodes.ALOAD, 3)
            visitLdcInsn(":")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitVarInsn(Opcodes.ALOAD, 4)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
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
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "ConstructorInvokerTarget", null, "java/util/ArrayList", arrayOf("java/lang/Runnable"))
        cw.visitField(Opcodes.ACC_PRIVATE, "value", "Ljava/lang/String;", null, null).visitEnd()
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/String;)V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitFieldInsn(Opcodes.PUTFIELD, "ConstructorInvokerTarget", "value", "Ljava/lang/String;")
            visitInsn(Opcodes.RETURN)
            visitMaxs(2, 2)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null).apply {
            visitCode()
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 1)
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

    private fun privateInterfaceInvokerTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(
            Opcodes.V11,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
            "PrivateInterfaceInvokerTarget",
            null,
            "java/lang/Object",
            null,
        )
        cw.visitMethod(Opcodes.ACC_PRIVATE, "secret", "(Ljava/lang/String;)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 1)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 2)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun interfaceDefaultInvokerTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "InterfaceDefaultInvokerTarget", null, "java/lang/Object", arrayOf("java/lang/Iterable"))
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "iterator", "()Ljava/util/Iterator;", null, null).apply {
            visitCode()
            visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "emptyIterator", "()Ljava/util/Iterator;", false)
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

    private fun mixedNewExpressionValueTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "MixedNewExpressionValueTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, "java/lang/StringBuffer")
            visitInsn(Opcodes.DUP)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuffer", "<init>", "()V", false)
            visitInsn(Opcodes.POP)
            visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder")
            visitInsn(Opcodes.DUP)
            visitLdcInsn("original")
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

    private fun multiCastInstructionTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "MultiCastInstructionTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "cast", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 1)
            visitTypeInsn(Opcodes.CHECKCAST, "java/lang/StringBuilder")
            visitInsn(Opcodes.POP)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 3)
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

    private fun multiInstanceofTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "MultiInstanceofTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "isString", "(Ljava/lang/Object;Ljava/lang/Object;)Z", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 1)
            visitTypeInsn(Opcodes.INSTANCEOF, "java/lang/StringBuilder")
            visitInsn(Opcodes.POP)
            visitVarInsn(Opcodes.ALOAD, 2)
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

    private fun invokeStringTargetBytes(includeDirectMarker: Boolean = true): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "InvokeStringTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "target",
            "(Ljava/lang/String;)V",
            null,
            null,
        ).apply {
            visitCode()
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 1)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null).apply {
            visitCode()
            visitLdcInsn("other")
            visitMethodInsn(Opcodes.INVOKESTATIC, "InvokeStringTarget", "target", "(Ljava/lang/String;)V", false)
            if (includeDirectMarker) {
                visitLdcInsn("marker")
                visitMethodInsn(Opcodes.INVOKESTATIC, "InvokeStringTarget", "target", "(Ljava/lang/String;)V", false)
            }
            visitLdcInsn("marker")
            visitVarInsn(Opcodes.ASTORE, 1)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitMethodInsn(Opcodes.INVOKESTATIC, "InvokeStringTarget", "target", "(Ljava/lang/String;)V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 2)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun invokeStringLongArgumentTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "InvokeStringLongArgumentTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "target",
            "(JLjava/lang/String;)V",
            null,
            null,
        ).apply {
            visitCode()
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 3)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null).apply {
            visitCode()
            visitInsn(Opcodes.LCONST_1)
            visitLdcInsn("marker")
            visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "InvokeStringLongArgumentTarget",
                "target",
                "(JLjava/lang/String;)V",
                false,
            )
            visitInsn(Opcodes.RETURN)
            visitMaxs(3, 1)
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

    private fun invokeDynamicSliceConstantTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "InvokeDynamicSliceConstantTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        val concatBootstrap =
            org.objectweb.asm.Handle(
                Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/StringConcatFactory",
                "makeConcatWithConstants",
                "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
                    "Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                false,
            )
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "(Ljava/lang/String;)Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("target")
            visitVarInsn(Opcodes.ASTORE, 2)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitInvokeDynamicInsn(
                "makeConcatWithConstants",
                "(Ljava/lang/String;)Ljava/lang/String;",
                concatBootstrap,
                "start-\u0001",
            )
            visitInsn(Opcodes.POP)
            visitLdcInsn("target")
            visitVarInsn(Opcodes.ASTORE, 3)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitInvokeDynamicInsn(
                "makeConcatWithConstants",
                "(Ljava/lang/String;)Ljava/lang/String;",
                concatBootstrap,
                "end-\u0001",
            )
            visitInsn(Opcodes.POP)
            visitLdcInsn("target")
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

    private fun constantBoundarySliceConstantTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "ConstantBoundarySliceConstantTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("target")
            visitVarInsn(Opcodes.ASTORE, 1)
            visitLdcInsn("start-boundary")
            visitInsn(Opcodes.POP)
            visitLdcInsn("target")
            visitVarInsn(Opcodes.ASTORE, 2)
            visitLdcInsn("end-boundary")
            visitInsn(Opcodes.POP)
            visitLdcInsn("target")
            visitVarInsn(Opcodes.ASTORE, 3)
            appendThreeStringsWithColon()
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 4)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun fieldBoundarySliceConstantTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "FieldBoundarySliceConstantTarget", null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE, "marker", "Ljava/lang/String;", null, null).visitEnd()
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("target")
            visitVarInsn(Opcodes.ASTORE, 1)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "FieldBoundarySliceConstantTarget", "marker", "Ljava/lang/String;")
            visitInsn(Opcodes.POP)
            visitLdcInsn("target")
            visitVarInsn(Opcodes.ASTORE, 2)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, "FieldBoundarySliceConstantTarget", "marker", "Ljava/lang/String;")
            visitInsn(Opcodes.POP)
            visitLdcInsn("target")
            visitVarInsn(Opcodes.ASTORE, 3)
            appendThreeStringsWithColon()
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 4)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun fieldAssignBoundarySliceConstantTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "FieldAssignBoundarySliceConstantTarget", null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE, "marker", "Ljava/lang/String;", null, null).visitEnd()
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("target")
            visitVarInsn(Opcodes.ASTORE, 1)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitLdcInsn("start-marker")
            visitFieldInsn(Opcodes.PUTFIELD, "FieldAssignBoundarySliceConstantTarget", "marker", "Ljava/lang/String;")
            visitLdcInsn("target")
            visitVarInsn(Opcodes.ASTORE, 2)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitLdcInsn("end-marker")
            visitFieldInsn(Opcodes.PUTFIELD, "FieldAssignBoundarySliceConstantTarget", "marker", "Ljava/lang/String;")
            visitLdcInsn("target")
            visitVarInsn(Opcodes.ASTORE, 3)
            appendThreeStringsWithColon()
            visitInsn(Opcodes.ARETURN)
            visitMaxs(2, 4)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun fieldAssignToValueBoundarySliceConstantTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(
            Opcodes.V11,
            Opcodes.ACC_PUBLIC,
            "FieldAssignToValueBoundarySliceConstantTarget",
            null,
            "java/lang/Object",
            null,
        )
        cw.visitField(Opcodes.ACC_PRIVATE, "marker", "Ljava/lang/String;", null, null).visitEnd()
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("target")
            visitVarInsn(Opcodes.ASTORE, 1)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitLdcInsn("start-marker")
            visitFieldInsn(Opcodes.PUTFIELD, "FieldAssignToValueBoundarySliceConstantTarget", "marker", "Ljava/lang/String;")
            visitLdcInsn("target")
            visitVarInsn(Opcodes.ASTORE, 2)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitLdcInsn("target")
            visitFieldInsn(Opcodes.PUTFIELD, "FieldAssignToValueBoundarySliceConstantTarget", "marker", "Ljava/lang/String;")
            visitLdcInsn("target")
            visitVarInsn(Opcodes.ASTORE, 3)
            appendThreeStringsWithColon()
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

    private fun typedNullConstantTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "TypedNullConstantTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitInsn(Opcodes.ACONST_NULL)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun trueBooleanConstantTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "TrueBooleanConstantTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Z", null, null).apply {
            visitCode()
            visitInsn(Opcodes.ICONST_1)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun falseBooleanConstantTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "FalseBooleanConstantTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Z", null, null).apply {
            visitCode()
            visitInsn(Opcodes.ICONST_0)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun classLiteralConstantTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "ClassLiteralConstantTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/Class;", null, null).apply {
            visitCode()
            visitLdcInsn(org.objectweb.asm.Type.getType("Ljava/lang/String;"))
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun methodTypeConstantTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "MethodTypeConstantTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(
            Opcodes.ACC_PUBLIC,
            "value",
            "()Ljava/lang/invoke/MethodType;",
            null,
            null,
        ).apply {
            visitCode()
            visitLdcInsn(org.objectweb.asm.Type.getMethodType("(I)Ljava/lang/String;"))
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun methodHandleConstantTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "MethodHandleConstantTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(
            Opcodes.ACC_PUBLIC,
            "value",
            "()Ljava/lang/invoke/MethodHandle;",
            null,
            null,
        ).apply {
            visitCode()
            visitLdcInsn(
                org.objectweb.asm.Handle(
                    Opcodes.H_INVOKESTATIC,
                    "java/lang/String",
                    "valueOf",
                    "(I)Ljava/lang/String;",
                    false,
                ),
            )
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun dynamicConstantTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "DynamicConstantTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn(
                org.objectweb.asm.ConstantDynamic(
                    "dynamicText",
                    "Ljava/lang/String;",
                    org.objectweb.asm.Handle(
                        Opcodes.H_INVOKESTATIC,
                        "java/lang/invoke/ConstantBootstraps",
                        "explicitCast",
                        "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/Class;" +
                            "Ljava/lang/Object;)Ljava/lang/Object;",
                        false,
                    ),
                    "original",
                ),
            )
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

    private fun mixedNumericConstantTargetBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "MixedNumericConstantTarget", null, "java/lang/Object", null)
        addDefaultConstructor(cw)
        cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()I", null, null).apply {
            visitCode()
            visitInsn(Opcodes.LCONST_1)
            visitVarInsn(Opcodes.LSTORE, 1)
            visitInsn(Opcodes.ICONST_1)
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

    private fun MethodVisitor.appendThreeStringsWithColon() {
        visitVarInsn(Opcodes.ALOAD, 1)
        visitLdcInsn(":")
        visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
        visitVarInsn(Opcodes.ALOAD, 2)
        visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
        visitLdcInsn(":")
        visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
        visitVarInsn(Opcodes.ALOAD, 3)
        visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
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

    companion object {
        @JvmStatic
        fun bootstrapVoidInvokeDynamic(
            lookup: java.lang.invoke.MethodHandles.Lookup,
            name: String,
            type: java.lang.invoke.MethodType,
        ): java.lang.invoke.CallSite =
            java.lang.invoke.ConstantCallSite(lookup.findStatic(lookup.lookupClass(), name, type))
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

    private fun loadClasses(
        primaryClassName: String,
        classBytes: Map<String, ByteArray>,
    ): Class<*> {
        val loader =
            object : ClassLoader(Thread.currentThread().contextClassLoader) {
                override fun findClass(name: String): Class<*> {
                    val bytes = classBytes[name] ?: throw ClassNotFoundException(name)
                    return defineClass(name, bytes, 0, bytes.size)
                }
            }
        return loader.loadClass(primaryClassName)
    }

    private fun transformAndLoadTestFixture(): Class<*> {
        val fixtureLoader = testFixtureClassLoader("Test", "TestParent", "TestInterface")
        val transformed = AsmProcessor().transform("Test", testFixtureClassBytes("Test"), fixtureLoader)
        return loadClasses(
            "Test",
            mapOf(
                "Test" to transformed,
                "TestParent" to testFixtureClassBytes("TestParent"),
                "TestInterface" to testFixtureClassBytes("TestInterface"),
                "TestFunctionalInterface" to testFixtureClassBytes("TestFunctionalInterface"),
                "Test\$CustomException" to testFixtureClassBytes("Test\$CustomException"),
                "Test\$InnerClass" to testFixtureClassBytes("Test\$InnerClass"),
                "Test\$StaticInnerClass" to testFixtureClassBytes("Test\$StaticInnerClass"),
                "Test\$TestEnum" to testFixtureClassBytes("Test\$TestEnum"),
            ),
        )
    }

    private fun testFixtureClassBytes(className: String): ByteArray {
        val resourcePath = "test/$className.class"
        return javaClass.classLoader.getResourceAsStream(resourcePath)?.use { it.readBytes() }
            ?: error("Missing test fixture class resource: $resourcePath")
    }

    private fun testFixtureClassLoader(vararg classNames: String): ClassLoader {
        val classes = classNames.associateWith { testFixtureClassBytes(it) }
        return object : ClassLoader(Thread.currentThread().contextClassLoader) {
            override fun getResourceAsStream(name: String): java.io.InputStream? {
                val fixtureClassName = name.removeSuffix(".class").takeIf { name.endsWith(".class") && '/' !in name }
                val bytes = fixtureClassName?.let(classes::get)
                return if (bytes != null) {
                    java.io.ByteArrayInputStream(bytes)
                } else {
                    super.getResourceAsStream(name)
                }
            }
        }
    }
}




