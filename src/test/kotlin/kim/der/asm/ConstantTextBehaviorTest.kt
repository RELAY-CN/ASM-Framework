/*
 * Copyright 2020-2026 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm

import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.At
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.ModifyConstant
import kim.der.asm.api.annotation.Slice
import kim.der.asm.transformer.AsmProcessor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/** 通过转换、类加载和调用，区分字符串字面量与同名的布尔文本目标。 */
class ConstantTextBehaviorTest {
    @BeforeEach
    @AfterEach
    fun clearRegistry() = AsmRegistry.clear()

    @ParameterizedTest
    @MethodSource("stringConstants")
    fun modifiesStringConstantsThatLookLikeSpecialLiterals(value: String, mixin: Class<*>) {
        val bytes = targetBytes("()Ljava/lang/String;") {
            visitLdcInsn(value)
            visitInsn(Opcodes.ARETURN)
        }

        assertEquals(value, invoke(bytes))
        AsmRegistry.register(mixin)
        val transformed = AsmProcessor().transform(TARGET, bytes, javaClass.classLoader)

        assertEquals("changed:$value", invoke(transformed))
    }

    @ParameterizedTest
    @MethodSource("booleanConstants")
    fun preservesBooleanOpcodeMatching(value: Boolean, mixin: Class<*>) {
        val bytes = targetBytes("()Z") {
            visitInsn(if (value) Opcodes.ICONST_1 else Opcodes.ICONST_0)
            visitInsn(Opcodes.IRETURN)
        }

        assertEquals(value, invoke(bytes))
        AsmRegistry.register(mixin)
        val transformed = AsmProcessor().transform(TARGET, bytes, javaClass.classLoader)

        assertEquals(!value, invoke(transformed))
    }

    @Test
    fun stringBooleanWordsBoundOnlyTheMiddleConstantInSlice() {
        val bytes = targetBytes("()Ljava/lang/String;") {
            visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder")
            visitInsn(Opcodes.DUP)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false)
            for (value in listOf("target", "true", "target", "false", "target")) {
                visitLdcInsn(value)
                visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "java/lang/StringBuilder",
                    "append",
                    "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                    false,
                )
            }
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false)
            visitInsn(Opcodes.ARETURN)
        }

        assertEquals("targettruetargetfalsetarget", invoke(bytes))
        AsmRegistry.register(StringBoundaryMixin::class.java)
        val transformed = AsmProcessor().transform(TARGET, bytes, javaClass.classLoader)

        assertEquals("targettruechangedfalsetarget", invoke(transformed))
    }

    /** 直接构造常量 opcode，避免 Kotlin 编译器折叠常量或改变切片边界。 */
    private fun targetBytes(descriptor: String, body: MethodVisitor.() -> Unit): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, TARGET, null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "value", descriptor, null, null).apply {
            visitCode()
            body()
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun invoke(bytes: ByteArray): Any? {
        val target = object : ClassLoader(javaClass.classLoader) {
            fun define(): Class<*> = defineClass(TARGET, bytes, 0, bytes.size)
        }.define()
        return target.getMethod("value").invoke(null)
    }

    companion object {
        private const val TARGET = "ConstantTextTarget"

        @JvmStatic
        fun stringConstants(): List<Arguments> = listOf(
            Arguments.of("true", TrueStringMixin::class.java),
            Arguments.of("false", FalseStringMixin::class.java),
            Arguments.of("null", NullStringMixin::class.java),
        )

        @JvmStatic
        fun booleanConstants(): List<Arguments> = listOf(
            Arguments.of(true, TrueBooleanMixin::class.java),
            Arguments.of(false, FalseBooleanMixin::class.java),
        )
    }

    @AsmMixin(TARGET)
    object TrueStringMixin {
        @JvmStatic
        @ModifyConstant(method = "value()Ljava/lang/String;", constant = "true", require = 1, allow = 1)
        fun modify(original: String): String = "changed:$original"
    }

    @AsmMixin(TARGET)
    object FalseStringMixin {
        @JvmStatic
        @ModifyConstant(method = "value()Ljava/lang/String;", constant = "false", require = 1, allow = 1)
        fun modify(original: String): String = "changed:$original"
    }

    @AsmMixin(TARGET)
    object NullStringMixin {
        @JvmStatic
        @ModifyConstant(method = "value()Ljava/lang/String;", constant = "null", require = 1, allow = 1)
        fun modify(original: String): String = "changed:$original"
    }

    @AsmMixin(TARGET)
    object TrueBooleanMixin {
        @JvmStatic
        @ModifyConstant(method = "value()Z", constant = "true", require = 1, allow = 1)
        fun modify(original: Boolean): Boolean = !original
    }

    @AsmMixin(TARGET)
    object FalseBooleanMixin {
        @JvmStatic
        @ModifyConstant(method = "value()Z", constant = "false", require = 1, allow = 1)
        fun modify(original: Boolean): Boolean = !original
    }

    @AsmMixin(TARGET)
    object StringBoundaryMixin {
        @JvmStatic
        @ModifyConstant(
            method = "value()Ljava/lang/String;",
            constant = "target",
            slice = Slice(
                from = At(value = InjectionPoint.CONSTANT, target = "true"),
                to = At(value = InjectionPoint.CONSTANT, target = "false"),
            ),
            require = 1,
            allow = 1,
        )
        fun modify(original: String): String {
            assertEquals("target", original)
            return "changed"
        }
    }
}
