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
 * 该注入器会匹配目标方法内的 `void` 方法调用、返回 `void` 的 `invokedynamic` 调用、字段写入或简单数组元素写入，
 * 并在原指令前插入 boolean handler。
 * handler 返回 `true` 时恢复原调用的 receiver 与参数、字段写入值或数组写入栈参数并继续执行原指令，
 * 返回 `false` 时跳过原指令。
 * [InjectionPoint.INVOKE] 未指定调用目标时，会按 handler 参数和 boolean 返回类型筛选兼容的 `void`
 * 普通调用或 `invokedynamic` 调用；构造器、非 `void` 调用和 handler 不兼容的调用不会计入 [WrapWithCondition.ordinal] 或命中数。
 * 构造器 `<init>` 虽然返回 `void`，但会消费未初始化对象，当前明确拒绝条件包裹。
 *
 * @param at 调用点定位；当前支持 [InjectionPoint.INVOKE] 与 [InjectionPoint.FIELD_ASSIGN]
 * @param ordinal 匹配调用点序号；负数表示处理全部匹配调用点
 * @param slice 切片范围；当前 [InjectionPoint.INVOKE] 与 [InjectionPoint.FIELD_ASSIGN] 条件包裹使用
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
     * 在匹配的 `void` 方法调用、返回 `void` 的 `invokedynamic` 调用、字段写入或数组元素写入前插入条件包裹逻辑。
     *
     * @param target 目标方法
     * @return 至少包裹一个调用点、动态调用点、字段写入点或数组元素写入点时返回 `true`
     * @throws IllegalArgumentException 定位点、目标调用、字段目标或 handler 签名不合法时抛出
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun inject(target: MethodNode): Boolean = injectCount(target) > 0

    /**
     * 在匹配的 `void` 方法调用、返回 `void` 的 `invokedynamic` 调用、字段写入或数组元素写入前插入条件包裹逻辑，并返回实际包裹数量。
     *
     * @param target 目标方法
     * @return 实际包裹的调用点、动态调用点、字段写入点或数组元素写入点数量
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
            else -> throw IllegalArgumentException(
                "@WrapWithCondition supports only INVOKE and FIELD_ASSIGN injection points",
            )
        }
    }

    private fun isArrayWriteMode(): Boolean {
        val arrayArg = at.args.firstOrNull { it.trim().startsWith("array=") } ?: return false
        return when (arrayArg.substringAfter('=').trim().lowercase()) {
            "set" -> true
            "get" -> throw IllegalArgumentException("@WrapWithCondition array access supports only array=set")
            else -> throw IllegalArgumentException("Unsupported @WrapWithCondition array access mode: $arrayArg")
        }
    }

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

    private fun isMethodCallConditionCompatible(
        target: MethodNode,
        insn: MethodInsnNode,
    ): Boolean {
        if (insn.name == "<init>" || Type.getReturnType(insn.desc) != Type.VOID_TYPE) {
            return false
        }
        return runCatching { validateHandlerSignature(target, insn) }.isSuccess
    }

    private fun isInvokeDynamicConditionCompatible(
        target: MethodNode,
        insn: InvokeDynamicInsnNode,
    ): Boolean {
        if (Type.getReturnType(insn.desc) != Type.VOID_TYPE) {
            return false
        }
        return runCatching { validateInvokeDynamicHandlerSignature(target, insn) }.isSuccess
    }

    private fun injectFieldAssign(target: MethodNode): Int {
        val fieldTarget = parseFieldTarget(at.target)
        if (fieldTarget.name == null) {
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
            if (insn !is FieldInsnNode || insn.opcode !in FIELD_WRITE_OPS || !matchesTargetField(insn, fieldTarget)) {
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
    }
}
