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
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
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
 * ModifyExpressionValue 注入器。
 *
 * 该注入器会匹配目标方法内的指定普通方法调用、`invokedynamic` 调用、字段读取、字段写入值、数组元素读取、数组元素写入值、数组长度、对象构造、类型转换、
 * `INSTANCEOF` 判断、局部变量读取值、局部变量写入值、条件跳转、`tableswitch` / `lookupswitch` selector 或 `ATHROW` 抛异常指令，
 * 并在表达式产生值后把原值传给 handler。
 * handler 返回的新值会替代原表达式值留在操作数栈顶，后续原始字节码继续按未修改的栈形态执行。
 * 对象或数组表达式可用原值类型的父类、接口、`Any` 或 `Object` 接收。
 * handler 返回类型对基础类型必须精确匹配，引用表达式可返回表达式类型的子类型，也可用 `Any` 或 `Object`
 * 作为泛型引用返回类型，框架会在调用后转换回表达式类型。
 * [InjectionPoint.THROW] 会在目标 `ATHROW` 前改写即将抛出的异常，也可继续追加目标方法参数；
 * 指定类型目标时，只匹配 `ATHROW` 前直接构造出的同类型异常。
 *
 * @param at 表达式定位；当前支持 [InjectionPoint.INVOKE]、[InjectionPoint.INVOKE_ASSIGN]、
 * [InjectionPoint.FIELD]、[InjectionPoint.FIELD_ASSIGN]、[InjectionPoint.NEW]、[InjectionPoint.CAST]、[InjectionPoint.INSTANCEOF]、[InjectionPoint.LOAD]、[InjectionPoint.STORE]、[InjectionPoint.JUMP]、[InjectionPoint.SWITCH] 与 [InjectionPoint.THROW]；[InjectionPoint.FIELD]
 * 可匹配字段读取值，省略字段目标时会按 handler 首参与返回类型筛选兼容的 `GETFIELD` / `GETSTATIC`；
 * [InjectionPoint.FIELD_ASSIGN] 可匹配 `PUTFIELD` / `PUTSTATIC` 消费前的待写入值，省略字段目标时会按 handler 首参与返回类型筛选兼容字段写入；
 * [InjectionPoint.FIELD] 也可通过 `array=get` 匹配数组元素读取值，通过 `array=length` 匹配数组长度值；
 * [InjectionPoint.FIELD_ASSIGN] 可通过 `array=set` 匹配数组元素写入前的待写入值，
 * [InjectionPoint.INVOKE] / [InjectionPoint.INVOKE_ASSIGN] 可显式匹配普通调用或按 bootstrap owner、动态调用名、bootstrap 名
 * 以及动态调用点描述符匹配 `invokedynamic` 返回值；未指定调用目标时，会按 handler 首参与返回类型筛选兼容的非 `void` 调用返回；
 * [InjectionPoint.NEW] 匹配对象构造完成后的实例，未指定类型目标时，会按 handler 首参与返回类型筛选兼容的 `NEW` 候选；
 * [InjectionPoint.CAST] 匹配 `CHECKCAST` 完成后的对象值；未指定类型目标时，会按 handler 首参与返回类型筛选兼容的
 * `CHECKCAST` 候选；不兼容的调用返回、字段读取、字段写入、数组写入、`NEW` / `CHECKCAST` 候选不计入 [ModifyExpressionValue.ordinal] 或命中数。
 * [InjectionPoint.INSTANCEOF] 匹配类型判断后的 boolean 结果，
 * [InjectionPoint.LOAD] 匹配 `xLOAD` 读取出的栈顶表达式值，可用 [At.args] 中的 `index=N` 或 `var=N` 按 JVM 局部变量槽位过滤；
 * handler 返回的新值只替换这一次读取表达式，不写回原局部变量槽位，
 * [InjectionPoint.STORE] 匹配 `xSTORE` 消费前的待写入表达式值，可用 [At.args] 中的 `index=N` 或 `var=N` 按 JVM 局部变量槽位过滤；
 * handler 返回的新值交给原 `xSTORE` 继续写入槽位，
 * [InjectionPoint.JUMP] 匹配条件跳转的原始分支结果，handler 接收 `Boolean` 并返回新的分支结果；`GOTO` 与 `JSR` 不支持表达式改写，
 * [InjectionPoint.SWITCH] 匹配 `tableswitch` 或 `lookupswitch` 消费前的 `Int` selector，handler 返回的新 selector 会继续交给原 switch 指令分派，
 * [InjectionPoint.THROW] 匹配 `ATHROW` 前即将抛出的 `Throwable`，handler 可返回 `Throwable` 或其子类；
 * 指定类型目标时，只会匹配前一条真实指令为同类型 `<init>` 的直接构造异常
 * @param ordinal 表达式匹配点序号；负数表示处理全部匹配表达式
 * @param slice 切片范围；当前 [InjectionPoint.INVOKE] / [InjectionPoint.INVOKE_ASSIGN] 调用返回、
 * [InjectionPoint.FIELD] 字段读取、[InjectionPoint.FIELD_ASSIGN] 字段写入值、数组元素读取、数组元素写入值、数组长度、[InjectionPoint.NEW]、[InjectionPoint.CAST]、
 * [InjectionPoint.INSTANCEOF]、[InjectionPoint.LOAD]、[InjectionPoint.STORE]、[InjectionPoint.JUMP]、[InjectionPoint.SWITCH] 与 [InjectionPoint.THROW] 表达式使用 INVOKE 边界缩小匹配范围，
 * 边界可匹配普通方法调用、构造器调用或 `invokedynamic` 调用
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
class ModifyExpressionValueInjector(
    method: Method,
    asmInfo: AsmInfo,
    private val at: At,
    private val ordinal: Int = -1,
    private val slice: Slice = Slice(),
) : AbstractAsmInjector(method, asmInfo) {
    /**
     * 在匹配表达式产生值后改写该值。
     *
     * @param target 目标方法
     * @return 至少匹配并改写一个表达式值时返回 `true`
     * @throws IllegalArgumentException 定位点、目标表达式或 handler 签名不合法时抛出
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun inject(target: MethodNode): Boolean = injectCount(target) > 0

    /**
     * 在匹配表达式产生值后改写该值并返回实际修改数量。
     *
     * @param target 目标方法
     * @return 实际写入表达式值修改逻辑的数量
     * @throws IllegalArgumentException 定位点、目标表达式或 handler 签名不合法时抛出
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun injectCount(target: MethodNode): Int {
        return when (at.value) {
            InjectionPoint.INVOKE, InjectionPoint.INVOKE_ASSIGN -> injectMethodCallReturn(target)
            InjectionPoint.FIELD ->
                when (arrayAccessMode()) {
                    ArrayAccessMode.NONE -> injectFieldRead(target)
                    ArrayAccessMode.GET -> injectArrayRead(target)
                    ArrayAccessMode.LENGTH -> injectArrayLength(target)
                    ArrayAccessMode.SET -> throw IllegalArgumentException(
                        "@ModifyExpressionValue array=set requires FIELD_ASSIGN injection point",
                    )
                }
            InjectionPoint.FIELD_ASSIGN ->
                when (arrayAccessMode()) {
                    ArrayAccessMode.NONE -> injectFieldAssign(target)
                    ArrayAccessMode.SET -> injectArrayWrite(target)
                    ArrayAccessMode.GET -> throw IllegalArgumentException(
                        "@ModifyExpressionValue array=get requires FIELD injection point",
                    )
                    ArrayAccessMode.LENGTH -> throw IllegalArgumentException(
                        "@ModifyExpressionValue array=length requires FIELD injection point",
                    )
                }
            InjectionPoint.NEW -> injectNewObject(target)
            InjectionPoint.CAST -> injectCast(target)
            InjectionPoint.INSTANCEOF -> injectInstanceof(target)
            InjectionPoint.LOAD -> injectLoad(target)
            InjectionPoint.STORE -> injectStore(target)
            InjectionPoint.JUMP -> injectJump(target)
            InjectionPoint.SWITCH -> injectSwitch(target)
            InjectionPoint.CONSTANT -> injectConstant(target)
            InjectionPoint.THROW -> injectThrow(target)
            else -> throw IllegalArgumentException(
                "@ModifyExpressionValue currently supports only INVOKE, INVOKE_ASSIGN, FIELD, FIELD_ASSIGN, NEW, CAST, INSTANCEOF, LOAD, STORE, JUMP, SWITCH, CONSTANT and THROW",
            )
        }
    }

    /**
     * 解析数组表达式访问模式。
     *
     * 未声明 `array=` 参数时按普通字段表达式处理；声明后只接受 `get`、`length` 或 `set`。
     *
     * @return 当前定位点对应的数组访问模式
     * @throws IllegalArgumentException 声明了不支持的数组访问模式时抛出
     */
    private fun arrayAccessMode(): ArrayAccessMode {
        val arrayArg = at.args.firstOrNull { it.trim().startsWith("array=") } ?: return ArrayAccessMode.NONE
        return when (arrayArg.substringAfter('=').trim().lowercase()) {
            "get" -> ArrayAccessMode.GET
            "length" -> ArrayAccessMode.LENGTH
            "set" -> ArrayAccessMode.SET
            else -> throw IllegalArgumentException("Unsupported @ModifyExpressionValue array access mode: $arrayArg")
        }
    }

    /**
     * 改写普通方法调用或 `invokedynamic` 调用产生的返回值。
     *
     * 显式声明目标时按 owner、名称与描述符匹配；未声明目标时按 handler 首参与返回类型筛选所有非 `void` 调用返回。
     * 注入逻辑插入在调用指令之后，使 handler 接收原返回值并将新值留在栈顶。
     *
     * @param target 目标方法
     * @return 实际改写的调用返回值数量
     * @throws IllegalArgumentException 目标签名不完整、匹配到 `void` 调用或 handler 签名不兼容时抛出
     */
    private fun injectMethodCallReturn(target: MethodNode): Int {
        val inferTarget = at.target.isEmpty()
        val (targetOwner, targetName, targetDesc) = parseTargetMethod(at.target)
        if (!inferTarget && (targetName == null || targetDesc == null)) {
            throw IllegalArgumentException("@ModifyExpressionValue requires at.target method signature")
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
                }
            if (callDesc == null) {
                continue
            }

            val callReturnType = Type.getReturnType(callDesc)
            if (callReturnType == Type.VOID_TYPE) {
                if (inferTarget) {
                    continue
                }
                throw IllegalArgumentException(
                    "@ModifyExpressionValue cannot modify void call ${callName(insn)}$callDesc",
                )
            }
            if (inferTarget && !isHandlerCompatible(callReturnType, allowThrowableSubtypeReturn = false)) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val targetParamCount = validateHandlerSignature(target, callReturnType)
            val il = buildExpressionValueModification(target, callReturnType, targetParamCount)
            target.instructions.insert(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 改写字段读取表达式产生的字段值。
     *
     * 显式声明字段目标时按字段 owner、名称与描述符匹配；未声明目标时按 handler 类型筛选兼容字段读取。
     * 注入逻辑插入在 `GETFIELD` 或 `GETSTATIC` 之后，替换已压入栈顶的字段值。
     *
     * @param target 目标方法
     * @return 实际改写的字段读取值数量
     * @throws IllegalArgumentException 字段目标缺少名称或 handler 签名不兼容时抛出
     */
    private fun injectFieldRead(target: MethodNode): Int {
        val inferTarget = at.target.isEmpty()
        val fieldTarget = parseFieldTarget(at.target)
        if (!inferTarget && fieldTarget.name == null) {
            throw IllegalArgumentException("@ModifyExpressionValue requires at.target field signature")
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

            val fieldType = Type.getType(insn.desc)
            if (inferTarget && !isHandlerCompatible(fieldType, allowThrowableSubtypeReturn = false)) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val targetParamCount = validateHandlerSignature(target, fieldType)
            val il = buildExpressionValueModification(target, fieldType, targetParamCount)
            target.instructions.insert(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 改写字段写入指令即将消费的待写入值。
     *
     * 显式声明字段目标时按字段 owner、名称与描述符匹配；未声明目标时按 handler 类型筛选兼容字段写入。
     * 注入逻辑插入在 `PUTFIELD` 或 `PUTSTATIC` 之前，让原字段写入继续消费 handler 返回的新值。
     *
     * @param target 目标方法
     * @return 实际改写的字段写入值数量
     * @throws IllegalArgumentException 字段目标缺少名称或 handler 签名不兼容时抛出
     */
    private fun injectFieldAssign(target: MethodNode): Int {
        val inferTarget = at.target.isEmpty()
        val fieldTarget = parseFieldTarget(at.target)
        if (!inferTarget && fieldTarget.name == null) {
            throw IllegalArgumentException("@ModifyExpressionValue FIELD_ASSIGN requires at.target field signature")
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

            val fieldType = Type.getType(insn.desc)
            if (inferTarget && !isHandlerCompatible(fieldType, allowThrowableSubtypeReturn = false)) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val targetParamCount = validateHandlerSignature(target, fieldType)
            val il = buildExpressionValueModification(target, fieldType, targetParamCount)
            target.instructions.insertBefore(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 改写数组元素读取表达式产生的元素值。
     *
     * 该模式通过 `array=get` 启用，并要求 `at.target` 指向数组字段。
     * 方法会从数组读取指令向前追踪数组字段来源，只改写来源字段匹配的元素读取结果。
     *
     * @param target 目标方法
     * @return 实际改写的数组元素读取值数量
     * @throws IllegalArgumentException 数组字段目标缺失或目标描述符不是数组类型时抛出
     */
    private fun injectArrayRead(target: MethodNode): Int {
        val fieldTarget = parseFieldTarget(at.target)
        if (fieldTarget.name == null) {
            throw IllegalArgumentException("@ModifyExpressionValue requires at.target array field signature")
        }
        if (fieldTarget.desc != null && Type.getType(fieldTarget.desc).sort != Type.ARRAY) {
            throw IllegalArgumentException("@ModifyExpressionValue array target must be an array field: ${at.target}")
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

            val expressionType = Type.getType(fieldInsn.desc).elementType
            val targetParamCount = validateHandlerSignature(target, expressionType)
            val il = buildExpressionValueModification(target, expressionType, targetParamCount)
            target.instructions.insert(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 改写数组长度表达式产生的 `Int` 值。
     *
     * 该模式通过 `array=length` 启用，并要求 `at.target` 指向数组字段。
     * 方法会从 `ARRAYLENGTH` 向前追踪数组字段来源，只改写来源字段匹配的长度结果。
     *
     * @param target 目标方法
     * @return 实际改写的数组长度表达式数量
     * @throws IllegalArgumentException 数组字段目标缺失或目标描述符不是数组类型时抛出
     */
    private fun injectArrayLength(target: MethodNode): Int {
        val fieldTarget = parseFieldTarget(at.target)
        if (fieldTarget.name == null) {
            throw IllegalArgumentException("@ModifyExpressionValue requires at.target array field signature")
        }
        if (fieldTarget.desc != null && Type.getType(fieldTarget.desc).sort != Type.ARRAY) {
            throw IllegalArgumentException("@ModifyExpressionValue array target must be an array field: ${at.target}")
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

            val targetParamCount = validateHandlerSignature(target, Type.INT_TYPE)
            val il = buildExpressionValueModification(target, Type.INT_TYPE, targetParamCount)
            target.instructions.insert(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 改写数组元素写入指令即将消费的待写入值。
     *
     * 该模式通过 `array=set` 启用，并要求 `at.target` 指向数组字段。
     * 注入逻辑插入在数组写入指令之前，让原写入指令继续消费 handler 返回的新元素值。
     *
     * @param target 目标方法
     * @return 实际改写的数组元素写入值数量
     * @throws IllegalArgumentException 数组字段目标缺失或目标描述符不是数组类型时抛出
     */
    private fun injectArrayWrite(target: MethodNode): Int {
        val fieldTarget = parseFieldTarget(at.target)
        if (fieldTarget.name == null) {
            throw IllegalArgumentException("@ModifyExpressionValue array write requires at.target array field signature")
        }
        if (fieldTarget.desc != null && Type.getType(fieldTarget.desc).sort != Type.ARRAY) {
            throw IllegalArgumentException("@ModifyExpressionValue array write target must be an array field: ${at.target}")
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

            val expressionType = Type.getType(fieldInsn.desc).elementType
            val targetParamCount = validateHandlerSignature(target, expressionType)
            val il = buildExpressionValueModification(target, expressionType, targetParamCount)
            target.instructions.insertBefore(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 改写对象构造表达式完成后的实例值。
     *
     * 显式声明类型目标时只匹配该类型的 `NEW`；未声明目标时按 handler 类型筛选兼容构造结果。
     * 注入逻辑插入在对应构造器调用之后，替换构造完成后留在栈顶的实例。
     *
     * @param target 目标方法
     * @return 实际改写的新对象表达式数量
     * @throws IllegalArgumentException 找不到 `NEW` 对应的构造器调用或 handler 签名不兼容时抛出
     */
    private fun injectNewObject(target: MethodNode): Int {
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

            val expressionType = Type.getObjectType(insn.desc)
            if (normalizedTarget.isEmpty() && !isHandlerCompatible(expressionType, allowThrowableSubtypeReturn = false)) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val constructorInsn = findConstructorInvocation(insn)
            val targetParamCount = validateHandlerSignature(target, expressionType)
            val il = buildExpressionValueModification(target, expressionType, targetParamCount)
            target.instructions.insert(constructorInsn, il)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 改写 `CHECKCAST` 表达式完成后的引用值。
     *
     * 显式声明类型目标时只匹配该 cast 类型；未声明目标时按 handler 类型筛选兼容 cast 结果。
     * 注入逻辑插入在 `CHECKCAST` 之后，替换转换完成后留在栈顶的引用。
     *
     * @param target 目标方法
     * @return 实际改写的类型转换表达式数量
     * @throws IllegalArgumentException handler 签名不兼容时抛出
     */
    private fun injectCast(target: MethodNode): Int {
        val normalizedTarget = at.target.replace('.', '/')
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
            if (normalizedTarget.isNotEmpty() && insn.desc != normalizedTarget) {
                continue
            }

            val expressionType = Type.getObjectType(insn.desc)
            if (normalizedTarget.isEmpty() && !isHandlerCompatible(expressionType, allowThrowableSubtypeReturn = false)) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val targetParamCount = validateHandlerSignature(target, expressionType)
            val il = buildExpressionValueModification(target, expressionType, targetParamCount)
            target.instructions.insert(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    private fun injectLoad(target: MethodNode): Int {
        require(at.target.isEmpty()) {
            "@ModifyExpressionValue LOAD uses At.args index=N or var=N for local variable slot filtering, not At.target"
        }
        val localVariableIndex = parseLocalVariableIndex("LOAD")
        val handlerExpressionType = requireHandlerExpressionArgumentType()
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
            if (!isLoadCompatibleWithHandler(insn.opcode, handlerExpressionType)) {
                continue
            }

            val resolvedExpressionType = resolveIndexedLocalExpressionType(target, insn.`var`, handlerExpressionType)
            if (localVariableIndex == null && handlerExpressionType.isReferenceType() && resolvedExpressionType == null) {
                continue
            }
            val expressionType = resolvedExpressionType ?: handlerExpressionType
            if (localVariableIndex == null && !isHandlerCompatible(expressionType, allowThrowableSubtypeReturn = false)) {
                continue
            }
            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val targetParamCount = validateHandlerSignature(target, expressionType)
            val il = buildExpressionValueModification(target, expressionType, targetParamCount)
            target.instructions.insert(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    private fun injectStore(target: MethodNode): Int {
        require(at.target.isEmpty()) {
            "@ModifyExpressionValue STORE uses At.args index=N or var=N for local variable slot filtering, not At.target"
        }
        val localVariableIndex = parseLocalVariableIndex("STORE")
        val handlerExpressionType = requireHandlerExpressionArgumentType()
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
            if (!isStoreCompatibleWithHandler(insn.opcode, handlerExpressionType)) {
                continue
            }

            val resolvedExpressionType = resolveIndexedLocalExpressionType(target, insn.`var`, handlerExpressionType)
            if (localVariableIndex == null && handlerExpressionType.isReferenceType() && resolvedExpressionType == null) {
                continue
            }
            val expressionType = resolvedExpressionType ?: handlerExpressionType
            if (localVariableIndex == null && !isHandlerCompatible(expressionType, allowThrowableSubtypeReturn = false)) {
                continue
            }
            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val targetParamCount = validateHandlerSignature(target, expressionType)
            val il = buildExpressionValueModification(target, expressionType, targetParamCount)
            target.instructions.insertBefore(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    private fun isHandlerCompatible(
        expressionType: Type,
        allowThrowableSubtypeReturn: Boolean,
    ): Boolean {
        val asmParamTypes = Type.getArgumentTypes(asmMethod)
        if (asmParamTypes.isEmpty() || !isHandlerParameterCompatible(expressionType, asmParamTypes[0])) {
            return false
        }
        return isHandlerReturnCompatible(expressionType, Type.getReturnType(asmMethod), allowThrowableSubtypeReturn)
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
            "@ModifyExpressionValue $pointName supports only one local variable slot filter in At.args"
        }

        val index =
            values.single().toIntOrNull()
                ?: throw IllegalArgumentException(
                    "@ModifyExpressionValue $pointName local variable slot filter must be an integer: ${values.single()}",
                )
        require(index >= 0) {
            "@ModifyExpressionValue $pointName local variable slot filter must be non-negative: $index"
        }
        return index
    }

    private fun requireHandlerExpressionArgumentType(): Type {
        val handlerParams = Type.getArgumentTypes(asmMethod)
        if (handlerParams.isEmpty()) {
            throw IllegalArgumentException(
                "@ModifyExpressionValue handler ${asmMethod.name} must take at least one argument for the original expression value",
            )
        }
        return handlerParams[0]
    }

    private fun resolveIndexedLocalExpressionType(
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

        val localVariable = target.localVariables
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
            if (inferTarget && !isHandlerCompatible(constantType, allowThrowableSubtypeReturn = false)) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val targetParamCount = validateHandlerSignature(target, constantType)
            val il = buildExpressionValueModification(target, constantType, targetParamCount)
            target.instructions.insert(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    private fun injectInstanceof(target: MethodNode): Int {
        val normalizedTarget = at.target.replace('.', '/')
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
            if (normalizedTarget.isNotEmpty() && insn.desc != normalizedTarget) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val targetParamCount = validateHandlerSignature(target, Type.BOOLEAN_TYPE)
            val il = buildExpressionValueModification(target, Type.BOOLEAN_TYPE, targetParamCount)
            target.instructions.insert(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    private fun injectJump(target: MethodNode): Int {
        val targetOpcode = parseJumpOpcodeTarget(at.target)
        if (targetOpcode != null && targetOpcode !in CONDITIONAL_JUMP_OPS) {
            throw IllegalArgumentException(
                "@ModifyExpressionValue JUMP target must be a conditional JVM jump opcode: ${at.target}",
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

            val targetParamCount = validateHandlerSignature(target, Type.BOOLEAN_TYPE)
            val il = buildJumpExpressionValueModification(insn, target, targetParamCount)
            target.instructions.insertBefore(insn, il)
            target.instructions.remove(insn)
            injectionCount++
        }

        return injectionCount
    }

    private fun buildJumpExpressionValueModification(
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
        il.add(buildExpressionValueModification(target, Type.BOOLEAN_TYPE, targetParamCount))
        il.add(JumpInsnNode(Opcodes.IFNE, jumpInsn.label))
        return il
    }

    private fun injectSwitch(target: MethodNode): Int {
        if (at.target.isNotEmpty()) {
            throw IllegalArgumentException("@ModifyExpressionValue SWITCH does not support at.target")
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

            val targetParamCount = validateHandlerSignature(target, Type.INT_TYPE)
            val il = buildExpressionValueModification(target, Type.INT_TYPE, targetParamCount)
            target.instructions.insertBefore(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    private fun injectThrow(target: MethodNode): Int {
        val normalizedTarget = at.target.replace('.', '/')
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
            if (normalizedTarget.isNotEmpty() && directThrownTypeInternalName(insn) != normalizedTarget) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val throwableType = Type.getType(Throwable::class.java)
            val targetParamCount = validateHandlerSignature(target, throwableType, allowThrowableSubtypeReturn = true)
            val il = buildExpressionValueModification(target, throwableType, targetParamCount)
            target.instructions.insertBefore(insn, il)
            injectionCount++
        }

        return injectionCount
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

    private fun buildExpressionValueModification(
        target: MethodNode,
        expressionType: Type,
        targetParamCount: Int,
    ): InsnList {
        val il = InsnList()
        val valueIndex = nextLocalIndex(target)

        storeStackValue(il, expressionType, valueIndex)
        addHandlerOwner(il)
        loadFromVariable(il, expressionType, valueIndex)
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
        addExpressionCastIfNeeded(il, expressionType)

        return il
    }

    private fun validateHandlerSignature(
        target: MethodNode,
        expressionType: Type,
        allowThrowableSubtypeReturn: Boolean = false,
    ): Int {
        val asmParamTypes = Type.getArgumentTypes(asmMethod)
        if (asmParamTypes.isEmpty() || !isHandlerParameterCompatible(expressionType, asmParamTypes[0])) {
            throw IllegalArgumentException(
                "@ModifyExpressionValue handler ${asmMethod.name} first parameter must be $expressionType " +
                    "or compatible Object/Any, " +
                    "actual ${asmParamTypes.toList()}",
            )
        }

        val asmReturnType = Type.getReturnType(asmMethod)
        if (!isHandlerReturnCompatible(expressionType, asmReturnType, allowThrowableSubtypeReturn)) {
            throw IllegalArgumentException(
                "@ModifyExpressionValue handler ${asmMethod.name} return type $asmReturnType " +
                    "must match expression type $expressionType",
            )
        }

        val targetParamTypes = Type.getArgumentTypes(target.desc)
        val requestedTargetParamCount = asmParamTypes.size - 1
        if (requestedTargetParamCount > targetParamTypes.size) {
            throw IllegalArgumentException(
                "@ModifyExpressionValue handler ${asmMethod.name} " +
                    "requests $requestedTargetParamCount target parameter(s), " +
                    "but target method ${target.name}${target.desc} has only ${targetParamTypes.size}",
            )
        }

        for (index in 0 until requestedTargetParamCount) {
            val expected = targetParamTypes[index]
            val actual = asmParamTypes[index + 1]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalArgumentException(
                    "@ModifyExpressionValue handler ${asmMethod.name} target parameter #$index mismatch: " +
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

    private fun isHandlerReturnCompatible(
        expressionType: Type,
        handlerReturnType: Type,
        allowThrowableSubtypeReturn: Boolean,
    ): Boolean {
        if (expressionType == handlerReturnType) {
            return true
        }
        if (handlerReturnType == Type.VOID_TYPE) {
            return false
        }
        if (allowThrowableSubtypeReturn && Throwable::class.java.isAssignableFrom(asmMethod.returnType)) {
            return true
        }
        if (!expressionType.isReferenceType() || !handlerReturnType.isReferenceType()) {
            return false
        }
        if (handlerReturnType.sort == Type.OBJECT &&
            (handlerReturnType.internalName == "java/lang/Object" || handlerReturnType.internalName == "kotlin/Any")
        ) {
            return true
        }
        return runCatching {
            val expressionClass = loadReferenceClass(expressionType)
            expressionClass.isAssignableFrom(asmMethod.returnType)
        }.getOrDefault(false)
    }

    private fun Type.isReferenceType(): Boolean = sort == Type.OBJECT || sort == Type.ARRAY

    private fun addExpressionCastIfNeeded(
        il: InsnList,
        expressionType: Type,
    ) {
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (expressionType != handlerReturnType && expressionType.isReferenceType()) {
            il.add(TypeInsnNode(Opcodes.CHECKCAST, expressionType.internalName))
        }
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

        throw IllegalArgumentException("@ModifyExpressionValue cannot find constructor call for NEW ${newInsn.desc}")
    }

    private fun previousRealInstruction(insn: AbstractInsnNode): AbstractInsnNode? {
        var current = insn.previous
        while (current != null && current.opcode < 0) {
            current = current.previous
        }
        return current
    }

    private fun nextRealInstruction(insn: AbstractInsnNode): AbstractInsnNode? {
        var current = insn.next
        while (current != null && current.opcode < 0) {
            current = current.next
        }
        return current
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
            "Only INVOKE slice boundaries are supported for @ModifyExpressionValue: ${at.value}"
        }

        val (boundaryOwner, boundaryName, boundaryDesc) = parseTargetMethod(at.target)
        if (boundaryName == null || boundaryDesc == null) {
            throw IllegalArgumentException(
                "Invalid ModifyExpressionValue slice boundary method signature: ${at.target} " +
                    "(parsed: owner=$boundaryOwner, name=$boundaryName, desc=$boundaryDesc)",
            )
        }

        for (index in startIndex until insns.size) {
            val insn = insns[index]
            if (insn is MethodInsnNode && matchesTargetMethod(insn, boundaryOwner, boundaryName, boundaryDesc)) {
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

    private fun parseJumpOpcodeTarget(target: String): Int? {
        if (target.isEmpty()) {
            return null
        }

        val normalized = target.trim().uppercase()
        normalized.toIntOrNull()?.let { opcode ->
            require(opcode in JUMP_OPS) {
                "@ModifyExpressionValue JUMP target opcode must be a JVM jump opcode: $target"
            }
            return opcode
        }

        return JUMP_OPCODE_NAMES[normalized]
            ?: throw IllegalArgumentException(
                "@ModifyExpressionValue JUMP target must be a jump opcode name or number: $target",
            )
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

    private fun callName(insn: AbstractInsnNode): String =
        when (insn) {
            is MethodInsnNode -> insn.name
            is InvokeDynamicInsnNode -> insn.name
            else -> "<unknown>"
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
                            "@ModifyExpressionValue array target must be an array field: ${cursor.owner}.${cursor.name}:${cursor.desc}",
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

    private data class LocalSlotType(
        val index: Int,
        val type: Type,
    )

    private enum class ArrayAccessMode {
        NONE,
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
