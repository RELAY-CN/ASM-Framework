/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.api.replace

import kim.der.asm.manager.replace.RedirectionIgnoreManagerImpl
import kim.der.asm.manager.replace.RedirectionManagerImpl

/**
 * 方法重定向替换入口。
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
object RedirectionReplaceApi {
    /**
     * 默认重定向替换管理器。
     *
     * 当前不使用 ServiceLoader：模块化环境/多 ClassLoader 场景下容易出现加载与隔离问题。
     */
    private val redirectionManager: RedirectionReplaceManager = RedirectionManagerImpl()
    private val redirectionIgnoreManager: RedirectionReplaceManager = RedirectionIgnoreManagerImpl()

    /**
     * 执行重定向替换（由 transformer 注入调用点调用）。
     *
     * @param obj 目标对象；静态方法调用场景下可能为 `Class` 常量或占位对象
     * @param desc 调用点描述符（示例：`Lcom/example/Target;methodName(Ljava/lang/String;)V`）
     * @param type 返回值类型
     * @param args 调用参数（按原顺序）
     * @return 替换后的返回值
     * @throws Throwable 由替换实现抛出的异常
     * @see kim.der.asm.api.annotation.Redirect
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

    /**
     * 执行“忽略模式”的重定向替换（由 transformer 注入调用点调用）。
     *
     * @param obj 目标对象；静态方法调用场景下可能为 `Class` 常量或占位对象
     * @param desc 调用点描述符（示例：`Lcom/example/Target;methodName(Ljava/lang/String;)V`）
     * @param type 返回值类型
     * @param args 调用参数（按原顺序）
     * @return 替换后的返回值
     * @throws Throwable 由替换实现抛出的异常
     */
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
