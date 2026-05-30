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
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LocalVariableNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * ModifyVariable 注入器。
 *
 * 当前实现支持在方法入口修改已有参数槽位，也支持在局部变量读取前或写入指令后改写槽位值。
 * 优先按 JVM 局部变量槽位索引定位；指定变量名时，会继续按 LocalVariableTable 中的局部变量名过滤候选。
 * 未显式指定槽位时，可按 handler 参数类型与 [ordinal] 选择同类型参数、读取点或写入点。
 * HEAD 模式未指定槽位与序号时，仅在同类型入口参数唯一时自动推断该参数；多候选仍需显式指定 [ordinal]。
 * 变量名过滤依赖目标字节码保留调试变量表，缺少 LocalVariableTable 时不会匹配名称限定的候选。
 * handler 第一个参数接收原变量值；显式指定槽位时，对象或数组变量可声明为原值类型的父类、接口、`Any` 或 `Object` 接收，
 * 返回类型对基础类型仍需精确匹配，对象或数组类型可返回可赋值给原变量类型的子类型，也可用 `Any` 或 `Object`
 * 作为泛型引用返回类型。后续可按顺序接收目标方法参数前缀；
 * 调用后会把返回的新值写回同一个槽位。显式槽位的引用变量会尽量通过目标方法参数、局部变量表与相邻字节码用途恢复真实槽位类型，
 * 避免 handler 使用 `Any` / `Object` 接收时把局部变量栈图退化为 `Object`。
 *
 * @param injectionPoint 修改位置；当前支持 [InjectionPoint.HEAD]、[InjectionPoint.LOAD] 与 [InjectionPoint.STORE]
 * @param variableIndex 要修改的 JVM 局部变量槽位索引
 * @param variableNames 要匹配的局部变量名；为空时不按名称过滤
 * @param ordinal 未指定 [variableIndex] 时，同类型入口参数、读取点或写入点的序号
 * @param slice 切片范围；[InjectionPoint.LOAD] 与 [InjectionPoint.STORE] 使用 INVOKE 边界缩小匹配范围，
 * 边界可匹配普通方法调用、构造器调用或 `invokedynamic` 调用
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
class ModifyVariableInjector(
    method: Method,
    asmInfo: AsmInfo,
    private val injectionPoint: InjectionPoint,
    private val variableIndex: Int,
    variableNames: Array<String> = emptyArray(),
    private val ordinal: Int,
    private val slice: Slice = Slice(),
) : AbstractAsmInjector(method, asmInfo) {
    private val requestedVariableNames = variableNames.filterTo(linkedSetOf()) { it.isNotBlank() }

    /**
     * 在目标方法入口、变量读取点前或变量写入点后修改指定局部变量槽位。
     *
     * @param target 目标方法
     * @return 成功插入变量改写逻辑时返回 `true`
     * @throws IllegalArgumentException 注入点、槽位索引或 handler 签名不合法时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun inject(target: MethodNode): Boolean = injectCount(target) > 0

    /**
     * 在目标方法入口、变量读取点前或变量写入点后修改指定局部变量槽位并返回实际修改数量。
     *
     * @param target 目标方法
     * @return 实际写入变量改写逻辑的数量；HEAD 模式最多为 1，LOAD/STORE 模式为匹配读写点数量
     * @throws IllegalArgumentException 注入点、槽位索引或 handler 签名不合法时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun injectCount(target: MethodNode): Int {
        return when (injectionPoint) {
            InjectionPoint.HEAD -> injectAtHeadCount(target)
            InjectionPoint.LOAD -> injectBeforeLoadCount(target)
            InjectionPoint.STORE -> injectAfterStoreCount(target)
            else -> throw IllegalArgumentException(
                "@ModifyVariable currently supports only HEAD, LOAD and STORE injection points",
            )
        }
    }

    /**
     * 在方法入口插入一次局部变量改写调用。
     *
     * 入口模式只能改写目标方法已有参数槽位。方法会先按显式槽位或 handler 首参类型解析参数，
     * 再校验 handler 签名并把 handler 返回值写回同一个参数槽位。
     *
     * @param target 目标方法
     * @return 固定返回 `1`，表示已插入入口改写逻辑
     * @throws IllegalArgumentException 无法解析入口参数或 handler 签名不匹配时抛出
     */
    private fun injectAtHeadCount(target: MethodNode): Int {
        val handlerVariableType = requireHandlerVariableArgumentType()
        val variable =
            resolveHeadVariable(target, variableIndex, handlerVariableType, ordinal)
                ?: throw IllegalArgumentException(
                    "@ModifyVariable cannot resolve HEAD variable: index=$variableIndex, ordinal=$ordinal, type=$handlerVariableType",
                )

        val targetParamCount = validateHandlerSignature(target, variable.type)

        val il = buildModificationCall(target, variable.type, variable.index, targetParamCount)

        if (target.instructions.size() == 0) {
            target.instructions.add(il)
        } else {
            target.instructions.insertBefore(target.instructions.first, il)
        }

        return 1
    }

    /**
     * 在匹配的局部变量读取指令前插入改写调用。
     *
     * 遍历切片范围内的 LOAD 指令，并按槽位、变量名、handler 首参类型与 ordinal 过滤候选。
     * 插入的调用会在原读取发生前先读取当前槽位值、调用 handler，然后把新值写回同一槽位，
     * 使后续原 LOAD 读取到修改后的值。
     *
     * @param target 目标方法
     * @return 实际插入的 LOAD 改写数量
     */
    private fun injectBeforeLoadCount(target: MethodNode): Int {
        val handlerVariableType = requireHandlerVariableArgumentType()
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
            if (variableIndex >= 0 && insn.`var` != variableIndex) {
                continue
            }
            if (!matchesRequestedVariableName(target, insn, insn.`var`)) {
                continue
            }
            if (!isLoadCompatibleWithHandler(insn.opcode, handlerVariableType)) {
                continue
            }

            val variableType =
                resolveIndexedVariableType(target, insn.`var`, handlerVariableType)
                    ?: handlerVariableType
            val currentOrdinal = matchedOrdinal++
            if (variableIndex < 0 && ordinal >= 0 && currentOrdinal != ordinal) {
                continue
            }

            val targetParamCount = validateHandlerSignature(target, variableType)
            val il = buildModificationCall(target, variableType, insn.`var`, targetParamCount)
            target.instructions.insertBefore(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 在匹配的局部变量写入指令后插入改写调用。
     *
     * 遍历切片范围内的 STORE 指令，并按槽位、变量名、handler 首参类型与 ordinal 过滤候选。
     * 插入点位于原 STORE 之后，因此 handler 接收到的是刚刚写入槽位的新值。
     *
     * @param target 目标方法
     * @return 实际插入的 STORE 改写数量
     */
    private fun injectAfterStoreCount(target: MethodNode): Int {
        val handlerVariableType = requireHandlerVariableArgumentType()
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
            if (variableIndex >= 0 && insn.`var` != variableIndex) {
                continue
            }
            if (!matchesRequestedVariableName(target, storeAnchor(insn), insn.`var`)) {
                continue
            }
            if (!isStoreCompatibleWithHandler(insn.opcode, handlerVariableType)) {
                continue
            }

            val variableType =
                resolveIndexedVariableType(target, insn.`var`, handlerVariableType)
                    ?: handlerVariableType
            val currentOrdinal = matchedOrdinal++
            if (variableIndex < 0 && ordinal >= 0 && currentOrdinal != ordinal) {
                continue
            }

            val targetParamCount = validateHandlerSignature(target, variableType)
            val il = buildModificationCall(target, variableType, insn.`var`, targetParamCount)
            target.instructions.insert(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 解析 HEAD 模式下需要改写的入口参数。
     *
     * 显式指定槽位时优先按 JVM 局部变量槽位匹配；未指定槽位时，按 handler 首参类型收集候选，
     * 并使用 [ordinal] 选择同类型参数。未指定 [ordinal] 时要求同类型候选唯一。
     *
     * @param target 目标方法
     * @param index 显式 JVM 局部变量槽位；小于 0 表示按类型推断
     * @param expectedType handler 首个参数声明的变量类型
     * @param ordinal 同类型入口参数序号；小于 0 表示自动唯一推断
     * @return 解析到的入口参数；无法唯一匹配时返回 `null`
     */
    private fun resolveHeadVariable(
        target: MethodNode,
        index: Int,
        expectedType: Type,
        ordinal: Int,
    ): HeadVariable? {
        val variables = collectHeadParameters(target)
        if (index >= 0) {
            return variables.find { it.index == index && matchesRequestedVariableName(it.name) }
        }

        val matchingVariables = variables.filter { it.type == expectedType && matchesRequestedVariableName(it.name) }
        if (ordinal < 0) {
            return matchingVariables.singleOrNull()
        }

        return matchingVariables.getOrNull(ordinal)
    }

    /**
     * 收集目标方法入口处可被 HEAD 模式改写的参数槽位。
     *
     * 实例方法会跳过 `this` 所在的 0 号槽位，并按参数类型宽度推进槽位索引。
     * 若目标方法包含 LocalVariableTable，则顺带记录对应参数名，供变量名过滤使用。
     *
     * @param target 目标方法
     * @return 按声明顺序排列的入口参数槽位信息
     */
    private fun collectHeadParameters(target: MethodNode): List<HeadVariable> {
        val isStatic = (target.access and Opcodes.ACC_STATIC) != 0
        var slot = if (isStatic) 0 else 1
        return buildList {
            for (argumentType in Type.getArgumentTypes(target.desc)) {
                add(HeadVariable(slot, argumentType, localVariableNameAtSlot(target, slot)))
                slot += argumentType.size
            }
        }
    }

    /**
     * 读取 handler 第一个参数声明的变量类型。
     *
     * `@ModifyVariable` 的 handler 至少需要接收原变量值；后续参数才表示目标方法参数前缀。
     *
     * @return handler 首个参数类型
     * @throws IllegalArgumentException handler 未声明原变量值参数时抛出
     */
    private fun requireHandlerVariableArgumentType(): Type {
        val handlerParams = Type.getArgumentTypes(asmMethod)
        if (handlerParams.isEmpty()) {
            throw IllegalArgumentException(
                "@ModifyVariable handler ${asmMethod.name} must take at least one argument for the original variable value",
            )
        }
        return handlerParams[0]
    }

    /**
     * 为显式槽位的引用类型变量恢复更精确的槽位类型。
     *
     * handler 可用 `Any` 或 `Object` 接收引用变量，但写回局部变量时仍应尽量保持真实槽位类型。
     * 本方法依次尝试目标方法参数、LocalVariableTable 与槽位周边字节码用途来恢复类型。
     * 只有显式指定槽位且 handler 首参为引用类型时才需要恢复。
     *
     * @param target 目标方法
     * @param index JVM 局部变量槽位
     * @param fallbackType handler 首参类型
     * @return 恢复出的引用类型；无需恢复或无法恢复时返回 `null`
     */
    private fun resolveIndexedVariableType(
        target: MethodNode,
        index: Int,
        fallbackType: Type,
    ): Type? {
        if (variableIndex < 0 || !fallbackType.isReferenceType()) {
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

    /**
     * 通过同槽位的引用读写指令推断真实引用类型。
     *
     * 该推断用于没有入口参数或 LocalVariableTable 可用时的兜底类型恢复。
     * 只检查 ALOAD/ASTORE，并保留能被 handler 首参兼容接收的类型。
     *
     * @param target 目标方法
     * @param index JVM 局部变量槽位
     * @param fallbackType handler 首参类型
     * @return 推断出的引用类型；无可靠证据时返回 `null`
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
     * 从引用槽位指令周边的字节码用途推断真实引用类型。
     *
     * 对 ASTORE，会优先读取写入前的 `CHECKCAST` 或字符串常量证据；若没有直接证据，
     * 则继续观察下一次同槽位 ALOAD 的消费位置。对 ALOAD，会根据后续实例调用、实例字段访问、
     * `CHECKCAST` 或引用返回指令推断该槽位在当前上下文中应保持的类型。
     *
     * @param target 目标方法
     * @param insn 待分析的 ALOAD 或 ASTORE 指令
     * @return 推断出的引用类型；证据不足时返回 `null`
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
     * 从 ASTORE 后同槽位的下一次读取用途推断引用类型。
     *
     * 当 STORE 前没有足够类型证据时，本方法向后扫描到下一次同槽位 ALOAD，并复用读取点消费方推断逻辑。
     * 若在下一次读取前遇到同槽位再次写入，则认为原写入值已经被覆盖，停止推断。
     *
     * @param target 目标方法
     * @param storeInsn 引用写入指令
     * @return 下一次读取用途推断出的引用类型；找不到可靠读取点时返回 `null`
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
     * 查找当前指令之前最近的真实字节码指令。
     *
     * ASM Tree 中 label、line number 与 frame 节点的 opcode 为负数，不能作为类型推断证据。
     *
     * @param insn 当前指令
     * @return 前一个 opcode 非负的指令；不存在时返回 `null`
     */
    private fun previousRealInstruction(insn: AbstractInsnNode): AbstractInsnNode? {
        var current = insn.previous
        while (current != null && current.opcode < 0) {
            current = current.previous
        }
        return current
    }

    /**
     * 查找当前指令之后最近的真实字节码指令。
     *
     * 该方法用于让 STORE anchor 与类型推断跳过 label、line number 和 frame 节点。
     *
     * @param insn 当前指令
     * @return 后一个 opcode 非负的指令；不存在时返回 `null`
     */
    private fun nextRealInstruction(insn: AbstractInsnNode): AbstractInsnNode? {
        var current = insn.next
        while (current != null && current.opcode < 0) {
            current = current.next
        }
        return current
    }

    /**
     * 选择 STORE 模式用于 LocalVariableTable 范围判断的锚点。
     *
     * STORE 指令本身可能位于变量作用域起点之前，因此优先使用写入后的下一条真实指令判断变量名范围。
     *
     * @param insn STORE 指令
     * @return 用于名称匹配的锚点指令
     */
    private fun storeAnchor(insn: AbstractInsnNode): AbstractInsnNode = nextRealInstruction(insn) ?: insn

    /**
     * 判断入口参数名是否满足用户声明的变量名过滤。
     *
     * @param name LocalVariableTable 中记录的参数名
     * @return 未声明变量名过滤时返回 `true`；否则仅匹配声明名称
     */
    private fun matchesRequestedVariableName(name: String?): Boolean =
        requestedVariableNames.isEmpty() || (name != null && name in requestedVariableNames)

    /**
     * 判断指定槽位在锚点处的局部变量名是否满足用户声明的变量名过滤。
     *
     * LOAD/STORE 模式需要结合 LocalVariableTable 的作用域范围判断变量名；缺少调试变量表、
     * 锚点不在变量作用域内或名称不一致时，名称过滤不会命中。
     *
     * @param target 目标方法
     * @param anchor 用于判断变量作用域的指令
     * @param index JVM 局部变量槽位
     * @return 未声明变量名过滤时返回 `true`；否则仅匹配作用域内的声明名称
     */
    private fun matchesRequestedVariableName(
        target: MethodNode,
        anchor: AbstractInsnNode,
        index: Int,
    ): Boolean {
        if (requestedVariableNames.isEmpty()) {
            return true
        }
        return localVariableAt(target, anchor, index)?.name in requestedVariableNames
    }

    /**
     * 读取入口槽位对应的调试变量名。
     *
     * 该方法只按槽位取第一个 LocalVariableTable 记录，供 HEAD 参数收集时记录可选名称。
     *
     * @param target 目标方法
     * @param index JVM 局部变量槽位
     * @return 变量名；目标方法没有 LocalVariableTable 或槽位不存在时返回 `null`
     */
    private fun localVariableNameAtSlot(
        target: MethodNode,
        index: Int,
    ): String? =
        target.localVariables
            .firstOrNull { it.index == index }
            ?.name

    /**
     * 查找锚点处覆盖指定槽位的局部变量表记录。
     *
     * LocalVariableTable 的同一槽位可能在不同生命周期内复用，因此必须同时匹配槽位和指令范围。
     *
     * @param target 目标方法
     * @param anchor 待匹配的锚点指令
     * @param index JVM 局部变量槽位
     * @return 覆盖锚点的局部变量记录；不存在时返回 `null`
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
     * JVM 局部变量作用域使用半开区间 `[start, end)`，因此结束标签所在下标不属于变量有效范围。
     *
     * @param insns 目标方法指令数组
     * @param instructionIndex 待判断的指令下标
     * @return 指令下标处于该局部变量生命周期内时返回 `true`
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
     * 构建一次变量改写的 handler 调用指令序列。
     *
     * 生成的字节码会按顺序加载 handler owner、当前槽位值与目标方法参数前缀，
     * 调用 handler 后按需插入引用类型转换，并把 handler 返回的新值写回原槽位。
     *
     * @param target 目标方法
     * @param variableType 实际写回槽位时使用的变量类型
     * @param variableIndex JVM 局部变量槽位
     * @param targetParamCount handler 额外声明的目标方法参数数量
     * @return 可直接插入目标方法的指令列表
     */
    private fun buildModificationCall(
        target: MethodNode,
        variableType: Type,
        variableIndex: Int,
        targetParamCount: Int,
    ): InsnList {
        val il = InsnList()
        addHandlerOwner(il)
        il.add(InstructionUtil.loadParam(variableType, variableIndex))
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
        addVariableCastIfNeeded(il, variableType)
        il.add(storeVariable(variableType, variableIndex))
        return il
    }

    /**
     * 校验 handler 签名并返回需要加载的目标方法参数数量。
     *
     * handler 第一个参数必须能接收原变量值，返回值必须能写回变量槽位。
     * 其余参数按顺序匹配目标方法参数前缀，允许引用类型使用父类型、接口或 `Any` / `Object` 接收。
     *
     * @param target 目标方法
     * @param variableType 当前改写点解析出的变量类型
     * @return handler 需要追加加载的目标方法参数数量
     * @throws IllegalArgumentException handler 首参、返回值或目标方法参数前缀不兼容时抛出
     */
    private fun validateHandlerSignature(
        target: MethodNode,
        variableType: Type,
    ): Int {
        val handlerParams = Type.getArgumentTypes(asmMethod)
        if (handlerParams.isEmpty() || !isHandlerParameterCompatible(variableType, handlerParams[0])) {
            throw IllegalArgumentException(
                "@ModifyVariable handler ${asmMethod.name} first parameter must be $variableType " +
                    "or compatible Object/Any, actual ${handlerParams.toList()}",
            )
        }

        val handlerReturnType = Type.getReturnType(asmMethod)
        if (!isHandlerReturnCompatible(variableType, handlerReturnType)) {
            throw IllegalArgumentException(
                "@ModifyVariable handler ${asmMethod.name} return type $handlerReturnType must match variable type $variableType",
            )
        }

        val targetParamTypes = Type.getArgumentTypes(target.desc)
        val requestedTargetParamCount = handlerParams.size - 1
        if (requestedTargetParamCount > targetParamTypes.size) {
            throw IllegalArgumentException(
                "@ModifyVariable handler ${asmMethod.name} requests $requestedTargetParamCount target parameter(s), " +
                    "but target method ${target.name}${target.desc} has only ${targetParamTypes.size}",
            )
        }

        for (index in 0 until requestedTargetParamCount) {
            val expected = targetParamTypes[index]
            val actual = handlerParams[index + 1]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalArgumentException(
                    "@ModifyVariable handler ${asmMethod.name} target parameter #$index mismatch: expected $expected, actual $actual",
                )
            }
        }

        return requestedTargetParamCount
    }

    /**
     * 判断 handler 参数声明是否能接收期望类型的运行时值。
     *
     * 基础类型必须精确匹配；引用类型允许 handler 参数声明为期望类型的父类、接口、
     * `java.lang.Object` 或 `kotlin.Any`。
     *
     * @param expected 注入点需要提供的值类型
     * @param actual handler 参数声明类型
     * @return handler 参数可以安全接收该值时返回 `true`
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
     * 判断 ASM 类型是否属于对象或数组引用类型。
     *
     * @return 当前类型为对象或数组时返回 `true`
     */
    private fun Type.isReferenceType(): Boolean = sort == Type.OBJECT || sort == Type.ARRAY

    /**
     * 使用 Mixin 类加载器解析引用类型对应的 Java Class。
     *
     * 引用兼容性校验需要真实类层级；数组类型使用描述符形式解析，对象类型使用类名解析。
     *
     * @param type 待解析的 ASM 引用类型
     * @return 对应的 Java Class
     * @throws ClassNotFoundException 类加载器无法解析该类型时抛出
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
     * 判断 handler 返回类型是否能写回目标变量槽位。
     *
     * 基础类型必须精确匹配且不能为 `void`；引用类型允许 handler 返回变量类型的子类型，
     * 也允许声明为 `Any` / `Object`，后续会通过 `CHECKCAST` 恢复写回槽位所需类型。
     *
     * @param variableType 变量槽位真实类型
     * @param handlerReturnType handler 返回类型
     * @return handler 返回值可写回该槽位时返回 `true`
     */
    private fun isHandlerReturnCompatible(
        variableType: Type,
        handlerReturnType: Type,
    ): Boolean {
        if (variableType == handlerReturnType) {
            return true
        }
        if (handlerReturnType == Type.VOID_TYPE) {
            return false
        }
        if (!variableType.isReferenceType() || !handlerReturnType.isReferenceType()) {
            return false
        }
        if (handlerReturnType.sort == Type.OBJECT &&
            (handlerReturnType.internalName == "java/lang/Object" || handlerReturnType.internalName == "kotlin/Any")
        ) {
            return true
        }
        return runCatching {
            val variableClass = loadReferenceClass(variableType)
            variableClass.isAssignableFrom(asmMethod.returnType)
        }.getOrDefault(false)
    }

    /**
     * 在 handler 返回引用泛型类型时补充写回前的类型转换。
     *
     * 当 handler 返回类型与变量槽位类型不同但二者均为引用类型时，插入 `CHECKCAST`，
     * 避免后续写回或栈图计算把局部变量退化为过宽的引用类型。
     *
     * @param il 正在构建的指令列表
     * @param variableType 变量槽位真实类型
     */
    private fun addVariableCastIfNeeded(
        il: InsnList,
        variableType: Type,
    ) {
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (variableType != handlerReturnType && variableType.isReferenceType()) {
            il.add(TypeInsnNode(Opcodes.CHECKCAST, variableType.internalName))
        }
    }

    /**
     * 按 handler 签名需要加载目标方法参数前缀。
     *
     * 参数从目标方法声明顺序的第一个参数开始加载，实例方法会跳过 `this` 槽位，
     * 并按参数类型宽度推进局部变量槽位。
     *
     * @param il 正在构建的指令列表
     * @param target 目标方法
     * @param requestedTargetParamCount 需要追加加载的目标方法参数数量
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
            il.add(InstructionUtil.loadParam(paramType, paramVarIndex))
            paramVarIndex += paramType.size
        }
    }

    /**
     * 为非静态 handler 加载调用接收者。
     *
     * Kotlin `object` 使用 `INSTANCE` 字段；普通类按无参构造器创建临时实例。
     * 静态 handler 不需要接收者，本方法直接返回。
     *
     * @param il 正在构建的指令列表
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
        il.add(org.objectweb.asm.tree.InsnNode(Opcodes.DUP))
        il.add(
            MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                ownerType.internalName,
                "<init>",
                "()V",
                false,
            ),
        )
    }

    /**
     * 选择调用 handler 时使用的方法调用 opcode。
     *
     * @return 静态 handler 使用 [Opcodes.INVOKESTATIC]，否则使用 [Opcodes.INVOKEVIRTUAL]
     */
    private fun handlerOpcode(): Int =
        if (isHandlerStatic()) {
            Opcodes.INVOKESTATIC
        } else {
            Opcodes.INVOKEVIRTUAL
        }

    /**
     * 判断 handler 方法是否为 Java 反射意义上的静态方法。
     *
     * Kotlin companion 或 object 中带 `@JvmStatic` 的方法会按静态 handler 调用。
     *
     * @return handler 具有 [Modifier.STATIC] 标记时返回 `true`
     */
    private fun isHandlerStatic(): Boolean = (asmMethod.modifiers and Modifier.STATIC) != 0

    /**
     * 解析 LOAD/STORE 模式使用的切片指令范围。
     *
     * `slice.from` 命中后从边界后一条指令开始匹配，`slice.to` 命中位置本身不参与匹配。
     * 任一边界声明但无法命中时返回空范围，避免在目标字节码漂移后误匹配整段方法体。
     *
     * @param insns 目标方法指令数组
     * @return 可遍历的半开区间 `[start, end)`
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
     * 判断切片边界是否声明了可匹配目标。
     *
     * @param at 切片边界注解配置
     * @return `target` 非空时返回 `true`
     */
    private fun hasSliceBoundary(at: At): Boolean = at.target.isNotEmpty()

    /**
     * 构造一个位于方法末尾的空切片范围。
     *
     * @param insns 目标方法指令数组
     * @return 不会命中任何指令的半开区间
     */
    private fun emptySlice(insns: Array<AbstractInsnNode>): Pair<Int, Int> = insns.size to insns.size

    /**
     * 从指定位置开始查找切片边界调用指令。
     *
     * 当前 `@ModifyVariable(LOAD/STORE)` 的切片边界只支持 [InjectionPoint.INVOKE]，
     * 可匹配普通方法调用、构造器调用或 `invokedynamic` 调用。
     *
     * @param insns 目标方法指令数组
     * @param at 切片边界配置
     * @param startIndex 起始搜索下标
     * @return 匹配边界的指令下标；未命中时返回 `null`
     * @throws IllegalArgumentException 边界注入点类型或目标方法签名不合法时抛出
     */
    private fun findSliceBoundaryIndex(
        insns: Array<AbstractInsnNode>,
        at: At,
        startIndex: Int,
    ): Int? {
        require(at.value == InjectionPoint.INVOKE) {
            "Only INVOKE slice boundaries are supported for @ModifyVariable(LOAD/STORE): ${at.value}"
        }

        val (boundaryOwner, boundaryName, boundaryDesc) = parseTargetMethod(at.target)
        if (boundaryName == null || boundaryDesc == null) {
            throw IllegalArgumentException(
                "Invalid ModifyVariable slice boundary method signature: ${at.target} " +
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
     * 解析注解中声明的方法目标签名。
     *
     * 支持 `owner/name(desc)`、`owner.name(desc)` 与 `name(desc)` 形式。
     * 未携带 owner 时只按方法名与描述符匹配；未携带描述符时返回 `null` 描述符供调用方判定非法。
     *
     * @param signature 注解中声明的目标签名
     * @return `owner`、`name`、`desc` 三元组；缺失部分以 `null` 表示
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
     * 判断普通方法调用是否匹配切片边界目标。
     *
     * @param insn 待检查的方法调用指令
     * @param targetOwner 目标 owner；为 `null` 时不限制 owner
     * @param targetName 目标方法名
     * @param targetDesc 目标方法描述符；为 `null` 时不限制描述符
     * @return 调用指令匹配目标时返回 `true`
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
     * 判断 `invokedynamic` 指令是否匹配切片边界目标。
     *
     * owner 匹配 bootstrap method owner；名称可匹配动态调用名或 bootstrap method 名。
     *
     * @param insn 待检查的 `invokedynamic` 指令
     * @param targetOwner 目标 bootstrap owner；为 `null` 时不限制 owner
     * @param targetName 目标动态调用名或 bootstrap method 名
     * @param targetDesc 目标动态调用描述符；为 `null` 时不限制描述符
     * @return 指令匹配目标时返回 `true`
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
     * 判断 LOAD 指令读取的槽位类型是否可由 handler 首参接收。
     *
     * int 家族的 JVM 读取指令共用 ILOAD，因此 boolean、byte、short、int 与 char
     * 都视为兼容；引用读取要求 handler 首参为对象或数组类型。
     *
     * @param opcode LOAD 指令 opcode
     * @param handlerType handler 首参类型
     * @return handler 首参可接收该 LOAD 值时返回 `true`
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
     * 判断 STORE 指令写入的槽位类型是否可由 handler 首参接收。
     *
     * 该检查用于 STORE 模式的候选过滤，确保 handler 接收的是刚写入槽位值的 JVM 类型。
     *
     * @param opcode STORE 指令 opcode
     * @param handlerType handler 首参类型
     * @return handler 首参可接收该 STORE 值时返回 `true`
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
     * 生成把 handler 返回值写回局部变量槽位的 STORE 指令。
     *
     * JVM 的 boolean、byte、short、int 与 char 统一使用 ISTORE；引用类型统一使用 ASTORE。
     *
     * @param type 要写回的变量类型
     * @param index JVM 局部变量槽位
     * @return 对应类型的变量写回指令
     * @throws IllegalArgumentException 尝试写回 `void` 类型时抛出
     */
    private fun storeVariable(
        type: Type,
        index: Int,
    ): VarInsnNode =
        when (type.sort) {
            Type.BOOLEAN, Type.BYTE, Type.SHORT, Type.INT, Type.CHAR -> VarInsnNode(Opcodes.ISTORE, index)
            Type.LONG -> VarInsnNode(Opcodes.LSTORE, index)
            Type.FLOAT -> VarInsnNode(Opcodes.FSTORE, index)
            Type.DOUBLE -> VarInsnNode(Opcodes.DSTORE, index)
            Type.VOID -> throw IllegalArgumentException("Cannot store VOID variable")
            else -> VarInsnNode(Opcodes.ASTORE, index)
        }

    private data class HeadVariable(
        val index: Int,
        val type: Type,
        val name: String?,
    )

    private companion object {
        private val LOAD_OPS = setOf(Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD, Opcodes.ALOAD)
        private val STORE_OPS = setOf(Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE, Opcodes.ASTORE)
        private val SLOT_REFERENCE_OPS = setOf(Opcodes.ALOAD, Opcodes.ASTORE)
        private val INT_VARIABLE_TYPE_SORTS = setOf(Type.BOOLEAN, Type.BYTE, Type.SHORT, Type.INT, Type.CHAR)
    }
}
