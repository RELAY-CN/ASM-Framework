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
 * ModifyReceiver 注入器。
 *
 * 该注入器会匹配目标方法内的实例方法调用或实例字段访问，在原操作执行前把 receiver 交给 handler 改写。
 * handler 返回的新 receiver 会替代原 receiver，随后恢复原调用参数或字段写入值并继续执行原操作。
 * [InjectionPoint.INVOKE] 未指定调用目标时，会按 handler 首参与返回类型筛选兼容的实例调用 receiver；
 * 静态调用、构造器调用和 handler 不兼容的实例调用不会计入 [ModifyReceiver.ordinal] 或命中数。
 * [InjectionPoint.FIELD] 未指定字段目标时，会按 handler 首参与返回类型筛选兼容的实例字段读取 receiver；
 * 静态字段和 handler 不兼容的字段读取不会计入 [ModifyReceiver.ordinal] 或命中数。
 * [InjectionPoint.FIELD_ASSIGN] 未指定字段目标时，会按 handler 首参与返回类型筛选兼容的实例字段写入 receiver；
 * 静态字段和 handler 不兼容的字段写入不会计入 [ModifyReceiver.ordinal] 或命中数。
 *
 * @param at 调用点定位；当前支持 [InjectionPoint.INVOKE]、[InjectionPoint.FIELD] 与 [InjectionPoint.FIELD_ASSIGN]
 * @param ordinal 匹配调用点序号；负数表示处理全部匹配调用点
 * @param slice 切片范围；当前 [InjectionPoint.INVOKE]、[InjectionPoint.FIELD] 与 [InjectionPoint.FIELD_ASSIGN]
 * receiver 改写使用 INVOKE 边界缩小匹配范围，边界可匹配普通方法调用、构造器调用或 `invokedynamic` 调用
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
class ModifyReceiverInjector(
    method: Method,
    asmInfo: AsmInfo,
    private val at: At,
    private val ordinal: Int = -1,
    private val slice: Slice = Slice(),
) : AbstractAsmInjector(method, asmInfo) {
    /**
     * 在匹配实例调用点或字段访问点前改写 receiver。
     *
     * @param target 目标方法
     * @return 至少匹配并改写一个 receiver 时返回 `true`
     * @throws IllegalArgumentException 调用点、目标调用或 handler 签名不合法时抛出
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun inject(target: MethodNode): Boolean = injectCount(target) > 0

    /**
     * 在匹配实例调用点或字段访问点前改写 receiver，并返回实际改写数量。
     *
     * @param target 目标方法
     * @return 实际改写的 receiver 数量
     * @throws IllegalArgumentException 调用点、目标调用或 handler 签名不合法时抛出
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun injectCount(target: MethodNode): Int =
        when (at.value) {
            InjectionPoint.INVOKE -> injectMethodCall(target)
            InjectionPoint.FIELD -> injectFieldRead(target)
            InjectionPoint.FIELD_ASSIGN -> injectFieldAssign(target)
            else -> throw IllegalArgumentException(
                "@ModifyReceiver supports only INVOKE, FIELD and FIELD_ASSIGN injection points",
            )
        }

    /**
     * 在匹配的实例方法调用前改写 receiver。
     *
     * 显式声明目标时按 [At.target] 匹配调用点；未声明目标时会跳过静态调用、构造器调用和
     * handler 签名不兼容的实例调用。通过筛选的调用点再应用 [ordinal]，并在原调用前插入
     * receiver 改写逻辑。
     *
     * @param target 目标方法
     * @return 实际插入 receiver 改写逻辑的实例调用数量
     * @throws IllegalArgumentException 显式目标签名无效或匹配到不支持的静态/构造器调用时抛出
     */
    private fun injectMethodCall(target: MethodNode): Int {
        val inferTarget = at.target.isEmpty()
        val (targetOwner, targetName, targetDesc) = parseTargetMethod(at.target)
        if (!inferTarget && (targetName == null || targetDesc == null)) {
            throw IllegalArgumentException("@ModifyReceiver INVOKE requires at.target method signature")
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
                insn !is MethodInsnNode ||
                !(inferTarget || (targetName != null && matchesTargetMethod(insn, targetOwner, targetName, targetDesc)))
            ) {
                continue
            }
            if (inferTarget && !isMethodReceiverCompatible(target, insn)) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            if (insn.opcode == Opcodes.INVOKESTATIC || insn.name == "<init>") {
                throw IllegalArgumentException(
                    "@ModifyReceiver supports only instance method calls, target ${insn.owner}.${insn.name}${insn.desc}",
                )
            }

            val receiverType = Type.getObjectType(insn.owner)
            val targetParamCount = validateHandlerSignature(target, receiverType)
            val il = buildCallReceiverModification(target, insn, receiverType, targetParamCount)
            target.instructions.insertBefore(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 判断自动推断模式下的方法调用 receiver 是否可由 handler 改写。
     *
     * 静态调用和构造器调用没有可替换的普通 receiver，会直接排除；其余实例调用按 owner
     * 类型校验 handler 首参、返回值和目标方法参数前缀。
     *
     * @param target 目标方法
     * @param insn 待检查的方法调用指令
     * @return handler 可处理该调用 receiver 时返回 `true`
     */
    private fun isMethodReceiverCompatible(
        target: MethodNode,
        insn: MethodInsnNode,
    ): Boolean {
        if (insn.opcode == Opcodes.INVOKESTATIC || insn.name == "<init>") {
            return false
        }
        val receiverType = Type.getObjectType(insn.owner)
        return isReceiverHandlerCompatible(target, receiverType)
    }

    /**
     * 在匹配的实例字段读取前改写 receiver。
     *
     * 显式声明目标时按字段 owner、名称和描述符匹配；未声明目标时跳过静态字段读取和
     * handler 签名不兼容的字段 owner。通过筛选的读取点再应用 [ordinal]。
     *
     * @param target 目标方法
     * @return 实际插入 receiver 改写逻辑的字段读取数量
     * @throws IllegalArgumentException 显式字段目标无效或匹配到静态字段读取时抛出
     */
    private fun injectFieldRead(target: MethodNode): Int {
        val inferTarget = at.target.isEmpty()
        val fieldTarget = parseFieldTarget(at.target)
        if (!inferTarget && fieldTarget.name == null) {
            throw IllegalArgumentException("@ModifyReceiver FIELD requires at.target field signature")
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
            if (inferTarget && insn.opcode == Opcodes.GETSTATIC) {
                continue
            }

            if (insn.opcode == Opcodes.GETSTATIC) {
                throw IllegalArgumentException(
                    "@ModifyReceiver supports only instance field reads, target ${insn.owner}.${insn.name}:${insn.desc}",
                )
            }

            val receiverType = Type.getObjectType(insn.owner)
            if (inferTarget && !isReceiverHandlerCompatible(target, receiverType)) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val targetParamCount = validateHandlerSignature(target, receiverType)
            val il = buildFieldReadReceiverModification(target, receiverType, targetParamCount)
            target.instructions.insertBefore(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 在匹配的实例字段写入前改写 receiver。
     *
     * 字段写入指令前的栈形态为 receiver 后跟待写入值；生成的改写逻辑会同时保护待写入值，
     * 确保替换 receiver 后仍能按原顺序执行字段写入。
     *
     * @param target 目标方法
     * @return 实际插入 receiver 改写逻辑的字段写入数量
     * @throws IllegalArgumentException 显式字段目标无效或匹配到静态字段写入时抛出
     */
    private fun injectFieldAssign(target: MethodNode): Int {
        val inferTarget = at.target.isEmpty()
        val fieldTarget = parseFieldTarget(at.target)
        if (!inferTarget && fieldTarget.name == null) {
            throw IllegalArgumentException("@ModifyReceiver FIELD_ASSIGN requires at.target field signature")
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
                insn.opcode !in FIELD_WRITE_OPS ||
                !(inferTarget || matchesTargetField(insn, fieldTarget))
            ) {
                continue
            }
            if (inferTarget && insn.opcode == Opcodes.PUTSTATIC) {
                continue
            }

            if (insn.opcode == Opcodes.PUTSTATIC) {
                throw IllegalArgumentException(
                    "@ModifyReceiver supports only instance field writes, target ${insn.owner}.${insn.name}:${insn.desc}",
                )
            }

            val receiverType = Type.getObjectType(insn.owner)
            val fieldType = Type.getType(insn.desc)
            if (inferTarget && !isReceiverHandlerCompatible(target, receiverType)) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val targetParamCount = validateHandlerSignature(target, receiverType)
            val il = buildFieldAssignReceiverModification(target, receiverType, fieldType, targetParamCount)
            target.instructions.insertBefore(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 判断 handler 签名是否可处理指定 receiver 类型。
     *
     * 该方法用于自动推断模式下的候选预筛选；签名不兼容时吞掉校验异常并返回 `false`，
     * 避免不兼容候选计入 ordinal 或命中数。
     *
     * @param target 目标方法
     * @param receiverType 候选 receiver 类型
     * @return handler 可处理该 receiver 类型时返回 `true`
     */
    private fun isReceiverHandlerCompatible(
        target: MethodNode,
        receiverType: Type,
    ): Boolean = runCatching { validateHandlerSignature(target, receiverType) }.isSuccess

    /**
     * 构建实例方法调用 receiver 改写的指令序列。
     *
     * 原实例调用的参数和 receiver 已经位于操作数栈上。方法会先保存调用参数与 receiver，
     * 调用 handler 生成新 receiver，再恢复原调用参数，使后续原方法调用继续消费同一组参数。
     *
     * @param target 目标方法
     * @param callInsn 被改写 receiver 的方法调用指令
     * @param receiverType 原 receiver 类型
     * @param targetParamCount handler 额外声明的目标方法参数数量
     * @return 可插入到实例调用前的 receiver 改写指令列表
     */
    private fun buildCallReceiverModification(
        target: MethodNode,
        callInsn: MethodInsnNode,
        receiverType: Type,
        targetParamCount: Int,
    ): InsnList {
        val il = InsnList()
        val callParamTypes = Type.getArgumentTypes(callInsn.desc)
        var nextTempIndex = nextLocalIndex(target)
        val receiverIndex = nextTempIndex.also { nextTempIndex += 1 }
        val argSlots =
            callParamTypes.map { paramType ->
                nextTempIndex.also { nextTempIndex += paramType.size }
            }

        for (index in callParamTypes.indices.reversed()) {
            storeStackValue(il, callParamTypes[index], argSlots[index])
        }
        il.add(VarInsnNode(Opcodes.ASTORE, receiverIndex))

        addHandlerOwner(il)
        il.add(VarInsnNode(Opcodes.ALOAD, receiverIndex))
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

        addReceiverCast(il, receiverType)
        for (index in callParamTypes.indices) {
            loadFromVariable(il, callParamTypes[index], argSlots[index])
        }

        return il
    }

    /**
     * 构建实例字段读取 receiver 改写的指令序列。
     *
     * 字段读取前栈顶是 receiver。方法会保存原 receiver，调用 handler 获取新 receiver，
     * 并把新 receiver 留在栈顶供原 GETFIELD 指令继续执行。
     *
     * @param target 目标方法
     * @param receiverType 字段 owner 对应的 receiver 类型
     * @param targetParamCount handler 额外声明的目标方法参数数量
     * @return 可插入到字段读取前的 receiver 改写指令列表
     */
    private fun buildFieldReadReceiverModification(
        target: MethodNode,
        receiverType: Type,
        targetParamCount: Int,
    ): InsnList {
        val il = InsnList()
        val receiverIndex = nextLocalIndex(target)

        il.add(VarInsnNode(Opcodes.ASTORE, receiverIndex))
        addHandlerOwner(il)
        il.add(VarInsnNode(Opcodes.ALOAD, receiverIndex))
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
        addReceiverCast(il, receiverType)

        return il
    }

    /**
     * 构建实例字段写入 receiver 改写的指令序列。
     *
     * 字段写入前栈上同时存在 receiver 与待写入值。方法会先保存待写入值和 receiver，
     * 调用 handler 替换 receiver，再把字段值重新加载回栈顶供原 PUTFIELD 指令继续执行。
     *
     * @param target 目标方法
     * @param receiverType 字段 owner 对应的 receiver 类型
     * @param fieldType 待写入字段值类型
     * @param targetParamCount handler 额外声明的目标方法参数数量
     * @return 可插入到字段写入前的 receiver 改写指令列表
     */
    private fun buildFieldAssignReceiverModification(
        target: MethodNode,
        receiverType: Type,
        fieldType: Type,
        targetParamCount: Int,
    ): InsnList {
        val il = InsnList()
        val receiverIndex = nextLocalIndex(target)
        val fieldValueIndex = receiverIndex + 1

        storeStackValue(il, fieldType, fieldValueIndex)
        il.add(VarInsnNode(Opcodes.ASTORE, receiverIndex))
        addHandlerOwner(il)
        il.add(VarInsnNode(Opcodes.ALOAD, receiverIndex))
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
        addReceiverCast(il, receiverType)
        loadFromVariable(il, fieldType, fieldValueIndex)

        return il
    }

    /**
     * 在 handler 返回泛化引用类型时补充 receiver 类型转换。
     *
     * handler 可返回 `Any` / `Object` 或更泛化的引用类型；原调用或字段访问仍需要 owner 类型，
     * 因此返回类型与 receiver 类型不一致时插入 `CHECKCAST`。
     *
     * @param il 正在构建的指令列表
     * @param receiverType 原调用点或字段 owner 的 receiver 类型
     */
    private fun addReceiverCast(
        il: InsnList,
        receiverType: Type,
    ) {
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (handlerReturnType != receiverType) {
            il.add(TypeInsnNode(Opcodes.CHECKCAST, receiverType.internalName))
        }
    }

    /**
     * 校验 `@ModifyReceiver` handler 签名并返回需要加载的目标方法参数数量。
     *
     * handler 第一个参数必须能接收原 receiver，返回值必须能作为新 receiver 写回调用点。
     * 后续参数按顺序匹配目标方法参数前缀，用于让 handler 读取目标方法上下文。
     *
     * @param target 目标方法
     * @param receiverType 当前候选 receiver 类型
     * @return handler 需要追加加载的目标方法参数数量
     * @throws IllegalArgumentException handler 首参、返回值或目标方法参数前缀不兼容时抛出
     */
    private fun validateHandlerSignature(
        target: MethodNode,
        receiverType: Type,
    ): Int {
        val asmParamTypes = Type.getArgumentTypes(asmMethod)
        if (asmParamTypes.isEmpty() || !isHandlerParameterCompatible(receiverType, asmParamTypes[0])) {
            throw IllegalArgumentException(
                "@ModifyReceiver handler ${asmMethod.name} first parameter must be $receiverType, " +
                    "actual ${asmParamTypes.toList()}",
            )
        }

        val asmReturnType = Type.getReturnType(asmMethod)
        if (!isReceiverReturnCompatible(receiverType, asmReturnType)) {
            throw IllegalArgumentException(
                "@ModifyReceiver handler ${asmMethod.name} return type $asmReturnType must be compatible with receiver type $receiverType",
            )
        }

        val targetParamTypes = Type.getArgumentTypes(target.desc)
        val requestedTargetParamCount = asmParamTypes.size - 1
        if (requestedTargetParamCount > targetParamTypes.size) {
            throw IllegalArgumentException(
                "@ModifyReceiver handler ${asmMethod.name} requests $requestedTargetParamCount target parameter(s), " +
                    "but target method ${target.name}${target.desc} has only ${targetParamTypes.size}",
            )
        }

        for (index in 0 until requestedTargetParamCount) {
            val expected = targetParamTypes[index]
            val actual = asmParamTypes[index + 1]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalArgumentException(
                    "@ModifyReceiver handler ${asmMethod.name} target parameter #$index mismatch: " +
                        "expected $expected, actual $actual",
                )
            }
        }

        return requestedTargetParamCount
    }

    /**
     * 判断 handler 参数声明是否能接收期望类型的运行时值。
     *
     * 基础类型必须精确匹配；引用类型允许 handler 参数声明为期望类型的父类、接口、
     * `java.lang.Object` 或 `kotlin.Any`。
     *
     * @param expected 注入点需要提供的值类型
     * @param actual handler 参数声明类型
     * @return handler 参数可以安全接收该值时返回 `true`
     */
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

    /**
     * 判断 ASM 类型是否属于对象或数组引用类型。
     *
     * @return 当前类型为对象或数组时返回 `true`
     */
    private fun Type.isReferenceType(): Boolean = sort == Type.OBJECT || sort == Type.ARRAY

    /**
     * 使用 Mixin 类加载器解析引用类型对应的 Java Class。
     *
     * 引用兼容性校验需要真实类层级；数组类型使用描述符形式解析，对象类型使用类名解析。
     *
     * @param type 待解析的 ASM 引用类型
     * @return 对应的 Java Class
     * @throws ClassNotFoundException 类加载器无法解析该类型时抛出
     */
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
     * 判断 handler 返回类型是否可作为替换后的 receiver。
     *
     * 返回类型不能为 `void`。引用类型允许 handler 返回 receiver 类型的子类型，
     * 也允许返回 `Any` / `Object`，后续会通过 `CHECKCAST` 恢复调用点所需的 owner 类型。
     *
     * @param receiverType 原调用点或字段 owner 的 receiver 类型
     * @param handlerReturnType handler 返回类型
     * @return handler 返回值可作为新 receiver 时返回 `true`
     */
    private fun isReceiverReturnCompatible(
        receiverType: Type,
        handlerReturnType: Type,
    ): Boolean {
        if (handlerReturnType == Type.VOID_TYPE) {
            return false
        }
        if (receiverType == handlerReturnType) {
            return true
        }
        if (!receiverType.isReferenceType() || !handlerReturnType.isReferenceType()) {
            return false
        }
        if (handlerReturnType.sort == Type.OBJECT &&
            (handlerReturnType.internalName == "java/lang/Object" || handlerReturnType.internalName == "kotlin/Any")
        ) {
            return true
        }
        return runCatching {
            val receiverClass = loadReferenceClass(receiverType)
            receiverClass.isAssignableFrom(asmMethod.returnType)
        }.getOrDefault(false)
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
            "Only INVOKE slice boundaries are supported for @ModifyReceiver: ${at.value}"
        }

        val (boundaryOwner, boundaryName, boundaryDesc) = parseTargetMethod(at.target)
        if (boundaryName == null || boundaryDesc == null) {
            throw IllegalArgumentException(
                "Invalid ModifyReceiver slice boundary method signature: ${at.target} " +
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

        val ownerAndName: String
        val desc: String?
        val colonIndex = signature.indexOf(':')
        if (colonIndex >= 0) {
            ownerAndName = signature.substring(0, colonIndex)
            desc = signature.substring(colonIndex + 1)
        } else {
            ownerAndName = signature
            desc = null
        }

        val slashIndex = ownerAndName.lastIndexOf('/')
        val dotIndex = ownerAndName.lastIndexOf('.')
        val separatorIndex = maxOf(slashIndex, dotIndex)
        return if (separatorIndex >= 0) {
            FieldTarget(
                ownerAndName.substring(0, separatorIndex).replace('.', '/'),
                ownerAndName.substring(separatorIndex + 1),
                desc,
            )
        } else {
            FieldTarget(null, ownerAndName, desc)
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

    private companion object {
        private val FIELD_READ_OPS = setOf(Opcodes.GETFIELD, Opcodes.GETSTATIC)
        private val FIELD_WRITE_OPS = setOf(Opcodes.PUTFIELD, Opcodes.PUTSTATIC)
    }
}
