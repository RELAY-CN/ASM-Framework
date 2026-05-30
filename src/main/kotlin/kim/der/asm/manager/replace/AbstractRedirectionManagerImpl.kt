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

    /**
     * 执行默认重定向替换。
     *
     * 该重载会先根据 [desc] 与 [type] 选择 fallback，再委托到带 [java.util.function.Supplier] 的重载。
     *
     * @param obj 调用所属对象
     * @param desc 调用点描述符
     * @param type 原调用返回类型
     * @param args 原调用参数
     * @return 替换后的返回值；void 调用可返回 `null`
     * @throws Throwable fallback 或替换器执行失败时透出
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    @Throws(Throwable::class)
    override operator fun invoke(
        obj: Any,
        desc: String,
        type: Class<*>,
        vararg args: Any?,
    ): Any? = invoke(desc, type, obj, { getFallback(desc, type) }, *args)

    /**
     * 按描述符与返回类型选择默认替换器。
     *
     * cast 描述符走专用类型转换替换器；其他调用按返回类型从 [BasicDataRedirections] 选择默认值或对象替换器。
     *
     * @param desc 调用点描述符
     * @param type 原调用返回类型
     * @return 可用于该调用点的 fallback 替换器
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    protected fun getFallback(
        desc: String,
        type: Class<*>,
    ): RedirectionReplace =
        if (desc.startsWith(RedirectionReplace.CAST_PREFIX)) {
            // 当前 cast 重定向 desc 形如：
            //  <cast> java/lang/String
            //  <init> <cast> java/lang/String
            //  该格式不包含调用方类信息；如需做精细化策略，需要先扩展描述符上下文。
            cast
        } else {
            BasicDataRedirections.fallback(type, objectRedirectionListener)
        }
}
