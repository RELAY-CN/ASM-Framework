/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.injector.impl

import kim.der.asm.data.AsmInfo
import kim.der.asm.injector.AbstractAsmInjector
import kim.der.asm.injector.util.AsmMethodCallGenerator
import kim.der.asm.utils.transformer.BytecodeUtil
import kim.der.asm.utils.transformer.InstructionUtil
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.lang.reflect.Method

/**
 * ModifyConstant 注入器
 * 修改方法中的常量值
 *
 * @author Dr (dr@der.kim)
 */
class ModifyConstantInjector(
    method: Method,
    asmInfo: AsmInfo,
    private val constantValue: String? = null,
) : AbstractAsmInjector(method, asmInfo) {
    override fun inject(target: MethodNode): Boolean {
        val instructions = target.instructions
        var transformed = false
        val insns = instructions.toArray()

        // 查找所有常量指令
        for (insn in insns) {
            if (!BytecodeUtil.isConstant(insn)) {
                continue
            }

            // 如果指定了常量值，检查是否匹配
            if (constantValue != null) {
                val constant = BytecodeUtil.getConstant(insn)
                if (!matchesConstant(constant, constantValue)) {
                    continue
                }
            }

            // 获取常量类型
            val constantType = BytecodeUtil.getConstantType(insn) ?: continue

            // 检查 ASM 方法的返回类型是否匹配
            val asmReturnType = Type.getReturnType(asmMethod)
            if (asmReturnType != constantType) {
                continue
            }

            // 注入常量修改
            if (injectConstantModifier(instructions, insn, target, constantType)) {
                transformed = true
            }
        }

        return transformed
    }

    /**
     * 检查常量值是否匹配
     */
    private fun matchesConstant(
        constant: Any?,
        value: String,
    ): Boolean {
        if (constant == null) {
            return value == "null"
        }

        return when (constant) {
            is Int -> constant.toString() == value
            is Long -> constant.toString() == value
            is Float -> constant.toString() == value
            is Double -> constant.toString() == value
            is String -> constant == value
            is Type -> constant.internalName == value.replace('.', '/')
            else -> false
        }
    }

    /**
     * 注入常量修改器
     */
    private fun injectConstantModifier(
        instructions: InsnList,
        constNode: AbstractInsnNode,
        target: MethodNode,
        constantType: Type,
    ): Boolean {
        val il = InsnList()

        // 加载原始常量值
        loadConstant(il, constNode, constantType)

        // 调用 ASM 方法修改常量
        val mockTarget = createMockMethodNode(target, constantType)
        AsmMethodCallGenerator.generateMethodCall(
            il,
            asmMethod,
            asmInfo,
            mockTarget,
            null,
        )

        // 替换原始常量指令
        instructions.insertBefore(constNode, il)
        instructions.remove(constNode)

        return true
    }

    /**
     * 加载常量值
     */
    private fun loadConstant(
        il: InsnList,
        insn: AbstractInsnNode,
        type: Type,
    ) {
        when (insn) {
            is LdcInsnNode -> {
                il.add(LdcInsnNode(insn.cst))
            }
            is IntInsnNode -> {
                when (insn.opcode) {
                    Opcodes.BIPUSH, Opcodes.SIPUSH -> {
                        il.add(IntInsnNode(insn.opcode, insn.operand))
                    }
                }
            }
            else -> {
                // 使用原始指令
                il.add(insn.clone(null))
            }
        }
    }

    /**
     * 创建模拟方法节点
     */
    private fun createMockMethodNode(
        target: MethodNode,
        constantType: Type,
    ): MethodNode {
        val asmReturnType = Type.getReturnType(asmMethod)
        val mockDesc = Type.getMethodDescriptor(asmReturnType, constantType)
        return MethodNode(
            target.access,
            target.name,
            mockDesc,
            target.signature,
            target.exceptions?.toTypedArray(),
        )
    }
}
