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
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LocalVariableNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * WrapWithCondition 注入器。
 *
 * 该注入器会匹配目标方法内的普通方法调用、任意返回值的 `invokedynamic` 调用、字段写入、简单数组元素写入、局部变量写入、条件跳转或抛异常点，
 * 并在原指令前插入 boolean handler。
 * handler 返回 `true` 时恢复原调用的 receiver 与参数、字段写入值、数组写入栈参数、局部变量待写入值、原条件跳转分支结果或原异常对象并继续执行原指令。
 * handler 返回 `false` 时跳过原指令、原条件跳转或原抛出；非 `void` 普通方法调用与非 `void` `invokedynamic`
 * 调用会压入返回类型对应的默认值。
 * [InjectionPoint.INVOKE] 未指定调用目标时，会按 handler 参数和 boolean 返回类型筛选兼容的普通调用或
 * `invokedynamic` 调用；构造器和 handler 不兼容的调用不会计入 [WrapWithCondition.ordinal] 或命中数。
 * [InjectionPoint.FIELD_ASSIGN] 未指定字段目标时，会按 handler 字段 owner 参数、待写入值和 boolean 返回类型筛选
 * 兼容的字段写入，且不兼容候选不会计入 [WrapWithCondition.ordinal] 或命中数。
 * [InjectionPoint.STORE] 不使用 [At.target]，可通过 [At.args] 中的 `index=N`、`var=N` 或 `name=localName`
 * 过滤局部变量写入，handler 首参接收本次 `xSTORE` 即将消费的待写入值。
 * [InjectionPoint.JUMP] 未指定跳转目标时会匹配切片内全部条件跳转，`GOTO` 与 `JSR` 不支持条件包裹。
 * [InjectionPoint.THROW] 未指定异常类型目标时会匹配切片内全部 `ATHROW`；指定目标时只匹配前一条真实指令为同类型 `<init>` 的直接构造异常。
 * 构造器 `<init>` 虽然返回 `void`，但会消费未初始化对象，当前明确拒绝条件包裹。
 *
 * @param at 调用点定位；当前支持 [InjectionPoint.INVOKE]、[InjectionPoint.FIELD_ASSIGN]、[InjectionPoint.STORE]、[InjectionPoint.JUMP] 与 [InjectionPoint.THROW]
 * @param ordinal 匹配调用点序号；负数表示处理全部匹配调用点
 * @param slice 切片范围；当前 [InjectionPoint.INVOKE]、[InjectionPoint.FIELD_ASSIGN]、[InjectionPoint.STORE]、[InjectionPoint.JUMP] 与 [InjectionPoint.THROW] 条件包裹使用
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
     * 在匹配的方法调用、`invokedynamic` 调用、字段写入、数组元素写入、局部变量写入、条件跳转或抛异常点前插入条件包裹逻辑。
     *
     * @param target 目标方法
     * @return 至少包裹一个调用点、动态调用点、字段写入点、数组元素写入点、局部变量写入点、条件跳转点或抛异常点时返回 `true`
     * @throws IllegalArgumentException 定位点、目标调用、字段目标或 handler 签名不合法时抛出
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun inject(target: MethodNode): Boolean = injectCount(target) > 0

    /**
     * 在匹配的方法调用、`invokedynamic` 调用、字段写入、数组元素写入、局部变量写入、条件跳转或抛异常点前插入条件包裹逻辑，并返回实际包裹数量。
     *
     * @param target 目标方法
     * @return 实际包裹的调用点、动态调用点、字段写入点、数组元素写入点、局部变量写入点、条件跳转点或抛异常点数量
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
            InjectionPoint.STORE -> injectStore(target)
            InjectionPoint.JUMP -> injectJump(target)
            InjectionPoint.THROW -> injectThrow(target)
            else -> throw IllegalArgumentException(
                "@WrapWithCondition supports only INVOKE, FIELD_ASSIGN, STORE, JUMP and THROW injection points",
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
     * @throws IllegalArgumentException 目标签名不完整、构造器或 handler 签名不兼容时抛出
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
                    val currentOrdinal = matchedOrdinal++
                    if (!matchesOrdinal(currentOrdinal)) {
                        continue
                    }

                    val targetParamCount = validateHandlerSignature(target, insn)
                    val skipOriginalLabel = LabelNode()
                    val afterOriginalLabel = LabelNode()
                    val il = buildConditionWrapper(target, insn, targetParamCount, skipOriginalLabel)
                    target.instructions.insertBefore(insn, il)
                    target.instructions.insert(insn, buildSkippedCallDefaultReturn(insn, skipOriginalLabel, afterOriginalLabel))
                    injectionCount++
                }
                insn is InvokeDynamicInsnNode &&
                    (inferTarget || (targetName != null && matchesTargetInvokeDynamic(insn, targetOwner, targetName, targetDesc))) -> {
                    if (inferTarget && !isInvokeDynamicConditionCompatible(target, insn)) {
                        continue
                    }

                    val currentOrdinal = matchedOrdinal++
                    if (!matchesOrdinal(currentOrdinal)) {
                        continue
                    }

                    val targetParamCount = validateInvokeDynamicHandlerSignature(target, insn)
                    val skipOriginalLabel = LabelNode()
                    val afterOriginalLabel = LabelNode()
                    val il = buildInvokeDynamicConditionWrapper(target, insn, targetParamCount, skipOriginalLabel)
                    target.instructions.insertBefore(insn, il)
                    target.instructions.insert(insn, buildSkippedInvokeDynamicDefaultReturn(insn, skipOriginalLabel, afterOriginalLabel))
                    injectionCount++
                }
            }
        }

        return injectionCount
    }

    /**
     * 判断 handler 是否兼容候选普通方法调用。
     *
     * 该方法用于目标推断模式，构造器或签名不兼容候选不会计入 ordinal 或命中数。
     *
     * @param target 目标方法
     * @param insn 候选方法调用指令
     * @return handler 可条件包裹该调用时返回 `true`
     */
    private fun isMethodCallConditionCompatible(
        target: MethodNode,
        insn: MethodInsnNode,
    ): Boolean {
        if (insn.name == "<init>") {
            return false
        }
        return runCatching { validateHandlerSignature(target, insn) }.isSuccess
    }

    /**
     * 判断 handler 是否兼容候选 `invokedynamic` 调用。
     *
     * 该方法用于目标推断模式，签名不兼容候选不会计入 ordinal 或命中数。
     *
     * @param target 目标方法
     * @param insn 候选 `invokedynamic` 指令
     * @return handler 可条件包裹该动态调用时返回 `true`
     */
    private fun isInvokeDynamicConditionCompatible(
        target: MethodNode,
        insn: InvokeDynamicInsnNode,
    ): Boolean {
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
     * 在匹配的局部变量写入前插入条件 handler。
     *
     * [InjectionPoint.STORE] 不使用 [At.target]，可通过 [At.args] 中的 `index=N`、`var=N` 或 `name=localName`
     * 限定候选 `xSTORE`。handler 返回 `false` 时丢弃待写入值并跳过原写入，返回 `true` 时恢复该值供原写入消费。
     *
     * @param target 目标方法
     * @return 实际插入条件包裹逻辑的局部变量写入数量
     * @throws IllegalArgumentException 声明了 `at.target`、槽位过滤参数非法或 handler 签名不兼容时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2026-05-31
     */
    private fun injectStore(target: MethodNode): Int {
        require(at.target.isEmpty()) {
            "@WrapWithCondition STORE uses At.args index=N, var=N or name=localName for local variable filtering, not At.target"
        }
        val localVariableFilter = parseLocalVariableFilter("STORE")
        val handlerStoreType = requireHandlerLocalArgumentType("STORE")
        var injectionCount = 0
        var matchedOrdinal = 0
        val insns = target.instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)
        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (insn !is VarInsnNode || insn.opcode !in STORE_OPS) {
                continue
            }
            if (!matchesLocalVariableFilter(target, insn, localVariableFilter)) {
                continue
            }
            if (!isStoreCompatibleWithHandler(insn.opcode, handlerStoreType)) {
                continue
            }

            val resolvedStoreType = resolveIndexedLocalValueType(target, insn.`var`, handlerStoreType)
            if (localVariableFilter.index == null && handlerStoreType.isReferenceType() && resolvedStoreType == null) {
                continue
            }
            val storeType = resolvedStoreType ?: handlerStoreType
            if (localVariableFilter.index == null && !isStoreHandlerCompatible(target, storeType)) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val targetParamCount = validateStoreHandlerSignature(target, storeType)
            val skipOriginalLabel = LabelNode()
            val il = buildStoreConditionWrapper(target, storeType, targetParamCount, skipOriginalLabel)
            target.instructions.insertBefore(insn, il)
            target.instructions.insert(insn, skipOriginalLabel)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 判断 handler 是否兼容候选局部变量写入。
     *
     * 该方法用于目标推断模式，签名不兼容候选不会计入 ordinal 或命中数。
     *
     * @param target 目标方法
     * @param storeType 候选写入值类型
     * @return handler 可条件包裹该局部变量写入时返回 `true`
     *
     * @author Dr (dr@der.kim)
     * @date 2026-05-31
     */
    private fun isStoreHandlerCompatible(
        target: MethodNode,
        storeType: Type,
    ): Boolean = runCatching { validateStoreHandlerSignature(target, storeType) }.isSuccess

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
     * 为被跳过的非 `void` 普通方法调用补齐默认返回值。
     *
     * 原调用返回 `void` 时只放置跳过标签；非 `void` 调用会先让原调用完成后跳过默认值分支，
     * 再在 handler 返回 `false` 的路径压入 JVM 默认值或 [DefaultReturnValueProvider] 生成的引用默认值。
     *
     * @param callInsn 被条件包裹的方法调用指令
     * @param skipOriginalLabel handler 返回 `false` 时跳转到的标签
     * @param afterOriginalLabel 原调用完成后跳转到的汇合标签
     * @return 应插入到原调用后的指令列表
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-01
     */
    private fun buildSkippedCallDefaultReturn(
        callInsn: MethodInsnNode,
        skipOriginalLabel: LabelNode,
        afterOriginalLabel: LabelNode,
    ): InsnList {
        val il = InsnList()
        val returnType = Type.getReturnType(callInsn.desc)
        if (returnType == Type.VOID_TYPE) {
            il.add(skipOriginalLabel)
            return il
        }

        il.add(JumpInsnNode(Opcodes.GOTO, afterOriginalLabel))
        il.add(skipOriginalLabel)
        loadDefaultReturnValue(returnType, il)
        il.add(afterOriginalLabel)
        return il
    }

    /**
     * 为条件跳过的非 `void` 调用加载默认返回值。
     *
     * 基础类型和常见文本类型直接使用 JVM 默认值；其他引用类型委托 [DefaultReturnValueProvider]。
     *
     * @param type 被跳过调用的返回类型
     * @param il 待追加指令列表
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-01
     */
    private fun loadDefaultReturnValue(
        type: Type,
        il: InsnList,
    ) {
        when (type.sort) {
            Type.BOOLEAN,
            Type.BYTE,
            Type.SHORT,
            Type.INT,
            Type.CHAR -> il.add(InsnNode(Opcodes.ICONST_0))
            Type.FLOAT -> il.add(InsnNode(Opcodes.FCONST_0))
            Type.LONG -> il.add(InsnNode(Opcodes.LCONST_0))
            Type.DOUBLE -> il.add(InsnNode(Opcodes.DCONST_0))
            Type.OBJECT ->
                if (type.internalName == "java/lang/String" || type.internalName == "java/lang/CharSequence") {
                    il.add(LdcInsnNode(""))
                } else {
                    injectDefaultReturnValue(type, il)
                }
            Type.ARRAY -> injectDefaultReturnValue(type, il)
            else -> throw IllegalStateException("Unsupported default return type for @WrapWithCondition: $type")
        }
    }

    /**
     * 通过 [DefaultReturnValueProvider] 生成引用类型默认值。
     *
     * @param type 被跳过调用的返回类型
     * @param il 待追加指令列表
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-01
     */
    private fun injectDefaultReturnValue(
        type: Type,
        il: InsnList,
    ) {
        il.add(InstructionUtil.loadType(type))
        il.add(
            MethodInsnNode(
                Opcodes.INVOKESTATIC,
                Type.getInternalName(DefaultReturnValueProvider::class.java),
                "defaultValue",
                "(Ljava/lang/Class;)Ljava/lang/Object;",
                false,
            ),
        )
        il.add(TypeInsnNode(Opcodes.CHECKCAST, type.internalName))
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
     * 为被跳过的 `invokedynamic` 调用补齐默认返回值。
     *
     * @param callInsn 被条件包裹的 `invokedynamic` 指令
     * @param skipOriginalLabel handler 返回 `false` 时跳转到的标签
     * @param afterOriginalLabel 原调用完成后跳转到的汇合标签
     * @return 应插入到原动态调用后的指令列表
     */
    private fun buildSkippedInvokeDynamicDefaultReturn(
        callInsn: InvokeDynamicInsnNode,
        skipOriginalLabel: LabelNode,
        afterOriginalLabel: LabelNode,
    ): InsnList {
        val il = InsnList()
        val returnType = Type.getReturnType(callInsn.desc)
        if (returnType == Type.VOID_TYPE) {
            il.add(skipOriginalLabel)
            return il
        }

        il.add(JumpInsnNode(Opcodes.GOTO, afterOriginalLabel))
        il.add(skipOriginalLabel)
        loadDefaultReturnValue(returnType, il)
        il.add(afterOriginalLabel)
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
     * 构造局部变量写入条件包裹的前置指令序列。
     *
     * 序列会暂存 `xSTORE` 即将消费的栈顶值，调用 boolean handler；
     * handler 返回 `false` 时跳过原写入，返回 `true` 时恢复待写入值供原 `xSTORE` 消费。
     *
     * @param target 目标方法
     * @param storeType 局部变量待写入值类型
     * @param targetParamCount handler 追加接收的目标方法参数数量
     * @param skipOriginalLabel handler 返回 `false` 时跳转到的标签
     * @return 插入到原局部变量写入前的条件包裹指令列表
     *
     * @author Dr (dr@der.kim)
     * @date 2026-05-31
     */
    private fun buildStoreConditionWrapper(
        target: MethodNode,
        storeType: Type,
        targetParamCount: Int,
        skipOriginalLabel: LabelNode,
    ): InsnList {
        val il = InsnList()
        val valueIndex = nextLocalIndex(target)

        storeStackValue(il, storeType, valueIndex)
        addHandlerOwner(il)
        loadFromVariable(il, storeType, valueIndex)
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
        loadFromVariable(il, storeType, valueIndex)

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
     * 校验局部变量写入条件包裹的 handler 签名。
     *
     * handler 必须返回 `boolean`，首参接收本次 `xSTORE` 即将消费的待写入值；
     * 其余参数会被解释为目标方法开头的参数前缀。
     *
     * @param target 目标方法
     * @param storeType 局部变量待写入值类型
     * @return handler 追加接收的目标方法参数数量
     * @throws IllegalArgumentException handler 返回值、待写入值参数或追加目标参数不兼容时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2026-05-31
     */
    private fun validateStoreHandlerSignature(
        target: MethodNode,
        storeType: Type,
    ): Int {
        val returnType = Type.getReturnType(asmMethod)
        if (returnType.sort != Type.BOOLEAN) {
            throw IllegalArgumentException(
                "@WrapWithCondition handler ${asmMethod.name} must return boolean, actual $returnType",
            )
        }

        val actualParams = Type.getArgumentTypes(asmMethod)
        if (actualParams.isEmpty()) {
            throw IllegalArgumentException(
                "@WrapWithCondition STORE handler ${asmMethod.name} must receive original local value",
            )
        }
        if (!isHandlerParameterCompatible(storeType, actualParams[0])) {
            throw IllegalArgumentException(
                "@WrapWithCondition handler ${asmMethod.name} parameter #0 mismatch: " +
                    "expected $storeType, actual ${actualParams[0]}",
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

    /**
     * 构造普通方法调用 handler 必须接收的前缀参数类型。
     *
     * 实例调用会把调用 owner 作为首参，静态调用只保留原调用参数。
     *
     * @param callInsn 被条件包裹的普通方法调用指令
     * @return handler 前缀参数类型数组
     */
    private fun buildExpectedHandlerParams(callInsn: MethodInsnNode): Array<Type> {
        val callParams = Type.getArgumentTypes(callInsn.desc).toList()
        return if (callInsn.opcode == Opcodes.INVOKESTATIC) {
            callParams.toTypedArray()
        } else {
            (listOf(Type.getObjectType(callInsn.owner)) + callParams).toTypedArray()
        }
    }

    /**
     * 构造字段写入 handler 必须接收的前缀参数类型。
     *
     * 静态字段写入只需要待写入值，实例字段写入需要字段 owner 与待写入值。
     *
     * @param fieldInsn 被条件包裹的字段写入指令
     * @return handler 前缀参数类型数组
     */
    private fun buildExpectedFieldAssignHandlerParams(fieldInsn: FieldInsnNode): Array<Type> {
        val fieldType = Type.getType(fieldInsn.desc)
        return if (fieldInsn.opcode == Opcodes.PUTSTATIC) {
            arrayOf(fieldType)
        } else {
            arrayOf(Type.getObjectType(fieldInsn.owner), fieldType)
        }
    }

    /**
     * 构造数组元素写入 handler 必须接收的前缀参数类型。
     *
     * 当前数组写入模式基于字段读取产生数组引用，因此参数固定为数组引用、索引与元素值。
     *
     * @param fieldInsn 产生数组引用的字段读取指令
     * @return handler 前缀参数类型数组
     */
    private fun buildExpectedArrayAssignHandlerParams(fieldInsn: FieldInsnNode): Array<Type> {
        val arrayType = Type.getType(fieldInsn.desc)
        return arrayOf(arrayType, Type.INT_TYPE, arrayType.elementType)
    }

    /**
     * 从数组写入指令向前查找产生数组引用的字段读取指令。
     *
     * 只接受直接邻近且匹配目标字段的字段读取；遇到其他字段指令、方法调用或数组写入时停止，
     * 避免跨过会改变栈结构的复杂表达式。
     *
     * @param arrayInsn 数组元素写入指令
     * @param target 字段目标约束
     * @return 匹配的数组字段读取指令；无法确认简单字段数组写入时返回 `null`
     * @throws IllegalArgumentException 匹配字段不是数组类型时抛出
     */
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

    /**
     * 推断 `ATHROW` 直接抛出的异常内部名。
     *
     * 当前只识别紧邻真实前序指令为异常构造器 `<init>` 调用的简单 `new ...; <init>; athrow` 形态。
     *
     * @param throwInsn `ATHROW` 指令
     * @return 直接构造异常的内部名；无法确认时返回 `null`
     */
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

    /**
     * 查找指定指令前一条真实 JVM 指令。
     *
     * 标签、行号与 frame 等伪指令会被跳过。
     *
     * @param insn 起始指令
     * @return 前一条 opcode 非负的真实指令；不存在时返回 `null`
     */
    private fun previousRealInstruction(insn: AbstractInsnNode): AbstractInsnNode? {
        var current = insn.previous
        while (current != null && current.opcode < 0) {
            current = current.previous
        }
        return current
    }

    /**
     * 查找指定指令后一条真实 JVM 指令。
     *
     * 标签、行号与 frame 等伪指令会被跳过。
     *
     * @param insn 起始指令
     * @return 后一条 opcode 非负的真实指令；不存在时返回 `null`
     *
     * @author Dr (dr@der.kim)
     * @date 2026-05-31
     */
    private fun nextRealInstruction(insn: AbstractInsnNode): AbstractInsnNode? {
        var current = insn.next
        while (current != null && current.opcode < 0) {
            current = current.next
        }
        return current
    }

    /**
     * 解析条件跳转定位目标。
     *
     * 空字符串表示不限制跳转 opcode；非空目标可使用 opcode 数值或 [JUMP_OPCODE_NAMES] 中的助记名。
     *
     * @param target `At.target` 中声明的跳转目标
     * @return 需要匹配的跳转 opcode；未声明时返回 `null`
     * @throws IllegalArgumentException 声明的目标不是受支持的 JVM 条件跳转 opcode 时抛出
     */
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

    /**
     * 解析局部变量写入过滤条件。
     *
     * `index=` 与 `var=` 等价，用于按 JVM 局部变量槽位过滤；`name=` 用于按 LocalVariableTable 变量名过滤。
     *
     * @param pointName 当前定位点名称，用于错误提示
     * @return 局部变量过滤条件；未指定时返回空过滤
     * @throws IllegalArgumentException 声明多个同类过滤条件、非整数、负数槽位或空变量名时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2026-05-31
     */
    private fun parseLocalVariableFilter(pointName: String): LocalVariableFilter {
        val slotValues =
            at.args.mapNotNull { arg ->
                val trimmed = arg.trim()
                when {
                    trimmed.startsWith("index=") -> trimmed.substringAfter("index=")
                    trimmed.startsWith("var=") -> trimmed.substringAfter("var=")
                    else -> null
                }
            }
        val nameValues =
            at.args.mapNotNull { arg ->
                val trimmed = arg.trim()
                if (trimmed.startsWith("name=")) {
                    trimmed.substringAfter("name=")
                } else {
                    null
                }
            }

        require(slotValues.size <= 1) {
            "@WrapWithCondition $pointName supports only one local variable slot filter in At.args"
        }
        require(nameValues.size <= 1) {
            "@WrapWithCondition $pointName supports only one local variable name filter in At.args"
        }

        val index =
            slotValues.singleOrNull()?.let { value ->
                val parsed =
                    value.toIntOrNull()
                        ?: throw IllegalArgumentException(
                            "@WrapWithCondition $pointName local variable slot filter must be an integer: $value",
                        )
                require(parsed >= 0) {
                    "@WrapWithCondition $pointName local variable slot filter must be non-negative: $parsed"
                }
                parsed
            }
        val name =
            nameValues.singleOrNull()?.trim()?.also { value ->
                require(value.isNotEmpty()) {
                    "@WrapWithCondition $pointName local variable name filter must not be blank"
                }
            }

        return LocalVariableFilter(index, name)
    }

    /**
     * 判断局部变量写入指令是否满足槽位和名称过滤条件。
     *
     * @param target 目标方法
     * @param insn 候选局部变量写入指令
     * @param filter 注解声明的局部变量过滤条件
     * @return 候选指令满足过滤条件时返回 `true`
     *
     * @author Dr (dr@der.kim)
     * @date 2026-05-31
     */
    private fun matchesLocalVariableFilter(
        target: MethodNode,
        insn: VarInsnNode,
        filter: LocalVariableFilter,
    ): Boolean {
        if (filter.isEmpty()) {
            return true
        }
        if (filter.index != null && insn.`var` != filter.index) {
            return false
        }
        if (filter.name == null) {
            return true
        }
        return localVariableAt(target, nextRealInstruction(insn) ?: insn, insn.`var`)?.name == filter.name
    }

    /**
     * 查找锚点处覆盖指定槽位的局部变量表记录。
     *
     * @param target 目标方法
     * @param anchor 待匹配的锚点指令
     * @param index JVM 局部变量槽位
     * @return 覆盖锚点的局部变量记录；不存在时返回 `null`
     *
     * @author Dr (dr@der.kim)
     * @date 2026-05-31
     */
    private fun localVariableAt(
        target: MethodNode,
        anchor: AbstractInsnNode,
        index: Int,
    ): LocalVariableNode? {
        val insns = target.instructions.toArray()
        val anchorIndex = insns.indexOf(anchor)
        if (anchorIndex < 0) {
            return null
        }

        return target.localVariables.firstOrNull { local ->
            local.index == index && local.containsInstruction(insns, anchorIndex)
        }
    }

    /**
     * 判断局部变量表记录是否覆盖给定指令下标。
     *
     * @param insns 目标方法指令数组
     * @param instructionIndex 待判断的指令下标
     * @return 指令下标处于该局部变量生命周期内时返回 `true`
     *
     * @author Dr (dr@der.kim)
     * @date 2026-05-31
     */
    private fun LocalVariableNode.containsInstruction(
        insns: Array<AbstractInsnNode>,
        instructionIndex: Int,
    ): Boolean {
        val startIndex = insns.indexOf(start)
        val endIndex = insns.indexOf(end)
        return startIndex >= 0 &&
            endIndex >= 0 &&
            instructionIndex >= startIndex &&
            instructionIndex < endIndex
    }

    /**
     * 读取 handler 首个参数作为局部变量待写入值类型。
     *
     * @param pointName 当前定位点名称，用于错误提示
     * @return handler 首参的 ASM 类型
     * @throws IllegalArgumentException handler 没有参数时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2026-05-31
     */
    private fun requireHandlerLocalArgumentType(pointName: String): Type {
        val handlerParams = Type.getArgumentTypes(asmMethod)
        if (handlerParams.isEmpty()) {
            throw IllegalArgumentException(
                "@WrapWithCondition handler ${asmMethod.name} must take at least one argument for the local variable $pointName value",
            )
        }
        return handlerParams[0]
    }

    /**
     * 解析指定局部变量槽位中引用值的更具体表达式类型。
     *
     * 基础类型不需要额外推断，引用类型会依次尝试目标方法参数、LocalVariableTable 与相邻指令上下文。
     * 只有推断类型能被 handler 首参接收时才会返回，避免把不相关槽位误计为候选写入。
     *
     * @param target 目标方法
     * @param index JVM 局部变量槽位
     * @param fallbackType handler 首参类型
     * @return 推断出的引用表达式类型；无法可靠推断时返回 `null`
     *
     * @author Dr (dr@der.kim)
     * @date 2026-05-31
     */
    private fun resolveIndexedLocalValueType(
        target: MethodNode,
        index: Int,
        fallbackType: Type,
    ): Type? {
        if (!fallbackType.isReferenceType()) {
            return null
        }

        val headVariable = collectHeadParameters(target).firstOrNull { it.index == index }
        if (headVariable != null) {
            return headVariable.type
        }

        val localVariable =
            target.localVariables
                .filter { it.index == index }
                .mapNotNull { runCatching { Type.getType(it.desc) }.getOrNull() }
                .firstOrNull { it.isReferenceType() && isHandlerParameterCompatible(it, fallbackType) }
        if (localVariable != null) {
            return localVariable
        }

        return referencedTypeFromSlotInstructions(target, index, fallbackType)
    }

    /**
     * 收集目标方法参数在方法入口处占用的局部变量槽位。
     *
     * 实例方法会跳过 `this` 槽位，宽类型参数按两个槽位推进。
     *
     * @param target 目标方法
     * @return 参数起始槽位与参数类型列表
     *
     * @author Dr (dr@der.kim)
     * @date 2026-05-31
     */
    private fun collectHeadParameters(target: MethodNode): List<LocalSlotType> {
        val isStatic = (target.access and Opcodes.ACC_STATIC) != 0
        var slot = if (isStatic) 0 else 1
        return buildList {
            for (argumentType in Type.getArgumentTypes(target.desc)) {
                add(LocalSlotType(slot, argumentType))
                slot += argumentType.size
            }
        }
    }

    /**
     * 通过同一槽位附近的引用读写指令推断表达式类型。
     *
     * 该推断作为 LocalVariableTable 缺失或不完整时的兜底，只考察 `ALOAD` 与 `ASTORE` 相关上下文。
     *
     * @param target 目标方法
     * @param index JVM 局部变量槽位
     * @param fallbackType handler 首参类型
     * @return 能与 handler 首参兼容的引用类型；无法推断时返回 `null`
     *
     * @author Dr (dr@der.kim)
     * @date 2026-05-31
     */
    private fun referencedTypeFromSlotInstructions(
        target: MethodNode,
        index: Int,
        fallbackType: Type,
    ): Type? =
        target.instructions.toArray()
            .asSequence()
            .filterIsInstance<VarInsnNode>()
            .filter { it.`var` == index && it.opcode in SLOT_REFERENCE_OPS }
            .mapNotNull { inferReferenceTypeAroundSlotInstruction(target, it) }
            .firstOrNull { isHandlerParameterCompatible(it, fallbackType) }

    /**
     * 根据单条引用槽位读写指令的相邻上下文推断引用类型。
     *
     * `ASTORE` 优先使用前一条真实指令中的 `CHECKCAST` 或字符串常量特征；
     * `ALOAD` 则观察后续方法调用、字段访问、类型转换或返回指令对该引用的消费方式。
     *
     * @param target 目标方法
     * @param insn 待分析的 `ALOAD` 或 `ASTORE` 指令
     * @return 推断出的引用类型；上下文不足时返回 `null`
     *
     * @author Dr (dr@der.kim)
     * @date 2026-05-31
     */
    private fun inferReferenceTypeAroundSlotInstruction(
        target: MethodNode,
        insn: VarInsnNode,
    ): Type? {
        if (insn.opcode == Opcodes.ASTORE) {
            val previous = previousRealInstruction(insn)
            if (previous is TypeInsnNode && previous.opcode == Opcodes.CHECKCAST) {
                return Type.getObjectType(previous.desc)
            }
            if (previous is LdcInsnNode && previous.cst is String) {
                return Type.getType(String::class.java)
            }
            inferReferenceTypeFromNextLoadConsumer(target, insn)?.let { return it }
            return null
        }

        val next = nextRealInstruction(insn)
        return when (next) {
            is MethodInsnNode -> {
                val ownerType = Type.getObjectType(next.owner)
                if (next.opcode == Opcodes.INVOKEVIRTUAL || next.opcode == Opcodes.INVOKEINTERFACE) {
                    ownerType
                } else {
                    null
                }
            }
            is FieldInsnNode -> {
                val ownerType = Type.getObjectType(next.owner)
                if (next.opcode == Opcodes.GETFIELD || next.opcode == Opcodes.PUTFIELD) {
                    ownerType
                } else {
                    null
                }
            }
            is TypeInsnNode ->
                if (next.opcode == Opcodes.CHECKCAST) {
                    Type.getObjectType(next.desc)
                } else {
                    null
                }
            else ->
                if (next?.opcode == Opcodes.ARETURN) {
                    val returnType = Type.getReturnType(target.desc)
                    if (returnType.isReferenceType()) returnType else null
                } else {
                    null
                }
        }
    }

    /**
     * 从 `ASTORE` 后续第一次读取该槽位的消费场景推断引用类型。
     *
     * 如果在读取前遇到同槽位再次写入，则当前写入值的类型无法继续追踪。
     *
     * @param target 目标方法
     * @param storeInsn 当前引用写入指令
     * @return 后续读取消费场景推断出的引用类型；无法推断时返回 `null`
     *
     * @author Dr (dr@der.kim)
     * @date 2026-05-31
     */
    private fun inferReferenceTypeFromNextLoadConsumer(
        target: MethodNode,
        storeInsn: VarInsnNode,
    ): Type? {
        var current = storeInsn.next
        while (current != null) {
            if (current is VarInsnNode && current.`var` == storeInsn.`var`) {
                if (current.opcode == Opcodes.ALOAD) {
                    return inferReferenceTypeAroundSlotInstruction(target, current)
                }
                if (current.opcode in STORE_OPS) {
                    return null
                }
            }
            current = current.next
        }
        return null
    }

    /**
     * 判断局部变量写入指令消费的值类型是否可交给 handler 首参。
     *
     * JVM 的 `ISTORE` 覆盖 boolean、byte、short、int 与 char，引用写入只接受对象或数组 handler 参数。
     *
     * @param opcode 写入指令 opcode
     * @param handlerType handler 首参类型
     * @return 写入值类型与 handler 首参兼容时返回 `true`
     *
     * @author Dr (dr@der.kim)
     * @date 2026-05-31
     */
    private fun isStoreCompatibleWithHandler(
        opcode: Int,
        handlerType: Type,
    ): Boolean =
        when (opcode) {
            Opcodes.ISTORE -> handlerType.sort in INT_VARIABLE_TYPE_SORTS
            Opcodes.LSTORE -> handlerType == Type.LONG_TYPE
            Opcodes.FSTORE -> handlerType == Type.FLOAT_TYPE
            Opcodes.DSTORE -> handlerType == Type.DOUBLE_TYPE
            Opcodes.ASTORE -> handlerType.sort == Type.OBJECT || handlerType.sort == Type.ARRAY
            else -> false
        }

    /**
     * 判断 handler 参数类型是否能接收期望值。
     *
     * 基础类型必须完全一致；引用类型允许 handler 使用相同类型、父类型、`Object` 或 `kotlin.Any`。
     * 类加载失败时按不兼容处理。
     *
     * @param expected 注入点会压入 handler 的实际值类型
     * @param actual handler 声明的参数类型
     * @return `actual` 能接收 `expected` 时返回 `true`
     */
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

    /**
     * 判断 ASM 类型是否为引用类型。
     *
     * @return 类型为对象或数组时返回 `true`
     */
    private fun Type.isReferenceType(): Boolean = sort == Type.OBJECT || sort == Type.ARRAY

    /**
     * 使用 Mixin 类加载器加载 ASM 引用类型。
     *
     * 数组类型会使用描述符形式加载，对象类型使用 Java 类名加载。
     *
     * @param type ASM 引用类型
     * @return 已加载的 Java class
     * @throws ClassNotFoundException 目标引用类型无法由当前类加载器解析时抛出
     */
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

    /**
     * 把目标方法开头的参数加载到 handler 调用栈。
     *
     * 实例方法会跳过 `this` 槽位，宽类型参数按两个 JVM 槽位推进。
     *
     * @param il 正在构造的指令列表
     * @param target 目标方法
     * @param requestedTargetParamCount 需要追加传给 handler 的目标方法参数数量
     */
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

    /**
     * 按类型从局部变量槽位加载值。
     *
     * @param il 正在构造的指令列表
     * @param paramType 需要加载的值类型
     * @param varIndex JVM 局部变量槽位
     */
    private fun loadFromVariable(
        il: InsnList,
        paramType: Type,
        varIndex: Int,
    ) {
        InstructionUtil.loadParam(paramType, varIndex).let { il.add(it) }
    }

    /**
     * 按类型把栈顶值暂存到局部变量槽位。
     *
     * @param il 正在构造的指令列表
     * @param paramType 栈顶值类型
     * @param varIndex JVM 局部变量槽位
     */
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

    /**
     * 为实例 handler 准备调用 owner。
     *
     * 静态 handler 不需要 owner；Kotlin `object` 会读取 `INSTANCE`，
     * 普通类会生成无参构造的新实例作为调用目标。
     *
     * @param il 正在构造的指令列表
     */
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

    /**
     * 选择 handler 调用 opcode。
     *
     * @return 静态 handler 使用 `INVOKESTATIC`，实例 handler 使用 `INVOKEVIRTUAL`
     */
    private fun handlerOpcode(): Int =
        if (isHandlerStatic()) {
            Opcodes.INVOKESTATIC
        } else {
            Opcodes.INVOKEVIRTUAL
        }

    /**
     * 判断 handler 是否为静态方法。
     *
     * @return handler 带有 `static` 修饰符时返回 `true`
     */
    private fun isHandlerStatic(): Boolean = (asmMethod.modifiers and Modifier.STATIC) != 0

    /**
     * 判断当前命中序号是否满足注解声明的 ordinal 过滤。
     *
     * 负数 ordinal 表示不按序号过滤。
     *
     * @param currentOrdinal 当前候选点在同类注入点中的命中序号
     * @return 当前候选点应被处理时返回 `true`
     */
    private fun matchesOrdinal(currentOrdinal: Int): Boolean = ordinal < 0 || currentOrdinal == ordinal

    /**
     * 解析当前切片在指令数组中的起止范围。
     *
     * `from` 边界命中后从下一条指令开始，`to` 边界命中前结束；边界未命中时返回空范围。
     *
     * @param insns 目标方法指令数组
     * @return 左闭右开的指令范围
     */
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

    /**
     * 判断切片边界是否已声明目标。
     *
     * @param at 切片边界定位点
     * @return `target` 非空时返回 `true`
     */
    private fun hasSliceBoundary(at: At): Boolean = at.target.isNotEmpty()

    /**
     * 构造位于方法末尾的空切片范围。
     *
     * @param insns 目标方法指令数组
     * @return 左右边界都等于指令数量的空范围
     */
    private fun emptySlice(insns: Array<AbstractInsnNode>): Pair<Int, Int> = insns.size to insns.size

    /**
     * 查找切片边界方法调用在指令数组中的位置。
     *
     * 当前只支持 `INVOKE` 边界，可匹配普通方法调用或 `invokedynamic` 调用。
     *
     * @param insns 目标方法指令数组
     * @param at 切片边界定位点
     * @param startIndex 开始查找的指令下标
     * @return 边界指令下标；未命中时返回 `null`
     * @throws IllegalArgumentException 边界类型不是 [InjectionPoint.INVOKE] 或目标签名不完整时抛出
     */
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

    /**
     * 解析方法目标签名。
     *
     * 支持 `owner.name(desc)`、`owner/name(desc)`、`name(desc)` 与仅方法名形式；
     * owner 会统一转换为 JVM internal name。
     *
     * @param signature `At.target` 中声明的方法目标
     * @return owner、name 与 descriptor；未声明的部分返回 `null`
     */
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

    /**
     * 判断普通方法调用是否匹配目标方法约束。
     *
     * @param insn 候选普通方法调用指令
     * @param targetOwner 目标 owner；为 `null` 时不限制 owner
     * @param targetName 目标方法名
     * @param targetDesc 目标方法描述符；为 `null` 时不限制描述符
     * @return 候选调用满足目标约束时返回 `true`
     */
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
     * 判断 `invokedynamic` 调用是否匹配目标方法约束。
     *
     * owner 约束会匹配 bootstrap method owner，名称约束可匹配动态调用名或 bootstrap method 名。
     *
     * @param insn 候选 `invokedynamic` 调用指令
     * @param targetOwner 目标 owner；为 `null` 时不限制 bootstrap owner
     * @param targetName 目标调用名或 bootstrap method 名
     * @param targetDesc 目标动态调用描述符；为 `null` 时不限制描述符
     * @return 候选动态调用满足目标约束时返回 `true`
     */
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

    /**
     * 解析字段目标签名。
     *
     * 支持 `owner.name:desc`、`owner/name:desc`、`name:desc` 与仅字段名形式；
     * owner 会统一转换为 JVM internal name，空签名表示不限制字段。
     *
     * @param signature `At.target` 中声明的字段目标
     * @return 解析后的字段目标约束
     */
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

    /**
     * 判断字段指令是否匹配字段目标约束。
     *
     * @param insn 候选字段指令
     * @param target 字段目标约束
     * @return 候选字段满足 owner、name 与 descriptor 约束时返回 `true`
     */
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

    /**
     * 计算目标方法中可用于新增临时变量的下一个局部变量槽位。
     *
     * 结果会综合方法参数、LocalVariableTable 与已有变量访问指令，避免覆盖现有局部变量。
     *
     * @param target 目标方法
     * @return 可安全分配给新增临时变量的起始槽位
     */
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

    /**
     * 字段目标约束。
     *
     * @property owner 字段 owner 的 JVM internal name；为 `null` 时不限制 owner
     * @property name 字段名；为 `null` 时不限制名称
     * @property desc 字段描述符；为 `null` 时不限制描述符
     */
    private data class FieldTarget(
        val owner: String?,
        val name: String?,
        val desc: String?,
    )

    /**
     * 局部变量读写过滤条件。
     *
     * @property index JVM 局部变量槽位；为 `null` 时不按槽位过滤
     * @property name LocalVariableTable 中的变量名；为 `null` 时不按名称过滤
     *
     * @author Dr (dr@der.kim)
     * @date 2026-05-31
     */
    private data class LocalVariableFilter(
        val index: Int? = null,
        val name: String? = null,
    ) {
        /**
         * 当前过滤条件是否为空。
         *
         * @return 未声明槽位和名称过滤时返回 `true`
         *
         * @author Dr (dr@der.kim)
         * @date 2026-05-31
         */
        fun isEmpty(): Boolean = index == null && name == null
    }

    /**
     * 局部变量槽位与其类型的配对信息。
     *
     * @property index JVM 局部变量槽位
     * @property type 该槽位承载的值类型
     *
     * @author Dr (dr@der.kim)
     * @date 2026-05-31
     */
    private data class LocalSlotType(
        val index: Int,
        val type: Type,
    )

    /**
     * WrapWithCondition 注入器使用的 opcode 集合与助记名索引。
     */
    private companion object {
        /**
         * 可作为数组字段来源或字段写入匹配辅助的字段读取 opcode。
         */
        private val FIELD_READ_OPS = setOf(Opcodes.GETFIELD, Opcodes.GETSTATIC)

        /**
         * 可被 `FIELD_ASSIGN` 条件包裹的字段写入 opcode。
         */
        private val FIELD_WRITE_OPS = setOf(Opcodes.PUTFIELD, Opcodes.PUTSTATIC)

        /**
         * 可被 `STORE` 条件包裹的局部变量写入 opcode。
         */
        private val STORE_OPS = setOf(Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE, Opcodes.ASTORE)

        /**
         * 可参与引用类型上下文推断的局部变量读写 opcode。
         */
        private val SLOT_REFERENCE_OPS = setOf(Opcodes.ALOAD, Opcodes.ASTORE)

        /**
         * JVM `I*` 局部变量指令可承载的窄整型类型。
         */
        private val INT_VARIABLE_TYPE_SORTS = setOf(Type.BOOLEAN, Type.BYTE, Type.SHORT, Type.INT, Type.CHAR)

        /**
         * 可被数组元素写入条件包裹的数组写入 opcode。
         */
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

        /**
         * JUMP 定位点可识别的 JVM 跳转 opcode。
         */
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

        /**
         * 可实际执行条件包裹的条件跳转 opcode。
         */
        private val CONDITIONAL_JUMP_OPS = JUMP_OPS - setOf(Opcodes.GOTO, Opcodes.JSR)

        /**
         * JUMP 目标助记名到 opcode 的映射。
         */
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
