/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.redirections.replace

import kim.der.asm.api.replace.RedirectionReplace
import kim.der.asm.api.replace.RedirectionReplaceManager

class CastRedirectionReplace(
    private val manager: RedirectionReplaceManager,
) : RedirectionReplace {
    @Throws(Throwable::class)
    override fun invoke(
        obj: Any,
        desc: String,
        type: Class<*>,
        vararg args: Any?,
    ): Any? =
        if (type.isInstance(obj)) {
            obj
        } else {
            manager.invoke(obj, "<init> $desc", type, *args)
        }
}
