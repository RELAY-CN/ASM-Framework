/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.api.annotation

/**
 * ASM 类注解，用于标记需要应用到目标类的 ASM 类
 * 类似 Fabric Mixin 的 @Mixin，但基于 ASM 实现
 *
 * @param value 目标类名（内部名称，如 "com/example/Target"）
 * @param remap 是否需要重映射（暂时不支持，保留用于未来扩展）
 * @param targets 支持多个目标类
 *
 * @author Dr (dr@der.kim)
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class AsmMixin(
    val value: String = "",
    val remap: Boolean = false,
    val targets: Array<String> = [],
)

/**
 * 全方法替换注解
 * 用于替换目标类中的所有方法，将所有方法体替换为调用 RedirectionReplaceApi
 *
 * @param removeSync 是否同时移除所有方法的 synchronized 关键字
 * @param remap 是否需要重映射（暂时不支持，保留用于未来扩展）
 *
 * @author Dr (dr@der.kim)
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class ReplaceAllMethods(
    val removeSync: Boolean = false,
    val remap: Boolean = false,
)

/**
 * 覆盖方法注解，类似 Mixin 的 @Overwrite
 * 用于完全替换目标方法的实现
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Overwrite(
    val method: String = "",
    val remap: Boolean = false,
)

/**
 * 复制方法注解
 * 用于将 ASM 方法复制到目标类中作为新方法
 * 与 @Overwrite 不同，@Copy 不会覆盖现有方法，而是创建一个新方法
 *
 * @param method 目标方法签名，格式：方法名(参数类型)返回类型，如 "methodName(Ljava/lang/String;)V"
 *               如果为空，则使用 ASM 方法名和描述符
 * @param remap 是否需要重映射（暂时不支持，保留用于未来扩展）
 *
 * @author Dr (dr@der.kim)
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Copy(
    val method: String = "",
    val remap: Boolean = false,
)

/**
 * 修改参数注解，类似 Mixin 的 @ModifyArg
 * 在方法执行前修改指定索引的参数值
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ModifyArg(
    val method: String = "",
    val index: Int = -1,
    val at: At = At(),
    val slice: Slice = Slice(),
    val remap: Boolean = false,
)

/**
 * 修改返回值注解
 * 在方法返回前修改返回值
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ModifyReturnValue(
    val method: String = "",
    val at: At = At(),
    val remap: Boolean = false,
)

/**
 * 修改常量注解，类似 Mixin 的 @ModifyConstant
 * 用于修改方法中的常量值
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ModifyConstant(
    val method: String = "",
    val constant: String = "",
    val remap: Boolean = false,
)

/**
 * 重定向方法调用注解，类似 Mixin 的 @Redirect
 * 将目标方法中的方法调用重定向到 ASM 方法
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Redirect(
    val method: String = "",
    val target: String = "",
    val at: At = At(),
    val slice: Slice = Slice(),
    val remap: Boolean = false,
)

/**
 * Shadow 字段/方法注解，类似 Mixin 的 @Shadow
 * 用于在 ASM 类中引用目标类的字段或方法
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Shadow(
    val method: String = "",
    val remap: Boolean = false,
) {
    companion object {
        const val prefix: String = "shadow_"
    }
}

/**
 * Accessor 注解，用于生成字段访问器
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Accessor(
    val value: String = "",
    val remap: Boolean = false,
)

/**
 * Invoker 注解，用于调用私有/受保护方法
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Invoker(
    val value: String = "",
    val remap: Boolean = false,
)

/**
 * Mutable 注解，标记字段为可变
 * 可以用于字段或 Accessor 方法（用于移除 final 字段的 final 修饰符）
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Mutable

/**
 * Final 注解，标记字段为最终
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class Final

/**
 * 移除方法注解
 * 用于在 ASM 类中标记需要移除的目标方法
 *
 * @param method 目标方法签名，格式：方法名(参数类型)返回类型，如 "methodName(Ljava/lang/String;)V"
 *               如果为空，则移除 ASM 方法对应的目标方法
 * @param remap 是否需要重映射（暂时不支持，保留用于未来扩展）
 *
 * @author Dr (dr@der.kim)
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RemoveMethod(
    val method: String = "",
    val remap: Boolean = false,
)

/**
 * 移除方法同步注解
 * 用于移除目标方法的 synchronized 关键字和相关的同步指令
 *
 * @param method 目标方法签名，格式：方法名(参数类型)返回类型，如 "methodName(Ljava/lang/String;)V"
 *               如果为空，则处理 ASM 方法对应的目标方法
 * @param remap 是否需要重映射（暂时不支持，保留用于未来扩展）
 *
 * @author Dr (dr@der.kim)
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RemoveSynchronized(
    val method: String = "",
    val remap: Boolean = false,
)
