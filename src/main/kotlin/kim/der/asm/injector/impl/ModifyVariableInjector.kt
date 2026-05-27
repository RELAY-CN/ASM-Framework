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
 * handler 第一个参数接收原变量值；显式指定槽位时，对象或数组变量可声明为 `Any` / `Object` 接收，
 * 返回类型对基础类型仍需精确匹配，对象或数组类型可返回可赋值给原变量类型的子类型。后续可按顺序接收目标方法参数前缀；
 * 调用后会把返回的新值写回同一个槽位。
 *
 * @param injectionPoint 修改位置；当前支持 [InjectionPoint.HEAD]、[InjectionPoint.LOAD] 与 [InjectionPoint.STORE]
 * @param variableIndex 要修改的 JVM 局部变量槽位索引
 * @param ordinal 未指定 [variableIndex] 时，同类型入口参数、读取点或写入点的序号
 * @param slice 切片范围；[InjectionPoint.LOAD] 与 [InjectionPoint.STORE] 使用 INVOKE 边界缩小匹配范围
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
    private val slice: Slice = Slice(),
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
    override fun inject(target: MethodNode): Boolean = injectCount(target) > 0

    /**
     * 在目标方法入口、变量读取点前或变量写入点后修改指定局部变量槽位并返回实际修改数量。
     *
     * @param target 目标方法
     * @return 实际写入变量改写逻辑的数量；HEAD 模式最多为 1，LOAD/STORE 模式为匹配读写点数量
     * @throws IllegalArgumentException 注入点、槽位索引或 handler 签名不合法时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun injectCount(target: MethodNode): Int {
        return when (injectionPoint) {
            InjectionPoint.HEAD -> injectAtHeadCount(target)
            InjectionPoint.LOAD -> injectBeforeLoadCount(target)
            InjectionPoint.STORE -> injectAfterStoreCount(target)
            else -> throw IllegalArgumentException(
                "@ModifyVariable currently supports only HEAD, LOAD and STORE injection points",
            )
        }
    }

    private fun injectAtHeadCount(target: MethodNode): Int {
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

        return 1
    }

    private fun injectBeforeLoadCount(target: MethodNode): Int {
        val handlerVariableType = requireHandlerVariableArgumentType()
        var injectionCount = 0
        var matchedOrdinal = 0

        val insns = target.instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)
        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
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
            injectionCount++
        }

        return injectionCount
    }

    private fun injectAfterStoreCount(target: MethodNode): Int {
        val handlerVariableType = requireHandlerVariableArgumentType()
        var injectionCount = 0
        var matchedOrdinal = 0

        val insns = target.instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)
        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
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
            injectionCount++
        }

        return injectionCount
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
        if (handlerParams.isEmpty() || !isHandlerParameterCompatible(variableType, handlerParams[0])) {
            throw IllegalArgumentException(
                "@ModifyVariable handler ${asmMethod.name} first parameter must be $variableType " +
                    "or compatible Object/Any, actual ${handlerParams.toList()}",
            )
        }

        val handlerReturnType = Type.getReturnType(asmMethod)
        if (!isHandlerReturnCompatible(variableType, handlerReturnType)) {
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
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalArgumentException(
                    "@ModifyVariable handler ${asmMethod.name} target parameter #$index mismatch: expected $expected, actual $actual",
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
            return actual.sort == Type.OBJECT &&
                (actual.internalName == "java/lang/Object" || actual.internalName == "kotlin/Any")
        }
        return false
    }

    private fun isHandlerReturnCompatible(
        variableType: Type,
        handlerReturnType: Type,
    ): Boolean {
        if (variableType == handlerReturnType) {
            return true
        }
        if (handlerReturnType == Type.VOID_TYPE) {
            return false
        }
        if (!variableType.isReferenceType() || !handlerReturnType.isReferenceType()) {
            return false
        }
        return runCatching {
            val variableClass = loadReferenceClass(variableType)
            variableClass.isAssignableFrom(asmMethod.returnType)
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
            "Only INVOKE slice boundaries are supported for @ModifyVariable(LOAD/STORE): ${at.value}"
        }

        val (boundaryOwner, boundaryName, boundaryDesc) = parseTargetMethod(at.target)
        if (boundaryName == null || boundaryDesc == null) {
            throw IllegalArgumentException(
                "Invalid ModifyVariable slice boundary method signature: ${at.target} " +
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
