/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.injector.impl

import kim.der.asm.api.annotation.At
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.Slice
import kim.der.asm.data.AsmInfo
import kim.der.asm.injector.AbstractAsmInjector
import kim.der.asm.injector.util.SliceBoundaryResolver
import kim.der.asm.utils.transformer.BytecodeUtil
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
import org.objectweb.asm.tree.LookupSwitchInsnNode
import org.objectweb.asm.tree.LocalVariableNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TableSwitchInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * WrapWithCondition 注入器。
 *
 * 该注入器会匹配目标方法内的普通方法调用、任意返回值的 `invokedynamic` 调用、调用返回值、字段读取、字段写入、
 * 简单数组元素读取、数组元素写入、数组长度读取、对象构造结果、局部变量读取或写入、`CHECKCAST` 类型转换、`INSTANCEOF` 类型判断、常量加载、条件跳转、switch selector 或抛异常点，
 * 并在原指令前后插入 boolean handler。
 * handler 返回 `true` 时恢复原调用的 receiver 与参数、字段读取值、字段写入值、数组读取值、
 * 数组写入栈参数、数组长度值、调用返回值、构造完成后的引用、局部变量读取值或待写入值、类型转换后的引用、类型判断结果、常量值、原条件跳转分支结果、switch selector 或原异常对象并继续执行或保留原语义。
 * handler 返回 `false` 时，调用前、写入、跳转或抛出类控制点会跳过原操作；非 `void` 普通方法调用与非 `void` `invokedynamic`
 * 调用、调用返回值、字段读取、数组元素读取、数组长度读取、局部变量读取、类型判断、常量加载以及 switch selector 会压入对应类型的默认值；
 * 对象构造结果与类型转换会压入引用默认值 `null`。
 * [InjectionPoint.INVOKE] 未指定调用目标时，会按 handler 参数和 boolean 返回类型筛选兼容的普通调用或
 * `invokedynamic` 调用；构造器和 handler 不兼容的调用不会计入 [WrapWithCondition.ordinal] 或命中数。
 * [InjectionPoint.INVOKE_ASSIGN] 匹配非 `void` 普通方法调用或 `invokedynamic` 调用完成后的返回值；
 * 省略 [At.target] 时按 handler 首参与 boolean 返回类型筛选兼容调用返回值，不兼容或 `void` 调用不计入 [WrapWithCondition.ordinal] 或命中数。
 * [InjectionPoint.FIELD] 未指定字段目标时，会按 handler 首参和 boolean 返回类型筛选兼容字段读取；
 * handler 只接收读取出的字段值，不接收 `GETFIELD` receiver，不兼容候选不会计入 [WrapWithCondition.ordinal] 或命中数。
 * [InjectionPoint.FIELD] 也可通过 [At.args] 中的 `array=get` 或 `array=length` 匹配目标数组字段后的数组元素读取或数组长度读取；
 * handler 分别只接收已读取的元素值或 `Int` 长度，不接收数组引用或索引。
 * [InjectionPoint.FIELD_ASSIGN] 未指定字段目标时，会按 handler 字段 owner 参数、待写入值和 boolean 返回类型筛选
 * 兼容的字段写入，且不兼容候选不会计入 [WrapWithCondition.ordinal] 或命中数。
 * [InjectionPoint.LOAD] 不使用 [At.target]，可通过 [At.args] 中的 `index=N`、`var=N` 或 `name=localName`
 * 过滤局部变量读取，handler 首参接收本次 `xLOAD` 读取出的表达式值；返回 `false` 时仅替换本次读取结果，不回写槽位。
 * [InjectionPoint.STORE] 不使用 [At.target]，可通过 [At.args] 中的 `index=N`、`var=N` 或 `name=localName`
 * 过滤局部变量写入，handler 首参接收本次 `xSTORE` 即将消费的待写入值。
 * [InjectionPoint.NEW] 使用 [At.target] 类型 internal name 或 binary name 过滤，省略时按 handler 首参筛选兼容构造点；
 * handler 首参接收构造完成后的引用，返回 `false` 时把本次构造表达式替换为 `null`。
 * [InjectionPoint.CAST] 使用 [At.target] 类型 internal name 或 binary name 过滤，省略时按 handler 首参筛选兼容转换点；
 * handler 首参接收 `CHECKCAST` 完成后的引用，返回 `false` 时把本次转换结果替换为 `null`。
 * [InjectionPoint.INSTANCEOF] 使用 [At.target] 类型 internal name 或 binary name 过滤，省略时匹配切片内全部兼容类型判断；
 * handler 首参接收原始 boolean 判断结果，返回 `false` 时把本次判断替换为 `false`。
 * [InjectionPoint.CONSTANT] 使用 [At.target] 常量文本过滤，省略时按 handler 首参和 boolean 返回类型筛选兼容常量。
 * [InjectionPoint.JUMP] 未指定跳转目标时会匹配切片内全部条件跳转，`GOTO` 与 `JSR` 不支持条件包裹。
 * [InjectionPoint.SWITCH] 不支持 [At.target]，handler 首参接收 `tableswitch` / `lookupswitch` 消费前的 `Int` selector。
 * [InjectionPoint.THROW] 未指定异常类型目标时会匹配切片内全部 `ATHROW`；指定目标时只匹配前一条真实指令为同类型 `<init>` 的直接构造异常。
 * [InjectionPoint.INVOKE] 命中的构造器 `<init>` 虽然返回 `void`，但会消费未初始化对象，仍明确拒绝条件包裹；
 * 如需按构造完成后的对象决定是否保留表达式，应使用 [InjectionPoint.NEW]。
 *
 * @param at 调用点定位；当前支持 [InjectionPoint.INVOKE]、[InjectionPoint.INVOKE_ASSIGN]、[InjectionPoint.FIELD]、[InjectionPoint.FIELD_ASSIGN]、
 * [InjectionPoint.LOAD]、[InjectionPoint.STORE]、[InjectionPoint.NEW]、[InjectionPoint.CAST]、[InjectionPoint.INSTANCEOF]、[InjectionPoint.CONSTANT]、[InjectionPoint.JUMP]、[InjectionPoint.SWITCH] 与 [InjectionPoint.THROW]
 * @param ordinal 匹配调用点序号；负数表示处理全部匹配调用点
 * @param slice 切片范围；当前 [InjectionPoint.INVOKE]、[InjectionPoint.INVOKE_ASSIGN]、[InjectionPoint.FIELD]、[InjectionPoint.FIELD_ASSIGN]、
 * [InjectionPoint.LOAD]、[InjectionPoint.STORE]、[InjectionPoint.NEW]、[InjectionPoint.CAST]、[InjectionPoint.INSTANCEOF]、[InjectionPoint.CONSTANT]、[InjectionPoint.JUMP]、[InjectionPoint.SWITCH] 与 [InjectionPoint.THROW]
 * 条件包裹使用
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
     * 在匹配的方法调用、`invokedynamic` 调用、调用返回值、字段读取、字段写入、数组元素读取、数组元素写入、数组长度读取、
     * 对象构造结果、局部变量读取或写入、`CHECKCAST` 类型转换、`INSTANCEOF` 类型判断、常量加载、条件跳转或抛异常点插入条件包裹逻辑。
     *
     * @param target 目标方法
     * @return 至少包裹一个调用点、动态调用点、调用返回值、字段读取点、字段写入点、数组元素读取点、数组元素写入点、数组长度读取点、
     * 对象构造点、局部变量读取或写入点、类型判断点、常量加载点、条件跳转点、switch 点或抛异常点时返回 `true`
     * @throws IllegalArgumentException 定位点、目标调用、字段目标或 handler 签名不合法时抛出
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun inject(target: MethodNode): Boolean = injectCount(target) > 0

    /**
     * 在匹配的方法调用、`invokedynamic` 调用、调用返回值、字段读取、字段写入、数组元素读取、数组元素写入、数组长度读取、
     * 对象构造结果、局部变量读取或写入、`CHECKCAST` 类型转换、`INSTANCEOF` 类型判断、常量加载、条件跳转、switch selector 或抛异常点插入条件包裹逻辑，并返回实际包裹数量。
     *
     * @param target 目标方法
     * @return 实际包裹的调用点、动态调用点、调用返回值、字段读取点、字段写入点、数组元素读取点、数组元素写入点、数组长度读取点、
     * 对象构造点、局部变量读取或写入点、类型判断点、常量加载点、条件跳转点、switch 点或抛异常点数量
     * @throws IllegalArgumentException 定位点、目标调用、字段目标或 handler 签名不合法时抛出
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun injectCount(target: MethodNode): Int {
        return when (at.value) {
            InjectionPoint.INVOKE -> injectMethodCall(target)
            InjectionPoint.INVOKE_ASSIGN -> injectMethodCallReturn(target)
            InjectionPoint.FIELD ->
                when (arrayAccessMode()) {
                    ArrayAccessMode.NONE -> injectFieldRead(target)
                    ArrayAccessMode.GET -> injectArrayRead(target)
                    ArrayAccessMode.LENGTH -> injectArrayLength(target)
                    ArrayAccessMode.SET ->
                        throw IllegalArgumentException("@WrapWithCondition array=set requires FIELD_ASSIGN injection point")
                }
            InjectionPoint.FIELD_ASSIGN ->
                when (arrayAccessMode()) {
                    ArrayAccessMode.NONE -> injectFieldAssign(target)
                    ArrayAccessMode.SET -> injectArrayAssign(target)
                    ArrayAccessMode.GET ->
                        throw IllegalArgumentException("@WrapWithCondition array=get requires FIELD injection point")
                    ArrayAccessMode.LENGTH ->
                        throw IllegalArgumentException("@WrapWithCondition array=length requires FIELD injection point")
                }
            InjectionPoint.LOAD -> injectLoad(target)
            InjectionPoint.STORE -> injectStore(target)
            InjectionPoint.NEW -> injectNewObject(target)
            InjectionPoint.CAST -> injectCast(target)
            InjectionPoint.INSTANCEOF -> injectInstanceof(target)
            InjectionPoint.CONSTANT -> injectConstant(target)
            InjectionPoint.JUMP -> injectJump(target)
            InjectionPoint.SWITCH -> injectSwitch(target)
            InjectionPoint.THROW -> injectThrow(target)
            else -> throw IllegalArgumentException(
                "@WrapWithCondition supports only INVOKE, INVOKE_ASSIGN, FIELD, FIELD_ASSIGN, LOAD, STORE, NEW, CAST, INSTANCEOF, CONSTANT, JUMP, SWITCH and THROW injection points",
            )
        }
    }

    /**
     * 解析数组访问条件包裹模式。
     *
     * 未声明 `array=` 参数时按普通字段读写处理；声明后按读、写或长度访问分派到对应数组逻辑。
     *
     * @return 当前声明的数组访问模式
     * @throws IllegalArgumentException 声明了不支持的数组访问模式时抛出
     */
    private fun arrayAccessMode(): ArrayAccessMode {
        val arrayArg = at.args.firstOrNull { it.trim().startsWith("array=") } ?: return ArrayAccessMode.NONE
        return when (arrayArg.substringAfter('=').trim().lowercase()) {
            "get" -> ArrayAccessMode.GET
            "length" -> ArrayAccessMode.LENGTH
            "set" -> ArrayAccessMode.SET
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
     * 在匹配的非 `void` 普通方法调用或 `invokedynamic` 调用后插入条件 handler。
     *
     * 该模式保留原调用执行，只在调用已经把返回值压入栈顶后决定是否继续采纳该返回表达式。
     * handler 返回 `false` 时把本次返回值替换为对应类型默认值，返回 `true` 时恢复原返回值。
     *
     * @param target 目标方法
     * @return 实际插入条件包裹逻辑的调用返回值数量
     * @throws IllegalArgumentException 目标签名不完整、匹配到 `void` 调用或 handler 签名不兼容时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-05
     */
    private fun injectMethodCallReturn(target: MethodNode): Int {
        val inferTarget = at.target.isEmpty()
        val (targetOwner, targetName, targetDesc) = parseTargetMethod(at.target)
        if (!inferTarget && (targetName == null || targetDesc == null)) {
            throw IllegalArgumentException("@WrapWithCondition INVOKE_ASSIGN requires at.target method signature")
        }

        var injectionCount = 0
        var matchedOrdinal = 0
        val insns = target.instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)
        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            val callDesc =
                when (insn) {
                    is MethodInsnNode ->
                        if (inferTarget || (targetName != null && matchesTargetMethod(insn, targetOwner, targetName, targetDesc))) {
                            insn.desc
                        } else {
                            null
                        }
                    is InvokeDynamicInsnNode ->
                        if (inferTarget || (targetName != null && matchesTargetInvokeDynamic(insn, targetOwner, targetName, targetDesc))) {
                            insn.desc
                        } else {
                            null
                        }
                    else -> null
                } ?: continue

            val callReturnType = Type.getReturnType(callDesc)
            if (callReturnType == Type.VOID_TYPE) {
                if (inferTarget) {
                    continue
                }
                throw IllegalArgumentException(
                    "@WrapWithCondition INVOKE_ASSIGN cannot conditionally keep void call " +
                        callDisplayName(insn, callDesc),
                )
            }
            if (inferTarget && !isCallReturnHandlerCompatible(target, callReturnType)) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val targetParamCount = validateCallReturnHandlerSignature(target, callReturnType)
            val il = buildMethodCallReturnConditionWrapper(target, callReturnType, targetParamCount)
            target.instructions.insert(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 判断 handler 是否兼容候选调用返回值。
     *
     * 该方法用于目标推断模式，签名不兼容候选不会计入 ordinal 或命中数。
     *
     * @param target 目标方法
     * @param callReturnType 候选调用返回值类型
     * @return handler 可条件包裹该调用返回值时返回 `true`
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-05
     */
    private fun isCallReturnHandlerCompatible(
        target: MethodNode,
        callReturnType: Type,
    ): Boolean = runCatching { validateCallReturnHandlerSignature(target, callReturnType) }.isSuccess

    /**
     * 在匹配的字段读取后插入条件 handler。
     *
     * 显式声明字段目标时按 owner、名称与描述符匹配；未声明目标时按 handler 签名筛选兼容字段读取。
     * handler 返回 `false` 时把本次读取结果替换为字段类型默认值，返回 `true` 时恢复原字段值。
     *
     * @param target 目标方法
     * @return 实际插入条件包裹逻辑的字段读取数量
     * @throws IllegalArgumentException 字段目标缺少名称或 handler 签名不兼容时抛出
     */
    private fun injectFieldRead(target: MethodNode): Int {
        val inferTarget = at.target.isEmpty()
        val fieldTarget = parseFieldTarget(at.target)
        if (!inferTarget && fieldTarget.name == null) {
            throw IllegalArgumentException("@WrapWithCondition FIELD requires at.target field signature")
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

            val fieldType = Type.getType(insn.desc)
            val targetParamCount = validateFieldReadHandlerSignature(target, fieldType)
            val il = buildFieldReadConditionWrapper(target, fieldType, targetParamCount)
            target.instructions.insert(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 判断 handler 是否兼容候选字段读取。
     *
     * 该方法用于目标推断模式，签名不兼容候选不会计入 ordinal 或命中数。
     *
     * @param target 目标方法
     * @param insn 候选字段读取指令
     * @return handler 可条件包裹该字段读取时返回 `true`
     */
    private fun isFieldReadHandlerCompatible(
        target: MethodNode,
        insn: FieldInsnNode,
    ): Boolean = runCatching { validateFieldReadHandlerSignature(target, Type.getType(insn.desc)) }.isSuccess

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
     * 在匹配的数组元素读取后插入条件 handler。
     *
     * 该入口通过 `array=get` 启用，并要求 [At.target] 指向数组字段。
     * handler 首参只接收本次元素读取结果，不接收数组引用和索引；返回 `false` 时压入元素类型默认值。
     *
     * @param target 目标方法
     * @return 实际插入条件包裹逻辑的数组元素读取数量
     * @throws IllegalArgumentException 数组字段目标缺失、目标不是数组字段或 handler 签名不兼容时抛出
     */
    private fun injectArrayRead(target: MethodNode): Int {
        val fieldTarget = parseFieldTarget(at.target)
        if (fieldTarget.name == null) {
            throw IllegalArgumentException("@WrapWithCondition array read requires at.target array field signature")
        }
        if (fieldTarget.desc != null && Type.getType(fieldTarget.desc).sort != Type.ARRAY) {
            throw IllegalArgumentException("@WrapWithCondition array read target must be an array field: ${at.target}")
        }

        var injectionCount = 0
        var matchedOrdinal = 0
        val insns = target.instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)
        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (insn.opcode !in ARRAY_READ_OPS) {
                continue
            }

            val fieldInsn = findArrayFieldProducer(insn, fieldTarget) ?: continue
            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val elementType = Type.getType(fieldInsn.desc).elementType
            val targetParamCount = validateArrayReadHandlerSignature(target, elementType)
            val il = buildArrayReadConditionWrapper(target, elementType, targetParamCount)
            target.instructions.insert(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 在匹配的数组长度读取后插入条件 handler。
     *
     * 该入口通过 `array=length` 启用，并要求 [At.target] 指向数组字段。
     * handler 首参接收 `ARRAYLENGTH` 产生的 `Int` 长度；返回 `false` 时压入 `0`。
     *
     * @param target 目标方法
     * @return 实际插入条件包裹逻辑的数组长度读取数量
     * @throws IllegalArgumentException 数组字段目标缺失、目标不是数组字段或 handler 签名不兼容时抛出
     */
    private fun injectArrayLength(target: MethodNode): Int {
        val fieldTarget = parseFieldTarget(at.target)
        if (fieldTarget.name == null) {
            throw IllegalArgumentException("@WrapWithCondition array length requires at.target array field signature")
        }
        if (fieldTarget.desc != null && Type.getType(fieldTarget.desc).sort != Type.ARRAY) {
            throw IllegalArgumentException("@WrapWithCondition array length target must be an array field: ${at.target}")
        }

        var injectionCount = 0
        var matchedOrdinal = 0
        val insns = target.instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)
        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (insn.opcode != Opcodes.ARRAYLENGTH) {
                continue
            }

            findArrayFieldProducer(insn, fieldTarget) ?: continue
            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val targetParamCount = validateArrayReadHandlerSignature(target, Type.INT_TYPE)
            val il = buildArrayLengthConditionWrapper(target, targetParamCount)
            target.instructions.insert(insn, il)
            injectionCount++
        }

        return injectionCount
    }

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
     * 在匹配的局部变量读取后插入条件 handler。
     *
     * [InjectionPoint.LOAD] 不使用 [At.target]，可通过 [At.args] 中的 `index=N`、`var=N` 或 `name=localName`
     * 限定候选 `xLOAD`。handler 返回 `false` 时把本次读取结果替换为类型默认值，返回 `true` 时恢复原读取值。
     *
     * @param target 目标方法
     * @return 实际插入条件包裹逻辑的局部变量读取数量
     * @throws IllegalArgumentException 声明了 `at.target`、槽位过滤参数非法或 handler 签名不兼容时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-01
     */
    private fun injectLoad(target: MethodNode): Int {
        require(at.target.isEmpty()) {
            "@WrapWithCondition LOAD uses At.args index=N, var=N or name=localName for local variable filtering, not At.target"
        }
        val localVariableFilter = parseLocalVariableFilter("LOAD")
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
            if (!matchesLocalVariableFilter(target, insn, localVariableFilter)) {
                continue
            }
            if (!isLoadCompatibleWithHandler(insn.opcode, handlerLoadType)) {
                continue
            }

            val resolvedLoadType = resolveIndexedLocalValueType(target, insn, handlerLoadType)
            if (localVariableFilter.index == null && handlerLoadType.isReferenceType() && resolvedLoadType == null) {
                continue
            }
            val loadType = resolvedLoadType ?: handlerLoadType
            if (localVariableFilter.index == null && !isLoadHandlerCompatible(target, loadType)) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val targetParamCount = validateLoadHandlerSignature(target, loadType)
            val il = buildLoadConditionWrapper(target, loadType, targetParamCount)
            target.instructions.insert(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 判断 handler 是否兼容候选局部变量读取。
     *
     * 该方法用于目标推断模式，签名不兼容候选不会计入 ordinal 或命中数。
     *
     * @param target 目标方法
     * @param loadType 候选读取值类型
     * @return handler 可条件包裹该局部变量读取时返回 `true`
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-01
     */
    private fun isLoadHandlerCompatible(
        target: MethodNode,
        loadType: Type,
    ): Boolean = runCatching { validateLoadHandlerSignature(target, loadType) }.isSuccess

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

            val resolvedStoreType = resolveIndexedLocalValueType(target, insn, handlerStoreType)
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
     * 在匹配的常量加载后插入条件 handler。
     *
     * 显式声明 [At.target] 时按 [BytecodeUtil.matchesConstantText] 过滤常量；省略目标时按 handler 首参与 boolean
     * 返回类型筛选兼容常量。handler 返回 `false` 时把本次常量表达式替换为同类型默认值。
     *
     * @param target 目标方法
     * @return 实际插入条件包裹逻辑的常量加载数量
     * @throws IllegalArgumentException handler 签名不兼容时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-01
     */
    private fun injectConstant(target: MethodNode): Int {
        val inferTarget = at.target.isEmpty()
        val requestedConstant = at.target.takeIf { it.isNotEmpty() }
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
            if (requestedConstant != null && !BytecodeUtil.matchesConstantText(insn, requestedConstant)) {
                continue
            }

            val constantType = resolveConstantConditionType(insn, requestedConstant) ?: continue
            if (inferTarget && !isConstantHandlerCompatible(target, constantType)) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val targetParamCount = validateConstantHandlerSignature(target, constantType)
            val il = buildConstantConditionWrapper(target, constantType, targetParamCount)
            target.instructions.insert(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 判断 handler 是否兼容候选常量加载。
     *
     * 该方法用于目标推断模式，签名不兼容候选不会计入 ordinal 或命中数。
     *
     * @param target 目标方法
     * @param constantType 候选常量值类型
     * @return handler 可条件包裹该常量加载时返回 `true`
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-01
     */
    private fun isConstantHandlerCompatible(
        target: MethodNode,
        constantType: Type,
    ): Boolean = runCatching { validateConstantHandlerSignature(target, constantType) }.isSuccess

    /**
     * 在匹配的对象构造完成后插入条件 handler。
     *
     * 显式声明类型目标时按 internal name 或 binary name 匹配；省略目标时按 handler 首参筛选兼容构造点。
     * 注入点位于配对 `<init>` 之后，此时栈顶已经是初始化完成的对象，handler 返回 `false` 时用 `null` 替换本次构造表达式。
     *
     * @param target 目标方法
     * @return 实际插入条件包裹逻辑的对象构造数量
     * @throws IllegalArgumentException 找不到配对构造器、`NEW` 后缺少 `DUP` 或 handler 签名不兼容时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-05
     */
    private fun injectNewObject(target: MethodNode): Int {
        val typeTarget = at.target.replace('.', '/')
        val inferTarget = typeTarget.isEmpty()
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
            if (!inferTarget && insn.desc != typeTarget) {
                continue
            }

            val newType = Type.getObjectType(insn.desc)
            if (inferTarget && !isNewObjectHandlerCompatible(target, newType)) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val dupInsn = nextRealInstruction(insn)
            if (dupInsn?.opcode != Opcodes.DUP) {
                throw IllegalArgumentException(
                    "@WrapWithCondition NEW requires NEW followed by DUP for ${insn.desc}",
                )
            }
            val constructorInsn = findConstructorInvocation(insn)
            val targetParamCount = validateNewObjectHandlerSignature(target, newType)
            val il = buildNewObjectConditionWrapper(target, newType, targetParamCount)
            target.instructions.insert(constructorInsn, il)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 判断 handler 是否兼容候选对象构造结果。
     *
     * 该方法用于目标推断模式，签名不兼容候选不会计入 ordinal 或命中数。
     *
     * @param target 目标方法
     * @param newType 构造完成后的对象类型
     * @return handler 可条件包裹该构造点时返回 `true`
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-05
     */
    private fun isNewObjectHandlerCompatible(
        target: MethodNode,
        newType: Type,
    ): Boolean = runCatching { validateNewObjectHandlerSignature(target, newType) }.isSuccess

    /**
     * 在匹配的 `CHECKCAST` 类型转换后插入条件 handler。
     *
     * 显式声明类型目标时按 internal name 或 binary name 匹配；省略目标时按 handler 首参筛选兼容转换点。
     * handler 返回 `false` 时把转换后的引用替换为 `null`，返回 `true` 时恢复原转换结果。
     *
     * @param target 目标方法
     * @return 实际插入条件包裹逻辑的类型转换数量
     * @throws IllegalArgumentException handler 签名不兼容时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-05
     */
    private fun injectCast(target: MethodNode): Int {
        val typeTarget = at.target.replace('.', '/')
        val inferTarget = typeTarget.isEmpty()
        var injectionCount = 0
        var matchedOrdinal = 0
        val insns = target.instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)
        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (insn !is TypeInsnNode || insn.opcode != Opcodes.CHECKCAST) {
                continue
            }
            if (!inferTarget && insn.desc != typeTarget) {
                continue
            }

            val castType = Type.getObjectType(insn.desc)
            if (inferTarget && !isCastHandlerCompatible(target, castType)) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val targetParamCount = validateCastHandlerSignature(target, castType)
            val il = buildCastConditionWrapper(target, castType, targetParamCount)
            target.instructions.insert(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 判断 handler 是否兼容候选类型转换。
     *
     * 该方法用于目标推断模式，签名不兼容候选不会计入 ordinal 或命中数。
     *
     * @param target 目标方法
     * @param castType `CHECKCAST` 完成后的引用类型
     * @return handler 可条件包裹该类型转换时返回 `true`
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-05
     */
    private fun isCastHandlerCompatible(
        target: MethodNode,
        castType: Type,
    ): Boolean = runCatching { validateCastHandlerSignature(target, castType) }.isSuccess

    /**
     * 在匹配的 `INSTANCEOF` 类型判断后插入条件 handler。
     *
     * 显式声明类型目标时按 internal name 或 binary name 匹配；省略目标时按 handler 签名筛选兼容判断点。
     * handler 返回 `false` 时把本次类型判断结果替换为 `false`，返回 `true` 时恢复原判断结果。
     *
     * @param target 目标方法
     * @return 实际插入条件包裹逻辑的类型判断数量
     * @throws IllegalArgumentException handler 签名不兼容时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-05
     */
    private fun injectInstanceof(target: MethodNode): Int {
        val typeTarget = at.target.replace('.', '/')
        val inferTarget = typeTarget.isEmpty()
        var injectionCount = 0
        var matchedOrdinal = 0
        val insns = target.instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)
        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (insn !is TypeInsnNode || insn.opcode != Opcodes.INSTANCEOF) {
                continue
            }
            if (!inferTarget && insn.desc != typeTarget) {
                continue
            }
            if (inferTarget && !isInstanceofHandlerCompatible(target)) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val targetParamCount = validateInstanceofHandlerSignature(target)
            val il = buildInstanceofConditionWrapper(target, targetParamCount)
            target.instructions.insert(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 判断 handler 是否兼容候选类型判断。
     *
     * 该方法用于目标推断模式，签名不兼容候选不会计入 ordinal 或命中数。
     *
     * @param target 目标方法
     * @return handler 可条件包裹该类型判断时返回 `true`
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-05
     */
    private fun isInstanceofHandlerCompatible(target: MethodNode): Boolean =
        runCatching { validateInstanceofHandlerSignature(target) }.isSuccess

    /**
     * 在匹配的 switch selector 前插入条件 handler。
     *
     * `tableswitch` 与 `lookupswitch` 都只消费一个 `Int` selector；handler 返回 `true` 时保留原 selector，
     * 返回 `false` 时用 `Int` 默认值 `0` 交给原 switch 指令继续分派。
     *
     * @param target 目标方法
     * @return 实际插入条件包裹逻辑的 switch 数量
     * @throws IllegalArgumentException 声明了 [At.target] 或 handler 签名不兼容时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-05
     */
    private fun injectSwitch(target: MethodNode): Int {
        if (at.target.isNotEmpty()) {
            throw IllegalArgumentException("@WrapWithCondition SWITCH does not support at.target")
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
            val il = buildSwitchConditionWrapper(target, targetParamCount)
            target.instructions.insertBefore(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 解析常量加载条件包裹使用的表达式类型。
     *
     * `ICONST_0` / `ICONST_1` 在用户按 `true` / `false` 过滤，或 handler 首参声明为 boolean 时按 boolean 处理；
     * `ACONST_NULL` 没有固有精确引用类型，优先使用 handler 首参声明的引用类型作为默认值与校验类型。
     *
     * @param insn 常量加载指令
     * @param requestedConstant 用户声明的常量文本；为 `null` 时按字节码类型和 handler 首参推断
     * @return handler 接收与 false 分支默认值使用的常量类型；无法解析时返回 `null`
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-01
     */
    private fun resolveConstantConditionType(
        insn: AbstractInsnNode,
        requestedConstant: String?,
    ): Type? {
        val handlerFirstParam = Type.getArgumentTypes(asmMethod).firstOrNull()
        if (isBooleanConstantOpcode(insn)) {
            if (requestedConstant != null &&
                isBooleanLiteral(requestedConstant) &&
                isBooleanConstantInsn(insn, requestedConstant == "true")
            ) {
                return Type.BOOLEAN_TYPE
            }
            if (requestedConstant == null && handlerFirstParam == Type.BOOLEAN_TYPE) {
                return Type.BOOLEAN_TYPE
            }
        }
        if (insn.opcode == Opcodes.ACONST_NULL) {
            if (handlerFirstParam?.isReferenceType() == true) {
                return handlerFirstParam
            }
        }
        return BytecodeUtil.getConstantType(insn)
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
     * 构造字段读取条件包裹的后置指令序列。
     *
     * `GETFIELD` / `GETSTATIC` 已经把字段值压入栈顶，因此这里复用局部变量读取的后置包装模型：
     * 先暂存原字段值，再由 handler 决定恢复原值或压入字段类型默认值。
     *
     * @param target 目标方法
     * @param fieldType 字段读取值类型
     * @param targetParamCount handler 追加接收的目标方法参数数量
     * @return 插入到原字段读取后的条件包裹指令列表
     */
    private fun buildFieldReadConditionWrapper(
        target: MethodNode,
        fieldType: Type,
        targetParamCount: Int,
    ): InsnList = buildLoadConditionWrapper(target, fieldType, targetParamCount)

    /**
     * 构造数组元素读取条件包裹的后置指令序列。
     *
     * `xALOAD` 已经把元素值压入栈顶，因此该逻辑复用局部变量读取模型：
     * 先暂存元素值，再由 handler 决定恢复元素值或压入元素类型默认值。
     *
     * @param target 目标方法
     * @param elementType 数组元素读取值类型
     * @param targetParamCount handler 追加接收的目标方法参数数量
     * @return 插入到数组元素读取后的条件包裹指令列表
     */
    private fun buildArrayReadConditionWrapper(
        target: MethodNode,
        elementType: Type,
        targetParamCount: Int,
    ): InsnList = buildLoadConditionWrapper(target, elementType, targetParamCount)

    /**
     * 构造数组长度读取条件包裹的后置指令序列。
     *
     * `ARRAYLENGTH` 的表达式值固定为 `Int`，false 分支使用 `0` 作为默认长度。
     *
     * @param target 目标方法
     * @param targetParamCount handler 追加接收的目标方法参数数量
     * @return 插入到数组长度读取后的条件包裹指令列表
     */
    private fun buildArrayLengthConditionWrapper(
        target: MethodNode,
        targetParamCount: Int,
    ): InsnList = buildLoadConditionWrapper(target, Type.INT_TYPE, targetParamCount)

    /**
     * 构造调用返回值条件包裹的后置指令序列。
     *
     * `INVOKE_ASSIGN` 插入点位于调用指令之后，栈顶已经是原调用返回值；序列会暂存该值，
     * 再由 boolean handler 决定恢复原值还是压入返回类型默认值。
     *
     * @param target 目标方法
     * @param returnType 原调用返回值类型
     * @param targetParamCount handler 追加接收的目标方法参数数量
     * @return 插入到原调用后的条件包裹指令列表
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-05
     */
    private fun buildMethodCallReturnConditionWrapper(
        target: MethodNode,
        returnType: Type,
        targetParamCount: Int,
    ): InsnList = buildLoadConditionWrapper(target, returnType, targetParamCount)

    /**
     * 构造局部变量读取条件包裹的后置指令序列。
     *
     * 序列会暂存 `xLOAD` 刚压入栈顶的读取值，调用 boolean handler；
     * handler 返回 `false` 时压入读取类型的默认值，返回 `true` 时恢复原读取值。
     *
     * @param target 目标方法
     * @param loadType 局部变量读取值类型
     * @param targetParamCount handler 追加接收的目标方法参数数量
     * @return 插入到原局部变量读取后的条件包裹指令列表
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-01
     */
    private fun buildLoadConditionWrapper(
        target: MethodNode,
        loadType: Type,
        targetParamCount: Int,
    ): InsnList {
        val il = InsnList()
        val valueIndex = nextLocalIndex(target)
        val defaultValueLabel = LabelNode()
        val afterConditionLabel = LabelNode()

        storeStackValue(il, loadType, valueIndex)
        addHandlerOwner(il)
        loadFromVariable(il, loadType, valueIndex)
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
        il.add(JumpInsnNode(Opcodes.IFEQ, defaultValueLabel))
        loadFromVariable(il, loadType, valueIndex)
        il.add(JumpInsnNode(Opcodes.GOTO, afterConditionLabel))
        il.add(defaultValueLabel)
        loadDefaultReturnValue(loadType, il)
        il.add(afterConditionLabel)

        return il
    }

    /**
     * 构造常量加载条件包裹的后置指令序列。
     *
     * 序列与局部变量读取包裹保持同一栈形状：先暂存原常量值，再调用 boolean handler；
     * handler 返回 `false` 时压入常量类型默认值，返回 `true` 时恢复原常量值。
     *
     * @param target 目标方法
     * @param constantType 常量表达式值类型
     * @param targetParamCount handler 追加接收的目标方法参数数量
     * @return 插入到原常量加载后的条件包裹指令列表
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-01
     */
    private fun buildConstantConditionWrapper(
        target: MethodNode,
        constantType: Type,
        targetParamCount: Int,
    ): InsnList = buildLoadConditionWrapper(target, constantType, targetParamCount)

    /**
     * 构造 `NEW` 构造结果条件包裹的后置指令序列。
     *
     * `NEW` 表达式在配对 `<init>` 之后留下已初始化对象，栈形状与 `CHECKCAST` 完成后的引用一致：
     * 暂存原对象，调用 handler；handler 返回 `false` 时压入 `null` 替换本次构造表达式。
     *
     * @param target 目标方法
     * @param newType 构造完成后的对象类型
     * @param targetParamCount handler 追加接收的目标方法参数数量
     * @return 插入到配对 `<init>` 后的条件包裹指令列表
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-05
     */
    private fun buildNewObjectConditionWrapper(
        target: MethodNode,
        newType: Type,
        targetParamCount: Int,
    ): InsnList = buildCastConditionWrapper(target, newType, targetParamCount)

    /**
     * 构造 `CHECKCAST` 类型转换条件包裹的后置指令序列。
     *
     * CAST 是引用表达式，false 分支必须留下 `null`，不能复用会为 `String` 生成空串的通用默认值策略。
     * 序列会暂存转换后的引用，调用 boolean handler；handler 返回 `true` 时恢复原引用。
     *
     * @param target 目标方法
     * @param castType `CHECKCAST` 完成后的引用类型
     * @param targetParamCount handler 追加接收的目标方法参数数量
     * @return 插入到原 `CHECKCAST` 后的条件包裹指令列表
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-05
     */
    private fun buildCastConditionWrapper(
        target: MethodNode,
        castType: Type,
        targetParamCount: Int,
    ): InsnList {
        val il = InsnList()
        val valueIndex = nextLocalIndex(target)
        val defaultValueLabel = LabelNode()
        val afterConditionLabel = LabelNode()

        storeStackValue(il, castType, valueIndex)
        addHandlerOwner(il)
        loadFromVariable(il, castType, valueIndex)
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
        il.add(JumpInsnNode(Opcodes.IFEQ, defaultValueLabel))
        loadFromVariable(il, castType, valueIndex)
        il.add(JumpInsnNode(Opcodes.GOTO, afterConditionLabel))
        il.add(defaultValueLabel)
        il.add(InsnNode(Opcodes.ACONST_NULL))
        il.add(afterConditionLabel)

        return il
    }

    /**
     * 构造 `INSTANCEOF` 类型判断条件包裹的后置指令序列。
     *
     * `INSTANCEOF` 已经在栈顶留下 boolean 结果，因此可复用表达式值条件包裹的栈形状：
     * 暂存原判断结果，调用 handler；handler 返回 `false` 时压入 boolean 默认值 `false`。
     *
     * @param target 目标方法
     * @param targetParamCount handler 追加接收的目标方法参数数量
     * @return 插入到原 `INSTANCEOF` 后的条件包裹指令列表
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-05
     */
    private fun buildInstanceofConditionWrapper(
        target: MethodNode,
        targetParamCount: Int,
    ): InsnList = buildLoadConditionWrapper(target, Type.BOOLEAN_TYPE, targetParamCount)

    /**
     * 构造 switch selector 条件包裹的前置指令序列。
     *
     * switch 指令执行前 selector 已经位于栈顶，因此可复用表达式值条件包裹的栈形状：
     * 暂存原 selector，调用 handler；handler 返回 `false` 时压入 `Int` 默认值 `0`。
     *
     * @param target 目标方法
     * @param targetParamCount handler 追加接收的目标方法参数数量
     * @return 插入到原 switch 前的条件包裹指令列表
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-05
     */
    private fun buildSwitchConditionWrapper(
        target: MethodNode,
        targetParamCount: Int,
    ): InsnList = buildLoadConditionWrapper(target, Type.INT_TYPE, targetParamCount)

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
     * 校验调用返回值条件包裹的 handler 签名。
     *
     * handler 必须返回 `boolean`，首参接收普通调用或 `invokedynamic` 调用已经产生的返回值；
     * 其余参数会被解释为目标方法开头的参数前缀。
     *
     * @param target 目标方法
     * @param callReturnType 调用返回值类型
     * @return handler 追加接收的目标方法参数数量
     * @throws IllegalArgumentException handler 返回值、调用返回值参数或追加目标参数不兼容时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-05
     */
    private fun validateCallReturnHandlerSignature(
        target: MethodNode,
        callReturnType: Type,
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
                "@WrapWithCondition INVOKE_ASSIGN handler ${asmMethod.name} must receive call return value",
            )
        }
        if (!isHandlerParameterCompatible(callReturnType, actualParams[0])) {
            throw IllegalArgumentException(
                "@WrapWithCondition INVOKE_ASSIGN handler ${asmMethod.name} parameter #0 mismatch: " +
                    "expected $callReturnType, actual ${actualParams[0]}",
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
     * 校验数组元素读取或数组长度读取条件包裹的 handler 签名。
     *
     * handler 必须返回 `boolean`，首参接收数组读取表达式值；其余参数会被解释为目标方法开头的参数前缀。
     *
     * @param target 目标方法
     * @param valueType 数组元素值类型或长度 `Int` 类型
     * @return handler 追加接收的目标方法参数数量
     * @throws IllegalArgumentException handler 返回值、读取值参数或追加目标参数不兼容时抛出
     */
    private fun validateArrayReadHandlerSignature(
        target: MethodNode,
        valueType: Type,
    ): Int = validateFieldReadHandlerSignature(target, valueType)

    /**
     * 校验字段读取条件包裹的 handler 签名。
     *
     * handler 必须返回 `boolean`，首参接收 `GETFIELD` / `GETSTATIC` 已读取出的字段值；
     * 其余参数会被解释为目标方法开头的参数前缀。
     *
     * @param target 目标方法
     * @param fieldType 字段读取值类型
     * @return handler 追加接收的目标方法参数数量
     * @throws IllegalArgumentException handler 返回值、字段值参数或追加目标参数不兼容时抛出
     */
    private fun validateFieldReadHandlerSignature(
        target: MethodNode,
        fieldType: Type,
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
                "@WrapWithCondition FIELD handler ${asmMethod.name} must receive original field value",
            )
        }
        if (!isHandlerParameterCompatible(fieldType, actualParams[0])) {
            throw IllegalArgumentException(
                "@WrapWithCondition handler ${asmMethod.name} parameter #0 mismatch: " +
                    "expected $fieldType, actual ${actualParams[0]}",
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
     * 校验局部变量读取条件包裹的 handler 签名。
     *
     * handler 必须返回 `boolean`，首参接收本次 `xLOAD` 读取出的表达式值；
     * 其余参数会被解释为目标方法开头的参数前缀。
     *
     * @param target 目标方法
     * @param loadType 局部变量读取值类型
     * @return handler 追加接收的目标方法参数数量
     * @throws IllegalArgumentException handler 返回值、读取值参数或追加目标参数不兼容时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-01
     */
    private fun validateLoadHandlerSignature(
        target: MethodNode,
        loadType: Type,
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
                "@WrapWithCondition LOAD handler ${asmMethod.name} must receive original local value",
            )
        }
        if (!isHandlerParameterCompatible(loadType, actualParams[0])) {
            throw IllegalArgumentException(
                "@WrapWithCondition handler ${asmMethod.name} parameter #0 mismatch: " +
                    "expected $loadType, actual ${actualParams[0]}",
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
     * 校验常量加载条件包裹的 handler 签名。
     *
     * handler 必须返回 `boolean`，首参接收本次常量加载产生的表达式值；
     * 其余参数会被解释为目标方法开头的参数前缀。
     *
     * @param target 目标方法
     * @param constantType 常量表达式值类型
     * @return handler 追加接收的目标方法参数数量
     * @throws IllegalArgumentException handler 返回值、常量值参数或追加目标参数不兼容时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-01
     */
    private fun validateConstantHandlerSignature(
        target: MethodNode,
        constantType: Type,
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
                "@WrapWithCondition CONSTANT handler ${asmMethod.name} must receive original constant value",
            )
        }
        if (!isHandlerParameterCompatible(constantType, actualParams[0])) {
            throw IllegalArgumentException(
                "@WrapWithCondition handler ${asmMethod.name} parameter #0 mismatch: " +
                    "expected $constantType, actual ${actualParams[0]}",
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
     * 校验 `NEW` 构造结果条件包裹的 handler 签名。
     *
     * handler 必须返回 `boolean`，首参接收构造完成后的对象引用；
     * 其余参数会被解释为目标方法开头的参数前缀。
     *
     * @param target 目标方法
     * @param newType 构造完成后的对象类型
     * @return handler 追加接收的目标方法参数数量
     * @throws IllegalArgumentException handler 返回值、构造对象参数或追加目标参数不兼容时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-05
     */
    private fun validateNewObjectHandlerSignature(
        target: MethodNode,
        newType: Type,
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
                "@WrapWithCondition NEW handler ${asmMethod.name} must receive constructed object",
            )
        }
        if (!isHandlerParameterCompatible(newType, actualParams[0])) {
            throw IllegalArgumentException(
                "@WrapWithCondition NEW handler ${asmMethod.name} parameter #0 mismatch: " +
                    "expected $newType, actual ${actualParams[0]}",
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
     * 校验 `CHECKCAST` 类型转换条件包裹的 handler 签名。
     *
     * handler 必须返回 `boolean`，首参接收转换完成后的引用；
     * 其余参数会被解释为目标方法开头的参数前缀。
     *
     * @param target 目标方法
     * @param castType `CHECKCAST` 完成后的引用类型
     * @return handler 追加接收的目标方法参数数量
     * @throws IllegalArgumentException handler 返回值、转换值参数或追加目标参数不兼容时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-05
     */
    private fun validateCastHandlerSignature(
        target: MethodNode,
        castType: Type,
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
                "@WrapWithCondition CAST handler ${asmMethod.name} must receive original cast value",
            )
        }
        if (!isHandlerParameterCompatible(castType, actualParams[0])) {
            throw IllegalArgumentException(
                "@WrapWithCondition CAST handler ${asmMethod.name} parameter #0 mismatch: " +
                    "expected $castType, actual ${actualParams[0]}",
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
     * 校验 `INSTANCEOF` 类型判断条件包裹的 handler 签名。
     *
     * handler 必须返回 `boolean`，首参接收原始类型判断结果；
     * 其余参数会被解释为目标方法开头的参数前缀。
     *
     * @param target 目标方法
     * @return handler 追加接收的目标方法参数数量
     * @throws IllegalArgumentException handler 返回值、原判断结果参数或追加目标参数不兼容时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-05
     */
    private fun validateInstanceofHandlerSignature(target: MethodNode): Int {
        val returnType = Type.getReturnType(asmMethod)
        if (returnType.sort != Type.BOOLEAN) {
            throw IllegalArgumentException(
                "@WrapWithCondition handler ${asmMethod.name} must return boolean, actual $returnType",
            )
        }

        val actualParams = Type.getArgumentTypes(asmMethod)
        if (actualParams.isEmpty()) {
            throw IllegalArgumentException(
                "@WrapWithCondition INSTANCEOF handler ${asmMethod.name} must receive original boolean result",
            )
        }
        if (!isHandlerParameterCompatible(Type.BOOLEAN_TYPE, actualParams[0])) {
            throw IllegalArgumentException(
                "@WrapWithCondition INSTANCEOF handler ${asmMethod.name} parameter #0 mismatch: " +
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
     * 校验 switch selector 条件包裹的 handler 签名。
     *
     * handler 必须返回 `boolean`，首参接收原始 `Int` selector；
     * 其余参数会被解释为目标方法开头的参数前缀。
     *
     * @param target 目标方法
     * @return handler 追加接收的目标方法参数数量
     * @throws IllegalArgumentException handler 返回值、selector 参数或追加目标参数不兼容时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-05
     */
    private fun validateSwitchHandlerSignature(target: MethodNode): Int {
        val returnType = Type.getReturnType(asmMethod)
        if (returnType.sort != Type.BOOLEAN) {
            throw IllegalArgumentException(
                "@WrapWithCondition handler ${asmMethod.name} must return boolean, actual $returnType",
            )
        }

        val actualParams = Type.getArgumentTypes(asmMethod)
        if (actualParams.isEmpty()) {
            throw IllegalArgumentException(
                "@WrapWithCondition SWITCH handler ${asmMethod.name} must receive original selector",
            )
        }
        if (!isHandlerParameterCompatible(Type.INT_TYPE, actualParams[0])) {
            throw IllegalArgumentException(
                "@WrapWithCondition SWITCH handler ${asmMethod.name} parameter #0 mismatch: " +
                    "expected ${Type.INT_TYPE}, actual ${actualParams[0]}",
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
     * 从数组访问指令向前查找产生数组引用的字段读取指令。
     *
     * 只接受直接邻近且匹配目标字段的字段读取；遇到其他字段指令、方法调用或其他数组访问时停止，
     * 避免跨过会改变栈结构的复杂表达式。
     *
     * @param arrayInsn 数组元素读写或数组长度指令
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
            if (cursor is MethodInsnNode || cursor.opcode in ARRAY_READ_OPS || cursor.opcode in ARRAY_WRITE_OPS) {
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
     * 查找与 `NEW` 指令配对的构造器调用。
     *
     * 同一 owner 出现嵌套 `NEW` 时使用计数跳过内层 `<init>`，确保条件包裹插入到当前构造表达式完成后。
     *
     * @param newInsn 待匹配的 `NEW` 指令
     * @return 与该 `NEW` 配对的构造器调用指令
     * @throws IllegalArgumentException 找不到配对构造器调用时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-05
     */
    private fun findConstructorInvocation(newInsn: TypeInsnNode): MethodInsnNode {
        var nestedSameOwnerNewCount = 0
        var current = newInsn.next
        while (current != null) {
            if (current is TypeInsnNode && current.opcode == Opcodes.NEW && current.desc == newInsn.desc) {
                nestedSameOwnerNewCount++
            } else if (
                current is MethodInsnNode &&
                current.opcode == Opcodes.INVOKESPECIAL &&
                current.owner == newInsn.desc &&
                current.name == "<init>"
            ) {
                if (nestedSameOwnerNewCount == 0) {
                    return current
                }
                nestedSameOwnerNewCount--
            }
            current = current.next
        }

        throw IllegalArgumentException("@WrapWithCondition cannot find constructor call for NEW ${newInsn.desc}")
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
     * 解析局部变量读写过滤条件。
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
     * 判断局部变量读写指令是否满足槽位和名称过滤条件。
     *
     * @param target 目标方法
     * @param insn 候选局部变量读写指令
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
        return localVariableAt(target, localVariableAnchor(insn), insn.`var`)?.name == filter.name
    }

    /**
     * 选择局部变量名称过滤使用的锚点。
     *
     * STORE 指令可能位于变量作用域起点标签之前，因此优先使用写入后的下一条真实指令判断变量名范围。
     * LOAD 指令本身位于变量生命周期内，直接使用读取指令判断，避免误用后续指令越过生命周期边界。
     *
     * @param insn 候选局部变量读写指令
     * @return 用于 LocalVariableTable 范围判断的锚点
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-01
     */
    private fun localVariableAnchor(insn: VarInsnNode): AbstractInsnNode =
        if (insn.opcode in STORE_OPS) {
            nextRealInstruction(insn) ?: insn
        } else {
            insn
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
     * 基础类型不需要额外推断，引用类型会依次尝试当前 LocalVariableTable 作用域、目标方法参数与当前指令相邻上下文。
     * 当前作用域必须绑定到具体 [insn]，避免同一槽位复用时误取历史生命周期的类型。
     *
     * @param target 目标方法
     * @param insn 候选局部变量读写指令
     * @param fallbackType handler 首参类型
     * @return 推断出的引用表达式类型；无法可靠推断时返回 `null`
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-01
     */
    private fun resolveIndexedLocalValueType(
        target: MethodNode,
        insn: VarInsnNode,
        fallbackType: Type,
    ): Type? {
        if (!fallbackType.isReferenceType()) {
            return null
        }

        val currentLocalVariable =
            localVariableAt(target, localVariableAnchor(insn), insn.`var`)
                ?.let { runCatching { Type.getType(it.desc) }.getOrNull() }
                ?.takeIf { it.isReferenceType() }
        if (currentLocalVariable != null) {
            return currentLocalVariable
        }

        val headVariable = collectHeadParameters(target).firstOrNull { it.index == insn.`var` }
        if (headVariable != null) {
            return headVariable.type
        }

        return referencedTypeFromSlotInstruction(target, insn, fallbackType)
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
     * 通过当前引用读写指令附近的消费关系推断表达式类型。
     *
     * 该推断作为 LocalVariableTable 缺失或不完整时的兜底，只考察当前 `ALOAD` 或 `ASTORE` 的相邻上下文，
     * 避免同槽位其他生命周期的读写指令干扰当前候选点。
     *
     * @param target 目标方法
     * @param insn 候选局部变量读写指令
     * @param fallbackType handler 首参类型
     * @return 能与 handler 首参兼容的引用类型；无法推断时返回 `null`
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-01
     */
    private fun referencedTypeFromSlotInstruction(
        target: MethodNode,
        insn: VarInsnNode,
        fallbackType: Type,
    ): Type? {
        if (insn.opcode !in SLOT_REFERENCE_OPS) {
            return null
        }

        return inferReferenceTypeAroundSlotInstruction(target, insn)
            ?.takeIf { isHandlerParameterCompatible(it, fallbackType) }
    }

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
     * 判断局部变量读取指令产生的值类型是否可交给 handler 首参。
     *
     * JVM 的 `ILOAD` 覆盖 boolean、byte、short、int 与 char，引用读取只接受对象或数组 handler 参数。
     *
     * @param opcode 读取指令 opcode
     * @param handlerType handler 首参类型
     * @return 读取值类型与 handler 首参兼容时返回 `true`
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-01
     */
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
     * 判断用户声明的常量文本是否为布尔字面量。
     *
     * @param value 常量过滤文本
     * @return 文本为 `true` 或 `false` 时返回 `true`
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-01
     */
    private fun isBooleanLiteral(value: String): Boolean = value == "true" || value == "false"

    /**
     * 判断常量指令是否为 JVM 承载 boolean 字面量时使用的短整型常量。
     *
     * @param insn 待检查的常量指令
     * @return 指令为 `ICONST_0` 或 `ICONST_1` 时返回 `true`
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-01
     */
    private fun isBooleanConstantOpcode(insn: AbstractInsnNode): Boolean =
        insn.opcode == Opcodes.ICONST_0 || insn.opcode == Opcodes.ICONST_1

    /**
     * 判断整数短常量指令是否表示指定布尔值。
     *
     * JVM 使用 `ICONST_0` 与 `ICONST_1` 承载 boolean 常量，只有用户按布尔文本过滤时才按此语义解释。
     *
     * @param insn 待检查的常量指令
     * @param value 期望布尔值
     * @return 指令与期望布尔值一致时返回 `true`
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-01
     */
    private fun isBooleanConstantInsn(
        insn: AbstractInsnNode,
        value: Boolean,
    ): Boolean =
        when (insn.opcode) {
            Opcodes.ICONST_0 -> !value
            Opcodes.ICONST_1 -> value
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
    private fun resolveSliceRange(insns: Array<AbstractInsnNode>): Pair<Int, Int> =
        SliceBoundaryResolver.resolveRange(
            insns,
            slice,
            "@WrapWithCondition",
            SliceBoundaryResolver.INVOKE_BOUNDARIES,
        )

    /**
     * 提取调用指令的可读名称。
     *
     * 该名称只用于错误提示；普通调用包含 owner、name 和 descriptor，动态调用标明 `invokedynamic`。
     *
     * @param insn 待描述的调用指令
     * @param desc 调用点 JVM 描述符
     * @return 可读调用名称
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-05
     */
    private fun callDisplayName(
        insn: AbstractInsnNode,
        desc: String,
    ): String =
        when (insn) {
            is MethodInsnNode -> "${insn.owner}.${insn.name}$desc"
            is InvokeDynamicInsnNode -> "invokedynamic ${insn.name}$desc"
            else -> "<unknown>$desc"
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
     * 数组访问条件包裹模式。
     */
    private enum class ArrayAccessMode {
        /**
         * 未声明数组访问模式，按普通字段读写处理。
         */
        NONE,

        /**
         * 条件包裹数组元素读取表达式。
         */
        GET,

        /**
         * 条件包裹数组长度读取表达式。
         */
        LENGTH,

        /**
         * 条件包裹数组元素写入指令。
         */
        SET,
    }

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
         * 可被 `LOAD` 条件包裹的局部变量读取 opcode。
         */
        private val LOAD_OPS = setOf(Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD, Opcodes.ALOAD)

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
         * 可被数组元素读取条件包裹的数组读取 opcode。
         */
        private val ARRAY_READ_OPS = setOf(
            Opcodes.IALOAD,
            Opcodes.LALOAD,
            Opcodes.FALOAD,
            Opcodes.DALOAD,
            Opcodes.AALOAD,
            Opcodes.BALOAD,
            Opcodes.CALOAD,
            Opcodes.SALOAD,
        )

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
