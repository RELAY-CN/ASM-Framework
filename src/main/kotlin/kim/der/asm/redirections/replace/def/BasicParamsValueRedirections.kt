/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.redirections.replace.def

import kim.der.asm.api.replace.RedirectionReplace

/**
 * 常用参数返回型重定向默认实现。
 *
 * 提供“直接返回第 N 个参数”的 [RedirectionReplace]，用于快速屏蔽调用副作用或透传参数。
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
object BasicParamsValueRedirections {
    val ReturnFirstParams =
        RedirectionReplace {
            _: Any,
            _: String,
            _: Class<*>,
            args: Array<out Any?>,
            ->
            return@RedirectionReplace args[0]
        }
    val ReturnSecondParams =
        RedirectionReplace {
            _: Any,
            _: String,
            _: Class<*>,
            args: Array<out Any?>,
            ->
            return@RedirectionReplace args[1]
        }

    object ValueClass {
        object ReturnFirstParamsValue : RedirectionReplace {
            override fun invoke(
                obj: Any,
                desc: String,
                type: Class<*>,
                args: Array<out Any?>,
            ): Any? = args[0]
        }

        object ReturnSecondParamsValue : RedirectionReplace {
            override fun invoke(
                obj: Any,
                desc: String,
                type: Class<*>,
                args: Array<out Any?>,
            ): Any? = args[1]
        }
    }
}
