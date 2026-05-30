/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.redirections.replace.def

import kim.der.asm.api.replace.RedirectionReplace

/**
 * 常用函数重定向默认实现。
 *
 * 当前文件主要提供与时间相关的 [RedirectionReplace] 实现（`System.nanoTime()` / `System.currentTimeMillis()` 等），
 * 供 transformer 在替换调用点时复用。
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
object BasicFunctionRedirections {
    /**
     * 返回 [System.nanoTime] 当前值的替换器。
     */
    val Nanos = RedirectionReplace { _: Any, _: String, _: Class<*>, _: Array<out Any?> -> System.nanoTime() }

    /**
     * 返回 [System.nanoTime] 当前值的替换器。
     *
     * 该成员保留历史命名；需要转换为毫秒值时使用 [ValueClass.NanosToMillisValue]。
     */
    val NanosToMillis = RedirectionReplace { _: Any, _: String, _: Class<*>, _: Array<out Any?> -> System.nanoTime() }

    /**
     * 返回 [System.currentTimeMillis] 当前值的替换器。
     */
    val Millis = RedirectionReplace { _: Any, _: String, _: Class<*>, _: Array<out Any?> -> System.currentTimeMillis() }

    /**
     * 返回当前 Unix 秒级时间戳的替换器。
     */
    val Second = RedirectionReplace { _: Any, _: String, _: Class<*>, _: Array<out Any?> -> System.currentTimeMillis() / 1000 }

    /**
     * 对象形式的时间替换器实现。
     *
     * 这些对象适合需要稳定类名或对象实例引用的调用场景。
     */
    object ValueClass {
        /**
         * 返回 [System.nanoTime] 当前值。
         */
        object NanosValue : RedirectionReplace {
            override fun invoke(
                obj: Any,
                desc: String,
                type: Class<*>,
                args: Array<out Any?>,
            ): Long = System.nanoTime()
        }

        /**
         * 返回 [System.nanoTime] 换算后的毫秒值。
         */
        object NanosToMillisValue : RedirectionReplace {
            override fun invoke(
                obj: Any,
                desc: String,
                type: Class<*>,
                args: Array<out Any?>,
            ): Long = System.nanoTime() / 1000000L
        }

        /**
         * 返回 [System.currentTimeMillis] 当前值。
         */
        object MillisValue : RedirectionReplace {
            override fun invoke(
                obj: Any,
                desc: String,
                type: Class<*>,
                args: Array<out Any?>,
            ): Long = System.currentTimeMillis()
        }

        /**
         * 返回当前 Unix 秒级时间戳。
         */
        object SecondValue : RedirectionReplace {
            override fun invoke(
                obj: Any,
                desc: String,
                type: Class<*>,
                args: Array<out Any?>,
            ): Long = System.currentTimeMillis() / 1000L
        }
    }
}
