/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.injector.impl

import kim.der.asm.api.annotation.At
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.Slice
import kim.der.asm.data.AsmInfo
import kim.der.asm.injector.AbstractAsmInjector
import kim.der.asm.utils.transformer.BytecodeUtil
import kim.der.asm.utils.transformer.InstructionUtil
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * ModifyConstant 注入器。
 *
 * 遍历目标方法中的常量加载指令，并用 ASM 方法返回值替换匹配常量。
 * 当 [constantValue] 为 `null` 时仅按常量类型匹配；指定值时会同时校验常量文本。
 * 支持 `LDC` 字符串、数字、类字面量、方法类型字面量、方法句柄字面量、动态常量，以及
 * `ACONST_NULL`、`ICONST_*`、`LCONST_*`、`FCONST_*`、`DCONST_*`、
 * `BIPUSH` 与 `SIPUSH` 形式的常量加载。
 * ASM 方法的第一个参数接收原常量，后续参数可按顺序接收目标方法的部分参数；
 * 引用类型参数可声明为原值类型的父类、接口、`Any` 或 `Object`，基础类型仍需精确匹配；
 * 引用类型返回值可返回原常量类型的子类型，也可用 `Any` 或 `Object` 作为泛型引用返回类型。
 * 匹配 `ACONST_NULL` 时，允许任意引用类型参数接收原始 `null`，并会按 handler 首参类型确定替换后的引用栈类型。
 * 常量文本匹配后仍会按 handler 返回类型筛选实际候选；例如 `"1"` 可能同时命中 `int` 与 `long` 文本，
 * 但 `Int` handler 只会修改 `int` 常量。
 * [injectCount] 会返回实际替换的常量数量，供上层执行 `@ModifyConstant` 的命中数契约校验。
 *
 * @param constantValue 常量过滤值；为 `null` 表示不按值过滤
 * @param ordinal 匹配常量序号；负数表示处理全部匹配常量
 * @param slice 切片范围；当前使用 INVOKE 边界缩小常量匹配范围，边界可匹配普通方法调用、构造器调用或
 * `invokedynamic` 调用
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
class ModifyConstantInjector(
    method: Method,
    asmInfo: AsmInfo,
    private val constantValue: String? = null,
    private val ordinal: Int = -1,
    private val slice: Slice = Slice(),
) : AbstractAsmInjector(method, asmInfo) {
    /**
     * 替换目标方法中匹配的常量。
     *
     * @param target 目标方法
     * @return 至少替换一个常量时返回 `true`
     * @throws IllegalArgumentException ASM 方法参数或返回类型与常量类型不匹配时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun inject(target: MethodNode): Boolean = injectCount(target) > 0

    /**
     * 替换目标方法中匹配的常量，并返回实际替换数量。
     *
     * @param target 目标方法
     * @return 实际替换的常量数量
     * @throws IllegalArgumentException ASM 方法参数或返回类型与常量类型不匹配时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun injectCount(target: MethodNode): Int {
        val instructions = target.instructions
        var injectionCount = 0
        val insns = instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns)
        var matchedOrdinal = 0

        // 查找所有常量指令
        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }
            if (!BytecodeUtil.isConstant(insn)) {
                continue
            }

            // 如果指定了常量值，检查是否匹配
            if (constantValue != null) {
                if (!BytecodeUtil.matchesConstantText(insn, constantValue)) {
                    continue
                }
            }

            // 获取常量类型
            val constantType = resolveConstantType(insn, constantValue) ?: continue

            // 检查 ASM 方法的返回类型是否匹配
            val asmReturnType = Type.getReturnType(asmMethod)
            if (!isHandlerReturnCompatible(constantType, asmReturnType)) {
                continue
            }

            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal)) {
                continue
            }

            // 注入常量修改
            val replacementType = resolveReplacementType(insn, constantType, asmReturnType)
            if (injectConstantModifier(instructions, insn, target, constantType, replacementType)) {
                injectionCount++
            }
        }

        return injectionCount
    }

    /**
     * 判断当前匹配常量序号是否满足用户声明的 ordinal 过滤。
     *
     * @param currentOrdinal 已通过类型与值过滤的常量序号
     * @return 未声明 ordinal 或序号一致时返回 `true`
     */
    private fun matchesOrdinal(currentOrdinal: Int): Boolean = ordinal < 0 || currentOrdinal == ordinal

    /**
     * 解析常量匹配使用的切片指令范围。
     *
     * `slice.from` 命中后从边界后一条指令开始匹配，`slice.to` 命中位置本身不参与匹配。
     * 任一边界声明但无法命中时返回空范围，避免目标字节码漂移后误修改切片外常量。
     *
     * @param insns 目标方法指令数组
     * @return 可遍历的半开区间 `[start, end)`
     */
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

    /**
     * 判断切片边界是否声明了可匹配目标。
     *
     * @param at 切片边界注解配置
     * @return `target` 非空时返回 `true`
     */
    private fun hasSliceBoundary(at: At): Boolean = at.target.isNotEmpty()

    /**
     * 构造一个位于方法末尾的空切片范围。
     *
     * @param insns 目标方法指令数组
     * @return 不会命中任何指令的半开区间
     */
    private fun emptySlice(insns: Array<AbstractInsnNode>): Pair<Int, Int> = insns.size to insns.size

    /**
     * 从指定位置开始查找切片边界调用指令。
     *
     * 当前 `@ModifyConstant` 的切片边界只支持 [InjectionPoint.INVOKE]，
     * 可匹配普通方法调用、构造器调用或 `invokedynamic` 调用。
     *
     * @param insns 目标方法指令数组
     * @param at 切片边界配置
     * @param startIndex 起始搜索下标
     * @return 匹配边界的指令下标；未命中时返回 `null`
     * @throws IllegalArgumentException 边界注入点类型或目标方法签名不合法时抛出
     */
    private fun findSliceBoundaryIndex(
        insns: Array<AbstractInsnNode>,
        at: At,
        startIndex: Int,
    ): Int? {
        require(at.value == InjectionPoint.INVOKE) {
            "Only INVOKE slice boundaries are supported for @ModifyConstant: ${at.value}"
        }

        val (boundaryOwner, boundaryName, boundaryDesc) = parseTargetMethod(at.target)
        if (boundaryName == null || boundaryDesc == null) {
            throw IllegalArgumentException(
                "Invalid ModifyConstant slice boundary method signature: ${at.target} " +
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

    /**
     * 解析注解中声明的方法目标签名。
     *
     * 支持 `owner/name(desc)`、`owner.name(desc)` 与 `name(desc)` 形式。
     * 未携带 owner 时只按方法名与描述符匹配；未携带描述符时返回 `null` 描述符供调用方判定非法。
     *
     * @param signature 注解中声明的目标签名
     * @return `owner`、`name`、`desc` 三元组；缺失部分以 `null` 表示
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
            Triple(
                ownerAndName.substring(0, separatorIndex).replace('.', '/'),
                ownerAndName.substring(separatorIndex + 1),
                desc,
            )
        } else {
            Triple(null, ownerAndName, desc)
        }
    }

    /**
     * 判断普通方法调用是否匹配切片边界目标。
     *
     * @param insn 待检查的方法调用指令
     * @param targetOwner 目标 owner；为 `null` 时不限制 owner
     * @param targetName 目标方法名
     * @param targetDesc 目标方法描述符；为 `null` 时不限制描述符
     * @return 调用指令匹配目标时返回 `true`
     */
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

    /**
     * 判断 `invokedynamic` 指令是否匹配切片边界目标。
     *
     * owner 匹配 bootstrap method owner；名称可匹配动态调用名或 bootstrap method 名。
     *
     * @param insn 待检查的 `invokedynamic` 指令
     * @param targetOwner 目标 bootstrap owner；为 `null` 时不限制 owner
     * @param targetName 目标动态调用名或 bootstrap method 名
     * @param targetDesc 目标动态调用描述符；为 `null` 时不限制描述符
     * @return 指令匹配目标时返回 `true`
     */
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

    /**
     * 用 handler 调用替换原始常量加载指令。
     *
     * 方法会在原常量指令前插入等价的 handler 调用序列，再移除原始常量指令。
     * 替换后的栈顶值类型由 [replacementType] 决定，用于保持后续消费方看到的常量类型稳定。
     *
     * @param instructions 目标方法指令列表
     * @param constNode 原始常量加载指令
     * @param target 目标方法
     * @param constantType 原始常量类型
     * @param replacementType handler 返回值最终暴露给后续字节码的替换类型
     * @return 成功插入替换调用时返回 `true`
     */
    private fun injectConstantModifier(
        instructions: InsnList,
        constNode: AbstractInsnNode,
        target: MethodNode,
        constantType: Type,
        replacementType: Type,
    ): Boolean {
        val il = InsnList()

        // 调用 ASM 方法修改常量
        generateConstantModifierCall(il, constNode, target, constantType, replacementType)

        // 替换原始常量指令
        instructions.insertBefore(constNode, il)
        instructions.remove(constNode)

        return true
    }

    /**
     * 生成调用 `@ModifyConstant` handler 的指令序列。
     *
     * 生成顺序为：加载非静态 handler 接收者、重新加载原常量、按需加载目标方法参数前缀、
     * 调用 handler，并在引用替换类型更窄时补充 `CHECKCAST`。
     *
     * @param il 正在构建的指令列表
     * @param constNode 原始常量加载指令
     * @param target 目标方法
     * @param constantType 原始常量类型
     * @param replacementType 替换后需要保留在栈上的类型
     * @throws IllegalArgumentException handler 参数签名不兼容时抛出
     */
    private fun generateConstantModifierCall(
        il: InsnList,
        constNode: AbstractInsnNode,
        target: MethodNode,
        constantType: Type,
        replacementType: Type,
    ) {
        val asmParamTypes = Type.getArgumentTypes(asmMethod)
        validateHandlerParameters(target, constantType, asmParamTypes, isNullConstant(constNode))

        val instanceType = Type.getType(asmInfo.asmClass)
        val useStaticCall = Modifier.isStatic(asmMethod.modifiers)

        if (!useStaticCall) {
            loadAsmHandlerReceiver(il, instanceType)
        }

        // 原始常量就是 @ModifyConstant handler 的第一个参数。
        loadConstant(il, constNode, constantType)
        loadTargetMethodParameters(il, target, asmParamTypes.size - 1)

        il.add(
            MethodInsnNode(
                if (useStaticCall) Opcodes.INVOKESTATIC else Opcodes.INVOKEVIRTUAL,
                instanceType.internalName,
                asmMethod.name,
                Type.getMethodDescriptor(asmMethod),
                false,
            ),
        )
        addConstantCastIfNeeded(il, replacementType)
    }

    /**
     * 校验 handler 参数是否能接收原常量与目标方法参数前缀。
     *
     * handler 第一个参数接收原常量值；`ACONST_NULL` 没有精确运行时类型，因此允许任意引用类型首参。
     * 后续参数按顺序匹配目标方法参数前缀，数量不能超过目标方法声明参数数。
     *
     * @param target 目标方法
     * @param constantType 原始常量类型
     * @param asmParamTypes handler 参数类型列表
     * @param nullConstant 当前常量是否为 `ACONST_NULL`
     * @throws IllegalArgumentException handler 首参、目标参数前缀数量或类型不兼容时抛出
     */
    private fun validateHandlerParameters(
        target: MethodNode,
        constantType: Type,
        asmParamTypes: Array<Type>,
        nullConstant: Boolean,
    ) {
        if (asmParamTypes.isEmpty()) {
            throw IllegalArgumentException(
                "ASM method ${asmMethod.name} first parameter must accept constant type $constantType, actual ${asmParamTypes.toList()}",
            )
        }

        val acceptsConstant =
            if (nullConstant) {
                asmParamTypes[0].isReferenceType()
            } else {
                isHandlerParameterCompatible(constantType, asmParamTypes[0])
            }
        if (!acceptsConstant) {
            throw IllegalArgumentException(
                "ASM method ${asmMethod.name} first parameter must accept constant type $constantType, actual ${asmParamTypes.toList()}",
            )
        }

        val targetParamTypes = Type.getArgumentTypes(target.desc)
        val requestedTargetParamCount = asmParamTypes.size - 1
        if (requestedTargetParamCount > targetParamTypes.size) {
            throw IllegalArgumentException(
                "ASM method ${asmMethod.name} requests $requestedTargetParamCount target parameter(s), " +
                    "but target method ${target.name}${target.desc} has only ${targetParamTypes.size}",
            )
        }

        for (index in 0 until requestedTargetParamCount) {
            val expected = targetParamTypes[index]
            val actual = asmParamTypes[index + 1]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalArgumentException(
                    "ASM method ${asmMethod.name} parameter #${index + 1} ($actual) " +
                        "must match target method ${target.name}${target.desc} parameter #$index ($expected)",
                )
            }
        }
    }

    /**
     * 解析常量加载指令对应的 handler 匹配类型。
     *
     * JVM 使用 `ICONST_0` 与 `ICONST_1` 同时承载布尔与整数常量。用户按 `true` / `false`
     * 文本匹配时，这里把对应指令视为 boolean 常量；其他情况交给 [BytecodeUtil.getConstantType]。
     *
     * @param insn 常量加载指令
     * @param requestedValue 用户声明的常量文本；为 `null` 时仅按字节码类型解析
     * @return handler 匹配时使用的常量类型；无法识别时返回 `null`
     */
    private fun resolveConstantType(
        insn: AbstractInsnNode,
        requestedValue: String?,
    ): Type? {
        if (requestedValue != null && isBooleanLiteral(requestedValue) && isBooleanConstantInsn(insn, requestedValue == "true")) {
            return Type.BOOLEAN_TYPE
        }
        return BytecodeUtil.getConstantType(insn)
    }

    /**
     * 判断 handler 返回类型是否可替换原常量类型。
     *
     * 基础类型必须精确匹配且不能为 `void`。引用类型允许 handler 返回常量类型的子类型，
     * 也允许声明为 `Any` / `Object`，后续会通过 `CHECKCAST` 恢复替换值的栈类型。
     *
     * @param constantType 原始常量类型
     * @param handlerReturnType handler 返回类型
     * @return handler 返回值可作为该常量替换值时返回 `true`
     */
    private fun isHandlerReturnCompatible(
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
        if (handlerReturnType.sort == Type.OBJECT &&
            (handlerReturnType.internalName == "java/lang/Object" || handlerReturnType.internalName == "kotlin/Any")
        ) {
            return true
        }
        return runCatching {
            val constantClass = loadReferenceClass(constantType)
            constantClass.isAssignableFrom(asmMethod.returnType)
        }.getOrDefault(false)
    }

    /**
     * 判断 ASM 类型是否属于对象或数组引用类型。
     *
     * @return 当前类型为对象或数组时返回 `true`
     */
    private fun Type.isReferenceType(): Boolean = sort == Type.OBJECT || sort == Type.ARRAY

    /**
     * 解析 handler 返回值在后续字节码中应表现出的替换类型。
     *
     * `ACONST_NULL` 没有自身引用类型，优先使用 handler 首参类型作为后续栈类型；
     * 原常量类型为泛化 `Object` 时使用 handler 返回类型，否则保持原常量类型。
     *
     * @param insn 原始常量加载指令
     * @param constantType 原始常量类型
     * @param handlerReturnType handler 返回类型
     * @return 替换调用结束后应保留在栈上的类型
     */
    private fun resolveReplacementType(
        insn: AbstractInsnNode,
        constantType: Type,
        handlerReturnType: Type,
    ): Type {
        if (isNullConstant(insn)) {
            val firstParamType = Type.getArgumentTypes(asmMethod).firstOrNull()
            if (firstParamType?.isReferenceType() == true) {
                return firstParamType
            }
        }
        if (constantType.sort == Type.OBJECT && constantType.internalName == "java/lang/Object") {
            return handlerReturnType
        }
        return constantType
    }

    /**
     * 在 handler 返回引用泛型类型时补充替换值转换。
     *
     * 当替换类型与 handler 返回类型不同且替换类型为引用类型时，插入 `CHECKCAST`，
     * 让后续字节码和栈图计算继续看到原常量语义上的引用类型。
     *
     * @param il 正在构建的指令列表
     * @param replacementType 替换后应暴露给后续字节码的类型
     */
    private fun addConstantCastIfNeeded(
        il: InsnList,
        replacementType: Type,
    ) {
        val handlerReturnType = Type.getReturnType(asmMethod)
        if (replacementType != handlerReturnType && replacementType.isReferenceType()) {
            il.add(TypeInsnNode(Opcodes.CHECKCAST, replacementType.internalName))
        }
    }

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
     * 判断用户声明的常量文本是否为布尔字面量。
     *
     * @param value 常量过滤文本
     * @return 文本为 `true` 或 `false` 时返回 `true`
     */
    private fun isBooleanLiteral(value: String): Boolean = value == "true" || value == "false"

    /**
     * 判断常量指令是否为 `null` 常量。
     *
     * @param insn 常量加载指令
     * @return 指令为 [Opcodes.ACONST_NULL] 时返回 `true`
     */
    private fun isNullConstant(insn: AbstractInsnNode): Boolean = insn.opcode == Opcodes.ACONST_NULL

    /**
     * 判断整数短常量指令是否表示指定布尔值。
     *
     * JVM 使用 `ICONST_0` 与 `ICONST_1` 承载 boolean 常量，只有用户按布尔文本过滤时才按此语义解释。
     *
     * @param insn 待检查的常量指令
     * @param value 期望布尔值
     * @return 指令与期望布尔值一致时返回 `true`
     */
    private fun isBooleanConstantInsn(
        insn: AbstractInsnNode,
        value: Boolean,
    ): Boolean =
        when (insn.opcode) {
            Opcodes.ICONST_0 -> !value
            Opcodes.ICONST_1 -> value
            else -> false
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
            InstructionUtil.loadParam(paramType, paramVarIndex).let { il.add(it) }
            paramVarIndex += paramType.size
        }
    }

    private fun loadAsmHandlerReceiver(
        il: InsnList,
        instanceType: Type,
    ) {
        if (isKotlinObject()) {
            il.add(
                FieldInsnNode(
                    Opcodes.GETSTATIC,
                    instanceType.internalName,
                    "INSTANCE",
                    "L${instanceType.internalName};",
                ),
            )
            return
        }

        val targetClassInternalName =
            asmInfo.targets.firstOrNull()?.replace('.', '/')
                ?: instanceType.internalName
        val singletonFieldName = "\$asmInstance\$${asmInfo.asmClass.simpleName}"
        val singletonFieldDesc = "L${instanceType.internalName};"
        val notNullLabel = LabelNode()
        val endLabel = LabelNode()

        il.add(FieldInsnNode(Opcodes.GETSTATIC, targetClassInternalName, singletonFieldName, singletonFieldDesc))
        il.add(InsnNode(Opcodes.DUP))
        il.add(JumpInsnNode(Opcodes.IFNONNULL, notNullLabel))
        il.add(InsnNode(Opcodes.POP))
        il.add(TypeInsnNode(Opcodes.NEW, instanceType.internalName))
        il.add(InsnNode(Opcodes.DUP))
        il.add(MethodInsnNode(Opcodes.INVOKESPECIAL, instanceType.internalName, "<init>", "()V", false))
        il.add(InsnNode(Opcodes.DUP))
        il.add(FieldInsnNode(Opcodes.PUTSTATIC, targetClassInternalName, singletonFieldName, singletonFieldDesc))
        il.add(JumpInsnNode(Opcodes.GOTO, endLabel))
        il.add(notNullLabel)
        il.add(endLabel)
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

    /**
     * 重新加载匹配到的常量值。
     *
     * `LDC` 与 `BIPUSH` / `SIPUSH` 会重新创建等价指令，其他短常量指令直接克隆原指令。
     *
     * @param il 指令列表
     * @param insn 原始常量加载指令
     * @param type 常量入栈类型；当前实现保留该参数用于调用方语义说明
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    private fun loadConstant(
        il: InsnList,
        insn: AbstractInsnNode,
        type: Type,
    ) {
        when (insn) {
            is LdcInsnNode -> {
                il.add(LdcInsnNode(insn.cst))
            }
            is IntInsnNode -> {
                when (insn.opcode) {
                    Opcodes.BIPUSH, Opcodes.SIPUSH -> {
                        il.add(IntInsnNode(insn.opcode, insn.operand))
                    }
                }
            }
            else -> {
                // 使用原始指令
                il.add(insn.clone(null))
            }
        }
    }

}
