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
import kim.der.asm.utils.transformer.InstructionUtil
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * INVOKE 注入器。
 *
 * 根据 [kim.der.asm.api.annotation.AsmInject.at] 定位普通方法调用、构造器调用或 `invokedynamic` 调用，
 * 并按 shift 语义在调用前、调用后或替换调用点插入 ASM 方法调用。
 * 当普通 [InjectionPoint.INVOKE_ASSIGN] 使用默认 [Shift.BEFORE] 时，会按调用完成后处理；
 * 需要调用前注入时应使用普通 [InjectionPoint.INVOKE]。
 * 当注解缺少目标调用签名时返回未修改。BEFORE/AFTER handler 可先接收原调用参数前缀，
 * 再继续接收目标方法参数前缀；`invokedynamic` 没有 receiver，handler 从动态调用点描述符的参数开始接收。
 * REPLACE handler 保持替换原调用的参数与返回值语义。
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
class InvokeInjector(
    method: Method,
    asmInfo: AsmInfo,
    private val injectionPoint: InjectionPoint = InjectionPoint.INVOKE,
) : AbstractAsmInjector(method, asmInfo) {
    /**
     * 被 `INVOKE` / `INVOKE_ASSIGN` 匹配到的调用点信息。
     *
     * @property insn 原始方法调用或 `invokedynamic` 指令
     * @property desc 调用点方法描述符
     * @property hasReceiver 调用栈中是否包含实例 receiver
     */
    private data class CallSite(
        val insn: AbstractInsnNode,
        val desc: String,
        val hasReceiver: Boolean,
    )

    /**
     * 在匹配的调用点附近注入 ASM 调用。
     *
     * @param target 目标方法
     * @return 至少命中一个调用点并插入指令时返回 `true`
     * @throws IllegalArgumentException 调用点签名无法解析时抛出
     * @throws RuntimeException 调用点参数、目标方法参数映射或字节码结构不合法时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun inject(target: MethodNode): Boolean = injectCount(target) > 0

    /**
     * 在目标方法中执行调用点注入并返回命中数量。
     *
     * 会解析 `@AsmInject.at.target` 为目标调用签名，并按 `Slice` 与 `ordinal` 限制候选调用点。
     * `INVOKE_ASSIGN` 的默认 [Shift.BEFORE] 会转为调用完成后的注入语义。
     *
     * @param target 目标方法
     * @return 实际插入或替换的调用点数量
     * @throws IllegalArgumentException 目标调用签名不完整时抛出
     * @throws IllegalStateException handler 参数、返回值或切片边界不兼容时抛出
     */
    override fun injectCount(target: MethodNode): Int {
        // 从 @AsmInject 注解中获取 @At 信息
        val injectAnnotation =
            asmMethod.getAnnotation(AsmInject::class.java)
                ?: return 0

        val at = injectAnnotation.at
        val shift =
            if (injectionPoint == InjectionPoint.INVOKE_ASSIGN && at.shift == Shift.BEFORE) {
                Shift.AFTER
            } else {
                at.shift
            }
        val targetMethodSignature = at.target

        if (targetMethodSignature.isEmpty()) {
            return 0
        }

        // 解析目标方法签名
        val (targetOwner, targetName, targetDesc) = parseTargetMethod(targetMethodSignature)

        if (targetName == null || targetDesc == null) {
            throw IllegalArgumentException(
                "Invalid target method signature: $targetMethodSignature " +
                    "(parsed: owner=$targetOwner, name=$targetName, desc=$targetDesc)",
            )
        }

        val instructions = target.instructions
        var injectionCount = 0
        val insns = instructions.toArray()
        val (sliceStartIndex, sliceEndIndex) = resolveSliceRange(insns, injectAnnotation.slice)

        // 查找所有匹配的调用点
        var matchedOrdinal = 0
        for ((index, insn) in insns.withIndex()) {
            if (index < sliceStartIndex || index >= sliceEndIndex) {
                continue
            }

            val callSite = matchCallSite(insn, targetOwner, targetName, targetDesc) ?: continue
            val currentOrdinal = matchedOrdinal++
            if (!matchesOrdinal(currentOrdinal, injectAnnotation.ordinal)) {
                continue
            }

            when (shift) {
                Shift.BEFORE -> {
                    injectBeforeCall(instructions, callSite, target)
                    injectionCount++
                }
                Shift.AFTER -> {
                    injectAfterCall(instructions, callSite, target)
                    injectionCount++
                }
                Shift.REPLACE -> {
                    replaceCall(instructions, callSite, target)
                    injectionCount++
                }
            }
        }

        return injectionCount
    }

    /**
     * 解析调用点候选扫描范围。
     *
     * `from` 边界命中后从下一条指令开始扫描，`to` 边界命中前结束扫描；
     * 任一边界找不到时返回空范围。
     *
     * @param insns 目标方法指令快照
     * @param slice 注解声明的切片范围
     * @return 左闭右开的候选扫描下标范围
     */
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

    /**
     * 判断切片边界是否声明了可匹配目标。
     *
     * @param at 切片边界注入点
     * @return 边界 `target` 非空时返回 `true`
     */
    private fun hasSliceBoundary(at: At): Boolean = at.target.isNotEmpty()

    /**
     * 构造空切片范围。
     *
     * @param insns 目标方法指令快照
     * @return 位于指令末尾的空范围
     */
    private fun emptySlice(insns: Array<AbstractInsnNode>): Pair<Int, Int> = insns.size to insns.size

    /**
     * 查找 `Slice` 边界调用点下标。
     *
     * 当前调用点注入只支持以 [InjectionPoint.INVOKE] 作为切片边界，
     * 边界可匹配普通方法调用、构造器调用或 `invokedynamic`。
     *
     * @param insns 目标方法指令快照
     * @param at 切片边界声明
     * @param startIndex 起始扫描下标
     * @return 边界命中的指令下标；未找到时返回 `null`
     * @throws IllegalArgumentException 边界不是 `INVOKE` 或目标签名不完整时抛出
     */
    private fun findSliceBoundaryIndex(
        insns: Array<AbstractInsnNode>,
        at: At,
        startIndex: Int,
    ): Int? {
        require(at.value == InjectionPoint.INVOKE) {
            "Only INVOKE slice boundaries are supported for @AsmInject(INVOKE/INVOKE_ASSIGN): ${at.value}"
        }

        val (boundaryOwner, boundaryName, boundaryDesc) = parseTargetMethod(at.target)
        if (boundaryName == null || boundaryDesc == null) {
            throw IllegalArgumentException(
                "Invalid slice boundary method signature: ${at.target} " +
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
     * 判断当前匹配序号是否满足 `ordinal` 过滤。
     *
     * 负数表示不限制序号，否则只允许指定的第 N 个候选命中。
     *
     * @param currentOrdinal 当前候选在匹配集合中的序号
     * @param requestedOrdinal 注解声明的目标序号
     * @return 当前候选应被注入时返回 `true`
     */
    private fun matchesOrdinal(
        currentOrdinal: Int,
        requestedOrdinal: Int,
    ): Boolean = requestedOrdinal < 0 || currentOrdinal == requestedOrdinal

    /**
     * 解析目标方法签名。
     *
     * 支持 `owner.name(desc)`、`owner/name(desc)` 与仅方法名加描述符形式；
     * owner 会统一转换为 JVM internal name。
     *
     * @param signature `At.target` 中声明的方法目标
     * @return owner、方法名与描述符；未声明的部分返回 `null`
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
        val desc: String
        val lastDot = ownerAndName.lastIndexOf('.')
        val lastSlash = ownerAndName.lastIndexOf('/')
        val separator = if (lastDot > lastSlash) lastDot else lastSlash

        return if (separator > 0) {
            // 包含类名
            val owner = ownerAndName.substring(0, separator).replace('.', '/')
            val methodName = ownerAndName.substring(separator + 1)
            desc = signature.substring(parenIndex)
            Triple(owner, methodName, desc)
        } else {
            // 只有方法名
            val methodName = ownerAndName
            desc = signature.substring(parenIndex)
            Triple(null, methodName, desc)
        }
    }

    /**
     * 判断普通方法调用是否匹配目标方法约束。
     *
     * @param insn 候选普通方法调用指令
     * @param targetOwner 目标 owner；为 `null` 时不限制 owner
     * @param targetName 目标方法名
     * @param targetDesc 目标方法描述符
     * @return 候选调用满足目标约束时返回 `true`
     */
    private fun matchesTargetMethod(
        insn: MethodInsnNode,
        targetOwner: String?,
        targetName: String,
        targetDesc: String,
    ): Boolean {
        // 检查所有者
        if (targetOwner != null && insn.owner != targetOwner) {
            return false
        }

        // 检查方法名
        if (insn.name != targetName) {
            return false
        }

        // 检查描述符
        if (insn.desc != targetDesc) {
            return false
        }

        return true
    }

    /**
     * 将候选指令匹配为调用点。
     *
     * 普通方法调用会保留是否携带 receiver 的信息，`invokedynamic` 永远不携带 receiver。
     *
     * @param insn 候选指令
     * @param targetOwner 目标 owner；为 `null` 时不限制 owner
     * @param targetName 目标方法名或动态调用名
     * @param targetDesc 目标方法描述符
     * @return 匹配的调用点信息；不匹配时返回 `null`
     */
    private fun matchCallSite(
        insn: AbstractInsnNode,
        targetOwner: String?,
        targetName: String,
        targetDesc: String,
    ): CallSite? =
        when (insn) {
            is MethodInsnNode ->
                if (matchesTargetMethod(insn, targetOwner, targetName, targetDesc)) {
                    CallSite(insn = insn, desc = insn.desc, hasReceiver = insn.opcode != Opcodes.INVOKESTATIC)
                } else {
                    null
                }
            is InvokeDynamicInsnNode ->
                if (matchesTargetInvokeDynamic(insn, targetOwner, targetName, targetDesc)) {
                    CallSite(insn = insn, desc = insn.desc, hasReceiver = false)
                } else {
                    null
                }
            else -> null
        }

    /**
     * 判断 `invokedynamic` 调用是否匹配目标方法约束。
     *
     * owner 约束会匹配 bootstrap method owner，名称约束可匹配动态调用名或 bootstrap method 名。
     *
     * @param insn 候选 `invokedynamic` 调用指令
     * @param targetOwner 目标 owner；为 `null` 时不限制 bootstrap owner
     * @param targetName 目标动态调用名或 bootstrap method 名
     * @param targetDesc 目标动态调用描述符
     * @return 候选动态调用满足目标约束时返回 `true`
     */
    private fun matchesTargetInvokeDynamic(
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

    /**
     * 在原调用点前插入 handler 调用。
     *
     * 会先把调用点参数和实例 receiver 暂存到局部变量，调用 handler 后再恢复原调用栈，
     * 保证原方法调用仍按原始参数执行。
     *
     * @param instructions 目标方法指令列表
     * @param callSite 被命中的调用点
     * @param targetMethod 目标方法
     */
    private fun injectBeforeCall(
        instructions: InsnList,
        callSite: CallSite,
        targetMethod: MethodNode,
    ) {
        val il = InsnList()

        // 保存方法调用的参数到局部变量
        val paramTypes = Type.getArgumentTypes(callSite.desc)
        val savedParams = mutableListOf<Int>()
        var nextVarIndex = allocateVariablesForParams(targetMethod, paramTypes, callSite.hasReceiver)

        // 保存所有参数（从右到左）
        for (i in paramTypes.indices.reversed()) {
            val paramType = paramTypes[i]

            nextVarIndex -= paramType.size
            saveParameter(il, paramType, nextVarIndex)
            savedParams.add(0, nextVarIndex)
        }

        // 如果是实例方法调用，保存实例引用
        var savedInstanceIndex: Int? = null
        if (callSite.hasReceiver) {
            nextVarIndex -= 1
            savedInstanceIndex = nextVarIndex
            il.add(VarInsnNode(Opcodes.ASTORE, savedInstanceIndex))
        }

        val callbackVarIndex = createCallbackInfoIfNeeded(il, targetMethod, paramTypes, savedParams, savedInstanceIndex)

        // 生成调用 ASM 方法的指令，参数来自已保存的调用点参数和目标方法参数。
        generateCallSiteHandlerCall(
            il,
            targetMethod,
            paramTypes,
            savedParams,
            callbackVarIndex,
        )
        dropUnusedHandlerReturnValue(il)

        // 恢复参数（从左到右）
        if (savedInstanceIndex != null) {
            il.add(VarInsnNode(Opcodes.ALOAD, savedInstanceIndex))
        }

        for (savedIndex in savedParams) {
            val paramIndex = savedParams.indexOf(savedIndex)
            val paramType = paramTypes[paramIndex]
            InstructionUtil.loadParam(paramType, savedIndex).let { il.add(it) }
        }

        // 在调用前插入
        instructions.insertBefore(callSite.insn, il)
    }

    /**
     * 在原调用点后插入 handler 调用。
     *
     * 对有返回值的调用，会先暂存返回值，执行 handler 后再恢复返回值到栈顶；
     * 对无返回值的调用，会直接在调用点下一条指令前插入 handler。
     *
     * @param instructions 目标方法指令列表
     * @param callSite 被命中的调用点
     * @param targetMethod 目标方法
     */
    private fun injectAfterCall(
        instructions: InsnList,
        callSite: CallSite,
        targetMethod: MethodNode,
    ) {
        // 查找调用后的位置
        val nextInsn = callSite.insn.next
        val paramTypes = Type.getArgumentTypes(callSite.desc)
        val savedParams = mutableListOf<Int>()
        var nextVarIndex = allocateVariablesForParams(targetMethod, paramTypes, callSite.hasReceiver)
        val beforeCall = InsnList()

        for (i in paramTypes.indices.reversed()) {
            val paramType = paramTypes[i]

            nextVarIndex -= paramType.size
            saveParameter(beforeCall, paramType, nextVarIndex)
            savedParams.add(0, nextVarIndex)
        }

        var savedInstanceIndex: Int? = null
        if (callSite.hasReceiver) {
            nextVarIndex -= 1
            savedInstanceIndex = nextVarIndex
            beforeCall.add(VarInsnNode(Opcodes.ASTORE, savedInstanceIndex))
        }

        if (savedInstanceIndex != null) {
            beforeCall.add(VarInsnNode(Opcodes.ALOAD, savedInstanceIndex))
        }

        for (savedIndex in savedParams) {
            val paramIndex = savedParams.indexOf(savedIndex)
            val paramType = paramTypes[paramIndex]
            InstructionUtil.loadParam(paramType, savedIndex).let { beforeCall.add(it) }
        }

        if (beforeCall.size() > 0) {
            instructions.insertBefore(callSite.insn, beforeCall)
        }

        // 跳过调用本身（如果有返回值，跳过返回值）
        val returnType = Type.getReturnType(callSite.desc)
        if (returnType != Type.VOID_TYPE) {
            // 返回值在栈顶，需要保存
            val il = InsnList()
            val returnVarIndex = allocateVariableAfterSavedCallState(targetMethod, paramTypes, savedParams, savedInstanceIndex)

            // 保存返回值
            saveReturnValue(il, returnType, returnVarIndex)

            val callbackVarIndex =
                createCallbackInfoIfNeeded(
                    il,
                    targetMethod,
                    paramTypes + returnType,
                    savedParams + returnVarIndex,
                    savedInstanceIndex,
                )

            // 生成调用 ASM 方法的指令。
            generateCallSiteHandlerCall(
                il,
                targetMethod,
                paramTypes,
                savedParams,
                callbackVarIndex,
            )
            dropUnusedHandlerReturnValue(il)

            // 恢复返回值
            loadReturnValue(il, returnType, returnVarIndex)

            instructions.insertBefore(nextInsn, il)
        } else {
            // 无返回值，直接在调用后插入
            val il = InsnList()
            val callbackVarIndex = createCallbackInfoIfNeeded(il, targetMethod, paramTypes, savedParams, savedInstanceIndex)
            generateCallSiteHandlerCall(
                il,
                targetMethod,
                paramTypes,
                savedParams,
                callbackVarIndex,
            )
            dropUnusedHandlerReturnValue(il)
            instructions.insertBefore(nextInsn, il)
        }
    }

    /**
     * 用 handler 调用替换原调用点。
     *
     * 会暂存原调用参数和 receiver，校验 handler 返回值可替代原调用结果，
     * 再移除原始调用指令。
     *
     * @param instructions 目标方法指令列表
     * @param callSite 被替换的调用点
     * @param targetMethod 目标方法
     * @throws IllegalStateException handler 返回值不能替代原调用结果时抛出
     */
    private fun replaceCall(
        instructions: InsnList,
        callSite: CallSite,
        targetMethod: MethodNode,
    ) {
        val il = InsnList()

        // 保存参数
        val paramTypes = Type.getArgumentTypes(callSite.desc)
        val savedParams = mutableListOf<Int>()
        var nextVarIndex = allocateVariablesForParams(targetMethod, paramTypes, callSite.hasReceiver)

        for (i in paramTypes.indices.reversed()) {
            val paramType = paramTypes[i]

            nextVarIndex -= paramType.size
            saveParameter(il, paramType, nextVarIndex)
            savedParams.add(0, nextVarIndex)
        }

        var savedInstanceIndex: Int? = null
        if (callSite.hasReceiver) {
            nextVarIndex -= 1
            savedInstanceIndex = nextVarIndex
            il.add(VarInsnNode(Opcodes.ASTORE, savedInstanceIndex))
        }

        val callbackVarIndex = createCallbackInfoIfNeeded(il, targetMethod, paramTypes, savedParams, savedInstanceIndex)

        // 调用 ASM 方法替换原调用
        validateReplaceSignature(callSite.desc)
        generateCallSiteHandlerCall(
            il,
            targetMethod,
            paramTypes,
            savedParams,
            callbackVarIndex,
        )

        // 处理返回值类型转换
        val originalReturnType = Type.getReturnType(callSite.desc)
        val asmReturnType = Type.getReturnType(asmMethod)

        if (asmReturnType != originalReturnType) {
            if (asmReturnType != Type.VOID_TYPE && originalReturnType == Type.VOID_TYPE) {
                // ASM 返回了值，但原方法返回 void，弹出
                il.add(InsnNode(if (asmReturnType.size == 2) Opcodes.POP2 else Opcodes.POP))
            } else if (originalReturnType.sort == Type.OBJECT || originalReturnType.sort == Type.ARRAY) {
                il.add(TypeInsnNode(Opcodes.CHECKCAST, originalReturnType.internalName))
            }
        }

        // 替换原始调用
        instructions.insertBefore(callSite.insn, il)
        instructions.remove(callSite.insn)
    }

    /**
     * 丢弃 BEFORE / AFTER handler 未被原调用点消费的返回值。
     *
     * 普通调用点前后注入不会替换原调用结果，因此 handler 非 `void` 返回值需要从栈顶弹出。
     *
     * @param il 正在构造的注入指令列表
     */
    private fun dropUnusedHandlerReturnValue(il: InsnList) {
        val returnType = Type.getReturnType(asmMethod)
        if (returnType == Type.VOID_TYPE) {
            return
        }

        il.add(InsnNode(if (returnType.size == 2) Opcodes.POP2 else Opcodes.POP))
    }

    /**
     * 生成调用当前 ASM handler 的指令。
     *
     * 该方法会按 handler 形态压入 owner、可选 [CallbackInfo]、调用点参数前缀与目标方法参数前缀，
     * 最后追加 `INVOKESTATIC` 或 `INVOKEVIRTUAL`。
     *
     * @param il 正在构造的注入指令列表
     * @param targetMethod 目标方法
     * @param callParamTypes 原调用点参数类型
     * @param savedParamIndexes 已暂存的原调用点参数槽位
     * @param callbackVarIndex 已暂存的 [CallbackInfo] 槽位；为 `null` 时 handler 不接收 callback
     * @throws IllegalStateException handler 请求的调用点参数或目标方法参数不兼容时抛出
     */
    private fun generateCallSiteHandlerCall(
        il: InsnList,
        targetMethod: MethodNode,
        callParamTypes: Array<Type>,
        savedParamIndexes: List<Int>,
        callbackVarIndex: Int?,
    ) {
        val instanceType = Type.getType(asmInfo.asmClass)
        val useStaticCall = Modifier.isStatic(asmMethod.modifiers)

        if (!useStaticCall) {
            loadAsmHandlerReceiver(il, instanceType)
        }

        if (callbackVarIndex != null) {
            il.add(VarInsnNode(Opcodes.ALOAD, callbackVarIndex))
        }

        loadCallSiteHandlerArguments(il, targetMethod, callParamTypes, savedParamIndexes, callbackVarIndex != null)

        il.add(
            MethodInsnNode(
                if (useStaticCall) Opcodes.INVOKESTATIC else Opcodes.INVOKEVIRTUAL,
                instanceType.internalName,
                asmMethod.name,
                Type.getMethodDescriptor(asmMethod),
                false,
            ),
        )
    }

    /**
     * 为实例 ASM handler 压入 receiver。
     *
     * Kotlin `object` handler 直接读取 `INSTANCE`；普通类 handler 通过目标类上的
     * `$asmInstance$...` 单例字段懒加载实例。
     *
     * @param il 正在构造的注入指令列表
     * @param instanceType ASM handler 所在类类型
     */
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

    /**
     * 按 handler 签名加载调用点参数与目标方法参数。
     *
     * 参数顺序为可选 [CallbackInfo] 后接原调用参数前缀，再接目标方法开头的参数前缀。
     *
     * @param il 正在构造的注入指令列表
     * @param targetMethod 目标方法
     * @param callParamTypes 原调用点参数类型
     * @param savedParamIndexes 已暂存的原调用点参数槽位
     * @param skipCallbackInfo handler 首参是否已由 callback 占用
     * @throws IllegalStateException handler 请求参数数量过多或类型不兼容时抛出
     */
    private fun loadCallSiteHandlerArguments(
        il: InsnList,
        targetMethod: MethodNode,
        callParamTypes: Array<Type>,
        savedParamIndexes: List<Int>,
        skipCallbackInfo: Boolean,
    ) {
        val asmParamTypes = Type.getArgumentTypes(asmMethod)
        val handlerParamStart = if (skipCallbackInfo) 1 else 0
        val requestedHandlerParamCount = asmParamTypes.size - handlerParamStart
        val requestedCallParamCount = minOf(requestedHandlerParamCount, callParamTypes.size)

        val targetParamTypes = Type.getArgumentTypes(targetMethod.desc)
        val requestedTargetParamCount = requestedHandlerParamCount - requestedCallParamCount
        if (requestedTargetParamCount > targetParamTypes.size) {
            throw IllegalStateException(
                "Invoke handler ${asmMethod.name} requests $requestedTargetParamCount target parameter(s), " +
                    "but target method ${targetMethod.name}${targetMethod.desc} has only ${targetParamTypes.size}",
            )
        }

        for (index in 0 until requestedCallParamCount) {
            val expected = callParamTypes[index]
            val actual = asmParamTypes[handlerParamStart + index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalStateException(
                    "Invoke handler ${asmMethod.name} parameter #${handlerParamStart + index} mismatch: " +
                        "expected call argument $expected, actual $actual",
                )
            }

            InstructionUtil.loadParam(expected, savedParamIndexes[index]).let { il.add(it) }
        }

        var targetVarIndex = if ((targetMethod.access and Opcodes.ACC_STATIC) != 0) 0 else 1
        for (index in 0 until requestedTargetParamCount) {
            val expected = targetParamTypes[index]
            val actual = asmParamTypes[handlerParamStart + requestedCallParamCount + index]
            if (!isHandlerParameterCompatible(expected, actual)) {
                throw IllegalStateException(
                    "Invoke handler ${asmMethod.name} target parameter #$index mismatch: " +
                        "expected $expected, actual $actual",
                )
            }

            InstructionUtil.loadParam(expected, targetVarIndex).let { il.add(it) }
            targetVarIndex += expected.size
        }
    }

    /**
     * 判断 handler 参数类型是否能接收原栈值类型。
     *
     * 基本类型要求完全一致；引用类型允许 handler 声明为更宽的父类型，
     * 其中 `java.lang.Object` 与 `kotlin.Any` 作为通用引用参数特殊放行。
     *
     * @param expected 原栈值类型
     * @param actual handler 声明的参数类型
     * @return handler 参数可安全接收原值时返回 `true`
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
     * 判断 ASM 类型是否为 JVM 引用类型。
     *
     * @return 当前类型是对象或数组类型时返回 `true`
     */
    private fun Type.isReferenceType(): Boolean = sort == Type.OBJECT || sort == Type.ARRAY

    /**
     * 按 ASM 引用类型加载对应的运行时 [Class]。
     *
     * 数组类型使用 descriptor 转 Java 类名，对象类型使用 [Type.getClassName]；
     * 类加载器优先取当前 Mixin 类加载器。
     *
     * @param type 待加载的对象或数组类型
     * @return 对应的运行时类
     * @throws ClassNotFoundException 类型无法由当前类加载器解析时抛出
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
     * 按类型把栈顶调用参数暂存到局部变量槽位。
     *
     * 根据 ASM 类型选择 `ISTORE`、`LSTORE`、`FSTORE`、`DSTORE` 或 `ASTORE`。
     *
     * @param il 正在构造的注入指令列表
     * @param paramType 栈顶参数类型
     * @param varIndex 局部变量槽位
     */
    private fun saveParameter(
        il: InsnList,
        paramType: Type,
        varIndex: Int,
    ) {
        when (paramType.sort) {
            Type.BOOLEAN, Type.BYTE, Type.SHORT, Type.INT, Type.CHAR -> {
                il.add(VarInsnNode(Opcodes.ISTORE, varIndex))
            }
            Type.LONG -> {
                il.add(VarInsnNode(Opcodes.LSTORE, varIndex))
            }
            Type.FLOAT -> {
                il.add(VarInsnNode(Opcodes.FSTORE, varIndex))
            }
            Type.DOUBLE -> {
                il.add(VarInsnNode(Opcodes.DSTORE, varIndex))
            }
            else -> {
                il.add(VarInsnNode(Opcodes.ASTORE, varIndex))
            }
        }
    }

    /**
     * 把调用点返回值暂存到局部变量槽位。
     *
     * @param il 正在构造的注入指令列表
     * @param returnType 调用点返回类型
     * @param varIndex 局部变量槽位
     */
    private fun saveReturnValue(
        il: InsnList,
        returnType: Type,
        varIndex: Int,
    ) {
        saveParameter(il, returnType, varIndex)
    }

    /**
     * 从局部变量槽位恢复调用点返回值。
     *
     * @param il 正在构造的注入指令列表
     * @param returnType 调用点返回类型
     * @param varIndex 局部变量槽位
     */
    private fun loadReturnValue(
        il: InsnList,
        returnType: Type,
        varIndex: Int,
    ) {
        val loadInsn = InstructionUtil.loadParam(returnType, varIndex)
        il.add(loadInsn)
    }

    /**
     * 校验 REPLACE handler 返回值是否能替代原调用结果。
     *
     * 参数兼容性由 [loadCallSiteHandlerArguments] 在生成 handler 调用参数时校验。
     *
     * @param callDesc 被替换调用点的方法描述符
     * @throws IllegalStateException handler 返回值不能替代原调用结果时抛出
     */
    private fun validateReplaceSignature(callDesc: String) {
        val originalReturnType = Type.getReturnType(callDesc)
        val asmReturnType = Type.getReturnType(asmMethod)
        if (!isReplaceReturnCompatible(originalReturnType, asmReturnType)) {
            throw IllegalStateException(
                "Invoke REPLACE handler ${asmMethod.name} return type mismatch: original $originalReturnType, handler $asmReturnType",
            )
        }
    }

    /**
     * 在需要时创建并暂存 [CallbackInfo]。
     *
     * callback 槽位会避开目标方法已有局部变量以及已经暂存的调用点参数和 receiver。
     *
     * @param il 正在构造的注入指令列表
     * @param targetMethod 目标方法
     * @param savedParamTypes 已暂存值类型
     * @param savedParamIndexes 已暂存值槽位
     * @param savedInstanceIndex 已暂存 receiver 槽位；静态或 `invokedynamic` 调用为 `null`
     * @return callback 暂存槽位；handler 不需要 callback 时返回 `null`
     */
    private fun createCallbackInfoIfNeeded(
        il: InsnList,
        targetMethod: MethodNode,
        savedParamTypes: Array<Type>,
        savedParamIndexes: List<Int>,
        savedInstanceIndex: Int?,
    ): Int? {
        if (!AsmMethodCallGenerator.needsCallbackInfo(asmMethod)) {
            return null
        }

        AsmMethodCallGenerator.generateCallbackInfoCreation(il)
        val callbackVarIndex = allocateVariableAfterSavedCallState(targetMethod, savedParamTypes, savedParamIndexes, savedInstanceIndex)
        il.add(VarInsnNode(Opcodes.ASTORE, callbackVarIndex))
        return callbackVarIndex
    }

    /**
     * 计算位于已暂存调用状态之后的临时变量槽位。
     *
     * 用于为返回值或 [CallbackInfo] 分配不会覆盖原调用参数、receiver 的槽位。
     *
     * @param targetMethod 目标方法
     * @param savedParamTypes 已暂存值类型
     * @param savedParamIndexes 已暂存值槽位
     * @param savedInstanceIndex 已暂存 receiver 槽位；没有 receiver 时为 `null`
     * @return 可用于额外临时值的槽位
     */
    private fun allocateVariableAfterSavedCallState(
        targetMethod: MethodNode,
        savedParamTypes: Array<Type>,
        savedParamIndexes: List<Int>,
        savedInstanceIndex: Int?,
    ): Int {
        var nextIndex = findLocalEnd(targetMethod)

        savedParamIndexes.forEachIndexed { index, savedIndex ->
            val paramType = savedParamTypes[index]
            nextIndex = maxOf(nextIndex, savedIndex + paramType.size)
        }

        if (savedInstanceIndex != null) {
            nextIndex = maxOf(nextIndex, savedInstanceIndex + 1)
        }

        return nextIndex
    }

    /**
     * 计算目标方法当前局部变量使用范围的末尾槽位。
     *
     * 结果会覆盖方法参数、调试局部变量表和现有局部变量读写指令已使用的最高槽位。
     *
     * @param targetMethod 目标方法
     * @return 下一个可用局部变量槽位
     */
    private fun findLocalEnd(targetMethod: MethodNode): Int {
        val isStatic = (targetMethod.access and Opcodes.ACC_STATIC) != 0
        var maxIndex = if (isStatic) 0 else 1

        val methodParamTypes = Type.getArgumentTypes(targetMethod.desc)
        for (paramType in methodParamTypes) {
            maxIndex += paramType.size
        }

        for (localVar in targetMethod.localVariables) {
            val size = Type.getType(localVar.desc).size
            maxIndex = maxOf(maxIndex, localVar.index + size)
        }

        for (insn in targetMethod.instructions.toArray()) {
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

    /**
     * 判断 REPLACE handler 返回值是否能替代原调用返回值。
     *
     * 原调用返回 `void` 时允许 handler 返回任意值并在替换后弹出；非 `void` 基本类型要求完全一致，
     * 引用类型允许后续通过 `CHECKCAST` 转回原返回类型。
     *
     * @param original 原调用返回类型
     * @param replacement handler 返回类型
     * @return handler 返回值可替代原调用结果时返回 `true`
     */
    private fun isReplaceReturnCompatible(
        original: Type,
        replacement: Type,
    ): Boolean {
        if (original == Type.VOID_TYPE) return true
        if (replacement == Type.VOID_TYPE) return false
        if (original == replacement) return true
        return (original.sort == Type.OBJECT || original.sort == Type.ARRAY) &&
            (replacement.sort == Type.OBJECT || replacement.sort == Type.ARRAY)
    }

    /**
     * 为调用点参数和可选 receiver 预留局部变量槽位。
     *
     * 返回值是临时区域末尾，用于后续按从右到左的栈顺序递减分配每个参数。
     *
     * @param targetMethod 目标方法
     * @param paramTypes 调用点参数类型
     * @param reserveInstanceSlot 是否需要为实例 receiver 预留槽位
     * @return 临时参数区域的末尾槽位
     */
    private fun allocateVariablesForParams(
        targetMethod: MethodNode,
        paramTypes: Array<Type>,
        reserveInstanceSlot: Boolean,
    ): Int {
        var neededSlots = if (reserveInstanceSlot) 1 else 0
        for (paramType in paramTypes) {
            neededSlots += paramType.size
        }

        return findLocalEnd(targetMethod) + neededSlots
    }

    /**
     * 为调用点返回值分配局部变量槽位。
     *
     * @param targetMethod 目标方法
     * @param returnType 调用点返回类型
     * @return 可用于暂存返回值的槽位
     */
    private fun allocateVariableForReturn(
        targetMethod: MethodNode,
        returnType: Type,
    ): Int = findLocalEnd(targetMethod)

}
