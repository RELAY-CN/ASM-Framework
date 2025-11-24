/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm

import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.listener.RedirectionListener
import java.lang.reflect.Method

/**
 * ASM 方法监听器
 * 用于调用 ASM 类中的方法
 *
 * @author Dr (dr@der.kim)
 */
class AsmListener(
    private val asmInstance: Any,
    private val method: Method,
) : RedirectionListener {
    init {
        method.isAccessible = true
    }

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
         * 为 ASM 实例和方法创建监听器
         */
        @JvmStatic
        fun create(
            asmInstance: Any,
            method: Method,
        ): AsmListener = AsmListener(asmInstance, method)
    }
}
