/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.injector.impl

import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.data.AsmInfo
import kim.der.asm.injector.AbstractAsmInjector
import kim.der.asm.injector.impl.TailInjector.Companion.RETURN_OPS
import kim.der.asm.injector.util.AsmMethodCallGenerator
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.lang.reflect.Method

/**
 * TAIL 注入器。
 *
 * 在目标方法的返回指令之前插入 ASM 方法调用。当前实现会对每个 RETURN 位置插入调用副本；
 * 若方法没有 RETURN 指令，则退回到方法末尾插入。
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
class TailInjector(
    method: Method,
    asmInfo: AsmInfo,
) : AbstractAsmInjector(method, asmInfo) {
    /**
     * 在目标方法尾部注入 ASM 调用。
     *
     * @param target 目标方法
     * @return 成功插入指令后返回 `true`
     * @throws RuntimeException 参数映射或字节码结构不合法时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun inject(target: MethodNode): Boolean = injectCount(target) > 0

    override fun injectCount(target: MethodNode): Int {
        val isStatic = (target.access and Opcodes.ACC_STATIC) != 0
        val il = InsnList()

        // 检查是否需要 CallbackInfo
        val needsCallbackInfo = AsmMethodCallGenerator.needsCallbackInfo(asmMethod)

        var callbackVarIndex: Int? = null
        if (needsCallbackInfo) {
            AsmMethodCallGenerator.generateCallbackInfoCreation(il)
            callbackVarIndex = allocateLocalVariable(target, Type.getType(CallbackInfo::class.java))
            il.add(VarInsnNode(Opcodes.ASTORE, callbackVarIndex))
        }

        // 生成调用 ASM 方法的指令
        AsmMethodCallGenerator.generateMethodCall(
            il,
            asmMethod,
            asmInfo,
            target,
            callbackVarIndex,
        )

        // TAIL handler 的返回值不参与目标方法返回，必须始终丢弃以保持返回值栈顶不变。
        if (Type.getReturnType(asmMethod) != Type.VOID_TYPE) {
            AsmMethodCallGenerator.generatePopReturnValue(il, asmMethod)
        }

        // 查找所有 RETURN 指令并在第一个之前插入
        val instructions = target.instructions
        var injectionCount = 0

        for (insn in instructions.toArray()) {
            if (insn is InsnNode && insn.opcode in RETURN_OPS) {
                // 复制指令列表以避免重复插入
                instructions.insertBefore(insn, cloneInsnList(il))
                injectionCount++
            }
        }

        // 如果没有找到 RETURN，在最后添加
        if (injectionCount == 0 && instructions.size() > 0) {
            instructions.insertBefore(instructions.last, il)
            injectionCount = 1
        } else if (instructions.size() == 0) {
            instructions.add(il)
            injectionCount = 1
        }

        return injectionCount
    }

    private fun cloneInsnList(source: InsnList): InsnList {
        val labelMap = mutableMapOf<LabelNode, LabelNode>()
        source.toArray().filterIsInstance<LabelNode>().forEach { label ->
            labelMap[label] = LabelNode()
        }

        val cloned = InsnList()
        for (insn in source) {
            cloned.add(insn.clone(labelMap))
        }
        return cloned
    }

    /**
     * 分配局部变量索引
     */
    private fun allocateLocalVariable(
        target: MethodNode,
        type: Type,
    ): Int {
        val isStatic = (target.access and Opcodes.ACC_STATIC) != 0
        var varIndex = if (isStatic) 0 else 1

        // 计算参数占用的局部变量数量
        val paramTypes = Type.getArgumentTypes(target.desc)
        for (paramType in paramTypes) {
            varIndex += if (paramType.sort == Type.LONG || paramType.sort == Type.DOUBLE) 2 else 1
        }

        // 返回类型占用的局部变量数量
        val returnType = Type.getReturnType(target.desc)
        if (returnType != Type.VOID_TYPE) {
            varIndex += if (returnType.sort == Type.LONG || returnType.sort == Type.DOUBLE) 2 else 1
        }

        return varIndex
    }

    companion object {
        private val RETURN_OPS =
            setOf(
                Opcodes.RETURN,
                Opcodes.IRETURN,
                Opcodes.LRETURN,
                Opcodes.FRETURN,
                Opcodes.DRETURN,
                Opcodes.ARETURN,
            )
    }
}
