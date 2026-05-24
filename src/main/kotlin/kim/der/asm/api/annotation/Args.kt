/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.api.annotation

/**
 * 调用参数容器。
 *
 * [ModifyArgs] handler 会接收该容器，用于读取和改写匹配方法调用的整组参数。索引按目标调用
 * 的方法描述符声明顺序计算，不包含实例方法调用的 receiver。
 *
 * ## 类型约束
 *
 * 容器不会在写入时执行类型检查；若写入值与原调用参数类型不兼容，后续字节码恢复调用参数时会抛出
 * [ClassCastException] 或拆箱异常。调用方应只写入与原调用参数兼容的值。
 *
 * @param values 参数数组；容器会直接持有该数组用于就地修改
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
class Args(
    private val values: Array<Any?>,
) {
    /**
     * 返回参数数量。
     *
     * @return 当前调用点的方法参数数量
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    fun size(): Int = values.size

    /**
     * 读取指定位置的参数。
     *
     * @param index 参数索引，从 0 开始
     * @return 指定位置的参数值
     * @throws IndexOutOfBoundsException 当 [index] 不在参数范围内时抛出
     * @throws ClassCastException 当调用方指定的泛型类型与实际值不兼容时抛出
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> get(index: Int): T = values[index] as T

    /**
     * 改写指定位置的参数。
     *
     * @param index 参数索引，从 0 开始
     * @param value 新参数值；必须与原调用参数类型兼容
     * @throws IndexOutOfBoundsException 当 [index] 不在参数范围内时抛出
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    fun set(
        index: Int,
        value: Any?,
    ) {
        values[index] = value
    }

    /**
     * 返回底层参数数组。
     *
     * 该方法主要供注入器生成的字节码读取修改后的参数使用。返回数组为可变数组，调用方修改它会直接影响
     * 当前容器内容。
     *
     * @return 底层参数数组
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    fun toArray(): Array<Any?> = values
}
