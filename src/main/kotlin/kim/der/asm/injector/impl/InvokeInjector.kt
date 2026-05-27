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
import kim.der.asm.utils.transformer.InstructionUtil
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * INVOKE 注入器。
 *
 * 根据 [kim.der.asm.api.annotation.AsmInject.at] 定位目标方法调用，
 * 并按 shift 语义在调用前、调用后或替换调用点插入 ASM 方法调用。
 * 当注解缺少目标调用签名时返回未修改。BEFORE/AFTER handler 可先接收原调用参数前缀，
 * 再继续接收目标方法参数前缀；REPLACE handler 保持替换原调用的参数与返回值语义。
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
class InvokeInjector(
    method: Method,
    asmInfo: AsmInfo,
) : AbstractAsmInjector(method, asmInfo) {
    /**
     * 在匹配的调用点附近注入 ASM 调用。
     *
     * @param target 目标方法
     * @return 至少命中一个调用点并插入指令时返回 `true`
     * @throws IllegalArgumentException 调用点签名无法解析时抛出
     * @throws RuntimeException 调用点参数、目标方法参数映射或字节码结构不合法时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun inject(target: MethodNode): Boolean = injectCount(target) > 0

    override fun injectCount(target: MethodNode): Int {
        // 从 @AsmInject 注解中获取 @At 信息
        val injectAnnotation =
            asmMethod.getAnnotation(AsmInject::class.java)
                ?: return 0

        val at = injectAnnotation.at
        val targetMethodSignature = at.target

        if (targetMethodSignature.isEmpty()) {
            return 0
        }

        // 解析目标方法签名
        val (targetOwner, targetName, targetDesc) = parseTargetMethod(targetMethodSignature)

        if (targetName == null || targetDesc == null) {
            throw IllegalArgumentException(
                "Invalid target method signature: $targetMethodSignature " +
                    "(parsed: owner=$targetOwner, name=$targetName, desc=$targetDesc)",
            )
        }

        val instructions = target.instructions
        var injectionCount = 0
        val insns = instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns, injectAnnotation.slice)

        // 查找所有匹配的方法调用
        var matchedOrdinal = 0
        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }

            if (insn is MethodInsnNode) {
                if (matchesTargetMethod(insn, targetOwner, targetName, targetDesc)) {
                    val currentOrdinal = matchedOrdinal++
                    if (!matchesOrdinal(currentOrdinal, injectAnnotation.ordinal)) {
                        continue
                    }

                    when (at.shift) {
                        Shift.BEFORE -> {
                            injectBeforeCall(instructions, insn, target)
                            injectionCount++
                        }
                        Shift.AFTER -> {
                            injectAfterCall(instructions, insn, target)
                            injectionCount++
                        }
                        Shift.REPLACE -> {
                            replaceCall(instructions, insn, target)
                            injectionCount++
                        }
                    }
                }
            }
        }

        return injectionCount
    }

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

    private fun hasSliceBoundary(at: At): Boolean = at.target.isNotEmpty()

    private fun emptySlice(insns: Array<AbstractInsnNode>): Pair<Int, Int> = insns.size to insns.size

    private fun findSliceBoundaryIndex(
        insns: Array<AbstractInsnNode>,
        at: At,
        startIndex: Int,
    ): Int? {
        require(at.value == InjectionPoint.INVOKE) {
            "Only INVOKE slice boundaries are supported for @AsmInject(INVOKE): ${at.value}"
        }

        val (boundaryOwner, boundaryName, boundaryDesc) = parseTargetMethod(at.target)
        if (boundaryName == null || boundaryDesc == null) {
            throw IllegalArgumentException(
                "Invalid slice boundary method signature: ${at.target} " +
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

    private fun matchesOrdinal(
        currentOrdinal: Int,
        requestedOrdinal: Int,
    ): Boolean = requestedOrdinal < 0 || currentOrdinal == requestedOrdinal

    /**
     * 解析目标方法签名
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
        val desc: String
        val lastDot = ownerAndName.lastIndexOf('.')
        val lastSlash = ownerAndName.lastIndexOf('/')
        val separator = if (lastDot > lastSlash) lastDot else lastSlash

        return if (separator > 0) {
            // 包含类名
            val owner = ownerAndName.substring(0, separator).replace('.', '/')
            val methodName = ownerAndName.substring(separator + 1)
            desc = signature.substring(parenIndex)
            Triple(owner, methodName, desc)
        } else {
            // 只有方法名
            val methodName = ownerAndName
            desc = signature.substring(parenIndex)
            Triple(null, methodName, desc)
        }
    }

    /**
     * 检查是否匹配目标方法
     */
    private fun matchesTargetMethod(
        insn: MethodInsnNode,
        targetOwner: String?,
        targetName: String,
        targetDesc: String,
    ): Boolean {
        // 检查所有者
        if (targetOwner != null && insn.owner != targetOwner) {
            return false
        }

        // 检查方法名
        if (insn.name != targetName) {
            return false
        }

        // 检查描述符
        if (insn.desc != targetDesc) {
            return false
        }

        return true
    }

    /**
     * 在方法调用前注入
     */
    private fun injectBeforeCall(
        instructions: InsnList,
        callInsn: MethodInsnNode,
        targetMethod: MethodNode,
    ) {
        val il = InsnList()

        // 保存方法调用的参数到局部变量
        val paramTypes = Type.getArgumentTypes(callInsn.desc)
        val savedParams = mutableListOf<Int>()
        var nextVarIndex = allocateVariablesForParams(targetMethod, paramTypes, callInsn.opcode != Opcodes.INVOKESTATIC)

        // 保存所有参数（从右到左）
        for (i in paramTypes.indices.reversed()) {
            val paramType = paramTypes[i]

            nextVarIndex -= paramType.size
            saveParameter(il, paramType, nextVarIndex)
            savedParams.add(0, nextVarIndex)
        }

        // 如果是实例方法调用，保存实例引用
        var savedInstanceIndex: Int? = null
        if (callInsn.opcode != Opcodes.INVOKESTATIC) {
            nextVarIndex -= 1
            savedInstanceIndex = nextVarIndex
            il.add(VarInsnNode(Opcodes.ASTORE, savedInstanceIndex))
        }

        val callbackVarIndex = createCallbackInfoIfNeeded(il, targetMethod, paramTypes, savedParams, savedInstanceIndex)

        // 生成调用 ASM 方法的指令，参数来自已保存的调用点参数和目标方法参数。
        generateCallSiteHandlerCall(
            il,
            targetMethod,
            paramTypes,
            savedParams,
            callbackVarIndex,
        )
        dropUnusedHandlerReturnValue(il)

        // 恢复参数（从左到右）
        if (savedInstanceIndex != null) {
            il.add(VarInsnNode(Opcodes.ALOAD, savedInstanceIndex))
        }

        for (savedIndex in savedParams) {
            val paramIndex = savedParams.indexOf(savedIndex)
            val paramType = paramTypes[paramIndex]
            InstructionUtil.loadParam(paramType, savedIndex).let { il.add(it) }
        }

        // 在调用前插入
        instructions.insertBefore(callInsn, il)
    }

    /**
     * 在方法调用后注入
     */
    private fun injectAfterCall(
        instructions: InsnList,
        callInsn: MethodInsnNode,
        targetMethod: MethodNode,
    ) {
        // 查找调用后的位置
        val nextInsn = callInsn.next
        val paramTypes = Type.getArgumentTypes(callInsn.desc)
        val savedParams = mutableListOf<Int>()
        var nextVarIndex = allocateVariablesForParams(targetMethod, paramTypes, callInsn.opcode != Opcodes.INVOKESTATIC)
        val beforeCall = InsnList()

        for (i in paramTypes.indices.reversed()) {
            val paramType = paramTypes[i]

            nextVarIndex -= paramType.size
            saveParameter(beforeCall, paramType, nextVarIndex)
            savedParams.add(0, nextVarIndex)
        }

        var savedInstanceIndex: Int? = null
        if (callInsn.opcode != Opcodes.INVOKESTATIC) {
            nextVarIndex -= 1
            savedInstanceIndex = nextVarIndex
            beforeCall.add(VarInsnNode(Opcodes.ASTORE, savedInstanceIndex))
        }

        if (savedInstanceIndex != null) {
            beforeCall.add(VarInsnNode(Opcodes.ALOAD, savedInstanceIndex))
        }

        for (savedIndex in savedParams) {
            val paramIndex = savedParams.indexOf(savedIndex)
            val paramType = paramTypes[paramIndex]
            InstructionUtil.loadParam(paramType, savedIndex).let { beforeCall.add(it) }
        }

        if (beforeCall.size() > 0) {
            instructions.insertBefore(callInsn, beforeCall)
        }

        // 跳过调用本身（如果有返回值，跳过返回值）
        val returnType = Type.getReturnType(callInsn.desc)
        if (returnType != Type.VOID_TYPE) {
            // 返回值在栈顶，需要保存
            val il = InsnList()
            val returnVarIndex = allocateVariableAfterSavedCallState(targetMethod, paramTypes, savedParams, savedInstanceIndex)

            // 保存返回值
            saveReturnValue(il, returnType, returnVarIndex)

            val callbackVarIndex =
                createCallbackInfoIfNeeded(
                    il,
                    targetMethod,
                    paramTypes + returnType,
                    savedParams + returnVarIndex,
                    savedInstanceIndex,
                )

            // 生成调用 ASM 方法的指令。
            generateCallSiteHandlerCall(
                il,
                targetMethod,
                paramTypes,
                savedParams,
                callbackVarIndex,
            )
            dropUnusedHandlerReturnValue(il)

            // 恢复返回值
            loadReturnValue(il, returnType, returnVarIndex)

            instructions.insertBefore(nextInsn, il)
        } else {
            // 无返回值，直接在调用后插入
            val il = InsnList()
            val callbackVarIndex = createCallbackInfoIfNeeded(il, targetMethod, paramTypes, savedParams, savedInstanceIndex)
            generateCallSiteHandlerCall(
                il,
                targetMethod,
                paramTypes,
                savedParams,
                callbackVarIndex,
            )
            dropUnusedHandlerReturnValue(il)
            instructions.insertBefore(nextInsn, il)
        }
    }

    /**
     * 替换方法调用
     */
    private fun replaceCall(
        instructions: InsnList,
        callInsn: MethodInsnNode,
        targetMethod: MethodNode,
    ) {
        val il = InsnList()

        // 保存参数
        val paramTypes = Type.getArgumentTypes(callInsn.desc)
        val savedParams = mutableListOf<Int>()
        var nextVarIndex = allocateVariablesForParams(targetMethod, paramTypes, callInsn.opcode != Opcodes.INVOKESTATIC)

        for (i in paramTypes.indices.reversed()) {
            val paramType = paramTypes[i]

            nextVarIndex -= paramType.size
            saveParameter(il, paramType, nextVarIndex)
            savedParams.add(0, nextVarIndex)
        }

        var savedInstanceIndex: Int? = null
        if (callInsn.opcode != Opcodes.INVOKESTATIC) {
            nextVarIndex -= 1
            savedInstanceIndex = nextVarIndex
            il.add(VarInsnNode(Opcodes.ASTORE, savedInstanceIndex))
        }

        val callbackVarIndex = createCallbackInfoIfNeeded(il, targetMethod, paramTypes, savedParams, savedInstanceIndex)

        // 调用 ASM 方法替换原调用
        val mockTarget = createMockMethodNode(targetMethod, callInsn)
        validateReplaceSignature(callInsn)
        AsmMethodCallGenerator.generateMethodCall(
            il,
            asmMethod,
            asmInfo,
            mockTarget,
            callbackVarIndex,
        )

        // 处理返回值类型转换
        val originalReturnType = Type.getReturnType(callInsn.desc)
        val asmReturnType = Type.getReturnType(asmMethod)

        if (asmReturnType != originalReturnType) {
            if (asmReturnType != Type.VOID_TYPE && originalReturnType == Type.VOID_TYPE) {
                // ASM 返回了值，但原方法返回 void，弹出
                il.add(InsnNode(if (asmReturnType.size == 2) Opcodes.POP2 else Opcodes.POP))
            } else if (originalReturnType.sort == Type.OBJECT || originalReturnType.sort == Type.ARRAY) {
                il.add(TypeInsnNode(Opcodes.CHECKCAST, originalReturnType.internalName))
            }
        }

        // 替换原始调用
        instructions.insertBefore(callInsn, il)
        instructions.remove(callInsn)
    }

    private fun dropUnusedHandlerReturnValue(il: InsnList) {
        val returnType = Type.getReturnType(asmMethod)
        if (returnType == Type.VOID_TYPE) {
            return
        }

        il.add(InsnNode(if (returnType.size == 2) Opcodes.POP2 else Opcodes.POP))
    }

    private fun generateCallSiteHandlerCall(
        il: InsnList,
        targetMethod: MethodNode,
        callParamTypes: Array<Type>,
        savedParamIndexes: List<Int>,
        callbackVarIndex: Int?,
    ) {
        val instanceType = Type.getType(asmInfo.asmClass)
        val useStaticCall = Modifier.isStatic(asmMethod.modifiers)

        if (!useStaticCall) {
            loadAsmHandlerReceiver(il, instanceType)
        }

        if (callbackVarIndex != null) {
            il.add(VarInsnNode(Opcodes.ALOAD, callbackVarIndex))
        }

        loadCallSiteHandlerArguments(il, targetMethod, callParamTypes, savedParamIndexes, callbackVarIndex != null)

        il.add(
            MethodInsnNode(
                if (useStaticCall) Opcodes.INVOKESTATIC else Opcodes.INVOKEVIRTUAL,
                instanceType.internalName,
                asmMethod.name,
                Type.getMethodDescriptor(asmMethod),
                false,
            ),
        )
    }

    private fun loadAsmHandlerReceiver(
        il: InsnList,
        instanceType: Type,
    ) {
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

        val targetClassInternalName =
            asmInfo.targets.firstOrNull()?.replace('.', '/')
                ?: instanceType.internalName
        val singletonFieldName = "\$asmInstance\$${asmInfo.asmClass.simpleName}"
        val singletonFieldDesc = "L${instanceType.internalName};"
        val notNullLabel = LabelNode()
        val endLabel = LabelNode()

        il.add(FieldInsnNode(Opcodes.GETSTATIC, targetClassInternalName, singletonFieldName, singletonFieldDesc))
        il.add(InsnNode(Opcodes.DUP))
        il.add(JumpInsnNode(Opcodes.IFNONNULL, notNullLabel))
        il.add(InsnNode(Opcodes.POP))
        il.add(TypeInsnNode(Opcodes.NEW, instanceType.internalName))
        il.add(InsnNode(Opcodes.DUP))
        il.add(MethodInsnNode(Opcodes.INVOKESPECIAL, instanceType.internalName, "<init>", "()V", false))
        il.add(InsnNode(Opcodes.DUP))
        il.add(FieldInsnNode(Opcodes.PUTSTATIC, targetClassInternalName, singletonFieldName, singletonFieldDesc))
        il.add(JumpInsnNode(Opcodes.GOTO, endLabel))
        il.add(notNullLabel)
        il.add(endLabel)
    }

    private fun loadCallSiteHandlerArguments(
        il: InsnList,
        targetMethod: MethodNode,
        callParamTypes: Array<Type>,
        savedParamIndexes: List<Int>,
        skipCallbackInfo: Boolean,
    ) {
        val asmParamTypes = Type.getArgumentTypes(asmMethod)
        val handlerParamStart = if (skipCallbackInfo) 1 else 0
        val requestedHandlerParamCount = asmParamTypes.size - handlerParamStart
        val requestedCallParamCount = minOf(requestedHandlerParamCount, callParamTypes.size)

        val targetParamTypes = Type.getArgumentTypes(targetMethod.desc)
        val requestedTargetParamCount = requestedHandlerParamCount - requestedCallParamCount
        if (requestedTargetParamCount > targetParamTypes.size) {
            throw IllegalStateException(
                "Invoke handler ${asmMethod.name} requests $requestedTargetParamCount target parameter(s), " +
                    "but target method ${targetMethod.name}${targetMethod.desc} has only ${targetParamTypes.size}",
            )
        }

        for (index in 0 until requestedCallParamCount) {
            val expected = callParamTypes[index]
            val actual = asmParamTypes[handlerParamStart + index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalStateException(
                    "Invoke handler ${asmMethod.name} parameter #${handlerParamStart + index} mismatch: " +
                        "expected call argument $expected, actual $actual",
                )
            }

            InstructionUtil.loadParam(expected, savedParamIndexes[index]).let { il.add(it) }
        }

        var targetVarIndex = if ((targetMethod.access and Opcodes.ACC_STATIC) != 0) 0 else 1
        for (index in 0 until requestedTargetParamCount) {
            val expected = targetParamTypes[index]
            val actual = asmParamTypes[handlerParamStart + requestedCallParamCount + index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalStateException(
                    "Invoke handler ${asmMethod.name} target parameter #$index mismatch: " +
                        "expected $expected, actual $actual",
                )
            }

            InstructionUtil.loadParam(expected, targetVarIndex).let { il.add(it) }
            targetVarIndex += expected.size
        }
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

    /**
     * 保存参数
     */
    private fun saveParameter(
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

    /**
     * 保存返回值
     */
    private fun saveReturnValue(
        il: InsnList,
        returnType: Type,
        varIndex: Int,
    ) {
        saveParameter(il, returnType, varIndex)
    }

    /**
     * 加载返回值
     */
    private fun loadReturnValue(
        il: InsnList,
        returnType: Type,
        varIndex: Int,
    ) {
        val loadInsn = InstructionUtil.loadParam(returnType, varIndex)
        il.add(loadInsn)
    }

    private fun validateReplaceSignature(callInsn: MethodInsnNode) {
        val originalReturnType = Type.getReturnType(callInsn.desc)
        val asmReturnType = Type.getReturnType(asmMethod)
        if (!isReplaceReturnCompatible(originalReturnType, asmReturnType)) {
            throw IllegalStateException(
                "Invoke REPLACE handler ${asmMethod.name} return type mismatch: original $originalReturnType, handler $asmReturnType",
            )
        }
    }

    private fun createCallbackInfoIfNeeded(
        il: InsnList,
        targetMethod: MethodNode,
        savedParamTypes: Array<Type>,
        savedParamIndexes: List<Int>,
        savedInstanceIndex: Int?,
    ): Int? {
        if (!AsmMethodCallGenerator.needsCallbackInfo(asmMethod)) {
            return null
        }

        AsmMethodCallGenerator.generateCallbackInfoCreation(il)
        val callbackVarIndex = allocateVariableAfterSavedCallState(targetMethod, savedParamTypes, savedParamIndexes, savedInstanceIndex)
        il.add(VarInsnNode(Opcodes.ASTORE, callbackVarIndex))
        return callbackVarIndex
    }

    private fun allocateVariableAfterSavedCallState(
        targetMethod: MethodNode,
        savedParamTypes: Array<Type>,
        savedParamIndexes: List<Int>,
        savedInstanceIndex: Int?,
    ): Int {
        var nextIndex = findLocalEnd(targetMethod)

        savedParamIndexes.forEachIndexed { index, savedIndex ->
            val paramType = savedParamTypes[index]
            nextIndex = maxOf(nextIndex, savedIndex + paramType.size)
        }

        if (savedInstanceIndex != null) {
            nextIndex = maxOf(nextIndex, savedInstanceIndex + 1)
        }

        return nextIndex
    }

    private fun findLocalEnd(targetMethod: MethodNode): Int {
        val isStatic = (targetMethod.access and Opcodes.ACC_STATIC) != 0
        var maxIndex = if (isStatic) 0 else 1

        val methodParamTypes = Type.getArgumentTypes(targetMethod.desc)
        for (paramType in methodParamTypes) {
            maxIndex += paramType.size
        }

        for (localVar in targetMethod.localVariables) {
            val size = Type.getType(localVar.desc).size
            maxIndex = maxOf(maxIndex, localVar.index + size)
        }

        for (insn in targetMethod.instructions.toArray()) {
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

    private fun isReplaceReturnCompatible(
        original: Type,
        replacement: Type,
    ): Boolean {
        if (original == Type.VOID_TYPE) return true
        if (replacement == Type.VOID_TYPE) return false
        if (original == replacement) return true
        return (original.sort == Type.OBJECT || original.sort == Type.ARRAY) &&
            (replacement.sort == Type.OBJECT || replacement.sort == Type.ARRAY)
    }

    /**
     * 为参数分配局部变量
     */
    private fun allocateVariablesForParams(
        targetMethod: MethodNode,
        paramTypes: Array<Type>,
        reserveInstanceSlot: Boolean,
    ): Int {
        var neededSlots = if (reserveInstanceSlot) 1 else 0
        for (paramType in paramTypes) {
            neededSlots += paramType.size
        }

        return findLocalEnd(targetMethod) + neededSlots
    }

    /**
     * 为返回值分配局部变量
     */
    private fun allocateVariableForReturn(
        targetMethod: MethodNode,
        returnType: Type,
    ): Int = findLocalEnd(targetMethod)

    /**
     * 创建模拟方法节点
     */
    private fun createMockMethodNode(
        targetMethod: MethodNode,
        callInsn: MethodInsnNode,
    ): MethodNode {
        val paramTypes = Type.getArgumentTypes(callInsn.desc)
        val returnType = Type.getReturnType(asmMethod)
        val mockDesc = Type.getMethodDescriptor(returnType, *paramTypes)
        return MethodNode(
            targetMethod.access,
            targetMethod.name,
            mockDesc,
            targetMethod.signature,
            targetMethod.exceptions?.toTypedArray(),
        )
    }

}
