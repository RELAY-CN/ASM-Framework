/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.injector

import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.data.AsmInfo
import kim.der.asm.injector.impl.*
import java.lang.reflect.Method

/**
 * ASM 注入器工厂。
 *
 * 根据注解解析阶段得到的注入点与参数，创建对应的 [AsmInjector] 实现。
 * 未显式支持的 [InjectionPoint] 当前会回退为 HEAD 注入，这是兼容旧注解枚举的行为。
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
object AsmInjectorFactory {
    /**
     * 创建普通注入器。
     *
     * @param injectionPoint 注入点类型
     * @param method ASM 方法
     * @param asmInfo ASM 注册信息
     * @return 对应注入点的注入器；未知注入点回退为 [HeadInjector]
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
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
     * 创建 ModifyArg 注入器。
     *
     * @param method ASM 方法
     * @param asmInfo ASM 注册信息
     * @param index 要修改的参数索引
     * @return ModifyArg 注入器
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    fun createModifyArgInjector(
        method: Method,
        asmInfo: AsmInfo,
        index: Int,
    ): AsmInjector = ModifyArgInjector(method, asmInfo, index)

    /**
     * 创建 Redirect 注入器。
     *
     * @param method ASM 方法
     * @param asmInfo ASM 注册信息
     * @param target 目标调用签名
     * @return Redirect 注入器
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    fun createRedirectInjector(
        method: Method,
        asmInfo: AsmInfo,
        target: String,
    ): AsmInjector = RedirectInjector(method, asmInfo, target)

    /**
     * 创建 Overwrite 注入器。
     *
     * @param method ASM 方法
     * @param asmInfo ASM 注册信息
     * @return Overwrite 注入器
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    fun createOverwriteInjector(
        method: Method,
        asmInfo: AsmInfo,
    ): AsmInjector = OverwriteInjector(method, asmInfo)

    /**
     * 创建 Copy 注入器。
     *
     * @param method ASM 方法
     * @param asmInfo ASM 注册信息
     * @return Copy 注入器
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    fun createCopyInjector(
        method: Method,
        asmInfo: AsmInfo,
    ): CopyInjector = CopyInjector(method, asmInfo)

    /**
     * 创建 ModifyReturnValue 注入器。
     *
     * @param method ASM 方法
     * @param asmInfo ASM 注册信息
     * @return ModifyReturnValue 注入器
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    fun createModifyReturnValueInjector(
        method: Method,
        asmInfo: AsmInfo,
    ): AsmInjector = ModifyReturnValueInjector(method, asmInfo)

    /**
     * 创建 ModifyConstant 注入器。
     *
     * @param method ASM 方法
     * @param asmInfo ASM 注册信息
     * @param constant 常量值过滤；为 `null` 表示仅按类型匹配
     * @return ModifyConstant 注入器
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    fun createModifyConstantInjector(
        method: Method,
        asmInfo: AsmInfo,
        constant: String? = null,
    ): AsmInjector = ModifyConstantInjector(method, asmInfo, constant)
}
