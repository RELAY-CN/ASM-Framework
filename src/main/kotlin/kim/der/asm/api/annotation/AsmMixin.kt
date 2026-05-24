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
 * 添加接口注解。
 *
 * 用于为目标类追加一个或多个接口 internal name。该注解只修改 classfile 的接口声明列表，
 * 不会自动生成接口方法实现；调用方必须通过 [Overwrite]、[Copy] 或目标类已有方法保证接口契约可满足。
 *
 * ## 使用边界
 *
 * - [value] 与 [interfaces] 均为空时不会产生改写。
 * - 接口名可使用 JVM internal name（如 `java/io/Closeable`）或 Java binary name（如 `java.io.Closeable`），转换时会统一为 internal name。
 * - 已存在的接口会被跳过，不会重复写入 [org.objectweb.asm.tree.ClassNode.interfaces]。
 *
 * @param value 单个接口名；为空时可使用 [interfaces]
 * @param interfaces 多个接口名
 * @param remap 是否启用重映射（当前实现未启用，字段仅作为元数据保留）
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class AddInterface(
    val value: String = "",
    val interfaces: Array<String> = [],
    val remap: Boolean = false,
)

/**
 * 移除接口注解。
 *
 * 用于从目标类声明中移除一个或多个接口 internal name。该注解只改写 classfile 的接口声明列表，
 * 不会删除目标类中已经存在的方法实现，也不会检查外部代码是否仍按被移除接口使用该类。
 *
 * ## 使用边界
 *
 * - [value] 与 [interfaces] 均为空时不会产生改写。
 * - 接口名可使用 JVM internal name（如 `java/lang/Runnable`）或 Java binary name（如 `java.lang.Runnable`），转换时会统一为 internal name。
 * - 目标类未声明的接口会被跳过，不会视为转换失败。
 *
 * @param value 单个接口名；为空时可使用 [interfaces]
 * @param interfaces 多个接口名
 * @param remap 是否启用重映射（当前实现未启用，字段仅作为元数据保留）
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class RemoveInterface(
    val value: String = "",
    val interfaces: Array<String> = [],
    val remap: Boolean = false,
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
 * 用于修改目标方法参数或目标方法内某次方法调用的参数值（语义参考 Mixin 的 `@ModifyArg`）。
 * 默认在目标方法入口直接写回参数槽位；当 [at] 指向 [InjectionPoint.INVOKE] 时，会改写匹配调用点的指定参数。
 *
 * ASM 方法要求：
 *
 * - 接收原始参数值并返回同类型的新值
 * - 第一个参数必须是被修改的原参数；后续参数可按顺序接收目标方法参数前缀
 * - INVOKE 模式只把 [index] 选中的调用参数作为第一个参数，不会自动传入目标调用的其他参数
 *
 * @param method 目标方法签名
 * @param index 要修改的参数索引（从 0 开始）；入口模式下为目标方法参数索引，INVOKE 模式下为目标调用参数索引
 * @param at 注入位置；默认 HEAD 改写入口参数，`INVOKE` 时用 [At.target] 匹配目标调用
 * @param ordinal 匹配调用点序号；`-1` 表示修改全部匹配调用点，当前仅在 INVOKE 模式下生效
 * @param slice 切片范围；当前 INVOKE 调用点参数修改支持用 [Slice.from] / [Slice.to] 的
 * [InjectionPoint.INVOKE] 边界缩小查找范围
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
    val ordinal: Int = -1,
    val slice: Slice = Slice(),
    val remap: Boolean = false,
)

/**
 * 修改调用参数组注解。
 *
 * 用于修改目标方法内某次方法调用的整组参数（语义参考 Mixin 的 `@ModifyArgs`）。当需要同时改写
 * 同一个调用点的多个参数时，优先使用该注解，而不是叠加多个 [ModifyArg]。
 *
 * ASM 方法要求：
 *
 * - 第一个参数必须是 [Args]，用于读取和写回匹配调用点的参数
 * - handler 必须返回 `void`
 * - 后续参数可按顺序接收目标方法参数前缀
 * - [At.value] 必须为 [InjectionPoint.INVOKE]，并通过 [At.target] 指定要匹配的方法调用
 *
 * @param method 目标方法签名
 * @param at 调用点定位；当前仅支持 [InjectionPoint.INVOKE]
 * @param ordinal 匹配调用点序号；`-1` 表示修改全部匹配调用点，`0` 及以上表示只修改第 N 个匹配调用点
 * @param slice 切片范围；当前 INVOKE 调用点参数组修改支持用 [Slice.from] / [Slice.to] 的
 * [InjectionPoint.INVOKE] 边界缩小查找范围
 * @param remap 是否启用重映射（当前实现未启用，字段仅作为元数据保留）
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ModifyArgs(
    val method: String = "",
    val at: At = At(value = InjectionPoint.INVOKE),
    val ordinal: Int = -1,
    val slice: Slice = Slice(),
    val remap: Boolean = false,
)

/**
 * 修改调用 receiver 注解。
 *
 * 用于修改目标方法内某次实例方法调用、实例字段读取或实例字段写入的 receiver（语义参考 Mixin Extras 的 `@ModifyReceiver`）。
 * handler 接收原 receiver 并返回新的 receiver；原调用参数或字段写入值会保持原顺序继续传给目标操作。
 *
 * ASM 方法要求：
 *
 * - 第一个参数必须接收原 receiver
 * - 返回类型必须兼容原 receiver 类型
 * - 后续参数可按顺序接收目标方法参数前缀
 * - [At.value] 为 [InjectionPoint.INVOKE] 时，通过 [At.target] 指定要匹配的实例方法调用
 * - [At.value] 为 [InjectionPoint.FIELD] 时匹配实例字段读取，为 [InjectionPoint.FIELD_ASSIGN] 时匹配实例字段写入
 *
 * @param method 目标方法签名
 * @param at 调用点定位；当前支持 [InjectionPoint.INVOKE]、[InjectionPoint.FIELD] 与 [InjectionPoint.FIELD_ASSIGN]
 * @param ordinal 匹配点序号；`-1` 表示修改全部匹配点，`0` 及以上表示只修改第 N 个匹配点
 * @param slice 预留参数，当前实现未使用
 * @param remap 是否启用重映射（当前实现未启用，字段仅作为元数据保留）
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ModifyReceiver(
    val method: String = "",
    val at: At = At(value = InjectionPoint.INVOKE),
    val ordinal: Int = -1,
    val slice: Slice = Slice(),
    val remap: Boolean = false,
)

/**
 * 包裹原始操作注解。
 *
 * 用于把目标方法内匹配的方法调用、构造器调用、字段读取、字段写入、数组元素读写或数组长度读取替换为 handler 调用（语义参考
 * Mixin Extras 的 `@WrapOperation`）。handler 会接收原操作的 receiver（实例调用、实例字段读取与
 * 实例字段写入）、原调用参数、构造器参数、字段写入值或数组访问参数与 [Operation]，可选择调用、跳过或多次调用原始操作。
 *
 * 当前实现支持 [InjectionPoint.INVOKE] 方法调用、[InjectionPoint.FIELD] 字段读取与
 * [InjectionPoint.FIELD_ASSIGN] 字段写入；[InjectionPoint.FIELD] 可通过 `args = ["array=get"]`
 * 包裹数组元素读取，通过 `args = ["array=length"]` 包裹数组长度读取；
 * [InjectionPoint.FIELD_ASSIGN] 可通过 `args = ["array=set"]` 包裹数组元素写入。
 * 构造器调用通过 [InjectionPoint.INVOKE] 与 `<init>` 目标指定，当前支持常见 `NEW/DUP/args/<init>` 形态。
 *
 * ASM 方法要求：
 *
 * - 实例调用的 handler 参数先接收 receiver，再接收原调用参数
 * - 静态调用的 handler 参数接收原调用参数
 * - 构造器调用的 handler 参数先接收构造器参数，不接收未初始化 receiver
 * - `GETFIELD` 的 handler 参数先接收字段 owner
 * - `GETSTATIC` 的 handler 不接收字段 owner
 * - `PUTFIELD` 的 handler 参数先接收字段 owner，再接收待写入值
 * - `PUTSTATIC` 的 handler 参数接收待写入值
 * - 数组读取 handler 参数先接收数组引用与 `Int` 索引
 * - 数组写入 handler 参数先接收数组引用、`Int` 索引与待写入元素值
 * - 数组长度 handler 参数先接收数组引用
 * - 下一参数必须是 [Operation]，用于执行原始调用、构造器调用、字段读取、字段写入、数组元素读写或数组长度读取
 * - handler 返回类型必须兼容原操作返回类型；原调用为 `void` 时 handler 必须返回 `void`，构造器调用必须返回 owner 类型兼容对象
 * - 后续参数可按顺序接收目标方法参数前缀
 * - [At.value] 必须为 [InjectionPoint.INVOKE]、[InjectionPoint.FIELD] 或 [InjectionPoint.FIELD_ASSIGN]，
 *   并通过 [At.target] 指定要匹配的方法调用、字段读取、字段写入或产生数组引用的字段
 *
 * @param method 目标方法签名
 * @param at 操作点定位；当前支持 [InjectionPoint.INVOKE]、[InjectionPoint.FIELD] 与 [InjectionPoint.FIELD_ASSIGN]
 * @param ordinal 匹配操作点序号；`-1` 表示包裹全部匹配操作点，`0` 及以上表示只包裹第 N 个匹配操作点
 * @param slice 切片范围；当前 INVOKE 操作包裹支持用 [Slice.from] / [Slice.to] 的
 * [InjectionPoint.INVOKE] 边界缩小查找范围
 * @param remap 是否启用重映射（当前实现未启用，字段仅作为元数据保留）
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class WrapOperation(
    val method: String = "",
    val at: At = At(value = InjectionPoint.INVOKE),
    val ordinal: Int = -1,
    val slice: Slice = Slice(),
    val remap: Boolean = false,
)

/**
 * 条件包裹注解。
 *
 * 用于在目标方法内匹配 `void` 方法调用、字段写入或简单数组元素写入前插入条件判断（语义参考
 * Mixin Extras 的 `@WrapWithCondition`）。handler 返回 `true` 时继续执行原调用或写入，
 * 返回 `false` 时跳过原指令。
 * 相比 [Redirect]，该注解不替换原逻辑，只决定原指令是否继续执行，更适合“按条件跳过副作用调用或写入”的场景。
 *
 * [InjectionPoint.INVOKE] 模式要求目标调用返回类型必须为 `void`。[InjectionPoint.FIELD_ASSIGN] 模式匹配
 * `PUTFIELD` / `PUTSTATIC` 字段写入，字段目标格式支持 `owner.field:desc`、`field:desc` 与 `field`。
 * 数组元素写入使用 [InjectionPoint.FIELD_ASSIGN]、数组字段 [At.target] 与 [At.args] 中的 `array=set` 指定，
 * 当前匹配由最近的目标数组字段读取产生数组引用的 `xASTORE` 指令。
 * [InjectionPoint.INVOKE] 模式可使用 [slice] 把候选调用限制在一段 INVOKE 边界之间，边界指令本身不参与匹配。
 *
 * ASM 方法要求：
 *
 * - handler 必须返回 `Boolean`
 * - 非静态目标调用的 handler 参数先接收 receiver，再接收原调用参数
 * - 静态目标调用的 handler 参数接收原调用参数
 * - 实例字段写入的 handler 参数先接收字段 owner，再接收待写入值
 * - 静态字段写入的 handler 参数接收待写入值
 * - 数组元素写入的 handler 参数先接收数组引用、`Int` 索引，再接收待写入元素值
 * - 后续参数可按顺序接收目标方法参数前缀
 * - [At.target] 必须指定要匹配的方法调用签名或字段签名
 *
 * @param method 目标方法签名
 * @param at 调用点定位；当前支持 [InjectionPoint.INVOKE] 与 [InjectionPoint.FIELD_ASSIGN]
 * @param ordinal 匹配点序号；`-1` 表示包裹全部匹配点，`0` 及以上表示只包裹第 N 个匹配点
 * @param slice 切片范围；当前仅 [InjectionPoint.INVOKE] 模式支持 INVOKE 边界切片
 * @param remap 是否启用重映射（当前实现未启用，字段仅作为元数据保留）
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class WrapWithCondition(
    val method: String = "",
    val at: At = At(value = InjectionPoint.INVOKE),
    val ordinal: Int = -1,
    val slice: Slice = Slice(),
    val remap: Boolean = false,
)

/**
 * 修改表达式值注解。
 *
 * 用于修改目标方法内某个表达式产生的值（语义参考 Mixin Extras 的 `@ModifyExpressionValue`）。
 * 当前实现支持 [InjectionPoint.INVOKE]、[InjectionPoint.INVOKE_ASSIGN]、[InjectionPoint.FIELD]、
 * [InjectionPoint.NEW] 与 [InjectionPoint.CAST]，可修改匹配方法调用完成后的非 `void` 返回值、字段读取值、
 * 数组元素读取值、数组长度值、对象构造完成后的实例或 `CHECKCAST` 完成后的类型转换结果。
 * 相比 [Redirect]，该注解不替换原调用、字段读取、数组读取、构造器调用或类型转换指令，只在表达式产生值后
 * 把原值交给 handler 改写。
 * [InjectionPoint.INVOKE] / [InjectionPoint.INVOKE_ASSIGN] 表达式可使用 [slice] 把候选调用限制在
 * 一段 INVOKE 边界内，边界指令本身不参与匹配。
 *
 * ASM 方法要求：
 *
 * - 第一个参数必须接收匹配表达式的原始值
 * - 返回类型必须与匹配表达式的值类型一致
 * - 后续参数可按顺序接收目标方法参数前缀
 * - 方法调用目标的 [At.target] 必须指定调用签名，字段读取目标必须指定字段签名
 * - 数组元素读取目标通过 [At.value] = [InjectionPoint.FIELD]、数组字段 [At.target] 与 [At.args] 中的 `array=get` 指定
 * - 数组长度目标通过 [At.value] = [InjectionPoint.FIELD]、数组字段 [At.target] 与 [At.args] 中的 `array=length` 指定
 * - [InjectionPoint.NEW] 的 [At.target] 为类型 internal name 或 binary name；handler 接收已初始化对象
 * - [InjectionPoint.CAST] 的 [At.target] 为类型 internal name 或 binary name；handler 接收转换完成后的同类型对象
 *
 * @param method 目标方法签名
 * @param at 表达式定位；当前支持 [InjectionPoint.INVOKE]、[InjectionPoint.INVOKE_ASSIGN]、[InjectionPoint.FIELD]、
 * [InjectionPoint.NEW] 与 [InjectionPoint.CAST]
 * @param ordinal 匹配表达式序号；`-1` 表示修改全部匹配表达式，`0` 及以上表示只修改第 N 个匹配表达式
 * @param slice 切片范围；当前 [InjectionPoint.INVOKE] / [InjectionPoint.INVOKE_ASSIGN] 表达式支持用
 * [Slice.from] / [Slice.to] 的 [InjectionPoint.INVOKE] 边界缩小查找范围
 * @param remap 是否启用重映射（当前实现未启用，字段仅作为元数据保留）
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ModifyExpressionValue(
    val method: String = "",
    val at: At = At(value = InjectionPoint.INVOKE),
    val ordinal: Int = -1,
    val slice: Slice = Slice(),
    val remap: Boolean = false,
)

/**
 * 修改局部变量注解。
 *
 * 用于修改目标方法中的参数或局部变量（语义参考 Mixin 的 `@ModifyVariable`）。
 * 当前实现支持在方法入口（[InjectionPoint.HEAD]）修改已有参数槽位，也支持在局部变量读取前
 *（[InjectionPoint.LOAD]）或写入后（[InjectionPoint.STORE]）改写槽位值。可以通过 JVM 局部变量槽位
 * [index] 精确定位，未指定 [index] 时按 handler 参数类型与 [ordinal] 选择匹配项。
 *
 * ASM 方法要求：
 *
 * - 第一个参数接收原始变量值并返回同类型的新值
 * - 后续参数可按目标方法声明顺序接收目标方法参数前缀
 * - [index] 使用 JVM 局部变量槽位索引；实例方法中 `this` 占用槽位 0，第一个参数从槽位 1 开始
 * - [InjectionPoint.HEAD] 会在目标方法体执行前写回选中的参数槽位
 * - [InjectionPoint.LOAD] 会在匹配的 xLOAD 指令前加载当前槽位值、调用 handler，并写回同一槽位
 * - [InjectionPoint.STORE] 会在匹配的 xSTORE 指令后加载新存入的值、调用 handler，并写回同一槽位
 * - [index] 为负数时，按 handler 第一个参数类型筛选入口参数、读取点或写入点，并用 [ordinal] 选择第 N 个同类型匹配项
 *
 * @param method 目标方法签名
 * @param at 修改位置；当前支持 [InjectionPoint.HEAD]、[InjectionPoint.LOAD] 与 [InjectionPoint.STORE]
 * @param index 要修改的局部变量槽位索引
 * @param ordinal 未指定 [index] 时，同类型入口参数、读取点或写入点的序号
 * @param slice 预留参数，当前实现未使用
 * @param remap 是否启用重映射（当前实现未启用，字段仅作为元数据保留）
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ModifyVariable(
    val method: String = "",
    val at: At = At(value = InjectionPoint.HEAD),
    val index: Int = -1,
    val ordinal: Int = -1,
    val slice: Slice = Slice(),
    val remap: Boolean = false,
)

/**
 * 修改返回值注解。
 *
 * 用于在目标方法返回前修改返回值。
 * 当前实现会在非 void 的 RETURN 指令前注入修改逻辑，可用 [ordinal] 只修改第 N 个返回点。
 *
 * ASM 方法要求：
 *
 * - 返回类型必须与目标方法返回类型一致
 * - 参数可选：可以只接收原始返回值，也可以追加目标方法的部分参数
 *
 * @param method 目标方法签名
 * @param at 预留参数，当前实现未使用
 * @param ordinal 返回点序号；`-1` 表示修改全部非 void 返回点，`0` 及以上表示只修改第 N 个返回点
 * @param remap 是否启用重映射（当前实现未启用，字段仅作为元数据保留）
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ModifyReturnValue(
    val method: String = "",
    val at: At = At(),
    val ordinal: Int = -1,
    val remap: Boolean = false,
)

/**
 * 修改常量注解。
 *
 * 用于修改目标方法中的常量值（语义参考 Mixin 的 `@ModifyConstant`）。
 * 当前实现会遍历字节码中的常量指令，并在匹配时用 ASM 方法返回值替换原常量。
 * 支持 `LDC`、`ACONST_NULL`、`ICONST_*`、`LCONST_*`、`FCONST_*`、`DCONST_*`、
 * `BIPUSH` 与 `SIPUSH` 形式的常量加载。
 *
 * ASM 方法要求：
 *
 * - 第一个参数接收原始常量值，后续参数可按顺序接收目标方法的部分参数
 * - 返回类型必须与被修改常量的类型一致
 *
 * @param method 目标方法签名
 * @param constant 常量匹配过滤；为空时不做值过滤（仅按类型匹配）
 * @param ordinal 匹配常量序号；`-1` 表示修改全部匹配常量，`0` 及以上表示只修改第 N 个匹配常量
 * @param remap 是否启用重映射（当前实现未启用，字段仅作为元数据保留）
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ModifyConstant(
    val method: String = "",
    val constant: String = "",
    val ordinal: Int = -1,
    val remap: Boolean = false,
)

/**
 * 重定向方法调用、构造器调用或字段访问注解。
 *
 * 用于将目标方法中的某个方法调用、构造器调用、字段读取、字段写入、简单数组元素访问或数组长度读取重定向到当前 ASM 方法（语义参考 Mixin 的 `@Redirect`）。
 * 当前实现会在字节码中查找匹配的调用指令、构造器调用指令、字段访问指令、数组元素访问指令或数组长度指令，并用重定向处理器调用替换原指令。
 *
 * 字段读取重定向通过 [At.value] 指定 [InjectionPoint.FIELD]，并通过 [At.target] 指定
 * `owner.field:desc`、`field:desc` 或 `field`；字段写入重定向通过 [At.value] 指定
 * [InjectionPoint.FIELD_ASSIGN]，目标格式相同。数组元素访问与数组长度重定向通过 [At.value] 指定 [InjectionPoint.FIELD]，
 * [At.target] 指定产生数组引用的字段，并通过 [At.args] 中的 `array=get`、`array=set` 或 `array=length` 区分读取、写入与长度读取。
 * 构造器重定向通过 [At.value] 指定 [InjectionPoint.INVOKE]，并使用 `<init>` 目标匹配常见
 * `NEW/DUP/args/<init>` 构造表达式。
 *
 * 重定向处理器要求：
 *
 * - 方法调用、构造器调用、字段读取、字段写入、数组元素访问与数组长度重定向支持静态方法、`@JvmStatic` 方法，或 Kotlin `object` 中的实例方法
 * - 方法调用重定向的参数栈形态需先与原调用保持一致（包括实例方法的 `this`），后续参数可按顺序接收目标方法的部分参数
 * - 构造器重定向的参数栈形态需先与原构造器参数保持一致，不接收未初始化 receiver，并返回构造器 owner 类型兼容对象
 * - 实例字段读取重定向的第一个参数为字段所属实例；静态字段读取重定向不需要接收者参数，后续参数可按顺序接收目标方法的部分参数
 * - 字段读取重定向的返回值类型需与字段类型兼容
 * - 实例字段写入重定向参数为字段所属实例与原写入值；静态字段写入重定向参数为原写入值，后续参数可按顺序接收目标方法的部分参数
 * - 字段写入重定向必须返回 `void`
 * - 数组读取重定向参数为数组引用与 `Int` 索引，返回元素值；数组写入重定向参数为数组引用、`Int` 索引与原元素值，返回 `void`
 * - 数组长度重定向参数为数组引用，返回 `Int`
 *
 * @param method 目标方法签名
 * @param target 目标调用、构造器或字段签名组件；会与 [At.target] 组合构建最终的匹配签名
 * @param at 调用点信息；[At.value] 决定重定向方法调用、构造器调用、字段读取、字段写入、数组元素访问还是数组长度读取，[At.target] 用于指定匹配签名
 * @param ordinal 匹配点序号；`-1` 表示重定向全部匹配点，当前在方法调用、构造器调用、字段读取、字段写入、数组元素访问与数组长度读取中生效
 * @param slice 切片范围；当前普通方法调用重定向支持用 [Slice.from] / [Slice.to] 的
 * [InjectionPoint.INVOKE] 边界缩小查找范围
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
    val ordinal: Int = -1,
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
 * 添加字段注解。
 *
 * 用于把 ASM 类中的字段声明复制到目标类。该注解只新增字段声明，不会复制字段初始化逻辑；
 * 非静态字段仍由 JVM 默认值初始化，静态字段也不会自动执行 ASM 类中的初始化代码。
 *
 * ## 使用边界
 *
 * - 当 [field] 为空时，使用 ASM 字段名作为目标字段名。
 * - 目标类已存在同名字段时会跳过，不会重复写入 [org.objectweb.asm.tree.ClassNode.fields]。
 * - 字段访问标志与类型来自被标注字段；泛型签名与初始值当前不复制。
 *
 * @param field 目标字段名；为空时使用被标注字段名
 * @param remap 是否启用重映射（当前实现未启用，字段仅作为元数据保留）
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class AddField(
    val field: String = "",
    val remap: Boolean = false,
)

/**
 * 移除字段注解。
 *
 * 用于在 ASM 类中标记需要从目标类移除的字段。该注解可标在函数上作为声明式删除入口，
 * 也可标在字段上直接使用字段名作为目标名称。
 *
 * ## 使用边界
 *
 * - 当 [field] 为空且注解标在字段上时，使用 ASM 字段名作为目标字段名。
 * - 当 [field] 为空且注解标在函数上时，会从 `removeXxx`、`getXxx`、`setXxx`、`isXxx` 方法名推断目标字段名。
 * - 目标字段不存在时转换会失败，避免静默遗漏预期的字节码修改。
 *
 * @param field 目标字段名；为空时按被标注成员名称推断
 * @param remap 是否启用重映射（当前实现未启用，字段仅作为元数据保留）
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RemoveField(
    val field: String = "",
    val remap: Boolean = false,
)

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

