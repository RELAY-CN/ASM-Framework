/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.manager.replace

import kim.der.asm.api.replace.RedirectionReplace
import kim.der.asm.api.replace.RedirectionReplaceManager
import kim.der.asm.redirections.replace.CastRedirectionReplace
import kim.der.asm.redirections.replace.ObjectRedirectionReplace
import kim.der.asm.redirections.replace.def.BasicDataRedirections

/**
 *
 *
 * @date 2023/11/3 20:44
 * @author Dr (dr@der.kim)
 */
@Suppress("UNUSED")
abstract class AbstractRedirectionManagerImpl : RedirectionReplaceManager {
    private val objectRedirectionListener: RedirectionReplace by lazy { ObjectRedirectionReplace(this) }
    private val cast: RedirectionReplace by lazy { CastRedirectionReplace(this) }

    @Throws(Throwable::class)
    override operator fun invoke(
        obj: Any,
        desc: String,
        type: Class<*>,
        vararg args: Any?,
    ): Any? = invoke(desc, type, obj, { getFallback(desc, type) }, *args)

    private fun getFallback(
        desc: String,
        type: Class<*>,
    ): RedirectionReplace =
        if (desc.startsWith(RedirectionReplace.CAST_PREFIX)) {
            // TODO: currently cast redirection looks like this:
            //  <cast> java/lang/String
            //  <init> <cast> java/lang/String
            //  It contains no information about the calling class!
            cast
        } else {
            BasicDataRedirections.fallback(type, objectRedirectionListener)
        }
}
