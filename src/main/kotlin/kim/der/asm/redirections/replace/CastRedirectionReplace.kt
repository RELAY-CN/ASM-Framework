/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.redirections.replace

import kim.der.asm.api.replace.RedirectionReplace
import kim.der.asm.api.replace.RedirectionReplaceManager

/**
 * 类型转换默认替换器。
 *
 * 当原对象已经是目标类型实例时直接返回原对象；否则委托 [manager] 使用构造语义生成目标类型的兜底值。
 *
 * @param manager 负责处理兜底构造调用的重定向替换管理器
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
class CastRedirectionReplace(
    private val manager: RedirectionReplaceManager,
) : RedirectionReplace {
    /**
     * 执行类型转换替换。
     *
     * @param obj 原始对象
     * @param desc 类型转换调用点描述符
     * @param type 目标类型
     * @param args 原调用参数
     * @return 可作为目标类型返回值使用的对象，或由 [manager] 返回的兜底值
     * @throws Throwable 兜底替换器执行失败时透出
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
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
