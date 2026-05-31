/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.injector.impl

import kim.der.asm.api.annotation.At
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.Slice
import kim.der.asm.data.AsmInfo
import kim.der.asm.injector.AbstractAsmInjector
import kim.der.asm.utils.transformer.InstructionUtil
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * WrapWithCondition 注入器。
 *
 * 该注入器会匹配目标方法内的 `void` 方法调用、返回 `void` 的 `invokedynamic` 调用、字段写入、简单数组元素写入、条件跳转或抛异常点，
 * 并在原指令前插入 boolean handler。
 * handler 返回 `true` 时恢复原调用的 receiver 与参数、字段写入值、数组写入栈参数、原条件跳转分支结果或原异常对象并继续执行原指令，
 * 返回 `false` 时跳过原指令、原条件跳转或原抛出。
 * [InjectionPoint.INVOKE] 未指定调用目标时，会按 handler 参数和 boolean 返回类型筛选兼容的 `void`
 * 普通调用或 `invokedynamic` 调用；构造器、非 `void` 调用和 handler 不兼容的调用不会计入 [WrapWithCondition.ordinal] 或命中数。
 * [InjectionPoint.FIELD_ASSIGN] 未指定字段目标时，会按 handler 字段 owner 参数、待写入值和 boolean 返回类型筛选
 * 兼容的字段写入，且不兼容候选不会计入 [WrapWithCondition.ordinal] 或命中数。
 * [InjectionPoint.JUMP] 未指定跳转目标时会匹配切片内全部条件跳转，`GOTO` 与 `JSR` 不支持条件包裹。
 * [InjectionPoint.THROW] 未指定异常类型目标时会匹配切片内全部 `ATHROW`；指定目标时只匹配前一条真实指令为同类型 `<init>` 的直接构造异常。
 * 构造器 `<init>` 虽然返回 `void`，但会消费未初始化对象，当前明确拒绝条件包裹。
 *
 * @param at 调用点定位；当前支持 [InjectionPoint.INVOKE]、[InjectionPoint.FIELD_ASSIGN]、[InjectionPoint.JUMP] 与 [InjectionPoint.THROW]
 * @param ordinal 匹配调用点序号；负数表示处理全部匹配调用点
 * @param slice 切片范围；当前 [InjectionPoint.INVOKE]、[InjectionPoint.FIELD_ASSIGN]、[InjectionPoint.JUMP] 与 [InjectionPoint.THROW] 条件包裹使用
 * INVOKE 边界缩小匹配范围
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
class WrapWithConditionInjector(
    method: Method,
    asmInfo: AsmInfo,
    private val at: At,
    private val ordinal: Int = -1,
    private val slice: Slice = Slice(),
) : AbstractAsmInjector(method, asmInfo) {
    /**
     * 在匹配的 `void` 方法调用、返回 `void` 的 `invokedynamic` 调用、字段写入、数组元素写入、条件跳转或抛异常点前插入条件包裹逻辑。
     *
     * @param target 目标方法
     * @return 至少包裹一个调用点、动态调用点、字段写入点、数组元素写入点、条件跳转点或抛异常点时返回 `true`
     * @throws IllegalArgumentException 定位点、目标调用、字段目标或 handler 签名不合法时抛出
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun inject(target: MethodNode): Boolean = injectCount(target) > 0

    /**
     * 在匹配的 `void` 方法调用、返回 `void` 的 `invokedynamic` 调用、字段写入、数组元素写入、条件跳转或抛异常点前插入条件包裹逻辑，并返回实际包裹数量。
     *
     * @param target 目标方法
     * @return 实际包裹的调用点、动态调用点、字段写入点、数组元素写入点、条件跳转点或抛异常点数量
     * @throws IllegalArgumentException 定位点、目标调用、字段目标或 handler 签名不合法时抛出
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun injectCount(target: MethodNode): Int {
        return when (at.value) {
            InjectionPoint.INVOKE -> injectMethodCall(target)
            InjectionPoint.FIELD_ASSIGN ->
                if (isArrayWriteMode()) {
                    injectArrayAssign(target)
                } else {
                    injectFieldAssign(target)
                }
            InjectionPoint.JUMP -> injectJump(target)
            InjectionPoint.THROW -> injectThrow(target)
            else -> throw IllegalArgumentException(
                "@WrapWithCondition supports only INVOKE, FIELD_ASSIGN, JUMP and THROW injection points",
            )
        }
    }

    /**
     * 解析数组写入条件包裹模式。
     *
     * 未声明 `array=` 参数时按普通字段写入处理；当前只支持 `array=set`。
     *
     * @return 声明 `array=set` 时返回 `true`，未声明时返回 `false`
     * @throws IllegalArgumentException 声明了不支持的数组访问模式时抛出
     */
    private fun isArrayWriteMode(): Boolean {
        val arrayArg = at.args.firstOrNull { it.trim().startsWith("array=") } ?: return false
        return when (arrayArg.substringAfter('=').trim().lowercase()) {
            "set" -> true
            "get" -> throw IllegalArgumentException("@WrapWithCondition array access supports only array=set")
            else -> throw IllegalArgumentException("Unsupported @WrapWithCondition array access mode: $arrayArg")
        }
    }

    /**
     * 在匹配的 `void` 普通方法调用或 `invokedynamic` 调用前插入条件 handler。
     *
     * 显式声明目标时按 owner、名称与描述符匹配；未声明目标时按 handler 签名筛选兼容的 `void` 调用。
     * handler 返回 `false` 时跳过原调用，返回 `true` 时恢复原 receiver 与调用参数并继续执行原调用。
     *
     * @param target 目标方法
     * @return 实际插入条件包裹逻辑的调用数量
     * @throws IllegalArgumentException 目标签名不完整、构造器或非 `void` 调用被显式匹配、handler 签名不兼容时抛出
     */
    private fun injectMethodCall(target: MethodNode): Int {
        val inferTarget = at.target.isEmpty()
        val (targetOwner, targetName, targetDesc) = parseTargetMethod(at.target)
        if (!inferTarget && (targetName == null || targetDesc == null)) {
            throw IllegalArgumentException("@WrapWithCondition INVOKE requires at.target method signature")
        }

        var injectionCount = 0
        var matchedOrdinal = 0
        val insns = target.instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)
        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            when {
                insn is MethodInsnNode &&
                    (inferTarget || (targetName != null && matchesTargetMethod(insn, targetOwner, targetName, targetDesc))) -> {
                    if (inferTarget && !isMethodCallConditionCompatible(target, insn)) {
                        continue
                    }

                    if (insn.name == "<init>") {
                        throw IllegalArgumentException(
                            "@WrapWithCondition does not support constructor calls, target ${insn.owner}.${insn.name}${insn.desc}",
                        )
                    }
                    if (Type.getReturnType(insn.desc) != Type.VOID_TYPE) {
                        throw IllegalArgumentException(
                            "@WrapWithCondition only supports void method calls, target ${insn.name}${insn.desc}",
                        )
                    }

                    val currentOrdinal = matchedOrdinal++
                    if (!matchesOrdinal(currentOrdinal)) {
                        continue
                    }

                    val targetParamCount = validateHandlerSignature(target, insn)
                    val skipOriginalLabel = LabelNode()
                    val il = buildConditionWrapper(target, insn, targetParamCount, skipOriginalLabel)
                    target.instructions.insertBefore(insn, il)
                    target.instructions.insert(insn, skipOriginalLabel)
                    injectionCount++
                }
                insn is InvokeDynamicInsnNode &&
                    (inferTarget || (targetName != null && matchesTargetInvokeDynamic(insn, targetOwner, targetName, targetDesc))) -> {
                    if (inferTarget && !isInvokeDynamicConditionCompatible(target, insn)) {
                        continue
                    }

                    if (Type.getReturnType(insn.desc) != Type.VOID_TYPE) {
                        throw IllegalArgumentException(
                            "@WrapWithCondition only supports void invokedynamic calls, target ${insn.name}${insn.desc}",
                        )
                    }

                    val currentOrdinal = matchedOrdinal++
                    if (!matchesOrdinal(currentOrdinal)) {
                        continue
                    }

                    val targetParamCount = validateInvokeDynamicHandlerSignature(target, insn)
                    val skipOriginalLabel = LabelNode()
                    val il = buildInvokeDynamicConditionWrapper(target, insn, targetParamCount, skipOriginalLabel)
                    target.instructions.insertBefore(insn, il)
                    target.instructions.insert(insn, skipOriginalLabel)
                    injectionCount++
                }
            }
        }

        return injectionCount
    }

    /**
     * 判断 handler 是否兼容候选普通方法调用。
     *
     * 该方法用于目标推断模式，构造器、非 `void` 调用或签名不兼容候选不会计入 ordinal 或命中数。
     *
     * @param target 目标方法
     * @param insn 候选方法调用指令
     * @return handler 可条件包裹该调用时返回 `true`
     */
    private fun isMethodCallConditionCompatible(
        target: MethodNode,
        insn: MethodInsnNode,
    ): Boolean {
        if (insn.name == "<init>" || Type.getReturnType(insn.desc) != Type.VOID_TYPE) {
            return false
        }
        return runCatching { validateHandlerSignature(target, insn) }.isSuccess
    }

    /**
     * 判断 handler 是否兼容候选 `invokedynamic` 调用。
     *
     * 该方法用于目标推断模式，非 `void` 动态调用或签名不兼容候选不会计入 ordinal 或命中数。
     *
     * @param target 目标方法
     * @param insn 候选 `invokedynamic` 指令
     * @return handler 可条件包裹该动态调用时返回 `true`
     */
    private fun isInvokeDynamicConditionCompatible(
        target: MethodNode,
        insn: InvokeDynamicInsnNode,
    ): Boolean {
        if (Type.getReturnType(insn.desc) != Type.VOID_TYPE) {
            return false
        }
        return runCatching { validateInvokeDynamicHandlerSignature(target, insn) }.isSuccess
    }

    /**
     * 在匹配的字段写入前插入条件 handler。
     *
     * 显式声明字段目标时按 owner、名称与描述符匹配；未声明目标时按 handler 签名筛选兼容字段写入。
     * handler 返回 `false` 时跳过原字段写入，返回 `true` 时恢复 receiver 与待写入值并继续执行原写入。
     *
     * @param target 目标方法
     * @return 实际插入条件包裹逻辑的字段写入数量
     * @throws IllegalArgumentException 字段目标缺少名称或 handler 签名不兼容时抛出
     */
    private fun injectFieldAssign(target: MethodNode): Int {
        val inferTarget = at.target.isEmpty()
        val fieldTarget = parseFieldTarget(at.target)
        if (!inferTarget && fieldTarget.name == null) {
            throw IllegalArgumentException("@WrapWithCondition FIELD_ASSIGN requires at.target field signature")
        }

        var injectionCount = 0
        var matchedOrdinal = 0
        val insns = target.instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)
        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (
                insn !is FieldInsnNode ||
                insn.opcode !in FIELD_WRITE_OPS ||
                !(inferTarget || matchesTargetField(insn, fieldTarget))
            ) {
                continue
            }
            if (inferTarget && !isFieldAssignHandlerCompatible(target, insn)) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val targetParamCount = validateFieldAssignHandlerSignature(target, insn)
            val skipOriginalLabel = LabelNode()
            val il = buildFieldAssignConditionWrapper(target, insn, targetParamCount, skipOriginalLabel)
            target.instructions.insertBefore(insn, il)
            target.instructions.insert(insn, skipOriginalLabel)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 判断 handler 是否兼容候选字段写入。
     *
     * 该方法用于目标推断模式，签名不兼容候选不会计入 ordinal 或命中数。
     *
     * @param target 目标方法
     * @param insn 候选字段写入指令
     * @return handler 可条件包裹该字段写入时返回 `true`
     */
    private fun isFieldAssignHandlerCompatible(
        target: MethodNode,
        insn: FieldInsnNode,
    ): Boolean = runCatching { validateFieldAssignHandlerSignature(target, insn) }.isSuccess

    /**
     * 在匹配的数组元素写入前插入条件 handler。
     *
     * 该入口通过 `array=set` 启用，并要求 `at.target` 指向数组字段。
     * 方法会从数组写入指令向前追踪数组字段来源，只包裹来源字段匹配的数组元素写入。
     *
     * @param target 目标方法
     * @return 实际插入条件包裹逻辑的数组元素写入数量
     * @throws IllegalArgumentException 数组字段目标缺失、目标不是数组字段或 handler 签名不兼容时抛出
     */
    private fun injectArrayAssign(target: MethodNode): Int {
        val fieldTarget = parseFieldTarget(at.target)
        if (fieldTarget.name == null) {
            throw IllegalArgumentException("@WrapWithCondition array write requires at.target array field signature")
        }
        if (fieldTarget.desc != null && Type.getType(fieldTarget.desc).sort != Type.ARRAY) {
            throw IllegalArgumentException("@WrapWithCondition array write target must be an array field: ${at.target}")
        }

        var injectionCount = 0
        var matchedOrdinal = 0
        val insns = target.instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)
        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (insn.opcode !in ARRAY_WRITE_OPS) {
                continue
            }

            val fieldInsn = findArrayFieldProducer(insn, fieldTarget) ?: continue
            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val targetParamCount = validateArrayAssignHandlerSignature(target, fieldInsn)
            val skipOriginalLabel = LabelNode()
            val il = buildArrayAssignConditionWrapper(target, fieldInsn, targetParamCount, skipOriginalLabel)
            target.instructions.insertBefore(insn, il)
            target.instructions.insert(insn, skipOriginalLabel)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 条件包裹条件跳转的原始分支结果。
     *
     * `at.target` 可声明具体条件跳转 opcode 名称或数字；未声明时匹配所有条件跳转。
     * handler 返回 `false` 时跳过原跳转逻辑，返回 `true` 时按原始分支结果继续分派。
     *
     * @param target 目标方法
     * @return 实际包裹的条件跳转数量
     * @throws IllegalArgumentException 目标 opcode 不是条件跳转或 handler 签名不兼容时抛出
     */
    private fun injectJump(target: MethodNode): Int {
        val targetOpcode = parseJumpOpcodeTarget(at.target)
        if (targetOpcode != null && targetOpcode !in CONDITIONAL_JUMP_OPS) {
            throw IllegalArgumentException(
                "@WrapWithCondition JUMP target must be a conditional JVM jump opcode: ${at.target}",
            )
        }

        var injectionCount = 0
        var matchedOrdinal = 0
        val instructions = target.instructions
        val insns = instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)

        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (
                insn !is JumpInsnNode ||
                insn.opcode !in CONDITIONAL_JUMP_OPS ||
                (targetOpcode != null && insn.opcode != targetOpcode)
            ) {
                continue
            }
            if (targetOpcode == null && !isJumpHandlerCompatible(target)) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val targetParamCount = validateJumpHandlerSignature(target)
            val il = buildJumpConditionWrapper(insn, target, targetParamCount)
            instructions.insertBefore(insn, il)
            instructions.remove(insn)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 判断 handler 是否兼容候选条件跳转。
     *
     * 该方法用于目标推断模式，签名不兼容候选不会计入 ordinal 或命中数。
     *
     * @param target 目标方法
     * @return handler 可条件包裹条件跳转时返回 `true`
     */
    private fun isJumpHandlerCompatible(target: MethodNode): Boolean =
        runCatching { validateJumpHandlerSignature(target) }.isSuccess

    /**
     * 在匹配的 `ATHROW` 前插入条件 handler。
     *
     * 未声明 `at.target` 时匹配所有兼容 `ATHROW`；声明类型目标时，仅匹配前一条真实指令为同 owner `<init>` 的直接构造异常。
     * handler 返回 `false` 时跳过原抛出，返回 `true` 时恢复原异常对象并继续执行 `ATHROW`。
     *
     * @param target 目标方法
     * @return 实际插入条件包裹逻辑的异常抛出数量
     * @throws IllegalArgumentException handler 签名不兼容时抛出
     */
    private fun injectThrow(target: MethodNode): Int {
        val normalizedTarget = at.target.replace('.', '/')
        val inferTarget = normalizedTarget.isEmpty()
        var injectionCount = 0
        var matchedOrdinal = 0
        val insns = target.instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)

        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (insn.opcode != Opcodes.ATHROW) {
                continue
            }
            if (!inferTarget && directThrownTypeInternalName(insn) != normalizedTarget) {
                continue
            }
            if (inferTarget && !isThrowHandlerCompatible(target)) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val targetParamCount = validateThrowHandlerSignature(target)
            val skipOriginalLabel = LabelNode()
            val il = buildThrowConditionWrapper(target, targetParamCount, skipOriginalLabel)
            target.instructions.insertBefore(insn, il)
            target.instructions.insert(insn, skipOriginalLabel)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 判断 handler 是否兼容候选异常抛出操作。
     *
     * 该方法用于目标推断模式，签名不兼容候选不会计入 ordinal 或命中数。
     *
     * @param target 目标方法
     * @return handler 可条件包裹异常抛出时返回 `true`
     */
    private fun isThrowHandlerCompatible(target: MethodNode): Boolean =
        runCatching { validateThrowHandlerSignature(target) }.isSuccess

    /**
     * 构造普通方法调用条件包裹的前置指令序列。
     *
     * 序列会暂存原 receiver 与调用参数，调用 boolean handler；
     * handler 返回 `false` 时跳转到原调用后的跳过标签，返回 `true` 时恢复 receiver 与参数供原调用继续消费。
     *
     * @param target 目标方法
     * @param callInsn 被条件包裹的方法调用指令
     * @param targetParamCount handler 追加接收的目标方法参数数量
     * @param skipOriginalLabel handler 返回 `false` 时跳转到的标签
     * @return 插入到原方法调用前的条件包裹指令列表
     */
    private fun buildConditionWrapper(
        target: MethodNode,
        callInsn: MethodInsnNode,
        targetParamCount: Int,
        skipOriginalLabel: LabelNode,
    ): InsnList {
        val il = InsnList()
        val callParamTypes = Type.getArgumentTypes(callInsn.desc)
        var nextTempIndex = nextLocalIndex(target)
        val receiverIndex =
            if (callInsn.opcode == Opcodes.INVOKESTATIC) {
                null
            } else {
                nextTempIndex.also { nextTempIndex += 1 }
            }
        val argSlots =
            callParamTypes.map { paramType ->
                nextTempIndex.also { nextTempIndex += paramType.size }
            }

        for (index in callParamTypes.indices.reversed()) {
            storeStackValue(il, callParamTypes[index], argSlots[index])
        }
        if (receiverIndex != null) {
            il.add(VarInsnNode(Opcodes.ASTORE, receiverIndex))
        }

        addHandlerOwner(il)
        if (receiverIndex != null) {
            il.add(VarInsnNode(Opcodes.ALOAD, receiverIndex))
        }
        for (index in callParamTypes.indices) {
            loadFromVariable(il, callParamTypes[index], argSlots[index])
        }
        loadTargetMethodParameters(il, target, targetParamCount)
        il.add(
            MethodInsnNode(
                handlerOpcode(),
                Type.getType(asmInfo.asmClass).internalName,
                asmMethod.name,
                Type.getMethodDescriptor(asmMethod),
                false,
            ),
        )
        il.add(JumpInsnNode(Opcodes.IFEQ, skipOriginalLabel))

        if (receiverIndex != null) {
            il.add(VarInsnNode(Opcodes.ALOAD, receiverIndex))
        }
        for (index in callParamTypes.indices) {
            loadFromVariable(il, callParamTypes[index], argSlots[index])
        }

        return il
    }

    /**
     * 构造 `invokedynamic` 调用条件包裹的前置指令序列。
     *
     * 序列会暂存动态调用参数，调用 boolean handler；
     * handler 返回 `false` 时跳转到原动态调用后的跳过标签，返回 `true` 时恢复参数供原 `invokedynamic` 继续消费。
     *
     * @param target 目标方法
     * @param callInsn 被条件包裹的 `invokedynamic` 指令
     * @param targetParamCount handler 追加接收的目标方法参数数量
     * @param skipOriginalLabel handler 返回 `false` 时跳转到的标签
     * @return 插入到原动态调用前的条件包裹指令列表
     */
    private fun buildInvokeDynamicConditionWrapper(
        target: MethodNode,
        callInsn: InvokeDynamicInsnNode,
        targetParamCount: Int,
        skipOriginalLabel: LabelNode,
    ): InsnList {
        val il = InsnList()
        val callParamTypes = Type.getArgumentTypes(callInsn.desc)
        var nextTempIndex = nextLocalIndex(target)
        val argSlots =
            callParamTypes.map { paramType ->
                nextTempIndex.also { nextTempIndex += paramType.size }
            }

        for (index in callParamTypes.indices.reversed()) {
            storeStackValue(il, callParamTypes[index], argSlots[index])
        }

        addHandlerOwner(il)
        for (index in callParamTypes.indices) {
            loadFromVariable(il, callParamTypes[index], argSlots[index])
        }
        loadTargetMethodParameters(il, target, targetParamCount)
        il.add(
            MethodInsnNode(
                handlerOpcode(),
                Type.getType(asmInfo.asmClass).internalName,
                asmMethod.name,
                Type.getMethodDescriptor(asmMethod),
                false,
            ),
        )
        il.add(JumpInsnNode(Opcodes.IFEQ, skipOriginalLabel))

        for (index in callParamTypes.indices) {
            loadFromVariable(il, callParamTypes[index], argSlots[index])
        }

        return il
    }

    /**
     * 构造字段写入条件包裹的前置指令序列。
     *
     * 序列会暂存实例 receiver 与待写入值，调用 boolean handler；
     * handler 返回 `false` 时跳过原字段写入，返回 `true` 时恢复 receiver 与值供原 `PUTFIELD` 或 `PUTSTATIC` 消费。
     *
     * @param target 目标方法
     * @param fieldInsn 被条件包裹的字段写入指令
     * @param targetParamCount handler 追加接收的目标方法参数数量
     * @param skipOriginalLabel handler 返回 `false` 时跳转到的标签
     * @return 插入到原字段写入前的条件包裹指令列表
     */
    private fun buildFieldAssignConditionWrapper(
        target: MethodNode,
        fieldInsn: FieldInsnNode,
        targetParamCount: Int,
        skipOriginalLabel: LabelNode,
    ): InsnList {
        val il = InsnList()
        val fieldType = Type.getType(fieldInsn.desc)
        var nextTempIndex = nextLocalIndex(target)
        val receiverIndex =
            if (fieldInsn.opcode == Opcodes.PUTSTATIC) {
                null
            } else {
                nextTempIndex.also { nextTempIndex += 1 }
            }
        val valueIndex = nextTempIndex.also { nextTempIndex += fieldType.size }

        storeStackValue(il, fieldType, valueIndex)
        if (receiverIndex != null) {
            il.add(VarInsnNode(Opcodes.ASTORE, receiverIndex))
        }

        addHandlerOwner(il)
        if (receiverIndex != null) {
            il.add(VarInsnNode(Opcodes.ALOAD, receiverIndex))
        }
        loadFromVariable(il, fieldType, valueIndex)
        loadTargetMethodParameters(il, target, targetParamCount)
        il.add(
            MethodInsnNode(
                handlerOpcode(),
                Type.getType(asmInfo.asmClass).internalName,
                asmMethod.name,
                Type.getMethodDescriptor(asmMethod),
                false,
            ),
        )
        il.add(JumpInsnNode(Opcodes.IFEQ, skipOriginalLabel))

        if (receiverIndex != null) {
            il.add(VarInsnNode(Opcodes.ALOAD, receiverIndex))
        }
        loadFromVariable(il, fieldType, valueIndex)

        return il
    }

    /**
     * 构造数组元素写入条件包裹的前置指令序列。
     *
     * 序列会暂存数组引用、索引与待写入元素值，调用 boolean handler；
     * handler 返回 `false` 时跳过原数组写入，返回 `true` 时恢复数组写入所需的三段栈参数。
     *
     * @param target 目标方法
     * @param fieldInsn 产生数组引用的字段读取指令
     * @param targetParamCount handler 追加接收的目标方法参数数量
     * @param skipOriginalLabel handler 返回 `false` 时跳转到的标签
     * @return 插入到原数组写入前的条件包裹指令列表
     */
    private fun buildArrayAssignConditionWrapper(
        target: MethodNode,
        fieldInsn: FieldInsnNode,
        targetParamCount: Int,
        skipOriginalLabel: LabelNode,
    ): InsnList {
        val il = InsnList()
        val arrayType = Type.getType(fieldInsn.desc)
        val elementType = arrayType.elementType
        var nextTempIndex = nextLocalIndex(target)
        val arrayIndex = nextTempIndex.also { nextTempIndex += 1 }
        val indexIndex = nextTempIndex.also { nextTempIndex += 1 }
        val valueIndex = nextTempIndex.also { nextTempIndex += elementType.size }

        storeStackValue(il, elementType, valueIndex)
        storeStackValue(il, Type.INT_TYPE, indexIndex)
        storeStackValue(il, arrayType, arrayIndex)

        addHandlerOwner(il)
        loadFromVariable(il, arrayType, arrayIndex)
        loadFromVariable(il, Type.INT_TYPE, indexIndex)
        loadFromVariable(il, elementType, valueIndex)
        loadTargetMethodParameters(il, target, targetParamCount)
        il.add(
            MethodInsnNode(
                handlerOpcode(),
                Type.getType(asmInfo.asmClass).internalName,
                asmMethod.name,
                Type.getMethodDescriptor(asmMethod),
                false,
            ),
        )
        il.add(JumpInsnNode(Opcodes.IFEQ, skipOriginalLabel))

        loadFromVariable(il, arrayType, arrayIndex)
        loadFromVariable(il, Type.INT_TYPE, indexIndex)
        loadFromVariable(il, elementType, valueIndex)

        return il
    }

    /**
     * 构造条件跳转包裹的替代指令序列。
     *
     * 序列会先按原跳转条件生成 boolean 分支值并暂存，调用 handler 判断是否允许原跳转逻辑继续；
     * handler 返回 `true` 时按原分支值跳转，返回 `false` 时直接落到原跳转后的指令。
     *
     * @param jumpInsn 被条件包裹的跳转指令
     * @param target 目标方法
     * @param targetParamCount handler 追加接收的目标方法参数数量
     * @return 可替换原条件跳转的指令列表
     */
    private fun buildJumpConditionWrapper(
        jumpInsn: JumpInsnNode,
        target: MethodNode,
        targetParamCount: Int,
    ): InsnList {
        val originalTrue = LabelNode()
        val afterOriginal = LabelNode()
        val skipOriginal = LabelNode()
        val originalIndex = nextLocalIndex(target)
        val il = InsnList()

        il.add(JumpInsnNode(jumpInsn.opcode, originalTrue))
        il.add(InsnNode(Opcodes.ICONST_0))
        il.add(JumpInsnNode(Opcodes.GOTO, afterOriginal))
        il.add(originalTrue)
        il.add(InsnNode(Opcodes.ICONST_1))
        il.add(afterOriginal)
        il.add(VarInsnNode(Opcodes.ISTORE, originalIndex))
        addHandlerOwner(il)
        il.add(VarInsnNode(Opcodes.ILOAD, originalIndex))
        loadTargetMethodParameters(il, target, targetParamCount)
        il.add(
            MethodInsnNode(
                handlerOpcode(),
                Type.getType(asmInfo.asmClass).internalName,
                asmMethod.name,
                Type.getMethodDescriptor(asmMethod),
                false,
            ),
        )
        il.add(JumpInsnNode(Opcodes.IFEQ, skipOriginal))
        il.add(VarInsnNode(Opcodes.ILOAD, originalIndex))
        il.add(JumpInsnNode(Opcodes.IFNE, jumpInsn.label))
        il.add(skipOriginal)

        return il
    }

    /**
     * 构造异常抛出条件包裹的前置指令序列。
     *
     * 序列会暂存原异常对象，调用 boolean handler；
     * handler 返回 `false` 时跳过原 `ATHROW`，返回 `true` 时恢复异常对象供原 `ATHROW` 消费。
     *
     * @param target 目标方法
     * @param targetParamCount handler 追加接收的目标方法参数数量
     * @param skipOriginalLabel handler 返回 `false` 时跳转到的标签
     * @return 插入到原 `ATHROW` 前的条件包裹指令列表
     */
    private fun buildThrowConditionWrapper(
        target: MethodNode,
        targetParamCount: Int,
        skipOriginalLabel: LabelNode,
    ): InsnList {
        val il = InsnList()
        val throwableType = Type.getType(Throwable::class.java)
        val throwableIndex = nextLocalIndex(target)

        il.add(VarInsnNode(Opcodes.ASTORE, throwableIndex))
        addHandlerOwner(il)
        il.add(VarInsnNode(Opcodes.ALOAD, throwableIndex))
        loadTargetMethodParameters(il, target, targetParamCount)
        il.add(
            MethodInsnNode(
                handlerOpcode(),
                Type.getType(asmInfo.asmClass).internalName,
                asmMethod.name,
                Type.getMethodDescriptor(asmMethod),
                false,
            ),
        )
        il.add(JumpInsnNode(Opcodes.IFEQ, skipOriginalLabel))
        il.add(VarInsnNode(Opcodes.ALOAD, throwableIndex))

        return il
    }

    /**
     * 校验普通方法调用条件包裹的 handler 签名。
     *
     * handler 必须返回 `boolean`，并按原调用栈顺序接收被调用实例与调用参数；
     * `INVOKESTATIC` 不需要实例参数，额外参数会被解释为目标方法开头的参数前缀。
     *
     * @param target 目标方法
     * @param callInsn 被条件包裹的普通方法调用指令
     * @return handler 追加接收的目标方法参数数量
     * @throws IllegalArgumentException handler 返回值、原调用参数或追加目标参数不兼容时抛出
     */
    private fun validateHandlerSignature(
        target: MethodNode,
        callInsn: MethodInsnNode,
    ): Int {
        val returnType = Type.getReturnType(asmMethod)
        if (returnType.sort != Type.BOOLEAN) {
            throw IllegalArgumentException(
                "@WrapWithCondition handler ${asmMethod.name} must return boolean, actual $returnType",
            )
        }

        val expectedCallParams = buildExpectedHandlerParams(callInsn)
        val actualParams = Type.getArgumentTypes(asmMethod)
        if (actualParams.size < expectedCallParams.size) {
            throw IllegalArgumentException(
                "@WrapWithCondition handler ${asmMethod.name} parameter count mismatch: " +
                    "expected at least ${expectedCallParams.toList()}, actual ${actualParams.toList()}",
            )
        }

        expectedCallParams.forEachIndexed { index, expected ->
            val actual = actualParams[index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalArgumentException(
                    "@WrapWithCondition handler ${asmMethod.name} parameter #$index mismatch: " +
                        "expected $expected, actual $actual",
                )
            }
        }

        val targetParamTypes = Type.getArgumentTypes(target.desc)
        val requestedTargetParamCount = actualParams.size - expectedCallParams.size
        if (requestedTargetParamCount > targetParamTypes.size) {
            throw IllegalArgumentException(
                "@WrapWithCondition handler ${asmMethod.name} requests " +
                    "$requestedTargetParamCount target parameter(s), " +
                    "but target method ${target.name}${target.desc} has only ${targetParamTypes.size}",
            )
        }

        for (index in 0 until requestedTargetParamCount) {
            val expected = targetParamTypes[index]
            val actual = actualParams[expectedCallParams.size + index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalArgumentException(
                    "@WrapWithCondition handler ${asmMethod.name} target parameter #$index mismatch: " +
                        "expected $expected, actual $actual",
                )
            }
        }

        return requestedTargetParamCount
    }

    /**
     * 校验 `invokedynamic` 调用条件包裹的 handler 签名。
     *
     * handler 必须返回 `boolean`，前缀参数需要与动态调用点描述符的参数兼容；
     * 其余参数会被解释为目标方法开头的参数前缀。
     *
     * @param target 目标方法
     * @param callInsn 被条件包裹的 `invokedynamic` 调用指令
     * @return handler 追加接收的目标方法参数数量
     * @throws IllegalArgumentException handler 返回值、动态调用参数或追加目标参数不兼容时抛出
     */
    private fun validateInvokeDynamicHandlerSignature(
        target: MethodNode,
        callInsn: InvokeDynamicInsnNode,
    ): Int {
        val returnType = Type.getReturnType(asmMethod)
        if (returnType.sort != Type.BOOLEAN) {
            throw IllegalArgumentException(
                "@WrapWithCondition handler ${asmMethod.name} must return boolean, actual $returnType",
            )
        }

        val expectedCallParams = Type.getArgumentTypes(callInsn.desc)
        val actualParams = Type.getArgumentTypes(asmMethod)
        if (actualParams.size < expectedCallParams.size) {
            throw IllegalArgumentException(
                "@WrapWithCondition handler ${asmMethod.name} parameter count mismatch: " +
                    "expected at least ${expectedCallParams.toList()}, actual ${actualParams.toList()}",
            )
        }

        expectedCallParams.forEachIndexed { index, expected ->
            val actual = actualParams[index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalArgumentException(
                    "@WrapWithCondition handler ${asmMethod.name} parameter #$index mismatch: " +
                        "expected $expected, actual $actual",
                )
            }
        }

        val targetParamTypes = Type.getArgumentTypes(target.desc)
        val requestedTargetParamCount = actualParams.size - expectedCallParams.size
        if (requestedTargetParamCount > targetParamTypes.size) {
            throw IllegalArgumentException(
                "@WrapWithCondition handler ${asmMethod.name} requests " +
                    "$requestedTargetParamCount target parameter(s), " +
                    "but target method ${target.name}${target.desc} has only ${targetParamTypes.size}",
            )
        }

        for (index in 0 until requestedTargetParamCount) {
            val expected = targetParamTypes[index]
            val actual = actualParams[expectedCallParams.size + index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalArgumentException(
                    "@WrapWithCondition handler ${asmMethod.name} target parameter #$index mismatch: " +
                        "expected $expected, actual $actual",
                )
            }
        }

        return requestedTargetParamCount
    }

    /**
     * 校验字段写入条件包裹的 handler 签名。
     *
     * handler 必须返回 `boolean`；静态字段写入接收待写入值，实例字段写入先接收字段 owner 再接收待写入值。
     * 额外参数会被解释为目标方法开头的参数前缀。
     *
     * @param target 目标方法
     * @param fieldInsn 被条件包裹的字段写入指令
     * @return handler 追加接收的目标方法参数数量
     * @throws IllegalArgumentException handler 返回值、字段写入参数或追加目标参数不兼容时抛出
     */
    private fun validateFieldAssignHandlerSignature(
        target: MethodNode,
        fieldInsn: FieldInsnNode,
    ): Int {
        val returnType = Type.getReturnType(asmMethod)
        if (returnType.sort != Type.BOOLEAN) {
            throw IllegalArgumentException(
                "@WrapWithCondition handler ${asmMethod.name} must return boolean, actual $returnType",
            )
        }

        val expectedFieldParams = buildExpectedFieldAssignHandlerParams(fieldInsn)
        val actualParams = Type.getArgumentTypes(asmMethod)
        if (actualParams.size < expectedFieldParams.size) {
            throw IllegalArgumentException(
                "@WrapWithCondition handler ${asmMethod.name} parameter count mismatch: " +
                    "expected at least ${expectedFieldParams.toList()}, actual ${actualParams.toList()}",
            )
        }

        expectedFieldParams.forEachIndexed { index, expected ->
            val actual = actualParams[index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalArgumentException(
                    "@WrapWithCondition handler ${asmMethod.name} parameter #$index mismatch: " +
                        "expected $expected, actual $actual",
                )
            }
        }

        val targetParamTypes = Type.getArgumentTypes(target.desc)
        val requestedTargetParamCount = actualParams.size - expectedFieldParams.size
        if (requestedTargetParamCount > targetParamTypes.size) {
            throw IllegalArgumentException(
                "@WrapWithCondition handler ${asmMethod.name} requests " +
                    "$requestedTargetParamCount target parameter(s), " +
                    "but target method ${target.name}${target.desc} has only ${targetParamTypes.size}",
            )
        }

        for (index in 0 until requestedTargetParamCount) {
            val expected = targetParamTypes[index]
            val actual = actualParams[expectedFieldParams.size + index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalArgumentException(
                    "@WrapWithCondition handler ${asmMethod.name} target parameter #$index mismatch: " +
                        "expected $expected, actual $actual",
                )
            }
        }

        return requestedTargetParamCount
    }

    /**
     * 校验数组元素写入条件包裹的 handler 签名。
     *
     * handler 必须返回 `boolean`，并依次接收数组引用、元素索引与待写入元素值；
     * 额外参数会被解释为目标方法开头的参数前缀。
     *
     * @param target 目标方法
     * @param fieldInsn 产生数组引用的字段读取指令
     * @return handler 追加接收的目标方法参数数量
     * @throws IllegalArgumentException handler 返回值、数组写入参数或追加目标参数不兼容时抛出
     */
    private fun validateArrayAssignHandlerSignature(
        target: MethodNode,
        fieldInsn: FieldInsnNode,
    ): Int {
        val returnType = Type.getReturnType(asmMethod)
        if (returnType.sort != Type.BOOLEAN) {
            throw IllegalArgumentException(
                "@WrapWithCondition handler ${asmMethod.name} must return boolean, actual $returnType",
            )
        }

        val expectedArrayParams = buildExpectedArrayAssignHandlerParams(fieldInsn)
        val actualParams = Type.getArgumentTypes(asmMethod)
        if (actualParams.size < expectedArrayParams.size) {
            throw IllegalArgumentException(
                "@WrapWithCondition handler ${asmMethod.name} parameter count mismatch: " +
                    "expected at least ${expectedArrayParams.toList()}, actual ${actualParams.toList()}",
            )
        }

        expectedArrayParams.forEachIndexed { index, expected ->
            val actual = actualParams[index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalArgumentException(
                    "@WrapWithCondition handler ${asmMethod.name} parameter #$index mismatch: " +
                        "expected $expected, actual $actual",
                )
            }
        }

        val targetParamTypes = Type.getArgumentTypes(target.desc)
        val requestedTargetParamCount = actualParams.size - expectedArrayParams.size
        if (requestedTargetParamCount > targetParamTypes.size) {
            throw IllegalArgumentException(
                "@WrapWithCondition handler ${asmMethod.name} requests " +
                    "$requestedTargetParamCount target parameter(s), " +
                    "but target method ${target.name}${target.desc} has only ${targetParamTypes.size}",
            )
        }

        for (index in 0 until requestedTargetParamCount) {
            val expected = targetParamTypes[index]
            val actual = actualParams[expectedArrayParams.size + index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalArgumentException(
                    "@WrapWithCondition handler ${asmMethod.name} target parameter #$index mismatch: " +
                        "expected $expected, actual $actual",
                )
            }
        }

        return requestedTargetParamCount
    }

    /**
     * 校验条件跳转包裹的 handler 签名。
     *
     * handler 必须返回 `boolean`，首参接收原条件跳转结果；
     * 其余参数会被解释为目标方法开头的参数前缀。
     *
     * @param target 目标方法
     * @return handler 追加接收的目标方法参数数量
     * @throws IllegalArgumentException handler 返回值、原分支结果参数或追加目标参数不兼容时抛出
     */
    private fun validateJumpHandlerSignature(target: MethodNode): Int {
        val returnType = Type.getReturnType(asmMethod)
        if (returnType.sort != Type.BOOLEAN) {
            throw IllegalArgumentException(
                "@WrapWithCondition handler ${asmMethod.name} must return boolean, actual $returnType",
            )
        }

        val actualParams = Type.getArgumentTypes(asmMethod)
        if (actualParams.isEmpty()) {
            throw IllegalArgumentException(
                "@WrapWithCondition JUMP handler ${asmMethod.name} must receive original branch result",
            )
        }
        if (!isHandlerParameterCompatible(Type.BOOLEAN_TYPE, actualParams[0])) {
            throw IllegalArgumentException(
                "@WrapWithCondition handler ${asmMethod.name} parameter #0 mismatch: " +
                    "expected ${Type.BOOLEAN_TYPE}, actual ${actualParams[0]}",
            )
        }

        val targetParamTypes = Type.getArgumentTypes(target.desc)
        val requestedTargetParamCount = actualParams.size - 1
        if (requestedTargetParamCount > targetParamTypes.size) {
            throw IllegalArgumentException(
                "@WrapWithCondition handler ${asmMethod.name} requests " +
                    "$requestedTargetParamCount target parameter(s), " +
                    "but target method ${target.name}${target.desc} has only ${targetParamTypes.size}",
            )
        }

        for (index in 0 until requestedTargetParamCount) {
            val expected = targetParamTypes[index]
            val actual = actualParams[1 + index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalArgumentException(
                    "@WrapWithCondition handler ${asmMethod.name} target parameter #$index mismatch: " +
                        "expected $expected, actual $actual",
                )
            }
        }

        return requestedTargetParamCount
    }

    /**
     * 校验异常抛出条件包裹的 handler 签名。
     *
     * handler 必须返回 `boolean`，首参接收原始异常对象；
     * 其余参数会被解释为目标方法开头的参数前缀。
     *
     * @param target 目标方法
     * @return handler 追加接收的目标方法参数数量
     * @throws IllegalArgumentException handler 返回值、异常参数或追加目标参数不兼容时抛出
     */
    private fun validateThrowHandlerSignature(target: MethodNode): Int {
        val returnType = Type.getReturnType(asmMethod)
        if (returnType.sort != Type.BOOLEAN) {
            throw IllegalArgumentException(
                "@WrapWithCondition handler ${asmMethod.name} must return boolean, actual $returnType",
            )
        }

        val actualParams = Type.getArgumentTypes(asmMethod)
        val throwableType = Type.getType(Throwable::class.java)
        if (actualParams.isEmpty()) {
            throw IllegalArgumentException(
                "@WrapWithCondition THROW handler ${asmMethod.name} must receive original throwable",
            )
        }
        if (!isHandlerParameterCompatible(throwableType, actualParams[0])) {
            throw IllegalArgumentException(
                "@WrapWithCondition handler ${asmMethod.name} parameter #0 mismatch: " +
                    "expected $throwableType, actual ${actualParams[0]}",
            )
        }

        val targetParamTypes = Type.getArgumentTypes(target.desc)
        val requestedTargetParamCount = actualParams.size - 1
        if (requestedTargetParamCount > targetParamTypes.size) {
            throw IllegalArgumentException(
                "@WrapWithCondition handler ${asmMethod.name} requests " +
                    "$requestedTargetParamCount target parameter(s), " +
                    "but target method ${target.name}${target.desc} has only ${targetParamTypes.size}",
            )
        }

        for (index in 0 until requestedTargetParamCount) {
            val expected = targetParamTypes[index]
            val actual = actualParams[1 + index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalArgumentException(
                    "@WrapWithCondition handler ${asmMethod.name} target parameter #$index mismatch: " +
                        "expected $expected, actual $actual",
                )
            }
        }

        return requestedTargetParamCount
    }

    private fun buildExpectedHandlerParams(callInsn: MethodInsnNode): Array<Type> {
        val callParams = Type.getArgumentTypes(callInsn.desc).toList()
        return if (callInsn.opcode == Opcodes.INVOKESTATIC) {
            callParams.toTypedArray()
        } else {
            (listOf(Type.getObjectType(callInsn.owner)) + callParams).toTypedArray()
        }
    }

    private fun buildExpectedFieldAssignHandlerParams(fieldInsn: FieldInsnNode): Array<Type> {
        val fieldType = Type.getType(fieldInsn.desc)
        return if (fieldInsn.opcode == Opcodes.PUTSTATIC) {
            arrayOf(fieldType)
        } else {
            arrayOf(Type.getObjectType(fieldInsn.owner), fieldType)
        }
    }

    private fun buildExpectedArrayAssignHandlerParams(fieldInsn: FieldInsnNode): Array<Type> {
        val arrayType = Type.getType(fieldInsn.desc)
        return arrayOf(arrayType, Type.INT_TYPE, arrayType.elementType)
    }

    private fun findArrayFieldProducer(
        arrayInsn: AbstractInsnNode,
        target: FieldTarget,
    ): FieldInsnNode? {
        var cursor = arrayInsn.previous
        while (cursor != null) {
            if (cursor is FieldInsnNode) {
                if (cursor.opcode in FIELD_READ_OPS && matchesTargetField(cursor, target)) {
                    val fieldType = Type.getType(cursor.desc)
                    if (fieldType.sort != Type.ARRAY) {
                        throw IllegalArgumentException(
                            "@WrapWithCondition array write target must be an array field: " +
                                "${cursor.owner}.${cursor.name}:${cursor.desc}",
                        )
                    }
                    return cursor
                }
                return null
            }
            if (cursor is MethodInsnNode || cursor.opcode in ARRAY_WRITE_OPS) {
                return null
            }
            cursor = cursor.previous
        }
        return null
    }

    private fun directThrownTypeInternalName(throwInsn: AbstractInsnNode): String? {
        val previous = previousRealInstruction(throwInsn)
        if (previous is MethodInsnNode &&
            previous.opcode == Opcodes.INVOKESPECIAL &&
            previous.name == "<init>"
        ) {
            return previous.owner
        }
        return null
    }

    private fun previousRealInstruction(insn: AbstractInsnNode): AbstractInsnNode? {
        var current = insn.previous
        while (current != null && current.opcode < 0) {
            current = current.previous
        }
        return current
    }

    private fun parseJumpOpcodeTarget(target: String): Int? {
        if (target.isEmpty()) {
            return null
        }

        val normalized = target.trim().uppercase()
        normalized.toIntOrNull()?.let { opcode ->
            require(opcode in JUMP_OPS) {
                "@WrapWithCondition JUMP target opcode must be a JVM jump opcode: $target"
            }
            return opcode
        }

        return JUMP_OPCODE_NAMES[normalized]
            ?: throw IllegalArgumentException(
                "@WrapWithCondition JUMP target must be a jump opcode name or number: $target",
            )
    }

    private fun isHandlerParameterCompatible(
        expected: Type,
        actual: Type,
    ): Boolean {
        if (expected == actual) {
            return true
        }
        if (!expected.isReferenceType() || !actual.isReferenceType()) {
            return false
        }
        if (actual.sort == Type.OBJECT &&
            (actual.internalName == "java/lang/Object" || actual.internalName == "kotlin/Any")
        ) {
            return true
        }
        return runCatching {
            val expectedClass = loadReferenceClass(expected)
            loadReferenceClass(actual).isAssignableFrom(expectedClass)
        }.getOrDefault(false)
    }

    private fun Type.isReferenceType(): Boolean = sort == Type.OBJECT || sort == Type.ARRAY

    private fun loadReferenceClass(type: Type): Class<*> {
        val className =
            if (type.sort == Type.ARRAY) {
                type.descriptor.replace('/', '.')
            } else {
                type.className
            }
        val classLoader = asmInfo.asmClass.classLoader ?: ClassLoader.getSystemClassLoader()
        return Class.forName(className, false, classLoader)
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
            loadFromVariable(il, paramType, paramVarIndex)
            paramVarIndex += paramType.size
        }
    }

    private fun loadFromVariable(
        il: InsnList,
        paramType: Type,
        varIndex: Int,
    ) {
        InstructionUtil.loadParam(paramType, varIndex).let { il.add(it) }
    }

    private fun storeStackValue(
        il: InsnList,
        paramType: Type,
        varIndex: Int,
    ) {
        when (paramType.sort) {
            Type.BOOLEAN, Type.BYTE, Type.SHORT, Type.INT, Type.CHAR -> il.add(VarInsnNode(Opcodes.ISTORE, varIndex))
            Type.LONG -> il.add(VarInsnNode(Opcodes.LSTORE, varIndex))
            Type.FLOAT -> il.add(VarInsnNode(Opcodes.FSTORE, varIndex))
            Type.DOUBLE -> il.add(VarInsnNode(Opcodes.DSTORE, varIndex))
            else -> il.add(VarInsnNode(Opcodes.ASTORE, varIndex))
        }
    }

    private fun addHandlerOwner(il: InsnList) {
        if (isHandlerStatic()) {
            return
        }

        val ownerType = Type.getType(asmInfo.asmClass)
        if (isKotlinObject()) {
            il.add(
                FieldInsnNode(
                    Opcodes.GETSTATIC,
                    ownerType.internalName,
                    "INSTANCE",
                    "L${ownerType.internalName};",
                ),
            )
            return
        }

        il.add(TypeInsnNode(Opcodes.NEW, ownerType.internalName))
        il.add(InsnNode(Opcodes.DUP))
        il.add(MethodInsnNode(Opcodes.INVOKESPECIAL, ownerType.internalName, "<init>", "()V", false))
    }

    private fun handlerOpcode(): Int =
        if (isHandlerStatic()) {
            Opcodes.INVOKESTATIC
        } else {
            Opcodes.INVOKEVIRTUAL
        }

    private fun isHandlerStatic(): Boolean = (asmMethod.modifiers and Modifier.STATIC) != 0

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
            "Only INVOKE slice boundaries are supported for @WrapWithCondition: ${at.value}"
        }

        val (boundaryOwner, boundaryName, boundaryDesc) = parseTargetMethod(at.target)
        if (boundaryName == null || boundaryDesc == null) {
            throw IllegalArgumentException(
                "Invalid WrapWithCondition slice boundary method signature: ${at.target} " +
                    "(parsed: owner=$boundaryOwner, name=$boundaryName, desc=$boundaryDesc)",
            )
        }

        for (index in startIndex until insns.size) {
            val insn = insns[index]
            if (
                insn is MethodInsnNode &&
                matchesTargetMethod(insn, boundaryOwner, boundaryName, boundaryDesc)
            ) {
                return index
            }
            if (
                insn is InvokeDynamicInsnNode &&
                matchesTargetInvokeDynamic(insn, boundaryOwner, boundaryName, boundaryDesc)
            ) {
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

    private fun matchesTargetInvokeDynamic(
        insn: InvokeDynamicInsnNode,
        targetOwner: String?,
        targetName: String,
        targetDesc: String?,
    ): Boolean {
        if (targetOwner != null && insn.bsm.owner != targetOwner) {
            return false
        }
        if (insn.name != targetName && insn.bsm.name != targetName) {
            return false
        }
        return targetDesc == null || insn.desc == targetDesc
    }

    private fun parseFieldTarget(signature: String): FieldTarget {
        if (signature.isEmpty()) {
            return FieldTarget(null, null, null)
        }

        val colonIndex = signature.indexOf(':')
        val ownerAndName = if (colonIndex >= 0) signature.substring(0, colonIndex) else signature
        val desc = if (colonIndex >= 0) signature.substring(colonIndex + 1) else null
        val slashIndex = ownerAndName.lastIndexOf('/')
        val dotIndex = ownerAndName.lastIndexOf('.')
        val separatorIndex = maxOf(slashIndex, dotIndex)

        return if (separatorIndex >= 0) {
            FieldTarget(
                owner = ownerAndName.substring(0, separatorIndex).replace('.', '/'),
                name = ownerAndName.substring(separatorIndex + 1),
                desc = desc,
            )
        } else {
            FieldTarget(owner = null, name = ownerAndName, desc = desc)
        }
    }

    private fun matchesTargetField(
        insn: FieldInsnNode,
        target: FieldTarget,
    ): Boolean {
        if (target.owner != null && insn.owner != target.owner) {
            return false
        }
        if (target.name != null && insn.name != target.name) {
            return false
        }
        return target.desc == null || insn.desc == target.desc
    }

    private fun nextLocalIndex(target: MethodNode): Int {
        var maxIndex = if ((target.access and Opcodes.ACC_STATIC) != 0) 0 else 1
        for (paramType in Type.getArgumentTypes(target.desc)) {
            maxIndex += paramType.size
        }
        for (localVar in target.localVariables) {
            maxIndex = maxOf(maxIndex, localVar.index + Type.getType(localVar.desc).size)
        }
        for (insn in target.instructions.toArray()) {
            if (insn is VarInsnNode) {
                val size =
                    when (insn.opcode) {
                        Opcodes.LLOAD, Opcodes.LSTORE, Opcodes.DLOAD, Opcodes.DSTORE -> 2
                        else -> 1
                    }
                maxIndex = maxOf(maxIndex, insn.`var` + size)
            }
        }
        return maxIndex
    }

    private data class FieldTarget(
        val owner: String?,
        val name: String?,
        val desc: String?,
    )

    private companion object {
        private val FIELD_READ_OPS = setOf(Opcodes.GETFIELD, Opcodes.GETSTATIC)
        private val FIELD_WRITE_OPS = setOf(Opcodes.PUTFIELD, Opcodes.PUTSTATIC)
        private val ARRAY_WRITE_OPS = setOf(
            Opcodes.IASTORE,
            Opcodes.LASTORE,
            Opcodes.FASTORE,
            Opcodes.DASTORE,
            Opcodes.AASTORE,
            Opcodes.BASTORE,
            Opcodes.CASTORE,
            Opcodes.SASTORE,
        )
        private val JUMP_OPS = setOf(
            Opcodes.IFEQ,
            Opcodes.IFNE,
            Opcodes.IFLT,
            Opcodes.IFGE,
            Opcodes.IFGT,
            Opcodes.IFLE,
            Opcodes.IF_ICMPEQ,
            Opcodes.IF_ICMPNE,
            Opcodes.IF_ICMPLT,
            Opcodes.IF_ICMPGE,
            Opcodes.IF_ICMPGT,
            Opcodes.IF_ICMPLE,
            Opcodes.IF_ACMPEQ,
            Opcodes.IF_ACMPNE,
            Opcodes.GOTO,
            Opcodes.JSR,
            Opcodes.IFNULL,
            Opcodes.IFNONNULL,
        )
        private val CONDITIONAL_JUMP_OPS = JUMP_OPS - setOf(Opcodes.GOTO, Opcodes.JSR)
        private val JUMP_OPCODE_NAMES =
            mapOf(
                "IFEQ" to Opcodes.IFEQ,
                "IFNE" to Opcodes.IFNE,
                "IFLT" to Opcodes.IFLT,
                "IFGE" to Opcodes.IFGE,
                "IFGT" to Opcodes.IFGT,
                "IFLE" to Opcodes.IFLE,
                "IF_ICMPEQ" to Opcodes.IF_ICMPEQ,
                "IF_ICMPNE" to Opcodes.IF_ICMPNE,
                "IF_ICMPLT" to Opcodes.IF_ICMPLT,
                "IF_ICMPGE" to Opcodes.IF_ICMPGE,
                "IF_ICMPGT" to Opcodes.IF_ICMPGT,
                "IF_ICMPLE" to Opcodes.IF_ICMPLE,
                "IF_ACMPEQ" to Opcodes.IF_ACMPEQ,
                "IF_ACMPNE" to Opcodes.IF_ACMPNE,
                "GOTO" to Opcodes.GOTO,
                "JSR" to Opcodes.JSR,
                "IFNULL" to Opcodes.IFNULL,
                "IFNONNULL" to Opcodes.IFNONNULL,
            )
    }
}
