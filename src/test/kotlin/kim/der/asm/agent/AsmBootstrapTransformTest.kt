package kim.der.asm.agent

import kim.der.asm.AsmRegistry
import kim.der.asm.api.annotation.Accessor
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.RemoveMethod
import kim.der.asm.transformer.AsmTransformException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes.*
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

/** 通过 Agent 生产入口验证单次注册快照、真实栈帧与失败原子性。 */
class AsmBootstrapTransformTest {
    @BeforeEach
    fun setUp() = AsmRegistry.clear()

    @AfterEach
    fun tearDown() = AsmRegistry.clear()

    @Test
    fun usesOneRegistrationSnapshotEvenWhenMatcherRegistersAnotherMixin() {
        var matcherCalls = 0
        AsmRegistry.registerWithPathMatcher(RemoveDiscardMixin::class.java) {
            if (matcherCalls++ == 0) {
                AsmRegistry.registerWithPathMatcher(MissingAccessorMixin::class.java) { true }
            }
            true
        }
        val bootstrap = AsmBootstrap()
        val bytes = targetBytes()

        val transformed = bootstrap.transform(javaClass.classLoader, TARGET, null, null, bytes)
        assertEquals(1, matcherCalls)
        assertFailsWith<NoSuchMethodException> { define(transformed).getMethod("discard") }

        val failure = assertFailsWith<AsmTransformException> {
            bootstrap.transform(javaClass.classLoader, TARGET, null, null, bytes)
        }
        assertEquals(MissingAccessorMixin::class.java.name, failure.asmClassName)
    }

    @Test
    fun keepsCapturedMixinWhenMatcherClearsRegistry() {
        AsmRegistry.registerWithPathMatcher(RemoveDiscardMixin::class.java) {
            AsmRegistry.clear()
            true
        }
        val bootstrap = AsmBootstrap()
        val bytes = targetBytes()

        val transformed = bootstrap.transform(javaClass.classLoader, TARGET, null, null, bytes)
        assertFailsWith<NoSuchMethodException> { define(transformed).getMethod("discard") }
        assertSame(bytes, bootstrap.transform(javaClass.classLoader, TARGET, null, null, bytes))
    }

    @Test
    fun unmatchedInputIsReturnedWithoutParsing() {
        val bytes = byteArrayOf(1, 2, 3)
        assertSame(bytes, AsmBootstrap().transform(null, TARGET, null, null, bytes))
    }

    @Test
    fun matchedButUnchangedInputKeepsOriginalArray() {
        AsmRegistry.register(EmptyMixin::class.java)
        val bytes = targetBytes()
        assertSame(bytes, AsmBootstrap().transform(null, TARGET, null, null, bytes))
    }

    @Test
    fun matchingFailurePropagatesOriginalException() {
        val sentinel = IllegalStateException("matcher failed")
        AsmRegistry.registerWithPathMatcher(EmptyMixin::class.java) { throw sentinel }
        val failure = assertFailsWith<IllegalStateException> {
            AsmBootstrap().transform(null, TARGET, null, null, targetBytes())
        }
        assertSame(sentinel, failure)
    }

    @Test
    fun failedMixinPreservesInputAndReportsCauseAndContext() {
        AsmRegistry.register(RemoveDiscardMixin::class.java)
        AsmRegistry.register(MissingAccessorMixin::class.java)
        val bytes = targetBytes()
        val original = bytes.copyOf()

        val failure = assertFailsWith<AsmTransformException> {
            AsmBootstrap().transform(javaClass.classLoader, TARGET, null, null, bytes)
        }

        assertEquals(TARGET, failure.className)
        assertEquals(MissingAccessorMixin::class.java.name, failure.asmClassName)
        assertIs<IllegalStateException>(failure.cause)
        assertEquals("Accessor target field missingField not found in $TARGET", failure.cause?.message)
        assertContentEquals(original, bytes)
        define(bytes).getMethod("discard").invoke(null)
    }

    @Test
    fun transformedCompressedFramesLoadAndExecuteBranchesAndCatch() {
        AsmRegistry.register(RemoveDiscardMixin::class.java)
        AsmBootstrap()
        // AsmCore 是服务端实际使用的入口；执行产物验证展开与重算 frame 没有破坏控制流。
        val transformed = AsmCore.transform(javaClass.classLoader, TARGET, targetBytes())
        val type = define(transformed)
        val choose = type.getMethod("choose", Int::class.javaPrimitiveType)

        assertEquals("ArrayList", choose.invoke(null, 0))
        assertEquals("LinkedList", choose.invoke(null, 1))
        assertEquals("caught", choose.invoke(null, -1))
        assertFailsWith<NoSuchMethodException> { type.getMethod("discard") }
    }

    private fun define(bytes: ByteArray): Class<*> = object : ClassLoader(javaClass.classLoader) {
        fun define(): Class<*> = defineClass(TARGET.replace('/', '.'), bytes, 0, bytes.size)
    }.define()

    private fun targetBytes(): ByteArray = ClassWriter(ClassWriter.COMPUTE_FRAMES).apply {
        visit(V11, ACC_PUBLIC or ACC_SUPER, TARGET, null, "java/lang/Object", null)
        visitMethod(ACC_PUBLIC or ACC_STATIC, "discard", "()V", null, null).apply {
            visitCode()
            visitInsn(RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        visitMethod(ACC_PUBLIC or ACC_STATIC, "choose", "(I)Ljava/lang/String;", null, null).apply {
            val start = Label()
            val nonNegative = Label()
            val linked = Label()
            val merged = Label()
            val end = Label()
            val caught = Label()
            visitCode()
            visitTryCatchBlock(start, end, caught, "java/lang/IllegalArgumentException")
            visitLabel(start)
            visitVarInsn(ILOAD, 0)
            visitJumpInsn(IFGE, nonNegative)
            visitTypeInsn(NEW, "java/lang/IllegalArgumentException")
            visitInsn(DUP)
            visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalArgumentException", "<init>", "()V", false)
            visitInsn(ATHROW)
            visitLabel(nonNegative)
            visitVarInsn(ILOAD, 0)
            visitJumpInsn(IFNE, linked)
            visitTypeInsn(NEW, "java/util/ArrayList")
            visitInsn(DUP)
            visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false)
            visitJumpInsn(GOTO, merged)
            visitLabel(linked)
            visitTypeInsn(NEW, "java/util/LinkedList")
            visitInsn(DUP)
            visitMethodInsn(INVOKESPECIAL, "java/util/LinkedList", "<init>", "()V", false)
            visitLabel(merged)
            visitVarInsn(ASTORE, 1)
            visitVarInsn(ALOAD, 1)
            visitMethodInsn(INVOKEVIRTUAL, "java/util/AbstractList", "size", "()I", false)
            visitInsn(POP)
            visitVarInsn(ALOAD, 1)
            visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false)
            visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getSimpleName", "()Ljava/lang/String;", false)
            visitLabel(end)
            visitInsn(ARETURN)
            visitLabel(caught)
            visitInsn(POP)
            visitLdcInsn("caught")
            visitInsn(ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        visitEnd()
    }.toByteArray()

    @AsmMixin(TARGET)
    object RemoveDiscardMixin {
        @RemoveMethod("discard()V")
        @JvmStatic
        fun marker() {}
    }

    @AsmMixin(TARGET)
    object MissingAccessorMixin {
        @Accessor("missingField")
        @JvmStatic
        fun missing(): String = error("只用于触发转换失败")
    }

    @AsmMixin(TARGET)
    object EmptyMixin

    private companion object {
        const val TARGET = "asm/bootstrapfixture/Target"
    }
}
