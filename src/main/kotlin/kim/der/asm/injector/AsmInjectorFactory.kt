/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.injector

import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.At
import kim.der.asm.data.AsmInfo
import kim.der.asm.injector.impl.*
import java.lang.reflect.Method

/**
 * ASM 注入器工厂。
 *
 * 根据注解解析阶段得到的注入点与参数，创建对应的 [AsmInjector] 实现。
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
     * @return 对应注入点的注入器
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
            InjectionPoint.INVOKE_ASSIGN -> InvokeInjector(method, asmInfo)
            InjectionPoint.FIELD,
            InjectionPoint.FIELD_ASSIGN,
            InjectionPoint.NEW,
            InjectionPoint.CAST,
            InjectionPoint.THROW,
            -> InstructionPointInjector(method, asmInfo, injectionPoint)
            InjectionPoint.LOAD -> throw IllegalArgumentException(
                "InjectionPoint.LOAD is supported only by @ModifyVariable; ordinary @AsmInject does not receive local variable values",
            )
            InjectionPoint.STORE -> throw IllegalArgumentException(
                "InjectionPoint.STORE is supported only by @ModifyVariable; ordinary @AsmInject does not receive local variable values",
            )
        }

    /**
     * 创建 ModifyArg 注入器。
     *
     * @param method ASM 方法
     * @param asmInfo ASM 注册信息
     * @param index 要修改的参数索引
     * @param at 调用点定位；默认 HEAD 时保持入口参数改写语义
     * @param ordinal 匹配调用点序号；负数表示处理全部匹配调用点
     * @return ModifyArg 注入器
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    fun createModifyArgInjector(
        method: Method,
        asmInfo: AsmInfo,
        index: Int,
        at: At = At(),
        ordinal: Int = -1,
    ): AsmInjector = ModifyArgInjector(method, asmInfo, index, at, ordinal)

    /**
     * 创建 ModifyArgs 注入器。
     *
     * @param method ASM 方法
     * @param asmInfo ASM 注册信息
     * @param at 调用点定位；当前仅支持 INVOKE
     * @param ordinal 匹配调用点序号；负数表示处理全部匹配调用点
     * @return ModifyArgs 注入器
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    fun createModifyArgsInjector(
        method: Method,
        asmInfo: AsmInfo,
        at: At,
        ordinal: Int = -1,
    ): AsmInjector = ModifyArgsInjector(method, asmInfo, at, ordinal)

    /**
     * 创建 ModifyReceiver 注入器。
     *
     * @param method ASM 方法
     * @param asmInfo ASM 注册信息
     * @param at 调用点定位；当前仅支持 INVOKE
     * @param ordinal 匹配调用点序号；负数表示处理全部匹配调用点
     * @return ModifyReceiver 注入器
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    fun createModifyReceiverInjector(
        method: Method,
        asmInfo: AsmInfo,
        at: At,
        ordinal: Int = -1,
    ): AsmInjector = ModifyReceiverInjector(method, asmInfo, at, ordinal)

    /**
     * 创建 WrapOperation 注入器。
     *
     * @param method ASM 方法
     * @param asmInfo ASM 注册信息
     * @param at 调用点定位；当前仅支持 INVOKE
     * @param ordinal 匹配调用点序号；负数表示处理全部匹配调用点
     * @return WrapOperation 注入器
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    fun createWrapOperationInjector(
        method: Method,
        asmInfo: AsmInfo,
        at: At,
        ordinal: Int = -1,
    ): AsmInjector = WrapOperationInjector(method, asmInfo, at, ordinal)

    /**
     * 创建 WrapWithCondition 注入器。
     *
     * @param method ASM 方法
     * @param asmInfo ASM 注册信息
     * @param at 调用点定位；当前支持 INVOKE 与 FIELD_ASSIGN
     * @param ordinal 匹配点序号；负数表示处理全部匹配点
     * @return WrapWithCondition 注入器
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    fun createWrapWithConditionInjector(
        method: Method,
        asmInfo: AsmInfo,
        at: At,
        ordinal: Int = -1,
    ): AsmInjector = WrapWithConditionInjector(method, asmInfo, at, ordinal)

    /**
     * 创建 ModifyExpressionValue 注入器。
     *
     * @param method ASM 方法
     * @param asmInfo ASM 注册信息
     * @param at 表达式定位；当前支持 INVOKE、INVOKE_ASSIGN 与 FIELD
     * @param ordinal 匹配调用点序号；负数表示处理全部匹配调用点
     * @return ModifyExpressionValue 注入器
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    fun createModifyExpressionValueInjector(
        method: Method,
        asmInfo: AsmInfo,
        at: At,
        ordinal: Int = -1,
    ): AsmInjector = ModifyExpressionValueInjector(method, asmInfo, at, ordinal)

    /**
     * 创建 ModifyVariable 注入器。
     *
     * @param method ASM 方法
     * @param asmInfo ASM 注册信息
     * @param injectionPoint 修改位置
     * @param index 要修改的局部变量槽位索引
     * @param ordinal 未指定槽位索引时，同类型入口参数、读取点或写入点的序号
     * @return ModifyVariable 注入器
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    fun createModifyVariableInjector(
        method: Method,
        asmInfo: AsmInfo,
        injectionPoint: InjectionPoint,
        index: Int,
        ordinal: Int,
    ): AsmInjector = ModifyVariableInjector(method, asmInfo, injectionPoint, index, ordinal)

    /**
     * 创建 Redirect 注入器。
     *
     * @param method ASM 方法
     * @param asmInfo ASM 注册信息
     * @param target 目标调用签名
     * @param injectionPoint Redirect 的定位点类型
     * @param ordinal 匹配调用点序号；负数表示处理全部匹配调用点
     * @param args 调用点附加参数
     * @return Redirect 注入器
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    fun createRedirectInjector(
        method: Method,
        asmInfo: AsmInfo,
        target: String,
        injectionPoint: InjectionPoint = InjectionPoint.INVOKE,
        ordinal: Int = -1,
        args: Array<String> = emptyArray(),
    ): AsmInjector = RedirectInjector(method, asmInfo, target, injectionPoint, ordinal, args)

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
     * @param ordinal 返回点序号；负数表示处理全部非 void 返回点
     * @return ModifyReturnValue 注入器
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    fun createModifyReturnValueInjector(
        method: Method,
        asmInfo: AsmInfo,
        ordinal: Int = -1,
    ): AsmInjector = ModifyReturnValueInjector(method, asmInfo, ordinal)

    /**
     * 创建 ModifyConstant 注入器。
     *
     * @param method ASM 方法
     * @param asmInfo ASM 注册信息
     * @param constant 常量值过滤；为 `null` 表示仅按类型匹配
     * @param ordinal 匹配常量序号；负数表示处理全部匹配常量
     * @return ModifyConstant 注入器
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    fun createModifyConstantInjector(
        method: Method,
        asmInfo: AsmInfo,
        constant: String? = null,
        ordinal: Int = -1,
    ): AsmInjector = ModifyConstantInjector(method, asmInfo, constant, ordinal)
}
