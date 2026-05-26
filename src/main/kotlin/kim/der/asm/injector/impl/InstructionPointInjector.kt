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
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import java.lang.reflect.Method

/**
 * 指令点注入器。
 *
 * 用于处理字段访问、字段赋值、局部变量读写、对象创建、类型转换与抛异常等单条字节码指令附近的普通 `@AsmInject`。
 * 当前实现只负责在匹配指令前后插入 ASM 方法调用，不替换原始指令，也不向 handler 传递栈顶操作数。
 * 普通 [InjectionPoint.FIELD] / [InjectionPoint.FIELD_ASSIGN] / [InjectionPoint.LOAD] / [InjectionPoint.STORE] /
 * [InjectionPoint.CAST] / [InjectionPoint.THROW] 可使用 `Slice` 的 [InjectionPoint.INVOKE] 边界缩小候选指令
 * 查找范围，也可通过 `At.by` 按真实字节码指令数移动插入锚点；LOAD/STORE 还可通过 `At.args` 中的
 * `index=N` 或 `var=N` 限制 JVM 局部变量槽位。对象创建指令点当前不使用 `slice` 或 `At.by`。
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

            val il = buildHandlerCall(target)
            val anchor = resolveByAnchor(insns, index, injectAnnotation.at.by)
            when (injectAnnotation.at.shift) {
                Shift.BEFORE, Shift.REPLACE -> instructions.insertBefore(anchor, il)
                Shift.AFTER -> instructions.insert(anchor, il)
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
            point == InjectionPoint.CAST ||
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
                fun(insn: AbstractInsnNode): Boolean = insn.opcode == Opcodes.ATHROW
            }
            else -> {
                fun(_: AbstractInsnNode): Boolean = false
            }
        }
    }

    private fun matchesLocalVariableIndex(
        insn: VarInsnNode,
        requestedIndex: Int?,
    ): Boolean = requestedIndex == null || insn.`var` == requestedIndex

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
    }
}
