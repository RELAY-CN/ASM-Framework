/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.utils.transformer

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.Handle
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.tree.*

/**
 * 字节码工具类。
 *
 * 提供常量指令识别、常量值与常量类型推断，以及 ASM 节点访问标志判断等通用能力。
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
object BytecodeUtil {
    /**
     * JVM 短常量操作码集合。
     */
    private val CONSTANTS_ALL =
        intArrayOf(
            Opcodes.ACONST_NULL,
            Opcodes.ICONST_M1,
            Opcodes.ICONST_0,
            Opcodes.ICONST_1,
            Opcodes.ICONST_2,
            Opcodes.ICONST_3,
            Opcodes.ICONST_4,
            Opcodes.ICONST_5,
            Opcodes.LCONST_0,
            Opcodes.LCONST_1,
            Opcodes.FCONST_0,
            Opcodes.FCONST_1,
            Opcodes.FCONST_2,
            Opcodes.DCONST_0,
            Opcodes.DCONST_1,
        )

    /**
     * 与 [CONSTANTS_ALL] 顺序对应的常量值。
     */
    private val CONSTANTS_VALUES =
        arrayOf<Any?>(
            null, // ACONST_NULL
            -1,
            0,
            1,
            2,
            3,
            4,
            5, // ICONST_M1 to ICONST_5
            0L,
            1L, // LCONST_0, LCONST_1
            0.0f,
            1.0f,
            2.0f, // FCONST_0 to FCONST_2
            0.0,
            1.0, // DCONST_0, DCONST_1
        )

    /**
     * 与 [CONSTANTS_ALL] 顺序对应的常量类型描述符。
     */
    private val CONSTANTS_TYPES =
        arrayOf(
            "Ljava/lang/Object;", // ACONST_NULL
            "I",
            "I",
            "I",
            "I",
            "I",
            "I",
            "I", // ICONST_M1 to ICONST_5
            "J",
            "J", // LCONST_0, LCONST_1
            "F",
            "F",
            "F", // FCONST_0 to FCONST_2
            "D",
            "D", // DCONST_0, DCONST_1
        )

    /**
     * 判断指令是否为框架支持的常量加载指令。
     *
     * 支持 JVM 短常量指令、`LDC`，以及 `BIPUSH` / `SIPUSH`。
     *
     * @param insn 待检查的指令；可为 `null`
     * @return 指令为常量加载指令时返回 `true`
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    fun isConstant(insn: AbstractInsnNode?): Boolean {
        if (insn == null) {
            return false
        }
        return CONSTANTS_ALL.contains(insn.opcode) ||
            insn is LdcInsnNode ||
            (insn is IntInsnNode && (insn.opcode == Opcodes.BIPUSH || insn.opcode == Opcodes.SIPUSH))
    }

    /**
     * 读取常量加载指令对应的常量值。
     *
     * 非常量指令或 `null` 会返回 `null`；非法的 [IntInsnNode] 操作码会抛出异常。
     *
     * @param insn 待读取的指令；可为 `null`
     * @return 指令加载的常量值
     * @throws IllegalArgumentException 当 [IntInsnNode] 不是 `BIPUSH` 或 `SIPUSH` 时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
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
        }

        val index = CONSTANTS_ALL.indexOf(insn.opcode)
        return if (index >= 0) CONSTANTS_VALUES[index] else null
    }

    /**
     * 推断常量加载指令的栈类型。
     *
     * `Type` 常量会区分类字面量与方法类型字面量；`ConstantDynamic` 使用自身描述符作为结果类型。
     *
     * @param insn 待读取的指令；可为 `null`
     * @return 常量入栈后的 ASM [Type]；无法推断时返回 `null`
     * @throws IllegalArgumentException 当 [IntInsnNode] 不是 `BIPUSH` 或 `SIPUSH` 时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
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
                    is Type -> {
                        if (cst.sort == Type.METHOD) {
                            Type.getType("Ljava/lang/invoke/MethodType;")
                        } else {
                            Type.getType("Ljava/lang/Class;")
                        }
                    }
                    is Handle -> Type.getType("Ljava/lang/invoke/MethodHandle;")
                    is ConstantDynamic -> Type.getType(cst.descriptor)
                    else -> null
                }
            }
            is IntInsnNode -> {
                if (insn.opcode == Opcodes.BIPUSH || insn.opcode == Opcodes.SIPUSH) {
                    return Type.INT_TYPE
                }
                throw IllegalArgumentException("IntInsnNode with invalid opcode ${insn.opcode} in getConstantType")
            }
        }

        val index = CONSTANTS_ALL.indexOf(insn.opcode)
        return if (index >= 0) Type.getType(CONSTANTS_TYPES[index]) else null
    }

    /**
     * 检查常量指令是否匹配注解中的文本目标。
     *
     * 文本目标支持 `null`、`true` / `false`、数字、字符串、类字面量 internal name 或 binary name、
     * 方法类型描述符、方法句柄 `owner.name(desc)`，以及动态常量的 `name` 或 `name:descriptor`。
     *
     * @param insn 常量加载指令
     * @param value 注解中声明的文本目标
     * @return 常量值与文本目标匹配时返回 `true`
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    fun matchesConstantText(
        insn: AbstractInsnNode,
        value: String,
    ): Boolean {
        val constant = getConstant(insn)
        if (constant == null) {
            return value == "null"
        }
        if (isBooleanLiteral(value)) {
            return isBooleanConstantInsn(insn, value == "true")
        }

        return when (constant) {
            is Int -> constant.toString() == value
            is Long -> constant.toString() == value
            is Float -> constant.toString() == value
            is Double -> constant.toString() == value
            is String -> constant == value
            is Type -> {
                if (constant.sort == Type.METHOD) {
                    constant.descriptor == value
                } else {
                    constant.internalName == value.replace('.', '/')
                }
            }
            is Handle -> "${constant.owner}.${constant.name}${constant.desc}" == value
            is ConstantDynamic -> constant.name == value || "${constant.name}:${constant.descriptor}" == value
            else -> false
        }
    }

    private fun isBooleanLiteral(value: String): Boolean = value == "true" || value == "false"

    private fun isBooleanConstantInsn(
        insn: AbstractInsnNode,
        value: Boolean,
    ): Boolean =
        when (insn.opcode) {
            Opcodes.ICONST_0 -> !value
            Opcodes.ICONST_1 -> value
            else -> false
        }

    /**
     * 检查方法是否带有指定访问标志。
     *
     * @param method 方法节点
     * @param flag ASM 访问标志
     * @return 方法访问标志完整包含 [flag] 时返回 `true`
     */
    fun hasFlag(
        method: MethodNode,
        flag: Int,
    ): Boolean = (method.access and flag) == flag

    /**
     * 检查类是否带有指定访问标志。
     *
     * @param classNode 类节点
     * @param flag ASM 访问标志
     * @return 类访问标志完整包含 [flag] 时返回 `true`
     */
    fun hasFlag(
        classNode: ClassNode,
        flag: Int,
    ): Boolean = (classNode.access and flag) == flag

    /**
     * 检查字段是否带有指定访问标志。
     *
     * @param field 字段节点
     * @param flag ASM 访问标志
     * @return 字段访问标志完整包含 [flag] 时返回 `true`
     */
    fun hasFlag(
        field: FieldNode,
        flag: Int,
    ): Boolean = (field.access and flag) == flag
}
