/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.injector.impl

import kim.der.asm.api.annotation.At
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.Slice
import kim.der.asm.data.AsmInfo
import kim.der.asm.injector.AbstractAsmInjector
import kim.der.asm.utils.transformer.BytecodeUtil
import kim.der.asm.utils.transformer.InstructionUtil
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
 * 支持 `LDC`、`ACONST_NULL`、`ICONST_*`、`LCONST_*`、`FCONST_*`、`DCONST_*`、
 * `BIPUSH` 与 `SIPUSH` 形式的常量加载。
 * ASM 方法的第一个参数接收原常量，后续参数可按顺序接收目标方法的部分参数。
 *
 * @param constantValue 常量过滤值；为 `null` 表示不按值过滤
 * @param ordinal 匹配常量序号；负数表示处理全部匹配常量
 * @param slice 切片范围；当前使用 INVOKE 边界缩小常量匹配范围
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
class ModifyConstantInjector(
    method: Method,
    asmInfo: AsmInfo,
    private val constantValue: String? = null,
    private val ordinal: Int = -1,
    private val slice: Slice = Slice(),
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
    override fun inject(target: MethodNode): Boolean = injectCount(target) > 0

    override fun injectCount(target: MethodNode): Int {
        val instructions = target.instructions
        var injectionCount = 0
        val insns = instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)
        var matchedOrdinal = 0

        // 查找所有常量指令
        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
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
                val currentOrdinal = matchedOrdinal++
                if (!matchesOrdinal(currentOrdinal)) {
                    continue
                }
                throw IllegalArgumentException(
                    "ASM method ${asmMethod.name} return type ($asmReturnType) must match constant type ($constantType)",
                )
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            // 注入常量修改
            if (injectConstantModifier(instructions, insn, target, constantType)) {
                injectionCount++
            }
        }

        return injectionCount
    }

    private fun matchesOrdinal(currentOrdinal: Int): Boolean = ordinal < 0 || currentOrdinal == ordinal

    private fun resolveSliceRange(insns: Array<AbstractInsnNode>): Pair<Int, Int> {
        val startIndex =
            if (hasSliceBoundary(slice.from)) {
                val fromIndex = findSliceBoundaryIndex(insns, slice.from, 0) ?: return emptySlice(insns)
                fromIndex + 1
            } else {
                0
            }
        val endIndex =
            if (hasSliceBoundary(slice.to)) {
                findSliceBoundaryIndex(insns, slice.to, startIndex) ?: return emptySlice(insns)
            } else {
                insns.size
            }

        return startIndex to endIndex.coerceAtLeast(startIndex)
    }

    private fun hasSliceBoundary(at: At): Boolean = at.target.isNotEmpty()

    private fun emptySlice(insns: Array<AbstractInsnNode>): Pair<Int, Int> = insns.size to insns.size

    private fun findSliceBoundaryIndex(
        insns: Array<AbstractInsnNode>,
        at: At,
        startIndex: Int,
    ): Int? {
        require(at.value == InjectionPoint.INVOKE) {
            "Only INVOKE slice boundaries are supported for @ModifyConstant: ${at.value}"
        }

        val (boundaryOwner, boundaryName, boundaryDesc) = parseTargetMethod(at.target)
        if (boundaryName == null || boundaryDesc == null) {
            throw IllegalArgumentException(
                "Invalid ModifyConstant slice boundary method signature: ${at.target} " +
                    "(parsed: owner=$boundaryOwner, name=$boundaryName, desc=$boundaryDesc)",
            )
        }

        for (index in startIndex until insns.size) {
            val insn = insns[index]
            if (insn is MethodInsnNode && matchesTargetMethod(insn, boundaryOwner, boundaryName, boundaryDesc)) {
                return index
            }
        }

        return null
    }

    private fun parseTargetMethod(signature: String): Triple<String?, String?, String?> {
        if (signature.isEmpty()) {
            return Triple(null, null, null)
        }

        val parenIndex = signature.indexOf('(')
        if (parenIndex < 0) {
            return Triple(null, signature, null)
        }

        val ownerAndName = signature.substring(0, parenIndex)
        val desc = signature.substring(parenIndex)
        val slashIndex = ownerAndName.lastIndexOf('/')
        val dotIndex = ownerAndName.lastIndexOf('.')
        val separatorIndex = maxOf(slashIndex, dotIndex)

        return if (separatorIndex >= 0) {
            Triple(
                ownerAndName.substring(0, separatorIndex).replace('.', '/'),
                ownerAndName.substring(separatorIndex + 1),
                desc,
            )
        } else {
            Triple(null, ownerAndName, desc)
        }
    }

    private fun matchesTargetMethod(
        insn: MethodInsnNode,
        targetOwner: String?,
        targetName: String,
        targetDesc: String?,
    ): Boolean {
        if (targetOwner != null && insn.owner != targetOwner) {
            return false
        }
        if (insn.name != targetName) {
            return false
        }
        return targetDesc == null || insn.desc == targetDesc
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
        generateConstantModifierCall(il, constNode, target, constantType)

        // 替换原始常量指令
        instructions.insertBefore(constNode, il)
        instructions.remove(constNode)

        return true
    }

    private fun generateConstantModifierCall(
        il: InsnList,
        constNode: AbstractInsnNode,
        target: MethodNode,
        constantType: Type,
    ) {
        val asmParamTypes = Type.getArgumentTypes(asmMethod)
        validateHandlerParameters(target, constantType, asmParamTypes)

        val instanceType = Type.getType(asmInfo.asmClass)
        val useStaticCall = Modifier.isStatic(asmMethod.modifiers)

        if (!useStaticCall) {
            loadAsmHandlerReceiver(il, instanceType)
        }

        // 原始常量就是 @ModifyConstant handler 的第一个参数。
        loadConstant(il, constNode, constantType)
        loadTargetMethodParameters(il, target, asmParamTypes.size - 1)

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

    private fun validateHandlerParameters(
        target: MethodNode,
        constantType: Type,
        asmParamTypes: Array<Type>,
    ) {
        if (asmParamTypes.isEmpty() || !isHandlerParameterCompatible(constantType, asmParamTypes[0])) {
            throw IllegalArgumentException(
                "ASM method ${asmMethod.name} first parameter must accept constant type $constantType, actual ${asmParamTypes.toList()}",
            )
        }

        val targetParamTypes = Type.getArgumentTypes(target.desc)
        val requestedTargetParamCount = asmParamTypes.size - 1
        if (requestedTargetParamCount > targetParamTypes.size) {
            throw IllegalArgumentException(
                "ASM method ${asmMethod.name} requests $requestedTargetParamCount target parameter(s), " +
                    "but target method ${target.name}${target.desc} has only ${targetParamTypes.size}",
            )
        }

        for (index in 0 until requestedTargetParamCount) {
            val expected = targetParamTypes[index]
            val actual = asmParamTypes[index + 1]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalArgumentException(
                    "ASM method ${asmMethod.name} parameter #${index + 1} ($actual) " +
                        "must match target method ${target.name}${target.desc} parameter #$index ($expected)",
                )
            }
        }
    }

    private fun loadTargetMethodParameters(
        il: InsnList,
        target: MethodNode,
        requestedTargetParamCount: Int,
    ) {
        if (requestedTargetParamCount <= 0) {
            return
        }

        var paramVarIndex = if ((target.access and Opcodes.ACC_STATIC) != 0) 0 else 1
        val targetParamTypes = Type.getArgumentTypes(target.desc)
        for (index in 0 until requestedTargetParamCount) {
            val paramType = targetParamTypes[index]
            InstructionUtil.loadParam(paramType, paramVarIndex).let { il.add(it) }
            paramVarIndex += paramType.size
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
