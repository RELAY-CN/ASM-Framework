/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.api.annotation

/**
 * 注入回调控制信息。
 *
 * 当注入方法以 [CallbackInfo] 作为第一个参数时，注入器会在调用后读取该对象的状态：
 *
 * - 通过 [cancel] 标记取消：用于声明 `cancellable = true` 的 HEAD 注入提前返回（跳过原方法体）
 * - 通过 [setReturnValue] 修改返回值：用于 RETURN 注入在返回前替换结果；在可取消回调中也会标记取消
 *   引用类型返回值可以被显式替换为 `null`
 *
 * 注意：该对象是可变的、非线程安全的，只应在单次注入调用链路内使用。
 *
 * ## 示例
 *
 * ```kotlin
 * @AsmInject(method = "targetMethod()V", target = InjectionPoint.HEAD, cancellable = true)
 * fun onHead(ci: CallbackInfo) {
 *     // 跳过目标方法体
 *     ci.cancel()
 * }
 * ```
 *
 * @param returnValue 初始返回值（用于在返回值可变的注入点中提供默认值）
 * @param cancellable 是否允许 [cancel] 标记取消；为 `false` 时调用 [cancel] 会抛出异常
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
class CallbackInfo
    @JvmOverloads
    constructor(
        private var returnValue: Any? = null,
        private val cancellable: Boolean = false,
    ) {
        private var cancelled = false

        /**
         * 标记取消。
         *
         * 注入器在支持取消的注入点（例如声明 `cancellable = true` 的 HEAD）会根据该标记决定是否提前返回。
         *
         * @throws IllegalStateException 当前注入点未声明可取消时抛出
         */
        fun cancel() {
            check(cancellable) {
                "CallbackInfo is not cancellable; set @AsmInject(cancellable = true) before calling cancel()"
            }
            cancelled = true
        }

        /**
         * 是否已取消。
         */
        fun isCancelled(): Boolean = cancelled

        /**
         * 获取返回值。
         *
         * 该方法会尝试将内部保存的值转换为目标类型；类型不匹配时返回 `null`。
         */
        @Suppress("UNCHECKED_CAST")
        fun <T> getReturnValue(): T? = returnValue as? T

        /**
         * 设置返回值。
         *
         * 该值用于支持“返回值可变”的注入点（例如 RETURN），以及取消分支需要返回特定结果的场景。
         * 当前回调可取消时，设置返回值会同时标记为已取消，使 HEAD 注入提前返回该值。
         * 在 RETURN 注入中，引用类型的 `null` 会作为明确的新返回值写回，而不是被视为未修改。
         *
         * @param value 新的返回值；可以为 `null`
         */
        fun setReturnValue(value: Any?) {
            this.returnValue = value
            if (cancellable) {
                cancelled = true
            }
        }

        companion object {
            /**
             * 创建并立即标记为取消的回调信息。
             */
            @JvmStatic
            fun cancellable(): CallbackInfo = CallbackInfo(cancellable = true).apply { cancel() }

            /**
             * 创建带初始返回值的回调信息。
             */
            @JvmStatic
            fun returnable(returnValue: Any?): CallbackInfo = CallbackInfo(returnValue)
        }
    }

