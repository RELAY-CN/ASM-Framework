/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.injector.impl

import kim.der.asm.api.replace.RedirectionReplace
import kim.der.asm.api.replace.RedirectionReplaceManager

/**
 * 方法重定向替换入口。
 *
 * 该对象的方法签名是转换器注入字节码时直接引用的运行期 ABI，方法名和描述符需要与
 * [RedirectionReplace] 中的桥接常量保持一致。调用方不应直接替换内部 manager；
 * 如需扩展策略，应通过 manager 实现或上层注册机制完成。
 *
 * ## ABI 约束
 *
 * [invoke] 与 [invokeIgnore] 会被转换后的目标类直接调用，属于运行期兼容契约。
 * 修改方法名、参数顺序、返回类型或 JVM 描述符会破坏已生成字节码，应同步更新所有注入器和桥接常量。
 *
 * ## 忽略模式
 *
 * 普通 [invoke] 使用默认重定向管理器。忽略模式 [invokeIgnore] 使用忽略管理器，
 * 用于全方法替换等需要保留调用形态但跳过用户替换分派的场景。
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
object RedirectionReplaceApi {
    /**
     * 默认重定向替换管理器。
     *
     * 当前不使用 ServiceLoader：模块化环境/多 ClassLoader 场景下容易出现加载与隔离问题。
     * 若后续需要外部注册，应优先通过显式注册入口设计类加载器边界，而不是在这里隐式扫描。
     *
     * @author Dr (dr@der.kim)
     * @date 2026-05-31
     */
    private val redirectionManager: RedirectionReplaceManager = RedirectionManagerImpl()

    /**
     * 忽略模式重定向替换管理器。
     *
     * 该管理器只用于 [invokeIgnore]，用于全方法替换链路中保留调用形态但跳过普通用户替换分派。
     *
     * @author Dr (dr@der.kim)
     * @date 2026-05-31
     */
    private val redirectionIgnoreManager: RedirectionReplaceManager = RedirectionIgnoreManagerImpl()

    /**
     * 执行重定向替换（由 transformer 注入调用点调用）。
     *
     * 该方法是普通重定向桥接入口，会把调用点上下文交给默认 [RedirectionReplaceManager]。
     * 调用方通常不需要直接调用它；测试或低层扩展直接调用时，必须使用与转换器一致的描述符格式。
     *
     * @param obj 目标对象；静态方法调用场景下可能为 `Class` 常量或占位对象
     * @param desc 调用点描述符（示例：`Lcom/example/Target;methodName(Ljava/lang/String;)V`）
     * @param type 返回值类型
     * @param args 调用参数（按原顺序）
     * @return 替换后的返回值
     * @throws Throwable 由替换实现抛出的异常
     * @see kim.der.asm.api.annotation.Redirect
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
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
     * 忽略模式不会进行用户替换分派，只使用忽略管理器提供的默认替换路径。
     * 该入口主要服务全方法替换链路，避免目标方法体内的普通调用继续触发额外重定向副作用。
     *
     * @param obj 目标对象；静态方法调用场景下可能为 `Class` 常量或占位对象
     * @param desc 调用点描述符（示例：`Lcom/example/Target;methodName(Ljava/lang/String;)V`）
     * @param type 返回值类型
     * @param args 调用参数（按原顺序）
     * @return 替换后的返回值
     * @throws Throwable 由替换实现抛出的异常
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
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
