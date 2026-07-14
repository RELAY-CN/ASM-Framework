/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.api.annotation

/**
 * 删除 Mixin 声明对应的目标成员。
 *
 * 方法级标注按声明方法的 JVM 名称与描述符解析目标方法；字段级标注按声明字段名解析目标字段。
 * [value] 非空时可显式指定目标方法签名或字段名；目标不存在时转换失败。
 * 类级标注仅用于表达不受当前字节码转换模型支持的整类删除意图，转换时会明确失败，
 * 不会伪造一个不可加载的类文件，也不会尝试卸载已经加载的 JVM 类。
 * 构造器和类初始化器不能被删除。
 * 成员删除在同一 Mixin 的最终转换阶段执行；位于独立声明的普通就地改写与删除命中同一成员时，
 * 最终以删除为准。[AsmDelete] 与任何其他转换注解标在同一声明时会转换失败。
 * 与 [WrapMethod]、[Accessor]、[Invoker] 或 [Shadow] 绑定到同一成员时会转换失败，
 * 避免留下孤立实现、桥接方法或明确的悬空引用。
 * 删除只移除成员声明，不会重写目标类中已有的成员引用，调用方必须确保剩余字节码不再依赖该成员。
 *
 * 该注解会改变类结构，只能用于目标类初次定义前的加载转换或离线转换。
 * JVM retransform/redefine 不允许删除已加载类的字段或方法，因此不能用该机制删除已加载类的成员。
 *
 * ## 示例
 *
 * ```kotlin
 * @AsmDelete
 * fun legacyEndpoint() = Unit
 *
 * @AsmDelete("legacyField")
 * val legacyField: String? = null
 * ```
 *
 * @param value 目标方法 JVM 签名或字段名；为空时按被标注成员推断
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class AsmDelete(
    val value: String = "",
)

