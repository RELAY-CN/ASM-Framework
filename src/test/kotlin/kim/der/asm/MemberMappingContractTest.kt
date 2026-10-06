/*
 * Copyright 2020-2026 Dr (dr@der.kim) and contributors.
 */
package kim.der.asm

import kim.der.asm.api.annotation.*
import kim.der.asm.fixture.StaticShadowReferenceFixtures.*
import kim.der.asm.transformer.AsmProcessor
import kim.der.asm.transformer.AsmTransformException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.util.CheckClassAdapter
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.reflect.Modifier

/**
 * 验证 Kotlin Mixin 的成员名称、receiver 与字段修饰符契约。
 *
 * 三条复制路径使用真实类加载和调用，检查 AddField 字段状态及 Shadow 方法的三种名称形式、
 * 重载描述符和外部调用。普通回调作为对照，证明 Mixin 状态不会隐式同步到目标对象。
 * 字段和方法的类型、static 契约及非法 final/volatile 组合必须在转换阶段失败。
 *
 * fixture 使用 @JvmField 生成直接字段指令；反射仅用于测试侧跨 ClassLoader 调用和观察，
 * handler 本身直接读写字段。此测试不将 Kotlin 自定义访问器或 lambda 的间接访问视作字段指令。
 */
class MemberMappingContractTest {
    /** 每次执行前后都清理全局注册表，参数化用例之间不共享 Mixin 注册。 */
    @BeforeEach
    @AfterEach
    fun clearRegistry() = AsmRegistry.clear()

    /** 三条复制路径都从目标字段的 JVM 默认值开始，且不同目标实例持有独立状态。 */
    @ParameterizedTest
    @MethodSource("addedFieldMixins")
    fun copiedBodiesAccessAddedFieldsOnEachTarget(mixin: Class<*>) {
        val target = transform(mixin)
        val first = target.getDeclaredConstructor().newInstance()
        val second = target.getDeclaredConstructor().newInstance()
        assertEquals(0, target.getField("added").getInt(first))
        target.getMethod("run").invoke(first)
        assertEquals(3, target.getField("added").getInt(first))
        assertEquals(2, target.getField("sameName").getInt(first))
        assertEquals(0, target.getField("added").getInt(second))
    }

    /** 同时校验显式别名、前缀、声明名和同名重载；占位方法一旦被误调用就立即失败。 */
    @ParameterizedTest
    @MethodSource("shadowMixins")
    fun copiedBodiesResolveExplicitShadowFieldAndMethodAliases(mixin: Class<*>) {
        val target = transform(mixin)
        val instance = target.getDeclaredConstructor().newInstance()
        target.getMethod("run").invoke(instance)
        assertEquals(31, target.getField("actual").getInt(instance))
    }

    /** 私有成员必须在目标内部直接调用；反射或外部 Mixin owner 都不能替代重映射。 */
    @ParameterizedTest
    @MethodSource("shadowMixins")
    fun copiedBodiesBindPrivateMembersWithDirectInstructions(mixin: Class<*>) {
        val bytes = transformBytes(mixin, fieldAccess = Opcodes.ACC_PRIVATE, methodAccess = Opcodes.ACC_PRIVATE)
        val node = ClassNode().also { ClassReader(bytes).accept(it, 0) }
        val instructions = node.methods.flatMap { it.instructions.toArray().toList() }
        val writes = instructions.filterIsInstance<FieldInsnNode>().filter { it.opcode == Opcodes.PUTFIELD }
        assertTrue(writes.any { it.owner == "MemberTarget" && it.name == "actual" && it.desc == "I" })
        val calls = instructions.filterIsInstance<MethodInsnNode>()
        assertTrue(calls.any { it.owner == "MemberTarget" && it.name == "actualMethod" && it.desc == "()I" })
        assertTrue(calls.any { it.owner == "MemberTarget" && it.name == "actualMethod" && it.desc == "(I)I" })
        assertFalse(calls.any { it.owner == mixin.name.replace('.', '/') || it.owner.startsWith("java/lang/reflect/") })

        val diagnostics = StringWriter()
        CheckClassAdapter.verify(ClassReader(bytes), javaClass.classLoader, false, PrintWriter(diagnostics))
        assertEquals("", diagnostics.toString())
        val target = define(bytes)
        val instance = target.getDeclaredConstructor().newInstance()
        target.getMethod("run").invoke(instance)
        val field = target.getDeclaredField("actual").apply { isAccessible = true }
        assertEquals(31, field.getInt(instance))
        assertTrue(Modifier.isPrivate(field.modifiers))
        assertTrue(Modifier.isPrivate(target.getDeclaredMethod("actualMethod").modifiers))
    }

    /** J/D 双槽值和引用数组使用真实字段指令，初始化值不迁移，重复调用也不串实例。 */
    @Test
    fun copiedBodiesPreserveWideAndArrayFieldDescriptors() {
        val target = transform(WideState::class.java)
        val first = target.getDeclaredConstructor().newInstance()
        val second = target.getDeclaredConstructor().newInstance()
        assertEquals(0L, target.getField("total").getLong(first))
        assertEquals(0.0, target.getField("fraction").getDouble(first))
        assertNull(target.getField("items").get(first))
        repeat(2) { target.getMethod("run").invoke(first) }
        assertEquals(4L, target.getField("total").getLong(first))
        assertEquals(1.0, target.getField("fraction").getDouble(first))
        assertArrayEquals(arrayOf("target"), target.getField("items").get(first) as Array<*>)
        assertEquals(0L, target.getField("total").getLong(second))
        assertEquals(0.0, target.getField("fraction").getDouble(second))
        assertNull(target.getField("items").get(second))
    }

    /** 合法的静态字段和方法在三条路径中都可使用别名，且不会写入 Mixin 自身状态。 */
    @ParameterizedTest
    @MethodSource("staticShadowMixins")
    fun copiedBodiesResolveStaticShadowMembers(mixin: Class<*>) {
        val target = transform(mixin, fieldAccess = PUBLIC_STATIC, methodAccess = PUBLIC_STATIC)
        target.getMethod("run").invoke(target.getDeclaredConstructor().newInstance())
        assertEquals(7, target.getField("actual").getInt(null))
        assertEquals(0, mixin.getField("alias").getInt(null))
    }

    /** 反向不匹配也要拒绝：Mixin 的静态字段不能绑定到目标实例字段。 */
    @Test
    fun staticShadowFieldRejectsInstanceTarget() {
        assertTransformFailure("Shadow field actual static") {
            transform(StaticInline::class.java, methodAccess = PUBLIC_STATIC)
        }
    }

    /** 字段已匹配时仍要独立验证方法，不能让 INVOKESTATIC 指向目标实例方法。 */
    @Test
    fun staticShadowMethodRejectsInstanceTarget() {
        assertTransformFailure("Shadow method actualMethod()I static") {
            transform(StaticInline::class.java, fieldAccess = PUBLIC_STATIC)
        }
    }

    /** 普通 handler 保留 Mixin 的初始值和 receiver，不修改目标的新增字段。 */
    @Test
    fun ordinaryHandlerRetainsMixinState() {
        val target = transform(ExternalHandler::class.java)
        val instance = target.getDeclaredConstructor().newInstance()
        target.getMethod("run").invoke(instance)
        assertEquals(0, target.getField("added").getInt(instance))
        val cache = target.declaredFields.single { it.name.startsWith("\$asmInstance\$") }
        cache.isAccessible = true
        assertEquals(13, (cache.get(null) as ExternalHandler).source)
    }

    /** AddField 别名在新增字段上同样接受 Final/Mutable，并优先于 Shadow 的名称提示。 */
    @Test
    fun addFieldAppliesModifiersToExplicitTargetName() {
        val target = transform(AddedModifiers::class.java)
        assertTrue(Modifier.isFinal(target.getField("immutable").modifiers))
        assertFalse(Modifier.isFinal(target.getField("mutable").modifiers))
        assertFalse(Modifier.isFinal(target.getField("actual").modifiers))
    }

    /** 复用同名目标字段后仍须处理 Mutable，且复制方法必须能真实写入该字段。 */
    @Test
    fun reusedAddedFieldAppliesMutableBeforeWriting() {
        val target = transform(ExistingAddedField::class.java, fieldAccess = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL)
        val instance = target.getDeclaredConstructor().newInstance()
        target.getMethod("run").invoke(instance)
        assertFalse(Modifier.isFinal(target.getField("actual").modifiers))
        assertEquals(5, target.getField("actual").getInt(instance))
    }

    /** 同名字段不能以不同描述符复用，否则字段指令会在运行时抛 NoSuchFieldError。 */
    @Test
    fun addedFieldRejectsIncompatibleType() {
        assertTransformFailure("type") { transform(ExistingAddedField::class.java, fieldDesc = "J") }
    }

    /** 保留字段指令 opcode 的前提是新增声明与已有目标字段的 static 属性一致。 */
    @Test
    fun addedFieldRejectsIncompatibleStaticness() {
        assertTransformFailure("static") {
            transform(ExistingAddedField::class.java, fieldAccess = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC)
        }
    }

    /** Shadow 字段同样不能把实例指令绑定到目标静态字段。 */
    @Test
    fun shadowFieldRejectsIncompatibleStaticness() {
        assertTransformFailure("static") {
            transform(ShadowInline::class.java, fieldAccess = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC)
        }
    }

    /** 方法描述符不包含 static 信息，必须额外拒绝实例 Shadow 对静态目标的绑定。 */
    @Test
    fun shadowMethodRejectsIncompatibleStaticness() {
        assertTransformFailure("static") {
            transform(ShadowInline::class.java, methodAccess = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC)
        }
    }

    /** 非法 final/volatile 组合应给出转换诊断，不能留到 defineClass 才抛 ClassFormatError。 */
    @Test
    fun finalRejectsVolatileFieldDuringTransform() {
        assertTransformFailure("volatile") {
            transform(FinalVolatile::class.java, fieldAccess = Opcodes.ACC_PUBLIC or Opcodes.ACC_VOLATILE)
        }
    }

    /** 新增 volatile 字段也必须遵守与已有字段相同的 Final 约束。 */
    @Test
    fun finalRejectsVolatileAddedFieldDuringTransform() {
        assertTransformFailure("volatile") { transform(AddedFinalVolatile::class.java) }
    }

    /** 强制异常来自转换流程，并检查根因中的具体契约诊断。 */
    private fun assertTransformFailure(message: String, action: () -> Unit) {
        val failure = assertThrows(AsmTransformException::class.java) { action() }
        assertTrue(failure.cause!!.message!!.contains(message), failure.toString())
    }

    /** 生成独立目标类并立即加载，既验证转换诊断，也验证最终 JVM 字节码是否合法。 */
    private fun transform(
        mixin: Class<*>,
        fieldDesc: String = "I",
        fieldAccess: Int = Opcodes.ACC_PUBLIC,
        methodAccess: Int = Opcodes.ACC_PUBLIC,
    ): Class<*> {
        return define(transformBytes(mixin, fieldDesc, fieldAccess, methodAccess))
    }

    /** 字节码检查与运行测试共用同一转换入口，避免只验证另一份生成产物。 */
    private fun transformBytes(
        mixin: Class<*>,
        fieldDesc: String = "I",
        fieldAccess: Int = Opcodes.ACC_PUBLIC,
        methodAccess: Int = Opcodes.ACC_PUBLIC,
    ): ByteArray {
        AsmRegistry.register(mixin)
        return AsmProcessor().transform("MemberTarget", targetBytes(fieldDesc, fieldAccess, methodAccess), javaClass.classLoader)
    }

    private fun define(bytes: ByteArray): Class<*> {
        return object : ClassLoader(javaClass.classLoader) {
            /** 由独立加载器定义，避免多个用例的同名目标类互相污染。 */
            fun define(): Class<*> = defineClass("MemberTarget", bytes, 0, bytes.size)
        }.define()
    }

    /** 构造最小目标：一个字段、运行入口及两个重载，参数仅用于制造成员契约不匹配。 */
    private fun targetBytes(fieldDesc: String, fieldAccess: Int, methodAccess: Int): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "MemberTarget", null, "java/lang/Object", null)
        writer.visitField(
            fieldAccess, "actual", fieldDesc, null, null,
        ).visitEnd()
        writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null).apply {
            visitCode()
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitMethod(methodAccess, "actualMethod", "()I", null, null).apply {
            visitCode()
            visitIntInsn(Opcodes.BIPUSH, 7)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC, "actualMethod", "(I)I", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ILOAD, 1)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    companion object {
        private const val PUBLIC_STATIC = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC

        /** 相同状态断言覆盖 Overwrite、Copy 和 inline，防止路径间语义分歧。 */
        @JvmStatic
        fun addedFieldMixins(): List<Class<*>> = listOf(AddedOverwrite::class.java, AddedCopy::class.java, AddedInline::class.java)

        /** 用同组 Shadow 声明比较三条路径的目标名称和方法描述符解析。 */
        @JvmStatic
        fun shadowMixins(): List<Class<*>> = listOf(ShadowOverwrite::class.java, ShadowCopy::class.java, ShadowInline::class.java)

        /** Java 直接静态指令作为正向对照，排除 Kotlin companion 实例访问器的干扰。 */
        @JvmStatic
        fun staticShadowMixins(): List<Class<*>> = listOf(StaticOverwrite::class.java, StaticCopy::class.java, StaticInline::class.java)
    }

    /** 覆写方法直接访问 Kotlin @JvmField 的同名字段和别名字段。 */
    @AsmMixin("MemberTarget")
    class AddedOverwrite {
        @JvmField @AddField("added") var source = 10
        @JvmField @AddField var sameName = 10

        /** 字段初始化值属于 Mixin，复制后应从目标的零值累加。 */
        @Overwrite("run()V")
        fun replace() {
            source += 3
            sameName += 2
        }
    }

    /** 用复制的 helper 验证字段映射和 Copy 方法调用映射可以组合使用。 */
    @AsmMixin("MemberTarget")
    class AddedCopy {
        @JvmField @AddField("added") var source = 10
        @JvmField @AddField var sameName = 10

        /** 该方法体整体复制到目标，字段指令必须使用目标 owner。 */
        @Copy
        fun copied() {
            source += 3
            sameName += 2
        }

        /** 从目标入口调用复制 helper，避免仅验证生成指令而未实际执行。 */
        @Overwrite("run()V")
        fun replace() = copied()
    }

    /** 内联路径保留目标原方法，新增字段的读写发生在目标 this 上。 */
    @AsmMixin("MemberTarget")
    class AddedInline {
        @JvmField @AddField("added") var source = 10
        @JvmField @AddField var sameName = 10

        /** 以 HEAD 内联验证不依赖反射的直接字段赋值。 */
        @AsmInject(method = "run()V", inline = true)
        fun before() {
            source += 3
            sameName += 2
        }
    }

    /** 混合宽类型与数组，Copy 内的直接字段访问必须保留各自描述符。 */
    @AsmMixin("MemberTarget")
    class WideState {
        @JvmField @AddField var total = 100L
        @JvmField @AddField var fraction = 99.0
        @JvmField @AddField var items: Array<String>? = arrayOf("mixin")

        @Copy fun accumulate() {
            total += 2L
            fraction += 0.5
            items = arrayOf("target")
        }

        @Overwrite("run()V") fun replace() = accumulate()
    }

    /** 覆写路径覆盖三种 Shadow 名称及两个精确重载。 */
    @AsmMixin("MemberTarget")
    class ShadowOverwrite {
        @JvmField @Shadow("actual") var alias = 0
        /** 显式别名的无参占位方法不应在 Mixin 上执行。 */
        @Shadow("actualMethod") fun aliasMethod(): Int = error("placeholder")
        /** 同名重载保持自身描述符，目标返回传入值。 */
        @Shadow("actualMethod") fun aliasMethod(value: Int): Int = error("placeholder")
        /** 前缀形式与显式别名应指向同一个目标方法。 */
        @Shadow("shadow_actualMethod") fun prefixed(): Int = error("placeholder")
        /** 空注解参数按声明名定位。 */
        @Shadow fun actualMethod(): Int = error("placeholder")
        /** Math.abs 的外部 owner 不属于 Mixin，必须保留。 */
        @Overwrite("run()V") fun replace() { alias = aliasMethod() + aliasMethod(8) + prefixed() + actualMethod() + Math.abs(-2) }
    }

    /** Copy 路径必须与 Overwrite 使用相同的 Shadow 解析规则。 */
    @AsmMixin("MemberTarget")
    class ShadowCopy {
        @JvmField @Shadow("actual") var alias = 0
        /** 显式别名：旧实现误改写为目标 aliasMethod，导致 NoSuchMethodError。 */
        @Shadow("actualMethod") fun aliasMethod(): Int = error("placeholder")
        /** 保留重载参数描述符。 */
        @Shadow("actualMethod") fun aliasMethod(value: Int): Int = error("placeholder")
        /** 前缀形式仍须兼容。 */
        @Shadow("shadow_actualMethod") fun prefixed(): Int = error("placeholder")
        /** 空参数继续使用声明名。 */
        @Shadow fun actualMethod(): Int = error("placeholder")
        /** 复制方法体内直接调用目标的多个 Shadow 映射。 */
        @Copy fun copied() { alias = aliasMethod() + aliasMethod(8) + prefixed() + actualMethod() + Math.abs(-2) }
        /** 通过目标入口实际调用复制后的 helper。 */
        @Overwrite("run()V") fun replace() = copied()
    }

    /** inline 路径也需同时正确处理字段别名和方法别名。 */
    @AsmMixin("MemberTarget")
    class ShadowInline {
        @JvmField @Shadow("actual") var alias = 0
        /** 显式别名：旧实现会在目标上查找不存在的 aliasMethod。 */
        @Shadow("actualMethod") fun aliasMethod(): Int = error("placeholder")
        /** 有参数的同名重载独立匹配。 */
        @Shadow("actualMethod") fun aliasMethod(value: Int): Int = error("placeholder")
        /** 前缀名称保持兼容。 */
        @Shadow("shadow_actualMethod") fun prefixed(): Int = error("placeholder")
        /** 空名称保持声明名语义。 */
        @Shadow fun actualMethod(): Int = error("placeholder")
        /** 将计算结果写到目标字段，验证调用结果及实际 receiver。 */
        @AsmInject(method = "run()V", inline = true)
        fun before() { alias = aliasMethod() + aliasMethod(8) + prefixed() + actualMethod() + Math.abs(-2) }
    }

    /** 普通回调对照 fixture，其状态必须留在 Mixin 实例内。 */
    @AsmMixin("MemberTarget")
    class ExternalHandler {
        @JvmField @AddField("added") var source = 10
        /** 从 Mixin 初始化值 10 累加，不对目标零值字段赋值。 */
        @AsmInject(method = "run()V") fun before() { source += 3 }
    }

    /** 新增字段修饰符按 AddField 的目标名生效，即使同时提供其他 Shadow 名称。 */
    @AsmMixin("MemberTarget")
    class AddedModifiers {
        @JvmField @AddField("immutable") @Shadow("actual") @Final var first = 1
        @JvmField @AddField("mutable") @Mutable val second = 2
    }

    /** 复用目标字段时保留类型/static 契约，Mutable 允许后续复制方法写入。 */
    @AsmMixin("MemberTarget")
    class ExistingAddedField {
        @JvmField @AddField("actual") @Mutable var alias = 1
        /** 真正执行 PUTFIELD，确保 Mutable 不只是反射可见的标志变化。 */
        @Overwrite("run()V") fun replace() { alias = 5 }
    }

    /** 目标 volatile 字段不能通过 Shadow 添加 final。 */
    @AsmMixin("MemberTarget")
    class FinalVolatile {
        @JvmField @Shadow("actual") @Final var alias = 0
    }

    /** 新增字段也不能同时带有 volatile 与 final。 */
    @AsmMixin("MemberTarget")
    class AddedFinalVolatile {
        @JvmField @Volatile @AddField @Final var added = 0
    }
}
