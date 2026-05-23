/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.injector.impl

import kim.der.asm.data.AsmInfo
import kim.der.asm.injector.AbstractAsmInjector
import kim.der.asm.utils.transformer.BytecodeUtil
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * ModifyConstant 注入器。
 *
 * 遍历目标方法中的常量加载指令，并用 ASM 方法返回值替换匹配常量。
 * 当 [constantValue] 为 `null` 时仅按常量类型匹配；指定值时会同时校验常量文本。
 *
 * @param constantValue 常量过滤值；为 `null` 表示不按值过滤
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
class ModifyConstantInjector(
    method: Method,
    asmInfo: AsmInfo,
    private val constantValue: String? = null,
) : AbstractAsmInjector(method, asmInfo) {
    /**
     * 替换目标方法中匹配的常量。
     *
     * @param target 目标方法
     * @return 至少替换一个常量时返回 `true`
     * @throws IllegalArgumentException ASM 方法参数或返回类型与常量类型不匹配时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
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
                if (constantValue == null) {
                    continue
                }
                throw IllegalArgumentException(
                    "ASM method ${asmMethod.name} return type ($asmReturnType) must match constant type ($constantType)",
                )
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

        // 调用 ASM 方法修改常量
        generateConstantModifierCall(il, constNode, constantType)

        // 替换原始常量指令
        instructions.insertBefore(constNode, il)
        instructions.remove(constNode)

        return true
    }

    private fun generateConstantModifierCall(
        il: InsnList,
        constNode: AbstractInsnNode,
        constantType: Type,
    ) {
        validateHandlerParameter(constantType)

        val instanceType = Type.getType(asmInfo.asmClass)
        val useStaticCall = Modifier.isStatic(asmMethod.modifiers)

        if (!useStaticCall) {
            loadAsmHandlerReceiver(il, instanceType)
        }

        // 原始常量就是 @ModifyConstant handler 的第一个参数。
        loadConstant(il, constNode, constantType)

        il.add(
            MethodInsnNode(
                if (useStaticCall) Opcodes.INVOKESTATIC else Opcodes.INVOKEVIRTUAL,
                instanceType.internalName,
                asmMethod.name,
                Type.getMethodDescriptor(asmMethod),
                false,
            ),
        )
    }

    private fun validateHandlerParameter(constantType: Type) {
        val asmParamTypes = Type.getArgumentTypes(asmMethod)
        if (asmParamTypes.size != 1 || !isHandlerParameterCompatible(constantType, asmParamTypes[0])) {
            throw IllegalArgumentException(
                "ASM method ${asmMethod.name} must take exactly one argument of type $constantType, actual ${asmParamTypes.toList()}",
            )
        }
    }

    private fun loadAsmHandlerReceiver(
        il: InsnList,
        instanceType: Type,
    ) {
        if (isKotlinObject()) {
            il.add(
                FieldInsnNode(
                    Opcodes.GETSTATIC,
                    instanceType.internalName,
                    "INSTANCE",
                    "L${instanceType.internalName};",
                ),
            )
            return
        }

        val targetClassInternalName =
            asmInfo.targets.firstOrNull()?.replace('.', '/')
                ?: instanceType.internalName
        val singletonFieldName = "\$asmInstance\$${asmInfo.asmClass.simpleName}"
        val singletonFieldDesc = "L${instanceType.internalName};"
        val notNullLabel = LabelNode()
        val endLabel = LabelNode()

        il.add(FieldInsnNode(Opcodes.GETSTATIC, targetClassInternalName, singletonFieldName, singletonFieldDesc))
        il.add(InsnNode(Opcodes.DUP))
        il.add(JumpInsnNode(Opcodes.IFNONNULL, notNullLabel))
        il.add(InsnNode(Opcodes.POP))
        il.add(TypeInsnNode(Opcodes.NEW, instanceType.internalName))
        il.add(InsnNode(Opcodes.DUP))
        il.add(MethodInsnNode(Opcodes.INVOKESPECIAL, instanceType.internalName, "<init>", "()V", false))
        il.add(InsnNode(Opcodes.DUP))
        il.add(FieldInsnNode(Opcodes.PUTSTATIC, targetClassInternalName, singletonFieldName, singletonFieldDesc))
        il.add(JumpInsnNode(Opcodes.GOTO, endLabel))
        il.add(notNullLabel)
        il.add(endLabel)
    }

    private fun isHandlerParameterCompatible(
        expected: Type,
        actual: Type,
    ): Boolean {
        if (expected == actual) {
            return true
        }
        if (expected.sort == Type.OBJECT || expected.sort == Type.ARRAY) {
            return actual.sort == Type.OBJECT &&
                (actual.internalName == "java/lang/Object" || actual.internalName == "kotlin/Any")
        }
        return false
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

}
