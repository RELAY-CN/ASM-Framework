/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm

import kim.der.asm.api.replace.RedirectionReplace
import java.lang.reflect.Method

/**
 * ASM 方法替换器
 * 用于替换方法调用
 *
 * @author Dr (dr@der.kim)
 */
class AsmReplace(
    private val asmInstance: Any,
    private val method: Method,
) : RedirectionReplace {
    init {
        method.isAccessible = true
    }

    override fun invoke(
        obj: Any,
        desc: String,
        type: Class<*>,
        vararg args: Any?,
    ): Any? {
        val parameters = mutableListOf<Any?>()

        // 添加参数
        val paramTypes = method.parameterTypes
        for (i in args.indices) {
            if (i < paramTypes.size) {
                parameters.add(args[i])
            }
        }

        try {
            return method.invoke(asmInstance, *parameters.toTypedArray())
        } catch (e: Exception) {
            throw RuntimeException("Failed to invoke asm replace method: ${method.name}", e)
        }
    }

    companion object {
        /**
         * 为 ASM 实例和方法创建替换器
         */
        @JvmStatic
        fun create(
            asmInstance: Any,
            method: Method,
        ): AsmReplace = AsmReplace(asmInstance, method)
    }
}
