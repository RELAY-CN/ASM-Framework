/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.injector.impl

import kim.der.asm.api.annotation.At
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.Operation
import kim.der.asm.api.annotation.Slice
import kim.der.asm.data.AsmInfo
import kim.der.asm.injector.AbstractAsmInjector
import kim.der.asm.utils.transformer.BytecodeUtil
import kim.der.asm.utils.transformer.InstructionUtil
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LookupSwitchInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TableSwitchInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * WrapOperation 注入器。
 *
 * 该注入器会匹配目标方法内的指定方法调用、构造器调用、字段读取、字段写入、数组元素读写、类型转换、类型判断、条件跳转、switch selector、常量读取或即将抛出的异常，
 * 并用 handler 替换原操作。
 * handler 会收到原操作 receiver（实例调用、实例字段读取与实例字段写入）、原调用参数、构造器参数、字段写入值或
 * 数组访问参数、类型转换或类型判断输入值、局部变量读取值、局部变量待写入值、原条件跳转分支结果、switch selector、原常量值、即将抛出的异常、[Operation] 句柄和可选目标方法参数；handler 可通过 [Operation.call]
 * 执行原始操作，也可以跳过或改变调用参数。
 *
 * 当前实现支持普通 [InjectionPoint.INVOKE] 方法调用、[InjectionPoint.FIELD] 字段读取与
 * [InjectionPoint.FIELD_ASSIGN] 字段写入。[InjectionPoint.FIELD] 可通过 `array=get` 包裹数组元素读取，
 * 通过 `array=length` 包裹数组长度读取；[InjectionPoint.FIELD_ASSIGN] 可通过 `array=set` 包裹数组元素写入；
 * [InjectionPoint.INVOKE] 未指定调用目标时，会按 handler 栈参数、[Operation] 位置与返回类型筛选兼容的普通调用、
 * `invokedynamic` 调用或构造器调用，且不兼容候选不会计入 [WrapOperation.ordinal] 或命中数；
 * [InjectionPoint.FIELD] 未指定字段目标时，会按 handler 字段 owner 参数、[Operation] 位置与返回类型筛选兼容的字段读取，
 * 且不兼容候选不会计入 [WrapOperation.ordinal] 或命中数；
 * [InjectionPoint.FIELD_ASSIGN] 未指定字段目标时，会按 handler 字段 owner 参数、待写入值、[Operation] 位置与
 * `Unit` 返回类型筛选兼容的字段写入，且不兼容候选不会计入 [WrapOperation.ordinal] 或命中数；
 * [InjectionPoint.INVOKE] 可通过 `<init>` 目标包裹常见 `NEW/DUP/args/INVOKESPECIAL` 构造器调用，
 * [InjectionPoint.NEW] 可通过类型目标直接包裹同一构造表达式；
 * [InjectionPoint.CAST] 可包裹 `CHECKCAST` 类型转换；省略类型目标时会按 handler 返回类型筛选兼容转换目标，
 * 不兼容目标不会计入 [WrapOperation.ordinal] 或命中数。[InjectionPoint.INSTANCEOF] 可包裹类型判断；省略类型目标时
 * 会匹配切片内全部 `INSTANCEOF` 判断。[InjectionPoint.JUMP] 可包裹条件跳转分支结果；`GOTO` 与 `JSR` 不支持包裹。
 * [InjectionPoint.SWITCH] 可包裹 `tableswitch` 与 `lookupswitch` 消费前的 `Int` selector；该模式不支持 [At.target]。
 * [InjectionPoint.CONSTANT] 可包裹 `LDC`、`ACONST_NULL`、数值常量、
 * `BIPUSH` 与 `SIPUSH`；省略常量目标时会按 handler 常量参数与返回类型筛选兼容常量。
 * [InjectionPoint.LOAD] 可包裹 `xLOAD` 读取出的局部变量表达式值，可通过 [At.args] 中的 `index=N` 或 `var=N`
 * 按 JVM 局部变量槽位过滤；handler 返回值只替换这一次读取结果，不写回原槽位。
 * [InjectionPoint.STORE] 可包裹 `xSTORE` 消费前的局部变量待写入值，可通过 [At.args] 中的 `index=N` 或 `var=N`
 * 按 JVM 局部变量槽位过滤；handler 返回值交给原 `xSTORE` 继续写入槽位。
 * [InjectionPoint.THROW] 可包裹 `ATHROW` 前的异常对象；指定目标时只匹配直接构造后抛出的同类型异常。
 *
 * @param at 操作点定位；当前支持 [InjectionPoint.INVOKE]、[InjectionPoint.FIELD]、[InjectionPoint.FIELD_ASSIGN]
 * 与 [InjectionPoint.NEW]、[InjectionPoint.CAST]、[InjectionPoint.INSTANCEOF]、[InjectionPoint.LOAD]、[InjectionPoint.STORE]、[InjectionPoint.JUMP]、[InjectionPoint.SWITCH]、[InjectionPoint.CONSTANT]、[InjectionPoint.THROW]
 * @param ordinal 匹配操作点序号；负数表示处理全部匹配操作点
 * @param slice 切片范围；当前 [InjectionPoint.INVOKE]、[InjectionPoint.FIELD] 与
 * [InjectionPoint.FIELD_ASSIGN]、[InjectionPoint.NEW]、[InjectionPoint.CAST]、[InjectionPoint.INSTANCEOF]、[InjectionPoint.JUMP]、
 * [InjectionPoint.LOAD]、[InjectionPoint.STORE]、[InjectionPoint.SWITCH]、[InjectionPoint.CONSTANT]、[InjectionPoint.THROW] 操作包裹使用 INVOKE 边界缩小匹配范围
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
class WrapOperationInjector(
    method: Method,
    asmInfo: AsmInfo,
    private val at: At,
    private val ordinal: Int = -1,
    private val slice: Slice = Slice(),
) : AbstractAsmInjector(method, asmInfo) {
    /**
     * 用 handler 替换匹配的原始操作。
     *
     * @param target 目标方法
     * @return 至少替换一个操作点时返回 `true`
     * @throws IllegalArgumentException 定位点、目标操作或 handler 签名不合法时抛出
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun inject(target: MethodNode): Boolean = injectCount(target) > 0

    /**
     * 用 handler 替换匹配的原始操作，并返回实际替换数量。
     *
     * @param target 目标方法
     * @return 实际替换的操作点数量
     * @throws IllegalArgumentException 定位点、目标操作或 handler 签名不合法时抛出
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun injectCount(target: MethodNode): Int =
        when (at.value) {
            InjectionPoint.INVOKE -> injectMethodCall(target)
            InjectionPoint.FIELD -> {
                when (arrayAccessMode()) {
                    ArrayAccessMode.GET -> injectArrayAccess(target, ArrayAccessMode.GET)
                    ArrayAccessMode.LENGTH -> injectArrayAccess(target, ArrayAccessMode.LENGTH)
                    ArrayAccessMode.SET -> throw IllegalArgumentException(
                        "@WrapOperation array=set requires FIELD_ASSIGN injection point",
                    )
                    null -> injectFieldRead(target)
                }
            }
            InjectionPoint.FIELD_ASSIGN -> {
                when (arrayAccessMode()) {
                    ArrayAccessMode.GET -> throw IllegalArgumentException(
                        "@WrapOperation array=get requires FIELD injection point",
                    )
                    ArrayAccessMode.LENGTH -> throw IllegalArgumentException(
                        "@WrapOperation array=length requires FIELD injection point",
                    )
                    ArrayAccessMode.SET -> injectArrayAccess(target, ArrayAccessMode.SET)
                    null -> injectFieldAssign(target)
                }
            }
            InjectionPoint.NEW -> injectNewConstructor(target)
            InjectionPoint.CAST -> injectCast(target)
            InjectionPoint.INSTANCEOF -> injectInstanceof(target)
            InjectionPoint.LOAD -> injectLoad(target)
            InjectionPoint.STORE -> injectStore(target)
            InjectionPoint.JUMP -> injectJump(target)
            InjectionPoint.SWITCH -> injectSwitch(target)
            InjectionPoint.CONSTANT -> injectConstant(target)
            InjectionPoint.THROW -> injectThrow(target)
            else -> throw IllegalArgumentException(
                "@WrapOperation currently supports only INVOKE, FIELD, FIELD_ASSIGN, NEW, CAST, INSTANCEOF, LOAD, STORE, JUMP, SWITCH, CONSTANT and THROW injection points",
            )
        }

    /**
     * 解析数组操作包裹模式。
     *
     * 未声明 `array=` 参数时返回 `null`，表示按普通字段读写处理。
     * 已声明时只接受 `get`、`set` 或 `length`，分别对应数组元素读取、写入与长度读取。
     *
     * @return 解析出的数组访问模式；未声明时返回 `null`
     * @throws IllegalArgumentException 声明了不支持的数组访问模式时抛出
     */
    private fun arrayAccessMode(): ArrayAccessMode? {
        val arrayArg = at.args.firstOrNull { it.trim().startsWith("array=") } ?: return null
        return when (arrayArg.substringAfter('=').trim().lowercase()) {
            "get" -> ArrayAccessMode.GET
            "set" -> ArrayAccessMode.SET
            "length" -> ArrayAccessMode.LENGTH
            else -> throw IllegalArgumentException("Unsupported @WrapOperation array access mode: $arrayArg")
        }
    }

    /**
     * 包裹普通方法调用、构造器调用或 `invokedynamic` 调用。
     *
     * 显式声明目标时按 owner、名称与描述符匹配；未声明目标时按 handler 签名筛选兼容调用。
     * 普通调用与 `invokedynamic` 会被替换为 handler 调用，构造器调用会额外移除配对的 `NEW` 与 `DUP`。
     *
     * @param target 目标方法
     * @return 实际包裹的调用数量
     * @throws IllegalArgumentException 目标签名不完整、构造器模式不合法或 handler 签名不兼容时抛出
     */
    private fun injectMethodCall(target: MethodNode): Int {
        val inferTarget = at.target.isEmpty()
        val (targetOwner, targetName, targetDesc) = parseTargetMethod(at.target)
        if (!inferTarget && (targetName == null || targetDesc == null)) {
            throw IllegalArgumentException("@WrapOperation INVOKE requires at.target method signature")
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
                    if (inferTarget && !isMethodCallHandlerCompatible(target, insn)) {
                        continue
                    }
                    val currentOrdinal = matchedOrdinal++
                    if (!matchesOrdinal(currentOrdinal)) {
                        continue
                    }
                    if (insn.name == "<init>") {
                        val allocation = findConstructorAllocation(insn)
                        val targetParamCount = validateConstructorHandlerSignature(target, insn)
                        val il = buildConstructorWrapper(target, insn, targetParamCount)
                        target.instructions.insertBefore(insn, il)
                        target.instructions.remove(allocation.newInsn)
                        target.instructions.remove(allocation.dupInsn)
                        target.instructions.remove(insn)
                        injectionCount++
                        continue
                    }

                    val targetParamCount = validateHandlerSignature(target, insn)
                    val il = buildOperationWrapper(target, insn, targetParamCount)
                    target.instructions.insertBefore(insn, il)
                    target.instructions.remove(insn)
                    injectionCount++
                }
                insn is InvokeDynamicInsnNode &&
                    (inferTarget || (targetName != null && matchesTargetInvokeDynamic(insn, targetOwner, targetName, targetDesc))) -> {
                    if (inferTarget && !isInvokeDynamicHandlerCompatible(target, insn)) {
                        continue
                    }
                    val currentOrdinal = matchedOrdinal++
                    if (!matchesOrdinal(currentOrdinal)) {
                        continue
                    }

                    val targetParamCount = validateInvokeDynamicHandlerSignature(target, insn)
                    val il = buildInvokeDynamicOperationWrapper(target, insn, targetParamCount)
                    target.instructions.insertBefore(insn, il)
                    target.instructions.remove(insn)
                    injectionCount++
                }
            }
        }

        return injectionCount
    }

    /**
     * 判断 handler 是否兼容候选普通方法或构造器调用。
     *
     * 该方法用于目标推断模式，失败候选不会计入 ordinal 或命中数。
     *
     * @param target 目标方法
     * @param insn 候选方法调用指令
     * @return handler 签名可包裹该调用时返回 `true`
     */
    private fun isMethodCallHandlerCompatible(
        target: MethodNode,
        insn: MethodInsnNode,
    ): Boolean =
        runCatching {
            if (insn.name == "<init>") {
                validateConstructorHandlerSignature(target, insn)
            } else {
                validateHandlerSignature(target, insn)
            }
        }.isSuccess

    /**
     * 判断 handler 是否兼容候选 `invokedynamic` 调用。
     *
     * 该方法用于目标推断模式，失败候选不会计入 ordinal 或命中数。
     *
     * @param target 目标方法
     * @param insn 候选 `invokedynamic` 指令
     * @return handler 签名可包裹该动态调用时返回 `true`
     */
    private fun isInvokeDynamicHandlerCompatible(
        target: MethodNode,
        insn: InvokeDynamicInsnNode,
    ): Boolean = runCatching { validateInvokeDynamicHandlerSignature(target, insn) }.isSuccess

    /**
     * 通过 [InjectionPoint.NEW] 包裹对象构造表达式。
     *
     * 该入口从 `NEW` 指令出发查找配对构造器调用，要求 `NEW` 后的下一条真实指令为 `DUP`。
     * 匹配后会用 handler 调用替换整段 `NEW/DUP/args/INVOKESPECIAL` 构造流程。
     *
     * @param target 目标方法
     * @return 实际包裹的构造表达式数量
     * @throws IllegalArgumentException 找不到配对构造器、`NEW` 后缺少 `DUP` 或 handler 签名不兼容时抛出
     */
    private fun injectNewConstructor(target: MethodNode): Int {
        val normalizedTarget = at.target.replace('.', '/')
        var injectionCount = 0
        var matchedOrdinal = 0
        val insns = target.instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)
        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (insn !is TypeInsnNode || insn.opcode != Opcodes.NEW) {
                continue
            }
            if (normalizedTarget.isNotEmpty() && insn.desc != normalizedTarget) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val constructorInsn = findConstructorInvocation(insn)
            val dupInsn = nextRealInstruction(insn)
            if (dupInsn?.opcode != Opcodes.DUP) {
                throw IllegalArgumentException(
                    "@WrapOperation NEW requires NEW followed by DUP for ${insn.desc}",
                )
            }
            val targetParamCount = validateConstructorHandlerSignature(target, constructorInsn)
            val il = buildConstructorWrapper(target, constructorInsn, targetParamCount)
            target.instructions.insertBefore(constructorInsn, il)
            target.instructions.remove(insn)
            target.instructions.remove(dupInsn)
            target.instructions.remove(constructorInsn)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 包裹字段读取操作。
     *
     * 显式声明字段目标时按 owner、名称与描述符匹配；未声明目标时按 handler 签名筛选兼容字段读取。
     * 匹配后会把原 `GETFIELD` 或 `GETSTATIC` 替换为 handler 调用，handler 可通过 [Operation.call] 执行原读取。
     *
     * @param target 目标方法
     * @return 实际包裹的字段读取数量
     * @throws IllegalArgumentException 字段目标缺少名称或 handler 签名不兼容时抛出
     */
    private fun injectFieldRead(target: MethodNode): Int {
        val inferTarget = at.target.isEmpty()
        val fieldTarget = parseFieldTarget(at.target)
        if (!inferTarget && fieldTarget.name == null) {
            throw IllegalArgumentException("@WrapOperation FIELD requires at.target field signature")
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
                insn.opcode !in FIELD_READ_OPS ||
                !(inferTarget || matchesTargetField(insn, fieldTarget))
            ) {
                continue
            }
            if (inferTarget && !isFieldReadHandlerCompatible(target, insn)) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val targetParamCount = validateFieldHandlerSignature(target, insn)
            val il = buildFieldReadWrapper(target, insn, targetParamCount)
            target.instructions.insertBefore(insn, il)
            target.instructions.remove(insn)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 判断 handler 是否兼容候选字段读取。
     *
     * 该方法用于目标推断模式，失败候选不会计入 ordinal 或命中数。
     *
     * @param target 目标方法
     * @param insn 候选字段读取指令
     * @return handler 签名可包裹该字段读取时返回 `true`
     */
    private fun isFieldReadHandlerCompatible(
        target: MethodNode,
        insn: FieldInsnNode,
    ): Boolean = runCatching { validateFieldHandlerSignature(target, insn) }.isSuccess

    /**
     * 包裹字段写入操作。
     *
     * 显式声明字段目标时按 owner、名称与描述符匹配；未声明目标时按 handler 签名筛选兼容字段写入。
     * 匹配后会把原 `PUTFIELD` 或 `PUTSTATIC` 替换为 handler 调用，handler 可通过 [Operation.call] 执行原写入。
     *
     * @param target 目标方法
     * @return 实际包裹的字段写入数量
     * @throws IllegalArgumentException 字段目标缺少名称或 handler 签名不兼容时抛出
     */
    private fun injectFieldAssign(target: MethodNode): Int {
        val inferTarget = at.target.isEmpty()
        val fieldTarget = parseFieldTarget(at.target)
        if (!inferTarget && fieldTarget.name == null) {
            throw IllegalArgumentException("@WrapOperation FIELD_ASSIGN requires at.target field signature")
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
            val il = buildFieldAssignWrapper(target, insn, targetParamCount)
            target.instructions.insertBefore(insn, il)
            target.instructions.remove(insn)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 判断 handler 是否兼容候选字段写入。
     *
     * 该方法用于目标推断模式，失败候选不会计入 ordinal 或命中数。
     *
     * @param target 目标方法
     * @param insn 候选字段写入指令
     * @return handler 签名可包裹该字段写入时返回 `true`
     */
    private fun isFieldAssignHandlerCompatible(
        target: MethodNode,
        insn: FieldInsnNode,
    ): Boolean = runCatching { validateFieldAssignHandlerSignature(target, insn) }.isSuccess

    /**
     * 包裹数组元素读取、数组元素写入或数组长度读取操作。
     *
     * 该入口通过 `array=get`、`array=set` 或 `array=length` 启用，并要求 `at.target` 指向数组字段。
     * 方法会从数组操作指令向前追踪数组字段来源，只包裹来源字段匹配的数组操作。
     *
     * @param target 目标方法
     * @param mode 数组访问模式
     * @return 实际包裹的数组操作数量
     * @throws IllegalArgumentException 数组字段目标缺失、目标不是数组字段或 handler 签名不兼容时抛出
     */
    private fun injectArrayAccess(
        target: MethodNode,
        mode: ArrayAccessMode,
    ): Int {
        val fieldTarget = parseFieldTarget(at.target)
        if (fieldTarget.name == null) {
            throw IllegalArgumentException("@WrapOperation array access requires at.target array field signature")
        }
        if (fieldTarget.desc != null && Type.getType(fieldTarget.desc).sort != Type.ARRAY) {
            throw IllegalArgumentException("@WrapOperation array access target must be an array field: ${at.target}")
        }

        var injectionCount = 0
        var matchedOrdinal = 0
        val targetOpcodes =
            when (mode) {
                ArrayAccessMode.GET -> ARRAY_READ_OPS
                ArrayAccessMode.SET -> ARRAY_WRITE_OPS
                ArrayAccessMode.LENGTH -> setOf(Opcodes.ARRAYLENGTH)
            }

        val insns = target.instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)
        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (insn.opcode !in targetOpcodes) {
                continue
            }

            val fieldInsn = findArrayFieldProducer(insn, fieldTarget) ?: continue
            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val targetParamCount = validateArrayAccessHandlerSignature(target, fieldInsn, mode)
            val il = buildArrayAccessWrapper(target, fieldInsn, mode, targetParamCount)
            target.instructions.insertBefore(insn, il)
            target.instructions.remove(insn)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 包裹 `CHECKCAST` 类型转换操作。
     *
     * 显式声明类型目标时只匹配该 cast 类型；未声明目标时按 handler 返回类型筛选兼容转换目标。
     * 匹配后会把原 `CHECKCAST` 替换为 handler 调用，handler 可通过 [Operation.call] 执行原转换。
     *
     * @param target 目标方法
     * @return 实际包裹的类型转换数量
     * @throws IllegalArgumentException handler 签名不兼容时抛出
     */
    private fun injectCast(target: MethodNode): Int {
        val castTarget = at.target.replace('.', '/')
        val matchAnyTarget = castTarget.isEmpty()

        var injectionCount = 0
        var matchedOrdinal = 0
        val insns = target.instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)
        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (insn !is TypeInsnNode || insn.opcode != Opcodes.CHECKCAST || (!matchAnyTarget && insn.desc != castTarget)) {
                continue
            }
            if (matchAnyTarget && !isCastReturnCompatible(Type.getObjectType(insn.desc))) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val targetParamCount = validateCastHandlerSignature(target, insn)
            val il = buildCastWrapper(target, insn, targetParamCount)
            target.instructions.insertBefore(insn, il)
            target.instructions.remove(insn)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 判断目标推断模式下 handler 返回类型是否兼容候选 cast 类型。
     *
     * @param castType 候选 `CHECKCAST` 的目标类型
     * @return handler 返回类型可放回该 cast 结果位置时返回 `true`
     */
    private fun isCastReturnCompatible(castType: Type): Boolean =
        isReturnCompatible(castType, Type.getReturnType(asmMethod))

    /**
     * 包裹 `INSTANCEOF` 类型判断操作。
     *
     * 显式声明类型目标时只匹配该 `INSTANCEOF` 类型；未声明目标时匹配切片内全部类型判断。
     * 匹配后会把原 `INSTANCEOF` 替换为 handler 调用，handler 可通过 [Operation.call] 执行原判断。
     *
     * @param target 目标方法
     * @return 实际包裹的类型判断数量
     * @throws IllegalArgumentException handler 签名不兼容时抛出
     */
    private fun injectInstanceof(target: MethodNode): Int {
        val typeTarget = at.target.replace('.', '/')
        val matchAnyTarget = typeTarget.isEmpty()

        var injectionCount = 0
        var matchedOrdinal = 0
        val insns = target.instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)
        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (insn !is TypeInsnNode || insn.opcode != Opcodes.INSTANCEOF || (!matchAnyTarget && insn.desc != typeTarget)) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val targetParamCount = validateInstanceofHandlerSignature(target, insn)
            val il = buildInstanceofWrapper(target, insn, targetParamCount)
            target.instructions.insertBefore(insn, il)
            target.instructions.remove(insn)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 包裹条件跳转操作的 boolean 分支结果。
     *
     * `at.target` 可声明具体条件跳转 opcode 名称或数字；未声明时匹配所有条件跳转。
     * 原跳转会被替换为 handler 调用后的 `IFNE` 分派，handler 可通过 [Operation.call] 取得原分支结果。
     *
     * @param target 目标方法
     * @return 实际包裹的条件跳转数量
     * @throws IllegalArgumentException 目标 opcode 不是条件跳转或 handler 签名不兼容时抛出
     */
    private fun injectJump(target: MethodNode): Int {
        val targetOpcode = parseJumpOpcodeTarget(at.target)
        if (targetOpcode != null && targetOpcode !in CONDITIONAL_JUMP_OPS) {
            throw IllegalArgumentException(
                "@WrapOperation JUMP target must be a conditional JVM jump opcode: ${at.target}",
            )
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
                insn !is JumpInsnNode ||
                insn.opcode !in CONDITIONAL_JUMP_OPS ||
                (targetOpcode != null && insn.opcode != targetOpcode)
            ) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val targetParamCount = validateJumpHandlerSignature(target)
            val il = buildJumpWrapper(insn, target, targetParamCount)
            target.instructions.insertBefore(insn, il)
            target.instructions.remove(insn)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 包裹局部变量读取操作产生的表达式值。
     *
     * `LOAD` 不使用 `at.target`，可通过 `At.args` 的 `index=N` 或 `var=N` 限定 JVM 局部变量槽位。
     * 注入逻辑插入在读取指令之后，只替换本次读取压入栈顶的值，不回写局部变量槽位。
     *
     * @param target 目标方法
     * @return 实际包裹的局部变量读取数量
     * @throws IllegalArgumentException 声明了 `at.target`、槽位过滤参数非法或 handler 签名不兼容时抛出
     */
    private fun injectLoad(target: MethodNode): Int {
        require(at.target.isEmpty()) {
            "@WrapOperation LOAD uses At.args index=N or var=N for local variable slot filtering, not At.target"
        }
        val localVariableIndex = parseLocalVariableIndex("LOAD")
        val handlerLoadType = requireHandlerLocalArgumentType("LOAD")
        var injectionCount = 0
        var matchedOrdinal = 0
        val insns = target.instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)
        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (insn !is VarInsnNode || insn.opcode !in LOAD_OPS) {
                continue
            }
            if (localVariableIndex != null && insn.`var` != localVariableIndex) {
                continue
            }
            if (!isLoadCompatibleWithHandler(insn.opcode, handlerLoadType)) {
                continue
            }

            val resolvedLoadType = resolveIndexedLoadType(target, insn.`var`, handlerLoadType)
            if (localVariableIndex == null && handlerLoadType.isReferenceType() && resolvedLoadType == null) {
                continue
            }
            val loadType = resolvedLoadType ?: handlerLoadType
            if (localVariableIndex == null && !isLoadHandlerCompatible(target, loadType)) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val targetParamCount = validateLoadHandlerSignature(target, loadType)
            val il = buildLoadWrapper(target, loadType, targetParamCount)
            target.instructions.insert(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 包裹局部变量写入操作即将消费的表达式值。
     *
     * `STORE` 不使用 `at.target`，可通过 `At.args` 的 `index=N` 或 `var=N` 限定 JVM 局部变量槽位。
     * 注入逻辑插入在写入指令之前，让原 `xSTORE` 继续把 handler 返回的新值写入局部变量槽位。
     *
     * @param target 目标方法
     * @return 实际包裹的局部变量写入数量
     * @throws IllegalArgumentException 声明了 `at.target`、槽位过滤参数非法或 handler 签名不兼容时抛出
     */
    private fun injectStore(target: MethodNode): Int {
        require(at.target.isEmpty()) {
            "@WrapOperation STORE uses At.args index=N or var=N for local variable slot filtering, not At.target"
        }
        val localVariableIndex = parseLocalVariableIndex("STORE")
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
            if (localVariableIndex != null && insn.`var` != localVariableIndex) {
                continue
            }
            if (!isStoreCompatibleWithHandler(insn.opcode, handlerStoreType)) {
                continue
            }

            val resolvedStoreType = resolveIndexedLoadType(target, insn.`var`, handlerStoreType)
            if (localVariableIndex == null && handlerStoreType.isReferenceType() && resolvedStoreType == null) {
                continue
            }
            val storeType = resolvedStoreType ?: handlerStoreType
            if (localVariableIndex == null && !isLoadHandlerCompatible(target, storeType)) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val targetParamCount = validateLoadHandlerSignature(target, storeType)
            val il = buildStoreWrapper(target, storeType, targetParamCount)
            target.instructions.insertBefore(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    private fun injectConstant(target: MethodNode): Int {
        val inferTarget = at.target.isEmpty()
        var injectionCount = 0
        var matchedOrdinal = 0
        val insns = target.instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)
        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (!BytecodeUtil.isConstant(insn)) {
                continue
            }
            if (!inferTarget && !matchesConstant(insn, at.target)) {
                continue
            }

            val constantType = BytecodeUtil.getConstantType(insn) ?: continue
            val handlerConstantType = resolveHandlerConstantType(insn, constantType)
            if (inferTarget && !isConstantHandlerCompatible(target, handlerConstantType)) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val targetParamCount = validateConstantHandlerSignature(target, handlerConstantType)
            val replacementType = resolveConstantReplacementType(insn, constantType)
            val il = buildConstantWrapper(target, insn, handlerConstantType, replacementType, targetParamCount)
            target.instructions.insertBefore(insn, il)
            target.instructions.remove(insn)
            injectionCount++
        }

        return injectionCount
    }

    private fun injectSwitch(target: MethodNode): Int {
        if (at.target.isNotEmpty()) {
            throw IllegalArgumentException("@WrapOperation SWITCH does not support at.target")
        }

        var injectionCount = 0
        var matchedOrdinal = 0
        val insns = target.instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)
        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (insn !is TableSwitchInsnNode && insn !is LookupSwitchInsnNode) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val targetParamCount = validateSwitchHandlerSignature(target)
            val il = buildSwitchWrapper(target, targetParamCount)
            target.instructions.insertBefore(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    private fun isConstantHandlerCompatible(
        target: MethodNode,
        constantType: Type,
    ): Boolean = runCatching { validateConstantHandlerSignature(target, constantType) }.isSuccess

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
            val il = buildThrowWrapper(target, targetParamCount)
            target.instructions.insertBefore(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    private fun isThrowHandlerCompatible(target: MethodNode): Boolean =
        runCatching { validateThrowHandlerSignature(target) }.isSuccess

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

    private fun matchesConstant(
        insn: AbstractInsnNode,
        expected: String,
    ): Boolean {
        val value = BytecodeUtil.getConstant(insn)
        if (value == null) {
            return expected == "null"
        }
        if (value is Type) {
            return if (value.sort == Type.METHOD) {
                value.descriptor == expected
            } else {
                value.internalName == expected.replace('.', '/')
            }
        }
        return value.toString() == expected
    }

    private fun buildOperationWrapper(
        target: MethodNode,
        callInsn: MethodInsnNode,
        targetParamCount: Int,
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
        val operationIndex = nextTempIndex

        for (index in callParamTypes.indices.reversed()) {
            storeStackValue(il, callParamTypes[index], argSlots[index])
        }
        if (receiverIndex != null) {
            il.add(VarInsnNode(Opcodes.ASTORE, receiverIndex))
        }

        createMethodOperation(il, callInsn, callParamTypes)
        il.add(VarInsnNode(Opcodes.ASTORE, operationIndex))

        addHandlerOwner(il)
        if (receiverIndex != null) {
            il.add(VarInsnNode(Opcodes.ALOAD, receiverIndex))
        }
        for (index in callParamTypes.indices) {
            loadFromVariable(il, callParamTypes[index], argSlots[index])
        }
        il.add(VarInsnNode(Opcodes.ALOAD, operationIndex))
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

        val callReturnType = Type.getReturnType(callInsn.desc)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (
            callReturnType != Type.VOID_TYPE &&
            callReturnType != handlerReturnType &&
            callReturnType.sort >= Type.ARRAY
        ) {
            il.add(TypeInsnNode(Opcodes.CHECKCAST, callReturnType.internalName))
        }

        return il
    }

    private fun buildInvokeDynamicOperationWrapper(
        target: MethodNode,
        callInsn: InvokeDynamicInsnNode,
        targetParamCount: Int,
    ): InsnList {
        val il = InsnList()
        val callParamTypes = Type.getArgumentTypes(callInsn.desc)
        var nextTempIndex = nextLocalIndex(target)
        val argSlots =
            callParamTypes.map { paramType ->
                nextTempIndex.also { nextTempIndex += paramType.size }
            }
        val operationIndex = nextTempIndex

        for (index in callParamTypes.indices.reversed()) {
            storeStackValue(il, callParamTypes[index], argSlots[index])
        }

        createInvokeDynamicOperation(il, callInsn, callParamTypes)
        il.add(VarInsnNode(Opcodes.ASTORE, operationIndex))

        addHandlerOwner(il)
        for (index in callParamTypes.indices) {
            loadFromVariable(il, callParamTypes[index], argSlots[index])
        }
        il.add(VarInsnNode(Opcodes.ALOAD, operationIndex))
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

        val callReturnType = Type.getReturnType(callInsn.desc)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (
            callReturnType != Type.VOID_TYPE &&
            callReturnType != handlerReturnType &&
            callReturnType.sort >= Type.ARRAY
        ) {
            il.add(TypeInsnNode(Opcodes.CHECKCAST, callReturnType.internalName))
        }

        return il
    }

    private fun buildConstructorWrapper(
        target: MethodNode,
        constructorInsn: MethodInsnNode,
        targetParamCount: Int,
    ): InsnList {
        val il = InsnList()
        val constructorParamTypes = Type.getArgumentTypes(constructorInsn.desc)
        var nextTempIndex = nextLocalIndex(target)
        val argSlots =
            constructorParamTypes.map { paramType ->
                nextTempIndex.also { nextTempIndex += paramType.size }
            }
        val operationIndex = nextTempIndex

        for (index in constructorParamTypes.indices.reversed()) {
            storeStackValue(il, constructorParamTypes[index], argSlots[index])
        }

        createConstructorOperation(il, constructorInsn, constructorParamTypes)
        il.add(VarInsnNode(Opcodes.ASTORE, operationIndex))

        addHandlerOwner(il)
        for (index in constructorParamTypes.indices) {
            loadFromVariable(il, constructorParamTypes[index], argSlots[index])
        }
        il.add(VarInsnNode(Opcodes.ALOAD, operationIndex))
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

        val constructedType = Type.getObjectType(constructorInsn.owner)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (constructedType != handlerReturnType && constructedType.sort >= Type.ARRAY) {
            il.add(TypeInsnNode(Opcodes.CHECKCAST, constructedType.internalName))
        }

        return il
    }

    private fun buildFieldAssignWrapper(
        target: MethodNode,
        fieldInsn: FieldInsnNode,
        targetParamCount: Int,
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
        val operationIndex = nextTempIndex

        storeStackValue(il, fieldType, valueIndex)
        if (receiverIndex != null) {
            il.add(VarInsnNode(Opcodes.ASTORE, receiverIndex))
        }

        createFieldWriteOperation(il, fieldInsn)
        il.add(VarInsnNode(Opcodes.ASTORE, operationIndex))

        addHandlerOwner(il)
        if (receiverIndex != null) {
            il.add(VarInsnNode(Opcodes.ALOAD, receiverIndex))
        }
        loadFromVariable(il, fieldType, valueIndex)
        il.add(VarInsnNode(Opcodes.ALOAD, operationIndex))
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

        return il
    }

    private fun buildFieldReadWrapper(
        target: MethodNode,
        fieldInsn: FieldInsnNode,
        targetParamCount: Int,
    ): InsnList {
        val il = InsnList()
        val fieldType = Type.getType(fieldInsn.desc)
        var nextTempIndex = nextLocalIndex(target)
        val receiverIndex =
            if (fieldInsn.opcode == Opcodes.GETSTATIC) {
                null
            } else {
                nextTempIndex.also { nextTempIndex += 1 }
            }
        val operationIndex = nextTempIndex

        if (receiverIndex != null) {
            il.add(VarInsnNode(Opcodes.ASTORE, receiverIndex))
        }

        createFieldReadOperation(il, fieldInsn)
        il.add(VarInsnNode(Opcodes.ASTORE, operationIndex))

        addHandlerOwner(il)
        if (receiverIndex != null) {
            il.add(VarInsnNode(Opcodes.ALOAD, receiverIndex))
        }
        il.add(VarInsnNode(Opcodes.ALOAD, operationIndex))
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

        val handlerReturnType = Type.getReturnType(asmMethod)
        if (fieldType != handlerReturnType && fieldType.sort >= Type.ARRAY) {
            il.add(TypeInsnNode(Opcodes.CHECKCAST, fieldType.internalName))
        }

        return il
    }

    private fun buildArrayAccessWrapper(
        target: MethodNode,
        fieldInsn: FieldInsnNode,
        mode: ArrayAccessMode,
        targetParamCount: Int,
    ): InsnList {
        val il = InsnList()
        val arrayType = Type.getType(fieldInsn.desc)
        val elementType = arrayType.elementType
        var nextTempIndex = nextLocalIndex(target)
        val arrayIndex = nextTempIndex.also { nextTempIndex += 1 }
        val indexIndex =
            if (mode == ArrayAccessMode.LENGTH) {
                null
            } else {
                nextTempIndex.also { nextTempIndex += 1 }
            }
        val valueIndex =
            if (mode == ArrayAccessMode.SET) {
                nextTempIndex.also { nextTempIndex += elementType.size }
            } else {
                null
            }
        val operationIndex = nextTempIndex

        if (valueIndex != null) {
            storeStackValue(il, elementType, valueIndex)
        }
        if (indexIndex != null) {
            storeStackValue(il, Type.INT_TYPE, indexIndex)
        }
        storeStackValue(il, arrayType, arrayIndex)

        createArrayOperation(il, arrayType, mode)
        il.add(VarInsnNode(Opcodes.ASTORE, operationIndex))

        addHandlerOwner(il)
        loadFromVariable(il, arrayType, arrayIndex)
        if (indexIndex != null) {
            loadFromVariable(il, Type.INT_TYPE, indexIndex)
        }
        if (valueIndex != null) {
            loadFromVariable(il, elementType, valueIndex)
        }
        il.add(VarInsnNode(Opcodes.ALOAD, operationIndex))
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

        if (mode == ArrayAccessMode.GET) {
            val handlerReturnType = Type.getReturnType(asmMethod)
            if (elementType != handlerReturnType && elementType.sort >= Type.ARRAY) {
                il.add(TypeInsnNode(Opcodes.CHECKCAST, elementType.internalName))
            }
        }

        return il
    }

    private fun buildCastWrapper(
        target: MethodNode,
        castInsn: TypeInsnNode,
        targetParamCount: Int,
    ): InsnList {
        val il = InsnList()
        val castType = Type.getObjectType(castInsn.desc)
        var nextTempIndex = nextLocalIndex(target)
        val valueIndex = nextTempIndex.also { nextTempIndex += 1 }
        val operationIndex = nextTempIndex

        il.add(VarInsnNode(Opcodes.ASTORE, valueIndex))

        createCastOperation(il, castType)
        il.add(VarInsnNode(Opcodes.ASTORE, operationIndex))

        addHandlerOwner(il)
        il.add(VarInsnNode(Opcodes.ALOAD, valueIndex))
        il.add(VarInsnNode(Opcodes.ALOAD, operationIndex))
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

        val handlerReturnType = Type.getReturnType(asmMethod)
        if (castType != handlerReturnType) {
            il.add(TypeInsnNode(Opcodes.CHECKCAST, castType.internalName))
        }

        return il
    }

    private fun buildInstanceofWrapper(
        target: MethodNode,
        instanceofInsn: TypeInsnNode,
        targetParamCount: Int,
    ): InsnList {
        val il = InsnList()
        val instanceofType = Type.getObjectType(instanceofInsn.desc)
        var nextTempIndex = nextLocalIndex(target)
        val valueIndex = nextTempIndex.also { nextTempIndex += 1 }
        val operationIndex = nextTempIndex

        il.add(VarInsnNode(Opcodes.ASTORE, valueIndex))

        createInstanceofOperation(il, instanceofType)
        il.add(VarInsnNode(Opcodes.ASTORE, operationIndex))

        addHandlerOwner(il)
        il.add(VarInsnNode(Opcodes.ALOAD, valueIndex))
        il.add(VarInsnNode(Opcodes.ALOAD, operationIndex))
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

        return il
    }

    private fun buildLoadWrapper(
        target: MethodNode,
        loadType: Type,
        targetParamCount: Int,
    ): InsnList {
        val il = InsnList()
        var nextTempIndex = nextLocalIndex(target)
        val valueIndex = nextTempIndex.also { nextTempIndex += loadType.size }
        val operationIndex = nextTempIndex

        storeStackValue(il, loadType, valueIndex)

        createLoadOperation(il, loadType)
        il.add(VarInsnNode(Opcodes.ASTORE, operationIndex))

        addHandlerOwner(il)
        loadFromVariable(il, loadType, valueIndex)
        il.add(VarInsnNode(Opcodes.ALOAD, operationIndex))
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

        val handlerReturnType = Type.getReturnType(asmMethod)
        if (loadType != handlerReturnType && loadType.isReferenceType()) {
            il.add(TypeInsnNode(Opcodes.CHECKCAST, loadType.internalName))
        }

        return il
    }

    private fun buildStoreWrapper(
        target: MethodNode,
        storeType: Type,
        targetParamCount: Int,
    ): InsnList {
        val il = InsnList()
        var nextTempIndex = nextLocalIndex(target)
        val valueIndex = nextTempIndex.also { nextTempIndex += storeType.size }
        val operationIndex = nextTempIndex

        storeStackValue(il, storeType, valueIndex)

        createStoreOperation(il, storeType)
        il.add(VarInsnNode(Opcodes.ASTORE, operationIndex))

        addHandlerOwner(il)
        loadFromVariable(il, storeType, valueIndex)
        il.add(VarInsnNode(Opcodes.ALOAD, operationIndex))
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

        val handlerReturnType = Type.getReturnType(asmMethod)
        if (storeType != handlerReturnType && storeType.isReferenceType()) {
            il.add(TypeInsnNode(Opcodes.CHECKCAST, storeType.internalName))
        }

        return il
    }

    private fun buildConstantWrapper(
        target: MethodNode,
        constantInsn: AbstractInsnNode,
        handlerConstantType: Type,
        replacementType: Type,
        targetParamCount: Int,
    ): InsnList {
        val il = InsnList()
        val operationIndex = nextLocalIndex(target)

        createConstantOperation(il, constantInsn, handlerConstantType)
        il.add(VarInsnNode(Opcodes.ASTORE, operationIndex))

        addHandlerOwner(il)
        loadConstant(il, constantInsn)
        il.add(VarInsnNode(Opcodes.ALOAD, operationIndex))
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
        addConstantCastIfNeeded(il, replacementType)

        return il
    }

    private fun buildJumpWrapper(
        jumpInsn: JumpInsnNode,
        target: MethodNode,
        targetParamCount: Int,
    ): InsnList {
        val originalTrue = LabelNode()
        val afterOriginal = LabelNode()
        val il = InsnList()
        il.add(JumpInsnNode(jumpInsn.opcode, originalTrue))
        il.add(InsnNode(Opcodes.ICONST_0))
        il.add(JumpInsnNode(Opcodes.GOTO, afterOriginal))
        il.add(originalTrue)
        il.add(InsnNode(Opcodes.ICONST_1))
        il.add(afterOriginal)
        il.add(buildJumpOperationCall(target, targetParamCount))
        il.add(JumpInsnNode(Opcodes.IFNE, jumpInsn.label))
        return il
    }

    private fun buildJumpOperationCall(
        target: MethodNode,
        targetParamCount: Int,
    ): InsnList {
        val il = InsnList()
        var nextTempIndex = nextLocalIndex(target)
        val branchIndex = nextTempIndex.also { nextTempIndex += 1 }
        val operationIndex = nextTempIndex

        il.add(VarInsnNode(Opcodes.ISTORE, branchIndex))

        createJumpOperation(il)
        il.add(VarInsnNode(Opcodes.ASTORE, operationIndex))

        addHandlerOwner(il)
        il.add(VarInsnNode(Opcodes.ILOAD, branchIndex))
        il.add(VarInsnNode(Opcodes.ALOAD, operationIndex))
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

        return il
    }

    private fun buildSwitchWrapper(
        target: MethodNode,
        targetParamCount: Int,
    ): InsnList {
        val il = InsnList()
        var nextTempIndex = nextLocalIndex(target)
        val selectorIndex = nextTempIndex.also { nextTempIndex += 1 }
        val operationIndex = nextTempIndex

        il.add(VarInsnNode(Opcodes.ISTORE, selectorIndex))

        createSwitchOperation(il)
        il.add(VarInsnNode(Opcodes.ASTORE, operationIndex))

        addHandlerOwner(il)
        il.add(VarInsnNode(Opcodes.ILOAD, selectorIndex))
        il.add(VarInsnNode(Opcodes.ALOAD, operationIndex))
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

        return il
    }

    private fun buildThrowWrapper(
        target: MethodNode,
        targetParamCount: Int,
    ): InsnList {
        val il = InsnList()
        val throwableType = Type.getType(Throwable::class.java)
        var nextTempIndex = nextLocalIndex(target)
        val throwableIndex = nextTempIndex.also { nextTempIndex += 1 }
        val operationIndex = nextTempIndex

        il.add(VarInsnNode(Opcodes.ASTORE, throwableIndex))

        createThrowOperation(il)
        il.add(VarInsnNode(Opcodes.ASTORE, operationIndex))

        addHandlerOwner(il)
        il.add(VarInsnNode(Opcodes.ALOAD, throwableIndex))
        il.add(VarInsnNode(Opcodes.ALOAD, operationIndex))
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

        val handlerReturnType = Type.getReturnType(asmMethod)
        if (throwableType != handlerReturnType) {
            il.add(TypeInsnNode(Opcodes.CHECKCAST, throwableType.internalName))
        }

        return il
    }

    private fun createMethodOperation(
        il: InsnList,
        callInsn: MethodInsnNode,
        callParamTypes: Array<Type>,
    ) {
        val operationType = Type.getType(Operation::class.java)
        il.add(TypeInsnNode(Opcodes.NEW, operationType.internalName))
        il.add(InsnNode(Opcodes.DUP))
        il.add(LdcInsnNode(Type.getObjectType(callInsn.owner)))
        il.add(LdcInsnNode(callInsn.name))
        il.add(LdcInsnNode(callInsn.desc))
        il.add(InsnNode(if (callInsn.opcode == Opcodes.INVOKESTATIC) Opcodes.ICONST_1 else Opcodes.ICONST_0))
        il.add(LdcInsnNode(callParamTypes.size))
        il.add(TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Class"))
        for (index in callParamTypes.indices) {
            il.add(InsnNode(Opcodes.DUP))
            il.add(LdcInsnNode(index))
            il.add(InstructionUtil.loadType(callParamTypes[index]))
            il.add(InsnNode(Opcodes.AASTORE))
        }
        il.add(
            MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                operationType.internalName,
                "<init>",
                "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;Z[Ljava/lang/Class;)V",
                false,
            ),
        )
    }

    private fun createInvokeDynamicOperation(
        il: InsnList,
        callInsn: InvokeDynamicInsnNode,
        callParamTypes: Array<Type>,
    ) {
        val operationType = Type.getType(Operation::class.java)
        il.add(TypeInsnNode(Opcodes.NEW, operationType.internalName))
        il.add(InsnNode(Opcodes.DUP))
        il.add(LdcInsnNode(callInsn.bsm))
        il.add(LdcInsnNode(callInsn.name))
        il.add(LdcInsnNode(callInsn.desc))
        il.add(InstructionUtil.loadType(Type.getReturnType(callInsn.desc)))
        pushClassArray(il, callParamTypes)
        pushBootstrapArgumentArray(il, callInsn.bsmArgs)
        il.add(
            MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                operationType.internalName,
                "<init>",
                "(Ljava/lang/invoke/MethodHandle;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Class;" +
                    "[Ljava/lang/Class;[Ljava/lang/Object;)V",
                false,
            ),
        )
    }

    private fun createConstructorOperation(
        il: InsnList,
        constructorInsn: MethodInsnNode,
        constructorParamTypes: Array<Type>,
    ) {
        val operationType = Type.getType(Operation::class.java)
        il.add(TypeInsnNode(Opcodes.NEW, operationType.internalName))
        il.add(InsnNode(Opcodes.DUP))
        il.add(LdcInsnNode(Type.getObjectType(constructorInsn.owner)))
        il.add(LdcInsnNode(constructorInsn.desc))
        il.add(LdcInsnNode(constructorParamTypes.size))
        il.add(TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Class"))
        for (index in constructorParamTypes.indices) {
            il.add(InsnNode(Opcodes.DUP))
            il.add(LdcInsnNode(index))
            il.add(InstructionUtil.loadType(constructorParamTypes[index]))
            il.add(InsnNode(Opcodes.AASTORE))
        }
        il.add(
            MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                operationType.internalName,
                "<init>",
                "(Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/Class;)V",
                false,
            ),
        )
    }

    private fun createFieldReadOperation(
        il: InsnList,
        fieldInsn: FieldInsnNode,
    ) {
        val operationType = Type.getType(Operation::class.java)
        il.add(TypeInsnNode(Opcodes.NEW, operationType.internalName))
        il.add(InsnNode(Opcodes.DUP))
        il.add(LdcInsnNode(Type.getObjectType(fieldInsn.owner)))
        il.add(LdcInsnNode(fieldInsn.name))
        il.add(LdcInsnNode(fieldInsn.desc))
        il.add(InsnNode(if (fieldInsn.opcode == Opcodes.GETSTATIC) Opcodes.ICONST_1 else Opcodes.ICONST_0))
        il.add(
            MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                operationType.internalName,
                "<init>",
                "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;Z)V",
                false,
            ),
        )
    }

    private fun createFieldWriteOperation(
        il: InsnList,
        fieldInsn: FieldInsnNode,
    ) {
        val operationType = Type.getType(Operation::class.java)
        il.add(TypeInsnNode(Opcodes.NEW, operationType.internalName))
        il.add(InsnNode(Opcodes.DUP))
        il.add(LdcInsnNode(Type.getObjectType(fieldInsn.owner)))
        il.add(LdcInsnNode(fieldInsn.name))
        il.add(LdcInsnNode(fieldInsn.desc))
        il.add(InsnNode(if (fieldInsn.opcode == Opcodes.PUTSTATIC) Opcodes.ICONST_1 else Opcodes.ICONST_0))
        il.add(InsnNode(Opcodes.ICONST_1))
        il.add(
            MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                operationType.internalName,
                "<init>",
                "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;ZZ)V",
                false,
            ),
        )
    }

    private fun createArrayOperation(
        il: InsnList,
        arrayType: Type,
        mode: ArrayAccessMode,
    ) {
        val operationType = Type.getType(Operation::class.java)
        il.add(TypeInsnNode(Opcodes.NEW, operationType.internalName))
        il.add(InsnNode(Opcodes.DUP))
        il.add(LdcInsnNode(arrayType))
        if (mode == ArrayAccessMode.LENGTH) {
            il.add(
                MethodInsnNode(
                    Opcodes.INVOKESPECIAL,
                    operationType.internalName,
                    "<init>",
                    "(Ljava/lang/Class;)V",
                    false,
                ),
            )
            return
        }
        il.add(InsnNode(if (mode == ArrayAccessMode.SET) Opcodes.ICONST_1 else Opcodes.ICONST_0))
        il.add(
            MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                operationType.internalName,
                "<init>",
                "(Ljava/lang/Class;Z)V",
                false,
            ),
        )
    }

    private fun createCastOperation(
        il: InsnList,
        castType: Type,
    ) {
        val operationType = Type.getType(Operation::class.java)
        il.add(TypeInsnNode(Opcodes.NEW, operationType.internalName))
        il.add(InsnNode(Opcodes.DUP))
        il.add(LdcInsnNode(castType))
        il.add(LdcInsnNode("<checkcast>"))
        il.add(
            MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                operationType.internalName,
                "<init>",
                "(Ljava/lang/Class;Ljava/lang/String;)V",
                false,
            ),
        )
    }

    private fun createInstanceofOperation(
        il: InsnList,
        instanceofType: Type,
    ) {
        val operationType = Type.getType(Operation::class.java)
        il.add(TypeInsnNode(Opcodes.NEW, operationType.internalName))
        il.add(InsnNode(Opcodes.DUP))
        il.add(LdcInsnNode(instanceofType))
        il.add(LdcInsnNode("<instanceof>"))
        il.add(
            MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                operationType.internalName,
                "<init>",
                "(Ljava/lang/Class;Ljava/lang/String;)V",
                false,
            ),
        )
    }

    private fun createLoadOperation(
        il: InsnList,
        loadType: Type,
    ) {
        val operationType = Type.getType(Operation::class.java)
        il.add(TypeInsnNode(Opcodes.NEW, operationType.internalName))
        il.add(InsnNode(Opcodes.DUP))
        il.add(InstructionUtil.loadType(loadType))
        il.add(LdcInsnNode("<load>"))
        il.add(
            MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                operationType.internalName,
                "<init>",
                "(Ljava/lang/Class;Ljava/lang/String;)V",
                false,
            ),
        )
    }

    private fun createStoreOperation(
        il: InsnList,
        storeType: Type,
    ) {
        val operationType = Type.getType(Operation::class.java)
        il.add(TypeInsnNode(Opcodes.NEW, operationType.internalName))
        il.add(InsnNode(Opcodes.DUP))
        il.add(InstructionUtil.loadType(storeType))
        il.add(LdcInsnNode("<store>"))
        il.add(
            MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                operationType.internalName,
                "<init>",
                "(Ljava/lang/Class;Ljava/lang/String;)V",
                false,
            ),
        )
    }

    private fun createJumpOperation(il: InsnList) {
        val operationType = Type.getType(Operation::class.java)
        il.add(TypeInsnNode(Opcodes.NEW, operationType.internalName))
        il.add(InsnNode(Opcodes.DUP))
        il.add(InstructionUtil.loadType(Type.BOOLEAN_TYPE))
        il.add(LdcInsnNode("<jump>"))
        il.add(
            MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                operationType.internalName,
                "<init>",
                "(Ljava/lang/Class;Ljava/lang/String;)V",
                false,
            ),
        )
    }

    private fun createSwitchOperation(il: InsnList) {
        val operationType = Type.getType(Operation::class.java)
        il.add(TypeInsnNode(Opcodes.NEW, operationType.internalName))
        il.add(InsnNode(Opcodes.DUP))
        il.add(InstructionUtil.loadType(Type.INT_TYPE))
        il.add(LdcInsnNode("<switch>"))
        il.add(
            MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                operationType.internalName,
                "<init>",
                "(Ljava/lang/Class;Ljava/lang/String;)V",
                false,
            ),
        )
    }

    private fun createConstantOperation(
        il: InsnList,
        constantInsn: AbstractInsnNode,
        constantType: Type,
    ) {
        val operationType = Type.getType(Operation::class.java)
        il.add(TypeInsnNode(Opcodes.NEW, operationType.internalName))
        il.add(InsnNode(Opcodes.DUP))
        loadConstant(il, constantInsn)
        boxStackValue(il, constantType)
        il.add(InstructionUtil.loadType(constantType))
        il.add(
            MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                operationType.internalName,
                "<init>",
                "(Ljava/lang/Object;Ljava/lang/Class;)V",
                false,
            ),
        )
    }

    private fun createThrowOperation(il: InsnList) {
        val operationType = Type.getType(Operation::class.java)
        val throwableType = Type.getType(Throwable::class.java)
        il.add(TypeInsnNode(Opcodes.NEW, operationType.internalName))
        il.add(InsnNode(Opcodes.DUP))
        il.add(LdcInsnNode(throwableType))
        il.add(LdcInsnNode("<throw>"))
        il.add(
            MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                operationType.internalName,
                "<init>",
                "(Ljava/lang/Class;Ljava/lang/String;)V",
                false,
            ),
        )
    }

    private fun pushClassArray(
        il: InsnList,
        types: Array<Type>,
    ) {
        il.add(LdcInsnNode(types.size))
        il.add(TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Class"))
        for (index in types.indices) {
            il.add(InsnNode(Opcodes.DUP))
            il.add(LdcInsnNode(index))
            il.add(InstructionUtil.loadType(types[index]))
            il.add(InsnNode(Opcodes.AASTORE))
        }
    }

    private fun pushBootstrapArgumentArray(
        il: InsnList,
        bootstrapArgs: Array<Any>,
    ) {
        il.add(LdcInsnNode(bootstrapArgs.size))
        il.add(TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"))
        for (index in bootstrapArgs.indices) {
            il.add(InsnNode(Opcodes.DUP))
            il.add(LdcInsnNode(index))
            pushBootstrapArgument(il, bootstrapArgs[index])
            il.add(InsnNode(Opcodes.AASTORE))
        }
    }

    private fun pushBootstrapArgument(
        il: InsnList,
        value: Any,
    ) {
        il.add(LdcInsnNode(value))
        when (value) {
            is Int -> InstructionUtil.box(Type.INT_TYPE)?.let { il.add(it) }
            is Long -> InstructionUtil.box(Type.LONG_TYPE)?.let { il.add(it) }
            is Float -> InstructionUtil.box(Type.FLOAT_TYPE)?.let { il.add(it) }
            is Double -> InstructionUtil.box(Type.DOUBLE_TYPE)?.let { il.add(it) }
        }
    }

    private fun validateHandlerSignature(
        target: MethodNode,
        callInsn: MethodInsnNode,
    ): Int {
        val expectedStackParams = buildExpectedStackParams(callInsn)
        val operationType = Type.getType(Operation::class.java)
        val actualParams = Type.getArgumentTypes(asmMethod)
        val operationIndex = expectedStackParams.size
        if (actualParams.size <= operationIndex || actualParams[operationIndex] != operationType) {
            throw IllegalArgumentException(
                "@WrapOperation handler ${asmMethod.name} parameter #$operationIndex must be Operation, " +
                    "actual ${actualParams.toList()}",
            )
        }

        expectedStackParams.forEachIndexed { index, expected ->
            val actual = actualParams[index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalArgumentException(
                    "@WrapOperation handler ${asmMethod.name} parameter #$index mismatch: " +
                        "expected $expected, actual $actual",
                )
            }
        }

        val targetParamCount = validateTargetMethodParameters(target, actualParams, operationIndex + 1)
        val callReturnType = Type.getReturnType(callInsn.desc)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (!isReturnCompatible(callReturnType, handlerReturnType)) {
            throw IllegalArgumentException(
                "@WrapOperation handler ${asmMethod.name} return type mismatch: " +
                    "original $callReturnType, handler $handlerReturnType",
            )
        }
        return targetParamCount
    }

    private fun validateInvokeDynamicHandlerSignature(
        target: MethodNode,
        callInsn: InvokeDynamicInsnNode,
    ): Int {
        val expectedStackParams = Type.getArgumentTypes(callInsn.desc)
        val operationType = Type.getType(Operation::class.java)
        val actualParams = Type.getArgumentTypes(asmMethod)
        val operationIndex = expectedStackParams.size
        if (actualParams.size <= operationIndex || actualParams[operationIndex] != operationType) {
            throw IllegalArgumentException(
                "@WrapOperation handler ${asmMethod.name} parameter #$operationIndex must be Operation, " +
                    "actual ${actualParams.toList()}",
            )
        }

        expectedStackParams.forEachIndexed { index, expected ->
            val actual = actualParams[index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalArgumentException(
                    "@WrapOperation handler ${asmMethod.name} parameter #$index mismatch: " +
                        "expected $expected, actual $actual",
                )
            }
        }

        val targetParamCount = validateTargetMethodParameters(target, actualParams, operationIndex + 1)
        val callReturnType = Type.getReturnType(callInsn.desc)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (!isReturnCompatible(callReturnType, handlerReturnType)) {
            throw IllegalArgumentException(
                "@WrapOperation handler ${asmMethod.name} return type mismatch: " +
                    "original $callReturnType, handler $handlerReturnType",
            )
        }
        return targetParamCount
    }

    private fun validateInstanceofHandlerSignature(
        target: MethodNode,
        instanceofInsn: TypeInsnNode,
    ): Int {
        val expectedStackParams = arrayOf(Type.getType(Any::class.java))
        val operationType = Type.getType(Operation::class.java)
        val actualParams = Type.getArgumentTypes(asmMethod)
        val operationIndex = expectedStackParams.size
        if (actualParams.size <= operationIndex || actualParams[operationIndex] != operationType) {
            throw IllegalArgumentException(
                "@WrapOperation handler ${asmMethod.name} parameter #$operationIndex must be Operation, " +
                    "actual ${actualParams.toList()}",
            )
        }

        expectedStackParams.forEachIndexed { index, expected ->
            val actual = actualParams[index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalArgumentException(
                    "@WrapOperation handler ${asmMethod.name} parameter #$index mismatch: " +
                        "expected $expected, actual $actual",
                )
            }
        }

        val targetParamCount = validateTargetMethodParameters(target, actualParams, operationIndex + 1)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (!isReturnCompatible(Type.BOOLEAN_TYPE, handlerReturnType)) {
            val instanceofType = Type.getObjectType(instanceofInsn.desc)
            throw IllegalArgumentException(
                "@WrapOperation handler ${asmMethod.name} return type mismatch: " +
                    "original INSTANCEOF $instanceofType -> ${Type.BOOLEAN_TYPE}, handler $handlerReturnType",
            )
        }
        return targetParamCount
    }

    private fun validateCastHandlerSignature(
        target: MethodNode,
        castInsn: TypeInsnNode,
    ): Int {
        val expectedStackParams = arrayOf(Type.getType(Any::class.java))
        val operationType = Type.getType(Operation::class.java)
        val actualParams = Type.getArgumentTypes(asmMethod)
        val operationIndex = expectedStackParams.size
        if (actualParams.size <= operationIndex || actualParams[operationIndex] != operationType) {
            throw IllegalArgumentException(
                "@WrapOperation handler ${asmMethod.name} parameter #$operationIndex must be Operation, " +
                    "actual ${actualParams.toList()}",
            )
        }

        expectedStackParams.forEachIndexed { index, expected ->
            val actual = actualParams[index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalArgumentException(
                    "@WrapOperation handler ${asmMethod.name} parameter #$index mismatch: " +
                        "expected $expected, actual $actual",
                )
            }
        }

        val targetParamCount = validateTargetMethodParameters(target, actualParams, operationIndex + 1)
        val castType = Type.getObjectType(castInsn.desc)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (!isReturnCompatible(castType, handlerReturnType)) {
            throw IllegalArgumentException(
                "@WrapOperation handler ${asmMethod.name} return type mismatch: " +
                    "original $castType, handler $handlerReturnType",
            )
        }
        return targetParamCount
    }

    private fun validateConstructorHandlerSignature(
        target: MethodNode,
        constructorInsn: MethodInsnNode,
    ): Int {
        val expectedStackParams = Type.getArgumentTypes(constructorInsn.desc)
        val operationType = Type.getType(Operation::class.java)
        val actualParams = Type.getArgumentTypes(asmMethod)
        val operationIndex = expectedStackParams.size
        if (actualParams.size <= operationIndex || actualParams[operationIndex] != operationType) {
            throw IllegalArgumentException(
                "@WrapOperation handler ${asmMethod.name} parameter #$operationIndex must be Operation, " +
                    "actual ${actualParams.toList()}",
            )
        }

        expectedStackParams.forEachIndexed { index, expected ->
            val actual = actualParams[index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalArgumentException(
                    "@WrapOperation handler ${asmMethod.name} parameter #$index mismatch: " +
                        "expected $expected, actual $actual",
                )
            }
        }

        val targetParamCount = validateTargetMethodParameters(target, actualParams, operationIndex + 1)
        val constructedType = Type.getObjectType(constructorInsn.owner)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (!isReturnCompatible(constructedType, handlerReturnType)) {
            throw IllegalArgumentException(
                "@WrapOperation handler ${asmMethod.name} return type mismatch: " +
                    "original $constructedType, handler $handlerReturnType",
            )
        }
        return targetParamCount
    }

    private fun validateFieldAssignHandlerSignature(
        target: MethodNode,
        fieldInsn: FieldInsnNode,
    ): Int {
        val expectedStackParams = buildExpectedFieldAssignStackParams(fieldInsn)
        val operationType = Type.getType(Operation::class.java)
        val actualParams = Type.getArgumentTypes(asmMethod)
        val operationIndex = expectedStackParams.size
        if (actualParams.size <= operationIndex || actualParams[operationIndex] != operationType) {
            throw IllegalArgumentException(
                "@WrapOperation handler ${asmMethod.name} parameter #$operationIndex must be Operation, " +
                    "actual ${actualParams.toList()}",
            )
        }

        expectedStackParams.forEachIndexed { index, expected ->
            val actual = actualParams[index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalArgumentException(
                    "@WrapOperation handler ${asmMethod.name} parameter #$index mismatch: " +
                        "expected $expected, actual $actual",
                )
            }
        }

        val targetParamCount = validateTargetMethodParameters(target, actualParams, operationIndex + 1)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (!isReturnCompatible(Type.VOID_TYPE, handlerReturnType)) {
            throw IllegalArgumentException(
                "@WrapOperation handler ${asmMethod.name} return type mismatch: " +
                    "original ${Type.VOID_TYPE}, handler $handlerReturnType",
            )
        }
        return targetParamCount
    }

    private fun validateFieldHandlerSignature(
        target: MethodNode,
        fieldInsn: FieldInsnNode,
    ): Int {
        val expectedStackParams = buildExpectedFieldStackParams(fieldInsn)
        val operationType = Type.getType(Operation::class.java)
        val actualParams = Type.getArgumentTypes(asmMethod)
        val operationIndex = expectedStackParams.size
        if (actualParams.size <= operationIndex || actualParams[operationIndex] != operationType) {
            throw IllegalArgumentException(
                "@WrapOperation handler ${asmMethod.name} parameter #$operationIndex must be Operation, " +
                    "actual ${actualParams.toList()}",
            )
        }

        expectedStackParams.forEachIndexed { index, expected ->
            val actual = actualParams[index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalArgumentException(
                    "@WrapOperation handler ${asmMethod.name} parameter #$index mismatch: " +
                        "expected $expected, actual $actual",
                )
            }
        }

        val targetParamCount = validateTargetMethodParameters(target, actualParams, operationIndex + 1)
        val fieldType = Type.getType(fieldInsn.desc)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (!isReturnCompatible(fieldType, handlerReturnType)) {
            throw IllegalArgumentException(
                "@WrapOperation handler ${asmMethod.name} return type mismatch: " +
                    "original $fieldType, handler $handlerReturnType",
            )
        }
        return targetParamCount
    }

    private fun validateArrayAccessHandlerSignature(
        target: MethodNode,
        fieldInsn: FieldInsnNode,
        mode: ArrayAccessMode,
    ): Int {
        val expectedStackParams = buildExpectedArrayAccessStackParams(fieldInsn, mode)
        val operationType = Type.getType(Operation::class.java)
        val actualParams = Type.getArgumentTypes(asmMethod)
        val operationIndex = expectedStackParams.size
        if (actualParams.size <= operationIndex || actualParams[operationIndex] != operationType) {
            throw IllegalArgumentException(
                "@WrapOperation handler ${asmMethod.name} parameter #$operationIndex must be Operation, " +
                    "actual ${actualParams.toList()}",
            )
        }

        expectedStackParams.forEachIndexed { index, expected ->
            val actual = actualParams[index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalArgumentException(
                    "@WrapOperation handler ${asmMethod.name} parameter #$index mismatch: " +
                        "expected $expected, actual $actual",
                )
            }
        }

        val targetParamCount = validateTargetMethodParameters(target, actualParams, operationIndex + 1)
        val handlerReturnType = Type.getReturnType(asmMethod)
        when (mode) {
            ArrayAccessMode.GET -> {
                val elementType = Type.getType(fieldInsn.desc).elementType
                if (!isReturnCompatible(elementType, handlerReturnType)) {
                    throw IllegalArgumentException(
                        "@WrapOperation handler ${asmMethod.name} return type mismatch: " +
                            "original $elementType, handler $handlerReturnType",
                    )
                }
            }
            ArrayAccessMode.SET -> {
                if (!isReturnCompatible(Type.VOID_TYPE, handlerReturnType)) {
                    throw IllegalArgumentException(
                        "@WrapOperation handler ${asmMethod.name} return type mismatch: " +
                            "original ${Type.VOID_TYPE}, handler $handlerReturnType",
                    )
                }
            }
            ArrayAccessMode.LENGTH -> {
                if (!isReturnCompatible(Type.INT_TYPE, handlerReturnType)) {
                    throw IllegalArgumentException(
                        "@WrapOperation array length handler ${asmMethod.name} return type mismatch: " +
                            "original ${Type.INT_TYPE}, handler $handlerReturnType",
                    )
                }
            }
        }
        return targetParamCount
    }

    private fun validateLoadHandlerSignature(
        target: MethodNode,
        loadType: Type,
    ): Int {
        val expectedStackParams = arrayOf(loadType)
        val operationType = Type.getType(Operation::class.java)
        val actualParams = Type.getArgumentTypes(asmMethod)
        val operationIndex = expectedStackParams.size
        if (actualParams.size <= operationIndex || actualParams[operationIndex] != operationType) {
            throw IllegalArgumentException(
                "@WrapOperation handler ${asmMethod.name} parameter #$operationIndex must be Operation, " +
                    "actual ${actualParams.toList()}",
            )
        }

        expectedStackParams.forEachIndexed { index, expected ->
            val actual = actualParams[index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalArgumentException(
                    "@WrapOperation handler ${asmMethod.name} parameter #$index mismatch: " +
                        "expected $expected, actual $actual",
                )
            }
        }

        val targetParamCount = validateTargetMethodParameters(target, actualParams, operationIndex + 1)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (!isReturnCompatible(loadType, handlerReturnType)) {
            throw IllegalArgumentException(
                "@WrapOperation handler ${asmMethod.name} return type mismatch: " +
                    "original $loadType, handler $handlerReturnType",
            )
        }
        return targetParamCount
    }

    private fun validateConstantHandlerSignature(
        target: MethodNode,
        constantType: Type,
    ): Int {
        val expectedStackParams = arrayOf(constantType)
        val operationType = Type.getType(Operation::class.java)
        val actualParams = Type.getArgumentTypes(asmMethod)
        val operationIndex = expectedStackParams.size
        if (actualParams.size <= operationIndex || actualParams[operationIndex] != operationType) {
            throw IllegalArgumentException(
                "@WrapOperation handler ${asmMethod.name} parameter #$operationIndex must be Operation, " +
                    "actual ${actualParams.toList()}",
            )
        }

        expectedStackParams.forEachIndexed { index, expected ->
            val actual = actualParams[index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalArgumentException(
                    "@WrapOperation handler ${asmMethod.name} parameter #$index mismatch: " +
                        "expected $expected, actual $actual",
                )
            }
        }

        val targetParamCount = validateTargetMethodParameters(target, actualParams, operationIndex + 1)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (!isReturnCompatible(constantType, handlerReturnType)) {
            throw IllegalArgumentException(
                "@WrapOperation handler ${asmMethod.name} return type mismatch: " +
                    "original $constantType, handler $handlerReturnType",
            )
        }
        return targetParamCount
    }

    private fun validateJumpHandlerSignature(target: MethodNode): Int {
        val expectedStackParams = arrayOf(Type.BOOLEAN_TYPE)
        val operationType = Type.getType(Operation::class.java)
        val actualParams = Type.getArgumentTypes(asmMethod)
        val operationIndex = expectedStackParams.size
        if (actualParams.size <= operationIndex || actualParams[operationIndex] != operationType) {
            throw IllegalArgumentException(
                "@WrapOperation handler ${asmMethod.name} parameter #$operationIndex must be Operation, " +
                    "actual ${actualParams.toList()}",
            )
        }

        expectedStackParams.forEachIndexed { index, expected ->
            val actual = actualParams[index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalArgumentException(
                    "@WrapOperation handler ${asmMethod.name} parameter #$index mismatch: " +
                        "expected $expected, actual $actual",
                )
            }
        }

        val targetParamCount = validateTargetMethodParameters(target, actualParams, operationIndex + 1)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (!isReturnCompatible(Type.BOOLEAN_TYPE, handlerReturnType)) {
            throw IllegalArgumentException(
                "@WrapOperation handler ${asmMethod.name} return type mismatch: " +
                    "original ${Type.BOOLEAN_TYPE}, handler $handlerReturnType",
            )
        }
        return targetParamCount
    }

    private fun validateSwitchHandlerSignature(target: MethodNode): Int {
        val expectedStackParams = arrayOf(Type.INT_TYPE)
        val operationType = Type.getType(Operation::class.java)
        val actualParams = Type.getArgumentTypes(asmMethod)
        val operationIndex = expectedStackParams.size
        if (actualParams.size <= operationIndex || actualParams[operationIndex] != operationType) {
            throw IllegalArgumentException(
                "@WrapOperation handler ${asmMethod.name} parameter #$operationIndex must be Operation, " +
                    "actual ${actualParams.toList()}",
            )
        }

        expectedStackParams.forEachIndexed { index, expected ->
            val actual = actualParams[index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalArgumentException(
                    "@WrapOperation handler ${asmMethod.name} parameter #$index mismatch: " +
                        "expected $expected, actual $actual",
                )
            }
        }

        val targetParamCount = validateTargetMethodParameters(target, actualParams, operationIndex + 1)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (!isReturnCompatible(Type.INT_TYPE, handlerReturnType)) {
            throw IllegalArgumentException(
                "@WrapOperation handler ${asmMethod.name} return type mismatch: " +
                    "original ${Type.INT_TYPE}, handler $handlerReturnType",
            )
        }
        return targetParamCount
    }

    private fun validateThrowHandlerSignature(target: MethodNode): Int {
        val expectedStackParams = arrayOf(Type.getType(Throwable::class.java))
        val operationType = Type.getType(Operation::class.java)
        val actualParams = Type.getArgumentTypes(asmMethod)
        val operationIndex = expectedStackParams.size
        if (actualParams.size <= operationIndex || actualParams[operationIndex] != operationType) {
            throw IllegalArgumentException(
                "@WrapOperation handler ${asmMethod.name} parameter #$operationIndex must be Operation, " +
                    "actual ${actualParams.toList()}",
            )
        }

        expectedStackParams.forEachIndexed { index, expected ->
            val actual = actualParams[index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalArgumentException(
                    "@WrapOperation handler ${asmMethod.name} parameter #$index mismatch: " +
                        "expected $expected, actual $actual",
                )
            }
        }

        val targetParamCount = validateTargetMethodParameters(target, actualParams, operationIndex + 1)
        val throwableType = Type.getType(Throwable::class.java)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (!isReturnCompatible(throwableType, handlerReturnType)) {
            throw IllegalArgumentException(
                "@WrapOperation handler ${asmMethod.name} return type mismatch: " +
                    "original $throwableType, handler $handlerReturnType",
            )
        }
        return targetParamCount
    }

    private fun buildExpectedStackParams(callInsn: MethodInsnNode): Array<Type> {
        val callParams = Type.getArgumentTypes(callInsn.desc).toList()
        return if (callInsn.opcode == Opcodes.INVOKESTATIC) {
            callParams.toTypedArray()
        } else {
            (listOf(Type.getObjectType(callInsn.owner)) + callParams).toTypedArray()
        }
    }

    private fun buildExpectedFieldStackParams(fieldInsn: FieldInsnNode): Array<Type> =
        if (fieldInsn.opcode == Opcodes.GETSTATIC) {
            emptyArray()
        } else {
            arrayOf(Type.getObjectType(fieldInsn.owner))
        }

    private fun buildExpectedFieldAssignStackParams(fieldInsn: FieldInsnNode): Array<Type> {
        val fieldType = Type.getType(fieldInsn.desc)
        return if (fieldInsn.opcode == Opcodes.PUTSTATIC) {
            arrayOf(fieldType)
        } else {
            arrayOf(Type.getObjectType(fieldInsn.owner), fieldType)
        }
    }

    private fun buildExpectedArrayAccessStackParams(
        fieldInsn: FieldInsnNode,
        mode: ArrayAccessMode,
    ): Array<Type> {
        val arrayType = Type.getType(fieldInsn.desc)
        return when (mode) {
            ArrayAccessMode.GET -> arrayOf(arrayType, Type.INT_TYPE)
            ArrayAccessMode.SET -> arrayOf(arrayType, Type.INT_TYPE, arrayType.elementType)
            ArrayAccessMode.LENGTH -> arrayOf(arrayType)
        }
    }

    private fun parseLocalVariableIndex(pointName: String): Int? {
        val values =
            at.args.mapNotNull { arg ->
                val trimmed = arg.trim()
                when {
                    trimmed.startsWith("index=") -> trimmed.substringAfter("index=")
                    trimmed.startsWith("var=") -> trimmed.substringAfter("var=")
                    else -> null
                }
            }

        if (values.isEmpty()) {
            return null
        }
        require(values.size == 1) {
            "@WrapOperation $pointName supports only one local variable slot filter in At.args"
        }

        val index =
            values.single().toIntOrNull()
                ?: throw IllegalArgumentException(
                    "@WrapOperation $pointName local variable slot filter must be an integer: ${values.single()}",
                )
        require(index >= 0) {
            "@WrapOperation $pointName local variable slot filter must be non-negative: $index"
        }
        return index
    }

    private fun requireHandlerLocalArgumentType(pointName: String): Type {
        val handlerParams = Type.getArgumentTypes(asmMethod)
        if (handlerParams.isEmpty()) {
            throw IllegalArgumentException(
                "@WrapOperation handler ${asmMethod.name} must take at least one argument for the local variable $pointName value",
            )
        }
        return handlerParams[0]
    }

    private fun isLoadHandlerCompatible(
        target: MethodNode,
        loadType: Type,
    ): Boolean = runCatching { validateLoadHandlerSignature(target, loadType) }.isSuccess

    private fun resolveIndexedLoadType(
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

    private fun isLoadCompatibleWithHandler(
        opcode: Int,
        handlerType: Type,
    ): Boolean =
        when (opcode) {
            Opcodes.ILOAD -> handlerType.sort in INT_VARIABLE_TYPE_SORTS
            Opcodes.LLOAD -> handlerType == Type.LONG_TYPE
            Opcodes.FLOAD -> handlerType == Type.FLOAT_TYPE
            Opcodes.DLOAD -> handlerType == Type.DOUBLE_TYPE
            Opcodes.ALOAD -> handlerType.sort == Type.OBJECT || handlerType.sort == Type.ARRAY
            else -> false
        }

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
                            "@WrapOperation array access target must be an array field: " +
                                "${cursor.owner}.${cursor.name}:${cursor.desc}",
                        )
                    }
                    return cursor
                }
                return null
            }
            if (cursor is MethodInsnNode || cursor.opcode in ARRAY_READ_OPS || cursor.opcode in ARRAY_WRITE_OPS) {
                return null
            }
            cursor = cursor.previous
        }
        return null
    }

    private fun findConstructorAllocation(constructorInsn: MethodInsnNode): ConstructorAllocation {
        var cursor = constructorInsn.previous
        while (cursor != null) {
            if (cursor is TypeInsnNode && cursor.opcode == Opcodes.NEW && cursor.desc == constructorInsn.owner) {
                val dupInsn = nextRealInstruction(cursor)
                if (dupInsn?.opcode != Opcodes.DUP) {
                    throw IllegalArgumentException(
                        "@WrapOperation constructor calls require NEW followed by DUP for ${constructorInsn.owner}",
                    )
                }
                return ConstructorAllocation(cursor, dupInsn)
            }
            cursor = cursor.previous
        }

        throw IllegalArgumentException(
            "@WrapOperation cannot find NEW allocation for constructor ${constructorInsn.owner}${constructorInsn.desc}",
        )
    }

    private fun findConstructorInvocation(newInsn: TypeInsnNode): MethodInsnNode {
        var nestedSameOwnerNewCount = 0
        var cursor = newInsn.next
        while (cursor != null) {
            if (cursor is TypeInsnNode && cursor.opcode == Opcodes.NEW && cursor.desc == newInsn.desc) {
                nestedSameOwnerNewCount++
            } else if (
                cursor is MethodInsnNode &&
                cursor.opcode == Opcodes.INVOKESPECIAL &&
                cursor.owner == newInsn.desc &&
                cursor.name == "<init>"
            ) {
                if (nestedSameOwnerNewCount == 0) {
                    return cursor
                }
                nestedSameOwnerNewCount--
            }
            cursor = cursor.next
        }

        throw IllegalArgumentException("@WrapOperation cannot find constructor call for NEW ${newInsn.desc}")
    }

    private fun nextRealInstruction(insn: AbstractInsnNode): AbstractInsnNode? {
        var cursor = insn.next
        while (cursor != null && cursor.opcode < 0) {
            cursor = cursor.next
        }
        return cursor
    }

    private fun previousRealInstruction(insn: AbstractInsnNode): AbstractInsnNode? {
        var cursor = insn.previous
        while (cursor != null && cursor.opcode < 0) {
            cursor = cursor.previous
        }
        return cursor
    }

    private fun validateTargetMethodParameters(
        target: MethodNode,
        actualParams: Array<Type>,
        stackParamCount: Int,
    ): Int {
        val targetParamTypes = Type.getArgumentTypes(target.desc)
        val requestedTargetParamCount = actualParams.size - stackParamCount
        if (requestedTargetParamCount > targetParamTypes.size) {
            throw IllegalArgumentException(
                "@WrapOperation handler ${asmMethod.name} requests $requestedTargetParamCount target parameter(s), " +
                    "but target method ${target.name}${target.desc} has only ${targetParamTypes.size}",
            )
        }

        for (index in 0 until requestedTargetParamCount) {
            val expected = targetParamTypes[index]
            val actual = actualParams[stackParamCount + index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalArgumentException(
                    "@WrapOperation handler ${asmMethod.name} target parameter #$index mismatch: " +
                        "expected $expected, actual $actual",
                )
            }
        }
        return requestedTargetParamCount
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

    private fun isReturnCompatible(
        original: Type,
        handler: Type,
    ): Boolean {
        if (original == Type.VOID_TYPE) {
            return handler == Type.VOID_TYPE
        }
        if (handler == Type.VOID_TYPE) {
            return false
        }
        if (original == handler) {
            return true
        }
        if (!original.isReferenceType() || !handler.isReferenceType()) {
            return false
        }
        if (handler.sort == Type.OBJECT &&
            (handler.internalName == "java/lang/Object" || handler.internalName == "kotlin/Any")
        ) {
            return true
        }
        return runCatching {
            val originalClass = loadReferenceClass(original)
            originalClass.isAssignableFrom(loadReferenceClass(handler))
        }.getOrDefault(false)
    }

    private fun Type.isReferenceType(): Boolean = sort == Type.OBJECT || sort == Type.ARRAY

    private fun resolveHandlerConstantType(
        insn: AbstractInsnNode,
        constantType: Type,
    ): Type {
        if (insn.opcode == Opcodes.ACONST_NULL) {
            val firstParamType = Type.getArgumentTypes(asmMethod).firstOrNull()
            if (firstParamType?.isReferenceType() == true) {
                return firstParamType
            }
        }
        return constantType
    }

    private fun resolveConstantReplacementType(
        insn: AbstractInsnNode,
        constantType: Type,
    ): Type {
        if (insn.opcode == Opcodes.ACONST_NULL) {
            val firstParamType = Type.getArgumentTypes(asmMethod).firstOrNull()
            if (firstParamType?.isReferenceType() == true) {
                return firstParamType
            }
        }
        if (constantType.sort == Type.OBJECT && constantType.internalName == "java/lang/Object") {
            return Type.getReturnType(asmMethod)
        }
        return constantType
    }

    private fun addConstantCastIfNeeded(
        il: InsnList,
        replacementType: Type,
    ) {
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (replacementType != handlerReturnType && replacementType.isReferenceType()) {
            il.add(TypeInsnNode(Opcodes.CHECKCAST, replacementType.internalName))
        }
    }

    private fun loadConstant(
        il: InsnList,
        insn: AbstractInsnNode,
    ) {
        when (insn) {
            is LdcInsnNode -> il.add(LdcInsnNode(insn.cst))
            is IntInsnNode -> il.add(IntInsnNode(insn.opcode, insn.operand))
            else -> il.add(insn.clone(null))
        }
    }

    private fun boxStackValue(
        il: InsnList,
        type: Type,
    ) {
        InstructionUtil.box(type)?.let { il.add(it) }
    }

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
            "Only INVOKE slice boundaries are supported for @WrapOperation: ${at.value}"
        }

        val (boundaryOwner, boundaryName, boundaryDesc) = parseTargetMethod(at.target)
        if (boundaryName == null || boundaryDesc == null) {
            throw IllegalArgumentException(
                "Invalid WrapOperation slice boundary method signature: ${at.target} " +
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

    private fun parseJumpOpcodeTarget(target: String): Int? {
        if (target.isEmpty()) {
            return null
        }

        val normalized = target.trim().uppercase()
        normalized.toIntOrNull()?.let { opcode ->
            require(opcode in JUMP_OPS) {
                "@WrapOperation JUMP target opcode must be a JVM jump opcode: $target"
            }
            return opcode
        }

        return JUMP_OPCODE_NAMES[normalized]
            ?: throw IllegalArgumentException("@WrapOperation JUMP target must be a jump opcode name or number: $target")
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

    private data class ConstructorAllocation(
        val newInsn: TypeInsnNode,
        val dupInsn: AbstractInsnNode,
    )

    private data class LocalSlotType(
        val index: Int,
        val type: Type,
    )

    private enum class ArrayAccessMode {
        GET,
        SET,
        LENGTH,
    }

    private companion object {
        private val FIELD_READ_OPS = setOf(Opcodes.GETFIELD, Opcodes.GETSTATIC)
        private val FIELD_WRITE_OPS = setOf(Opcodes.PUTFIELD, Opcodes.PUTSTATIC)
        private val LOAD_OPS = setOf(Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD, Opcodes.ALOAD)
        private val STORE_OPS = setOf(Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE, Opcodes.ASTORE)
        private val SLOT_REFERENCE_OPS = setOf(Opcodes.ALOAD, Opcodes.ASTORE)
        private val INT_VARIABLE_TYPE_SORTS = setOf(Type.BOOLEAN, Type.BYTE, Type.SHORT, Type.INT, Type.CHAR)
        private val ARRAY_READ_OPS =
            setOf(
                Opcodes.IALOAD,
                Opcodes.LALOAD,
                Opcodes.FALOAD,
                Opcodes.DALOAD,
                Opcodes.AALOAD,
                Opcodes.BALOAD,
                Opcodes.CALOAD,
                Opcodes.SALOAD,
            )
        private val ARRAY_WRITE_OPS =
            setOf(
                Opcodes.IASTORE,
                Opcodes.LASTORE,
                Opcodes.FASTORE,
                Opcodes.DASTORE,
                Opcodes.AASTORE,
                Opcodes.BASTORE,
                Opcodes.CASTORE,
                Opcodes.SASTORE,
            )
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
        private val JUMP_OPS = JUMP_OPCODE_NAMES.values.toSet()
        private val CONDITIONAL_JUMP_OPS = JUMP_OPS - setOf(Opcodes.GOTO, Opcodes.JSR)
    }
}
