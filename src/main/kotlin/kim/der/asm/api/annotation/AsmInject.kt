/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.api.annotation

/**
 * 方法注入注解。
 *
 * 用于标记某个 ASM 方法需要在目标方法的指定位置执行（或将其字节码内联到目标方法中）。
 * 该注解的语义参考 Mixin 的 `@Inject`，但具体支持范围以当前转换器实现为准。
 *
 * ## 取消与返回值
 *
 * - 当注入方法的第一个参数为 [CallbackInfo] 时，可在注入方法内通过 [CallbackInfo.cancel] 标记取消。
 * - 是否以及如何提前返回/替换返回值由注入器实现决定（例如 HEAD 注入可能在取消分支提前返回）。
 *
 * @param method 目标方法签名，格式：`方法名(参数类型)返回类型`，例如 `"methodName(Ljava/lang/String;)V"`
 * @param target 注入点类型；当前实现主要支持 HEAD/TAIL/RETURN/INVOKE
 * @param cancellable 是否声明该注入点允许取消（当前实现不会基于该开关屏蔽取消分支，仅作为元数据保留）
 * @param require 预留参数，当前实现未强制校验
 * @param at 当 [target] 为 [InjectionPoint.INVOKE] 时用于描述调用点；核心字段为 [At.target] 与 [At.shift]
 * @param ordinal 预留参数，当前实现未实现“第 N 个匹配点”选择
 * @param slice 预留参数，当前实现未实现按切片范围缩小查找
 * @param allow 预留参数，当前实现未限制最大注入次数
 * @param expect 预留参数，当前实现未实现期望次数告警
 * @param inline 是否内联代码；为 true 时将直接把 ASM 方法的字节码插入到目标方法中，而不是生成方法调用
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class AsmInject(
    val method: String = "",
    val target: InjectionPoint = InjectionPoint.HEAD,
    val cancellable: Boolean = false,
    val require: Int = 0,
    val at: At = At(),
    val ordinal: Int = -1,
    val slice: Slice = Slice(),
    val allow: Int = -1,
    val expect: Int = 1,
    val inline: Boolean = false,
)

/**
 * 注入点枚举。
 *
 * 用于描述代码注入的位置。当前注入器工厂仅显式支持 [HEAD]、[TAIL]、[RETURN]、[INVOKE]；
 * 其余值在当前实现中会回退为 HEAD 注入。
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
enum class InjectionPoint {
    /** 方法开头 */
    HEAD,

    /** 方法结尾（所有 RETURN 之前） */
    TAIL,

    /** 返回前（每个 RETURN 之前） */
    RETURN,

    /** 方法调用前 */
    INVOKE,

    /** 方法调用后 */
    INVOKE_ASSIGN,

    /** 字段访问前 */
    FIELD,

    /** 字段赋值前 */
    FIELD_ASSIGN,

    /** NEW 操作前 */
    NEW,

    /** 抛出异常前 */
    THROW,
}

/**
 * 调用点定位信息。
 *
 * 当前主要用于 [InjectionPoint.INVOKE]：通过 [target] 指定要匹配的方法调用签名，
 * 并通过 [shift] 指定在调用前/后/替换该调用点。
 *
 * 注意：当前实现要求 [target] 必须包含 owner 与方法描述符，否则注入器会直接跳过处理。
 *
 * @param value 预留字段，当前实现未使用
 * @param target 目标方法调用签名，例如 `"java/lang/System.gc()V"` 或 `"java.lang.System.gc()V"`
 * @param shift 注入偏移策略
 * @param by 预留参数，当前实现未实现按字节码偏移移动
 * @param args 预留参数，当前实现未解析
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
annotation class At(
    val value: InjectionPoint = InjectionPoint.HEAD,
    val target: String = "",
    val shift: Shift = Shift.BEFORE,
    val by: Int = 0,
    val args: Array<String> = [],
)

/**
 * 注入偏移策略。
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
enum class Shift {
    /** 在目标之前 */
    BEFORE,

    /** 在目标之后 */
    AFTER,

    /** 替换目标 */
    REPLACE,
}

/**
 * 注入点切片范围。
 *
 * 用于描述在某段字节码范围内查找注入点的起止条件。
 * 当前实现未启用 slice 过滤，字段仅作为元数据保留。
 *
 * @param from 起始定位条件
 * @param to 结束定位条件
 * @param id 预留标识
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
annotation class Slice(
    val from: At = At(),
    val to: At = At(),
    val id: String = "",
)

