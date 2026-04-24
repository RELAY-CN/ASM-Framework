/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.api.annotation

/**
 * ASM Mixin 声明注解。
 *
 * 用于标记某个 ASM 类需要应用到一个或多个目标类上。
 * 目标类名称使用 JVM internal name（例如 `"com/example/Target"`），并由注册器用于建立“目标类 -> ASM 列表”的索引。
 *
 * @param value 单目标类 internal name；为空时可使用 [targets]
 * @param remap 是否启用重映射（当前实现未启用，字段仅作为元数据保留）
 * @param targets 多目标类 internal name 列表
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class AsmMixin(
    val value: String = "",
    val remap: Boolean = false,
    val targets: Array<String> = [],
)

/**
 * 全方法替换注解。
 *
 * 用于将目标类的所有方法体替换为调用 [RedirectionReplaceApi] 的兼容实现。
 * 该注解作用于类级别，并会遍历目标类的方法列表逐一替换。
 *
 * @param removeSync 是否同时移除方法的 `synchronized` 语义（移除标志与相关指令）
 * @param remap 是否启用重映射（当前实现未启用，字段仅作为元数据保留）
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class ReplaceAllMethods(
    val removeSync: Boolean = false,
    val remap: Boolean = false,
)

/**
 * 全方法重定向注解。
 *
 * 用于将目标类所有方法中的指定调用统一重定向到 [Redirect] 标注的方法。
 * 在该模式下，[Redirect.method] 不用于筛选目标方法；转换器会把每个 `@Redirect` 处理器应用到目标类的全部方法。
 *
 * ## 使用场景
 *
 * - 将所有方法中的 `System.gc()` 调用替换成空操作
 * - 将所有方法中的某个调用统一重定向到自定义实现
 *
 * 与普通 `@Redirect` 的区别：
 *
 * - 普通 `@Redirect` 需要在注解中指定 `method` 参数，仅影响特定方法
 * - `@RedirectAllMethods` 会让 `@Redirect` 应用到目标类的所有方法，无需指定 `method`
 *
 * @param remap 是否启用重映射（当前实现未启用，字段仅作为元数据保留）
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class RedirectAllMethods(
    val remap: Boolean = false,
)

/**
 * 覆盖方法注解。
 *
 * 用于完全替换目标方法的实现（类似 Mixin 的 `@Overwrite`）。
 * 若无法定位目标方法，会记录 warning 并跳过本次覆写。
 *
 * @param method 目标方法签名，格式：`方法名(参数类型)返回类型`，例如 `"methodName(Ljava/lang/String;)V"`
 * @param remap 是否启用重映射（当前实现未启用，字段仅作为元数据保留）
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Overwrite(
    val method: String = "",
    val remap: Boolean = false,
)

/**
 * 复制方法注解。
 *
 * 用于将 ASM 方法复制到目标类中作为一个新方法。
 * 与 [Overwrite] 不同，[Copy] 不会覆盖同名同签名的方法；当目标方法已存在时会跳过并输出 warning。
 *
 * @param method 目标方法签名；为空时使用 ASM 方法名与描述符
 * @param remap 是否启用重映射（当前实现未启用，字段仅作为元数据保留）
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Copy(
    val method: String = "",
    val remap: Boolean = false,
)

/**
 * 修改参数注解。
 *
 * 用于在目标方法执行前修改指定索引的参数值（语义参考 Mixin 的 `@ModifyArg`）。
 * 当前实现会在方法开头直接写回参数槽位。
 *
 * ASM 方法要求：
 *
 * - 接收原始参数值并返回同类型的新值
 *
 * @param method 目标方法签名
 * @param index 要修改的参数索引（从 0 开始）
 * @param at 预留参数，当前实现未使用
 * @param slice 预留参数，当前实现未使用
 * @param remap 是否启用重映射（当前实现未启用，字段仅作为元数据保留）
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
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
 * 修改返回值注解。
 *
 * 用于在目标方法返回前修改返回值。
 * 当前实现会在每个非 void 的 RETURN 指令前注入修改逻辑。
 *
 * ASM 方法要求：
 *
 * - 返回类型必须与目标方法返回类型一致
 * - 参数可选：可以只接收原始返回值，也可以追加目标方法的部分参数
 *
 * @param method 目标方法签名
 * @param at 预留参数，当前实现未使用
 * @param remap 是否启用重映射（当前实现未启用，字段仅作为元数据保留）
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ModifyReturnValue(
    val method: String = "",
    val at: At = At(),
    val remap: Boolean = false,
)

/**
 * 修改常量注解。
 *
 * 用于修改目标方法中的常量值（语义参考 Mixin 的 `@ModifyConstant`）。
 * 当前实现会遍历字节码中的常量指令，并在匹配时用 ASM 方法返回值替换原常量。
 *
 * ASM 方法要求：
 *
 * - 接收原始常量值并返回同类型的新值
 * - 返回类型必须与被修改常量的类型一致
 *
 * @param method 目标方法签名
 * @param constant 常量匹配过滤；为空时不做值过滤（仅按类型匹配）
 * @param remap 是否启用重映射（当前实现未启用，字段仅作为元数据保留）
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ModifyConstant(
    val method: String = "",
    val constant: String = "",
    val remap: Boolean = false,
)

/**
 * 重定向方法调用注解。
 *
 * 用于将目标方法中的某个方法调用重定向到当前 ASM 方法（语义参考 Mixin 的 `@Redirect`）。
 * 当前实现会在字节码中查找匹配的调用指令，并用 `INVOKESTATIC` 调用重定向处理器替换原调用。
 *
 * 重定向处理器要求：
 *
 * - 处理器方法必须为静态方法或 `@JvmStatic` 方法（当前实现固定使用 `INVOKESTATIC`）
 * - 处理器方法的参数栈形态需与原调用保持一致（包括实例方法的 `this`）
 *
 * @param method 目标方法签名
 * @param target 目标调用签名组件；会与 [At.target] 组合构建最终的匹配签名
 * @param at 调用点信息；[At.target] 用于指定要匹配的调用签名
 * @param slice 预留参数，当前实现未使用
 * @param remap 是否启用重映射（当前实现未启用，字段仅作为元数据保留）
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
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
 * Shadow 字段/方法注解。
 *
 * 用于在 ASM 类中声明对目标类字段/方法的“引用占位”，以便在转换阶段进行校验或修饰符调整（语义参考 Mixin 的 `@Shadow`）。
 * 当前实现会基于 [method] 与 [prefix] 解析目标名称：
 *
 * - 当 [method] 以 [prefix] 开头时，目标名称为去掉前缀后的部分
 * - 否则默认使用字段名/方法名
 *
 * @param method 目标名称提示；建议使用 `shadow_` 前缀显式指定
 * @param remap 是否启用重映射（当前实现未启用，字段仅作为元数据保留）
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Shadow(
    val method: String = "",
    val remap: Boolean = false,
) {
    companion object {
        /**
         * Shadow 前缀。
         *
         * 当 [Shadow.method] 以该前缀开头时，会在匹配目标名称时去掉该前缀。
         */
        const val prefix: String = "shadow_"
    }
}

/**
 * Accessor 注解。
 *
 * 用于在目标类上生成字段访问器方法。
 *
 * @param value 目标字段名；为空时从方法名推断
 * @param remap 是否启用重映射（当前实现未启用，字段仅作为元数据保留）
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Accessor(
    val value: String = "",
    val remap: Boolean = false,
)

/**
 * Invoker 注解。
 *
 * 用于生成“私有/受保护方法的调用器”，以便在转换后通过桥接方法访问目标方法。
 *
 * @param value 目标方法名；为空时从方法名推断
 * @param remap 是否启用重映射（当前实现未启用，字段仅作为元数据保留）
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Invoker(
    val value: String = "",
    val remap: Boolean = false,
)

/**
 * 可变字段标记注解。
 *
 * 标记字段为可变（移除目标字段的 `final` 修饰符）。该注解可用于字段或 [Accessor] 方法。
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Mutable

/**
 * 最终字段标记注解。
 *
 * 标记字段为最终（为目标字段添加 `final` 修饰符）。
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class Final

/**
 * 移除方法注解。
 *
 * 用于在 ASM 类中标记需要移除的目标方法。
 *
 * @param method 目标方法签名；为空时移除 ASM 方法对应的目标方法
 * @param remap 是否启用重映射（当前实现未启用，字段仅作为元数据保留）
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RemoveMethod(
    val method: String = "",
    val remap: Boolean = false,
)

/**
 * 移除方法同步注解。
 *
 * 用于移除目标方法的 `synchronized` 标志与相关的同步指令（例如 `MONITORENTER`）。
 *
 * @param method 目标方法签名；为空时处理 ASM 方法对应的目标方法
 * @param remap 是否启用重映射（当前实现未启用，字段仅作为元数据保留）
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RemoveSynchronized(
    val method: String = "",
    val remap: Boolean = false,
)

