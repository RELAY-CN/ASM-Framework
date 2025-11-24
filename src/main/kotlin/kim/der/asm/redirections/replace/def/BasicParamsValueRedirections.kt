/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.redirections.replace.def

import kim.der.asm.api.replace.RedirectionReplace

/**
 *
 *
 * @date 2023/12/25 15:15
 * @author Dr (dr@der.kim)
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
