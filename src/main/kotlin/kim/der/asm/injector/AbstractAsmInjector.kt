/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.injector

import kim.der.asm.data.AsmInfo
import java.lang.reflect.Method

/**
 * ASM 注入器基类。
 *
 * 持有当前 ASM 方法与注册信息，并在构造时放开反射访问权限。
 * 子类可复用实例获取逻辑处理 Kotlin `object` 与普通类两种 ASM 声明方式。
 *
 * @param asmMethod 当前 ASM 方法
 * @param asmInfo 当前 ASM 类的注册信息
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
abstract class AbstractAsmInjector(
    protected val asmMethod: Method,
    protected val asmInfo: AsmInfo,
) : AsmInjector {
    init {
        asmMethod.isAccessible = true
    }

    /**
     * 获取 ASM 实例。
     *
     * 优先尝试获取 Kotlin `object` 的 `INSTANCE` 字段；若不存在，则调用无参构造器创建新实例。
     *
     * @return ASM 实例
     * @throws RuntimeException `INSTANCE` 为空，或普通类无法通过无参构造器创建实例时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
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
     * 尝试获取 Kotlin `object` 的 `INSTANCE` 字段。
     *
     * @return object 单例实例；不存在 `INSTANCE` 字段时返回 `null`
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
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
     * 检查当前 ASM 类是否是 Kotlin `object`。
     *
     * @return 能读取到非空 `INSTANCE` 字段时返回 `true`
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    protected fun isKotlinObject(): Boolean = tryGetKotlinObjectInstance() != null
}
