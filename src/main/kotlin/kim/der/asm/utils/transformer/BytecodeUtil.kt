/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.utils.transformer

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*

/**
 * 字节码工具类
 * 提供常量检查和类型判断等功能
 */
object BytecodeUtil {
    /**
     * 所有常量操作码
     */
    private val CONSTANTS_ALL = intArrayOf(
        Opcodes.ACONST_NULL,
        Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2,
        Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5,
        Opcodes.LCONST_0, Opcodes.LCONST_1,
        Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2,
        Opcodes.DCONST_0, Opcodes.DCONST_1,
    )

    /**
     * 常量值数组
     */
    private val CONSTANTS_VALUES = arrayOf<Any?>(
        Type.VOID_TYPE, // ACONST_NULL
        -1, 0, 1, 2, 3, 4, 5, // ICONST_M1 to ICONST_5
        0L, 1L, // LCONST_0, LCONST_1
        0.0f, 1.0f, 2.0f, // FCONST_0 to FCONST_2
        0.0, 1.0, // DCONST_0, DCONST_1
    )

    /**
     * 常量类型数组
     */
    private val CONSTANTS_TYPES = arrayOf(
        "Ljava/lang/Object;", // ACONST_NULL
        "I", "I", "I", "I", "I", "I", "I", // ICONST_M1 to ICONST_5
        "J", "J", // LCONST_0, LCONST_1
        "F", "F", "F", // FCONST_0 to FCONST_2
        "D", "D", // DCONST_0, DCONST_1
    )

    /**
     * 检查指令是否为常量指令
     */
    fun isConstant(insn: AbstractInsnNode?): Boolean {
        if (insn == null) {
            return false
        }
        return CONSTANTS_ALL.contains(insn.opcode) ||
            insn is LdcInsnNode ||
            (insn is IntInsnNode && (insn.opcode == Opcodes.BIPUSH || insn.opcode == Opcodes.SIPUSH)) ||
            (insn is TypeInsnNode && insn.opcode >= Opcodes.CHECKCAST)
    }

    /**
     * 获取常量值
     */
    fun getConstant(insn: AbstractInsnNode?): Any? {
        if (insn == null) {
            return null
        }

        when (insn) {
            is LdcInsnNode -> return insn.cst
            is IntInsnNode -> {
                if (insn.opcode == Opcodes.BIPUSH || insn.opcode == Opcodes.SIPUSH) {
                    return insn.operand
                }
                throw IllegalArgumentException("IntInsnNode with invalid opcode ${insn.opcode} in getConstant")
            }
            is TypeInsnNode -> {
                if (insn.opcode >= Opcodes.CHECKCAST) {
                    return Type.getObjectType(insn.desc)
                }
                return null // NEW 和 ANEWARRAY 不算常量
            }
        }

        val index = CONSTANTS_ALL.indexOf(insn.opcode)
        return if (index >= 0) CONSTANTS_VALUES[index] else null
    }

    /**
     * 获取常量的类型
     */
    fun getConstantType(insn: AbstractInsnNode?): Type? {
        if (insn == null) {
            return null
        }

        when (insn) {
            is LdcInsnNode -> {
                val cst = insn.cst
                return when (cst) {
                    is Int -> Type.INT_TYPE
                    is Float -> Type.FLOAT_TYPE
                    is Long -> Type.LONG_TYPE
                    is Double -> Type.DOUBLE_TYPE
                    is String -> Type.getType("Ljava/lang/String;")
                    is Type -> Type.getType("Ljava/lang/Class;")
                    else -> null
                }
            }
            is TypeInsnNode -> {
                if (insn.opcode >= Opcodes.CHECKCAST) {
                    return Type.getType("Ljava/lang/Class;")
                }
                return null
            }
        }

        val index = CONSTANTS_ALL.indexOf(insn.opcode)
        return if (index >= 0) Type.getType(CONSTANTS_TYPES[index]) else null
    }

    /**
     * 检查方法是否有指定标志
     */
    fun hasFlag(method: MethodNode, flag: Int): Boolean {
        return (method.access and flag) == flag
    }

    /**
     * 检查类是否有指定标志
     */
    fun hasFlag(classNode: ClassNode, flag: Int): Boolean {
        return (classNode.access and flag) == flag
    }

    /**
     * 检查字段是否有指定标志
     */
    fun hasFlag(field: FieldNode, flag: Int): Boolean {
        return (field.access and flag) == flag
    }
}

