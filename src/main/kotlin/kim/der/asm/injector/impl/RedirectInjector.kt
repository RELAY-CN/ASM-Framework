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
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Redirect 注入器。
 *
 * 查找目标方法中的匹配方法调用、构造器调用、NEW 构造表达式、字段读取、字段写入、简单数组元素访问、数组长度、
 * `CHECKCAST` 类型转换或 `INSTANCEOF` 类型判断指令，并用 ASM 方法调用替换原指令。
 *
 * 方法调用目标使用 `owner.name(desc)` 或 `name(desc)` 格式；字段读取目标使用
 * `owner.field:desc`、`field:desc` 或 `field` 格式。字段写入目标格式与字段读取相同，
 * 但需要将 [injectionPoint] 设置为 [InjectionPoint.FIELD_ASSIGN]。数组元素访问与数组长度使用 [InjectionPoint.FIELD]
 * 匹配产生数组引用的字段读取，并通过 [args] 中的 `array=get`、`array=set` 或 `array=length` 区分读取、写入与长度读取。
 * 构造器重定向可通过 [InjectionPoint.INVOKE] 与 `<init>` 目标匹配，也可通过 [InjectionPoint.NEW]
 * 与构造类型 internal name 或 binary name 匹配。类型转换使用 [InjectionPoint.CAST] 与类型 internal name 或 binary name 匹配；类型判断使用 [InjectionPoint.INSTANCEOF]
 * 与类型 internal name 或 binary name 匹配。
 * 方法调用、构造器调用、NEW 构造表达式、字段读取、字段写入、数组元素访问、数组长度、类型转换与类型判断重定向支持静态处理器、`@JvmStatic`
 * 处理器或 Kotlin `object` 实例处理器。处理器需先接收原调用、构造器、字段访问、类型转换或类型判断需要的栈参数，
 * 后续可按顺序接收目标方法的部分参数。
 *
 * @param redirectTarget 要重定向的方法调用、构造器调用、字段访问、构造类型或类型签名
 * @param injectionPoint Redirect 的定位点类型；[InjectionPoint.FIELD] 与 [InjectionPoint.FIELD_ASSIGN]
 * 会强制按字段访问语义解析目标，[InjectionPoint.NEW] 会按构造类型解析目标，
 * [InjectionPoint.CAST] 会按类型转换语义解析目标，[InjectionPoint.INSTANCEOF] 会按类型判断语义解析目标
 * @param ordinal 匹配点序号；负数表示重定向全部匹配点，当前用于方法调用、构造器调用、NEW 构造表达式、字段读取、字段写入、数组元素访问、数组长度、类型转换与类型判断重定向
 * @param slice 切片范围；当前方法调用、构造器调用、NEW 构造表达式、字段读取、字段写入、数组元素访问、数组长度、类型转换与类型判断重定向
 * 使用 [InjectionPoint.INVOKE] 边界缩小匹配范围
 * @param args 调用点附加参数；`array=get` 匹配数组元素读取，`array=set` 匹配数组元素写入，`array=length` 匹配数组长度
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
class RedirectInjector(
    method: Method,
    asmInfo: AsmInfo,
    private val redirectTarget: String,
    private val injectionPoint: InjectionPoint = InjectionPoint.INVOKE,
    private val ordinal: Int = -1,
    private val slice: Slice = Slice(),
    private val args: Array<String> = emptyArray(),
) : AbstractAsmInjector(method, asmInfo) {
    /**
     * 替换目标方法中的匹配调用点、构造器调用点、字段读取点、字段写入点、数组元素访问点、数组长度点、类型转换点或类型判断点。
     *
     * @param target 目标方法
     * @return 至少替换一个调用点、构造器调用点、字段读取点、字段写入点、数组元素访问点、数组长度点、类型转换点或类型判断点时返回 `true`
     * @throws IllegalArgumentException 目标方法调用或字段签名无法解析时抛出
     * @throws RuntimeException 替换调用、字段访问或返回值适配失败时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun inject(target: MethodNode): Boolean = injectCount(target) > 0

    /**
     * 替换目标方法中的匹配点并返回实际重定向数量。
     *
     * @param target 目标方法
     * @return 实际替换的调用点、构造器调用点、字段访问点、数组访问点、数组长度点、类型转换点或类型判断点数量
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun injectCount(target: MethodNode): Int {
        val arrayAccessMode = arrayAccessMode()
        if (arrayAccessMode != null) {
            return injectArrayAccessCount(target, arrayAccessMode)
        }
        if (isInstanceofRedirect()) {
            return injectInstanceofCount(target)
        }
        if (isCastRedirect()) {
            return injectCastCount(target)
        }
        if (isFieldAssignRedirect()) {
            return injectFieldAssignCount(target)
        }
        if (isFieldReadRedirect()) {
            return injectFieldReadCount(target)
        }
        if (isNewRedirect()) {
            return injectNewConstructorCount(target)
        }

        val (targetOwner, targetName, targetDesc) = parseTargetMethod(redirectTarget)

        if (targetName == null || targetDesc == null) {
            throw IllegalArgumentException(
                "Invalid target method signature: $redirectTarget " +
                    "(parsed: owner=$targetOwner, name=$targetName, desc=$targetDesc)",
            )
        }

        val instructions = target.instructions
        var injectionCount = 0
        val insns = instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)
        var matchedOrdinal = 0

        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }

            when {
                insn is MethodInsnNode && matchesTargetMethod(insn, targetOwner, targetName, targetDesc) -> {
                    val currentOrdinal = matchedOrdinal++
                    if (!matchesOrdinal(currentOrdinal)) {
                        continue
                    }
                    if (insn.name == "<init>") {
                        replaceConstructorCall(target, instructions, insn)
                    } else {
                        replaceMethodCall(target, instructions, insn)
                    }
                    injectionCount++
                }
                insn is InvokeDynamicInsnNode && matchesTargetInvokeDynamic(insn, targetOwner, targetName, targetDesc) -> {
                    val currentOrdinal = matchedOrdinal++
                    if (!matchesOrdinal(currentOrdinal)) {
                        continue
                    }
                    replaceInvokeDynamicCall(target, instructions, insn)
                    injectionCount++
                }
            }
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
            "Only INVOKE slice boundaries are supported for @Redirect: ${at.value}"
        }

        val (boundaryOwner, boundaryName, boundaryDesc) = parseTargetMethod(at.target)
        if (boundaryName == null || boundaryDesc == null) {
            throw IllegalArgumentException(
                "Invalid Redirect slice boundary method signature: ${at.target} " +
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

    private fun isFieldReadRedirect(): Boolean =
        injectionPoint == InjectionPoint.FIELD ||
            (redirectTarget.contains(':') && !redirectTarget.contains('('))

    private fun isFieldAssignRedirect(): Boolean = injectionPoint == InjectionPoint.FIELD_ASSIGN

    private fun isInstanceofRedirect(): Boolean = injectionPoint == InjectionPoint.INSTANCEOF

    private fun isCastRedirect(): Boolean = injectionPoint == InjectionPoint.CAST

    private fun isNewRedirect(): Boolean = injectionPoint == InjectionPoint.NEW

    private fun arrayAccessMode(): ArrayAccessMode? {
        val arrayArg = args.firstOrNull { it.trim().startsWith("array=") } ?: return null
        return when (arrayArg.substringAfter('=').trim().lowercase()) {
            "get" -> ArrayAccessMode.GET
            "set" -> ArrayAccessMode.SET
            "length" -> ArrayAccessMode.LENGTH
            else -> throw IllegalArgumentException("Unsupported Redirect array access mode: $arrayArg")
        }
    }

    private fun injectNewConstructorCount(target: MethodNode): Int {
        val typeTarget = redirectTarget.replace('.', '/')
        if (typeTarget.isEmpty()) {
            throw IllegalArgumentException("Redirect NEW target type must not be empty")
        }

        val instructions = target.instructions
        var injectionCount = 0
        val insns = instructions.toArray()
        var matchedOrdinal = 0
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)

        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (insn is TypeInsnNode && insn.opcode == Opcodes.NEW && insn.desc == typeTarget) {
                val currentOrdinal = matchedOrdinal++
                if (!matchesOrdinal(currentOrdinal)) {
                    continue
                }
                val constructorInsn = findConstructorInvocation(insn)
                replaceNewConstructorCall(target, instructions, insn, constructorInsn)
                injectionCount++
            }
        }

        return injectionCount
    }

    private fun injectArrayAccessCount(
        target: MethodNode,
        mode: ArrayAccessMode,
    ): Int {
        val fieldTarget = parseFieldTarget(redirectTarget)
        if (fieldTarget.name == null) {
            throw IllegalArgumentException("Invalid target array field signature: $redirectTarget")
        }
        if (fieldTarget.desc != null && Type.getType(fieldTarget.desc).sort != Type.ARRAY) {
            throw IllegalArgumentException("Redirect array access target must be an array field: $redirectTarget")
        }

        val instructions = target.instructions
        var injectionCount = 0
        val insns = instructions.toArray()
        var matchedOrdinal = 0
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)
        val targetOpcodes =
            when (mode) {
                ArrayAccessMode.GET -> ARRAY_READ_OPS
                ArrayAccessMode.SET -> ARRAY_WRITE_OPS
                ArrayAccessMode.LENGTH -> setOf(Opcodes.ARRAYLENGTH)
            }

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

            replaceArrayAccess(target, instructions, insn, fieldInsn, mode)
            injectionCount++
        }

        return injectionCount
    }

    private fun injectInstanceofCount(target: MethodNode): Int {
        val typeTarget = redirectTarget.replace('.', '/')
        if (typeTarget.isEmpty()) {
            throw IllegalArgumentException("Redirect INSTANCEOF target type must not be empty")
        }

        val instructions = target.instructions
        var injectionCount = 0
        val insns = instructions.toArray()
        var matchedOrdinal = 0
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)

        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (insn is TypeInsnNode && insn.opcode == Opcodes.INSTANCEOF && insn.desc == typeTarget) {
                val currentOrdinal = matchedOrdinal++
                if (!matchesOrdinal(currentOrdinal)) {
                    continue
                }
                replaceInstanceof(target, instructions, insn)
                injectionCount++
            }
        }

        return injectionCount
    }

    private fun injectCastCount(target: MethodNode): Int {
        val typeTarget = redirectTarget.replace('.', '/')
        if (typeTarget.isEmpty()) {
            throw IllegalArgumentException("Redirect CAST target type must not be empty")
        }

        val instructions = target.instructions
        var injectionCount = 0
        val insns = instructions.toArray()
        var matchedOrdinal = 0
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)

        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (insn is TypeInsnNode && insn.opcode == Opcodes.CHECKCAST && insn.desc == typeTarget) {
                val currentOrdinal = matchedOrdinal++
                if (!matchesOrdinal(currentOrdinal)) {
                    continue
                }
                replaceCast(target, instructions, insn)
                injectionCount++
            }
        }

        return injectionCount
    }

    private fun injectFieldReadCount(target: MethodNode): Int {
        val fieldTarget = parseFieldTarget(redirectTarget)
        if (fieldTarget.name == null) {
            throw IllegalArgumentException("Invalid target field signature: $redirectTarget")
        }

        val instructions = target.instructions
        var injectionCount = 0
        val insns = instructions.toArray()
        var matchedOrdinal = 0
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)

        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (insn is FieldInsnNode &&
                insn.opcode in FIELD_READ_OPS &&
                matchesTargetField(insn, fieldTarget)
            ) {
                val currentOrdinal = matchedOrdinal++
                if (!matchesOrdinal(currentOrdinal)) {
                    continue
                }
                replaceFieldRead(target, instructions, insn)
                injectionCount++
            }
        }

        return injectionCount
    }

    private fun injectFieldAssignCount(target: MethodNode): Int {
        val fieldTarget = parseFieldTarget(redirectTarget)
        if (fieldTarget.name == null) {
            throw IllegalArgumentException("Invalid target field signature: $redirectTarget")
        }

        val instructions = target.instructions
        var injectionCount = 0
        val insns = instructions.toArray()
        var matchedOrdinal = 0
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)

        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (insn is FieldInsnNode &&
                insn.opcode in FIELD_WRITE_OPS &&
                matchesTargetField(insn, fieldTarget)
            ) {
                val currentOrdinal = matchedOrdinal++
                if (!matchesOrdinal(currentOrdinal)) {
                    continue
                }
                replaceFieldAssign(target, instructions, insn)
                injectionCount++
            }
        }

        return injectionCount
    }

    /**
     * 解析目标方法签名。
     * 支持 owner 使用 slash 或 dot：
     * - java/lang/String.trim()Ljava/lang/String;
     * - java.lang.String.trim()Ljava/lang/String;
     * - trim()Ljava/lang/String;
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
            val owner = ownerAndName.substring(0, separatorIndex).replace('.', '/')
            val methodName = ownerAndName.substring(separatorIndex + 1)
            Triple(owner, methodName, desc)
        } else {
            Triple(null, ownerAndName, desc)
        }
    }

    private fun matchesTargetMethod(
        insn: MethodInsnNode,
        targetOwner: String?,
        targetName: String,
        targetDesc: String,
    ): Boolean {
        if (targetOwner != null && insn.owner != targetOwner) {
            return false
        }
        if (insn.name != targetName) {
            return false
        }
        return targetDesc.isEmpty() || insn.desc == targetDesc
    }

    private fun matchesTargetInvokeDynamic(
        insn: InvokeDynamicInsnNode,
        targetOwner: String?,
        targetName: String,
        targetDesc: String,
    ): Boolean {
        if (targetOwner != null && insn.bsm.owner != targetOwner) {
            return false
        }
        if (insn.name != targetName && insn.bsm.name != targetName) {
            return false
        }
        return targetDesc.isEmpty() || insn.desc == targetDesc
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

    private fun replaceMethodCall(
        target: MethodNode,
        instructions: InsnList,
        originalInsn: MethodInsnNode,
    ) {
        val targetParamCount = validateHandlerSignature(target, originalInsn)

        val il = InsnList()
        if (isHandlerStatic()) {
            loadTargetMethodParameters(il, target, targetParamCount)
            addStaticHandlerCall(il)
        } else {
            addObjectHandlerCall(target, originalInsn, il, targetParamCount)
        }

        val originalReturnType = Type.getReturnType(originalInsn.desc)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (originalReturnType == Type.VOID_TYPE && handlerReturnType != Type.VOID_TYPE) {
            il.add(InsnNode(if (handlerReturnType.size == 2) Opcodes.POP2 else Opcodes.POP))
        } else if (originalReturnType != handlerReturnType && originalReturnType.sort >= Type.ARRAY) {
            il.add(TypeInsnNode(Opcodes.CHECKCAST, originalReturnType.internalName))
        }

        instructions.insertBefore(originalInsn, il)
        instructions.remove(originalInsn)
    }

    private fun replaceInvokeDynamicCall(
        target: MethodNode,
        instructions: InsnList,
        originalInsn: InvokeDynamicInsnNode,
    ) {
        val targetParamCount = validateInvokeDynamicHandlerSignature(target, originalInsn)

        val il = InsnList()
        if (isHandlerStatic()) {
            loadTargetMethodParameters(il, target, targetParamCount)
            addStaticHandlerCall(il)
        } else {
            addObjectInvokeDynamicHandlerCall(target, originalInsn, il, targetParamCount)
        }

        val originalReturnType = Type.getReturnType(originalInsn.desc)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (originalReturnType == Type.VOID_TYPE && handlerReturnType != Type.VOID_TYPE) {
            il.add(InsnNode(if (handlerReturnType.size == 2) Opcodes.POP2 else Opcodes.POP))
        } else if (originalReturnType != handlerReturnType && originalReturnType.sort >= Type.ARRAY) {
            il.add(TypeInsnNode(Opcodes.CHECKCAST, originalReturnType.internalName))
        }

        instructions.insertBefore(originalInsn, il)
        instructions.remove(originalInsn)
    }

    private fun replaceConstructorCall(
        target: MethodNode,
        instructions: InsnList,
        originalInsn: MethodInsnNode,
    ) {
        val allocation = findConstructorAllocation(originalInsn)
        val targetParamCount = validateConstructorHandlerSignature(target, originalInsn)

        val il = InsnList()
        if (isHandlerStatic()) {
            loadTargetMethodParameters(il, target, targetParamCount)
            addStaticHandlerCall(il)
        } else {
            addObjectConstructorHandlerCall(target, originalInsn, il, targetParamCount)
        }

        val constructedType = Type.getObjectType(originalInsn.owner)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (constructedType != handlerReturnType && constructedType.sort >= Type.ARRAY) {
            il.add(TypeInsnNode(Opcodes.CHECKCAST, constructedType.internalName))
        }

        instructions.insertBefore(originalInsn, il)
        instructions.remove(allocation.newInsn)
        instructions.remove(allocation.dupInsn)
        instructions.remove(originalInsn)
    }

    private fun replaceNewConstructorCall(
        target: MethodNode,
        instructions: InsnList,
        newInsn: TypeInsnNode,
        originalInsn: MethodInsnNode,
    ) {
        val dupInsn = nextRealInstruction(newInsn)
        if (dupInsn?.opcode != Opcodes.DUP) {
            throw IllegalArgumentException("Redirect NEW requires NEW followed by DUP for ${newInsn.desc}")
        }
        val targetParamCount = validateConstructorHandlerSignature(target, originalInsn)

        val il = InsnList()
        if (isHandlerStatic()) {
            loadTargetMethodParameters(il, target, targetParamCount)
            addStaticHandlerCall(il)
        } else {
            addObjectConstructorHandlerCall(target, originalInsn, il, targetParamCount)
        }

        val constructedType = Type.getObjectType(originalInsn.owner)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (constructedType != handlerReturnType && constructedType.sort >= Type.ARRAY) {
            il.add(TypeInsnNode(Opcodes.CHECKCAST, constructedType.internalName))
        }

        instructions.insertBefore(originalInsn, il)
        instructions.remove(newInsn)
        instructions.remove(dupInsn)
        instructions.remove(originalInsn)
    }

    private fun addStaticHandlerCall(il: InsnList) {
        val instanceType = Type.getType(asmInfo.asmClass)
        il.add(
            MethodInsnNode(
                Opcodes.INVOKESTATIC,
                instanceType.internalName,
                asmMethod.name,
                Type.getMethodDescriptor(asmMethod),
                false,
            ),
        )
    }

    private fun addObjectHandlerCall(
        target: MethodNode,
        originalInsn: MethodInsnNode,
        il: InsnList,
        targetParamCount: Int,
    ) {
        val callParamTypes = Type.getArgumentTypes(originalInsn.desc)
        var nextTempIndex = nextLocalIndex(target)
        val receiverIndex =
            if (originalInsn.opcode == Opcodes.INVOKESTATIC) {
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

        val instanceType = Type.getType(asmInfo.asmClass)
        il.add(
            MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                instanceType.internalName,
                asmMethod.name,
                Type.getMethodDescriptor(asmMethod),
                false,
            ),
        )
    }

    private fun addObjectInvokeDynamicHandlerCall(
        target: MethodNode,
        originalInsn: InvokeDynamicInsnNode,
        il: InsnList,
        targetParamCount: Int,
    ) {
        val callParamTypes = Type.getArgumentTypes(originalInsn.desc)
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

        val instanceType = Type.getType(asmInfo.asmClass)
        il.add(
            MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                instanceType.internalName,
                asmMethod.name,
                Type.getMethodDescriptor(asmMethod),
                false,
            ),
        )
    }

    private fun addObjectConstructorHandlerCall(
        target: MethodNode,
        originalInsn: MethodInsnNode,
        il: InsnList,
        targetParamCount: Int,
    ) {
        val constructorParamTypes = Type.getArgumentTypes(originalInsn.desc)
        var nextTempIndex = nextLocalIndex(target)
        val argSlots =
            constructorParamTypes.map { paramType ->
                nextTempIndex.also { nextTempIndex += paramType.size }
            }

        for (index in constructorParamTypes.indices.reversed()) {
            storeStackValue(il, constructorParamTypes[index], argSlots[index])
        }

        addHandlerOwner(il)
        for (index in constructorParamTypes.indices) {
            loadFromVariable(il, constructorParamTypes[index], argSlots[index])
        }
        loadTargetMethodParameters(il, target, targetParamCount)

        val instanceType = Type.getType(asmInfo.asmClass)
        il.add(
            MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                instanceType.internalName,
                asmMethod.name,
                Type.getMethodDescriptor(asmMethod),
                false,
            ),
        )
    }

    private fun replaceFieldRead(
        target: MethodNode,
        instructions: InsnList,
        originalInsn: FieldInsnNode,
    ) {
        val targetParamCount = validateFieldHandlerSignature(target, originalInsn)

        val il = InsnList()
        if (isHandlerStatic()) {
            loadTargetMethodParameters(il, target, targetParamCount)
            addStaticHandlerCall(il)
        } else {
            addObjectFieldReadHandlerCall(target, originalInsn, il, targetParamCount)
        }

        val fieldType = Type.getType(originalInsn.desc)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (fieldType != handlerReturnType && fieldType.sort >= Type.ARRAY) {
            il.add(TypeInsnNode(Opcodes.CHECKCAST, fieldType.internalName))
        }

        instructions.insertBefore(originalInsn, il)
        instructions.remove(originalInsn)
    }

    private fun replaceFieldAssign(
        target: MethodNode,
        instructions: InsnList,
        originalInsn: FieldInsnNode,
    ) {
        val targetParamCount = validateFieldAssignHandlerSignature(target, originalInsn)

        val il = InsnList()
        if (isHandlerStatic()) {
            loadTargetMethodParameters(il, target, targetParamCount)
            addStaticHandlerCall(il)
        } else {
            addObjectFieldAssignHandlerCall(target, originalInsn, il, targetParamCount)
        }

        instructions.insertBefore(originalInsn, il)
        instructions.remove(originalInsn)
    }

    private fun replaceArrayAccess(
        target: MethodNode,
        instructions: InsnList,
        arrayInsn: AbstractInsnNode,
        fieldInsn: FieldInsnNode,
        mode: ArrayAccessMode,
    ) {
        val targetParamCount = validateArrayAccessHandlerSignature(target, fieldInsn, mode)

        val il = InsnList()
        if (isHandlerStatic()) {
            loadTargetMethodParameters(il, target, targetParamCount)
            addStaticHandlerCall(il)
        } else {
            addObjectArrayAccessHandlerCall(target, fieldInsn, il, targetParamCount, mode)
        }

        if (mode == ArrayAccessMode.GET) {
            val elementType = Type.getType(fieldInsn.desc).elementType
            val handlerReturnType = Type.getReturnType(asmMethod)
            if (elementType != handlerReturnType && elementType.sort >= Type.ARRAY) {
                il.add(TypeInsnNode(Opcodes.CHECKCAST, elementType.internalName))
            }
        }

        instructions.insertBefore(arrayInsn, il)
        instructions.remove(arrayInsn)
    }

    private fun replaceCast(
        target: MethodNode,
        instructions: InsnList,
        originalInsn: TypeInsnNode,
    ) {
        val castType = Type.getObjectType(originalInsn.desc)
        val targetParamCount = validateCastHandlerSignature(target, castType)

        val il = InsnList()
        if (isHandlerStatic()) {
            loadTargetMethodParameters(il, target, targetParamCount)
            addStaticHandlerCall(il)
        } else {
            addObjectInstanceofHandlerCall(target, il, targetParamCount)
        }

        val handlerReturnType = Type.getReturnType(asmMethod)
        if (castType != handlerReturnType) {
            il.add(TypeInsnNode(Opcodes.CHECKCAST, castType.internalName))
        }

        instructions.insertBefore(originalInsn, il)
        instructions.remove(originalInsn)
    }

    private fun replaceInstanceof(
        target: MethodNode,
        instructions: InsnList,
        originalInsn: TypeInsnNode,
    ) {
        val targetParamCount = validateInstanceofHandlerSignature(target)

        val il = InsnList()
        if (isHandlerStatic()) {
            loadTargetMethodParameters(il, target, targetParamCount)
            addStaticHandlerCall(il)
        } else {
            addObjectInstanceofHandlerCall(target, il, targetParamCount)
        }

        instructions.insertBefore(originalInsn, il)
        instructions.remove(originalInsn)
    }

    private fun addObjectFieldReadHandlerCall(
        target: MethodNode,
        originalInsn: FieldInsnNode,
        il: InsnList,
        targetParamCount: Int,
    ) {
        val receiverIndex =
            if (originalInsn.opcode == Opcodes.GETSTATIC) {
                null
            } else {
                nextLocalIndex(target)
            }

        if (receiverIndex != null) {
            il.add(VarInsnNode(Opcodes.ASTORE, receiverIndex))
        }

        addHandlerOwner(il)
        if (receiverIndex != null) {
            il.add(VarInsnNode(Opcodes.ALOAD, receiverIndex))
        }
        loadTargetMethodParameters(il, target, targetParamCount)

        val instanceType = Type.getType(asmInfo.asmClass)
        il.add(
            MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                instanceType.internalName,
                asmMethod.name,
                Type.getMethodDescriptor(asmMethod),
                false,
            ),
        )
    }

    private fun addObjectFieldAssignHandlerCall(
        target: MethodNode,
        originalInsn: FieldInsnNode,
        il: InsnList,
        targetParamCount: Int,
    ) {
        val fieldType = Type.getType(originalInsn.desc)
        var nextTempIndex = nextLocalIndex(target)
        val receiverIndex =
            if (originalInsn.opcode == Opcodes.PUTSTATIC) {
                null
            } else {
                nextTempIndex.also { nextTempIndex += 1 }
            }
        val valueIndex = nextTempIndex

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

        val instanceType = Type.getType(asmInfo.asmClass)
        il.add(
            MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                instanceType.internalName,
                asmMethod.name,
                Type.getMethodDescriptor(asmMethod),
                false,
            ),
        )
    }

    private fun addObjectArrayAccessHandlerCall(
        target: MethodNode,
        fieldInsn: FieldInsnNode,
        il: InsnList,
        targetParamCount: Int,
        mode: ArrayAccessMode,
    ) {
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

        if (valueIndex != null) {
            storeStackValue(il, elementType, valueIndex)
        }
        if (indexIndex != null) {
            storeStackValue(il, Type.INT_TYPE, indexIndex)
        }
        storeStackValue(il, arrayType, arrayIndex)

        addHandlerOwner(il)
        loadFromVariable(il, arrayType, arrayIndex)
        if (indexIndex != null) {
            loadFromVariable(il, Type.INT_TYPE, indexIndex)
        }
        if (valueIndex != null) {
            loadFromVariable(il, elementType, valueIndex)
        }
        loadTargetMethodParameters(il, target, targetParamCount)

        val instanceType = Type.getType(asmInfo.asmClass)
        il.add(
            MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                instanceType.internalName,
                asmMethod.name,
                Type.getMethodDescriptor(asmMethod),
                false,
            ),
        )
    }

    private fun addObjectInstanceofHandlerCall(
        target: MethodNode,
        il: InsnList,
        targetParamCount: Int,
    ) {
        val valueIndex = nextLocalIndex(target)

        il.add(VarInsnNode(Opcodes.ASTORE, valueIndex))
        addHandlerOwner(il)
        il.add(VarInsnNode(Opcodes.ALOAD, valueIndex))
        loadTargetMethodParameters(il, target, targetParamCount)

        val instanceType = Type.getType(asmInfo.asmClass)
        il.add(
            MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                instanceType.internalName,
                asmMethod.name,
                Type.getMethodDescriptor(asmMethod),
                false,
            ),
        )
    }

    private fun validateHandlerSignature(
        target: MethodNode,
        originalInsn: MethodInsnNode,
    ): Int {
        if (!isHandlerStatic() && !isKotlinObject()) {
            throw IllegalStateException(
                "Redirect handler ${asmMethod.name} must be static, @JvmStatic, or a Kotlin object instance method",
            )
        }

        val expectedParams = buildExpectedHandlerParams(originalInsn)
        val actualParams = Type.getArgumentTypes(asmMethod)
        if (actualParams.size < expectedParams.size) {
            throw IllegalStateException(
                "Redirect handler ${asmMethod.name} parameter count mismatch: expected at least ${expectedParams.toList()}, actual ${actualParams.toList()}",
            )
        }

        expectedParams.forEachIndexed { index, expected ->
            val actual = actualParams[index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalStateException(
                    "Redirect handler ${asmMethod.name} parameter #$index mismatch: expected stack type $expected, actual $actual",
                )
            }
        }

        val requestedTargetParamCount = validateTargetMethodParameters(target, actualParams, expectedParams.size)

        val originalReturnType = Type.getReturnType(originalInsn.desc)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (!isReturnCompatible(originalReturnType, handlerReturnType)) {
            throw IllegalStateException(
                "Redirect handler ${asmMethod.name} return type mismatch: original $originalReturnType, handler $handlerReturnType",
            )
        }
        return requestedTargetParamCount
    }

    private fun validateConstructorHandlerSignature(
        target: MethodNode,
        originalInsn: MethodInsnNode,
    ): Int {
        if (!isHandlerStatic() && !isKotlinObject()) {
            throw IllegalStateException(
                "Redirect handler ${asmMethod.name} must be static, @JvmStatic, or a Kotlin object instance method",
            )
        }

        val expectedParams = Type.getArgumentTypes(originalInsn.desc)
        val actualParams = Type.getArgumentTypes(asmMethod)
        if (actualParams.size < expectedParams.size) {
            throw IllegalStateException(
                "Redirect constructor handler ${asmMethod.name} parameter count mismatch: " +
                    "expected at least ${expectedParams.toList()}, actual ${actualParams.toList()}",
            )
        }

        expectedParams.forEachIndexed { index, expected ->
            val actual = actualParams[index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalStateException(
                    "Redirect constructor handler ${asmMethod.name} parameter #$index mismatch: " +
                        "expected stack type $expected, actual $actual",
                )
            }
        }
        val targetParamCount = validateTargetMethodParameters(target, actualParams, expectedParams.size)

        val constructedType = Type.getObjectType(originalInsn.owner)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (!isConstructorReturnCompatible(constructedType, handlerReturnType)) {
            throw IllegalStateException(
                "Redirect constructor handler ${asmMethod.name} return type mismatch: " +
                    "original $constructedType, handler $handlerReturnType",
            )
        }
        return targetParamCount
    }

    private fun validateInvokeDynamicHandlerSignature(
        target: MethodNode,
        originalInsn: InvokeDynamicInsnNode,
    ): Int {
        if (!isHandlerStatic() && !isKotlinObject()) {
            throw IllegalStateException(
                "Redirect handler ${asmMethod.name} must be static, @JvmStatic, or a Kotlin object instance method",
            )
        }

        val expectedParams = Type.getArgumentTypes(originalInsn.desc)
        val actualParams = Type.getArgumentTypes(asmMethod)
        if (actualParams.size < expectedParams.size) {
            throw IllegalStateException(
                "Redirect invokedynamic handler ${asmMethod.name} parameter count mismatch: " +
                    "expected at least ${expectedParams.toList()}, actual ${actualParams.toList()}",
            )
        }

        expectedParams.forEachIndexed { index, expected ->
            val actual = actualParams[index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalStateException(
                    "Redirect invokedynamic handler ${asmMethod.name} parameter #$index mismatch: " +
                        "expected stack type $expected, actual $actual",
                )
            }
        }

        val targetParamCount = validateTargetMethodParameters(target, actualParams, expectedParams.size)

        val originalReturnType = Type.getReturnType(originalInsn.desc)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (!isReturnCompatible(originalReturnType, handlerReturnType)) {
            throw IllegalStateException(
                "Redirect invokedynamic handler ${asmMethod.name} return type mismatch: " +
                    "original $originalReturnType, handler $handlerReturnType",
            )
        }
        return targetParamCount
    }

    private fun validateTargetMethodParameters(
        target: MethodNode,
        actualParams: Array<Type>,
        stackParamCount: Int,
    ): Int {
        val targetParamTypes = Type.getArgumentTypes(target.desc)
        val requestedTargetParamCount = actualParams.size - stackParamCount
        if (requestedTargetParamCount > targetParamTypes.size) {
            throw IllegalStateException(
                "Redirect handler ${asmMethod.name} requests $requestedTargetParamCount target parameter(s), " +
                    "but target method ${target.name}${target.desc} has only ${targetParamTypes.size}",
            )
        }

        for (index in 0 until requestedTargetParamCount) {
            val expected = targetParamTypes[index]
            val actual = actualParams[stackParamCount + index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalStateException(
                    "Redirect handler ${asmMethod.name} target parameter #$index mismatch: expected $expected, actual $actual",
                )
            }
        }
        return requestedTargetParamCount
    }

    private fun validateFieldHandlerSignature(
        target: MethodNode,
        originalInsn: FieldInsnNode,
    ): Int {
        if (!isHandlerStatic() && !isKotlinObject()) {
            throw IllegalStateException(
                "Redirect handler ${asmMethod.name} must be static, @JvmStatic, or a Kotlin object instance method",
            )
        }

        val expectedParams = buildExpectedFieldHandlerParams(originalInsn)
        val actualParams = Type.getArgumentTypes(asmMethod)
        if (actualParams.size < expectedParams.size) {
            throw IllegalStateException(
                "Redirect handler ${asmMethod.name} parameter count mismatch: expected at least ${expectedParams.toList()}, actual ${actualParams.toList()}",
            )
        }

        expectedParams.forEachIndexed { index, expected ->
            val actual = actualParams[index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalStateException(
                    "Redirect handler ${asmMethod.name} parameter #$index mismatch: expected stack type $expected, actual $actual",
                )
            }
        }
        val targetParamCount = validateTargetMethodParameters(target, actualParams, expectedParams.size)

        val fieldType = Type.getType(originalInsn.desc)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (!isReturnCompatible(fieldType, handlerReturnType)) {
            throw IllegalStateException(
                "Redirect handler ${asmMethod.name} return type mismatch: original $fieldType, handler $handlerReturnType",
            )
        }
        return targetParamCount
    }

    private fun validateFieldAssignHandlerSignature(
        target: MethodNode,
        originalInsn: FieldInsnNode,
    ): Int {
        if (!isHandlerStatic() && !isKotlinObject()) {
            throw IllegalStateException(
                "Redirect handler ${asmMethod.name} must be static, @JvmStatic, or a Kotlin object instance method",
            )
        }

        val expectedParams = buildExpectedFieldAssignHandlerParams(originalInsn)
        val actualParams = Type.getArgumentTypes(asmMethod)
        if (actualParams.size < expectedParams.size) {
            throw IllegalStateException(
                "Redirect handler ${asmMethod.name} parameter count mismatch: expected at least ${expectedParams.toList()}, actual ${actualParams.toList()}",
            )
        }

        expectedParams.forEachIndexed { index, expected ->
            val actual = actualParams[index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalStateException(
                    "Redirect handler ${asmMethod.name} parameter #$index mismatch: expected stack type $expected, actual $actual",
                )
            }
        }
        val targetParamCount = validateTargetMethodParameters(target, actualParams, expectedParams.size)

        val handlerReturnType = Type.getReturnType(asmMethod)
        if (handlerReturnType != Type.VOID_TYPE) {
            throw IllegalStateException(
                "Redirect field assign handler ${asmMethod.name} must return void, actual $handlerReturnType",
            )
        }
        return targetParamCount
    }

    private fun validateCastHandlerSignature(
        target: MethodNode,
        castType: Type,
    ): Int {
        if (!isHandlerStatic() && !isKotlinObject()) {
            throw IllegalStateException(
                "Redirect handler ${asmMethod.name} must be static, @JvmStatic, or a Kotlin object instance method",
            )
        }

        val actualParams = Type.getArgumentTypes(asmMethod)
        val expectedParams = arrayOf(Type.getType(Any::class.java))
        if (actualParams.isEmpty()) {
            throw IllegalStateException(
                "Redirect CAST handler ${asmMethod.name} parameter count mismatch: " +
                    "expected at least ${expectedParams.toList()}, actual ${actualParams.toList()}",
            )
        }
        if (!isHandlerParameterCompatible(expectedParams[0], actualParams[0])) {
            throw IllegalStateException(
                "Redirect CAST handler ${asmMethod.name} parameter #0 mismatch: " +
                    "expected stack type ${expectedParams[0]}, actual ${actualParams[0]}",
            )
        }

        val targetParamCount = validateTargetMethodParameters(target, actualParams, expectedParams.size)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (!isReturnCompatible(castType, handlerReturnType)) {
            throw IllegalStateException(
                "Redirect CAST handler ${asmMethod.name} return type mismatch: original $castType, handler $handlerReturnType",
            )
        }
        return targetParamCount
    }

    private fun validateInstanceofHandlerSignature(target: MethodNode): Int {
        if (!isHandlerStatic() && !isKotlinObject()) {
            throw IllegalStateException(
                "Redirect handler ${asmMethod.name} must be static, @JvmStatic, or a Kotlin object instance method",
            )
        }

        val actualParams = Type.getArgumentTypes(asmMethod)
        val expectedParams = arrayOf(Type.getType(Any::class.java))
        if (actualParams.isEmpty()) {
            throw IllegalStateException(
                "Redirect INSTANCEOF handler ${asmMethod.name} parameter count mismatch: " +
                    "expected at least ${expectedParams.toList()}, actual ${actualParams.toList()}",
            )
        }
        if (!isHandlerParameterCompatible(expectedParams[0], actualParams[0])) {
            throw IllegalStateException(
                "Redirect INSTANCEOF handler ${asmMethod.name} parameter #0 mismatch: " +
                    "expected stack type ${expectedParams[0]}, actual ${actualParams[0]}",
            )
        }

        val targetParamCount = validateTargetMethodParameters(target, actualParams, expectedParams.size)
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (handlerReturnType != Type.BOOLEAN_TYPE) {
            throw IllegalStateException(
                "Redirect INSTANCEOF handler ${asmMethod.name} must return boolean, actual $handlerReturnType",
            )
        }
        return targetParamCount
    }

    private fun validateArrayAccessHandlerSignature(
        target: MethodNode,
        fieldInsn: FieldInsnNode,
        mode: ArrayAccessMode,
    ): Int {
        if (!isHandlerStatic() && !isKotlinObject()) {
            throw IllegalStateException(
                "Redirect handler ${asmMethod.name} must be static, @JvmStatic, or a Kotlin object instance method",
            )
        }

        val expectedParams = buildExpectedArrayAccessHandlerParams(fieldInsn, mode)
        val actualParams = Type.getArgumentTypes(asmMethod)
        if (actualParams.size < expectedParams.size) {
            throw IllegalStateException(
                "Redirect handler ${asmMethod.name} parameter count mismatch: expected at least ${expectedParams.toList()}, actual ${actualParams.toList()}",
            )
        }

        expectedParams.forEachIndexed { index, expected ->
            val actual = actualParams[index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalStateException(
                    "Redirect handler ${asmMethod.name} parameter #$index mismatch: expected stack type $expected, actual $actual",
                )
            }
        }
        val targetParamCount = validateTargetMethodParameters(target, actualParams, expectedParams.size)

        val handlerReturnType = Type.getReturnType(asmMethod)
        if (mode == ArrayAccessMode.GET) {
            val elementType = Type.getType(fieldInsn.desc).elementType
            if (!isReturnCompatible(elementType, handlerReturnType)) {
                throw IllegalStateException(
                    "Redirect array read handler ${asmMethod.name} return type mismatch: original $elementType, handler $handlerReturnType",
                )
            }
        } else if (mode == ArrayAccessMode.LENGTH) {
            if (handlerReturnType != Type.INT_TYPE) {
                throw IllegalStateException(
                    "Redirect array length handler ${asmMethod.name} must return int, actual $handlerReturnType",
                )
            }
        } else if (handlerReturnType != Type.VOID_TYPE) {
            throw IllegalStateException(
                "Redirect array write handler ${asmMethod.name} must return void, actual $handlerReturnType",
            )
        }
        return targetParamCount
    }

    private fun buildExpectedHandlerParams(originalInsn: MethodInsnNode): Array<Type> {
        val originalParams = Type.getArgumentTypes(originalInsn.desc).toList()
        return if (originalInsn.opcode == Opcodes.INVOKESTATIC) {
            originalParams.toTypedArray()
        } else {
            (listOf(Type.getObjectType(originalInsn.owner)) + originalParams).toTypedArray()
        }
    }

    private fun buildExpectedFieldHandlerParams(originalInsn: FieldInsnNode): Array<Type> =
        if (originalInsn.opcode == Opcodes.GETSTATIC) {
            emptyArray()
        } else {
            arrayOf(Type.getObjectType(originalInsn.owner))
        }

    private fun buildExpectedFieldAssignHandlerParams(originalInsn: FieldInsnNode): Array<Type> {
        val fieldType = Type.getType(originalInsn.desc)
        return if (originalInsn.opcode == Opcodes.PUTSTATIC) {
            arrayOf(fieldType)
        } else {
            arrayOf(Type.getObjectType(originalInsn.owner), fieldType)
        }
    }

    private fun buildExpectedArrayAccessHandlerParams(
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
                            "Redirect array access target must be an array field: ${cursor.owner}.${cursor.name}:${cursor.desc}",
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
                        "Redirect constructor calls require NEW followed by DUP for ${constructorInsn.owner}",
                    )
                }
                return ConstructorAllocation(cursor, dupInsn)
            }
            cursor = cursor.previous
        }

        throw IllegalArgumentException(
            "Redirect cannot find NEW allocation for constructor ${constructorInsn.owner}${constructorInsn.desc}",
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

        throw IllegalArgumentException("Redirect cannot find constructor call for NEW ${newInsn.desc}")
    }

    private fun nextRealInstruction(insn: AbstractInsnNode): AbstractInsnNode? {
        var cursor = insn.next
        while (cursor != null && cursor.opcode < 0) {
            cursor = cursor.next
        }
        return cursor
    }

    private fun isHandlerParameterCompatible(
        expected: Type,
        actual: Type,
    ): Boolean {
        if (expected == actual) return true
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

    private fun isReturnCompatible(
        original: Type,
        handler: Type,
    ): Boolean {
        if (original == Type.VOID_TYPE) return handler == Type.VOID_TYPE
        if (handler == Type.VOID_TYPE) return false
        if (original == handler) return true
        if (!original.isReferenceType() || !handler.isReferenceType()) {
            return false
        }
        if (handler.sort == Type.OBJECT &&
            (handler.internalName == "java/lang/Object" || handler.internalName == "kotlin/Any")
        ) {
            return true
        }
        return runCatching {
            val originalClass = loadReferenceClass(original)
            originalClass.isAssignableFrom(loadReferenceClass(handler))
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

    private fun isConstructorReturnCompatible(
        constructedType: Type,
        handler: Type,
    ): Boolean {
        return isReturnCompatible(constructedType, handler)
    }

    private fun addHandlerOwner(il: InsnList) {
        if (isHandlerStatic()) {
            return
        }

        val instanceType = Type.getType(asmInfo.asmClass)
        il.add(
            FieldInsnNode(
                Opcodes.GETSTATIC,
                instanceType.internalName,
                "INSTANCE",
                "L${instanceType.internalName};",
            ),
        )
    }

    private fun isHandlerStatic(): Boolean = Modifier.isStatic(asmMethod.modifiers)

    private fun loadFromVariable(
        il: InsnList,
        paramType: Type,
        varIndex: Int,
    ) {
        InstructionUtil.loadParam(paramType, varIndex).let { il.add(it) }
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
