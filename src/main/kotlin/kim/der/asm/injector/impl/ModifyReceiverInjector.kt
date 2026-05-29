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
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * ModifyReceiver 注入器。
 *
 * 该注入器会匹配目标方法内的实例方法调用或实例字段访问，在原操作执行前把 receiver 交给 handler 改写。
 * handler 返回的新 receiver 会替代原 receiver，随后恢复原调用参数或字段写入值并继续执行原操作。
 * [InjectionPoint.INVOKE] 未指定调用目标时，会按 handler 首参与返回类型筛选兼容的实例调用 receiver；
 * 静态调用、构造器调用和 handler 不兼容的实例调用不会计入 [ModifyReceiver.ordinal] 或命中数。
 * [InjectionPoint.FIELD] 未指定字段目标时，会按 handler 首参与返回类型筛选兼容的实例字段读取 receiver；
 * 静态字段和 handler 不兼容的字段读取不会计入 [ModifyReceiver.ordinal] 或命中数。
 * [InjectionPoint.FIELD_ASSIGN] 未指定字段目标时，会按 handler 首参与返回类型筛选兼容的实例字段写入 receiver；
 * 静态字段和 handler 不兼容的字段写入不会计入 [ModifyReceiver.ordinal] 或命中数。
 *
 * @param at 调用点定位；当前支持 [InjectionPoint.INVOKE]、[InjectionPoint.FIELD] 与 [InjectionPoint.FIELD_ASSIGN]
 * @param ordinal 匹配调用点序号；负数表示处理全部匹配调用点
 * @param slice 切片范围；当前 [InjectionPoint.INVOKE]、[InjectionPoint.FIELD] 与 [InjectionPoint.FIELD_ASSIGN]
 * receiver 改写使用 INVOKE 边界缩小匹配范围
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
class ModifyReceiverInjector(
    method: Method,
    asmInfo: AsmInfo,
    private val at: At,
    private val ordinal: Int = -1,
    private val slice: Slice = Slice(),
) : AbstractAsmInjector(method, asmInfo) {
    /**
     * 在匹配实例调用点或字段访问点前改写 receiver。
     *
     * @param target 目标方法
     * @return 至少匹配并改写一个 receiver 时返回 `true`
     * @throws IllegalArgumentException 调用点、目标调用或 handler 签名不合法时抛出
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun inject(target: MethodNode): Boolean = injectCount(target) > 0

    /**
     * 在匹配实例调用点或字段访问点前改写 receiver，并返回实际改写数量。
     *
     * @param target 目标方法
     * @return 实际改写的 receiver 数量
     * @throws IllegalArgumentException 调用点、目标调用或 handler 签名不合法时抛出
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun injectCount(target: MethodNode): Int =
        when (at.value) {
            InjectionPoint.INVOKE -> injectMethodCall(target)
            InjectionPoint.FIELD -> injectFieldRead(target)
            InjectionPoint.FIELD_ASSIGN -> injectFieldAssign(target)
            else -> throw IllegalArgumentException(
                "@ModifyReceiver supports only INVOKE, FIELD and FIELD_ASSIGN injection points",
            )
        }

    private fun injectMethodCall(target: MethodNode): Int {
        val inferTarget = at.target.isEmpty()
        val (targetOwner, targetName, targetDesc) = parseTargetMethod(at.target)
        if (!inferTarget && (targetName == null || targetDesc == null)) {
            throw IllegalArgumentException("@ModifyReceiver INVOKE requires at.target method signature")
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
                insn !is MethodInsnNode ||
                !(inferTarget || (targetName != null && matchesTargetMethod(insn, targetOwner, targetName, targetDesc)))
            ) {
                continue
            }
            if (inferTarget && !isMethodReceiverCompatible(target, insn)) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            if (insn.opcode == Opcodes.INVOKESTATIC || insn.name == "<init>") {
                throw IllegalArgumentException(
                    "@ModifyReceiver supports only instance method calls, target ${insn.owner}.${insn.name}${insn.desc}",
                )
            }

            val receiverType = Type.getObjectType(insn.owner)
            val targetParamCount = validateHandlerSignature(target, receiverType)
            val il = buildCallReceiverModification(target, insn, receiverType, targetParamCount)
            target.instructions.insertBefore(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    private fun isMethodReceiverCompatible(
        target: MethodNode,
        insn: MethodInsnNode,
    ): Boolean {
        if (insn.opcode == Opcodes.INVOKESTATIC || insn.name == "<init>") {
            return false
        }
        val receiverType = Type.getObjectType(insn.owner)
        return isReceiverHandlerCompatible(target, receiverType)
    }

    private fun injectFieldRead(target: MethodNode): Int {
        val inferTarget = at.target.isEmpty()
        val fieldTarget = parseFieldTarget(at.target)
        if (!inferTarget && fieldTarget.name == null) {
            throw IllegalArgumentException("@ModifyReceiver FIELD requires at.target field signature")
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
            if (inferTarget && insn.opcode == Opcodes.GETSTATIC) {
                continue
            }

            if (insn.opcode == Opcodes.GETSTATIC) {
                throw IllegalArgumentException(
                    "@ModifyReceiver supports only instance field reads, target ${insn.owner}.${insn.name}:${insn.desc}",
                )
            }

            val receiverType = Type.getObjectType(insn.owner)
            if (inferTarget && !isReceiverHandlerCompatible(target, receiverType)) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val targetParamCount = validateHandlerSignature(target, receiverType)
            val il = buildFieldReadReceiverModification(target, receiverType, targetParamCount)
            target.instructions.insertBefore(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    private fun injectFieldAssign(target: MethodNode): Int {
        val inferTarget = at.target.isEmpty()
        val fieldTarget = parseFieldTarget(at.target)
        if (!inferTarget && fieldTarget.name == null) {
            throw IllegalArgumentException("@ModifyReceiver FIELD_ASSIGN requires at.target field signature")
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
            if (inferTarget && insn.opcode == Opcodes.PUTSTATIC) {
                continue
            }

            if (insn.opcode == Opcodes.PUTSTATIC) {
                throw IllegalArgumentException(
                    "@ModifyReceiver supports only instance field writes, target ${insn.owner}.${insn.name}:${insn.desc}",
                )
            }

            val receiverType = Type.getObjectType(insn.owner)
            val fieldType = Type.getType(insn.desc)
            if (inferTarget && !isReceiverHandlerCompatible(target, receiverType)) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val targetParamCount = validateHandlerSignature(target, receiverType)
            val il = buildFieldAssignReceiverModification(target, receiverType, fieldType, targetParamCount)
            target.instructions.insertBefore(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    private fun isReceiverHandlerCompatible(
        target: MethodNode,
        receiverType: Type,
    ): Boolean = runCatching { validateHandlerSignature(target, receiverType) }.isSuccess

    private fun buildCallReceiverModification(
        target: MethodNode,
        callInsn: MethodInsnNode,
        receiverType: Type,
        targetParamCount: Int,
    ): InsnList {
        val il = InsnList()
        val callParamTypes = Type.getArgumentTypes(callInsn.desc)
        var nextTempIndex = nextLocalIndex(target)
        val receiverIndex = nextTempIndex.also { nextTempIndex += 1 }
        val argSlots =
            callParamTypes.map { paramType ->
                nextTempIndex.also { nextTempIndex += paramType.size }
            }

        for (index in callParamTypes.indices.reversed()) {
            storeStackValue(il, callParamTypes[index], argSlots[index])
        }
        il.add(VarInsnNode(Opcodes.ASTORE, receiverIndex))

        addHandlerOwner(il)
        il.add(VarInsnNode(Opcodes.ALOAD, receiverIndex))
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

        addReceiverCast(il, receiverType)
        for (index in callParamTypes.indices) {
            loadFromVariable(il, callParamTypes[index], argSlots[index])
        }

        return il
    }

    private fun buildFieldReadReceiverModification(
        target: MethodNode,
        receiverType: Type,
        targetParamCount: Int,
    ): InsnList {
        val il = InsnList()
        val receiverIndex = nextLocalIndex(target)

        il.add(VarInsnNode(Opcodes.ASTORE, receiverIndex))
        addHandlerOwner(il)
        il.add(VarInsnNode(Opcodes.ALOAD, receiverIndex))
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
        addReceiverCast(il, receiverType)

        return il
    }

    private fun buildFieldAssignReceiverModification(
        target: MethodNode,
        receiverType: Type,
        fieldType: Type,
        targetParamCount: Int,
    ): InsnList {
        val il = InsnList()
        val receiverIndex = nextLocalIndex(target)
        val fieldValueIndex = receiverIndex + 1

        storeStackValue(il, fieldType, fieldValueIndex)
        il.add(VarInsnNode(Opcodes.ASTORE, receiverIndex))
        addHandlerOwner(il)
        il.add(VarInsnNode(Opcodes.ALOAD, receiverIndex))
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
        addReceiverCast(il, receiverType)
        loadFromVariable(il, fieldType, fieldValueIndex)

        return il
    }

    private fun addReceiverCast(
        il: InsnList,
        receiverType: Type,
    ) {
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (handlerReturnType != receiverType) {
            il.add(TypeInsnNode(Opcodes.CHECKCAST, receiverType.internalName))
        }
    }

    private fun validateHandlerSignature(
        target: MethodNode,
        receiverType: Type,
    ): Int {
        val asmParamTypes = Type.getArgumentTypes(asmMethod)
        if (asmParamTypes.isEmpty() || !isHandlerParameterCompatible(receiverType, asmParamTypes[0])) {
            throw IllegalArgumentException(
                "@ModifyReceiver handler ${asmMethod.name} first parameter must be $receiverType, " +
                    "actual ${asmParamTypes.toList()}",
            )
        }

        val asmReturnType = Type.getReturnType(asmMethod)
        if (!isReceiverReturnCompatible(receiverType, asmReturnType)) {
            throw IllegalArgumentException(
                "@ModifyReceiver handler ${asmMethod.name} return type $asmReturnType must be compatible with receiver type $receiverType",
            )
        }

        val targetParamTypes = Type.getArgumentTypes(target.desc)
        val requestedTargetParamCount = asmParamTypes.size - 1
        if (requestedTargetParamCount > targetParamTypes.size) {
            throw IllegalArgumentException(
                "@ModifyReceiver handler ${asmMethod.name} requests $requestedTargetParamCount target parameter(s), " +
                    "but target method ${target.name}${target.desc} has only ${targetParamTypes.size}",
            )
        }

        for (index in 0 until requestedTargetParamCount) {
            val expected = targetParamTypes[index]
            val actual = asmParamTypes[index + 1]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalArgumentException(
                    "@ModifyReceiver handler ${asmMethod.name} target parameter #$index mismatch: " +
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

    private fun isReceiverReturnCompatible(
        receiverType: Type,
        handlerReturnType: Type,
    ): Boolean {
        if (handlerReturnType == Type.VOID_TYPE) {
            return false
        }
        if (receiverType == handlerReturnType) {
            return true
        }
        if (!receiverType.isReferenceType() || !handlerReturnType.isReferenceType()) {
            return false
        }
        if (handlerReturnType.sort == Type.OBJECT &&
            (handlerReturnType.internalName == "java/lang/Object" || handlerReturnType.internalName == "kotlin/Any")
        ) {
            return true
        }
        return runCatching {
            val receiverClass = loadReferenceClass(receiverType)
            receiverClass.isAssignableFrom(asmMethod.returnType)
        }.getOrDefault(false)
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
            "Only INVOKE slice boundaries are supported for @ModifyReceiver: ${at.value}"
        }

        val (boundaryOwner, boundaryName, boundaryDesc) = parseTargetMethod(at.target)
        if (boundaryName == null || boundaryDesc == null) {
            throw IllegalArgumentException(
                "Invalid ModifyReceiver slice boundary method signature: ${at.target} " +
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

    private fun parseFieldTarget(signature: String): FieldTarget {
        if (signature.isEmpty()) {
            return FieldTarget(null, null, null)
        }

        val ownerAndName: String
        val desc: String?
        val colonIndex = signature.indexOf(':')
        if (colonIndex >= 0) {
            ownerAndName = signature.substring(0, colonIndex)
            desc = signature.substring(colonIndex + 1)
        } else {
            ownerAndName = signature
            desc = null
        }

        val slashIndex = ownerAndName.lastIndexOf('/')
        val dotIndex = ownerAndName.lastIndexOf('.')
        val separatorIndex = maxOf(slashIndex, dotIndex)
        return if (separatorIndex >= 0) {
            FieldTarget(
                ownerAndName.substring(0, separatorIndex).replace('.', '/'),
                ownerAndName.substring(separatorIndex + 1),
                desc,
            )
        } else {
            FieldTarget(null, ownerAndName, desc)
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
    }
}
