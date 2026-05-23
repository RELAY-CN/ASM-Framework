/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.api.replace

import java.util.function.Supplier

/**
 * 重定向替换管理器。
 *
 * 管理器在 [RedirectionReplace] 的基础上增加按调用描述符查找替换实现的能力。
 * 调用方可提供 `fallback`，用于在管理器没有显式注册替换器时延迟创建默认替换逻辑。
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
interface RedirectionReplaceManager : RedirectionReplace {
    /**
     * 按调用点描述符执行替换。
     *
     * @param desc 调用点描述符，格式为 `Lowner;name(desc)return`
     * @param type 原调用返回类型
     * @param obj 调用所属对象；静态调用场景下可能为占位对象
     * @param fallback 未命中显式替换器时使用的延迟默认实现
     * @param args 原调用参数，按调用栈顺序传入
     * @return 替换后的返回值；void 调用可返回 `null`
     * @throws Throwable 替换逻辑或默认实现执行失败时透出给调用方
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    @Throws(Throwable::class)
    operator fun invoke(
        desc: String,
        type: Class<*>,
        obj: Any,
        fallback: Supplier<RedirectionReplace>,
        vararg args: Any?,
    ): Any?
}
