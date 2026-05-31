/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.injector.impl

import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.At
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.Shift
import kim.der.asm.api.annotation.Slice
import kim.der.asm.data.AsmInfo
import kim.der.asm.injector.AbstractAsmInjector
import kim.der.asm.injector.util.AsmMethodCallGenerator
import kim.der.asm.utils.transformer.BytecodeUtil
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LookupSwitchInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TableSwitchInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import java.lang.reflect.Method

/**
 * 指令点注入器。
 *
 * 用于处理字段访问、字段赋值、局部变量读写、对象创建、类型转换、类型判断、跳转、switch、常量与抛异常等单条字节码指令附近的普通 `@AsmInject`。
 * 当前实现只负责在匹配指令前后插入 ASM 方法调用，不替换原始指令，也不向 handler 传递栈顶操作数。
 * 普通 [InjectionPoint.FIELD] / [InjectionPoint.FIELD_ASSIGN] / [InjectionPoint.LOAD] / [InjectionPoint.STORE] /
 * [InjectionPoint.NEW] / [InjectionPoint.CAST] / [InjectionPoint.INSTANCEOF] / [InjectionPoint.JUMP] /
 * [InjectionPoint.SWITCH] / [InjectionPoint.CONSTANT] / [InjectionPoint.THROW] 可使用 `Slice` 的 [InjectionPoint.INVOKE]
 * 边界缩小候选指令查找范围，边界可匹配普通方法调用、构造器调用或 `invokedynamic` 调用；
 * 也可通过 `At.by` 按真实字节码指令数移动插入锚点；LOAD/STORE 还可通过 `At.args` 中的
 * `index=N` 或 `var=N` 限制 JVM 局部变量槽位；JUMP 指定 `At.target` 时按跳转操作码名或数字过滤，SWITCH 不支持 `At.target`，CONSTANT
 * 指定 `At.target` 时按常量文本过滤，THROW 指定 `At.target`
 * 时只匹配 `ATHROW` 前直接构造出的同类型异常。
 * [InjectionPoint.CONSTANT] 搭配 [Shift.REPLACE] 时会删除原常量加载指令，并用 handler 返回值作为新的常量表达式值；
 * 该模式不会把原常量值传给 handler，handler 仍只接收可选 [CallbackInfo] 与目标方法参数前缀。
 * 对象创建指令点仍不支持 `At.by`。
 * 由于 JVM verifier 不允许在未初始化对象仍位于栈顶时插入普通方法调用，[InjectionPoint.NEW] 不支持 [Shift.AFTER]。
 *
 * @param method ASM 方法
 * @param asmInfo ASM 信息
 * @param point 要处理的注入点
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
class InstructionPointInjector(
    method: Method,
    asmInfo: AsmInfo,
    private val point: InjectionPoint,
) : AbstractAsmInjector(method, asmInfo) {
    /**
     * 在匹配的目标指令附近插入 ASM 方法调用。
     *
     * @param target 目标方法
     * @return 至少匹配并改写一个指令点时返回 `true`
     * @throws IllegalStateException 注入方法参数无法映射到目标方法参数，或 NEW 注入使用 AFTER 偏移时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun inject(target: MethodNode): Boolean = injectCount(target) > 0

    /**
     * 在目标方法中执行指令点注入并返回命中数量。
     *
     * 会先构造当前 [point] 对应的指令匹配器，再按 `Slice`、`ordinal`、`Shift` 与 `At.by` 计算实际插入位置。
     * `CONSTANT + Shift.REPLACE` 是唯一替换原指令的普通 `@AsmInject` 模式，其余模式只插入 handler 调用。
     *
     * @param target 目标方法
     * @return 实际插入或替换的指令点数量
     * @throws IllegalStateException shift、`At.by`、slice 或 handler 参数不满足当前注入点约束时抛出
     */
    override fun injectCount(target: MethodNode): Int {
        val injectAnnotation = asmMethod.getAnnotation(AsmInject::class.java) ?: return 0
        requireSupportedShift(injectAnnotation.at.shift, injectAnnotation.at.by)
        val localVariableIndex = parseLocalVariableIndex(injectAnnotation.at.args)
        val matcher = buildMatcher(injectAnnotation.at.target, localVariableIndex)
        val instructions = target.instructions
        val insns = instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) =
            if (usesSliceRange()) {
                resolveSliceRange(insns, injectAnnotation.slice)
            } else {
                0 to insns.size
            }
        var injectionCount = 0
        var matchedOrdinal = 0

        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (!matcher(insn)) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal, injectAnnotation.ordinal)) {
                continue
            }

            when {
                injectAnnotation.at.shift == Shift.REPLACE && point == InjectionPoint.CONSTANT -> {
                    require(injectAnnotation.at.by == 0) {
                        "@AsmInject CONSTANT Shift.REPLACE does not support At.by because replacement must remove the matched constant"
                    }
                    val replacementCall = buildConstantReplacementHandlerCall(target, insn, injectAnnotation.at.target)
                    instructions.insertBefore(insn, replacementCall)
                    instructions.remove(insn)
                }
                else -> {
                    val il = buildHandlerCall(target)
                    val anchor = resolveByAnchor(insns, index, injectAnnotation.at.by)
                    when (injectAnnotation.at.shift) {
                        Shift.BEFORE, Shift.REPLACE -> instructions.insertBefore(anchor, il)
                        Shift.AFTER -> instructions.insert(anchor, il)
                    }
                }
            }
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 判断当前指令点是否支持 `Slice` 范围限制。
     *
     * @return 当前 [point] 会在候选指令扫描前解析 `Slice` 时返回 `true`
     */
    private fun usesSliceRange(): Boolean =
        point == InjectionPoint.LOAD ||
            point == InjectionPoint.STORE ||
            point == InjectionPoint.FIELD ||
            point == InjectionPoint.FIELD_ASSIGN ||
            point == InjectionPoint.NEW ||
            point == InjectionPoint.CAST ||
            point == InjectionPoint.INSTANCEOF ||
            point == InjectionPoint.JUMP ||
            point == InjectionPoint.SWITCH ||
            point == InjectionPoint.CONSTANT ||
            point == InjectionPoint.THROW

    /**
     * 判断当前匹配序号是否满足 `ordinal` 过滤。
     *
     * 负数表示不限制序号，否则只允许指定的第 N 个候选命中。
     *
     * @param currentOrdinal 当前候选在匹配集合中的序号
     * @param requestedOrdinal 注解声明的目标序号
     * @return 当前候选应被注入时返回 `true`
     */
    private fun matchesOrdinal(
        currentOrdinal: Int,
        requestedOrdinal: Int,
    ): Boolean = requestedOrdinal < 0 || currentOrdinal == requestedOrdinal

    /**
     * 校验当前指令点是否支持声明的 `Shift` 与 `At.by`。
     *
     * `NEW` 指令附近存在未初始化对象引用，不能向后插入，也不能通过 `At.by` 移动锚点。
     *
     * @param shift 注解声明的插入方向
     * @param by 相对匹配指令移动的真实指令数
     * @throws IllegalStateException 当前指令点不支持该组合时抛出
     */
    private fun requireSupportedShift(
        shift: Shift,
        by: Int,
    ) {
        if (point == InjectionPoint.NEW && shift == Shift.AFTER) {
            throw IllegalStateException(
                "InjectionPoint.NEW does not support Shift.AFTER because inserting after NEW leaves " +
                    "an uninitialized object on the stack; use Shift.BEFORE or Shift.REPLACE",
            )
        }
        if (point == InjectionPoint.NEW && by != 0) {
            throw IllegalStateException(
                "InjectionPoint.NEW does not support At.by because moving from NEW can leave an uninitialized " +
                    "object on the stack",
            )
        }
    }

    /**
     * 根据 `At.by` 解析最终插入锚点。
     *
     * 移动时只统计真实字节码指令，跳过 label、line number、frame 等伪节点。
     *
     * @param insns 目标方法指令快照
     * @param index 原始匹配指令下标
     * @param by 相对移动步数；正数向后，负数向前
     * @return 移动后的锚点指令
     * @throws IllegalArgumentException `by` 为 [Int.MIN_VALUE] 时无法取反
     * @throws IllegalStateException 移动结果越出方法指令范围时抛出
     */
    private fun resolveByAnchor(
        insns: Array<AbstractInsnNode>,
        index: Int,
        by: Int,
    ): AbstractInsnNode {
        if (by == 0) {
            return insns[index]
        }
        val steps =
            if (by > 0) {
                by
            } else if (by > Int.MIN_VALUE) {
                -by
            } else {
                throw IllegalArgumentException("@AsmInject ${point.name} At.by is too small: $by")
            }
        val direction = if (by > 0) 1 else -1
        var currentIndex = index

        repeat(steps) {
            currentIndex =
                nextRealInstructionIndex(insns, currentIndex, direction)
                    ?: throw IllegalStateException(
                        "@AsmInject ${point.name} At.by offset $by moves outside method instructions",
                    )
        }

        return insns[currentIndex]
    }

    /**
     * 查找指定方向上的下一条真实指令下标。
     *
     * @param insns 目标方法指令快照
     * @param index 起始指令下标
     * @param direction 搜索方向，`1` 向后，`-1` 向前
     * @return 找到的真实指令下标；越界前未找到时返回 `null`
     */
    private fun nextRealInstructionIndex(
        insns: Array<AbstractInsnNode>,
        index: Int,
        direction: Int,
    ): Int? {
        var candidate = index + direction
        while (candidate in insns.indices) {
            if (insns[candidate].opcode >= 0) {
                return candidate
            }
            candidate += direction
        }
        return null
    }

    /**
     * 解析当前注入的候选扫描范围。
     *
     * `from` 边界命中后从下一条指令开始扫描，`to` 边界命中前结束扫描；
     * 任一边界找不到时返回空范围。
     *
     * @param insns 目标方法指令快照
     * @param slice 注解声明的切片范围
     * @return 左闭右开的候选扫描下标范围
     */
    private fun resolveSliceRange(
        insns: Array<AbstractInsnNode>,
        slice: Slice,
    ): Pair<Int, Int> {
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
     * 判断切片边界是否声明了可匹配目标。
     *
     * @param at 切片边界注入点
     * @return 边界 `target` 非空时返回 `true`
     */
    private fun hasSliceBoundary(at: At): Boolean = at.target.isNotEmpty()

    /**
     * 构造空切片范围。
     *
     * @param insns 目标方法指令快照
     * @return 位于指令末尾的空范围
     */
    private fun emptySlice(insns: Array<AbstractInsnNode>): Pair<Int, Int> = insns.size to insns.size

    /**
     * 查找 `Slice` 边界方法调用下标。
     *
     * 当前普通指令点只支持以 [InjectionPoint.INVOKE] 作为切片边界，边界可匹配普通方法调用或 `invokedynamic`。
     *
     * @param insns 目标方法指令快照
     * @param at 切片边界声明
     * @param startIndex 起始扫描下标
     * @return 边界命中的指令下标；未找到时返回 `null`
     * @throws IllegalArgumentException 边界不是 `INVOKE` 或目标签名不完整时抛出
     */
    private fun findSliceBoundaryIndex(
        insns: Array<AbstractInsnNode>,
        at: At,
        startIndex: Int,
    ): Int? {
        require(at.value == InjectionPoint.INVOKE) {
            "Only INVOKE slice boundaries are supported for @AsmInject instruction points: ${at.value}"
        }

        val (boundaryOwner, boundaryName, boundaryDesc) = parseTargetMethod(at.target)
        if (boundaryName == null || boundaryDesc == null) {
            throw IllegalArgumentException(
                "Invalid @AsmInject instruction point slice boundary method signature: ${at.target} " +
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

    /**
     * 解析 `LOAD` / `STORE` 指令点的局部变量槽位过滤条件。
     *
     * 支持在 `At.args` 中使用 `index=<n>` 或 `var=<n>`，其他注入点会忽略该过滤。
     *
     * @param args 注解声明的 `At.args`
     * @return 指定的局部变量槽位；未声明或当前不是局部变量指令点时返回 `null`
     * @throws IllegalArgumentException 槽位不是整数、为负数或重复声明时抛出
     */
    private fun parseLocalVariableIndex(args: Array<String>): Int? {
        if (point != InjectionPoint.LOAD && point != InjectionPoint.STORE) {
            return null
        }

        val values =
            args.mapNotNull { arg ->
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
            "@AsmInject ${point.name} supports only one local variable slot filter in At.args"
        }

        val index =
            values.single().toIntOrNull()
                ?: throw IllegalArgumentException(
                    "@AsmInject ${point.name} local variable slot filter must be an integer: ${values.single()}",
                )
        require(index >= 0) {
            "@AsmInject ${point.name} local variable slot filter must be non-negative: $index"
        }
        return index
    }

    /**
     * 构造当前指令点的候选指令匹配器。
     *
     * 匹配器会把 `At.target` 解析为字段、类型、跳转 opcode、常量文本或异常类型约束；
     * `LOAD` / `STORE` 还会叠加局部变量槽位过滤。
     *
     * @param target 注解声明的 `At.target`
     * @param localVariableIndex `LOAD` / `STORE` 的局部变量槽位过滤
     * @return 用于筛选候选指令的谓词
     * @throws IllegalArgumentException 当前指令点不支持声明的目标格式时抛出
     */
    private fun buildMatcher(
        target: String,
        localVariableIndex: Int?,
    ): (AbstractInsnNode) -> Boolean {
        return when (point) {
            InjectionPoint.FIELD -> {
                val fieldTarget = parseFieldTarget(target)
                fun(insn: AbstractInsnNode): Boolean =
                    insn is FieldInsnNode &&
                        insn.opcode in FIELD_READ_OPS &&
                        matchesField(insn, fieldTarget)
            }
            InjectionPoint.FIELD_ASSIGN -> {
                val fieldTarget = parseFieldTarget(target)
                fun(insn: AbstractInsnNode): Boolean =
                    insn is FieldInsnNode &&
                        insn.opcode in FIELD_WRITE_OPS &&
                        matchesField(insn, fieldTarget)
            }
            InjectionPoint.NEW -> {
                val normalizedTarget = target.replace('.', '/')
                fun(insn: AbstractInsnNode): Boolean =
                    insn is TypeInsnNode &&
                        insn.opcode == Opcodes.NEW &&
                        (normalizedTarget.isEmpty() || insn.desc == normalizedTarget)
            }
            InjectionPoint.CAST -> {
                val normalizedTarget = target.replace('.', '/')
                fun(insn: AbstractInsnNode): Boolean =
                    insn is TypeInsnNode &&
                        insn.opcode == Opcodes.CHECKCAST &&
                        (normalizedTarget.isEmpty() || insn.desc == normalizedTarget)
            }
            InjectionPoint.INSTANCEOF -> {
                val normalizedTarget = target.replace('.', '/')
                fun(insn: AbstractInsnNode): Boolean =
                    insn is TypeInsnNode &&
                        insn.opcode == Opcodes.INSTANCEOF &&
                        (normalizedTarget.isEmpty() || insn.desc == normalizedTarget)
            }
            InjectionPoint.JUMP -> {
                val targetOpcode = parseJumpOpcodeTarget(target)
                fun(insn: AbstractInsnNode): Boolean =
                    insn is JumpInsnNode &&
                        (targetOpcode == null || insn.opcode == targetOpcode)
            }
            InjectionPoint.SWITCH -> {
                require(target.isEmpty()) {
                    "@AsmInject SWITCH does not support At.target"
                }
                fun(insn: AbstractInsnNode): Boolean =
                    insn is TableSwitchInsnNode || insn is LookupSwitchInsnNode
            }
            InjectionPoint.CONSTANT -> {
                fun(insn: AbstractInsnNode): Boolean =
                    BytecodeUtil.isConstant(insn) &&
                        (target.isEmpty() || BytecodeUtil.matchesConstantText(insn, target))
            }
            InjectionPoint.LOAD -> {
                fun(insn: AbstractInsnNode): Boolean =
                    insn is VarInsnNode &&
                        insn.opcode in LOAD_OPS &&
                        matchesLocalVariableIndex(insn, localVariableIndex)
            }
            InjectionPoint.STORE -> {
                fun(insn: AbstractInsnNode): Boolean =
                    insn is VarInsnNode &&
                        insn.opcode in STORE_OPS &&
                        matchesLocalVariableIndex(insn, localVariableIndex)
            }
            InjectionPoint.THROW -> {
                val normalizedTarget = target.replace('.', '/')
                fun(insn: AbstractInsnNode): Boolean =
                    insn.opcode == Opcodes.ATHROW &&
                        (normalizedTarget.isEmpty() || directThrownTypeInternalName(insn) == normalizedTarget)
            }
            else -> {
                fun(_: AbstractInsnNode): Boolean = false
            }
        }
    }

    /**
     * 解析跳转指令点的 opcode 过滤条件。
     *
     * 目标可声明为 JVM 跳转 opcode 数值或名称；空目标表示不按 opcode 过滤。
     *
     * @param target `At.target` 中声明的跳转 opcode 目标
     * @return 解析出的跳转 opcode；未声明目标时返回 `null`
     * @throws IllegalArgumentException 目标不是受支持的跳转 opcode 时抛出
     */
    private fun parseJumpOpcodeTarget(target: String): Int? {
        if (target.isEmpty()) {
            return null
        }

        val normalized = target.trim().uppercase()
        normalized.toIntOrNull()?.let { opcode ->
            require(opcode in JUMP_OPS) {
                "@AsmInject JUMP target opcode must be a JVM jump opcode: $target"
            }
            return opcode
        }

        return JUMP_OPCODE_NAMES[normalized]
            ?: throw IllegalArgumentException("@AsmInject JUMP target must be a jump opcode name or number: $target")
    }

    /**
     * 判断局部变量指令是否满足槽位过滤条件。
     *
     * @param insn 候选局部变量读写指令
     * @param requestedIndex 注解声明的槽位过滤；为 `null` 时不限制槽位
     * @return 候选指令槽位满足过滤条件时返回 `true`
     */
    private fun matchesLocalVariableIndex(
        insn: VarInsnNode,
        requestedIndex: Int?,
    ): Boolean = requestedIndex == null || insn.`var` == requestedIndex

    /**
     * 推断直接抛出的异常构造类型。
     *
     * 仅在 `ATHROW` 前一条真实指令是构造器调用时返回 owner，用于按 `At.target` 匹配 `THROW`。
     *
     * @param throwInsn 候选 `ATHROW` 指令
     * @return 直接构造并抛出的异常 internal name；无法确认时返回 `null`
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
     * 查找上一条真实字节码指令。
     *
     * 会跳过 label、line number、frame 等 `opcode < 0` 的伪节点。
     *
     * @param insn 起始指令节点
     * @return 上一条真实指令；不存在时返回 `null`
     */
    private fun previousRealInstruction(insn: AbstractInsnNode): AbstractInsnNode? {
        var current = insn.previous
        while (current != null && current.opcode < 0) {
            current = current.previous
        }
        return current
    }

    /**
     * 构造普通指令点注入的 handler 调用指令。
     *
     * 如 handler 需要 [CallbackInfo]，会先创建并暂存 callback；handler 非 `void` 返回值会被丢弃。
     *
     * @param target 目标方法
     * @return 可插入到匹配指令附近的 handler 调用指令列表
     */
    private fun buildHandlerCall(target: MethodNode): InsnList {
        val il = InsnList()
        val callbackVarIndex =
            if (AsmMethodCallGenerator.needsCallbackInfo(asmMethod)) {
                AsmMethodCallGenerator.generateCallbackInfoCreation(il)
                allocateLocalVariable(target, Type.getType(CallbackInfo::class.java)).also {
                    il.add(VarInsnNode(Opcodes.ASTORE, it))
                }
            } else {
                null
            }

        AsmMethodCallGenerator.generateMethodCall(il, asmMethod, asmInfo, target, callbackVarIndex)

        if (Type.getReturnType(asmMethod) != Type.VOID_TYPE) {
            AsmMethodCallGenerator.generatePopReturnValue(il, asmMethod)
        }

        return il
    }

    /**
     * 构造 `CONSTANT + Shift.REPLACE` 的替换 handler 调用指令。
     *
     * 该模式会用 handler 返回值替代原常量加载结果，并在引用类型需要时补充 `CHECKCAST`。
     *
     * @param target 目标方法
     * @param constantInsn 被替换的常量加载指令
     * @param constantTarget 注解声明的常量匹配文本
     * @return 产生替代常量值的指令列表
     * @throws IllegalStateException 常量类型无法解析、handler 返回 `void` 或返回值不兼容时抛出
     */
    private fun buildConstantReplacementHandlerCall(
        target: MethodNode,
        constantInsn: AbstractInsnNode,
        constantTarget: String,
    ): InsnList {
        val constantType =
            resolveConstantReplacementType(constantInsn, constantTarget)
                ?: throw IllegalStateException("@AsmInject CONSTANT Shift.REPLACE matched unsupported constant instruction")
        val handlerReturnType = Type.getReturnType(asmMethod)
        require(handlerReturnType != Type.VOID_TYPE) {
            "@AsmInject CONSTANT Shift.REPLACE handler ${asmMethod.name} must return replacement value for $constantType"
        }
        require(isConstantReplacementReturnCompatible(constantType, handlerReturnType)) {
            "@AsmInject CONSTANT Shift.REPLACE handler ${asmMethod.name} return type mismatch: " +
                "original $constantType, handler $handlerReturnType"
        }

        val il = InsnList()
        val callbackVarIndex =
            if (AsmMethodCallGenerator.needsCallbackInfo(asmMethod)) {
                AsmMethodCallGenerator.generateCallbackInfoCreation(il)
                allocateLocalVariable(target, Type.getType(CallbackInfo::class.java)).also {
                    il.add(VarInsnNode(Opcodes.ASTORE, it))
                }
            } else {
                null
            }

        AsmMethodCallGenerator.generateMethodCall(il, asmMethod, asmInfo, target, callbackVarIndex)
        addConstantReplacementCastIfNeeded(il, constantInsn, constantType, handlerReturnType)
        return il
    }

    /**
     * 解析常量替换位置的原常量类型。
     *
     * 布尔字面量目标会把 `ICONST_0` / `ICONST_1` 识别为 [Type.BOOLEAN_TYPE]；
     * 其他常量交给 [BytecodeUtil.getConstantType] 推断。
     *
     * @param constantInsn 被匹配的常量加载指令
     * @param constantTarget 注解声明的常量匹配文本
     * @return 可替换的常量类型；不支持的常量指令返回 `null`
     */
    private fun resolveConstantReplacementType(
        constantInsn: AbstractInsnNode,
        constantTarget: String,
    ): Type? {
        if (isBooleanLiteral(constantTarget) && isBooleanConstantInsn(constantInsn, constantTarget == "true")) {
            return Type.BOOLEAN_TYPE
        }
        return BytecodeUtil.getConstantType(constantInsn)
    }

    /**
     * 判断常量替换 handler 返回值是否能替代原常量类型。
     *
     * 基本类型要求完全一致；引用类型允许原常量或 handler 返回值为通用对象类型，
     * 否则通过运行时类可赋值关系判断。
     *
     * @param constantType 原常量类型
     * @param handlerReturnType handler 返回类型
     * @return handler 返回值可作为替换常量使用时返回 `true`
     */
    private fun isConstantReplacementReturnCompatible(
        constantType: Type,
        handlerReturnType: Type,
    ): Boolean {
        if (constantType == handlerReturnType) {
            return true
        }
        if (handlerReturnType == Type.VOID_TYPE) {
            return false
        }
        if (!constantType.isReferenceType() || !handlerReturnType.isReferenceType()) {
            return false
        }
        if (constantType.isGenericObjectType()) {
            return true
        }
        if (handlerReturnType.isGenericObjectType()) {
            return true
        }
        return runCatching {
            val constantClass = loadReferenceClass(constantType)
            constantClass.isAssignableFrom(asmMethod.returnType)
        }.getOrDefault(false)
    }

    /**
     * 必要时为常量替换返回值追加类型转换。
     *
     * `null` 常量和通用对象常量以 handler 返回类型为准；其他引用常量会转回原常量类型。
     *
     * @param il 正在构造的替换指令列表
     * @param constantInsn 被替换的常量加载指令
     * @param constantType 原常量类型
     * @param handlerReturnType handler 返回类型
     */
    private fun addConstantReplacementCastIfNeeded(
        il: InsnList,
        constantInsn: AbstractInsnNode,
        constantType: Type,
        handlerReturnType: Type,
    ) {
        val replacementType =
            if (constantInsn.opcode == Opcodes.ACONST_NULL || constantType.isGenericObjectType()) {
                handlerReturnType
            } else {
                constantType
            }
        if (replacementType != handlerReturnType && replacementType.isReferenceType()) {
            il.add(TypeInsnNode(Opcodes.CHECKCAST, replacementType.internalName))
        }
    }

    /**
     * 判断 ASM 类型是否为 JVM 引用类型。
     *
     * @return 当前类型是对象或数组类型时返回 `true`
     */
    private fun Type.isReferenceType(): Boolean = sort == Type.OBJECT || sort == Type.ARRAY

    /**
     * 判断 ASM 类型是否为通用对象类型。
     *
     * @return 当前类型是 `java.lang.Object` 或 `kotlin.Any` 时返回 `true`
     */
    private fun Type.isGenericObjectType(): Boolean =
        sort == Type.OBJECT && (internalName == "java/lang/Object" || internalName == "kotlin/Any")

    /**
     * 判断文本是否为布尔字面量。
     *
     * @param value 待检查文本
     * @return 文本为 `true` 或 `false` 时返回 `true`
     */
    private fun isBooleanLiteral(value: String): Boolean = value == "true" || value == "false"

    /**
     * 判断整数常量指令是否表示指定布尔值。
     *
     * @param insn 候选常量加载指令
     * @param value 期望布尔值
     * @return 指令是对应布尔值的 `ICONST_0` 或 `ICONST_1` 时返回 `true`
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
     * 按 ASM 引用类型加载对应的运行时 [Class]。
     *
     * 数组类型使用 descriptor 转 Java 类名，对象类型使用 [Type.getClassName]；
     * 类加载器优先取当前 Mixin 类加载器。
     *
     * @param type 待加载的对象或数组类型
     * @return 对应的运行时类
     * @throws ClassNotFoundException 类型无法由当前类加载器解析时抛出
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
     * 解析字段指令点的目标约束。
     *
     * 支持 `owner.name:desc`、`owner/name:desc`、`name:desc` 与仅字段名形式；
     * owner 会统一转换为 JVM internal name，空目标表示不限制字段。
     *
     * @param target `At.target` 中声明的字段目标
     * @return 解析后的字段目标约束
     */
    private fun parseFieldTarget(target: String): FieldTarget {
        if (target.isEmpty()) {
            return FieldTarget(null, null, null)
        }

        val colonIndex = target.indexOf(':')
        val ownerAndName = if (colonIndex >= 0) target.substring(0, colonIndex) else target
        val desc = if (colonIndex >= 0) target.substring(colonIndex + 1) else null
        val lastDot = ownerAndName.lastIndexOf('.')
        val lastSlash = ownerAndName.lastIndexOf('/')
        val separator = maxOf(lastDot, lastSlash)

        return if (separator >= 0) {
            FieldTarget(
                owner = ownerAndName.substring(0, separator).replace('.', '/'),
                name = ownerAndName.substring(separator + 1),
                desc = desc,
            )
        } else {
            FieldTarget(owner = null, name = ownerAndName, desc = desc)
        }
    }

    /**
     * 判断字段指令是否满足目标约束。
     *
     * @param insn 候选字段指令
     * @param target 字段目标约束
     * @return 候选字段满足 owner、name 与 descriptor 约束时返回 `true`
     */
    private fun matchesField(
        insn: FieldInsnNode,
        target: FieldTarget,
    ): Boolean {
        if (target.owner != null && insn.owner != target.owner) {
            return false
        }
        if (target.name != null && insn.name != target.name) {
            return false
        }
        if (target.desc != null && insn.desc != target.desc) {
            return false
        }
        return true
    }

    /**
     * 解析方法调用目标签名。
     *
     * 支持 owner 使用 slash 或 dot，也支持只声明方法名或方法名加描述符。
     *
     * @param signature `At.target` 中声明的方法目标
     * @return owner、方法名与描述符；未声明的部分返回 `null`
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
     * @param targetName 目标动态调用名或 bootstrap method 名
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
     * 计算可用于临时值的局部变量槽位。
     *
     * 结果会覆盖方法参数、调试局部变量表和现有局部变量读写指令已使用的最高槽位；
     * 双槽类型会额外向后移动一个槽位以避开宽类型占用。
     *
     * @param target 目标方法
     * @param type 待暂存值类型
     * @return 可用于写入临时值的局部变量槽位
     */
    private fun allocateLocalVariable(
        target: MethodNode,
        type: Type,
    ): Int {
        var varIndex = if ((target.access and Opcodes.ACC_STATIC) != 0) 0 else 1

        for (paramType in Type.getArgumentTypes(target.desc)) {
            varIndex += paramType.size
        }

        for (localVar in target.localVariables) {
            val size = Type.getType(localVar.desc).size
            varIndex = maxOf(varIndex, localVar.index + size)
        }

        for (insn in target.instructions.toArray()) {
            if (insn is VarInsnNode) {
                val size =
                    when (insn.opcode) {
                        Opcodes.LLOAD, Opcodes.LSTORE, Opcodes.DLOAD, Opcodes.DSTORE -> 2
                        else -> 1
                    }
                varIndex = maxOf(varIndex, insn.`var` + size)
            }
        }

        return varIndex + if (type.size == 2) 1 else 0
    }

    /**
     * 字段指令点的目标匹配条件。
     *
     * @property owner 字段 owner 的 JVM internal name；为 `null` 时不限制 owner
     * @property name 字段名；为 `null` 时不限制名称
     * @property desc 字段描述符；为 `null` 时不限制类型
     */
    private data class FieldTarget(
        val owner: String?,
        val name: String?,
        val desc: String?,
    )

    /**
     * `InstructionPointInjector` 共用的 opcode 集合与名称索引。
     */
    private companion object {
        /** 字段读取指令集合。 */
        private val FIELD_READ_OPS = setOf(Opcodes.GETFIELD, Opcodes.GETSTATIC)

        /** 字段写入指令集合。 */
        private val FIELD_WRITE_OPS = setOf(Opcodes.PUTFIELD, Opcodes.PUTSTATIC)

        /** 局部变量读取指令集合。 */
        private val LOAD_OPS = setOf(Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD, Opcodes.ALOAD)

        /** 局部变量写入指令集合。 */
        private val STORE_OPS = setOf(Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE, Opcodes.ASTORE)

        /** 支持在 `At.target` 中按名称声明的 JVM 跳转 opcode。 */
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

        /** 所有可由跳转指令点识别的 JVM 跳转 opcode。 */
        private val JUMP_OPS = JUMP_OPCODE_NAMES.values.toSet()
    }
}
