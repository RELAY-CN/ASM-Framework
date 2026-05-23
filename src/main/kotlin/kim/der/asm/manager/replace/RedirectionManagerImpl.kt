/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.manager.replace

import kim.der.asm.api.replace.RedirectionReplace
import java.util.function.Supplier

/**
 * 普通重定向替换管理器。
 *
 * 当前实现没有额外注册表，所有调用都会使用调用方传入的 `fallback`。
 * 该类作为默认 manager 保留扩展点，后续可在这里接入按描述符注册的替换策略。
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
class RedirectionManagerImpl : AbstractRedirectionManagerImpl() {
    /**
     * 使用 fallback 执行重定向替换。
     *
     * @param desc 调用点描述符
     * @param type 原调用返回类型
     * @param obj 调用所属对象
     * @param fallback 未命中显式替换器时使用的延迟默认实现
     * @param args 原调用参数
     * @return 替换后的返回值；void 调用可返回 `null`
     * @throws Throwable fallback 或替换器执行失败时透出
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
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
