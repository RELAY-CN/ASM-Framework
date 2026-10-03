/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */
package kim.der.asm

import kim.der.asm.fixture.AddFieldReferenceFixtures.*
import kim.der.asm.transformer.AsmProcessor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 验证复制方法体对 [kim.der.asm.api.annotation.AddField] 字段的直接读写契约。
 *
 * 通过 Java fixture 生成真实的 GETFIELD/PUTFIELD/GETSTATIC/PUTSTATIC 指令，再加载并调用
 * 转换后的类，使错误的 owner、字段别名或访问权限由 JVM 校验暴露。测试中的反射只负责跨
 * ClassLoader 构造对象与观察状态，Mixin handler 本身不使用反射。
 *
 * 同时固定普通回调和字段初始化边界，防止修复扩大到改变 Mixin 实例或构造器语义。
 */
class AddFieldReferenceTest {
    /** 清理注册表和 fixture 的可观察状态，隔离参数化测试的每次运行。 */
    @BeforeEach
    fun setUp() {
        AsmRegistry.clear()
        External.value = 0
        CallbackMixin.observed = 0
    }

    /** 测试结束后释放注册信息，避免污染其他转换测试。 */
    @AfterEach
    fun tearDown() {
        AsmRegistry.clear()
    }

    /**
     * 三条复制路径均须读写目标实例，解析 AddField/Shadow 别名，并保留外部字段 owner。
     * 两个实例使用不同输入，证明新增实例字段没有被错误地变成 Mixin 或全局状态。
     */
    @ParameterizedTest(name = "{0}: instance fields")
    @MethodSource("copyPaths")
    fun copiedBodiesAccessTargetFields(mixin: Class<*>) {
        val target = transformAndLoad(mixin)
        val first = target.getDeclaredConstructor().newInstance()
        val second = target.getDeclaredConstructor().newInstance()
        val methodName = if (mixin == JavaCopyMixin::class.java) "copied" else "run"
        val method = target.getMethod(methodName, Int::class.javaPrimitiveType)

        assertEquals(if (mixin == JavaInlineMixin::class.java) -1 else 10, method.invoke(first, 10))
        assertEquals(if (mixin == JavaInlineMixin::class.java) -1 else 20, method.invoke(second, 20))
        assertEquals(10, fieldValue(target, first, "value"))
        assertEquals(20, fieldValue(target, second, "value"))
        assertEquals(11L, fieldValue(target, first, "score"))
        assertEquals(21L, fieldValue(target, second, "score"))
        assertEquals(12, fieldValue(target, first, "existing"))
        assertEquals(13, fieldValue(target, first, "legacy"))
        assertEquals(14, fieldValue(target, first, "kept"))
        assertEquals(24, fieldValue(target, second, "kept"))
        assertEquals("target", fieldValue(target, null, "label"))
        assertEquals("mixin", fieldValue(mixin, null, "mixinLabel"))
        assertEquals(119, External.value)
        assertTrue(Modifier.isPrivate(target.getDeclaredField("value").modifiers))
        assertTrue(Modifier.isPublic(target.getDeclaredField("kept").modifiers))
        assertFalse(target.declaredFields.any { it.name == "mixinScore" || it.name == "mixinLabel" })
    }

    /** 静态入口必须读写目标静态字段，并保持 Mixin 原有的静态初始值。 */
    @ParameterizedTest(name = "{0}: static fields")
    @MethodSource("copyPaths")
    fun copiedStaticBodiesAccessTargetFields(mixin: Class<*>) {
        val target = transformAndLoad(mixin)
        val methodName = if (mixin == JavaCopyMixin::class.java) "copiedStatic" else "runStatic"
        val method = target.getMethod(methodName, Int::class.javaPrimitiveType)

        assertEquals(0L, fieldValue(target, null, "total"))
        assertEquals(if (mixin == JavaInlineMixin::class.java) -1L else 43L, method.invoke(null, 42))
        assertEquals(43L, fieldValue(target, null, "total"))
        assertEquals(88L, fieldValue(mixin, null, "mixinTotal"))
    }

    /** 双注解字段遵循 AddField 的实际声明名，不引用不存在的 Shadow 名称。 */
    @Test
    fun addFieldNameTakesPrecedenceOverShadowHint() {
        val target = transformAndLoad(CombinedAnnotationsMixin::class.java)
        val instance = target.getDeclaredConstructor().newInstance()

        assertEquals(42, target.getMethod("run", Int::class.javaPrimitiveType).invoke(instance, 42))
        assertEquals(42, fieldValue(target, instance, "value"))
        assertFalse(target.declaredFields.any { it.name == "unused" || it.name == "alias" })
    }

    /** 非内联 handler 的 this 仍是 Mixin 实例，目标新增字段不会被隐式同步。 */
    @Test
    fun ordinaryCallbackKeepsMixinReceiver() {
        val target = transformAndLoad(CallbackMixin::class.java)
        val instance = target.getDeclaredConstructor().newInstance()

        assertEquals(-1, target.getMethod("run", Int::class.javaPrimitiveType).invoke(instance, 42))
        assertEquals(42, CallbackMixin.observed)
        assertEquals(0, fieldValue(target, instance, "value"))
    }

    /** 添加声明不会执行 Mixin 的实例或静态字段初始化表达式。 */
    @Test
    fun addedFieldsKeepJvmDefaultValues() {
        val target = transformAndLoad(DeclarationMixin::class.java)
        val instance = target.getDeclaredConstructor().newInstance()

        assertEquals(0, fieldValue(target, instance, "value"))
        assertNull(fieldValue(target, null, "label"))
    }

    /** 注册唯一 fixture，转换后通过独立加载器触发 JVM 的真实字节码校验。 */
    private fun transformAndLoad(mixin: Class<*>): Class<*> {
        AsmRegistry.register(mixin)
        val bytes = AsmProcessor().transform(TARGET_NAME, targetBytes(), javaClass.classLoader)
        return object : ClassLoader(javaClass.classLoader) {
            fun defineTarget(): Class<*> = defineClass(TARGET_NAME.replace('/', '.'), bytes, 0, bytes.size)
        }.defineTarget()
    }

    /** 仅用于断言目标状态；不会参与被测 handler 的字段访问。 */
    private fun fieldValue(owner: Class<*>, instance: Any?, name: String): Any? =
        owner.getDeclaredField(name).apply { isAccessible = true }.get(instance)

    /** 构造未添加字段的目标类，保留三个已有字段以覆盖 Shadow 和同名字段复用。 */
    private fun targetBytes(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, TARGET_NAME, null, "java/lang/Object", null)
        for (name in listOf("existing", "legacy", "kept")) {
            writer.visitField(Opcodes.ACC_PUBLIC, name, "I", null, null).visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC, "run", "(I)I", null, null).apply {
            visitCode()
            visitInsn(Opcodes.ICONST_M1)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(1, 2)
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "runStatic", "(I)J", null, null).apply {
            visitCode()
            visitLdcInsn(-1L)
            visitInsn(Opcodes.LRETURN)
            visitMaxs(2, 1)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    companion object {
        private const val TARGET_NAME = "fieldrefs/Target"

        /** 用相同状态断言比较三种复制路径，避免只修复其中一种。 */
        @JvmStatic
        fun copyPaths(): List<Class<*>> = listOf(
            JavaOverwriteMixin::class.java,
            JavaInlineMixin::class.java,
            JavaCopyMixin::class.java,
        )
    }
}
