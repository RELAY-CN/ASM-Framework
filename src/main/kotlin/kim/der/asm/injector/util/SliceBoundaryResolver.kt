/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.injector.util

import kim.der.asm.api.annotation.At
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.Shift
import kim.der.asm.api.annotation.Slice
import kim.der.asm.utils.transformer.BytecodeUtil
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.MethodInsnNode

/**
 * `Slice` 边界解析器。
 *
 * 统一维护 `from` 命中后从下一条指令开始、`to` 命中前结束、任一声明边界未命中时返回空范围的契约。
 * 默认 [At] 表示未声明边界；一旦显式声明边界，目标必须非空，字段边界还必须包含字段名。
 * 这些限制能避免空目标或 descriptor-only 字段目标把切片扩大到错误锚点。
 * 该工具只服务内部 injector，不作为公开 API 暴露；不同注解仍可传入各自支持的边界类型集合。
 *
 * @author Dr (dr@der.kim)
 * @date 2026-06-05
 */
internal object SliceBoundaryResolver {
    /**
     * 通用注解支持的切片边界类型。
     *
     * 字段与常量哨兵常用于把旧重定向逻辑迁移到注解式 API 时收窄业务片段，所有使用该集合的注解
     * 都共享同一套边界解析和失败语义。
     */
    val GENERAL_BOUNDARIES: Set<InjectionPoint> =
        setOf(
            InjectionPoint.INVOKE,
            InjectionPoint.FIELD,
            InjectionPoint.FIELD_ASSIGN,
            InjectionPoint.CONSTANT,
        )

    /**
     * `@ModifyConstant` 与通用注解保持一致的切片边界类型。
     */
    val MODIFY_CONSTANT_BOUNDARIES: Set<InjectionPoint> = GENERAL_BOUNDARIES

    /**
     * 解析切片在指令数组中的半开范围。
     *
     * @param insns 目标方法指令快照
     * @param slice 注解声明的切片范围
     * @param context 错误消息中的注解上下文，例如 `@ModifyConstant`
     * @param supportedBoundaries 当前注解允许作为边界的注入点类型
     * @return 左闭右开的候选指令范围；边界未命中时返回方法末尾的空范围
     * @throws IllegalArgumentException 边界类型或目标格式不满足当前注解约束时抛出
     */
    fun resolveRange(
        insns: Array<AbstractInsnNode>,
        slice: Slice,
        context: String,
        supportedBoundaries: Set<InjectionPoint>,
    ): Pair<Int, Int> {
        val startIndex =
            if (isBoundaryDeclared(slice.from)) {
                val fromIndex = findBoundaryIndex(insns, slice.from, 0, context, supportedBoundaries)
                    ?: return emptySlice(insns)
                fromIndex + 1
            } else {
                0
            }
        val endIndex =
            if (isBoundaryDeclared(slice.to)) {
                findBoundaryIndex(insns, slice.to, startIndex, context, supportedBoundaries)
                    ?: return emptySlice(insns)
            } else {
                insns.size
            }

        return startIndex to endIndex.coerceAtLeast(startIndex)
    }

    private fun isBoundaryDeclared(at: At): Boolean =
        // 只有完全默认的 At() 表示未声明边界；显式写出的空 INVOKE 必须作为配置错误暴露。
        at.value != InjectionPoint.HEAD ||
            at.target.isNotEmpty() ||
            at.shift != Shift.BEFORE ||
            at.by != 0 ||
            at.args.isNotEmpty()

    private fun emptySlice(insns: Array<AbstractInsnNode>): Pair<Int, Int> = insns.size to insns.size

    private fun findBoundaryIndex(
        insns: Array<AbstractInsnNode>,
        at: At,
        startIndex: Int,
        context: String,
        supportedBoundaries: Set<InjectionPoint>,
    ): Int? {
        require(at.value in supportedBoundaries) {
            "Only ${supportedBoundaries.joinToString()} slice boundaries are supported for $context: ${at.value}"
        }
        validateBoundaryTarget(at, context)

        val matchesBoundary = buildBoundaryMatcher(at, context)
        for (index in startIndex until insns.size) {
            if (matchesBoundary(insns[index])) {
                return index
            }
        }

        return null
    }

    private fun validateBoundaryTarget(
        at: At,
        context: String,
    ) {
        // Slice 边界必须是明确锚点；空 target 的 match-all 语义容易把切片扩大到全方法而误改同值候选。
        require(at.target.isNotEmpty()) {
            "Invalid $context slice boundary ${at.value} target: target must not be empty"
        }
    }

    private fun buildBoundaryMatcher(
        at: At,
        context: String,
    ): (AbstractInsnNode) -> Boolean =
        when (at.value) {
            InjectionPoint.INVOKE -> buildInvokeMatcher(at, context)
            InjectionPoint.FIELD -> buildFieldMatcher(at, FIELD_READ_OPS, context)
            InjectionPoint.FIELD_ASSIGN -> buildFieldMatcher(at, FIELD_WRITE_OPS, context)
            InjectionPoint.CONSTANT -> buildConstantMatcher(at)
            else -> {
                { false }
            }
        }

    private fun buildInvokeMatcher(
        at: At,
        context: String,
    ): (AbstractInsnNode) -> Boolean {
        val target = parseInvokeTarget(at.target)
        if (target.name == null || target.desc == null) {
            throw IllegalArgumentException(
                "Invalid $context slice boundary method signature: ${at.target} " +
                    "(parsed: owner=${target.owner}, name=${target.name}, desc=${target.desc})",
            )
        }

        return { insn ->
            when (insn) {
                is MethodInsnNode -> matchesMethodCall(insn, target.owner, target.name, target.desc)
                is InvokeDynamicInsnNode -> matchesInvokeDynamic(insn, target.owner, target.name, target.desc)
                else -> false
            }
        }
    }

    private fun buildFieldMatcher(
        at: At,
        opcodes: Set<Int>,
        context: String,
    ): (AbstractInsnNode) -> Boolean {
        val target = parseFieldTarget(at, context)
        return { insn ->
            insn is FieldInsnNode &&
                insn.opcode in opcodes &&
                matchesField(insn, target)
        }
    }

    private fun buildConstantMatcher(at: At): (AbstractInsnNode) -> Boolean =
        { insn ->
            BytecodeUtil.isConstant(insn) && BytecodeUtil.matchesConstantText(insn, at.target)
        }

    private fun parseInvokeTarget(signature: String): InvokeTarget {
        if (signature.isEmpty()) {
            return InvokeTarget(null, null, null)
        }

        val parenIndex = signature.indexOf('(')
        if (parenIndex < 0) {
            return InvokeTarget(null, signature, null)
        }

        val ownerAndName = signature.substring(0, parenIndex)
        val desc = signature.substring(parenIndex)
        val separatorIndex = maxOf(ownerAndName.lastIndexOf('/'), ownerAndName.lastIndexOf('.'))

        return if (separatorIndex >= 0) {
            InvokeTarget(
                ownerAndName.substring(0, separatorIndex).replace('.', '/'),
                ownerAndName.substring(separatorIndex + 1),
                desc,
            )
        } else {
            InvokeTarget(null, ownerAndName, desc)
        }
    }

    private fun matchesMethodCall(
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
        return insn.desc == targetDesc
    }

    private fun matchesInvokeDynamic(
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
        return insn.desc == targetDesc
    }

    private fun parseFieldTarget(
        at: At,
        context: String,
    ): FieldTarget {
        val signature = at.target
        val colonIndex = signature.indexOf(':')
        val ownerAndName = if (colonIndex >= 0) signature.substring(0, colonIndex) else signature
        val desc = if (colonIndex >= 0) signature.substring(colonIndex + 1) else null
        val separatorIndex = maxOf(ownerAndName.lastIndexOf('/'), ownerAndName.lastIndexOf('.'))

        val target =
            if (separatorIndex >= 0) {
                FieldTarget(
                    ownerAndName.substring(0, separatorIndex).replace('.', '/'),
                    ownerAndName.substring(separatorIndex + 1),
                    desc,
                )
            } else {
                FieldTarget(null, ownerAndName.ifEmpty { null }, desc)
            }

        require(target.name != null && target.name.isNotEmpty()) {
            "Invalid $context slice boundary ${at.value} target: field name must not be empty"
        }
        return target
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

    private data class InvokeTarget(
        val owner: String?,
        val name: String?,
        val desc: String?,
    )

    private data class FieldTarget(
        val owner: String?,
        val name: String?,
        val desc: String?,
    )

    private val FIELD_READ_OPS = setOf(Opcodes.GETFIELD, Opcodes.GETSTATIC)

    private val FIELD_WRITE_OPS = setOf(Opcodes.PUTFIELD, Opcodes.PUTSTATIC)
}
