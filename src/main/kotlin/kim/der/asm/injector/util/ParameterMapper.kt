/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.injector.util

import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.utils.transformer.InstructionUtil
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode
import java.lang.reflect.Method

/**
 * 参数映射工具
 * 用于将目标方法的参数映射到 ASM 方法的参数
 *
 * @author Dr (dr@der.kim)
 */
object ParameterMapper {
    /**
     * 生成加载参数的指令
     *
     * @param il 指令列表
     * @param targetMethod 目标方法
     * @param asmMethod ASM 方法
     * @param skipCallbackInfo 是否跳过 CallbackInfo 参数
     * @param targetClassName 目标类名（用于传递 this 参数）
     */
    fun loadParameters(
        il: InsnList,
        targetMethod: MethodNode,
        asmMethod: Method,
        skipCallbackInfo: Boolean = true,
        targetClassName: String? = null,
    ) {
        val isStatic = (targetMethod.access and Opcodes.ACC_STATIC) != 0
        val paramTypes = asmMethod.parameterTypes
        val targetParamTypes = Type.getArgumentTypes(targetMethod.desc)

        var asmParamIndex = 0
        var targetVarIndex = if (isStatic) 0 else 1

        // 第一个参数可能是 CallbackInfo
        if (skipCallbackInfo && paramTypes.isNotEmpty() && paramTypes[0] == CallbackInfo::class.java) {
            asmParamIndex++
        }

        // 检查第一个参数是否是目标类的 this
        if (!isStatic && asmParamIndex < paramTypes.size && targetClassName != null) {
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

            // 如果 ASM 参数类型与目标参数类型匹配，加载对应的参数
            if (targetVarIndex - (if (isStatic) 0 else 1) < targetParamTypes.size) {
                val targetParamType = targetParamTypes[targetVarIndex - (if (isStatic) 0 else 1)]

                if (canAssign(asmParamType, targetParamType)) {
                    loadParameter(il, targetParamType, targetVarIndex)
                    targetVarIndex += if (targetParamType.sort == Type.LONG || targetParamType.sort == Type.DOUBLE) 2 else 1
                    asmParamIndex++
                } else {
                    // 类型不匹配，尝试使用默认值或跳过
                    loadDefaultValue(il, Type.getType(asmParamType))
                    asmParamIndex++
                }
            } else {
                // 目标方法参数不足，使用默认值
                loadDefaultValue(il, Type.getType(asmParamType))
                asmParamIndex++
            }
        }
    }

    /**
     * 加载单个参数
     */
    private fun loadParameter(
        il: InsnList,
        paramType: Type,
        varIndex: Int,
    ) {
        il.add(InstructionUtil.loadParam(paramType, varIndex))
    }

    /**
     * 加载默认值
     */
    private fun loadDefaultValue(
        il: InsnList,
        paramType: Type,
    ) {
        when (paramType.sort) {
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
     * 检查是否可以赋值
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
                return toClass.isAssignableFrom(fromClass)
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
}
