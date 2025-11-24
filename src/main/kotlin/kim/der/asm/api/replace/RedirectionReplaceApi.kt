/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.api.replace

import kim.der.asm.manager.replace.RedirectionIgnoreManagerImpl
import kim.der.asm.manager.replace.RedirectionManagerImpl

/**
 * 方法重定向-取代, 取代方法
 *
 * @date 2023/10/22 9:25
 * @author Dr (dr@der.kim)
 */
object RedirectionReplaceApi {
    /**
     *  Not using a ServiceLoader for now, modularized environments with
     *  multiple ClassLoaders cause some issues.
     *
     * @return an implementation of the [RedirectionManager].
     */
    private val redirectionManager: RedirectionReplaceManager = RedirectionManagerImpl()
    private val redirectionIgnoreManager: RedirectionReplaceManager = RedirectionIgnoreManagerImpl()

    /**
     *
     * @see Redirection
     */
    // used by the transformer
    @JvmStatic
    @Suppress("UNUSED")
    @Throws(Throwable::class)
    operator fun invoke(
        obj: Any,
        desc: String,
        type: Class<*>,
        vararg args: Any?,
    ): Any? = redirectionManager.invoke(obj, desc, type, *args)

    // used by the transformer
    @JvmStatic
    @Suppress("UNUSED")
    @Throws(Throwable::class)
    fun invokeIgnore(
        obj: Any,
        desc: String,
        type: Class<*>,
        vararg args: Any?,
    ): Any? = redirectionIgnoreManager.invoke(obj, desc, type, *args)
}
