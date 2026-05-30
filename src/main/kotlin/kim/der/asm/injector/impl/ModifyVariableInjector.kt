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

    private fun storeAnchor(insn: AbstractInsnNode): AbstractInsnNode = nextRealInstruction(insn) ?: insn

    private fun matchesRequestedVariableName(name: String?): Boolean =
        requestedVariableNames.isEmpty() || (name != null && name in requestedVariableNames)

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

    private fun localVariableNameAtSlot(
        target: MethodNode,
        index: Int,
    ): String? =
        target.localVariables
            .firstOrNull { it.index == index }
            ?.name

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

    private fun addVariableCastIfNeeded(
        il: InsnList,
        variableType: Type,
    ) {
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (variableType != handlerReturnType && variableType.isReferenceType()) {
            il.add(TypeInsnNode(Opcodes.CHECKCAST, variableType.internalName))
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
            il.add(InstructionUtil.loadParam(paramType, paramVarIndex))
            paramVarIndex += paramType.size
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

    private fun handlerOpcode(): Int =
        if (isHandlerStatic()) {
            Opcodes.INVOKESTATIC
        } else {
            Opcodes.INVOKEVIRTUAL
        }

    private fun isHandlerStatic(): Boolean = (asmMethod.modifiers and Modifier.STATIC) != 0

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
