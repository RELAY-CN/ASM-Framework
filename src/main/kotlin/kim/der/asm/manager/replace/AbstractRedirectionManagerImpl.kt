/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.manager.replace

import kim.der.asm.api.replace.RedirectionReplace
import kim.der.asm.api.replace.RedirectionReplaceManager
import kim.der.asm.redirections.replace.CastRedirectionReplace
import kim.der.asm.redirections.replace.ObjectRedirectionReplace
import kim.der.asm.redirections.replace.def.BasicDataRedirections

/**
 * 重定向替换管理器基类。
 *
 * 提供统一的 fallback 选择逻辑：
 *
 * - 当 `desc` 以 [RedirectionReplace.CAST_PREFIX] 开头时，走 cast 替换分支
 * - 否则根据返回值类型选择 [BasicDataRedirections] 的默认替换实现
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
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

    protected fun getFallback(
        desc: String,
        type: Class<*>,
    ): RedirectionReplace =
        if (desc.startsWith(RedirectionReplace.CAST_PREFIX)) {
            // TODO: 当前 cast 重定向 desc 形如：
            //  <cast> java/lang/String
            //  <init> <cast> java/lang/String
            //  该格式不包含调用方类信息，后续如需做精细化策略需要补齐上下文。
            cast
        } else {
            BasicDataRedirections.fallback(type, objectRedirectionListener)
        }
}
