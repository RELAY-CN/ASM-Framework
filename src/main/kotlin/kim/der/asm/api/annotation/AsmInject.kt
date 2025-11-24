/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.api.annotation

/**
 * 方法注入注解，类似 Mixin 的 @Inject
 * 用于在目标方法的指定位置注入代码
 *
 * @param method 目标方法名，格式：方法名(参数类型)返回类型，如 "methodName(Ljava/lang/String;)V"
 * @param target 注入点位置：HEAD（方法开头）、TAIL（方法结尾）、RETURN（返回前）、INVOKE（调用特定方法前/后）
 * @param cancellable 是否可取消方法执行
 * @param require 是否必须找到目标方法（找不到时抛出异常）
 * @param at 当 target 为 INVOKE 时，指定目标调用的方法签名
 * @param ordinal 当有多个匹配的注入点时，指定使用第几个（从 0 开始）
 * @param slice 注入点切片，用于在特定代码块范围内查找
 * @param allow 允许的最大注入次数（-1 表示不限制）
 * @param expect 期望的注入次数（不匹配时记录警告）
 * @param inline 是否内联代码。如果为 true，将直接把 ASM 方法的字节码插入到目标方法的指定位置，而不是调用方法
 *
 * @author Dr (dr@der.kim)
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
 * 注入点枚举，定义代码注入的位置
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
 * At 注解，用于指定精确的注入位置
 */
annotation class At(
    val value: InjectionPoint = InjectionPoint.HEAD,
    val target: String = "",
    val shift: Shift = Shift.BEFORE,
    val by: Int = 0,
    val args: Array<String> = [],
)

/**
 * 注入位置偏移
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
 * Slice 注解，用于定义查找范围
 */
annotation class Slice(
    val from: At = At(),
    val to: At = At(),
    val id: String = "",
)
