/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.api.annotation

/**
 * 显式捕获目标方法当前注入点可见的局部变量。
 *
 * 该注解用于普通 [AsmInject] handler 参数。当前实现支持 [InjectionPoint.TAIL]、[InjectionPoint.RETURN]
 * 与普通指令点注入的只读局部变量捕获：框架会在当前注入锚点读取 LocalVariableTable
 * 中仍处于作用域内的变量，并把该槽位的当前值传给被标记的 handler 参数。
 * 捕获值不会写回目标方法局部变量；需要修改局部变量时应使用
 * [ModifyVariable]、[ModifyExpressionValue]、[Redirect]、[WrapOperation] 或 [WrapWithCondition]。
 *
 * 必须至少设置 [name] / [value] 或 [index] 之一。[name] 与 [value] 语义相同，[name] 优先；
 * 当同时设置名称与 [index] 时，两者必须同时匹配同一个可见局部变量。[index] 只负责槽位过滤，
 * 当前仍通过 LocalVariableTable 判断局部变量作用域和类型。
 *
 * @param value 局部变量名的简写别名；当 [name] 为空时使用
 * @param name LocalVariableTable 中的局部变量名
 * @param index JVM 局部变量槽位；`-1` 表示不按槽位过滤
 * @author Dr (dr@der.kim)
 * @date 2026-06-07
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Local(
    val value: String = "",
    val name: String = "",
    val index: Int = -1,
)
