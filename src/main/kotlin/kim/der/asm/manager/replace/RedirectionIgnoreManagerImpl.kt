/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.manager.replace

import kim.der.asm.api.replace.RedirectionReplace
import java.util.function.Supplier

/**
 * 忽略模式重定向替换管理器。
 *
 * 忽略模式不会尝试执行用户注册的替换逻辑，而是直接走 fallback。
 * 该模式用于转换器需要保留调用形态但跳过实际替换副作用的场景。
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
class RedirectionIgnoreManagerImpl : AbstractRedirectionManagerImpl() {
    /**
     * 使用默认 fallback 执行忽略模式替换。
     *
     * @param obj 调用所属对象
     * @param desc 调用点描述符
     * @param type 原调用返回类型
     * @param args 原调用参数
     * @return fallback 返回值；void 调用可返回 `null`
     * @throws Throwable fallback 执行失败时透出
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
    ): Any? = getFallback(desc, type).invoke(obj, desc, type, *args)

    /**
     * 使用调用方提供的 fallback 执行忽略模式替换。
     *
     * @param desc 调用点描述符
     * @param type 原调用返回类型
     * @param obj 调用所属对象
     * @param fallback 未命中显式替换器时使用的延迟默认实现
     * @param args 原调用参数
     * @return fallback 返回值；void 调用可返回 `null`
     * @throws Throwable fallback 执行失败时透出
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
    ): Any? = fallback.get().invoke(obj, desc, type, *args)
}
