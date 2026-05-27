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
import kim.der.asm.utils.transformer.InstructionUtil
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * WrapOperation 注入器。
 *
 * 该注入器会匹配目标方法内的指定方法调用、构造器调用、字段读取、字段写入、数组元素读写、类型转换或类型判断，
 * 并用 handler 替换原操作。
 * handler 会收到原操作 receiver（实例调用、实例字段读取与实例字段写入）、原调用参数、构造器参数、字段写入值或
 * 数组访问参数、类型转换或类型判断输入值、[Operation] 句柄和可选目标方法参数；handler 可通过 [Operation.call] 执行原始操作，
 * 也可以跳过或改变调用参数。
 *
 * 当前实现支持普通 [InjectionPoint.INVOKE] 方法调用、[InjectionPoint.FIELD] 字段读取与
 * [InjectionPoint.FIELD_ASSIGN] 字段写入。[InjectionPoint.FIELD] 可通过 `array=get` 包裹数组元素读取，
 * 通过 `array=length` 包裹数组长度读取；[InjectionPoint.FIELD_ASSIGN] 可通过 `array=set` 包裹数组元素写入；
 * [InjectionPoint.INVOKE] 可通过 `<init>` 目标包裹常见 `NEW/DUP/args/INVOKESPECIAL` 构造器调用；
 * [InjectionPoint.CAST] 可包裹 `CHECKCAST` 类型转换；[InjectionPoint.INSTANCEOF] 可包裹类型判断。
 *
 * @param at 操作点定位；当前支持 [InjectionPoint.INVOKE]、[InjectionPoint.FIELD]、[InjectionPoint.FIELD_ASSIGN]
 * 与 [InjectionPoint.CAST]、[InjectionPoint.INSTANCEOF]
 * @param ordinal 匹配操作点序号；负数表示处理全部匹配操作点
 * @param slice 切片范围；当前 [InjectionPoint.INVOKE]、[InjectionPoint.FIELD] 与
 * [InjectionPoint.FIELD_ASSIGN]、[InjectionPoint.CAST]、[InjectionPoint.INSTANCEOF] 操作包裹使用 INVOKE 边界缩小匹配范围
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
            else -> throw IllegalArgumentException(
                "@WrapOperation currently supports only INVOKE, FIELD, FIELD_ASSIGN, NEW, CAST and INSTANCEOF injection points",
            )
        }

    private fun arrayAccessMode(): ArrayAccessMode? {
        val arrayArg = at.args.firstOrNull { it.trim().startsWith("array=") } ?: return null
        return when (arrayArg.substringAfter('=').trim().lowercase()) {
            "get" -> ArrayAccessMode.GET
            "set" -> ArrayAccessMode.SET
            "length" -> ArrayAccessMode.LENGTH
            else -> throw IllegalArgumentException("Unsupported @WrapOperation array access mode: $arrayArg")
        }
    }

    private fun injectMethodCall(target: MethodNode): Int {
        val (targetOwner, targetName, targetDesc) = parseTargetMethod(at.target)
        if (targetName == null || targetDesc == null) {
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
            if (insn !is MethodInsnNode || !matchesTargetMethod(insn, targetOwner, targetName, targetDesc)) {
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

        return injectionCount
    }

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

    private fun injectFieldRead(target: MethodNode): Int {
        val fieldTarget = parseFieldTarget(at.target)
        if (fieldTarget.name == null) {
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
            if (insn !is FieldInsnNode || insn.opcode !in FIELD_READ_OPS || !matchesTargetField(insn, fieldTarget)) {
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

    private fun injectFieldAssign(target: MethodNode): Int {
        val fieldTarget = parseFieldTarget(at.target)
        if (fieldTarget.name == null) {
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
            if (insn !is FieldInsnNode || insn.opcode !in FIELD_WRITE_OPS || !matchesTargetField(insn, fieldTarget)) {
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

    private fun injectCast(target: MethodNode): Int {
        val castTarget = at.target.replace('.', '/')
        if (castTarget.isEmpty()) {
            throw IllegalArgumentException("@WrapOperation CAST target type must not be empty")
        }

        var injectionCount = 0
        var matchedOrdinal = 0
        val insns = target.instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)
        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (insn !is TypeInsnNode || insn.opcode != Opcodes.CHECKCAST || insn.desc != castTarget) {
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

    private fun injectInstanceof(target: MethodNode): Int {
        val typeTarget = at.target.replace('.', '/')
        if (typeTarget.isEmpty()) {
            throw IllegalArgumentException("@WrapOperation INSTANCEOF target type must not be empty")
        }

        var injectionCount = 0
        var matchedOrdinal = 0
        val insns = target.instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)
        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (insn !is TypeInsnNode || insn.opcode != Opcodes.INSTANCEOF || insn.desc != typeTarget) {
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
        if (expected.sort == Type.OBJECT || expected.sort == Type.ARRAY) {
            return actual.sort == Type.OBJECT && actual.internalName == "java/lang/Object"
        }
        return false
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
        return (original.sort == Type.OBJECT || original.sort == Type.ARRAY) && handler.sort >= Type.ARRAY
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

    private data class ConstructorAllocation(
        val newInsn: TypeInsnNode,
        val dupInsn: AbstractInsnNode,
    )

    private enum class ArrayAccessMode {
        GET,
        SET,
        LENGTH,
    }

    private companion object {
        private val FIELD_READ_OPS = setOf(Opcodes.GETFIELD, Opcodes.GETSTATIC)
        private val FIELD_WRITE_OPS = setOf(Opcodes.PUTFIELD, Opcodes.PUTSTATIC)
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
    }
}
