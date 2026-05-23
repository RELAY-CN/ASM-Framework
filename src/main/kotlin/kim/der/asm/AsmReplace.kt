/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm

import kim.der.asm.api.replace.RedirectionReplace
import java.lang.reflect.Method

/**
 * ASM 方法替换器。
 *
 * 该适配器把 [RedirectionReplace] 的统一调用契约桥接到 ASM 类中的反射方法。
 * 构造时会将目标方法设为可访问，调用时会按目标方法参数数量截取传入参数，
 * 并使用反射方法的返回值作为替换结果。
 *
 * 该类本身不保存单次调用状态，但底层 [asmInstance] 与 [method] 的线程安全由 ASM 实现方负责。
 *
 * @param asmInstance ASM 实例
 * @param method 被调用的 ASM 替换方法
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
class AsmReplace(
    private val asmInstance: Any,
    private val method: Method,
) : RedirectionReplace {
    init {
        method.isAccessible = true
    }

    /**
     * 调用 ASM 替换方法。
     *
     * 当前实现不使用 [obj]、[desc] 与 [type] 做分派；三者仅来自统一替换器契约。
     * 反射调用失败时会包装为 [RuntimeException] 抛出。
     *
     * @param obj 调用所属对象；当前实现不直接使用
     * @param desc 调用点描述符；当前实现不直接使用
     * @param type 原调用返回类型；当前实现不直接使用
     * @param args 传给 ASM 方法的调用参数
     * @return ASM 方法返回值
     * @throws RuntimeException ASM 方法反射调用失败时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
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
         * 为 ASM 实例和方法创建替换器。
         *
         * @param asmInstance ASM 实例
         * @param method 被调用的 ASM 替换方法
         * @return 新的替换器实例
         *
         * @author Dr (dr@der.kim)
         * @date 2025-11-24
         */
        @JvmStatic
        fun create(
            asmInstance: Any,
            method: Method,
        ): AsmReplace = AsmReplace(asmInstance, method)
    }
}
