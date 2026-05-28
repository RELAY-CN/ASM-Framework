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
import org.objectweb.asm.tree.*
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * ModifyArg 注入器。
 *
 * 在目标方法开头读取指定参数槽位，调用 ASM 方法得到新值后写回原槽位。
 * 默认按参数索引直接修改方法入口处的参数值；当 [at] 指向 [InjectionPoint.INVOKE] 时，
 * 会改写匹配普通方法调用、构造器调用或 `invokedynamic` 调用点的指定调用参数。
 * handler 的第一个参数是被修改的原参数；对象或数组参数可声明为原值类型的父类、接口、`Any` 或 `Object` 接收，
 * 返回类型对基础类型仍需精确匹配，对象或数组类型可返回可赋值给原参数类型的子类型。后续可按顺序接收目标方法参数前缀。
 *
 * @param argIndex 要修改的目标参数索引，从 0 开始
 * @param at 调用点定位；[InjectionPoint.INVOKE] 时使用 [At.target] 匹配目标方法调用、构造器调用或 `invokedynamic` 调用
 * @param ordinal 匹配调用点序号；负数表示处理全部匹配调用点
 * @param slice 切片范围；当前仅 [InjectionPoint.INVOKE] 调用点参数修改使用 INVOKE 边界缩小匹配范围
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
class ModifyArgInjector(
    method: Method,
    asmInfo: AsmInfo,
    private val argIndex: Int,
    private val at: At = At(),
    private val ordinal: Int = -1,
    private val slice: Slice = Slice(),
) : AbstractAsmInjector(method, asmInfo) {
    /**
     * 在目标方法入口修改指定参数。
     *
     * @param target 目标方法
     * @return 参数索引合法且成功插入修改逻辑时返回 `true`
     * @throws IllegalArgumentException 参数索引非法、ASM 方法参数或返回类型不匹配时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun inject(target: MethodNode): Boolean = injectCount(target) > 0

    /**
     * 修改目标方法参数或匹配调用点参数并返回实际修改数量。
     *
     * @param target 目标方法
     * @return 实际写入参数修改逻辑的数量；入口参数模式最多为 1，INVOKE 模式为匹配调用点数量
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun injectCount(target: MethodNode): Int {
        if (at.value == InjectionPoint.INVOKE) {
            return modifyCallArgumentCount(target)
        }

        val targetParamTypes = Type.getArgumentTypes(target.desc)

        // 验证参数索引
        if (argIndex < 0 || argIndex >= targetParamTypes.size) {
            throw IllegalArgumentException("Invalid argument index: $argIndex (method has ${targetParamTypes.size} parameters)")
        }

        val paramType = targetParamTypes[argIndex]

        // 直接在方法开头修改参数
        return if (modifyParameterAtMethodStart(target, paramType, argIndex)) 1 else 0
    }

    private fun modifyCallArgumentCount(target: MethodNode): Int {
        val (targetOwner, targetName, targetDesc) = parseTargetMethod(at.target)
        if (targetName == null) {
            throw IllegalArgumentException("@ModifyArg INVOKE requires at.target method signature")
        }

        var injectionCount = 0
        var matchedOrdinal = 0
        val insns = target.instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)
        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }

            val callSite = describeMatchedCallSite(insn, targetOwner, targetName, targetDesc) ?: continue

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val callParamTypes = Type.getArgumentTypes(callSite.desc)
            if (argIndex < 0 || argIndex >= callParamTypes.size) {
                throw IllegalArgumentException("Invalid argument index: $argIndex (call has ${callParamTypes.size} parameters)")
            }

            val paramType = callParamTypes[argIndex]
            val targetParamCount = validateHandlerSignature(target, paramType)
            val il = buildCallArgumentModification(target, callSite.hasReceiver, callParamTypes, paramType, targetParamCount)
            target.instructions.insertBefore(insn, il)
            injectionCount++
        }

        return injectionCount
    }

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
            "Only INVOKE slice boundaries are supported for @ModifyArg(INVOKE): ${at.value}"
        }

        val (boundaryOwner, boundaryName, boundaryDesc) = parseTargetMethod(at.target)
        if (boundaryName == null || boundaryDesc == null) {
            throw IllegalArgumentException(
                "Invalid ModifyArg slice boundary method signature: ${at.target} " +
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

    private fun buildCallArgumentModification(
        target: MethodNode,
        hasReceiver: Boolean,
        callParamTypes: Array<Type>,
        selectedParamType: Type,
        targetParamCount: Int,
    ): InsnList {
        val il = InsnList()
        val firstTempIndex = nextLocalIndex(target)
        var nextTempIndex = firstTempIndex
        val receiverIndex =
            if (hasReceiver) {
                nextTempIndex.also { nextTempIndex += 1 }
            } else {
                null
            }
        val argSlots =
            callParamTypes.map { paramType ->
                nextTempIndex.also { nextTempIndex += paramType.size }
            }

        for (i in callParamTypes.indices.reversed()) {
            storeStackValue(il, callParamTypes[i], argSlots[i])
        }
        if (receiverIndex != null) {
            il.add(VarInsnNode(Opcodes.ASTORE, receiverIndex))
        }

        addHandlerOwner(il)
        loadFromVariable(il, selectedParamType, argSlots[argIndex])
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
        addArgumentCastIfNeeded(il, selectedParamType)
        storeStackValue(il, selectedParamType, argSlots[argIndex])

        if (receiverIndex != null) {
            il.add(VarInsnNode(Opcodes.ALOAD, receiverIndex))
        }
        for (i in callParamTypes.indices) {
            loadFromVariable(il, callParamTypes[i], argSlots[i])
        }

        return il
    }

    /**
     * 在方法开头修改参数
     */
    private fun modifyParameterAtMethodStart(
        target: MethodNode,
        paramType: Type,
        argIndex: Int,
    ): Boolean {
        val isStatic = (target.access and Opcodes.ACC_STATIC) != 0
        val il = InsnList()

        // 计算参数在局部变量中的索引
        var varIndex = if (isStatic) 0 else 1
        val paramTypes = Type.getArgumentTypes(target.desc)

        for (i in 0 until argIndex) {
            val pType = paramTypes[i]
            varIndex += if (pType.sort == Type.LONG || pType.sort == Type.DOUBLE) 2 else 1
        }

        // 调用 ASM 方法修改参数
        // ASM 方法应该接收原始参数值并返回修改后的值
        val targetParamCount = validateHandlerSignature(target, paramType)

        // 获取 ASM 实例并生成调用
        val instanceType =
            Type
                .getType(asmInfo.asmClass)
        val isKotlinObject = isKotlinObject()
        val useStaticCall = isHandlerStatic()

        if (!useStaticCall) {
            if (isKotlinObject) {
                // Kotlin object：加载 INSTANCE 字段
                il.add(
                    FieldInsnNode(
                        Opcodes.GETSTATIC,
                        instanceType.internalName,
                        "INSTANCE",
                        "L${instanceType.internalName};",
                    ),
                )
            } else {
                // 普通类：创建新实例
                il.add(
                    TypeInsnNode(Opcodes.NEW, instanceType.internalName),
                )
                il.add(
                    InsnNode(Opcodes.DUP),
                )
                il.add(
                    MethodInsnNode(
                        Opcodes.INVOKESPECIAL,
                        instanceType.internalName,
                        "<init>",
                        "()V",
                        false,
                    ),
                )
            }
        }

        // 实例调用必须先加载 receiver，再加载方法参数。
        loadFromVariable(il, paramType, varIndex)
        loadTargetMethodParameters(il, target, targetParamCount)

        il.add(
            MethodInsnNode(
                if (useStaticCall) Opcodes.INVOKESTATIC else Opcodes.INVOKEVIRTUAL,
                instanceType.internalName,
                asmMethod.name,
                Type
                    .getMethodDescriptor(asmMethod),
                false,
            ),
        )
        addArgumentCastIfNeeded(il, paramType)

        // 保存修改后的值回参数位置（ASM 方法的返回值现在在栈顶）
        when (paramType.sort) {
            Type.BOOLEAN, Type.BYTE, Type.SHORT, Type.INT, Type.CHAR -> {
                il.add(VarInsnNode(Opcodes.ISTORE, varIndex))
            }
            Type.LONG -> {
                il.add(VarInsnNode(Opcodes.LSTORE, varIndex))
            }
            Type.FLOAT -> {
                il.add(VarInsnNode(Opcodes.FSTORE, varIndex))
            }
            Type.DOUBLE -> {
                il.add(VarInsnNode(Opcodes.DSTORE, varIndex))
            }
            else -> {
                il.add(VarInsnNode(Opcodes.ASTORE, varIndex))
            }
        }

        // 在方法开头插入
        if (target.instructions.size() == 0) {
            target.instructions.add(il)
        } else {
            target.instructions.insertBefore(target.instructions.first, il)
        }

        return true
    }

    /**
     * 保存到变量
     */
    private fun saveToVariable(
        il: InsnList,
        paramType: Type,
        fromIndex: Int,
        toIndex: Int,
    ) {
        // 先加载源值
        loadFromVariable(il, paramType, fromIndex)

        // 保存到目标变量
        when (paramType.sort) {
            Type.BOOLEAN, Type.BYTE, Type.SHORT, Type.INT, Type.CHAR -> {
                il.add(VarInsnNode(Opcodes.ISTORE, toIndex))
            }
            Type.LONG -> {
                il.add(VarInsnNode(Opcodes.LSTORE, toIndex))
            }
            Type.FLOAT -> {
                il.add(VarInsnNode(Opcodes.FSTORE, toIndex))
            }
            Type.DOUBLE -> {
                il.add(VarInsnNode(Opcodes.DSTORE, toIndex))
            }
            else -> {
                il.add(VarInsnNode(Opcodes.ASTORE, toIndex))
            }
        }
    }

    /**
     * 从变量加载
     */
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
            Type.BOOLEAN, Type.BYTE, Type.SHORT, Type.INT, Type.CHAR -> {
                il.add(VarInsnNode(Opcodes.ISTORE, varIndex))
            }
            Type.LONG -> {
                il.add(VarInsnNode(Opcodes.LSTORE, varIndex))
            }
            Type.FLOAT -> {
                il.add(VarInsnNode(Opcodes.FSTORE, varIndex))
            }
            Type.DOUBLE -> {
                il.add(VarInsnNode(Opcodes.DSTORE, varIndex))
            }
            else -> {
                il.add(VarInsnNode(Opcodes.ASTORE, varIndex))
            }
        }
    }

    private fun validateHandlerSignature(
        target: MethodNode,
        paramType: Type,
    ): Int {
        val asmParamTypes = Type.getArgumentTypes(asmMethod)
        if (asmParamTypes.isEmpty() || !isHandlerParameterCompatible(paramType, asmParamTypes[0])) {
            throw IllegalArgumentException(
                "ASM method ${asmMethod.name} first parameter must be $paramType or compatible Object/Any, " +
                    "actual ${asmParamTypes.toList()}",
            )
        }

        val asmReturnType = Type.getReturnType(asmMethod)
        if (!isHandlerReturnCompatible(paramType, asmReturnType)) {
            throw IllegalArgumentException(
                "ASM method return type ($asmReturnType) must match parameter type ($paramType)",
            )
        }

        val targetParamTypes = Type.getArgumentTypes(target.desc)
        val requestedTargetParamCount = asmParamTypes.size - 1
        if (requestedTargetParamCount > targetParamTypes.size) {
            throw IllegalArgumentException(
                "ASM method ${asmMethod.name} requests $requestedTargetParamCount target parameter(s), " +
                    "but target method ${target.name}${target.desc} has only ${targetParamTypes.size}",
            )
        }

        for (index in 0 until requestedTargetParamCount) {
            val expected = targetParamTypes[index]
            val actual = asmParamTypes[index + 1]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalArgumentException(
                    "ASM method ${asmMethod.name} target parameter #$index mismatch: expected $expected, actual $actual",
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
        targetParamType: Type,
        handlerReturnType: Type,
    ): Boolean {
        if (targetParamType == handlerReturnType) {
            return true
        }
        if (handlerReturnType == Type.VOID_TYPE) {
            return false
        }
        if (!targetParamType.isReferenceType() || !handlerReturnType.isReferenceType()) {
            return false
        }
        if (handlerReturnType.sort == Type.OBJECT &&
            (handlerReturnType.internalName == "java/lang/Object" || handlerReturnType.internalName == "kotlin/Any")
        ) {
            return true
        }
        return runCatching {
            val targetParamClass = loadReferenceClass(targetParamType)
            targetParamClass.isAssignableFrom(asmMethod.returnType)
        }.getOrDefault(false)
    }

    private fun Type.isReferenceType(): Boolean = sort == Type.OBJECT || sort == Type.ARRAY

    private fun addArgumentCastIfNeeded(
        il: InsnList,
        paramType: Type,
    ) {
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (paramType != handlerReturnType && paramType.isReferenceType()) {
            il.add(TypeInsnNode(Opcodes.CHECKCAST, paramType.internalName))
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

    private fun addHandlerOwner(il: InsnList) {
        if (isHandlerStatic()) {
            return
        }

        val instanceType = Type.getType(asmInfo.asmClass)
        if (isKotlinObject()) {
            il.add(
                FieldInsnNode(
                    Opcodes.GETSTATIC,
                    instanceType.internalName,
                    "INSTANCE",
                    "L${instanceType.internalName};",
                ),
            )
            return
        }

        il.add(TypeInsnNode(Opcodes.NEW, instanceType.internalName))
        il.add(InsnNode(Opcodes.DUP))
        il.add(MethodInsnNode(Opcodes.INVOKESPECIAL, instanceType.internalName, "<init>", "()V", false))
    }

    private fun handlerOpcode(): Int =
        if (isHandlerStatic()) {
            Opcodes.INVOKESTATIC
        } else {
            Opcodes.INVOKEVIRTUAL
        }

    private fun isHandlerStatic(): Boolean = (asmMethod.modifiers and Modifier.STATIC) != 0

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

    private fun describeMatchedCallSite(
        insn: AbstractInsnNode,
        targetOwner: String?,
        targetName: String,
        targetDesc: String?,
    ): CallSite? =
        when (insn) {
            is MethodInsnNode ->
                if (matchesTargetMethod(insn, targetOwner, targetName, targetDesc)) {
                    CallSite(
                        desc = insn.desc,
                        hasReceiver = insn.opcode != Opcodes.INVOKESTATIC,
                    )
                } else {
                    null
                }
            is InvokeDynamicInsnNode ->
                if (matchesTargetInvokeDynamic(insn, targetOwner, targetName, targetDesc)) {
                    CallSite(desc = insn.desc, hasReceiver = false)
                } else {
                    null
                }
            else -> null
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
     * 分配临时变量
     */
    private fun allocateTempVariable(
        target: MethodNode,
        type: Type,
    ): Int {
        val isStatic = (target.access and Opcodes.ACC_STATIC) != 0
        var maxIndex = if (isStatic) 0 else 1

        // 计算所有参数占用的局部变量
        val paramTypes = Type.getArgumentTypes(target.desc)
        for (paramType in paramTypes) {
            maxIndex += if (paramType.sort == Type.LONG || paramType.sort == Type.DOUBLE) 2 else 1
        }

        // 查找最大局部变量索引
        for (localVar in target.localVariables) {
            val end = localVar.index + (if (needsDoubleSlot(localVar.desc)) 2 else 1)
            maxIndex = maxOf(maxIndex, end)
        }

        return maxIndex
    }

    /**
     * 释放临时变量
     */
    private fun freeTempVariable(
        target: MethodNode,
        varIndex: Int,
    ) {
        // 临时变量会在方法结束时自动释放，无需手动清理
        // 在需要优化局部变量使用的场景下，可以在这里添加变量复用逻辑
    }

    /**
     * 创建模拟方法节点用于参数加载
     */
    private fun createMockMethodNode(
        target: MethodNode,
        argIndex: Int,
        paramType: Type,
    ): MethodNode {
        // 创建一个只有一个参数（要修改的参数）的方法签名
        val mockDesc = Type.getMethodDescriptor(Type.getReturnType(asmMethod), paramType)
        return MethodNode(target.access, target.name, mockDesc, target.signature, target.exceptions?.toTypedArray())
    }

    /**
     * 检查是否需要双槽
     */
    private fun needsDoubleSlot(desc: String): Boolean = desc == "J" || desc == "D"

    private data class CallSite(
        val desc: String,
        val hasReceiver: Boolean,
    )
}
