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
    /**
     * 返回原调用第一个参数的替换器。
     *
     * 调用点没有参数时会因访问 `args[0]` 失败而抛出异常。
     */
    val ReturnFirstParams =
        RedirectionReplace {
            _: Any,
            _: String,
            _: Class<*>,
            args: Array<out Any?>,
            ->
            return@RedirectionReplace args[0]
        }

    /**
     * 返回原调用第二个参数的替换器。
     *
     * 调用点少于两个参数时会因访问 `args[1]` 失败而抛出异常。
     */
    val ReturnSecondParams =
        RedirectionReplace {
            _: Any,
            _: String,
            _: Class<*>,
            args: Array<out Any?>,
            ->
            return@RedirectionReplace args[1]
        }

    /**
     * 对象形式的参数返回型替换器实现。
     *
     * 这些对象适合需要稳定类名或对象实例引用的调用场景。
     */
    object ValueClass {
        /**
         * 返回原调用第一个参数。
         */
        object ReturnFirstParamsValue : RedirectionReplace {
            override fun invoke(
                obj: Any,
                desc: String,
                type: Class<*>,
                args: Array<out Any?>,
            ): Any? = args[0]
        }

        /**
         * 返回原调用第二个参数。
         */
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
