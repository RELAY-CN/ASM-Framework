/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.api.annotation

/**
 * 调用参数容器。
 *
 * [ModifyArgs] handler 会接收该容器，用于读取和改写匹配方法调用的整组参数。索引按目标调用
 * 的方法描述符声明顺序计算，不包含实例方法调用的 receiver。
 * Kotlin handler 可用 [get] / [set]，也可用等价的下标语法 `args[index]` 与 `args[index] = value`。
 * 当需要一次替换整组调用参数时，可使用 [setAll] 保证参数数量匹配后再批量写回。
 * 也可以通过 [size] 属性、`for (value in args)` 或 `joinToString` / `map` 等 [Iterable] 扩展读取参数。
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
) : Iterable<Any?> {
    /**
     * 当前参数数量。
     *
     * 该属性与 [size] 方法返回值一致，方便 Kotlin handler 使用属性语法读取参数数量。
     *
     * @return 当前调用点的方法参数数量
     * @author Dr (dr@der.kim)
     * @date 2026-05-31
     */
    val size: Int
        get() = values.size

    /**
     * 返回参数数量。
     *
     * @return 当前调用点的方法参数数量
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    fun size(): Int = size

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
    operator fun <T> get(index: Int): T = values[index] as T

    /**
     * 改写指定位置的参数。
     *
     * Kotlin handler 可用 `args[index] = value` 调用该方法；写入仍直接作用于底层参数数组。
     *
     * @param index 参数索引，从 0 开始
     * @param value 新参数值；必须与原调用参数类型兼容
     * @throws IndexOutOfBoundsException 当 [index] 不在参数范围内时抛出
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    operator fun set(
        index: Int,
        value: Any?,
    ) {
        values[index] = value
    }

    /**
     * 批量改写整组参数。
     *
     * 该方法会先校验 [newValues] 数量必须与当前参数数量一致，再把所有新值写入底层参数数组。
     * 因此数量不匹配时不会发生部分写入。写入值仍必须与原调用参数类型兼容，类型检查会在后续恢复调用参数时发生。
     *
     * @param newValues 新参数组；数量必须等于 [size]
     * @throws IllegalArgumentException 当 [newValues] 数量与当前参数数量不一致时抛出
     * @author Dr (dr@der.kim)
     * @date 2026-05-31
     */
    fun setAll(vararg newValues: Any?) {
        require(newValues.size == values.size) {
            "Args.setAll expects ${values.size} value(s), actual ${newValues.size}"
        }
        newValues.copyInto(values)
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

    /**
     * 按当前顺序遍历参数。
     *
     * 返回的迭代器直接来自底层数组；遍历期间通过 [set] 或下标语法修改参数时，后续读取会反映数组当前值。
     *
     * @return 当前参数数组的迭代器
     * @author Dr (dr@der.kim)
     * @date 2026-05-31
     */
    override fun iterator(): Iterator<Any?> = values.iterator()
}
