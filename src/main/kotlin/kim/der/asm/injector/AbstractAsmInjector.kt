/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.injector

import kim.der.asm.data.AsmInfo
import java.lang.reflect.Method

/**
 * ASM 注入器基类
 * 提供通用的 ASM 方法调用和实例获取功能
 *
 * @author Dr (dr@der.kim)
 */
abstract class AbstractAsmInjector(
    protected val asmMethod: Method,
    protected val asmInfo: AsmInfo,
) : AsmInjector {
    init {
        asmMethod.isAccessible = true
    }

    /**
     * 获取 ASM 实例
     * 优先尝试获取 Kotlin object 的 INSTANCE 字段，否则创建新实例
     */
    protected fun getAsmInstance(): Any =
        try {
            val instanceField = asmInfo.asmClass.getDeclaredField("INSTANCE")
            instanceField.isAccessible = true
            instanceField.get(null) ?: throw RuntimeException("INSTANCE is null")
        } catch (e: NoSuchFieldException) {
            // 如果没有 INSTANCE，尝试创建新实例
            try {
                asmInfo.asmClass.getDeclaredConstructor().newInstance()
            } catch (e2: Exception) {
                throw RuntimeException("Cannot create instance of asm class: ${asmInfo.asmClass.name}", e2)
            }
        }

    /**
     * 尝试获取 Kotlin object 的 INSTANCE 字段
     */
    private fun tryGetKotlinObjectInstance(): Any? =
        try {
            val instanceField = asmInfo.asmClass.getDeclaredField("INSTANCE")
            instanceField.isAccessible = true
            instanceField.get(null)
        } catch (e: NoSuchFieldException) {
            null
        }

    /**
     * 检查是否是 Kotlin object（有 INSTANCE 字段）
     */
    protected fun isKotlinObject(): Boolean = tryGetKotlinObjectInstance() != null
}
