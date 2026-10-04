/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */
package kim.der.asm

import kim.der.asm.api.annotation.*
import kim.der.asm.fixture.AnnotationUsageFixtures.*
import kim.der.asm.fixture.KotlinAnnotationUsageFixtures
import kim.der.asm.transformer.AsmProcessor
import kim.der.asm.transformer.AsmTransformException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.objectweb.asm.ClassReader
import org.objectweb.asm.util.CheckClassAdapter
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.Opcodes
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path

/**
 * 可执行的注解用法对照：对相同目标使用不同注解，比较返回值、原调用次数和局部变量状态。
 *
 * 全部注解的测试入口见 src/test/ANNOTATION_USAGE.md；这里补相近注解之间的差异，
 * 已有的命中数、定位、成员别名与错误路径契约继续由 FrameworkReliabilityTest 等测试维护。
 * 每次转换后立即定义并调用真实 JVM 类，避免仅检查生成节点而遗漏非法 classfile。
 */
class AnnotationUsageDifferencesTest {
    @BeforeEach
    @AfterEach
    fun clearRegistry() {
        AsmRegistry.clear()
        CaptureLocal.seen = null
        ReplaceReceiver.replacement = Receiver("other:")
    }

    /** 替换、改结果、包裹和条件保留应显式区分原操作是否执行、执行几次。 */
    @ParameterizedTest
    @MethodSource("callCases")
    fun callAnnotationsHaveDifferentSideEffects(case: CallCase) {
        val type = transform(case.mixin, case.target)
        val target = type.getConstructor().newInstance()
        assertEquals(case.result, type.getMethod("run").invoke(target))
        assertEquals(case.calls, (type.getField("receiver").get(target) as Receiver).calls)
        assertEquals(case.outer, type.getField("before").getInt(target))
        assertEquals(case.outer, type.getField("after").getInt(target))
        if (case.mixin == ReplaceReceiver::class.java) assertEquals(1, ReplaceReceiver.replacement.calls)
    }

    /** LOAD 写回槽位会影响后续读取；表达式只改一次栈值；Local 仅观察。 */
    @ParameterizedTest
    @MethodSource("localCases")
    fun localAnnotationsDifferInSlotWriteback(case: LocalCase) {
        val type = transform(case.mixin, case.target)
        assertEquals(case.result, type.getMethod("twice", String::class.java).invoke(type.getConstructor().newInstance(), "raw"))
        if (case.mixin == CaptureLocal::class.java) assertEquals("raw", CaptureLocal.seen)
    }

    /** 追加的目标参数来自同一槽位，可直接观察 STORE handler 在写入前还是写入后执行。 */
    @ParameterizedTest
    @MethodSource("storeCases")
    fun storeAnnotationsObserveDifferentSlotTiming(case: LocalCase) {
        val type = transform(case.mixin, Calls::class.java)
        assertEquals(case.result, type.getMethod("store", String::class.java).invoke(type.getConstructor().newInstance(), "raw"))
    }

    /** 普通 Copy 复用冲突方法，Unique 为 helper 改名，Overwrite 才改写目标原方法。 */
    @ParameterizedTest
    @MethodSource("copyCases")
    fun copyUniqueAndOverwriteHandleConflictsDifferently(case: LocalCase) {
        val type = transform(case.mixin, Helpers::class.java)
        val target = type.getConstructor().newInstance()
        assertEquals(case.result, type.getMethod("run").invoke(target))
        assertEquals(if (case.mixin == OverwriteHelper::class.java) "overwrite" else "target", type.getMethod("value").invoke(target))
        if (case.mixin == UniqueHelper::class.java) {
            assertTrue(type.declaredMethods.any { it.isSynthetic && Modifier.isPrivate(it.modifiers) })
        }
    }

    /** 无冲突的 Unique 沿用 Copy 的 public 契约，不能一律声明为 private synthetic。 */
    @Test
    fun uniqueWithoutConflictKeepsPublicCopy() {
        val type = transform(UniqueFresh::class.java, Helpers::class.java)
        val helper = type.getMethod("fresh")
        assertEquals("fresh", helper.invoke(type.getConstructor().newInstance()))
        assertTrue(Modifier.isPublic(helper.modifiers))
    }

    /** RemoveField 仅小写首字母；Accessor 使用保留连续大写前缀的 JavaBeans 命名规则。 */
    @Test
    fun removeFieldAndAccessorInferAcronymsDifferently() {
        val type = transform(AcronymMembers::class.java, Helpers::class.java)
        assertThrows(NoSuchFieldException::class.java) { type.getField("uRL") }
        val target = type.getConstructor().newInstance()
        type.getField("URL").set(target, "retained")
        assertEquals("retained", type.getMethod("getURL").invoke(target))
    }

    /** 浏览路径扁平化不应改写内部类名；联合注解分类中的产物必须一致。 */
    @Test
    fun exportedClassesUseAnnotationFoldersWithoutPackageDirectories() {
        transform(AcronymMembers::class.java, Helpers::class.java)
        val filename = "AcronymMembers__AnnotationUsageFixtures\$Helpers.class"
        val accessor = Files.readAllBytes(Path.of("src/test/resources/out/java/Accessor", filename))
        val removal = Files.readAllBytes(Path.of("src/test/resources/out/java/RemoveField", filename))
        assertEquals(HELPERS, ClassReader(accessor).className)
        assertArrayEquals(accessor, removal)
    }

    /** 删除接口不删实现；重新添加接口后，同一 run 方法仍能通过接口真实调用。 */
    @Test
    fun interfaceDeclarationsDoNotCreateOrDeleteImplementation() {
        val removed = transform(RemoveRunnable::class.java, RunnableTarget::class.java)
        val instance = removed.getConstructor().newInstance()
        assertFalse(instance is Runnable)
        removed.getMethod("run").invoke(instance)
        assertEquals(1, removed.getField("calls").getInt(instance))
        AsmRegistry.clear()
        AsmRegistry.register(RemoveRunnable::class.java)
        AsmRegistry.register(AddRunnable::class.java)
        val restored = define(RunnableTarget::class.java,
            transformBytes(RunnableTarget::class.java, RemoveRunnable::class.java, AddRunnable::class.java))
        val runnable = restored.getConstructor().newInstance() as Runnable
        runnable.run()
        assertEquals(1, restored.getField("calls").getInt(runnable))
    }

    /** final 字段、类 abstract 标志的改动不依赖是否有普通方法可被替换。 */
    @Test
    fun replaceAllWritesStructuralChangesWithoutOrdinaryMethods() {
        val type = transform(ReplaceConstructorOnly::class.java, ConstructorOnly::class.java)
        assertFalse(Modifier.isFinal(type.getField("value").modifiers))
        assertEquals(7, type.getField("value").getInt(type.getConstructor().newInstance()))
        assertFalse(Modifier.isAbstract(type.modifiers))
    }

    /** 接口字段必须保留 public static final；Mutable 对接口声明不改变修饰符。 */
    @Test
    fun mutableDoesNotInvalidateFieldsOnTargetInterface() {
        val type = transform(MutableInterface::class.java, Constants::class.java)
        assertTrue(type.isInterface)
        assertTrue(Modifier.isFinal(type.getField("VALUE").modifiers))
        assertEquals(7, type.getField("VALUE").getInt(null))
    }

    /** 同一接口目标允许生成 getter，却必须在转换阶段拒绝 setter，即使声明 Mutable。 */
    @Test
    fun interfaceAccessorGetterWorksButSetterFailsDuringTransform() {
        val type = transform(InterfaceGetter::class.java, Constants::class.java)
        assertEquals(7, type.getMethod("read").invoke(null))
        AsmRegistry.clear()
        AsmRegistry.register(InterfaceSetter::class.java)
        val error = assertThrows(AsmTransformException::class.java) { transformBytes(Constants::class.java) }
        assertTrue(error.cause!!.message!!.contains("interface field"), error.toString())
    }

    /** 两个标记同时出现时，Final 明确优先；这是声明修饰符而非初始化表达式复制。 */
    @Test
    fun finalWinsWhenCombinedWithMutable() {
        val type = transform(BothModifiers::class.java, ConstructorOnly::class.java)
        assertTrue(Modifier.isFinal(type.getField("value").modifiers))
    }

    /** 未实现偏移的处理器不能静默忽略 by，必须在改写前报告配置错误。 */
    @ParameterizedTest
    @MethodSource("unsupportedOffsets")
    fun unsupportedAtOffsetsFailDuringTransform(mixin: Class<*>) {
        AsmRegistry.register(mixin)
        val error = assertThrows(AsmTransformException::class.java) { transformBytes(Calls::class.java) }
        assertTrue(error.cause!!.message!!.contains("by"), error.toString())
    }

    /** 删除字段和方法后，剩余业务方法仍能真实执行。 */
    @Test
    fun deletionAnnotationsRemoveOnlySelectedMembers() {
        for (mixin in listOf(DeleteMembers::class.java, RemoveLegacy::class.java)) {
            AsmRegistry.clear()
            val type = transform(mixin, Structure::class.java)
            assertThrows(NoSuchMethodException::class.java) { type.getMethod("legacy") }
            if (mixin == DeleteMembers::class.java) {
                assertThrows(NoSuchFieldException::class.java) { type.getField("legacyField") }
            } else {
                assertNotNull(type.getField("legacyField"))
            }
            assertEquals("new", type.getMethod("version").invoke(type.getConstructor().newInstance()))
        }
    }

    /** 同时检查方法标志、同步块指令与副作用，避免只删 ACC_SYNCHRONIZED 的弱断言。 */
    @Test
    fun removeSynchronizedPreservesBodyAndRemovesMonitors() {
        AsmRegistry.register(Unlock::class.java)
        val bytes = transformBytes(Structure::class.java, Unlock::class.java)
        val node = ClassNode().also { ClassReader(bytes).accept(it, 0) }
        val method = node.methods.single { it.name == "locked" }
        assertEquals(0, method.access and Opcodes.ACC_SYNCHRONIZED)
        assertFalse(method.instructions.any { it.opcode == Opcodes.MONITORENTER || it.opcode == Opcodes.MONITOREXIT })
        val type = define(Structure::class.java, bytes)
        val instance = type.getConstructor().newInstance()
        assertEquals(1, type.getMethod("locked").invoke(instance))
        assertEquals(2, type.getMethod("locked").invoke(instance))
    }

    /** 桥接调用私有实现，静态工厂创建转换后的目标类而不是原 classloader 中的类。 */
    @Test
    fun invokerBridgesPrivateMethodAndConstructor() {
        val type = transform(BridgeMembers::class.java, Structure::class.java)
        val instance = type.getMethod("create").invoke(null)
        assertSame(type, instance.javaClass)
        assertEquals("secret:x", type.getMethod("callSecret", String::class.java).invoke(instance, "x"))
        assertTrue(Modifier.isPrivate(type.getDeclaredMethod("secret", String::class.java).modifiers))
    }

    /** 同值常量只修改切片内一次；边界调用和切片外常量均保留。 */
    @Test
    fun constantSlicePreservesOutsideValuesAndBoundaryCalls() {
        val type = transform(SlicedConstant::class.java, Structure::class.java)
        val instance = type.getConstructor().newInstance()
        assertEquals("rawpatchedraw", type.getMethod("constants").invoke(instance))
        assertEquals(2, type.getField("calls").getInt(instance))
    }

    /** 旧版常量按零命中计入组，新版候选必须贡献唯一一次命中。 */
    @Test
    fun groupAcceptsMissingConstantCandidate() {
        val type = transform(VersionCandidates::class.java, Structure::class.java)
        assertEquals("patched", type.getMethod("version").invoke(type.getConstructor().newInstance()))
    }

    /** Group 计数发生在目标方法解析之后，不能把 ModifyConstant 的缺失方法变为可选。 */
    @Test
    fun groupDoesNotMakeModifyConstantTargetMethodOptional() {
        AsmRegistry.register(MissingVersionMethod::class.java)
        val error = assertThrows(AsmTransformException::class.java) { transformBytes(Structure::class.java) }
        assertTrue(error.cause!!.message!!.contains("Cannot find target method oldVersion()Ljava/lang/String;"))
    }

    /** 新增字段只复制声明；复制体通过 Shadow 读取目标状态，两个实例互不共享。 */
    @Test
    fun addedFieldAndShadowBelongToTargetInstance() {
        val type = transform(AddState::class.java, Structure::class.java)
        val first = type.getConstructor().newInstance()
        val second = type.getConstructor().newInstance()
        type.getField("calls").setInt(first, 3)
        assertEquals(0, type.getField("extra").getInt(first))
        assertEquals(3, type.getMethod("accumulate").invoke(first))
        assertEquals(6, type.getMethod("accumulate").invoke(first))
        assertEquals(0, type.getField("extra").getInt(second))
    }

    /** 全方法规则忽略 Redirect.method，仍保留未匹配方法的业务行为。 */
    @Test
    fun redirectAllAppliesRuleWithoutMethodSelector() {
        val type = transform(RedirectConstants::class.java, Structure::class.java)
        val instance = type.getConstructor().newInstance()
        assertEquals("redirected", type.getMethod("version").invoke(instance))
        assertEquals("legacy", type.getMethod("legacy").invoke(instance))
    }

    private fun transform(mixin: Class<*>, target: Class<*>): Class<*> {
        AsmRegistry.register(mixin)
        return define(target, transformBytes(target, mixin))
    }

    private fun transformBytes(target: Class<*>, vararg mixins: Class<*>): ByteArray {
        val name = target.name.replace('.', '/')
        val language = if (target.name.startsWith(KotlinAnnotationUsageFixtures::class.java.name)) "kotlin" else "java"
        val resource = "test/$language/$name.class"
        val bytes = javaClass.classLoader.getResourceAsStream(resource)?.use { it.readBytes() }
            ?: error("Missing compiled fixture: $resource; run prepareMixinFixtures")
        val transformed = AsmProcessor().transform(name, bytes, javaClass.classLoader)
        val diagnostics = StringWriter()
        CheckClassAdapter.verify(ClassReader(transformed), javaClass.classLoader, false, PrintWriter(diagnostics))
        assertEquals("", diagnostics.toString(), "ASM 数据流校验: $name")
        val scenario = mixins.joinToString("+") { it.simpleName }.ifEmpty { "combined" }
        fun annotationNames(annotation: Annotation): List<String> {
            val type = annotation.annotationClass.java
            if (type.packageName != AsmMixin::class.java.packageName) return emptyList()
            // 仅收录显式定位的嵌套注解，默认空 Slice/At 不代表额外用途。
            val nested = type.declaredMethods.filter { it.returnType.isAnnotation }.flatMap {
                val value = it.invoke(annotation) as Annotation
                if (value == At() || value == Slice()) emptyList() else annotationNames(value)
            }
            return listOf(type.simpleName) + nested
        }
        val categories = mixins.flatMap { mixin ->
            (mixin.annotations.toList() + mixin.declaredFields.flatMap { it.annotations.toList() } +
                mixin.declaredMethods.flatMap { it.annotations.toList() + it.parameterAnnotations.flatMap { a -> a.toList() } })
                .flatMap(::annotationNames)
        }.toSortedSet().ifEmpty { sortedSetOf("Combined") }
        // 包路径只用于资源寻址和 JVM 类名；浏览产物按注解分组，场景名避免同目标相互覆盖。
        var exported = transformed
        for (category in categories) {
            val output = Path.of("src/test/resources/out", language, category, "${scenario}__${name.substringAfterLast('/')}.class")
            Files.createDirectories(output.parent)
            Files.write(output, transformed)
            exported = Files.readAllBytes(output)
            assertArrayEquals(transformed, exported)
        }
        return exported
    }

    private fun define(target: Class<*>, bytes: ByteArray): Class<*> =
        object : ClassLoader(javaClass.classLoader) {
            fun defineTarget(): Class<*> = defineClass(target.name, bytes, 0, bytes.size)
        }.defineTarget()

    data class CallCase(
        val mixin: Class<*>,
        val result: String?,
        val calls: Int,
        val outer: Int = 1,
        val target: Class<*> = Calls::class.java,
    )
    data class LocalCase(val mixin: Class<*>, val result: String, val target: Class<*> = Calls::class.java)

    companion object {
        const val CALLS = "kim/der/asm/fixture/AnnotationUsageFixtures\$Calls"
        const val KOTLIN_CALLS = "kim/der/asm/fixture/KotlinAnnotationUsageFixtures\$Calls"
        const val CONSTANTS = "kim/der/asm/fixture/AnnotationUsageFixtures\$Constants"
        const val CONSTRUCTOR = "kim/der/asm/fixture/AnnotationUsageFixtures\$ConstructorOnly"
        const val HELPERS = "kim/der/asm/fixture/AnnotationUsageFixtures\$Helpers"
        const val RUNNABLE = "kim/der/asm/fixture/AnnotationUsageFixtures\$RunnableTarget"
        const val JOIN = "kim/der/asm/fixture/AnnotationUsageFixtures\$Receiver.join(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
        const val RUN = "run()Ljava/lang/String;"
        const val TWICE = "twice(Ljava/lang/String;)Ljava/lang/String;"
        const val STORE = "store(Ljava/lang/String;)Ljava/lang/String;"
        const val STRUCTURE = "kim/der/asm/fixture/AnnotationUsageFixtures\$Structure"

        @JvmStatic
        fun callCases() = listOf(
            CallCase(RedirectCall::class.java, "redirected", 0),
            CallCase(ChangeExpression::class.java, "a:b:expr", 1),
            CallCase(RepeatOperation::class.java, "a:b|a:b", 2),
            CallCase(SkipOperation::class.java, "skipped", 0),
            CallCase(SkipMethod::class.java, "skipped", 0, 0),
            CallCase(ConditionBefore::class.java, "", 0),
            CallCase(ConditionAfter::class.java, "", 1),
            CallCase(ChangeArg::class.java, "changed:b", 1),
            CallCase(ChangeArgs::class.java, "changed:both", 1),
            CallCase(ReplaceReceiver::class.java, "other:a:b", 0),
            CallCase(IgnoredReturn::class.java, "a:b", 1),
            CallCase(ChangeReturn::class.java, "changed", 1),
            CallCase(CallbackReturn::class.java, "changed", 1),
        ).flatMap { listOf(it, it.copy(target = KotlinAnnotationUsageFixtures.Calls::class.java)) }

        @JvmStatic
        fun localCases() = listOf(
            LocalCase(ChangeLocal::class.java, "raw!:raw!"),
            LocalCase(ChangeRead::class.java, "raw!:raw"),
            LocalCase(CaptureLocal::class.java, "raw:raw"),
        ).flatMap { listOf(it, it.copy(target = KotlinAnnotationUsageFixtures.Calls::class.java)) }

        @JvmStatic
        fun storeCases() = listOf(
            LocalCase(BeforeStore::class.java, "raw>next"),
            LocalCase(AfterStore::class.java, "next>next"),
        )

        @JvmStatic
        fun copyCases() = listOf(
            LocalCase(CopyHelper::class.java, "target"),
            LocalCase(UniqueHelper::class.java, "copy"),
            LocalCase(OverwriteHelper::class.java, "overwrite"),
        )

        @JvmStatic
        fun unsupportedOffsets() = listOf(OffsetInvoke::class.java, OffsetArgs::class.java, OffsetInline::class.java)
    }

    @AsmMixin(targets = [CALLS, KOTLIN_CALLS])
    object RedirectCall {
        @Redirect(method = RUN, at = At(InjectionPoint.INVOKE, target = JOIN))
        fun replace(receiver: Receiver, left: String, right: String) = "redirected"
    }

    @AsmMixin(targets = [CALLS, KOTLIN_CALLS])
    object ChangeExpression {
        @ModifyExpressionValue(method = RUN, at = At(InjectionPoint.INVOKE, target = JOIN))
        fun change(value: String) = "$value:expr"
    }

    @AsmMixin(targets = [CALLS, KOTLIN_CALLS])
    object RepeatOperation {
        @WrapOperation(method = RUN, at = At(InjectionPoint.INVOKE, target = JOIN))
        fun wrap(receiver: Receiver, left: String, right: String, original: Operation<String>): String =
            original.call(receiver, left, right) + "|" + original.call(receiver, left, right)
    }

    @AsmMixin(targets = [CALLS, KOTLIN_CALLS])
    object SkipOperation {
        @WrapOperation(method = RUN, at = At(InjectionPoint.INVOKE, target = JOIN))
        fun wrap(receiver: Receiver, left: String, right: String, original: Operation<String>) = "skipped"
    }

    @AsmMixin(targets = [CALLS, KOTLIN_CALLS])
    object SkipMethod {
        @WrapMethod(method = RUN)
        fun wrap(original: Operation<String>) = "skipped"
    }

    @AsmMixin(targets = [CALLS, KOTLIN_CALLS])
    object ConditionBefore {
        @WrapWithCondition(method = RUN, at = At(InjectionPoint.INVOKE, target = JOIN))
        fun keep(receiver: Receiver, left: String, right: String) = false
    }

    @AsmMixin(targets = [CALLS, KOTLIN_CALLS])
    object ConditionAfter {
        @WrapWithCondition(method = RUN, at = At(InjectionPoint.INVOKE_ASSIGN, target = JOIN))
        fun keep(value: String) = false
    }

    @AsmMixin(targets = [CALLS, KOTLIN_CALLS])
    object ChangeArg {
        @ModifyArg(method = RUN, index = 0, at = At(InjectionPoint.INVOKE, target = JOIN))
        fun change(value: String) = "changed"
    }

    @AsmMixin(targets = [CALLS, KOTLIN_CALLS])
    object ChangeArgs {
        @ModifyArgs(method = RUN, at = At(InjectionPoint.INVOKE, target = JOIN))
        fun change(args: Args) { args.set(0, "changed"); args.set(1, "both") }
    }

    @AsmMixin(targets = [CALLS, KOTLIN_CALLS])
    object ReplaceReceiver {
        var replacement = Receiver("other:")
        @ModifyReceiver(method = RUN, at = At(InjectionPoint.INVOKE, target = JOIN))
        fun change(receiver: Receiver): Receiver = replacement
    }

    @AsmMixin(targets = [CALLS, KOTLIN_CALLS])
    object IgnoredReturn {
        @AsmInject(method = RUN, target = InjectionPoint.RETURN)
        fun observe() = "ignored"
    }

    @AsmMixin(targets = [CALLS, KOTLIN_CALLS])
    object ChangeReturn {
        @ModifyReturnValue(method = RUN)
        fun change(value: String) = "changed"
    }

    @AsmMixin(targets = [CALLS, KOTLIN_CALLS])
    object CallbackReturn {
        @AsmInject(method = RUN, target = InjectionPoint.RETURN, cancellable = true)
        fun change(callback: CallbackInfo) { callback.setReturnValue("changed") }
    }

    @AsmMixin(targets = [CALLS, KOTLIN_CALLS])
    object ChangeLocal {
        @ModifyVariable(method = TWICE, at = At(InjectionPoint.LOAD, args = ["index=1"]), ordinal = 0)
        fun change(value: String) = "$value!"
    }

    @AsmMixin(targets = [CALLS, KOTLIN_CALLS])
    object ChangeRead {
        @ModifyExpressionValue(method = TWICE, at = At(InjectionPoint.LOAD, args = ["index=1"]), ordinal = 0)
        fun change(value: String) = "$value!"
    }

    @AsmMixin(targets = [CALLS, KOTLIN_CALLS])
    object CaptureLocal {
        var seen: String? = null
        @AsmInject(method = TWICE, target = InjectionPoint.RETURN)
        fun observe(@Local(index = 1) value: String) { seen = value }
    }

    @AsmMixin(targets = [CALLS, KOTLIN_CALLS])
    object BeforeStore {
        @ModifyExpressionValue(method = STORE, at = At(InjectionPoint.STORE, args = ["index=1"]))
        fun change(value: String, argument: String) = "$argument>$value"
    }

    @AsmMixin(targets = [CALLS, KOTLIN_CALLS])
    object AfterStore {
        @ModifyVariable(method = STORE, at = At(InjectionPoint.STORE, args = ["index=1"]))
        fun change(value: String, argument: String) = "$argument>$value"
    }

    @AsmMixin(HELPERS)
    class CopyHelper {
        @Copy fun value() = "copy"
        @Overwrite fun run() = value()
    }

    @AsmMixin(STRUCTURE)
    class DeleteMembers {
        @JvmField @AsmDelete var legacyField = 0
        @AsmDelete("legacy()Ljava/lang/String;") fun remove() = Unit
    }

    @AsmMixin(STRUCTURE)
    object RemoveLegacy { @RemoveMethod("legacy()Ljava/lang/String;") fun remove() = Unit }

    @AsmMixin(STRUCTURE)
    object Unlock { @RemoveSynchronized("locked()I") fun unlock() = Unit }

    @AsmMixin(STRUCTURE)
    object BridgeMembers {
        @Invoker("secret") fun callSecret(input: String): String = error("占位")
        @JvmStatic @Invoker("<init>") fun create(): Any = error("占位")
    }

    @AsmMixin(STRUCTURE)
    object SlicedConstant {
        @ModifyConstant(method = "constants()Ljava/lang/String;", constant = "raw", require = 1, allow = 1,
            slice = Slice(from = At(InjectionPoint.INVOKE, target = "$STRUCTURE.start()V"),
                to = At(InjectionPoint.INVOKE, target = "$STRUCTURE.end()V")))
        fun change(value: String) = "patched"
    }

    @AsmMixin(STRUCTURE)
    object VersionCandidates {
        @Group(name = "version", min = 1, max = 1)
        @ModifyConstant(method = "version()Ljava/lang/String;", constant = "old")
        fun old(value: String) = "patched"

        @Group(name = "version", min = 1, max = 1)
        @ModifyConstant(method = "version()Ljava/lang/String;", constant = "new")
        fun current(value: String) = "patched"
    }

    @AsmMixin(STRUCTURE)
    object MissingVersionMethod {
        @Group(name = "version", min = 0)
        @ModifyConstant(method = "oldVersion()Ljava/lang/String;", constant = "old")
        fun old(value: String) = "patched"
    }

    @AsmMixin(STRUCTURE)
    class AddState {
        @JvmField @AddField var extra = 99
        @JvmField @Shadow var calls = 0
        @Copy fun accumulate(): Int { extra += calls; return extra }
    }

    @AsmMixin(STRUCTURE)
    @RedirectAllMethods
    object RedirectConstants {
        @Redirect(at = At(InjectionPoint.CONSTANT, target = "new"), require = 1, allow = 1)
        fun replace(value: String) = "redirected"
    }

    @AsmMixin(HELPERS)
    class UniqueHelper {
        @Unique @Copy fun value() = "copy"
        @Overwrite fun run() = value()
    }

    @AsmMixin(HELPERS)
    class OverwriteHelper { @Overwrite fun value() = "overwrite" }

    @AsmMixin(HELPERS)
    class UniqueFresh { @Unique @Copy fun fresh() = "fresh" }

    @AsmMixin(HELPERS)
    class AcronymMembers {
        @RemoveField fun removeURL() = Unit
        @Accessor fun getURL(): String = error("占位")
    }

    @AsmMixin(RUNNABLE, priority = 2000)
    @RemoveInterface("java/lang/Runnable")
    object RemoveRunnable

    @AsmMixin(RUNNABLE)
    @AddInterface("java/lang/Runnable")
    object AddRunnable

    @AsmMixin(CONSTRUCTOR)
    @ReplaceAllMethods
    object ReplaceConstructorOnly

    @AsmMixin(CONSTANTS)
    object MutableInterface { @JvmField @Shadow("VALUE") @Mutable var value = 0 }

    @AsmMixin(CONSTANTS)
    object InterfaceGetter { @JvmStatic @Accessor("VALUE") fun read(): Int = error("placeholder") }

    @AsmMixin(CONSTANTS)
    object InterfaceSetter { @JvmStatic @Accessor("VALUE") @Mutable fun write(value: Int): Unit = error("placeholder") }

    @AsmMixin(CONSTRUCTOR)
    class BothModifiers { @JvmField @Shadow @Mutable @Final var value = 0 }

    @AsmMixin(targets = [CALLS, KOTLIN_CALLS])
    object OffsetInvoke {
        @AsmInject(method = RUN, target = InjectionPoint.INVOKE, at = At(InjectionPoint.INVOKE, target = JOIN, by = 1))
        fun observe() = Unit
    }

    @AsmMixin(targets = [CALLS, KOTLIN_CALLS])
    object OffsetArgs {
        @ModifyArgs(method = RUN, at = At(InjectionPoint.INVOKE, target = JOIN, by = 1))
        fun observe(args: Args) = Unit
    }

    @AsmMixin(targets = [CALLS, KOTLIN_CALLS])
    object OffsetInline {
        @AsmInject(method = RUN, target = InjectionPoint.HEAD, inline = true, at = At(by = 1))
        fun observe() = Unit
    }
}
