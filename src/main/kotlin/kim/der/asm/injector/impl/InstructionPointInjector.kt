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

    private fun matchesOrdinal(
        currentOrdinal: Int,
        requestedOrdinal: Int,
    ): Boolean = requestedOrdinal < 0 || currentOrdinal == requestedOrdinal

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

    private fun matchesLocalVariableIndex(
        insn: VarInsnNode,
        requestedIndex: Int?,
    ): Boolean = requestedIndex == null || insn.`var` == requestedIndex

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

    private fun previousRealInstruction(insn: AbstractInsnNode): AbstractInsnNode? {
        var current = insn.previous
        while (current != null && current.opcode < 0) {
            current = current.previous
        }
        return current
    }

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

    private fun resolveConstantReplacementType(
        constantInsn: AbstractInsnNode,
        constantTarget: String,
    ): Type? {
        if (isBooleanLiteral(constantTarget) && isBooleanConstantInsn(constantInsn, constantTarget == "true")) {
            return Type.BOOLEAN_TYPE
        }
        return BytecodeUtil.getConstantType(constantInsn)
    }

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

    private fun Type.isReferenceType(): Boolean = sort == Type.OBJECT || sort == Type.ARRAY

    private fun Type.isGenericObjectType(): Boolean =
        sort == Type.OBJECT && (internalName == "java/lang/Object" || internalName == "kotlin/Any")

    private fun isBooleanLiteral(value: String): Boolean = value == "true" || value == "false"

    private fun isBooleanConstantInsn(
        insn: AbstractInsnNode,
        value: Boolean,
    ): Boolean =
        when (insn.opcode) {
            Opcodes.ICONST_0 -> !value
            Opcodes.ICONST_1 -> value
            else -> false
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

    private data class FieldTarget(
        val owner: String?,
        val name: String?,
        val desc: String?,
    )

    private companion object {
        private val FIELD_READ_OPS = setOf(Opcodes.GETFIELD, Opcodes.GETSTATIC)
        private val FIELD_WRITE_OPS = setOf(Opcodes.PUTFIELD, Opcodes.PUTSTATIC)
        private val LOAD_OPS = setOf(Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD, Opcodes.ALOAD)
        private val STORE_OPS = setOf(Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE, Opcodes.ASTORE)
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
    }
}
