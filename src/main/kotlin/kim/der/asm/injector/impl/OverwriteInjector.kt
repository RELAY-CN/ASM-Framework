/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.injector.impl

import kim.der.asm.api.annotation.Copy
import kim.der.asm.api.annotation.Shadow
import kim.der.asm.data.AsmInfo
import kim.der.asm.injector.AbstractAsmInjector
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.SourceInterpreter
import java.lang.reflect.Method

/**
 * Overwrite 注入器。
 *
 * 从 ASM 类字节码中提取当前 ASM 方法体，清空目标方法后复制方法体、异常处理和局部变量信息。
 * 复制完成后会适配参数/返回值差异，并重写 Shadow/Copy 引用到目标类。
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
class OverwriteInjector(
    method: Method,
    asmInfo: AsmInfo,
) : AbstractAsmInjector(method, asmInfo) {
    /**
     * 用 ASM 方法体覆盖目标方法。
     *
     * @param target 目标方法
     * @return 覆盖成功时返回 `true`
     * @throws IllegalStateException 无法提取 ASM 方法体，或返回/参数适配不受支持时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun inject(target: MethodNode): Boolean {
        // 获取 ASM 方法的字节码
        val asmClassBytes = getAsmClassBytes()
        val asmMethodNode = extractAsmMethodNode(asmClassBytes)

        if (asmMethodNode == null) {
            throw IllegalStateException("Cannot extract method ${asmMethod.name} from asm class ${asmInfo.asmClass.name}")
        }

        // 移除 abstract 和 native 修饰符（如果目标方法是抽象的或 native 的）
        // 因为我们要提供具体实现，所以不能是抽象的或 native 的
        target.access = target.access and Opcodes.ACC_ABSTRACT.inv() and Opcodes.ACC_NATIVE.inv()

        // 清空目标方法
        target.instructions.clear()
        target.localVariables.clear()
        target.tryCatchBlocks.clear()
        target.parameters = null

        // 复制方法体
        copyMethodBody(asmMethodNode, target)

        // 适配参数和返回值
        adaptMethodSignature(asmMethodNode, target)

        // 转换 Shadow 字段和方法调用
        val targetClassName = asmInfo.targets.firstOrNull()?.replace('.', '/')
        if (targetClassName != null) {
            transformShadowReferences(target, targetClassName)
        }
        recalculateMaxLocals(target)
        adaptKotlinObjectSelfReceivers(target)

        // 重新计算 maxLocals，确保包含所有使用的局部变量
        recalculateMaxLocals(target)

        return true
    }

    /**
     * 获取 ASM 类的字节码
     */
    private fun getAsmClassBytes(): ByteArray {
        val className = asmInfo.asmClass.name.replace('.', '/')
        val resource = className + ".class"
        val inputStream =
            asmInfo.asmClass.classLoader?.getResourceAsStream(resource)
                ?: ClassLoader.getSystemClassLoader().getResourceAsStream(resource)
                ?: throw IllegalStateException("Cannot find class file for ${asmInfo.asmClass.name}")

        return inputStream.use { it.readBytes() }
    }

    /**
     * 从类字节码中提取方法节点
     */
    private fun extractAsmMethodNode(classBytes: ByteArray): MethodNode? {
        val reader = ClassReader(classBytes)
        val classNode = ClassNode()
        reader.accept(classNode, ClassReader.EXPAND_FRAMES)

        // 查找匹配的方法
        for (method in classNode.methods) {
            if (method.name == asmMethod.name && method.desc == Type.getMethodDescriptor(asmMethod)) {
                return method
            }
        }

        return null
    }

    /**
     * 复制方法体
     * 使用临时 MethodNode 作为中间层，确保标签正确映射
     */
    private fun copyMethodBody(
        source: MethodNode,
        target: MethodNode,
    ) {
        // 创建标签映射
        val labelMap = mutableMapOf<LabelNode, LabelNode>()

        // 先遍历一次，创建所有标签的映射
        source.instructions.forEach { insn ->
            if (insn is LabelNode) {
                labelMap[insn] = LabelNode()
            }
        }

        // 复制指令 - 逐个复制并检查 null
        source.instructions.forEach { insn ->
            if (insn != null) {
                val cloned =
                    try {
                        insn.clone(labelMap)
                    } catch (e: Exception) {
                        // 如果 clone 失败，使用手动克隆
                        cloneInstructionSafely(insn, labelMap)
                    }

                if (cloned != null) {
                    target.instructions.add(cloned)
                } else {
                    // 如果仍然为 null，尝试手动创建
                    val manualCloned = cloneInstructionSafely(insn, labelMap)
                    if (manualCloned != null) {
                        target.instructions.add(manualCloned)
                    }
                }
            }
        }

        // 复制局部变量
        for (localVar in source.localVariables) {
            val newStart =
                labelMap[localVar.start] ?: LabelNode().apply {
                    labelMap[localVar.start] = this
                }
            val newEnd =
                labelMap[localVar.end] ?: LabelNode().apply {
                    labelMap[localVar.end] = this
                }
            val newLocalVar =
                LocalVariableNode(
                    localVar.name,
                    localVar.desc,
                    localVar.signature,
                    newStart,
                    newEnd,
                    localVar.index,
                )
            // 调整局部变量索引以匹配目标方法的参数布局
            val isValid = adjustLocalVariableIndex(newLocalVar, source, target)
            // 只添加有效的局部变量
            if (isValid) {
                target.localVariables.add(newLocalVar)
            }
        }

        // 复制异常处理块
        for (tryCatch in source.tryCatchBlocks) {
            val newStart =
                labelMap[tryCatch.start] ?: LabelNode().apply {
                    labelMap[tryCatch.start] = this
                }
            val newEnd =
                labelMap[tryCatch.end] ?: LabelNode().apply {
                    labelMap[tryCatch.end] = this
                }
            val newHandler =
                labelMap[tryCatch.handler] ?: LabelNode().apply {
                    labelMap[tryCatch.handler] = this
                }
            val newTryCatch =
                TryCatchBlockNode(
                    newStart,
                    newEnd,
                    newHandler,
                    tryCatch.type,
                )
            target.tryCatchBlocks.add(newTryCatch)
        }

        // 复制参数
        if (source.parameters != null) {
            target.parameters =
                source.parameters
                    .map { param ->
                        ParameterNode(param.name, param.access)
                    }.toMutableList()
        }

        // 复制其他属性
        target.maxStack = source.maxStack
        // maxLocals 会在复制完成后重新计算，确保包含所有局部变量
    }

    /**
     * 计算参数占用的局部变量槽数
     * 使用 Type.getSize() 来准确计算，与 ASM 框架保持一致
     */
    private fun calculateParamSlots(method: MethodNode): Int {
        val isStatic = (method.access and Opcodes.ACC_STATIC) != 0
        var slots = if (isStatic) 0 else 1

        val paramTypes = Type.getArgumentTypes(method.desc)
        for (paramType in paramTypes) {
            slots += paramType.size // 使用 getSize() 方法，long/double 返回 2，其他返回 1
        }

        return slots
    }

    /**
     * 调整局部变量索引
     * @return true 如果索引有效，false 如果索引无效应该跳过
     */
    private fun adjustLocalVariableIndex(
        localVar: LocalVariableNode,
        source: MethodNode,
        target: MethodNode,
    ): Boolean {
        val sourceParamTypes = Type.getArgumentTypes(source.desc)
        val targetParamTypes = Type.getArgumentTypes(target.desc)

        val sourceIsStatic = (source.access and Opcodes.ACC_STATIC) != 0
        val targetIsStatic = (target.access and Opcodes.ACC_STATIC) != 0

        val sourceFirstVar = if (sourceIsStatic) 0 else 1
        val targetFirstVar = if (targetIsStatic) 0 else 1

        // 计算源方法和目标方法的参数占用的局部变量数
        val sourceParamSlots = calculateParamSlots(source)
        val targetParamSlots = calculateParamSlots(target)

        // 检查原始索引是否有效
        if (localVar.index < 0 || localVar.index >= 65535) {
            return false
        }

        // 如果局部变量在参数区域，需要调整
        if (localVar.index < sourceParamSlots) {
            // 如果源方法是实例方法，局部变量 0 是 this，不应该映射到目标方法
            if (!sourceIsStatic && localVar.index == 0) {
                // this 参数不应该出现在目标方法的局部变量表中
                return false
            }

            // 局部变量在参数区域，使用映射表来确保正确映射
            val indexMap = buildParameterIndexMap(source, target, sourceParamTypes, targetParamTypes)
            val newIndex =
                indexMap[localVar.index] ?: run {
                    // 如果映射表中没有（比如 this 参数），跳过
                    return false
                }

            // 确保索引在有效范围内（0-65534）
            if (newIndex >= 0 && newIndex < 65535) {
                localVar.index = newIndex
                return true
            } else {
                return false
            }
        } else {
            // 局部变量在参数区域外，需要加上参数差异
            val paramDiff = targetParamSlots - sourceParamSlots
            val newIndex = localVar.index + paramDiff
            // 确保索引在有效范围内（0-65534）
            if (newIndex >= 0 && newIndex < 65535) {
                localVar.index = newIndex
                return true
            } else {
                return false
            }
        }
    }

    /**
     * 适配方法签名
     */
    private fun adaptMethodSignature(
        source: MethodNode,
        target: MethodNode,
    ) {
        val sourceParamTypes = Type.getArgumentTypes(source.desc)
        val targetParamTypes = Type.getArgumentTypes(target.desc)
        val sourceReturnType = Type.getReturnType(source.desc)
        val targetReturnType = Type.getReturnType(target.desc)

        val sourceIsStatic = (source.access and Opcodes.ACC_STATIC) != 0
        val targetIsStatic = (target.access and Opcodes.ACC_STATIC) != 0

        // 如果返回类型不同，需要适配返回指令
        if (sourceReturnType != targetReturnType) {
            adaptReturnInstructions(target, sourceReturnType, targetReturnType)
        }

        // 如果参数类型不同，或者静态/实例状态不同，需要适配参数加载指令
        // 注意：即使参数类型相同，如果一个是实例方法一个是静态方法，也需要调整局部变量索引
        if (!sourceParamTypes.contentEquals(targetParamTypes) || sourceIsStatic != targetIsStatic) {
            adaptParameterInstructions(target, source, sourceParamTypes, targetParamTypes)
        }
    }

    /**
     * 适配返回指令
     */
    private fun adaptReturnInstructions(
        target: MethodNode,
        sourceReturnType: Type,
        targetReturnType: Type,
    ) {
        if (!isReturnAdaptationSupported(sourceReturnType, targetReturnType)) {
            throw IllegalStateException("Cannot adapt return type from $sourceReturnType to $targetReturnType")
        }

        val instructions = target.instructions
        val insns = instructions.toArray()

        for (insn in insns) {
            if (insn is InsnNode && insn.opcode in RETURN_OPS) {
                val opcode = insn.opcode
                val sourceOpcode = getReturnOpcode(sourceReturnType)
                val targetOpcode = getReturnOpcode(targetReturnType)

                if (opcode == sourceOpcode && opcode != targetOpcode) {
                    val il = InsnList()
                    val insnIndex = insns.indexOf(insn)
                    instructions.remove(insn)

                    if (targetReturnType == Type.VOID_TYPE) {
                        il.add(InsnNode(if (sourceReturnType.size == 2) Opcodes.POP2 else Opcodes.POP))
                        il.add(InsnNode(Opcodes.RETURN))
                    } else if (sourceReturnType == Type.VOID_TYPE) {
                        loadDefaultValue(il, targetReturnType)
                        il.add(InsnNode(targetOpcode))
                    } else {
                        il.add(TypeInsnNode(Opcodes.CHECKCAST, targetReturnType.internalName))
                        il.add(InsnNode(targetOpcode))
                    }

                    if (insnIndex >= 0 && insnIndex + 1 < insns.size) {
                        instructions.insertBefore(insns[insnIndex + 1], il)
                    } else {
                        instructions.add(il)
                    }
                }
            }
        }
    }

    private fun isReturnAdaptationSupported(
        sourceReturnType: Type,
        targetReturnType: Type,
    ): Boolean {
        if (sourceReturnType == targetReturnType) return true
        if (targetReturnType == Type.VOID_TYPE) return true
        if (sourceReturnType == Type.VOID_TYPE) return isDefaultReturnSupported(targetReturnType)
        return sourceReturnType.sort >= Type.ARRAY && targetReturnType.sort >= Type.ARRAY
    }

    private fun isDefaultReturnSupported(type: Type): Boolean =
        when (type.sort) {
            Type.BOOLEAN,
            Type.BYTE,
            Type.SHORT,
            Type.INT,
            Type.LONG,
            Type.FLOAT,
            Type.DOUBLE,
            Type.CHAR,
            Type.OBJECT,
            Type.ARRAY -> true
            else -> false
        }
    /**
     * 适配参数指令
     * 调整所有局部变量索引以匹配目标方法的参数布局
     */
    private fun adaptParameterInstructions(
        target: MethodNode,
        source: MethodNode,
        sourceParamTypes: Array<Type>,
        targetParamTypes: Array<Type>,
    ) {
        val sourceIsStatic = (source.access and Opcodes.ACC_STATIC) != 0
        val targetIsStatic = (target.access and Opcodes.ACC_STATIC) != 0

        // 计算源方法和目标方法的参数占用的局部变量数
        val sourceParamSlots = calculateParamSlots(source)
        val targetParamSlots = calculateParamSlots(target)

        // 计算参数差异
        val paramDiff = targetParamSlots - sourceParamSlots

        // 构建完整的索引映射（包括参数区域和非参数区域）
        val indexMap = buildParameterIndexMap(source, target, sourceParamTypes, targetParamTypes)

        // 遍历所有指令，调整局部变量索引
        val instructions = target.instructions
        val insns = instructions.toArray()
        val toRemove = mutableListOf<AbstractInsnNode>()
        val toInsert = mutableMapOf<AbstractInsnNode, AbstractInsnNode>() // 要插入的指令

        // 第一遍：调整所有局部变量索引
        for (insn in insns) {
            val oldIndex: Int
            val isThisParam: Boolean

            when (insn) {
                is VarInsnNode -> {
                    oldIndex = insn.`var`
                    isThisParam = !sourceIsStatic && targetIsStatic && oldIndex == 0

                    // 如果源方法是实例方法而目标方法是静态方法，且访问的是 this（索引 0），需要处理
                    if (isThisParam) {
                        // 标记需要移除的指令
                        when (insn.opcode) {
                            Opcodes.ALOAD -> {
                                if (isKotlinObject()) {
                                    val instanceType = Type.getType(asmInfo.asmClass)
                                    instructions[insn] =
                                        FieldInsnNode(
                                            Opcodes.GETSTATIC,
                                            instanceType.internalName,
                                            "INSTANCE",
                                            "L${instanceType.internalName};",
                                        )
                                } else {
                                    // 如果是加载指令（aload_0），移除它
                                    // 注意：这可能会导致栈不平衡，但 this 在静态方法中不应该被使用
                                    toRemove.add(insn)
                                }
                            }
                            Opcodes.ASTORE -> {
                                // 如果是存储指令（astore_0），移除它并用 POP 替换
                                toRemove.add(insn)
                                toInsert[insn] = InsnNode(Opcodes.POP)
                            }
                            Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD -> {
                                // 其他加载指令不应该出现在 this 上，移除它
                                toRemove.add(insn)
                            }
                            Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE -> {
                                // 其他存储指令不应该出现在 this 上，移除它并用相应的 POP 替换
                                toRemove.add(insn)
                                val popOpcode =
                                    when (insn.opcode) {
                                        Opcodes.LSTORE, Opcodes.DSTORE -> Opcodes.POP2
                                        else -> Opcodes.POP
                                    }
                                toInsert[insn] = InsnNode(popOpcode)
                            }
                        }
                        // 注意：这里不 continue，因为我们已经标记了要移除的指令
                        // 但为了安全，我们仍然需要确保索引调整逻辑不会处理这个指令
                        continue
                    }
                }
                is IincInsnNode -> {
                    oldIndex = insn.`var`
                    isThisParam = !sourceIsStatic && targetIsStatic && oldIndex == 0

                    // 如果是对 this 参数的自增操作，移除它（this 不应该被修改）
                    if (isThisParam) {
                        toRemove.add(insn)
                        continue
                    }
                }
                else -> continue
            }

            // 计算新索引
            val newIndex =
                if (oldIndex < sourceParamSlots) {
                    // 参数区域：使用映射表
                    val mapped = indexMap[oldIndex]
                    if (mapped == null) {
                        // 如果映射表中没有（比如 this 参数），这是一个严重错误
                        // 但我们已经处理了 this 参数的情况（oldIndex == 0），所以这不应该发生
                        // 如果确实发生了，说明映射表构建有问题
                        throw IllegalStateException(
                            "Local variable index $oldIndex not found in parameter index map. " +
                                "Source is static: $sourceIsStatic, Target is static: $targetIsStatic, " +
                                "Source param slots: $sourceParamSlots, Target param slots: $targetParamSlots",
                        )
                    }
                    mapped
                } else {
                    // 非参数区域：加上参数差异
                    val adjusted = oldIndex + paramDiff
                    // 确保索引在有效范围内
                    if (adjusted >= 0 && adjusted < 65535) {
                        adjusted
                    } else {
                        // 如果调整后的索引无效，这是一个严重错误
                        throw IllegalStateException(
                            "Invalid local variable index adjustment: old=$oldIndex, paramDiff=$paramDiff, adjusted=$adjusted",
                        )
                    }
                }

            // 更新索引（必须更新，确保所有指令的索引都一致）
            if (newIndex >= 0 && newIndex < 65535) {
                when (insn) {
                    is VarInsnNode -> insn.`var` = newIndex
                    is IincInsnNode -> insn.`var` = newIndex
                }
            } else {
                // 如果新索引无效，这是一个严重错误
                throw IllegalStateException("Invalid local variable index: $newIndex (old: $oldIndex, paramDiff: $paramDiff)")
            }
        }

        // 第二遍：插入 POP 指令（在移除之前）
        for ((insn, popInsn) in toInsert) {
            instructions.insert(insn, popInsn)
        }

        // 第三遍：移除标记的指令
        for (insn in toRemove) {
            instructions.remove(insn)
        }
    }

    /**
     * 构建参数索引映射
     */
    private fun buildParameterIndexMap(
        source: MethodNode,
        target: MethodNode,
        sourceParamTypes: Array<Type>,
        targetParamTypes: Array<Type>,
    ): Map<Int, Int> {
        val map = mutableMapOf<Int, Int>()

        val sourceIsStatic = (source.access and Opcodes.ACC_STATIC) != 0
        val targetIsStatic = (target.access and Opcodes.ACC_STATIC) != 0

        var sourceIndex = if (sourceIsStatic) 0 else 1
        var targetIndex = if (targetIsStatic) 0 else 1

        val minParams = minOf(sourceParamTypes.size, targetParamTypes.size)

        // 映射相同位置的参数
        // 注意：源方法的 this 参数（索引 0，如果是实例方法）不应该被映射
        for (i in 0 until minParams) {
            val sourceType = sourceParamTypes[i]
            val targetType = targetParamTypes[i]

            // 只有当源索引不是 this 参数时才映射
            if (sourceIndex != 0 || sourceIsStatic) {
                map[sourceIndex] = targetIndex
            }

            // 使用 getSize() 方法，与 ASM 框架保持一致
            sourceIndex += sourceType.size
            targetIndex += targetType.size
        }

        return map
    }

    /**
     * 获取返回操作码
     */
    private fun getReturnOpcode(returnType: Type): Int =
        when (returnType.sort) {
            Type.VOID -> Opcodes.RETURN
            Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.IRETURN
            Type.LONG -> Opcodes.LRETURN
            Type.FLOAT -> Opcodes.FRETURN
            Type.DOUBLE -> Opcodes.DRETURN
            else -> Opcodes.ARETURN
        }

    /**
     * 加载默认值
     */
    private fun loadDefaultValue(
        il: InsnList,
        type: Type,
    ) {
        when (type.sort) {
            Type.BOOLEAN, Type.BYTE, Type.SHORT, Type.INT, Type.CHAR -> {
                il.add(InsnNode(Opcodes.ICONST_0))
            }
            Type.LONG -> {
                il.add(InsnNode(Opcodes.LCONST_0))
            }
            Type.FLOAT -> {
                il.add(InsnNode(Opcodes.FCONST_0))
            }
            Type.DOUBLE -> {
                il.add(InsnNode(Opcodes.DCONST_0))
            }
            else -> {
                il.add(InsnNode(Opcodes.ACONST_NULL))
            }
        }
    }

    /**
     * 安全地克隆指令（如果 clone 方法失败时的备用方案）
     */
    private fun cloneInstructionSafely(
        insn: AbstractInsnNode,
        labelMap: MutableMap<LabelNode, LabelNode>,
    ): AbstractInsnNode? =
        when (insn) {
            is InsnNode -> InsnNode(insn.opcode)
            is IntInsnNode -> IntInsnNode(insn.opcode, insn.operand)
            is VarInsnNode -> VarInsnNode(insn.opcode, insn.`var`)
            is TypeInsnNode -> TypeInsnNode(insn.opcode, insn.desc)
            is FieldInsnNode -> FieldInsnNode(insn.opcode, insn.owner, insn.name, insn.desc)
            is MethodInsnNode -> MethodInsnNode(insn.opcode, insn.owner, insn.name, insn.desc, insn.itf)
            is InvokeDynamicInsnNode -> InvokeDynamicInsnNode(insn.name, insn.desc, insn.bsm, *insn.bsmArgs)
            is JumpInsnNode -> {
                val newLabel = labelMap.getOrPut(insn.label) { LabelNode() }
                JumpInsnNode(insn.opcode, newLabel)
            }
            is LabelNode -> {
                // LabelNode 已经在前面处理了，这里不应该再出现
                labelMap[insn] ?: LabelNode()
            }
            is LdcInsnNode -> LdcInsnNode(insn.cst)
            is IincInsnNode -> IincInsnNode(insn.`var`, insn.incr)
            is TableSwitchInsnNode -> {
                val newLabels = insn.labels.map { labelMap.getOrPut(it) { LabelNode() } }
                val newDflt = labelMap.getOrPut(insn.dflt) { LabelNode() }
                TableSwitchInsnNode(insn.min, insn.max, newDflt, *newLabels.toTypedArray())
            }
            is LookupSwitchInsnNode -> {
                val newLabels = insn.labels.map { labelMap.getOrPut(it) { LabelNode() } }
                val newDflt = labelMap.getOrPut(insn.dflt) { LabelNode() }
                LookupSwitchInsnNode(newDflt, insn.keys.toIntArray(), newLabels.toTypedArray())
            }
            is MultiANewArrayInsnNode -> MultiANewArrayInsnNode(insn.desc, insn.dims)
            is FrameNode -> {
                // Frame 节点需要特殊处理
                when (insn.type) {
                    Opcodes.F_NEW, Opcodes.F_FULL, Opcodes.F_APPEND, Opcodes.F_CHOP, Opcodes.F_SAME1 -> {
                        FrameNode(insn.type, insn.local?.size ?: 0, null, insn.stack?.size ?: 0, null)
                    }
                    else -> FrameNode(insn.type, 0, null, 0, null)
                }
            }
            is LineNumberNode -> {
                val newLabel = labelMap.getOrPut(insn.start) { LabelNode() }
                LineNumberNode(insn.line, newLabel)
            }
            else -> null
        }

    /**
     * 重新计算 maxLocals
     * 确保包含所有使用的局部变量索引
     */
    private fun recalculateMaxLocals(target: MethodNode) {
        val isStatic = (target.access and Opcodes.ACC_STATIC) != 0
        var maxIndex = if (isStatic) 0 else 1

        // 计算参数占用的局部变量数（使用 getSize() 方法）
        val paramTypes = Type.getArgumentTypes(target.desc)
        for (paramType in paramTypes) {
            maxIndex += paramType.size
        }

        // 遍历所有局部变量表项，找出最大的索引
        for (localVar in target.localVariables) {
            // 使用 Type 来准确判断大小
            val varType =
                try {
                    Type.getType(localVar.desc)
                } catch (e: Exception) {
                    // 如果描述符无效，假设是单槽
                    null
                }
            val varSize = varType?.size ?: 1
            val endIndex = localVar.index + varSize
            // 确保索引在有效范围内
            if (endIndex < 65535) {
                maxIndex = maxOf(maxIndex, endIndex)
            }
        }

        // 遍历所有指令，找出使用的最大局部变量索引
        for (insn in target.instructions.toArray()) {
            if (insn is VarInsnNode) {
                val needsDoubleSlot =
                    when (insn.opcode) {
                        Opcodes.LLOAD, Opcodes.LSTORE, Opcodes.DLOAD, Opcodes.DSTORE -> true
                        else -> false
                    }
                val endIndex = insn.`var` + (if (needsDoubleSlot) 2 else 1)
                // 确保索引在有效范围内
                if (endIndex < 65535) {
                    maxIndex = maxOf(maxIndex, endIndex)
                }
            }
        }

        // 确保 maxLocals 至少为 1（即使是静态方法也可能有局部变量）
        // 并且不超过有效范围
        target.maxLocals = maxOf(1, minOf(maxIndex, 65535))
    }

    /**
     * Kotlin object 的非 @JvmStatic 方法体内，ALOAD 0 是 object receiver，而不是目标类 this。
     *
     * Shadow/Copy 调用已经在前一步改写到目标类，剩余 owner 仍为 ASM 类的实例调用需要继续通过 INSTANCE 调用。
     */
    private fun adaptKotlinObjectSelfReceivers(target: MethodNode) {
        if (!isKotlinObject()) {
            return
        }

        val asmClassName = Type.getType(asmInfo.asmClass).internalName
        val instructions = target.instructions
        val insns = instructions.toArray()
        val frames =
            Analyzer(SourceInterpreter()).analyze(asmClassName, target)

        for (index in insns.indices) {
            val insn = insns[index]
            if (insn !is MethodInsnNode ||
                insn.owner != asmClassName ||
                insn.opcode == Opcodes.INVOKESTATIC ||
                insn.name == "<init>"
            ) {
                continue
            }

            val frame = frames[index] ?: continue
            val argumentSlots = Type.getArgumentTypes(insn.desc).sumOf { it.size }
            val receiverIndex = frame.stackSize - argumentSlots - 1
            if (receiverIndex < 0) {
                continue
            }

            val receiverSource = frame.getStack(receiverIndex).insns.singleOrNull()
            if (receiverSource is VarInsnNode &&
                receiverSource.opcode == Opcodes.ALOAD &&
                receiverSource.`var` == 0
            ) {
                instructions[receiverSource] =
                    FieldInsnNode(
                        Opcodes.GETSTATIC,
                        asmClassName,
                        "INSTANCE",
                        "L$asmClassName;",
                    )
            }
        }
    }

    /**
     * 转换 Shadow 字段和方法引用
     * 将访问 Shadow 字段的指令转换为访问目标类字段的指令
     * 将调用 Shadow 方法的指令转换为调用目标类方法的指令
     */
    private fun transformShadowReferences(
        target: MethodNode,
        targetClassName: String,
    ) {
        val asmClassName = Type.getType(asmInfo.asmClass).internalName

        // 构建 Shadow 字段映射：ASM 字段名 -> 目标字段名
        val shadowFieldMap = mutableMapOf<String, String>()
        for (field in asmInfo.asmClass.declaredFields) {
            val shadowAnnotation = field.getAnnotation(Shadow::class.java)
            if (shadowAnnotation != null) {
                val fieldName = field.name
                val targetFieldName = resolveShadowTargetName(shadowAnnotation.method, fieldName)

                shadowFieldMap[fieldName] = targetFieldName
            }
        }

        // 构建 Shadow 方法映射：ASM 方法签名 -> 目标方法名
        val shadowMethodMap = mutableMapOf<String, String>()
        val shadowMethodNames = mutableSetOf<String>()
        // 构建 Copy 方法映射：ASM 方法名 -> 目标方法名（通过 @Copy 复制的方法）
        val copyMethodMap = mutableMapOf<String, String>()

        for (method in asmInfo.asmClass.declaredMethods) {
            val shadowAnnotation = method.getAnnotation(Shadow::class.java)
            if (shadowAnnotation != null) {
                val methodName = method.name
                val methodDesc = Type.getMethodDescriptor(method)
                val targetMethodName = resolveShadowTargetName(shadowAnnotation.method, methodName)

                shadowMethodMap["$methodName$methodDesc"] = targetMethodName
                shadowMethodNames.add(methodName)
            }

            // 检查是否是 @Copy 方法
            val copyAnnotation = method.getAnnotation(Copy::class.java)
            if (copyAnnotation != null) {
                val methodName = method.name
                val methodDesc = Type.getMethodDescriptor(method)

                // 确定目标方法名
                val targetMethodName =
                    if (copyAnnotation.method.isEmpty()) {
                        // 如果 method 为空，使用 ASM 方法名
                        methodName
                    } else {
                        // 解析方法签名
                        val (name, _) = parseMethodSignature(copyAnnotation.method)
                        name
                    }

                // 使用方法名和描述符作为键，确保唯一性
                copyMethodMap["$methodName$methodDesc"] = targetMethodName
            }
        }

        // 遍历指令，转换 Shadow 字段和方法引用
        val instructions = target.instructions
        val insns = instructions.toArray()
        for (insn in insns) {
            when (insn) {
                is FieldInsnNode -> {
                    // 如果是访问 ASM 类的字段，且该字段是 Shadow 字段，转换为目标类字段
                    if (insn.owner == asmClassName && shadowFieldMap.containsKey(insn.name)) {
                        val targetFieldName = shadowFieldMap[insn.name]!!
                        // 保持字段描述符不变，只更改 owner 和 name
                        insn.owner = targetClassName
                        insn.name = targetFieldName
                    }
                }
                is MethodInsnNode -> {
                    // 如果是调用 ASM 类的方法
                    if (insn.owner == asmClassName) {
                        // 检查是否是 Shadow 方法
                        val shadowMethodKey = "${insn.name}${insn.desc}"
                        if (shadowMethodMap.containsKey(shadowMethodKey)) {
                            val targetMethodName = shadowMethodMap[shadowMethodKey]!!
                            insn.owner = targetClassName
                            insn.name = targetMethodName
                        } else if (shadowMethodNames.contains(insn.name)) {
                            throw IllegalStateException(
                                "Shadow method call ${insn.name}${insn.desc} does not match declared shadow method signature",
                            )
                        }
                        // 检查是否是 @Copy 方法（通过方法名和描述符匹配）
                        else {
                            val methodKey = "${insn.name}${insn.desc}"
                            if (copyMethodMap.containsKey(methodKey)) {
                                val targetMethodName = copyMethodMap[methodKey]!!
                                // 将调用转换为目标类方法
                                insn.owner = targetClassName
                                insn.name = targetMethodName
                            }
                        }
                    }
                }
            }
        }
    }

    private fun resolveShadowTargetName(
        declaredName: String,
        memberName: String,
    ): String =
        when {
            declaredName.isEmpty() -> memberName
            declaredName.startsWith(Shadow.prefix) -> declaredName.substring(Shadow.prefix.length)
            else -> declaredName
        }

    /**
     * 解析方法签名
     * 格式: methodName(Ljava/lang/String;)V 或 methodName
     */
    private fun parseMethodSignature(signature: String): Pair<String, String> {
        if (signature.isEmpty()) {
            return Pair("", "")
        }

        val parenIndex = signature.indexOf('(')
        return if (parenIndex > 0) {
            val methodName = signature.substring(0, parenIndex)
            val desc = signature.substring(parenIndex)
            Pair(methodName, desc)
        } else {
            Pair(signature, "")
        }
    }

    companion object {
        private val RETURN_OPS =
            setOf(
                Opcodes.RETURN,
                Opcodes.IRETURN,
                Opcodes.LRETURN,
                Opcodes.FRETURN,
                Opcodes.DRETURN,
                Opcodes.ARETURN,
            )
    }
}

