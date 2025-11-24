/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.api.annotation

/**
 * 回调信息类，类似 Mixin 的 CallbackInfo
 * 支持取消执行和返回值修改，用于在注入的方法中控制目标方法的执行流程
 *
 * @param returnValue 返回值（用于修改方法的返回值）
 * @author Dr (dr@der.kim)
 */
class CallbackInfo @JvmOverloads constructor(
    private var returnValue: Any? = null,
) {
    private var cancelled = false

    /**
     * 取消方法的继续执行（如果可取消）
     */
    fun cancel() {
        cancelled = true
    }

    /**
     * 检查是否已取消
     */
    fun isCancelled(): Boolean = cancelled

    /**
     * 获取返回值
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> getReturnValue(): T? = returnValue as? T

    /**
     * 设置返回值（仅在支持返回值修改的注入点有效）
     */
    fun setReturnValue(value: Any?) {
        this.returnValue = value
    }

    companion object {
        /**
         * 创建可取消的回调信息
         */
        @JvmStatic
        fun cancellable(): CallbackInfo = CallbackInfo().apply { cancel() }

        /**
         * 创建带返回值的回调信息
         */
        @JvmStatic
        fun returnable(returnValue: Any?): CallbackInfo = CallbackInfo(returnValue)
    }
} 
