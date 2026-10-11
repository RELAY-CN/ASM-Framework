/*
 * Copyright 2020-2026 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm

import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.Local
import kim.der.asm.injector.util.ParameterMapper
import kim.der.asm.transformer.AsmProcessor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LocalVariableNode
import org.objectweb.asm.tree.MethodNode

/** 验证参数序号、双槽位类型和 LocalVariableTable 捕获之间的独立性。 */
class ParameterMappingBehaviorTest {
    @BeforeEach
    @AfterEach
    fun clearRegistry() {
        AsmRegistry.clear()
        ReceiverMixin.receiver = null
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun mapsParametersAfterLongAndDoubleForStaticAndInstanceMethods(isStatic: Boolean) {
        val target = transform(WideArgumentsMixin::class.java, isStatic)
        val instance = if (isStatic) null else target.getDeclaredConstructor().newInstance()

        assertEquals("11|7|2.5|payload", invoke(target, instance))
    }

    @Test
    fun preservesReceiverAndCallbackBeforeWideArguments() {
        val target = transform(ReceiverMixin::class.java, false)
        val instance = target.getDeclaredConstructor().newInstance()

        assertEquals("11|7|2.5|payload", invoke(target, instance))
        assertSame(instance, ReceiverMixin.receiver)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun localCapturesDoNotAdvanceTargetParameterOrdinal(isStatic: Boolean) {
        val target = transform(InterleavedLocalMixin::class.java, isStatic)
        val instance = if (isStatic) null else target.getDeclaredConstructor().newInstance()

        assertEquals("marker|11|copy|7|2.5|payload", invoke(target, instance))
    }

    @ParameterizedTest
    @ValueSource(strings = ["anchor", "start", "end"])
    fun rejectsForeignNodesEvenWhenTheirCachedIndexesFit(member: String) {
        val method = localCaptureMethod()
        val anchor = method.instructions[1]
        val local = method.localVariables.single()

        // 外来节点的缓存下标刻意与本方法重合，不能仅凭下标范围认定所有权。
        val foreign = InsnList()
        foreign.add(LabelNode())
        foreign.add(InsnNode(Opcodes.NOP))
        foreign.add(LabelNode())
        foreign.toArray()
        val captureAnchor = if (member == "anchor") foreign[1] else anchor
        if (member == "start") local.start = foreign[0] as LabelNode
        if (member == "end") local.end = foreign[2] as LabelNode
        val failure = assertThrows(IllegalStateException::class.java) {
            capture(method, captureAnchor)
        }

        assertTrue(failure.message.orEmpty().contains("cannot find visible local variable"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["remove", "replace", "clear", "moveOutsideScope"])
    fun rechecksLocalVisibilityAfterTheInstructionListChanges(operation: String) {
        val method = localCaptureMethod()
        val anchor = method.instructions[1]
        capture(method, anchor)

        // 先经生产入口建立索引，再修改同一列表；旧锚点不能因旧下标而继续捕获。
        when (operation) {
            "remove" -> method.instructions.remove(anchor)
            "replace" -> method.instructions.set(anchor, InsnNode(Opcodes.NOP))
            "clear" -> method.instructions.clear()
            "moveOutsideScope" -> {
                method.instructions.remove(anchor)
                method.instructions.insert(method.localVariables.single().end, anchor)
            }
        }

        val failure = assertThrows(IllegalStateException::class.java) { capture(method, anchor) }

        assertTrue(failure.message.orEmpty().contains("cannot find visible local variable"))
    }

    private fun localCaptureMethod(): MethodNode {
        val method = MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "run", "(Ljava/lang/String;)V", null, null)
        val start = LabelNode()
        val end = LabelNode()
        method.instructions.add(start)
        method.instructions.add(InsnNode(Opcodes.NOP))
        method.instructions.add(end)
        method.instructions.add(InsnNode(Opcodes.RETURN))
        method.localVariables.add(LocalVariableNode("value", "Ljava/lang/String;", null, start, end, 0))
        return method
    }

    private fun capture(method: MethodNode, anchor: AbstractInsnNode) {
        ParameterMapper.loadParameters(
            InsnList(),
            method,
            LocalOnlyHandler::class.java.getMethod("capture", String::class.java),
            localCaptureContext = ParameterMapper.LocalCaptureContext(anchor, TARGET),
        )
    }

    private fun transform(mixin: Class<*>, isStatic: Boolean): Class<*> {
        AsmRegistry.register(mixin)
        val bytes = AsmProcessor().transform(TARGET, targetBytes(isStatic), javaClass.classLoader)
        return object : ClassLoader(javaClass.classLoader) {
            fun define(): Class<*> = defineClass(TARGET, bytes, 0, bytes.size)
        }.define()
    }

    private fun invoke(target: Class<*>, instance: Any?): Any? = target.getMethod(
        "run",
        Long::class.javaPrimitiveType,
        Int::class.javaPrimitiveType,
        Double::class.javaPrimitiveType,
        String::class.java,
    ).invoke(instance, 11L, 7, 2.5, "payload")

    /** 宽参数占六个槽位，局部字符串位于参数之后，静态与实例方法只差 this 的一个槽位。 */
    private fun targetBytes(isStatic: Boolean): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, TARGET, null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        val localSlot = if (isStatic) 6 else 7
        val access = Opcodes.ACC_PUBLIC or if (isStatic) Opcodes.ACC_STATIC else 0
        writer.visitMethod(access, "run", DESCRIPTOR, null, null).apply {
            visitCode()
            visitLdcInsn("marker")
            visitVarInsn(Opcodes.ASTORE, localSlot)
            visitLdcInsn("copy")
            visitVarInsn(Opcodes.ASTORE, localSlot + 1)
            val start = Label()
            val end = Label()
            visitLabel(start)
            visitLdcInsn("original")
            visitInsn(Opcodes.ARETURN)
            visitLabel(end)
            visitLocalVariable("marker", "Ljava/lang/String;", null, start, end, localSlot)
            visitLocalVariable("copy", "Ljava/lang/String;", null, start, end, localSlot + 1)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    companion object {
        private const val TARGET = "ParameterMappingTarget"
        private const val DESCRIPTOR = "(JIDLjava/lang/String;)Ljava/lang/String;"
    }

    @AsmMixin(TARGET)
    object WideArgumentsMixin {
        @JvmStatic
        @AsmInject(method = "run$DESCRIPTOR", target = InjectionPoint.HEAD, cancellable = true)
        fun inject(callback: CallbackInfo, number: Long, count: Int, ratio: Double, text: String) {
            callback.setReturnValue("$number|$count|$ratio|$text")
        }
    }

    @AsmMixin(TARGET)
    object ReceiverMixin {
        var receiver: Any? = null

        @JvmStatic
        @AsmInject(method = "run$DESCRIPTOR", target = InjectionPoint.HEAD, cancellable = true)
        fun inject(callback: CallbackInfo, target: Any, number: Long, count: Int, ratio: Double, text: String) {
            receiver = target
            callback.setReturnValue("$number|$count|$ratio|$text")
        }
    }

    @AsmMixin(TARGET)
    object InterleavedLocalMixin {
        @JvmStatic
        @AsmInject(method = "run$DESCRIPTOR", target = InjectionPoint.RETURN, cancellable = true)
        fun inject(
            callback: CallbackInfo,
            @Local(name = "marker") marker: String,
            number: Long,
            @Local(name = "copy") copy: String,
            count: Int,
            ratio: Double,
            text: String,
        ) {
            callback.setReturnValue("$marker|$number|$copy|$count|$ratio|$text")
        }
    }

    object LocalOnlyHandler {
        @JvmStatic
        fun capture(@Local(name = "value") value: String): String = value
    }
}
