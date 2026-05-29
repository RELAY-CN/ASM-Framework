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
 * ModifyExpressionValue 注入器。
 *
 * 该注入器会匹配目标方法内的指定普通方法调用、`invokedynamic` 调用、字段读取、数组元素读取、数组长度、对象构造、类型转换、
 * `INSTANCEOF` 判断或 `ATHROW` 抛异常指令，
 * 并在表达式产生值后把原值传给 handler。
 * handler 返回的新值会替代原表达式值留在操作数栈顶，后续原始字节码继续按未修改的栈形态执行。
 * 对象或数组表达式可用原值类型的父类、接口、`Any` 或 `Object` 接收。
 * handler 返回类型对基础类型必须精确匹配，引用表达式可返回表达式类型的子类型，也可用 `Any` 或 `Object`
 * 作为泛型引用返回类型，框架会在调用后转换回表达式类型。
 * [InjectionPoint.THROW] 会在目标 `ATHROW` 前改写即将抛出的异常，也可继续追加目标方法参数；
 * 指定类型目标时，只匹配 `ATHROW` 前直接构造出的同类型异常。
 *
 * @param at 表达式定位；当前支持 [InjectionPoint.INVOKE]、[InjectionPoint.INVOKE_ASSIGN]、
 * [InjectionPoint.FIELD]、[InjectionPoint.NEW]、[InjectionPoint.CAST]、[InjectionPoint.INSTANCEOF] 与 [InjectionPoint.THROW]；[InjectionPoint.FIELD]
 * 可匹配字段读取值，省略字段目标时会按 handler 首参与返回类型筛选兼容的 `GETFIELD` / `GETSTATIC`；
 * 也可通过 `array=get` 匹配数组元素读取值，通过 `array=length` 匹配数组长度值，
 * [InjectionPoint.INVOKE] / [InjectionPoint.INVOKE_ASSIGN] 可显式匹配普通调用或按 bootstrap owner、动态调用名、bootstrap 名
 * 以及动态调用点描述符匹配 `invokedynamic` 返回值；未指定调用目标时，会按 handler 首参与返回类型筛选兼容的非 `void` 调用返回；
 * [InjectionPoint.NEW] 匹配对象构造完成后的实例，未指定类型目标时，会按 handler 首参与返回类型筛选兼容的 `NEW` 候选；
 * [InjectionPoint.CAST] 匹配 `CHECKCAST` 完成后的对象值；未指定类型目标时，会按 handler 首参与返回类型筛选兼容的
 * `CHECKCAST` 候选；不兼容的调用返回、字段读取、`NEW` / `CHECKCAST` 候选不计入 [ModifyExpressionValue.ordinal] 或命中数。
 * [InjectionPoint.INSTANCEOF] 匹配类型判断后的 boolean 结果，
 * [InjectionPoint.THROW] 匹配 `ATHROW` 前即将抛出的 `Throwable`，handler 可返回 `Throwable` 或其子类；
 * 指定类型目标时，只会匹配前一条真实指令为同类型 `<init>` 的直接构造异常
 * @param ordinal 表达式匹配点序号；负数表示处理全部匹配表达式
 * @param slice 切片范围；当前 [InjectionPoint.INVOKE] / [InjectionPoint.INVOKE_ASSIGN] 调用返回、
 * [InjectionPoint.FIELD] 字段读取、数组元素读取、数组长度、[InjectionPoint.NEW]、[InjectionPoint.CAST]、
 * [InjectionPoint.INSTANCEOF] 与 [InjectionPoint.THROW] 表达式使用 INVOKE 边界缩小匹配范围
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
class ModifyExpressionValueInjector(
    method: Method,
    asmInfo: AsmInfo,
    private val at: At,
    private val ordinal: Int = -1,
    private val slice: Slice = Slice(),
) : AbstractAsmInjector(method, asmInfo) {
    /**
     * 在匹配表达式产生值后改写该值。
     *
     * @param target 目标方法
     * @return 至少匹配并改写一个表达式值时返回 `true`
     * @throws IllegalArgumentException 定位点、目标表达式或 handler 签名不合法时抛出
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun inject(target: MethodNode): Boolean = injectCount(target) > 0

    /**
     * 在匹配表达式产生值后改写该值并返回实际修改数量。
     *
     * @param target 目标方法
     * @return 实际写入表达式值修改逻辑的数量
     * @throws IllegalArgumentException 定位点、目标表达式或 handler 签名不合法时抛出
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun injectCount(target: MethodNode): Int {
        return when (at.value) {
            InjectionPoint.INVOKE, InjectionPoint.INVOKE_ASSIGN -> injectMethodCallReturn(target)
            InjectionPoint.FIELD ->
                when (arrayAccessMode()) {
                    ArrayAccessMode.NONE -> injectFieldRead(target)
                    ArrayAccessMode.GET -> injectArrayRead(target)
                    ArrayAccessMode.LENGTH -> injectArrayLength(target)
                }
            InjectionPoint.NEW -> injectNewObject(target)
            InjectionPoint.CAST -> injectCast(target)
            InjectionPoint.INSTANCEOF -> injectInstanceof(target)
            InjectionPoint.THROW -> injectThrow(target)
            else -> throw IllegalArgumentException(
                "@ModifyExpressionValue currently supports only INVOKE, INVOKE_ASSIGN, FIELD, NEW, CAST, INSTANCEOF and THROW",
            )
        }
    }

    private fun arrayAccessMode(): ArrayAccessMode {
        val arrayArg = at.args.firstOrNull { it.trim().startsWith("array=") } ?: return ArrayAccessMode.NONE
        return when (arrayArg.substringAfter('=').trim().lowercase()) {
            "get" -> ArrayAccessMode.GET
            "length" -> ArrayAccessMode.LENGTH
            "set" -> throw IllegalArgumentException(
                "@ModifyExpressionValue array access supports only array=get and array=length",
            )
            else -> throw IllegalArgumentException("Unsupported @ModifyExpressionValue array access mode: $arrayArg")
        }
    }

    private fun injectMethodCallReturn(target: MethodNode): Int {
        val inferTarget = at.target.isEmpty()
        val (targetOwner, targetName, targetDesc) = parseTargetMethod(at.target)
        if (!inferTarget && (targetName == null || targetDesc == null)) {
            throw IllegalArgumentException("@ModifyExpressionValue requires at.target method signature")
        }

        var injectionCount = 0
        var matchedOrdinal = 0
        val insns = target.instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)
        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            val callDesc =
                when (insn) {
                    is MethodInsnNode ->
                        if (inferTarget || (targetName != null && matchesTargetMethod(insn, targetOwner, targetName, targetDesc))) {
                            insn.desc
                        } else {
                            null
                        }
                    is InvokeDynamicInsnNode ->
                        if (inferTarget || (targetName != null && matchesTargetInvokeDynamic(insn, targetOwner, targetName, targetDesc))) {
                            insn.desc
                        } else {
                            null
                        }
                    else -> null
                }
            if (callDesc == null) {
                continue
            }

            val callReturnType = Type.getReturnType(callDesc)
            if (callReturnType == Type.VOID_TYPE) {
                if (inferTarget) {
                    continue
                }
                throw IllegalArgumentException(
                    "@ModifyExpressionValue cannot modify void call ${callName(insn)}$callDesc",
                )
            }
            if (inferTarget && !isHandlerCompatible(callReturnType, allowThrowableSubtypeReturn = false)) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val targetParamCount = validateHandlerSignature(target, callReturnType)
            val il = buildExpressionValueModification(target, callReturnType, targetParamCount)
            target.instructions.insert(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    private fun injectFieldRead(target: MethodNode): Int {
        val inferTarget = at.target.isEmpty()
        val fieldTarget = parseFieldTarget(at.target)
        if (!inferTarget && fieldTarget.name == null) {
            throw IllegalArgumentException("@ModifyExpressionValue requires at.target field signature")
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

            val fieldType = Type.getType(insn.desc)
            if (inferTarget && !isHandlerCompatible(fieldType, allowThrowableSubtypeReturn = false)) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val targetParamCount = validateHandlerSignature(target, fieldType)
            val il = buildExpressionValueModification(target, fieldType, targetParamCount)
            target.instructions.insert(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    private fun injectArrayRead(target: MethodNode): Int {
        val fieldTarget = parseFieldTarget(at.target)
        if (fieldTarget.name == null) {
            throw IllegalArgumentException("@ModifyExpressionValue requires at.target array field signature")
        }
        if (fieldTarget.desc != null && Type.getType(fieldTarget.desc).sort != Type.ARRAY) {
            throw IllegalArgumentException("@ModifyExpressionValue array target must be an array field: ${at.target}")
        }

        var injectionCount = 0
        var matchedOrdinal = 0
        val insns = target.instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)
        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (insn.opcode !in ARRAY_READ_OPS) {
                continue
            }

            val fieldInsn = findArrayFieldProducer(insn, fieldTarget) ?: continue
            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val expressionType = Type.getType(fieldInsn.desc).elementType
            val targetParamCount = validateHandlerSignature(target, expressionType)
            val il = buildExpressionValueModification(target, expressionType, targetParamCount)
            target.instructions.insert(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    private fun injectArrayLength(target: MethodNode): Int {
        val fieldTarget = parseFieldTarget(at.target)
        if (fieldTarget.name == null) {
            throw IllegalArgumentException("@ModifyExpressionValue requires at.target array field signature")
        }
        if (fieldTarget.desc != null && Type.getType(fieldTarget.desc).sort != Type.ARRAY) {
            throw IllegalArgumentException("@ModifyExpressionValue array target must be an array field: ${at.target}")
        }

        var injectionCount = 0
        var matchedOrdinal = 0
        val insns = target.instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)
        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (insn.opcode != Opcodes.ARRAYLENGTH) {
                continue
            }

            findArrayFieldProducer(insn, fieldTarget) ?: continue
            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val targetParamCount = validateHandlerSignature(target, Type.INT_TYPE)
            val il = buildExpressionValueModification(target, Type.INT_TYPE, targetParamCount)
            target.instructions.insert(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    private fun injectNewObject(target: MethodNode): Int {
        val normalizedTarget = at.target.replace('.', '/')
        var injectionCount = 0
        var matchedOrdinal = 0
        val insns = target.instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)
        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (insn !is TypeInsnNode || insn.opcode != Opcodes.NEW) {
                continue
            }
            if (normalizedTarget.isNotEmpty() && insn.desc != normalizedTarget) {
                continue
            }

            val expressionType = Type.getObjectType(insn.desc)
            if (normalizedTarget.isEmpty() && !isHandlerCompatible(expressionType, allowThrowableSubtypeReturn = false)) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val constructorInsn = findConstructorInvocation(insn)
            val targetParamCount = validateHandlerSignature(target, expressionType)
            val il = buildExpressionValueModification(target, expressionType, targetParamCount)
            target.instructions.insert(constructorInsn, il)
            injectionCount++
        }

        return injectionCount
    }

    private fun injectCast(target: MethodNode): Int {
        val normalizedTarget = at.target.replace('.', '/')
        var injectionCount = 0
        var matchedOrdinal = 0
        val insns = target.instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)
        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (insn !is TypeInsnNode || insn.opcode != Opcodes.CHECKCAST) {
                continue
            }
            if (normalizedTarget.isNotEmpty() && insn.desc != normalizedTarget) {
                continue
            }

            val expressionType = Type.getObjectType(insn.desc)
            if (normalizedTarget.isEmpty() && !isHandlerCompatible(expressionType, allowThrowableSubtypeReturn = false)) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val targetParamCount = validateHandlerSignature(target, expressionType)
            val il = buildExpressionValueModification(target, expressionType, targetParamCount)
            target.instructions.insert(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    private fun isHandlerCompatible(
        expressionType: Type,
        allowThrowableSubtypeReturn: Boolean,
    ): Boolean {
        val asmParamTypes = Type.getArgumentTypes(asmMethod)
        if (asmParamTypes.isEmpty() || !isHandlerParameterCompatible(expressionType, asmParamTypes[0])) {
            return false
        }
        return isHandlerReturnCompatible(expressionType, Type.getReturnType(asmMethod), allowThrowableSubtypeReturn)
    }

    private fun injectInstanceof(target: MethodNode): Int {
        val normalizedTarget = at.target.replace('.', '/')
        var injectionCount = 0
        var matchedOrdinal = 0
        val insns = target.instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)
        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (insn !is TypeInsnNode || insn.opcode != Opcodes.INSTANCEOF) {
                continue
            }
            if (normalizedTarget.isNotEmpty() && insn.desc != normalizedTarget) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val targetParamCount = validateHandlerSignature(target, Type.BOOLEAN_TYPE)
            val il = buildExpressionValueModification(target, Type.BOOLEAN_TYPE, targetParamCount)
            target.instructions.insert(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    private fun injectThrow(target: MethodNode): Int {
        val normalizedTarget = at.target.replace('.', '/')
        var injectionCount = 0
        var matchedOrdinal = 0
        val insns = target.instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)
        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (insn.opcode != Opcodes.ATHROW) {
                continue
            }
            if (normalizedTarget.isNotEmpty() && directThrownTypeInternalName(insn) != normalizedTarget) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val throwableType = Type.getType(Throwable::class.java)
            val targetParamCount = validateHandlerSignature(target, throwableType, allowThrowableSubtypeReturn = true)
            val il = buildExpressionValueModification(target, throwableType, targetParamCount)
            target.instructions.insertBefore(insn, il)
            injectionCount++
        }

        return injectionCount
    }

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

    private fun buildExpressionValueModification(
        target: MethodNode,
        expressionType: Type,
        targetParamCount: Int,
    ): InsnList {
        val il = InsnList()
        val valueIndex = nextLocalIndex(target)

        storeStackValue(il, expressionType, valueIndex)
        addHandlerOwner(il)
        loadFromVariable(il, expressionType, valueIndex)
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
        addExpressionCastIfNeeded(il, expressionType)

        return il
    }

    private fun validateHandlerSignature(
        target: MethodNode,
        expressionType: Type,
        allowThrowableSubtypeReturn: Boolean = false,
    ): Int {
        val asmParamTypes = Type.getArgumentTypes(asmMethod)
        if (asmParamTypes.isEmpty() || !isHandlerParameterCompatible(expressionType, asmParamTypes[0])) {
            throw IllegalArgumentException(
                "@ModifyExpressionValue handler ${asmMethod.name} first parameter must be $expressionType " +
                    "or compatible Object/Any, " +
                    "actual ${asmParamTypes.toList()}",
            )
        }

        val asmReturnType = Type.getReturnType(asmMethod)
        if (!isHandlerReturnCompatible(expressionType, asmReturnType, allowThrowableSubtypeReturn)) {
            throw IllegalArgumentException(
                "@ModifyExpressionValue handler ${asmMethod.name} return type $asmReturnType " +
                    "must match expression type $expressionType",
            )
        }

        val targetParamTypes = Type.getArgumentTypes(target.desc)
        val requestedTargetParamCount = asmParamTypes.size - 1
        if (requestedTargetParamCount > targetParamTypes.size) {
            throw IllegalArgumentException(
                "@ModifyExpressionValue handler ${asmMethod.name} " +
                    "requests $requestedTargetParamCount target parameter(s), " +
                    "but target method ${target.name}${target.desc} has only ${targetParamTypes.size}",
            )
        }

        for (index in 0 until requestedTargetParamCount) {
            val expected = targetParamTypes[index]
            val actual = asmParamTypes[index + 1]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalArgumentException(
                    "@ModifyExpressionValue handler ${asmMethod.name} target parameter #$index mismatch: " +
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

    private fun isHandlerReturnCompatible(
        expressionType: Type,
        handlerReturnType: Type,
        allowThrowableSubtypeReturn: Boolean,
    ): Boolean {
        if (expressionType == handlerReturnType) {
            return true
        }
        if (handlerReturnType == Type.VOID_TYPE) {
            return false
        }
        if (allowThrowableSubtypeReturn && Throwable::class.java.isAssignableFrom(asmMethod.returnType)) {
            return true
        }
        if (!expressionType.isReferenceType() || !handlerReturnType.isReferenceType()) {
            return false
        }
        if (handlerReturnType.sort == Type.OBJECT &&
            (handlerReturnType.internalName == "java/lang/Object" || handlerReturnType.internalName == "kotlin/Any")
        ) {
            return true
        }
        return runCatching {
            val expressionClass = loadReferenceClass(expressionType)
            expressionClass.isAssignableFrom(asmMethod.returnType)
        }.getOrDefault(false)
    }

    private fun Type.isReferenceType(): Boolean = sort == Type.OBJECT || sort == Type.ARRAY

    private fun addExpressionCastIfNeeded(
        il: InsnList,
        expressionType: Type,
    ) {
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (expressionType != handlerReturnType && expressionType.isReferenceType()) {
            il.add(TypeInsnNode(Opcodes.CHECKCAST, expressionType.internalName))
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

    private fun findConstructorInvocation(newInsn: TypeInsnNode): MethodInsnNode {
        var nestedSameOwnerNewCount = 0
        var current = newInsn.next
        while (current != null) {
            if (current is TypeInsnNode && current.opcode == Opcodes.NEW && current.desc == newInsn.desc) {
                nestedSameOwnerNewCount++
            } else if (
                current is MethodInsnNode &&
                current.opcode == Opcodes.INVOKESPECIAL &&
                current.owner == newInsn.desc &&
                current.name == "<init>"
            ) {
                if (nestedSameOwnerNewCount == 0) {
                    return current
                }
                nestedSameOwnerNewCount--
            }
            current = current.next
        }

        throw IllegalArgumentException("@ModifyExpressionValue cannot find constructor call for NEW ${newInsn.desc}")
    }

    private fun previousRealInstruction(insn: AbstractInsnNode): AbstractInsnNode? {
        var current = insn.previous
        while (current != null && current.opcode < 0) {
            current = current.previous
        }
        return current
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
            "Only INVOKE slice boundaries are supported for @ModifyExpressionValue: ${at.value}"
        }

        val (boundaryOwner, boundaryName, boundaryDesc) = parseTargetMethod(at.target)
        if (boundaryName == null || boundaryDesc == null) {
            throw IllegalArgumentException(
                "Invalid ModifyExpressionValue slice boundary method signature: ${at.target} " +
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

    private fun callName(insn: AbstractInsnNode): String =
        when (insn) {
            is MethodInsnNode -> insn.name
            is InvokeDynamicInsnNode -> insn.name
            else -> "<unknown>"
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
                            "@ModifyExpressionValue array target must be an array field: ${cursor.owner}.${cursor.name}:${cursor.desc}",
                        )
                    }
                    return cursor
                }
                return null
            }
            if (cursor is MethodInsnNode || cursor.opcode in ARRAY_READ_OPS) {
                return null
            }
            cursor = cursor.previous
        }
        return null
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

    private enum class ArrayAccessMode {
        NONE,
        GET,
        LENGTH,
    }

    private companion object {
        private val FIELD_READ_OPS = setOf(Opcodes.GETFIELD, Opcodes.GETSTATIC)
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
    }
}
