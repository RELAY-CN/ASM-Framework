/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.api.annotation

/**
 * 带目标返回值类型的注入回调控制信息。
 *
 * 该类型用于非 `void` 目标方法的普通 `@AsmInject` handler 首参，语义与 [CallbackInfo] 一致，
 * 但通过泛型把目标方法返回类型标注在 handler 签名中，使 RETURN 注入和可取消 HEAD 注入更接近 Mixin 的
 * `CallbackInfoReturnable<T>` 写法。
 *
 * ## 示例
 *
 * ```kotlin
 * @AsmInject(method = "value()Ljava/lang/String;", target = InjectionPoint.RETURN)
 * fun onReturn(callback: CallbackInfoReturnable<String>) {
 *     val original: String? = callback.getReturnValue()
 *     callback.setReturnValue("$original patched")
 * }
 * ```
 *
 * @param T 目标方法返回值类型
 * @param returnValue 初始返回值；RETURN 注入会在 handler 调用前预置原始返回值
 * @param cancellable 是否允许 [cancel] 标记取消；为 `false` 时调用 [cancel] 会抛出异常
 * @author Dr (dr@der.kim)
 * @date 2026-05-31
 */
class CallbackInfoReturnable<T>
    @JvmOverloads
    constructor(
        returnValue: T? = null,
        cancellable: Boolean = false,
    ) : CallbackInfo(returnValue, cancellable) {
        /**
         * 获取带类级泛型标注的返回值。
         *
         * Kotlin 调用 [getReturnValue] 时通常可以通过接收变量类型推断返回值类型；当没有足够上下文时，
         * 可以使用该方法避免在调用点重复写出泛型。
         *
         * @return 当前保存的返回值；类型不匹配或值为 `null` 时返回 `null`
         * @author Dr (dr@der.kim)
         * @date 2026-05-31
         */
        fun getTypedReturnValue(): T? = getReturnValue()
    }
