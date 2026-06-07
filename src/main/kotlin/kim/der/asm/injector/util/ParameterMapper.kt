/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.injector.util

import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.annotation.Local
import kim.der.asm.utils.transformer.InstructionUtil
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.LocalVariableNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode
import java.lang.reflect.Method

/**
 * 参数映射工具。
 *
 * 用于在生成 ASM 方法调用指令时，把目标方法的局部变量槽位映射到 ASM 方法参数。
 * 当前映射按声明顺序匹配参数，并支持在 ASM 方法首参中跳过 [CallbackInfo]、为实例方法传入目标 `this`。
 * handler 参数显式标记 [Local] 时，会从调用方提供的局部变量捕获上下文读取当前注入点可见的槽位值。
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
object ParameterMapper {
    /**
     * 生成加载参数的指令。
     *
     * 该方法会向 [il] 追加 `load` 指令；若 ASM 方法参数无法映射到目标方法参数，会抛出异常而不是静默跳过。
     *
     * @param il 指令列表
     * @param targetMethod 目标方法
     * @param asmMethod ASM 方法
     * @param skipCallbackInfo 是否跳过 CallbackInfo 参数
     * @param targetClassName 目标类名（用于传递 this 参数）
     * @throws IllegalStateException ASM 方法参数无法映射到目标方法参数时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    fun loadParameters(
        il: InsnList,
        targetMethod: MethodNode,
        asmMethod: Method,
        skipCallbackInfo: Boolean = true,
        targetClassName: String? = null,
        localCaptureContext: LocalCaptureContext? = null,
    ) {
        val isStatic = (targetMethod.access and Opcodes.ACC_STATIC) != 0
        val paramTypes = asmMethod.parameterTypes
        val paramAnnotations = asmMethod.parameterAnnotations
        val targetParamTypes = Type.getArgumentTypes(targetMethod.desc)

        var asmParamIndex = 0
        var targetVarIndex = if (isStatic) 0 else 1

        // 第一个参数可能是 CallbackInfo
        if (skipCallbackInfo && paramTypes.isNotEmpty() && AsmMethodCallGenerator.isCallbackInfoType(paramTypes[0])) {
            asmParamIndex++
        }

        // 检查第一个参数是否是目标类的 this
        if (!isStatic && asmParamIndex < paramTypes.size && targetClassName != null && localAnnotation(paramAnnotations, asmParamIndex) == null) {
            val firstParamType = paramTypes[asmParamIndex]
            val targetClassType = Type.getObjectType(targetClassName.replace('.', '/'))

            // 如果第一个参数是目标类的类型，加载 this (ALOAD 0)
            if (canAssign(firstParamType, targetClassType)) {
                il.add(VarInsnNode(Opcodes.ALOAD, 0))
                asmParamIndex++
            }
        }

        // 映射其他参数
        while (asmParamIndex < paramTypes.size) {
            val asmParamType = paramTypes[asmParamIndex]
            val local = localAnnotation(paramAnnotations, asmParamIndex)
            if (local != null) {
                loadLocalParameter(
                    il = il,
                    targetMethod = targetMethod,
                    asmMethod = asmMethod,
                    asmParamIndex = asmParamIndex,
                    asmParamType = asmParamType,
                    local = local,
                    localCaptureContext = localCaptureContext,
                )
                asmParamIndex++
                continue
            }

            // 如果 ASM 参数类型与目标参数类型匹配，加载对应的参数
            if (targetVarIndex - (if (isStatic) 0 else 1) < targetParamTypes.size) {
                val targetParamType = targetParamTypes[targetVarIndex - (if (isStatic) 0 else 1)]

                if (canAssign(asmParamType, targetParamType)) {
                    loadParameter(il, targetParamType, targetVarIndex)
                    targetVarIndex += if (targetParamType.sort == Type.LONG || targetParamType.sort == Type.DOUBLE) 2 else 1
                    asmParamIndex++
                } else {
                    throw IllegalStateException(
                        "Cannot map ASM method parameter #$asmParamIndex " +
                            "(${Type.getType(asmParamType)}) to target method ${targetMethod.name}${targetMethod.desc}: " +
                            "target parameter type is $targetParamType",
                    )
                }
            } else {
                throw IllegalStateException(
                    "Cannot map ASM method parameter #$asmParamIndex " +
                        "(${Type.getType(asmParamType)}) to target method ${targetMethod.name}${targetMethod.desc}: " +
                        "target method has only ${targetParamTypes.size} parameter(s)",
                )
            }
        }
    }

    /**
     * 加载单个参数。
     *
     * @param il 指令列表
     * @param paramType 参数类型
     * @param varIndex 局部变量槽位索引
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    private fun loadParameter(
        il: InsnList,
        paramType: Type,
        varIndex: Int,
    ) {
        il.add(InstructionUtil.loadParam(paramType, varIndex))
    }

    /**
     * 加载 [Local] 标记的 handler 参数。
     *
     * 局部变量捕获必须由具体注入器提供锚点；缺少锚点、缺少匹配变量或类型不兼容都会快速失败，
     * 避免参数被误映射为目标方法参数前缀。
     */
    private fun loadLocalParameter(
        il: InsnList,
        targetMethod: MethodNode,
        asmMethod: Method,
        asmParamIndex: Int,
        asmParamType: Class<*>,
        local: Local,
        localCaptureContext: LocalCaptureContext?,
    ) {
        val context =
            localCaptureContext
                ?: throw IllegalStateException(
                    "@Local parameter #$asmParamIndex in handler ${asmMethod.name} requires a local capture anchor",
                )
        val requestedName = local.name.ifBlank { local.value }
        if (requestedName.isBlank() && local.index < 0) {
            throw IllegalStateException(
                "@Local parameter #$asmParamIndex in handler ${asmMethod.name} must declare name/value or index",
            )
        }

        val visibleLocals = visibleLocals(targetMethod, context.anchor)
        val matchedLocals =
            visibleLocals.filter { variable ->
                (requestedName.isBlank() || variable.name == requestedName) &&
                    (local.index < 0 || variable.index == local.index)
            }

        if (matchedLocals.isEmpty()) {
            throw IllegalStateException(
                "${localLabel(local)} cannot find visible local variable for " +
                    "${context.targetClassName}.${targetMethod.name}${targetMethod.desc}",
            )
        }

        val compatibleLocals = matchedLocals.filter { canAssign(asmParamType, Type.getType(it.desc)) }
        if (compatibleLocals.isEmpty()) {
            val actualTypes = matchedLocals.joinToString { "${it.name}:${it.desc}@${it.index}" }
            throw IllegalStateException(
                "${localLabel(local)} cannot assign visible local variable for " +
                    "${context.targetClassName}.${targetMethod.name}${targetMethod.desc} " +
                    "to handler parameter #$asmParamIndex ${Type.getType(asmParamType)}; candidates: $actualTypes",
            )
        }
        if (compatibleLocals.size > 1) {
            val candidates = compatibleLocals.joinToString { "${it.name}:${it.desc}@${it.index}" }
            throw IllegalStateException(
                "${localLabel(local)} matches multiple visible local variables for " +
                    "${context.targetClassName}.${targetMethod.name}${targetMethod.desc}: $candidates",
            )
        }

        val capturedLocal = compatibleLocals.single()
        loadParameter(il, Type.getType(capturedLocal.desc), capturedLocal.index)
    }

    /**
     * 查找当前锚点可见的 LocalVariableTable 记录。
     */
    private fun visibleLocals(
        targetMethod: MethodNode,
        anchor: AbstractInsnNode,
    ): List<LocalVariableNode> {
        val insns = targetMethod.instructions.toArray()
        val anchorIndex = insns.indexOf(anchor)
        if (anchorIndex < 0) {
            return emptyList()
        }
        return targetMethod.localVariables.filter { it.containsInstruction(insns, anchorIndex) }
    }

    /**
     * 判断局部变量表记录是否覆盖给定指令下标。
     */
    private fun LocalVariableNode.containsInstruction(
        insns: Array<AbstractInsnNode>,
        instructionIndex: Int,
    ): Boolean {
        val startIndex = insns.indexOf(start)
        val endIndex = insns.indexOf(end)
        return startIndex >= 0 &&
            endIndex >= 0 &&
            instructionIndex >= startIndex &&
            instructionIndex < endIndex
    }

    private fun localAnnotation(
        annotations: Array<Array<Annotation>>,
        index: Int,
    ): Local? = annotations.getOrNull(index)?.firstNotNullOfOrNull { it as? Local }

    private fun localLabel(local: Local): String {
        val requestedName = local.name.ifBlank { local.value }
        return when {
            requestedName.isNotBlank() && local.index >= 0 -> "@Local(name=$requestedName,index=${local.index})"
            requestedName.isNotBlank() -> "@Local(name=$requestedName)"
            else -> "@Local(index=${local.index})"
        }
    }

    /**
     * 检查 ASM 方法参数类型是否可以接收目标方法参数类型。
     *
     * 基本类型必须完全匹配；对象类型优先尝试类加载后的继承判断，加载失败时退回到名称匹配。
     *
     * @param from ASM 方法参数类型
     * @param toType 目标方法参数类型
     * @return 可以赋值时返回 `true`
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    private fun canAssign(
        from: Class<*>,
        toType: Type,
    ): Boolean {
        val fromType = Type.getType(from)

        // 基本类型必须完全匹配
        if (fromType.sort in PRIMITIVE_TYPES || toType.sort in PRIMITIVE_TYPES) {
            return fromType.sort == toType.sort
        }

        // 数组类型匹配
        if (fromType.sort == Type.ARRAY && toType.sort == Type.ARRAY) {
            return fromType.elementType == toType.elementType
        }

        // 对象类型：检查是否是同一类型或子类型
        if (fromType.sort == Type.OBJECT && toType.sort == Type.OBJECT) {
            val fromClassName = fromType.internalName.replace('/', '.')
            val toClassName = toType.internalName.replace('/', '.')

            // Object/Any 类型可以接收任何对象类型
            if (fromClassName == "java.lang.Object" || fromClassName == "kotlin.Any") {
                return true
            }

            // 完全匹配
            if (fromClassName == toClassName) {
                return true
            }

            // 检查继承关系
            try {
                val fromClass = Class.forName(fromClassName)
                val toClass = Class.forName(toClassName)
                return fromClass.isAssignableFrom(toClass)
            } catch (e: Exception) {
                // 如果无法加载类，使用名称匹配
                // 也检查简单名称匹配（处理默认包的情况）
                return fromClassName.startsWith("$toClassName.") ||
                    toClassName == "java/lang/Object" || toClassName == "java.lang.Object"
            }
        }

        return false
    }

    private val PRIMITIVE_TYPES =
        setOf(
            Type.BOOLEAN,
            Type.BYTE,
            Type.CHAR,
            Type.SHORT,
            Type.INT,
            Type.LONG,
            Type.FLOAT,
            Type.DOUBLE,
        )

    /**
     * 局部变量捕获上下文。
     *
     * @property anchor 注入器正在处理的目标方法指令，作为 LocalVariableTable 作用域判断锚点
     * @property targetClassName 目标类名，用于生成可诊断的失败消息
     */
    data class LocalCaptureContext(
        val anchor: AbstractInsnNode,
        val targetClassName: String,
    )
}
