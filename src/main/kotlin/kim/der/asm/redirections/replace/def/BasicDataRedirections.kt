/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.redirections.replace.def

import kim.der.asm.api.replace.RedirectionReplace

/**
 * 常用数据默认重定向实现。
 *
 * 该对象提供基础类型、字符串、空值以及对象标识比较相关的 [RedirectionReplace]。
 * 框架在未显式指定替换器时会按目标返回类型选择这些默认实现，避免被重定向的调用点继续执行原逻辑。
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
object BasicDataRedirections {
    /**
     * 固定返回 `null` 的替换器，用于 `void` 或引用类型的空值兜底。
     */
    @JvmField
    val NULL = RedirectionReplace.of(null)

    /**
     * 固定返回 `false` 的 boolean 替换器。
     */
    @JvmField
    val BOOLEANF = RedirectionReplace.of(false)

    /**
     * 固定返回 `0.toByte()` 的 byte 替换器。
     */
    @JvmField
    val BYTE = RedirectionReplace.of(0.toByte())

    /**
     * 固定返回 `0.toShort()` 的 short 替换器。
     */
    @JvmField
    val SHORT = RedirectionReplace.of(0.toShort())

    /**
     * 固定返回 `0` 的 int 替换器。
     */
    @JvmField
    val INT = RedirectionReplace.of(0)

    /**
     * 固定返回 `0L` 的 long 替换器。
     */
    @JvmField
    val LONG = RedirectionReplace.of(0L)

    /**
     * 固定返回 `0.0f` 的 float 替换器。
     */
    @JvmField
    val FLOAT = RedirectionReplace.of(0.0f)

    /**
     * 固定返回 `0.0` 的 double 替换器。
     */
    @JvmField
    val DOUBLE = RedirectionReplace.of(0.0)

    /**
     * 固定返回 `'a'` 的 char 替换器。
     */
    @JvmField
    val CHAR = RedirectionReplace.of('a')

    /**
     * 固定返回空字符串的替换器。
     */
    @JvmField
    val STRING = RedirectionReplace.of("")

    /**
     * 使用引用相等性比较前两个参数的替换器。
     */
    @JvmField
    val EQUALS = RedirectionReplace { _: Any, _: String, _: Class<*>, args: Array<out Any?> -> args[0] === args[1] }

    /**
     * 返回第一个参数标识哈希值的替换器。
     */
    @JvmField
    val HASHCODE = RedirectionReplace { _: Any, _: String, _: Class<*>, args: Array<out Any?> -> System.identityHashCode(args[0]) }

    /**
     * 根据返回类型选择默认替换器。
     *
     * 基础类型、`String` 与 `CharSequence` 会返回本对象中的固定替换器；其他引用类型返回调用方提供的兜底替换器。
     *
     * @param type 目标调用返回类型；可为空
     * @param redirectionReplace 其他引用类型使用的兜底替换器
     * @return 与 [type] 对应的默认替换器
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    @JvmStatic
    fun fallback(
        type: Class<*>?,
        redirectionReplace: RedirectionReplace,
    ): RedirectionReplace = when (type) {
        Void.TYPE -> NULL
        Boolean::class.javaPrimitiveType -> BOOLEANF
        Byte::class.javaPrimitiveType -> BYTE
        Short::class.javaPrimitiveType -> SHORT
        Int::class.javaPrimitiveType -> INT
        Long::class.javaPrimitiveType -> LONG
        Float::class.javaPrimitiveType -> FLOAT
        Double::class.javaPrimitiveType -> DOUBLE
        Char::class.javaPrimitiveType -> CHAR
        String::class.java -> STRING
        CharSequence::class.java -> EQUALS
        else -> redirectionReplace
    }
}
