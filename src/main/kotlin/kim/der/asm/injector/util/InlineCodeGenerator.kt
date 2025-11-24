/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.injector.util

import kim.der.asm.api.annotation.Copy
import kim.der.asm.api.annotation.Shadow
import kim.der.asm.data.AsmInfo
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.lang.reflect.Method

/**
 * 内联代码生成器
 * 将 ASM 方法的字节码直接内联到目标方法中
 *
 * @author Dr (dr@der.kim)
 */
object InlineCodeGenerator {
    /**
     * 提取并内联 ASM 方法的字节码
     *
     * @param target 目标方法节点
     * @param asmMethod ASM 方法
     * @param asmInfo ASM 信息
     * @param targetClassName 目标类名（内部名称，如 "com/example/MyClass"）
     * @return 内联后的指令列表
     */
    fun inlineMethodCode(
        target: MethodNode,
        asmMethod: Method,
        asmInfo: AsmInfo,
        targetClassName: String,
    ): InsnList {
        // 获取 ASM 方法的字节码
        val asmMethodNode =
            extractAsmMethodNode(asmInfo, asmMethod)
                ?: throw IllegalStateException("Cannot extract method ${asmMethod.name} from asm class ${asmInfo.asmClass.name}")

        // 创建标签映射
        val labelMap = mutableMapOf<LabelNode, LabelNode>()

        // 先遍历一次，创建所有标签的映射
        asmMethodNode.instructions.forEach { insn ->
            if (insn is LabelNode) {
                labelMap[insn] = LabelNode()
            }
        }

        // 复制指令
        val il = InsnList()
        asmMethodNode.instructions.forEach { insn ->
            if (insn != null) {
                val cloned =
                    try {
                        insn.clone(labelMap)
                    } catch (e: Exception) {
                        cloneInstructionSafely(insn, labelMap)
                    }

                if (cloned != null) {
                    il.add(cloned)
                } else {
                    val manualCloned = cloneInstructionSafely(insn, labelMap)
                    if (manualCloned != null) {
                        il.add(manualCloned)
                    }
                }
            }
        }

        // 调整局部变量索引和参数映射
        adjustLocalVariables(il, asmMethodNode, target)

        // 转换 Shadow 字段和方法调用
        transformShadowReferences(il, asmInfo, targetClassName)

        return il
    }

    /**
     * 从 ASM 类中提取方法节点
     */
    private fun extractAsmMethodNode(
        asmInfo: AsmInfo,
        asmMethod: Method,
    ): MethodNode? {
        val className = asmInfo.asmClass.name.replace('.', '/')
        val resource = className + ".class"
        val inputStream =
            asmInfo.asmClass.classLoader?.getResourceAsStream(resource)
                ?: ClassLoader.getSystemClassLoader().getResourceAsStream(resource)
                ?: return null

        val classBytes = inputStream.use { it.readBytes() }
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
     * 调整局部变量索引以匹配目标方法
     */
    private fun adjustLocalVariables(
        il: InsnList,
        source: MethodNode,
        target: MethodNode,
    ) {
        val sourceParamTypes = Type.getArgumentTypes(source.desc)
        val targetParamTypes = Type.getArgumentTypes(target.desc)

        val sourceIsStatic = (source.access and Opcodes.ACC_STATIC) != 0
        val targetIsStatic = (target.access and Opcodes.ACC_STATIC) != 0

        // 计算参数占用的局部变量数
        val sourceParamSlots = calculateParamSlots(source)
        val targetParamSlots = calculateParamSlots(target)

        // 构建参数索引映射
        val indexMap = buildParameterIndexMap(source, target, sourceParamTypes, targetParamTypes)

        // 调整所有局部变量索引
        val insns = il.toArray()
        for (insn in insns) {
            when (insn) {
                is VarInsnNode -> {
                    val oldIndex = insn.`var`
                    val isThisParam = !sourceIsStatic && targetIsStatic && oldIndex == 0

                    if (isThisParam) {
                        // 如果源方法是实例方法而目标方法是静态方法，且访问的是 this，需要移除
                        il.remove(insn)
                        continue
                    }

                    val newIndex =
                        if (oldIndex < sourceParamSlots) {
                            // 参数区域：使用映射表
                            indexMap[oldIndex] ?: oldIndex
                        } else {
                            // 非参数区域：加上参数差异
                            oldIndex + (targetParamSlots - sourceParamSlots)
                        }

                    if (newIndex >= 0 && newIndex < 65535) {
                        insn.`var` = newIndex
                    }
                }
                is IincInsnNode -> {
                    val oldIndex = insn.`var`
                    val isThisParam = !sourceIsStatic && targetIsStatic && oldIndex == 0

                    if (isThisParam) {
                        il.remove(insn)
                        continue
                    }

                    val newIndex =
                        if (oldIndex < sourceParamSlots) {
                            indexMap[oldIndex] ?: oldIndex
                        } else {
                            oldIndex + (targetParamSlots - sourceParamSlots)
                        }

                    if (newIndex >= 0 && newIndex < 65535) {
                        insn.`var` = newIndex
                    }
                }
            }
        }
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
     * 转换 Shadow 字段和方法引用
     * 将访问 Shadow 字段的指令转换为访问目标类字段的指令
     * 将调用 Shadow 方法的指令转换为调用目标类方法的指令
     */
    private fun transformShadowReferences(
        il: InsnList,
        asmInfo: AsmInfo,
        targetClassName: String,
    ) {
        val asmClassName = Type.getType(asmInfo.asmClass).internalName

        // 构建 Shadow 字段映射：ASM 字段名 -> 目标字段名
        val shadowFieldMap = mutableMapOf<String, String>()
        for (field in asmInfo.asmClass.declaredFields) {
            val shadowAnnotation = field.getAnnotation(Shadow::class.java)
            if (shadowAnnotation != null) {
                val fieldName = field.name
                val method = shadowAnnotation.method

                // 如果注解以 [Shadow.prefix] 开头，需要去掉 prefix
                val targetFieldName =
                    if (method.startsWith(Shadow.prefix)) {
                        method.substring(Shadow.prefix.length)
                    } else {
                        fieldName
                    }

                shadowFieldMap[fieldName] = targetFieldName
            }
        }

        // 构建 Shadow 方法映射：ASM 方法名 -> 目标方法名
        val shadowMethodMap = mutableMapOf<String, String>()
        // 构建 Copy 方法映射：ASM 方法名 -> 目标方法名（通过 @Copy 复制的方法）
        val copyMethodMap = mutableMapOf<String, String>()
        
        for (method in asmInfo.asmClass.declaredMethods) {
            val shadowAnnotation = method.getAnnotation(Shadow::class.java)
            if (shadowAnnotation != null) {
                val methodName = method.name
                val prefix = shadowAnnotation.method

                // 如果注解以 [Shadow.prefix] 开头，需要去掉 prefix
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
                val targetMethodName = if (copyAnnotation.method.isEmpty()) {
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
        val insns = il.toArray()
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
}
