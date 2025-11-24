/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.api.replace

import java.util.function.Supplier

interface RedirectionReplaceManager : RedirectionReplace {
    @Throws(Throwable::class)
    operator fun invoke(
        desc: String,
        type: Class<*>,
        obj: Any,
        fallback: Supplier<RedirectionReplace>,
        vararg args: Any?,
    ): Any?
}
