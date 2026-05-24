/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.injector.impl

import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.data.AsmInfo
import kim.der.asm.injector.AbstractAsmInjector
import kim.der.asm.utils.transformer.InstructionUtil
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * ModifyVariable 注入器。
 *
 * 当前实现支持在方法入口修改已有参数槽位，也支持在局部变量读取前或写入指令后改写槽位值。
 * 优先按 JVM 局部变量槽位索引定位；未显式指定槽位时，可按 handler 参数类型与 [ordinal] 选择同类型参数、读取点或写入点。
 * handler 第一个参数接收原变量值，后续可按顺序接收目标方法参数前缀；调用后会把返回的新值写回同一个槽位。
 *
 * @param injectionPoint 修改位置；当前支持 [InjectionPoint.HEAD]、[InjectionPoint.LOAD] 与 [InjectionPoint.STORE]
 * @param variableIndex 要修改的 JVM 局部变量槽位索引
 * @param ordinal 未指定 [variableIndex] 时，同类型入口参数、读取点或写入点的序号
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
class ModifyVariableInjector(
    method: Method,
    asmInfo: AsmInfo,
    private val injectionPoint: InjectionPoint,
    private val variableIndex: Int,
    private val ordinal: Int,
) : AbstractAsmInjector(method, asmInfo) {
    /**
     * 在目标方法入口、变量读取点前或变量写入点后修改指定局部变量槽位。
     *
     * @param target 目标方法
     * @return 成功插入变量改写逻辑时返回 `true`
     * @throws IllegalArgumentException 注入点、槽位索引或 handler 签名不合法时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun inject(target: MethodNode): Boolean {
        return when (injectionPoint) {
            InjectionPoint.HEAD -> injectAtHead(target)
            InjectionPoint.LOAD -> injectBeforeLoad(target)
            InjectionPoint.STORE -> injectAfterStore(target)
            else -> throw IllegalArgumentException(
                "@ModifyVariable currently supports only HEAD, LOAD and STORE injection points",
            )
        }
    }

    private fun injectAtHead(target: MethodNode): Boolean {
        val handlerVariableType = requireHandlerVariableArgumentType()
        val variable =
            resolveHeadVariable(target, variableIndex, handlerVariableType, ordinal)
                ?: throw IllegalArgumentException(
                    "@ModifyVariable cannot resolve HEAD variable: index=$variableIndex, ordinal=$ordinal, type=$handlerVariableType",
                )

        val targetParamCount = validateHandlerSignature(target, variable.type)

        val il = buildModificationCall(target, variable.type, variable.index, targetParamCount)

        if (target.instructions.size() == 0) {
            target.instructions.add(il)
        } else {
            target.instructions.insertBefore(target.instructions.first, il)
        }

        return true
    }

    private fun injectBeforeLoad(target: MethodNode): Boolean {
        val handlerVariableType = requireHandlerVariableArgumentType()
        var transformed = false
        var matchedOrdinal = 0

        for (insn in target.instructions.toArray()) {
            if (insn !is VarInsnNode || insn.opcode !in LOAD_OPS) {
                continue
            }
            if (variableIndex >= 0 && insn.`var` != variableIndex) {
                continue
            }
            if (!isLoadCompatibleWithHandler(insn.opcode, handlerVariableType)) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (variableIndex < 0 && ordinal >= 0 && currentOrdinal != ordinal) {
                continue
            }

            val targetParamCount = validateHandlerSignature(target, handlerVariableType)
            val il = buildModificationCall(target, handlerVariableType, insn.`var`, targetParamCount)
            target.instructions.insertBefore(insn, il)
            transformed = true
        }

        return transformed
    }

    private fun injectAfterStore(target: MethodNode): Boolean {
        val handlerVariableType = requireHandlerVariableArgumentType()
        var transformed = false
        var matchedOrdinal = 0

        for (insn in target.instructions.toArray()) {
            if (insn !is VarInsnNode || insn.opcode !in STORE_OPS) {
                continue
            }
            if (variableIndex >= 0 && insn.`var` != variableIndex) {
                continue
            }
            if (!isStoreCompatibleWithHandler(insn.opcode, handlerVariableType)) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (variableIndex < 0 && ordinal >= 0 && currentOrdinal != ordinal) {
                continue
            }

            val targetParamCount = validateHandlerSignature(target, handlerVariableType)
            val il = buildModificationCall(target, handlerVariableType, insn.`var`, targetParamCount)
            target.instructions.insert(insn, il)
            transformed = true
        }

        return transformed
    }

    private fun resolveHeadVariable(
        target: MethodNode,
        index: Int,
        expectedType: Type,
        ordinal: Int,
    ): HeadVariable? {
        val variables = collectHeadParameters(target)
        if (index >= 0) {
            return variables.find { it.index == index }
        }

        if (ordinal < 0) {
            return null
        }

        return variables.filter { it.type == expectedType }.getOrNull(ordinal)
    }

    private fun collectHeadParameters(target: MethodNode): List<HeadVariable> {
        val isStatic = (target.access and Opcodes.ACC_STATIC) != 0
        var slot = if (isStatic) 0 else 1
        return buildList {
            for (argumentType in Type.getArgumentTypes(target.desc)) {
                add(HeadVariable(slot, argumentType))
                slot += argumentType.size
            }
        }
    }

    private fun requireHandlerVariableArgumentType(): Type {
        val handlerParams = Type.getArgumentTypes(asmMethod)
        if (handlerParams.isEmpty()) {
            throw IllegalArgumentException(
                "@ModifyVariable handler ${asmMethod.name} must take at least one argument for the original variable value",
            )
        }
        return handlerParams[0]
    }

    private fun buildModificationCall(
        target: MethodNode,
        variableType: Type,
        variableIndex: Int,
        targetParamCount: Int,
    ): InsnList {
        val il = InsnList()
        addHandlerOwner(il)
        il.add(InstructionUtil.loadParam(variableType, variableIndex))
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
        il.add(storeVariable(variableType, variableIndex))
        return il
    }

    private fun validateHandlerSignature(
        target: MethodNode,
        variableType: Type,
    ): Int {
        val handlerParams = Type.getArgumentTypes(asmMethod)
        if (handlerParams.isEmpty() || handlerParams[0] != variableType) {
            throw IllegalArgumentException(
                "@ModifyVariable handler ${asmMethod.name} first parameter must be $variableType, actual ${handlerParams.toList()}",
            )
        }

        val handlerReturnType = Type.getReturnType(asmMethod)
        if (handlerReturnType != variableType) {
            throw IllegalArgumentException(
                "@ModifyVariable handler ${asmMethod.name} return type $handlerReturnType must match variable type $variableType",
            )
        }

        val targetParamTypes = Type.getArgumentTypes(target.desc)
        val requestedTargetParamCount = handlerParams.size - 1
        if (requestedTargetParamCount > targetParamTypes.size) {
            throw IllegalArgumentException(
                "@ModifyVariable handler ${asmMethod.name} requests $requestedTargetParamCount target parameter(s), " +
                    "but target method ${target.name}${target.desc} has only ${targetParamTypes.size}",
            )
        }

        for (index in 0 until requestedTargetParamCount) {
            val expected = targetParamTypes[index]
            val actual = handlerParams[index + 1]
            if (actual != expected) {
                throw IllegalArgumentException(
                    "@ModifyVariable handler ${asmMethod.name} target parameter #$index mismatch: expected $expected, actual $actual",
                )
            }
        }

        return requestedTargetParamCount
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
            il.add(InstructionUtil.loadParam(paramType, paramVarIndex))
            paramVarIndex += paramType.size
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
        il.add(org.objectweb.asm.tree.InsnNode(Opcodes.DUP))
        il.add(
            MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                ownerType.internalName,
                "<init>",
                "()V",
                false,
            ),
        )
    }

    private fun handlerOpcode(): Int =
        if (isHandlerStatic()) {
            Opcodes.INVOKESTATIC
        } else {
            Opcodes.INVOKEVIRTUAL
        }

    private fun isHandlerStatic(): Boolean = (asmMethod.modifiers and Modifier.STATIC) != 0

    private fun isLoadCompatibleWithHandler(
        opcode: Int,
        handlerType: Type,
    ): Boolean =
        when (opcode) {
            Opcodes.ILOAD -> handlerType.sort in INT_VARIABLE_TYPE_SORTS
            Opcodes.LLOAD -> handlerType == Type.LONG_TYPE
            Opcodes.FLOAD -> handlerType == Type.FLOAT_TYPE
            Opcodes.DLOAD -> handlerType == Type.DOUBLE_TYPE
            Opcodes.ALOAD -> handlerType.sort == Type.OBJECT || handlerType.sort == Type.ARRAY
            else -> false
        }

    private fun isStoreCompatibleWithHandler(
        opcode: Int,
        handlerType: Type,
    ): Boolean =
        when (opcode) {
            Opcodes.ISTORE -> handlerType.sort in INT_VARIABLE_TYPE_SORTS
            Opcodes.LSTORE -> handlerType == Type.LONG_TYPE
            Opcodes.FSTORE -> handlerType == Type.FLOAT_TYPE
            Opcodes.DSTORE -> handlerType == Type.DOUBLE_TYPE
            Opcodes.ASTORE -> handlerType.sort == Type.OBJECT || handlerType.sort == Type.ARRAY
            else -> false
        }

    private fun storeVariable(
        type: Type,
        index: Int,
    ): VarInsnNode =
        when (type.sort) {
            Type.BOOLEAN, Type.BYTE, Type.SHORT, Type.INT, Type.CHAR -> VarInsnNode(Opcodes.ISTORE, index)
            Type.LONG -> VarInsnNode(Opcodes.LSTORE, index)
            Type.FLOAT -> VarInsnNode(Opcodes.FSTORE, index)
            Type.DOUBLE -> VarInsnNode(Opcodes.DSTORE, index)
            Type.VOID -> throw IllegalArgumentException("Cannot store VOID variable")
            else -> VarInsnNode(Opcodes.ASTORE, index)
        }

    private data class HeadVariable(
        val index: Int,
        val type: Type,
    )

    private companion object {
        private val LOAD_OPS = setOf(Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD, Opcodes.ALOAD)
        private val STORE_OPS = setOf(Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE, Opcodes.ASTORE)
        private val INT_VARIABLE_TYPE_SORTS = setOf(Type.BOOLEAN, Type.BYTE, Type.SHORT, Type.INT, Type.CHAR)
    }
}
