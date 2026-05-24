/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.injector.impl

import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.Shift
import kim.der.asm.data.AsmInfo
import kim.der.asm.injector.AbstractAsmInjector
import kim.der.asm.injector.util.AsmMethodCallGenerator
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import java.lang.reflect.Method

/**
 * 指令点注入器。
 *
 * 用于处理字段访问、字段赋值、对象创建、类型转换与抛异常等单条字节码指令附近的普通 `@AsmInject`。
 * 当前实现只负责在匹配指令前后插入 ASM 方法调用，不替换原始指令，也不向 handler 传递栈顶操作数。
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
    override fun inject(target: MethodNode): Boolean {
        val injectAnnotation = asmMethod.getAnnotation(AsmInject::class.java) ?: return false
        requireSupportedShift(injectAnnotation.at.shift)
        val matcher = buildMatcher(injectAnnotation.at.target)
        val instructions = target.instructions
        var transformed = false
        var matchedOrdinal = 0

        for (insn in instructions.toArray()) {
            if (!matcher(insn)) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal, injectAnnotation.ordinal)) {
                continue
            }

            val il = buildHandlerCall(target)
            when (injectAnnotation.at.shift) {
                Shift.BEFORE, Shift.REPLACE -> instructions.insertBefore(insn, il)
                Shift.AFTER -> instructions.insert(insn, il)
            }
            transformed = true
        }

        return transformed
    }

    private fun matchesOrdinal(
        currentOrdinal: Int,
        requestedOrdinal: Int,
    ): Boolean = requestedOrdinal < 0 || currentOrdinal == requestedOrdinal

    private fun requireSupportedShift(shift: Shift) {
        if (point == InjectionPoint.NEW && shift == Shift.AFTER) {
            throw IllegalStateException(
                "InjectionPoint.NEW does not support Shift.AFTER because inserting after NEW leaves " +
                    "an uninitialized object on the stack; use Shift.BEFORE or Shift.REPLACE",
            )
        }
    }

    private fun buildMatcher(target: String): (AbstractInsnNode) -> Boolean {
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
            InjectionPoint.THROW -> {
                fun(insn: AbstractInsnNode): Boolean = insn.opcode == Opcodes.ATHROW
            }
            else -> {
                fun(_: AbstractInsnNode): Boolean = false
            }
        }
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
    }
}
