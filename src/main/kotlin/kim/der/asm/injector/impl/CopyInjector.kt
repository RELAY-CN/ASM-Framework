/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.injector.impl

import kim.der.asm.api.annotation.Copy
import kim.der.asm.api.annotation.Shadow
import kim.der.asm.data.AsmInfo
import kim.der.asm.injector.AbstractAsmInjector
import kim.der.asm.utils.transformer.InstructionUtil
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.lang.reflect.Method

/**
 * Copy 注入器
 * 将 ASM 方法复制到目标类中作为新方法
 *
 * @author Dr (dr@der.kim)
 */
class CopyInjector(
    method: Method,
    asmInfo: AsmInfo,
) : AbstractAsmInjector(method, asmInfo) {
    private var createdMethod: MethodNode? = null

    override fun inject(target: MethodNode): Boolean {
        // 对于 Copy，我们创建新方法而不是修改目标方法
        // 实际的方法创建在 createMethod 中完成
        createdMethod = createMethod(target)
        return true
    }

    /**
     * 创建新方法节点
     * @param targetMethodSignature 目标方法的签名（用于确定新方法的名称和描述符）
     * @return 新创建的方法节点
     */
    fun createMethod(targetMethodSignature: MethodNode): MethodNode {
        // 获取 ASM 方法的字节码
        val asmClassBytes = getAsmClassBytes()
        val asmMethodNode = extractAsmMethodNode(asmClassBytes)

        if (asmMethodNode == null) {
            throw IllegalStateException("Cannot extract method ${asmMethod.name} from asm class ${asmInfo.asmClass.name}")
        }

        // 创建新方法节点（使用目标方法的签名，但内容来自 ASM 方法）
        val newMethod =
            MethodNode(
                targetMethodSignature.access,
                targetMethodSignature.name,
                targetMethodSignature.desc,
                targetMethodSignature.signature,
                targetMethodSignature.exceptions?.toTypedArray(),
            )

        // 移除 abstract 和 native 修饰符
        newMethod.access = newMethod.access and Opcodes.ACC_ABSTRACT.inv() and Opcodes.ACC_NATIVE.inv()

        // 复制方法体
        copyMethodBody(asmMethodNode, newMethod)

        // 适配参数和返回值
        adaptMethodSignature(asmMethodNode, newMethod)

        // 处理 Kotlin object 兼容性
        adaptKotlinObjectCalls(asmMethodNode, newMethod)

        // 转换 Shadow 字段和方法调用
        val targetClassName = asmInfo.targets.firstOrNull()?.replace('.', '/')
        if (targetClassName != null) {
            transformShadowReferences(newMethod, targetClassName)
        }

        // 重新计算 maxLocals
        recalculateMaxLocals(newMethod)

        return newMethod
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
     */
    private fun calculateParamSlots(method: MethodNode): Int {
        val isStatic = (method.access and Opcodes.ACC_STATIC) != 0
        var slots = if (isStatic) 0 else 1

        val paramTypes = Type.getArgumentTypes(method.desc)
        for (paramType in paramTypes) {
            slots += paramType.size
        }

        return slots
    }

    /**
     * 调整局部变量索引
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
        val instructions = target.instructions
        val insns = instructions.toArray()

        for (insn in insns) {
            if (insn is InsnNode && insn.opcode in RETURN_OPS) {
                val opcode = insn.opcode
                val sourceOpcode = getReturnOpcode(sourceReturnType)
                val targetOpcode = getReturnOpcode(targetReturnType)

                if (opcode == sourceOpcode && opcode != targetOpcode) {
                    // 需要转换返回值
                    val il = InsnList()

                    // 如果需要从非 void 转换为 void
                    if (targetReturnType == Type.VOID_TYPE) {
                        instructions.remove(insn)
                        il.add(InsnNode(Opcodes.POP))
                        il.add(InsnNode(Opcodes.RETURN))
                        val insnIndex = insns.indexOf(insn)
                        if (insnIndex >= 0 && insnIndex + 1 < insns.size) {
                            instructions.insertBefore(insns[insnIndex + 1], il)
                        } else {
                            instructions.add(il)
                        }
                    } else if (sourceReturnType == Type.VOID_TYPE) {
                        // 需要从 void 转换为非 void，添加默认返回值
                        instructions.remove(insn)
                        loadDefaultValue(il, targetReturnType)
                        il.add(InsnNode(targetOpcode))
                        instructions.add(il)
                    } else {
                        // 需要类型转换
                        val insnIndex = insns.indexOf(insn)
                        if (insnIndex >= 0) {
                            instructions.remove(insn)
                            val unboxList = InstructionUtil.unbox(sourceReturnType)
                            for (unboxInsn in unboxList) {
                                il.add(unboxInsn)
                            }
                            loadDefaultValue(il, targetReturnType)
                            il.add(InsnNode(targetOpcode))
                            if (insnIndex + 1 < insns.size) {
                                instructions.insertBefore(insns[insnIndex + 1], il)
                            } else {
                                instructions.add(il)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 适配参数指令
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
        val toInsert = mutableMapOf<AbstractInsnNode, AbstractInsnNode>()

        // 第一遍：调整所有局部变量索引
        for (insn in insns) {
            val oldIndex: Int
            val isThisParam: Boolean

            when (insn) {
                is VarInsnNode -> {
                    oldIndex = insn.`var`
                    isThisParam = !sourceIsStatic && targetIsStatic && oldIndex == 0

                    if (isThisParam) {
                        when (insn.opcode) {
                            Opcodes.ALOAD -> {
                                toRemove.add(insn)
                            }
                            Opcodes.ASTORE -> {
                                toRemove.add(insn)
                                toInsert[insn] = InsnNode(Opcodes.POP)
                            }
                            else -> {
                                toRemove.add(insn)
                            }
                        }
                        continue
                    }
                }
                is IincInsnNode -> {
                    oldIndex = insn.`var`
                    isThisParam = !sourceIsStatic && targetIsStatic && oldIndex == 0

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
                    val mapped = indexMap[oldIndex]
                    if (mapped == null) {
                        throw IllegalStateException(
                            "Local variable index $oldIndex not found in parameter index map. " +
                                "Source is static: $sourceIsStatic, Target is static: $targetIsStatic, " +
                                "Source param slots: $sourceParamSlots, Target param slots: $targetParamSlots",
                        )
                    }
                    mapped
                } else {
                    val adjusted = oldIndex + paramDiff
                    if (adjusted >= 0 && adjusted < 65535) {
                        adjusted
                    } else {
                        throw IllegalStateException(
                            "Invalid local variable index adjustment: old=$oldIndex, paramDiff=$paramDiff, adjusted=$adjusted",
                        )
                    }
                }

            // 更新索引
            if (newIndex >= 0 && newIndex < 65535) {
                when (insn) {
                    is VarInsnNode -> insn.`var` = newIndex
                    is IincInsnNode -> insn.`var` = newIndex
                }
            } else {
                throw IllegalStateException("Invalid local variable index: $newIndex (old: $oldIndex, paramDiff: $paramDiff)")
            }
        }

        // 第二遍：插入 POP 指令
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
        for (i in 0 until minParams) {
            if (sourceIndex != 0 || sourceIsStatic) {
                map[sourceIndex] = targetIndex
            }

            sourceIndex += sourceParamTypes[i].size
            targetIndex += targetParamTypes[i].size
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
     * 安全地克隆指令
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
            is LabelNode -> labelMap[insn] ?: LabelNode()
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
            is FrameNode -> FrameNode(insn.type, 0, null, 0, null)
            is LineNumberNode -> {
                val newLabel = labelMap.getOrPut(insn.start) { LabelNode() }
                LineNumberNode(insn.line, newLabel)
            }
            else -> null
        }

    /**
     * 适配 Kotlin object 调用
     */
    private fun adaptKotlinObjectCalls(
        source: MethodNode,
        target: MethodNode,
    ) {
        val isKotlinObject = isKotlinObject()
        val targetIsStatic = (target.access and Opcodes.ACC_STATIC) != 0
        val sourceIsStatic = (source.access and Opcodes.ACC_STATIC) != 0

        if (isKotlinObject && targetIsStatic && !sourceIsStatic) {
            convertInstanceCallsToStatic(target)
        }
    }

    /**
     * 将方法体中的实例调用转换为静态调用
     */
    private fun convertInstanceCallsToStatic(target: MethodNode) {
        val asmClassName = Type.getType(asmInfo.asmClass).internalName
        val instructions = target.instructions
        val insns = instructions.toArray()
        val toRemove = mutableSetOf<AbstractInsnNode>()
        val toConvert = mutableSetOf<MethodInsnNode>()

        // 遍历指令，查找 GETSTATIC INSTANCE 后跟 INVOKEVIRTUAL 的模式
        for (i in insns.indices) {
            val insn = insns[i]

            if (insn is FieldInsnNode &&
                insn.opcode == Opcodes.GETSTATIC &&
                insn.owner == asmClassName &&
                insn.name == "INSTANCE"
            ) {
                var stackDepth = 1
                var foundCall = false

                for (j in (i + 1) until insns.size) {
                    val nextInsn = insns[j]

                    if (j > i + 1) {
                        val prevInsn = insns[j - 1]
                        val delta = getStackDelta(prevInsn)
                        stackDepth += delta
                    }

                    if (nextInsn is MethodInsnNode &&
                        nextInsn.owner == asmClassName &&
                        (nextInsn.opcode == Opcodes.INVOKEVIRTUAL || nextInsn.opcode == Opcodes.INVOKESPECIAL) &&
                        nextInsn.name != "<init>"
                    ) {
                        val paramCount = Type.getArgumentTypes(nextInsn.desc).size
                        val totalNeeded = paramCount + 1

                        if (stackDepth == totalNeeded) {
                            toRemove.add(insn)
                            toConvert.add(nextInsn)
                            foundCall = true
                            break
                        }
                    }

                    if (stackDepth <= 0) {
                        break
                    }

                    if (j - i > 100) {
                        break
                    }
                }
            }
        }

        // 转换所有标记的方法调用
        for (methodCall in toConvert) {
            methodCall.opcode = Opcodes.INVOKESTATIC
        }

        // 移除所有标记的 INSTANCE 加载指令
        for (insn in toRemove) {
            instructions.remove(insn)
        }
    }

    /**
     * 计算指令对栈深度的影响
     */
    private fun getStackDelta(insn: AbstractInsnNode): Int =
        when (insn) {
            is VarInsnNode -> {
                when (insn.opcode) {
                    Opcodes.ALOAD, Opcodes.ILOAD, Opcodes.FLOAD -> 1
                    Opcodes.LLOAD, Opcodes.DLOAD -> 2
                    Opcodes.ASTORE, Opcodes.ISTORE, Opcodes.FSTORE -> -1
                    Opcodes.LSTORE, Opcodes.DSTORE -> -2
                    else -> 0
                }
            }
            is FieldInsnNode -> {
                when (insn.opcode) {
                    Opcodes.GETFIELD -> 0
                    Opcodes.GETSTATIC -> 1
                    Opcodes.PUTFIELD -> -2
                    Opcodes.PUTSTATIC -> -1
                    else -> 0
                }
            }
            is MethodInsnNode -> {
                val returnType = Type.getReturnType(insn.desc)
                val argCount = Type.getArgumentTypes(insn.desc).size
                val isStatic = insn.opcode == Opcodes.INVOKESTATIC
                val consumed = argCount + (if (isStatic) 0 else 1)
                val produced =
                    if (returnType == Type.VOID_TYPE) {
                        0
                    } else {
                        if (returnType.sort == Type.LONG || returnType.sort == Type.DOUBLE) 2 else 1
                    }
                produced - consumed
            }
            is InsnNode -> {
                when (insn.opcode) {
                    Opcodes.POP -> -1
                    Opcodes.POP2 -> -2
                    Opcodes.DUP -> 1
                    Opcodes.DUP2 -> 2
                    Opcodes.SWAP -> 0
                    Opcodes.ACONST_NULL, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2,
                    Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5, Opcodes.ICONST_M1,
                    -> 1
                    Opcodes.LCONST_0, Opcodes.LCONST_1, Opcodes.DCONST_0, Opcodes.DCONST_1,
                    Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2,
                    -> 2
                    else -> 0
                }
            }
            is LdcInsnNode -> {
                val cst = insn.cst
                when (cst) {
                    is Long, is Double -> 2
                    else -> 1
                }
            }
            is IntInsnNode -> {
                if (insn.opcode == Opcodes.BIPUSH || insn.opcode == Opcodes.SIPUSH) 1 else 0
            }
            else -> 0
        }

    /**
     * 转换 Shadow 字段和方法引用
     */
    private fun transformShadowReferences(
        target: MethodNode,
        targetClassName: String,
    ) {
        val asmClassName = Type.getType(asmInfo.asmClass).internalName

        // 构建 Shadow 字段映射
        val shadowFieldMap = mutableMapOf<String, String>()
        for (field in asmInfo.asmClass.declaredFields) {
            val shadowAnnotation = field.getAnnotation(Shadow::class.java)
            if (shadowAnnotation != null) {
                val fieldName = field.name
                val method = shadowAnnotation.method

                val targetFieldName =
                    if (method.startsWith(Shadow.prefix)) {
                        method.substring(Shadow.prefix.length)
                    } else {
                        fieldName
                    }

                shadowFieldMap[fieldName] = targetFieldName
            }
        }

        // 构建 Shadow 方法映射
        val shadowMethodMap = mutableMapOf<String, String>()
        // 构建 Copy 方法映射：ASM 方法名 -> 目标方法名（通过 @Copy 复制的方法）
        val copyMethodMap = mutableMapOf<String, String>()

        for (method in asmInfo.asmClass.declaredMethods) {
            val shadowAnnotation = method.getAnnotation(Shadow::class.java)
            if (shadowAnnotation != null) {
                val methodName = method.name
                val prefix = shadowAnnotation.method

                val targetMethodName =
                    if (prefix.startsWith(Shadow.prefix)) {
                        prefix.substring(Shadow.prefix.length)
                    } else {
                        methodName
                    }

                shadowMethodMap[methodName] = targetMethodName
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
                    if (insn.owner == asmClassName && shadowFieldMap.containsKey(insn.name)) {
                        val targetFieldName = shadowFieldMap[insn.name]!!
                        insn.owner = targetClassName
                        insn.name = targetFieldName
                    }
                }
                is MethodInsnNode -> {
                    // 如果是调用 ASM 类的方法
                    if (insn.owner == asmClassName) {
                        // 检查是否是 Shadow 方法
                        if (shadowMethodMap.containsKey(insn.name)) {
                            val targetMethodName = shadowMethodMap[insn.name]!!
                            insn.owner = targetClassName
                            insn.name = targetMethodName
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

    /**
     * 重新计算 maxLocals
     */
    private fun recalculateMaxLocals(target: MethodNode) {
        val isStatic = (target.access and Opcodes.ACC_STATIC) != 0
        var maxIndex = if (isStatic) 0 else 1

        val paramTypes = Type.getArgumentTypes(target.desc)
        for (paramType in paramTypes) {
            maxIndex += paramType.size
        }

        for (localVar in target.localVariables) {
            val varType =
                try {
                    Type.getType(localVar.desc)
                } catch (e: Exception) {
                    null
                }
            val varSize = varType?.size ?: 1
            val endIndex = localVar.index + varSize
            if (endIndex < 65535) {
                maxIndex = maxOf(maxIndex, endIndex)
            }
        }

        for (insn in target.instructions.toArray()) {
            if (insn is VarInsnNode) {
                val needsDoubleSlot =
                    when (insn.opcode) {
                        Opcodes.LLOAD, Opcodes.LSTORE, Opcodes.DLOAD, Opcodes.DSTORE -> true
                        else -> false
                    }
                val endIndex = insn.`var` + (if (needsDoubleSlot) 2 else 1)
                if (endIndex < 65535) {
                    maxIndex = maxOf(maxIndex, endIndex)
                }
            }
        }

        target.maxLocals = maxOf(1, minOf(maxIndex, 65535))
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
