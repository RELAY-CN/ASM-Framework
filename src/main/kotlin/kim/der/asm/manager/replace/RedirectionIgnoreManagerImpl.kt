/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.manager.replace

import kim.der.asm.api.replace.RedirectionReplace
import java.util.function.Supplier

class RedirectionIgnoreManagerImpl : AbstractRedirectionManagerImpl() {
    @Throws(Throwable::class)
    override operator fun invoke(
        obj: Any,
        desc: String,
        type: Class<*>,
        vararg args: Any?,
    ): Any? = getFallback(desc, type).invoke(obj, desc, type, *args)

    @Throws(Throwable::class)
    override fun invoke(
        desc: String,
        type: Class<*>,
        obj: Any,
        fallback: Supplier<RedirectionReplace>,
        vararg args: Any?,
    ): Any? = fallback.get().invoke(obj, desc, type, *args)
}
