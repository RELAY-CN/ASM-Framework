/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.manager.replace

import kim.der.asm.api.replace.RedirectionReplace
import java.util.function.Supplier

class RedirectionManagerImpl : AbstractRedirectionManagerImpl() {
    @Throws(Throwable::class)
    override fun invoke(
        desc: String,
        type: Class<*>,
        obj: Any,
        fallback: Supplier<RedirectionReplace>,
        vararg args: Any?,
    ): Any? {
        val redirection = fallback.get()
        return redirection.invoke(obj, desc, type, *args)
    }
}
