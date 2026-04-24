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
    val Nanos = RedirectionReplace { _: Any, _: String, _: Class<*>, _: Array<out Any?> -> System.nanoTime() }
    val NanosToMillis = RedirectionReplace { _: Any, _: String, _: Class<*>, _: Array<out Any?> -> System.nanoTime() }
    val Millis = RedirectionReplace { _: Any, _: String, _: Class<*>, _: Array<out Any?> -> System.currentTimeMillis() }
    val Second = RedirectionReplace { _: Any, _: String, _: Class<*>, _: Array<out Any?> -> System.currentTimeMillis() / 1000 }

    object ValueClass {
        object NanosValue : RedirectionReplace {
            override fun invoke(
                obj: Any,
                desc: String,
                type: Class<*>,
                args: Array<out Any?>,
            ): Long = System.nanoTime()
        }

        object NanosToMillisValue : RedirectionReplace {
            override fun invoke(
                obj: Any,
                desc: String,
                type: Class<*>,
                args: Array<out Any?>,
            ): Long = System.nanoTime() / 1000000L
        }

        object MillisValue : RedirectionReplace {
            override fun invoke(
                obj: Any,
                desc: String,
                type: Class<*>,
                args: Array<out Any?>,
            ): Long = System.currentTimeMillis()
        }

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
