/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.injector

import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.At
import kim.der.asm.api.annotation.Slice
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
            InjectionPoint.INVOKE -> InvokeInjector(method, asmInfo, injectionPoint)
            InjectionPoint.INVOKE_ASSIGN -> InvokeInjector(method, asmInfo, injectionPoint)
            InjectionPoint.FIELD,
            InjectionPoint.FIELD_ASSIGN,
            InjectionPoint.NEW,
            InjectionPoint.CAST,
            InjectionPoint.INSTANCEOF,
            InjectionPoint.JUMP,
            InjectionPoint.SWITCH,
            InjectionPoint.CONSTANT,
            InjectionPoint.LOAD,
            InjectionPoint.STORE,
            InjectionPoint.THROW,
            -> InstructionPointInjector(method, asmInfo, injectionPoint)
        }

    /**
     * 创建 ModifyArg 注入器。
     *
     * @param method ASM 方法
     * @param asmInfo ASM 注册信息
     * @param index 要修改的参数索引
     * @param at 调用点定位；默认 HEAD 时保持入口参数改写语义
     * @param ordinal 匹配调用点序号；负数表示处理全部匹配调用点
     * @param slice 切片范围；当前 INVOKE 调用点参数修改支持 INVOKE 边界切片
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
        slice: Slice = Slice(),
    ): AsmInjector = ModifyArgInjector(method, asmInfo, index, at, ordinal, slice)

    /**
     * 创建 ModifyArgs 注入器。
     *
     * @param method ASM 方法
     * @param asmInfo ASM 注册信息
     * @param at 调用点定位；当前仅支持 INVOKE
     * @param ordinal 匹配调用点序号；负数表示处理全部匹配调用点
     * @param slice 切片范围；当前 INVOKE 调用点参数组修改支持 INVOKE 边界切片
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
        slice: Slice = Slice(),
    ): AsmInjector = ModifyArgsInjector(method, asmInfo, at, ordinal, slice)

    /**
     * 创建 ModifyReceiver 注入器。
     *
     * @param method ASM 方法
     * @param asmInfo ASM 注册信息
     * @param at 调用点定位；当前支持 INVOKE、FIELD 与 FIELD_ASSIGN
     * @param ordinal 匹配调用点序号；负数表示处理全部匹配调用点
     * @param slice 切片范围；当前 INVOKE、FIELD 与 FIELD_ASSIGN receiver 改写支持 INVOKE 边界切片
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
        slice: Slice = Slice(),
    ): AsmInjector = ModifyReceiverInjector(method, asmInfo, at, ordinal, slice)

    /**
     * 创建 WrapOperation 注入器。
     *
     * @param method ASM 方法
     * @param asmInfo ASM 注册信息
     * @param at 操作点定位；当前支持 INVOKE、FIELD、FIELD_ASSIGN、NEW、CAST、INSTANCEOF、LOAD、STORE、JUMP、SWITCH、CONSTANT 与 THROW
     * @param ordinal 匹配调用点序号；负数表示处理全部匹配调用点
     * @param slice 切片范围；当前调用、字段、数组、NEW、CAST、INSTANCEOF、LOAD、STORE、JUMP、SWITCH、CONSTANT 与 THROW
     * 操作包裹支持 INVOKE 边界切片
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
        slice: Slice = Slice(),
    ): AsmInjector = WrapOperationInjector(method, asmInfo, at, ordinal, slice)

    /**
     * 创建 WrapWithCondition 注入器。
     *
     * @param method ASM 方法
     * @param asmInfo ASM 注册信息
     * @param at 调用点定位；当前支持 INVOKE、FIELD_ASSIGN、JUMP 与 THROW
     * @param ordinal 匹配点序号；负数表示处理全部匹配点
     * @param slice 切片范围；当前 INVOKE、FIELD_ASSIGN、JUMP 与 THROW 条件包裹支持 INVOKE 边界切片
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
        slice: Slice = Slice(),
    ): AsmInjector = WrapWithConditionInjector(method, asmInfo, at, ordinal, slice)

    /**
     * 创建 ModifyExpressionValue 注入器。
     *
     * @param method ASM 方法
     * @param asmInfo ASM 注册信息
     * @param at 表达式定位；当前支持 INVOKE、INVOKE_ASSIGN、FIELD、FIELD_ASSIGN、NEW、CAST、INSTANCEOF、LOAD、STORE、JUMP、SWITCH、CONSTANT 与 THROW
     * @param ordinal 匹配调用点序号；负数表示处理全部匹配调用点
     * @param slice 切片范围；当前调用返回、字段读取、字段写入值、数组读取、数组写入值、数组长度、NEW、CAST、INSTANCEOF、LOAD、STORE、JUMP、SWITCH、CONSTANT 与 THROW
     * 表达式改写支持 INVOKE 边界切片
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
        slice: Slice = Slice(),
    ): AsmInjector = ModifyExpressionValueInjector(method, asmInfo, at, ordinal, slice)

    /**
     * 创建 ModifyVariable 注入器。
     *
     * @param method ASM 方法
     * @param asmInfo ASM 注册信息
     * @param injectionPoint 修改位置
     * @param index 要修改的局部变量槽位索引
     * @param names 要匹配的局部变量名集合
     * @param ordinal 未指定槽位索引时，同类型入口参数、读取点或写入点的序号
     * @param slice 切片范围；当前 LOAD 与 STORE 局部变量改写支持 INVOKE 边界切片
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
        names: Array<String>,
        ordinal: Int,
        slice: Slice = Slice(),
    ): AsmInjector = ModifyVariableInjector(method, asmInfo, injectionPoint, index, names, ordinal, slice)

    /**
     * 创建 Redirect 注入器。
     *
     * @param method ASM 方法
     * @param asmInfo ASM 注册信息
     * @param target 目标调用、构造器、字段、构造类型、类型签名、跳转操作码、常量文本或直接构造异常类型；LOAD、STORE 与 SWITCH 不使用该参数
     * @param injectionPoint Redirect 的定位点类型
     * @param ordinal 匹配调用点序号；负数表示处理全部匹配调用点
     * @param slice 切片范围；当前方法调用、构造器调用、NEW 构造表达式、字段读取、字段写入、数组元素访问、数组长度、局部变量读取、局部变量写入、类型转换、类型判断、条件跳转、switch selector、常量加载与抛异常点重定向支持
     * INVOKE 边界切片
     * @param args 调用点附加参数；数组模式支持 `array=get`、`array=set` 与 `array=length`，LOAD / STORE 支持 `index=N` 与 `var=N` 槽位过滤
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
        slice: Slice = Slice(),
        args: Array<String> = emptyArray(),
    ): AsmInjector = RedirectInjector(method, asmInfo, target, injectionPoint, ordinal, slice, args)

    /**
     * 创建 Overwrite 注入器。
     *
     * @param method ASM 方法
     * @param asmInfo ASM 注册信息
     * @param copyMethodNames ASM 方法签名到实际复制目标方法名的映射
     * @return Overwrite 注入器
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    fun createOverwriteInjector(
        method: Method,
        asmInfo: AsmInfo,
        copyMethodNames: Map<String, String> = emptyMap(),
    ): AsmInjector = OverwriteInjector(method, asmInfo, copyMethodNames)

    /**
     * 创建 Copy 注入器。
     *
     * @param method ASM 方法
     * @param asmInfo ASM 注册信息
     * @param copyMethodNames ASM 方法签名到实际复制目标方法名的映射
     * @return Copy 注入器
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    fun createCopyInjector(
        method: Method,
        asmInfo: AsmInfo,
        copyMethodNames: Map<String, String> = emptyMap(),
    ): CopyInjector = CopyInjector(method, asmInfo, copyMethodNames)

    /**
     * 创建 ModifyReturnValue 注入器。
     *
     * @param method ASM 方法
     * @param asmInfo ASM 注册信息
     * @param ordinal 返回点序号；负数表示处理全部非 void 返回点
     * @param slice 切片范围；当前返回值修改支持 INVOKE 边界切片
     * @return ModifyReturnValue 注入器
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    fun createModifyReturnValueInjector(
        method: Method,
        asmInfo: AsmInfo,
        ordinal: Int = -1,
        slice: Slice = Slice(),
    ): AsmInjector = ModifyReturnValueInjector(method, asmInfo, ordinal, slice)

    /**
     * 创建 ModifyConstant 注入器。
     *
     * @param method ASM 方法
     * @param asmInfo ASM 注册信息
     * @param constant 常量值过滤；为 `null` 表示仅按类型匹配
     * @param ordinal 匹配常量序号；负数表示处理全部匹配常量
     * @param slice 切片范围；当前常量修改支持 INVOKE 边界切片
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
        slice: Slice = Slice(),
    ): AsmInjector = ModifyConstantInjector(method, asmInfo, constant, ordinal, slice)
}
