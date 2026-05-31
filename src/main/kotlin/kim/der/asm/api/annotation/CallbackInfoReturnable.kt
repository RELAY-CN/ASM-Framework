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
         * 当前返回值。
         *
         * 该属性等价于 [getTypedReturnValue] 与 [setTypedReturnValue] 的组合，方便 Kotlin handler 使用
         * `callback.value = ...` 写法改写 RETURN 注入的返回值，或在可取消 HEAD 注入中设置提前返回值。
         *
         * 当当前回调可取消时，写入该属性会与 [setReturnValue] 一样自动标记为已取消。
         *
         * @author Dr (dr@der.kim)
         * @date 2026-05-31
         */
        var value: T?
            get() = getTypedReturnValue()
            set(value) {
                setTypedReturnValue(value)
            }

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

        /**
         * 设置带类级泛型标注的返回值。
         *
         * 该方法等价于 [setReturnValue]，但参数类型为 [T]，可让 Kotlin handler 在迁移 Mixin
         * `CallbackInfoReturnable<T>` 写法时获得更直接的类型提示。
         *
         * @param value 新返回值；可以为 `null`
         * @author Dr (dr@der.kim)
         * @date 2026-05-31
         */
        fun setTypedReturnValue(value: T?) {
            setReturnValue(value)
        }

        /**
         * 按 `Boolean` 读取返回值。
         *
         * @return 当前返回值为 [Boolean] 时返回该值，否则返回 `false`
         * @author Dr (dr@der.kim)
         * @date 2026-05-31
         */
        fun getReturnValueZ(): Boolean = getTypedReturnValue() as? Boolean ?: false

        /**
         * 按 `Byte` 读取返回值。
         *
         * @return 当前返回值为 [Number] 时返回其 [Number.toByte]，否则返回 `0`
         * @author Dr (dr@der.kim)
         * @date 2026-05-31
         */
        fun getReturnValueB(): Byte = (getTypedReturnValue() as? Number)?.toByte() ?: 0

        /**
         * 按 `Char` 读取返回值。
         *
         * @return 当前返回值为 [Char] 时返回该值；为 [Number] 时返回数值对应字符，否则返回 `\u0000`
         * @author Dr (dr@der.kim)
         * @date 2026-05-31
         */
        fun getReturnValueC(): Char {
            val value = getTypedReturnValue()
            return when (value) {
                is Char -> value
                is Number -> value.toInt().toChar()
                else -> 0.toChar()
            }
        }

        /**
         * 按 `Double` 读取返回值。
         *
         * @return 当前返回值为 [Number] 时返回其 [Number.toDouble]，否则返回 `0.0`
         * @author Dr (dr@der.kim)
         * @date 2026-05-31
         */
        fun getReturnValueD(): Double = (getTypedReturnValue() as? Number)?.toDouble() ?: 0.0

        /**
         * 按 `Float` 读取返回值。
         *
         * @return 当前返回值为 [Number] 时返回其 [Number.toFloat]，否则返回 `0.0f`
         * @author Dr (dr@der.kim)
         * @date 2026-05-31
         */
        fun getReturnValueF(): Float = (getTypedReturnValue() as? Number)?.toFloat() ?: 0.0f

        /**
         * 按 `Int` 读取返回值。
         *
         * @return 当前返回值为 [Number] 时返回其 [Number.toInt]，否则返回 `0`
         * @author Dr (dr@der.kim)
         * @date 2026-05-31
         */
        fun getReturnValueI(): Int = (getTypedReturnValue() as? Number)?.toInt() ?: 0

        /**
         * 按 `Long` 读取返回值。
         *
         * @return 当前返回值为 [Number] 时返回其 [Number.toLong]，否则返回 `0L`
         * @author Dr (dr@der.kim)
         * @date 2026-05-31
         */
        fun getReturnValueJ(): Long = (getTypedReturnValue() as? Number)?.toLong() ?: 0L

        /**
         * 按 `Short` 读取返回值。
         *
         * @return 当前返回值为 [Number] 时返回其 [Number.toShort]，否则返回 `0`
         * @author Dr (dr@der.kim)
         * @date 2026-05-31
         */
        fun getReturnValueS(): Short = (getTypedReturnValue() as? Number)?.toShort() ?: 0
    }
