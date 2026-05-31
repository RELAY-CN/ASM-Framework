/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */
package kim.der.asm.utils.transformer

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*

/**
 * 字节码指令构造工具。
 *
 * 该对象封装 ASM Tree API 中常见的类型常量加载、装箱、拆箱、局部变量读取与返回指令构造逻辑。
 * 所有方法只创建指令节点或指令列表，不会直接修改目标方法。
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
@Suppress("UNUSED")
internal object InstructionUtil {
    /**
     * 构造加载 [Type] 对应 `Class` 对象的指令。
     *
     * 基础类型与 `void` 返回对应包装类型的 `TYPE` 字段；引用类型与数组类型直接加载 ASM [Type] 常量。
     *
     * @param type 需要加载的 JVM 类型
     * @return 可插入方法体的单条加载指令
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    @JvmStatic
    fun loadType(type: Type): AbstractInsnNode =
        when (type.sort) {
            Type.BOOLEAN, Type.BYTE, Type.SHORT, Type.INT, Type.FLOAT, Type.LONG, Type.DOUBLE, Type.CHAR, Type.VOID ->
                FieldInsnNode(
                    Opcodes.GETSTATIC,
                    getWrapper(type).internalName,
                    "TYPE",
                    Type.getDescriptor(Class::class.java),
                )
            else -> LdcInsnNode(type)
        }

    /**
     * 构造基础类型装箱指令。
     *
     * 引用类型、数组类型与 `void` 不需要装箱，会返回 `null`。
     *
     * @param type 栈顶值的 JVM 类型
     * @return 调用包装类型 `valueOf` 的指令；不需要装箱时返回 `null`
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    @JvmStatic
    fun box(type: Type): MethodInsnNode? =
        when (type.sort) {
            Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT, Type.FLOAT, Type.LONG, Type.DOUBLE -> {
                val wrap = getWrapper(type)
                MethodInsnNode(Opcodes.INVOKESTATIC, wrap.internalName, "valueOf", "(" + type.descriptor + ")" + wrap.descriptor, false)
            }
            else -> null
        }

    /**
     * 获取基础类型对应的包装类型。
     *
     * 非基础类型会原样返回，用于判断是否需要装箱或拆箱。
     *
     * @param type JVM 类型
     * @return 基础类型的包装类型，或原始引用类型
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    fun getWrapper(type: Type): Type =
        when (type.sort) {
            Type.BOOLEAN -> Type.getType("Ljava/lang/Boolean;")
            Type.CHAR -> Type.getType("Ljava/lang/Character;")
            Type.BYTE -> Type.getType("Ljava/lang/Byte;")
            Type.SHORT -> Type.getType("Ljava/lang/Short;")
            Type.INT -> Type.getType("Ljava/lang/Integer;")
            Type.FLOAT -> Type.getType("Ljava/lang/Float;")
            Type.LONG -> Type.getType("Ljava/lang/Long;")
            Type.DOUBLE -> Type.getType("Ljava/lang/Double;")
            Type.VOID -> Type.getType("Ljava/lang/Void;")
            else -> type
        }

    /**
     * 判断类型是否为 JVM 基础类型或 `void`。
     *
     * @param type JVM 类型
     * @return 需要包装类型承载时返回 `true`
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    fun isPrimitive(type: Type): Boolean = getWrapper(type) !== type

    /**
     * 构造拆箱或强制类型转换指令列表。
     *
     * 基础类型会先转换为对应包装类型再调用 `xxxValue()`；引用类型会生成 `CHECKCAST`；
     * `void` 会生成 `POP` 丢弃占位返回值。
     *
     * @param type 目标 JVM 类型
     * @return 可插入方法体的拆箱或转换指令列表
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    @JvmStatic
    fun unbox(type: Type): InsnList {
        val name: String
        val il = InsnList()
        name =
            when (type.sort) {
                Type.BOOLEAN -> "booleanValue"
                Type.CHAR -> "charValue"
                Type.BYTE -> "byteValue"
                Type.SHORT -> "shortValue"
                Type.INT -> "intValue"
                Type.FLOAT -> "floatValue"
                Type.LONG -> "longValue"
                Type.DOUBLE -> "doubleValue"
                Type.VOID -> {
                    il.add(InsnNode(Opcodes.POP))
                    return il
                }
                else -> {
                    il.add(TypeInsnNode(Opcodes.CHECKCAST, type.internalName))
                    return il
                }
            }
        val o = getWrapper(type).internalName
        val s = "()" + type.descriptor
        il.add(TypeInsnNode(Opcodes.CHECKCAST, o))
        il.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, o, name, s))
        return il
    }

    /**
     * 构造从局部变量表加载指定类型值的指令。
     *
     * [type] 为 `void` 时没有可加载值，会抛出异常。
     *
     * @param type 局部变量的 JVM 类型
     * @param var 局部变量槽位索引
     * @return 对应的 `xLOAD` 指令
     * @throws IllegalArgumentException 当 [type] 为 `void` 时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    @JvmStatic
    fun loadParam(
        type: Type,
        `var`: Int,
    ): VarInsnNode =
        when (type.sort) {
            Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> VarInsnNode(Opcodes.ILOAD, `var`)
            Type.FLOAT -> VarInsnNode(Opcodes.FLOAD, `var`)
            Type.LONG -> VarInsnNode(Opcodes.LLOAD, `var`)
            Type.DOUBLE -> VarInsnNode(Opcodes.DLOAD, `var`)
            Type.VOID -> throw IllegalArgumentException("Can't load VOID type!")
            else -> VarInsnNode(Opcodes.ALOAD, `var`)
        }

    /**
     * 构造指定返回类型对应的 return 指令。
     *
     * @param type 方法返回 JVM 类型
     * @return 对应的 `xRETURN` 指令
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    @JvmStatic
    fun makeReturn(type: Type): InsnNode =
        when (type.sort) {
            Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT ->
                InsnNode(
                    Opcodes.IRETURN,
                )
            Type.FLOAT -> InsnNode(Opcodes.FRETURN)
            Type.LONG -> InsnNode(Opcodes.LRETURN)
            Type.DOUBLE -> InsnNode(Opcodes.DRETURN)
            Type.VOID -> InsnNode(Opcodes.RETURN)
            else -> InsnNode(Opcodes.ARETURN)
        }
}
