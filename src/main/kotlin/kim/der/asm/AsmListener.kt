/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm

import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.listener.RedirectionListener
import java.lang.reflect.Method

/**
 * ASM 方法监听器。
 *
 * 该适配器把 [RedirectionListener] 的统一调用契约桥接到 ASM 类中的反射方法。
 * 构造时会将目标方法设为可访问，调用时会按目标方法参数数量截取传入参数；
 * 若第一个参数类型为 [CallbackInfo]，会自动创建并传入新的回调对象。
 *
 * 该类本身不保存单次调用状态，但底层 [asmInstance] 与 [method] 的线程安全由 ASM 实现方负责。
 *
 * @param asmInstance ASM 实例
 * @param method 被调用的 ASM 方法
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
class AsmListener(
    private val asmInstance: Any,
    private val method: Method,
) : RedirectionListener {
    init {
        method.isAccessible = true
    }

    /**
     * 调用 ASM 监听方法。
     *
     * 当前实现不使用 [obj] 与 [desc] 做分派；二者仅来自统一监听器契约。
     * 反射调用失败时会包装为 [RuntimeException] 抛出。
     *
     * @param obj 调用所属对象；当前实现不直接使用
     * @param desc 调用点描述符；当前实现不直接使用
     * @param args 传给 ASM 方法的调用参数
     * @throws RuntimeException ASM 方法反射调用失败时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun invoke(
        obj: Any,
        desc: String,
        vararg args: Any?,
    ) {
        val callback = CallbackInfo()
        val parameters = mutableListOf<Any?>()

        // 第一个参数通常是 CallbackInfo（如果方法需要）
        val paramTypes = method.parameterTypes
        if (paramTypes.isNotEmpty() && paramTypes[0] == CallbackInfo::class.java) {
            parameters.add(callback)
            // 检查 @AsmInject 注解的 cancellable 属性
            val injectAnnotation = method.getAnnotation(AsmInject::class.java)
            if (injectAnnotation != null && injectAnnotation.cancellable && callback.isCancelled()) {
                // 如果可取消且已取消，直接返回
                return
            }
        }

        // 添加其他参数
        for (i in args.indices) {
            val argIndex = if (paramTypes.isNotEmpty() && paramTypes[0] == CallbackInfo::class.java) i + 1 else i
            if (argIndex < paramTypes.size) {
                parameters.add(args[i])
            }
        }

        try {
            method.invoke(asmInstance, *parameters.toTypedArray())
        } catch (e: Exception) {
            throw RuntimeException("Failed to invoke asm method: ${method.name}", e)
        }
    }

    companion object {
        /**
         * 为 ASM 实例和方法创建监听器。
         *
         * @param asmInstance ASM 实例
         * @param method 被调用的 ASM 方法
         * @return 新的监听器实例
         *
         * @author Dr (dr@der.kim)
         * @date 2025-11-24
         */
        @JvmStatic
        fun create(
            asmInstance: Any,
            method: Method,
        ): AsmListener = AsmListener(asmInstance, method)
    }
}
