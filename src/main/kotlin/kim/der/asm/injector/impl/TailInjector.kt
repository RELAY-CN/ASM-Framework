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
 * TAIL 注入器
 * 在方法结尾（所有 RETURN 之前）注入代码
 *
 * @author Dr (dr@der.kim)
 */
class TailInjector(
    method: Method,
    asmInfo: AsmInfo,
) : AbstractAsmInjector(method, asmInfo) {
    override fun inject(target: MethodNode): Boolean {
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

        // 如果方法有返回值且目标方法是 void，需要弹出
        if (AsmMethodCallGenerator.needsPopReturnValue(asmMethod, target)) {
            il.add(InsnNode(Opcodes.POP))
        }

        // 查找所有 RETURN 指令并在第一个之前插入
        val instructions = target.instructions
        var foundReturn = false

        for (insn in instructions.toArray()) {
            if (insn is InsnNode && insn.opcode in RETURN_OPS) {
                // 复制指令列表以避免重复插入
                val copy = InsnList()
                val labelMap = mutableMapOf<LabelNode, LabelNode>()
                for (node in il) {
                    copy.add(node.clone(labelMap))
                }
                instructions.insertBefore(insn, copy)
                foundReturn = true
            }
        }

        // 如果没有找到 RETURN，在最后添加
        if (!foundReturn && instructions.size() > 0) {
            instructions.insertBefore(instructions.last, il)
        } else if (instructions.size() == 0) {
            instructions.add(il)
        }

        return foundReturn || instructions.size() > 0
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
