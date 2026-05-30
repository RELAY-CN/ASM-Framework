/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.injector.impl

import kim.der.asm.api.annotation.Args
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
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * ModifyArgs 注入器。
 *
 * 该注入器会匹配目标方法中的指定方法调用、构造器调用或 `invokedynamic` 调用，把原调用参数保存到 [Args] 容器并传给 handler。
 * handler 可就地修改容器内容，注入器随后从容器中取回整组参数并恢复原方法调用。
 * 实例方法调用的 receiver 会被保存和恢复，但不会放入 [Args]；构造器调用只把构造器描述符中的参数放入 [Args]，
 * 不暴露未初始化 receiver；`invokedynamic` 调用按调用点描述符读取和写回参数，不存在 receiver。
 * [At.target] 为空时会扫描普通方法调用、构造器调用和 `invokedynamic` 调用；可配合 [ordinal]、[slice]
 * 或命中数约束收窄候选。
 *
 * @param at 调用点定位；当前仅支持 [InjectionPoint.INVOKE]，目标为空时按兼容调用点推断
 * @param ordinal 匹配调用点序号；负数表示处理全部匹配调用点
 * @param slice 切片范围；当前使用 INVOKE 边界缩小匹配范围，边界可匹配普通方法调用、构造器调用或
 * `invokedynamic` 调用
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
class ModifyArgsInjector(
    method: Method,
    asmInfo: AsmInfo,
    private val at: At,
    private val ordinal: Int = -1,
    private val slice: Slice = Slice(),
) : AbstractAsmInjector(method, asmInfo) {
    /**
     * 在匹配调用点前插入参数组修改逻辑。
     *
     * @param target 目标方法
     * @return 至少匹配并改写一个调用点时返回 `true`
     * @throws IllegalArgumentException 调用点、handler 签名或目标方法参数不合法时抛出
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun inject(target: MethodNode): Boolean = injectCount(target) > 0

    /**
     * 在匹配调用点前插入参数组修改逻辑并返回实际修改数量。
     *
     * @param target 目标方法
     * @return 实际写入参数组修改逻辑的调用点数量
     * @throws IllegalArgumentException 调用点、handler 签名或目标方法参数不合法时抛出
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun injectCount(target: MethodNode): Int {
        if (at.value != InjectionPoint.INVOKE) {
            throw IllegalArgumentException("@ModifyArgs currently supports only INVOKE injection point")
        }

        val inferTarget = at.target.isEmpty()
        val (targetOwner, targetName, targetDesc) = parseTargetMethod(at.target)
        if (!inferTarget && (targetName == null || targetDesc == null)) {
            throw IllegalArgumentException("@ModifyArgs INVOKE requires at.target method signature")
        }

        val targetParamCount = validateHandlerSignature(target)
        var injectionCount = 0
        var matchedOrdinal = 0
        val insns = target.instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)

        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            val callSite =
                if (inferTarget) {
                    describeAnyCallSite(insn)
                } else {
                    describeMatchedCallSite(insn, targetOwner, targetName!!, targetDesc)
                } ?: continue

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            val il = buildArgsModification(target, callSite, Type.getArgumentTypes(callSite.desc), targetParamCount)
            target.instructions.insertBefore(insn, il)
            injectionCount++
        }

        return injectionCount
    }

    /**
     * 构建一次调用参数组改写的指令序列。
     *
     * 原调用点参数位于操作数栈上，方法会先按逆序保存参数和可选 receiver 到临时局部变量，
     * 再用原参数创建 [Args] 容器并传给 handler。handler 返回后，从容器按原顺序取回参数，
     * 恢复 receiver 与调用参数栈形态，让原调用指令继续执行。
     *
     * @param target 目标方法
     * @param callSite 已匹配的调用点信息
     * @param callParamTypes 调用点描述符中的参数类型
     * @param targetParamCount handler 额外声明的目标方法参数数量
     * @return 可插入到调用点前的参数组改写指令列表
     */
    private fun buildArgsModification(
        target: MethodNode,
        callSite: CallSite,
        callParamTypes: Array<Type>,
        targetParamCount: Int,
    ): InsnList {
        val il = InsnList()
        val firstTempIndex = nextLocalIndex(target)
        var nextTempIndex = firstTempIndex
        val receiverIndex =
            if (!callSite.hasReceiver) {
                null
            } else {
                nextTempIndex.also { nextTempIndex += 1 }
            }
        val argSlots =
            callParamTypes.map { paramType ->
                nextTempIndex.also { nextTempIndex += paramType.size }
            }
        val argsIndex = nextTempIndex

        for (index in callParamTypes.indices.reversed()) {
            storeStackValue(il, callParamTypes[index], argSlots[index])
        }
        if (receiverIndex != null) {
            il.add(VarInsnNode(Opcodes.ASTORE, receiverIndex))
        }

        createArgsContainer(il, callParamTypes, argSlots)
        il.add(VarInsnNode(Opcodes.ASTORE, argsIndex))

        addHandlerOwner(il)
        il.add(VarInsnNode(Opcodes.ALOAD, argsIndex))
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

        if (receiverIndex != null) {
            il.add(VarInsnNode(Opcodes.ALOAD, receiverIndex))
        }
        for (index in callParamTypes.indices) {
            loadArgsValue(il, argsIndex, index, callParamTypes[index])
        }

        return il
    }

    /**
     * 创建保存调用参数快照的 [Args] 容器。
     *
     * 方法会创建 `Object[]`，按调用点参数顺序从临时槽位加载参数，并对基础类型执行装箱。
     * handler 可通过 [Args] 就地修改该数组中的值。
     *
     * @param il 正在构建的指令列表
     * @param callParamTypes 调用点参数类型
     * @param argSlots 每个调用点参数保存到的临时槽位
     */
    private fun createArgsContainer(
        il: InsnList,
        callParamTypes: Array<Type>,
        argSlots: List<Int>,
    ) {
        val argsType = Type.getType(Args::class.java)
        il.add(TypeInsnNode(Opcodes.NEW, argsType.internalName))
        il.add(InsnNode(Opcodes.DUP))
        il.add(LdcInsnNode(callParamTypes.size))
        il.add(TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"))
        for (index in callParamTypes.indices) {
            il.add(InsnNode(Opcodes.DUP))
            il.add(LdcInsnNode(index))
            loadFromVariable(il, callParamTypes[index], argSlots[index])
            InstructionUtil.box(callParamTypes[index])?.let { il.add(it) }
            il.add(InsnNode(Opcodes.AASTORE))
        }
        il.add(
            MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                argsType.internalName,
                "<init>",
                "([Ljava/lang/Object;)V",
                false,
            ),
        )
    }

    /**
     * 从 [Args] 容器回读指定参数并恢复为调用点所需类型。
     *
     * `Args.get` 返回 `Object`，因此基础类型参数需要拆箱，引用类型参数保持引用栈值。
     *
     * @param il 正在构建的指令列表
     * @param argsIndex [Args] 容器所在局部变量槽位
     * @param argumentIndex 参数在调用点描述符中的序号
     * @param argumentType 参数原始 JVM 类型
     */
    private fun loadArgsValue(
        il: InsnList,
        argsIndex: Int,
        argumentIndex: Int,
        argumentType: Type,
    ) {
        val argsType = Type.getType(Args::class.java)
        il.add(VarInsnNode(Opcodes.ALOAD, argsIndex))
        il.add(LdcInsnNode(argumentIndex))
        il.add(
            MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                argsType.internalName,
                "get",
                "(I)Ljava/lang/Object;",
                false,
            ),
        )
        InstructionUtil.unbox(argumentType).forEach { il.add(it) }
    }

    /**
     * 校验 `@ModifyArgs` handler 签名并返回需要加载的目标方法参数数量。
     *
     * handler 第一个参数必须是 [Args]，返回值必须为 `void`。后续参数按顺序匹配目标方法参数前缀，
     * 用于让 handler 在修改调用点参数时读取目标方法上下文。
     *
     * @param target 目标方法
     * @return handler 需要追加加载的目标方法参数数量
     * @throws IllegalArgumentException handler 首参、返回值或目标方法参数前缀不兼容时抛出
     */
    private fun validateHandlerSignature(target: MethodNode): Int {
        val asmParamTypes = Type.getArgumentTypes(asmMethod)
        val argsType = Type.getType(Args::class.java)
        if (asmParamTypes.isEmpty() || asmParamTypes[0] != argsType) {
            throw IllegalArgumentException(
                "@ModifyArgs handler ${asmMethod.name} first parameter must be Args, actual ${asmParamTypes.toList()}",
            )
        }

        val returnType = Type.getReturnType(asmMethod)
        if (returnType != Type.VOID_TYPE) {
            throw IllegalArgumentException("@ModifyArgs handler ${asmMethod.name} must return void, actual $returnType")
        }

        val targetParamTypes = Type.getArgumentTypes(target.desc)
        val requestedTargetParamCount = asmParamTypes.size - 1
        if (requestedTargetParamCount > targetParamTypes.size) {
            throw IllegalArgumentException(
                "@ModifyArgs handler ${asmMethod.name} requests $requestedTargetParamCount target parameter(s), " +
                    "but target method ${target.name}${target.desc} has only ${targetParamTypes.size}",
            )
        }

        for (index in 0 until requestedTargetParamCount) {
            val expected = targetParamTypes[index]
            val actual = asmParamTypes[index + 1]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalArgumentException(
                    "@ModifyArgs handler ${asmMethod.name} target parameter #$index mismatch: expected $expected, actual $actual",
                )
            }
        }

        return requestedTargetParamCount
    }

    /**
     * 判断 handler 参数声明是否能接收目标方法参数值。
     *
     * 基础类型必须精确匹配；引用类型允许 handler 参数声明为目标参数类型的父类、接口、
     * `java.lang.Object` 或 `kotlin.Any`。
     *
     * @param expected 目标方法参数实际类型
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
     * 按 handler 签名需要加载目标方法参数前缀。
     *
     * 参数从目标方法声明顺序的第一个参数开始加载，实例方法会跳过 `this` 槽位，
     * 并按参数类型宽度推进局部变量槽位。
     *
     * @param il 正在构建的指令列表
     * @param target 目标方法
     * @param requestedTargetParamCount 需要追加加载的目标方法参数数量
     */
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

    /**
     * 从局部变量槽位加载指定类型的值。
     *
     * @param il 正在构建的指令列表
     * @param paramType 要加载的 JVM 类型
     * @param varIndex 局部变量槽位
     */
    private fun loadFromVariable(
        il: InsnList,
        paramType: Type,
        varIndex: Int,
    ) {
        InstructionUtil.loadParam(paramType, varIndex).let { il.add(it) }
    }

    /**
     * 把当前栈顶值保存到临时局部变量槽位。
     *
     * 参数组改写前需要先保存原调用点参数；基础类型会选择对应 STORE 指令，引用类型使用 ASTORE。
     *
     * @param il 正在构建的指令列表
     * @param paramType 栈顶值类型
     * @param varIndex 目标局部变量槽位
     */
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

    /**
     * 为非静态 handler 加载调用接收者。
     *
     * Kotlin `object` 使用 `INSTANCE` 字段；普通类按无参构造器创建临时实例。
     * 静态 handler 不需要接收者，本方法直接返回。
     *
     * @param il 正在构建的指令列表
     */
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

    /**
     * 选择调用 handler 时使用的方法调用 opcode。
     *
     * @return 静态 handler 使用 [Opcodes.INVOKESTATIC]，否则使用 [Opcodes.INVOKEVIRTUAL]
     */
    private fun handlerOpcode(): Int =
        if (isHandlerStatic()) {
            Opcodes.INVOKESTATIC
        } else {
            Opcodes.INVOKEVIRTUAL
        }

    /**
     * 判断 handler 方法是否为 Java 反射意义上的静态方法。
     *
     * Kotlin companion 或 object 中带 `@JvmStatic` 的方法会按静态 handler 调用。
     *
     * @return handler 具有 [Modifier.STATIC] 标记时返回 `true`
     */
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
            "Only INVOKE slice boundaries are supported for @ModifyArgs(INVOKE): ${at.value}"
        }

        val (boundaryOwner, boundaryName, boundaryDesc) = parseTargetMethod(at.target)
        if (boundaryName == null || boundaryDesc == null) {
            throw IllegalArgumentException(
                "Invalid ModifyArgs slice boundary method signature: ${at.target} " +
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

    private fun describeMatchedCallSite(
        insn: AbstractInsnNode,
        targetOwner: String?,
        targetName: String,
        targetDesc: String?,
    ): CallSite? =
        when (insn) {
            is MethodInsnNode ->
                if (matchesTargetMethod(insn, targetOwner, targetName, targetDesc)) {
                    CallSite(
                        desc = insn.desc,
                        hasReceiver = insn.opcode != Opcodes.INVOKESTATIC,
                    )
                } else {
                    null
                }
            is InvokeDynamicInsnNode ->
                if (matchesTargetInvokeDynamic(insn, targetOwner, targetName, targetDesc)) {
                    CallSite(desc = insn.desc, hasReceiver = false)
                } else {
                    null
                }
            else -> null
        }

    private fun describeAnyCallSite(insn: AbstractInsnNode): CallSite? =
        when (insn) {
            is MethodInsnNode ->
                CallSite(
                    desc = insn.desc,
                    hasReceiver = insn.opcode != Opcodes.INVOKESTATIC,
                )
            is InvokeDynamicInsnNode -> CallSite(desc = insn.desc, hasReceiver = false)
            else -> null
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

    private data class CallSite(
        val desc: String,
        val hasReceiver: Boolean,
    )
}
