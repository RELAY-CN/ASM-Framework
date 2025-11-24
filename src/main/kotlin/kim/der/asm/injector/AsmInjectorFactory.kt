/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.injector

import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.data.AsmInfo
import kim.der.asm.injector.impl.*
import java.lang.reflect.Method

/**
 * ASM 注入器工厂
 * 创建不同类型的注入器
 *
 * @author Dr (dr@der.kim)
 */
object AsmInjectorFactory {
    /**
     * 创建注入器
     */
    fun createInjector(
        injectionPoint: InjectionPoint,
        method: Method,
        asmInfo: AsmInfo,
    ): AsmInjector =
        when (injectionPoint) {
            InjectionPoint.HEAD -> HeadInjector(method, asmInfo)
            InjectionPoint.TAIL -> TailInjector(method, asmInfo)
            InjectionPoint.RETURN -> ReturnInjector(method, asmInfo)
            InjectionPoint.INVOKE -> InvokeInjector(method, asmInfo)
            else -> HeadInjector(method, asmInfo) // 默认使用 HEAD
        }

    /**
     * 创建 ModifyArg 注入器
     */
    fun createModifyArgInjector(
        method: Method,
        asmInfo: AsmInfo,
        index: Int,
    ): AsmInjector = ModifyArgInjector(method, asmInfo, index)

    /**
     * 创建 Redirect 注入器
     */
    fun createRedirectInjector(
        method: Method,
        asmInfo: AsmInfo,
        target: String,
    ): AsmInjector = RedirectInjector(method, asmInfo, target)

    /**
     * 创建 Overwrite 注入器
     */
    fun createOverwriteInjector(
        method: Method,
        asmInfo: AsmInfo,
    ): AsmInjector = OverwriteInjector(method, asmInfo)

    /**
     * 创建 Copy 注入器
     */
    fun createCopyInjector(
        method: Method,
        asmInfo: AsmInfo,
    ): CopyInjector = CopyInjector(method, asmInfo)

    /**
     * 创建 ModifyReturnValue 注入器
     */
    fun createModifyReturnValueInjector(
        method: Method,
        asmInfo: AsmInfo,
    ): AsmInjector = ModifyReturnValueInjector(method, asmInfo)

    /**
     * 创建 ModifyConstant 注入器
     */
    fun createModifyConstantInjector(
        method: Method,
        asmInfo: AsmInfo,
        constant: String? = null,
    ): AsmInjector = ModifyConstantInjector(method, asmInfo, constant)
}
