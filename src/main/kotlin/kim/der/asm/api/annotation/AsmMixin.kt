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
 * 该注解作用于类级别，并会在方法级注解处理前遍历目标类的方法列表逐一替换。
 * 后续同一个 Mixin 中的 [Overwrite] 仍可定点覆盖某个方法，用于在全局默认替换后恢复关键方法实现。
 *
 * ## 使用边界
 *
 * - 会移除目标类非接口场景下的 `abstract` 类标志。
 * - 会移除目标方法的 `abstract` / `native` 标志，并清空原方法体、异常处理块、局部变量表和参数信息。
 * - 非静态字段会被置为非 `final`，以便替换后的方法可按默认运行期策略构造对象状态。
 * - 基本类型、`String` 与 `CharSequence` 返回值优先写入框架默认值；其他非 void 返回值会调用 [RedirectionReplaceApi.invokeIgnore]。
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
 * 该注解会复制 ASM 方法体、异常处理块和局部变量信息到目标方法，并保留目标方法的签名。
 * 若无法定位目标方法，或 ASM 方法体无法适配目标方法签名，转换会失败而不是静默跳过。
 *
 * ## 使用边界
 *
 * - 覆写会清空目标方法原有方法体，并移除目标方法的 `abstract` / `native` 标志。
 * - 目标方法签名以 [method] 指定；为空时按 ASM 方法名与描述符匹配。
 * - 当同一个 Mixin 同时使用 [ReplaceAllMethods] 时，类级全方法替换先执行，[Overwrite] 后执行，可覆盖全替换后的方法体。
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
 * 唯一成员标记注解。
 *
 * 用于标记 ASM 成员在复制到目标类时避免与目标类已有成员冲突（语义参考 Mixin 的 `@Unique`）。
 * 当前实现支持与 [Copy] 配合使用：当目标类已存在同名同描述符方法时，会把被复制方法重命名为私有 synthetic 方法，
 * 并同步改写同一个 ASM 类中 [Overwrite]、[Copy] 与 inline [AsmInject] 方法体内对该 [Copy] 方法的调用。
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Unique

/**
 * 修改参数注解。
 *
 * 用于修改目标方法参数，或目标方法内某次方法调用/构造器调用的参数值（语义参考 Mixin 的 `@ModifyArg`）。
 * 默认在目标方法入口直接写回参数槽位；当 [at] 指向 [InjectionPoint.INVOKE] 时，会改写匹配调用点的指定参数。
 *
 * ASM 方法要求：
 *
 * - 接收原始参数值并返回同类型的新值
 * - 第一个参数必须是被修改的原参数；后续参数可按顺序接收目标方法参数前缀
 * - 被修改参数或目标方法参数为对象/数组类型时，对应 handler 参数可声明为原值类型的父类、接口、`Any` 或 `Object`；返回类型对基础类型仍需精确匹配，对象/数组类型可返回可赋值给原参数类型的子类型
 * - 后续目标方法参数可用原值类型的父类、接口、`Any` 或 `Object` 接收
 * - INVOKE 模式只把 [index] 选中的调用参数作为第一个参数，不会自动传入目标调用的其他参数；构造器调用不暴露未初始化 receiver
 * - [require] / [allow] 可约束实际参数修改数量，目标字节码漂移时会在转换阶段失败
 * - [expect] 用于调试期望参数修改数量，不一致时只输出警告，不阻断转换
 *
 * @param method 目标方法签名
 * @param index 要修改的参数索引（从 0 开始）；入口模式下为目标方法参数索引，INVOKE 模式下为目标调用参数索引
 * @param at 注入位置；默认 HEAD 改写入口参数，`INVOKE` 时用 [At.target] 匹配目标方法调用、构造器调用或
 * `invokedynamic` 调用；`invokedynamic` 目标按 bootstrap owner、动态调用名或 bootstrap 名，以及动态调用点描述符匹配
 * @param ordinal 匹配调用点序号；`-1` 表示修改全部匹配调用点，当前仅在 INVOKE 模式下生效
 * @param slice 切片范围；当前 INVOKE 调用点参数修改支持用 [Slice.from] / [Slice.to] 的
 * [InjectionPoint.INVOKE] 边界缩小查找范围
 * @param require 最小命中数；大于 0 时实际参数修改数必须不少于该值
 * @param expect 期望命中数；设置为非默认值时不一致会输出警告
 * @param allow 最大命中数；大于等于 0 时实际参数修改数不能超过该值
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
    val require: Int = 0,
    val expect: Int = 1,
    val allow: Int = -1,
    val remap: Boolean = false,
)

/**
 * 修改调用参数组注解。
 *
 * 用于修改目标方法内某次方法调用、构造器调用或 `invokedynamic` 调用的整组参数（语义参考 Mixin 的 `@ModifyArgs`）。当需要同时改写
 * 同一个调用点的多个参数时，优先使用该注解，而不是叠加多个 [ModifyArg]。
 *
 * ASM 方法要求：
 *
 * - 第一个参数必须是 [Args]，用于读取和写回匹配调用点的参数
 * - 构造器调用使用 `<init>` 目标，且 [Args] 只包含构造器参数，不包含未初始化 receiver
 * - `invokedynamic` 调用没有 receiver，且 [Args] 只包含调用点描述符中的参数
 * - handler 必须返回 `void`
 * - 后续参数可按顺序接收目标方法参数前缀
 * - [At.value] 必须为 [InjectionPoint.INVOKE]，并通过 [At.target] 指定要匹配的方法调用、构造器调用或 `invokedynamic` 调用
 * - `invokedynamic` 目标按 bootstrap owner、动态调用名或 bootstrap 名，以及动态调用点描述符匹配
 * - [require] / [allow] 可约束实际参数组修改数量，目标字节码漂移时会在转换阶段失败
 * - [expect] 用于调试期望参数组修改数量，不一致时只输出警告，不阻断转换
 *
 * @param method 目标方法签名
 * @param at 调用点定位；当前仅支持 [InjectionPoint.INVOKE]，可匹配普通方法调用、构造器调用或 `invokedynamic` 调用
 * @param ordinal 匹配调用点序号；`-1` 表示修改全部匹配调用点，`0` 及以上表示只修改第 N 个匹配调用点
 * @param slice 切片范围；当前 INVOKE 调用点参数组修改支持用 [Slice.from] / [Slice.to] 的
 * [InjectionPoint.INVOKE] 边界缩小查找范围
 * @param require 最小命中数；大于 0 时实际参数组修改数必须不少于该值
 * @param expect 期望命中数；设置为非默认值时不一致会输出警告
 * @param allow 最大命中数；大于等于 0 时实际参数组修改数不能超过该值
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
    val require: Int = 0,
    val expect: Int = 1,
    val allow: Int = -1,
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
 * - 第一个参数必须接收原 receiver；对象或数组 receiver 可用原 receiver 类型的父类、接口、`Any` 或 `Object` 接收
 * - 返回类型对基础类型仍需精确匹配；对象或数组类型可返回可赋值给原 receiver 类型的子类型，`Any` / `Object` 也可作为泛型引用返回类型
 * - 后续参数可按顺序接收目标方法参数前缀
 * - [At.value] 为 [InjectionPoint.INVOKE] 时，通过 [At.target] 指定要匹配的实例方法调用
 * - [InjectionPoint.INVOKE]、[InjectionPoint.FIELD] 与 [InjectionPoint.FIELD_ASSIGN] 模式可使用 [slice]
 *   把候选 receiver 改写限制在一段 INVOKE 边界之间，边界指令本身不参与匹配
 * - [At.value] 为 [InjectionPoint.FIELD] 时匹配实例字段读取，为 [InjectionPoint.FIELD_ASSIGN] 时匹配实例字段写入
 * - [require] / [allow] 可约束实际 receiver 修改数量，目标字节码漂移时会在转换阶段失败
 * - [expect] 用于调试期望 receiver 修改数量，不一致时只输出警告，不阻断转换
 *
 * @param method 目标方法签名
 * @param at 调用点定位；当前支持 [InjectionPoint.INVOKE]、[InjectionPoint.FIELD] 与 [InjectionPoint.FIELD_ASSIGN]
 * @param ordinal 匹配点序号；`-1` 表示修改全部匹配点，`0` 及以上表示只修改第 N 个匹配点
 * @param slice 切片范围；当前 [InjectionPoint.INVOKE]、[InjectionPoint.FIELD] 与 [InjectionPoint.FIELD_ASSIGN]
 * receiver 改写支持用 [Slice.from] / [Slice.to] 的 [InjectionPoint.INVOKE] 边界缩小查找范围
 * @param require 最小命中数；大于 0 时实际 receiver 修改数必须不少于该值
 * @param expect 期望命中数；设置为非默认值时不一致会输出警告
 * @param allow 最大命中数；大于等于 0 时实际 receiver 修改数不能超过该值
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
    val require: Int = 0,
    val expect: Int = 1,
    val allow: Int = -1,
    val remap: Boolean = false,
)

/**
 * 包裹原始操作注解。
 *
 * 用于把目标方法内匹配的方法调用、`invokedynamic` 调用、构造器调用、字段读取、字段写入、数组元素读写、数组长度读取、类型转换或类型判断替换为 handler 调用（语义参考
 * Mixin Extras 的 `@WrapOperation`）。handler 会接收原操作的 receiver（实例调用、实例字段读取与
 * 实例字段写入）、原调用参数、动态调用点参数、构造器参数、字段写入值、数组访问参数或类型检查输入值与 [Operation]，
 * 可选择调用、跳过或多次调用原始操作。
 *
 * 当前实现支持 [InjectionPoint.INVOKE] 方法调用、`invokedynamic` 调用、[InjectionPoint.FIELD] 字段读取与
 * [InjectionPoint.FIELD_ASSIGN] 字段写入；[InjectionPoint.FIELD] 可通过 `args = ["array=get"]`
 * 包裹数组元素读取，通过 `args = ["array=length"]` 包裹数组长度读取；
 * [InjectionPoint.FIELD_ASSIGN] 可通过 `args = ["array=set"]` 包裹数组元素写入。
 * 构造器调用可通过 [InjectionPoint.INVOKE] 与 `<init>` 目标指定，也可通过 [InjectionPoint.NEW]
 * 与类型目标指定，当前支持常见 `NEW/DUP/args/<init>` 形态。
 * 类型转换通过 [InjectionPoint.CAST] 与类型 internal name 或 binary name 指定；
 * 类型判断通过 [InjectionPoint.INSTANCEOF] 与类型 internal name 或 binary name 指定。
 *
 * ASM 方法要求：
 *
 * - 实例调用的 handler 参数先接收 receiver，再接收原调用参数
 * - 静态调用的 handler 参数接收原调用参数
 * - `invokedynamic` 调用没有 receiver，handler 参数接收动态调用点描述符中的参数
 * - 构造器调用的 handler 参数先接收构造器参数，不接收未初始化 receiver
 * - `GETFIELD` 的 handler 参数先接收字段 owner
 * - `GETSTATIC` 的 handler 不接收字段 owner
 * - `PUTFIELD` 的 handler 参数先接收字段 owner，再接收待写入值
 * - `PUTSTATIC` 的 handler 参数接收待写入值
 * - 数组读取 handler 参数先接收数组引用与 `Int` 索引
 * - 数组写入 handler 参数先接收数组引用、`Int` 索引与待写入元素值
 * - 数组长度 handler 参数先接收数组引用
 * - 类型转换 handler 参数先接收待转换对象；`Operation.call(value)` 会执行原始 `CHECKCAST` 语义
 * - 类型判断 handler 参数先接收被判断对象；`Operation.call(value)` 会执行原始 `INSTANCEOF` 语义并返回 `Boolean`
 * - 下一参数必须是 [Operation]，用于执行原始调用、`invokedynamic` 调用、构造器调用、字段读取、字段写入、数组元素读写、数组长度读取、类型转换或类型判断
 * - handler 参数接收引用或数组栈值、目标方法参数时，可声明为原值类型的父类、接口、`Any` 或 `Object`
 * - handler 返回类型必须兼容原操作返回类型；基础类型需精确匹配，引用或数组返回值可为原返回类型的可赋值子类型，也可用 `Any` / `Object` 作为泛型引用返回类型
 * - 原调用为 `void` 时 handler 必须返回 `void`，构造器调用必须返回 owner 类型兼容对象
 * - 后续参数可按顺序接收目标方法参数前缀
 * - [At.value] 必须为 [InjectionPoint.INVOKE]、[InjectionPoint.FIELD]、[InjectionPoint.FIELD_ASSIGN]、
 *   [InjectionPoint.NEW] 或 [InjectionPoint.CAST] / [InjectionPoint.INSTANCEOF]，并通过 [At.target]
 *   指定要匹配的方法调用、`invokedynamic` 调用、字段读取、字段写入、产生数组引用的字段、构造类型或类型目标
 *
 * @param method 目标方法签名
 * @param at 操作点定位；当前支持 [InjectionPoint.INVOKE]、[InjectionPoint.FIELD]、[InjectionPoint.FIELD_ASSIGN]、
 * [InjectionPoint.NEW] 与 [InjectionPoint.CAST] / [InjectionPoint.INSTANCEOF]
 * @param ordinal 匹配操作点序号；`-1` 表示包裹全部匹配操作点，`0` 及以上表示只包裹第 N 个匹配操作点
 * @param slice 切片范围；当前 [InjectionPoint.INVOKE]、[InjectionPoint.FIELD]、
 * [InjectionPoint.FIELD_ASSIGN]、[InjectionPoint.NEW]、[InjectionPoint.CAST] /
 * [InjectionPoint.INSTANCEOF] 操作包裹支持用 [Slice.from] / [Slice.to] 的 [InjectionPoint.INVOKE] 边界缩小查找范围
 * @param require 最小命中数；大于 0 时实际操作包裹数必须不少于该值
 * @param expect 期望命中数；设置为非默认值时不一致会输出警告
 * @param allow 最大命中数；大于等于 0 时实际操作包裹数不能超过该值
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
    val require: Int = 0,
    val expect: Int = 1,
    val allow: Int = -1,
    val remap: Boolean = false,
)

/**
 * 包裹目标方法注解。
 *
 * 用于把整个目标方法体替换为 handler 调用（语义参考 Mixin Extras 的 `@WrapMethod`）。
 * handler 会接收目标方法参数与 [Operation]，可选择调用原方法、跳过原方法或多次调用原方法。
 * 转换时，原方法体会被迁移到私有 synthetic 方法，原方法名与原描述符会保留给新的 wrapper。
 *
 * ASM 方法要求：
 *
 * - handler 参数先按目标方法声明顺序接收原方法参数
 * - 对象/数组类型的目标方法参数可用其父类、接口、`Any` 或 `Object` 接收，基础类型仍需精确匹配
 * - 下一参数必须是 [Operation]，用于执行被包裹的原目标方法
 * - 实例目标方法不会把 `this` 作为 handler 参数；[Operation] 已绑定当前 receiver，[Operation.call] 只传目标方法参数
 * - handler 返回类型必须兼容目标方法返回类型；目标方法为 `void` 时 handler 必须返回 `Unit` / `void`
 * - 不支持构造器 `<init>`、类初始化器 `<clinit>`、`abstract` 方法或 `native` 方法
 * - [require] / [allow] 可约束实际整方法包裹数量，目标方法漂移时会在转换阶段失败
 * - [expect] 用于调试期望整方法包裹数量，不一致时只输出警告，不阻断转换
 *
 * @param method 目标方法签名
 * @param require 最小命中数；大于 0 时实际整方法包裹数必须不少于该值
 * @param expect 期望命中数；设置为非默认值时不一致会输出警告
 * @param allow 最大命中数；大于等于 0 时实际整方法包裹数不能超过该值
 * @param remap 是否启用重映射（当前实现未启用，字段仅作为元数据保留）
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class WrapMethod(
    val method: String = "",
    val require: Int = 0,
    val expect: Int = 1,
    val allow: Int = -1,
    val remap: Boolean = false,
)

/**
 * 条件包裹注解。
 *
 * 用于在目标方法内匹配 `void` 方法调用、返回 `void` 的 `invokedynamic` 调用、字段写入或简单数组元素写入前插入条件判断（语义参考
 * Mixin Extras 的 `@WrapWithCondition`）。handler 返回 `true` 时继续执行原调用或写入，
 * 返回 `false` 时跳过原指令。
 * 相比 [Redirect]，该注解不替换原逻辑，只决定原指令是否继续执行，更适合“按条件跳过副作用调用或写入”的场景。
 *
 * [InjectionPoint.INVOKE] 模式要求目标普通调用或 `invokedynamic` 调用返回类型必须为 `void`。
 * `invokedynamic` 目标按 bootstrap owner、动态调用名或 bootstrap 方法名，以及动态调用点描述符匹配。
 * [InjectionPoint.FIELD_ASSIGN] 模式匹配
 * `PUTFIELD` / `PUTSTATIC` 字段写入，字段目标格式支持 `owner.field:desc`、`field:desc` 与 `field`。
 * 数组元素写入使用 [InjectionPoint.FIELD_ASSIGN]、数组字段 [At.target] 与 [At.args] 中的 `array=set` 指定，
 * 当前匹配由最近的目标数组字段读取产生数组引用的 `xASTORE` 指令。
 * [InjectionPoint.INVOKE] 与 [InjectionPoint.FIELD_ASSIGN] 模式可使用 [slice] 把候选调用、字段写入或数组元素写入
 * 限制在一段 INVOKE 边界之间，边界指令本身不参与匹配。
 *
 * ASM 方法要求：
 *
 * - handler 必须返回 `Boolean`
 * - 非静态目标调用的 handler 参数先接收 receiver，再接收原调用参数
 * - 静态目标调用的 handler 参数接收原调用参数
 * - `invokedynamic` 目标调用没有 receiver，handler 参数接收动态调用点描述符中的参数
 * - 实例字段写入的 handler 参数先接收字段 owner，再接收待写入值
 * - 静态字段写入的 handler 参数接收待写入值
 * - 数组元素写入的 handler 参数先接收数组引用、`Int` 索引，再接收待写入元素值
 * - 后续参数可按顺序接收目标方法参数前缀
 * - [At.target] 必须指定要匹配的方法调用、动态调用或字段签名
 *
 * @param method 目标方法签名
 * @param at 调用点定位；当前支持 [InjectionPoint.INVOKE] 与 [InjectionPoint.FIELD_ASSIGN]
 * @param ordinal 匹配点序号；`-1` 表示包裹全部匹配点，`0` 及以上表示只包裹第 N 个匹配点
 * @param slice 切片范围；当前 [InjectionPoint.INVOKE] 与 [InjectionPoint.FIELD_ASSIGN] 模式支持 INVOKE 边界切片
 * @param require 最小命中数；大于 0 时实际条件包裹数必须不少于该值
 * @param expect 期望命中数；设置为非默认值时不一致会输出警告
 * @param allow 最大命中数；大于等于 0 时实际条件包裹数不能超过该值
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
    val require: Int = 0,
    val expect: Int = 1,
    val allow: Int = -1,
    val remap: Boolean = false,
)

/**
 * 修改表达式值注解。
 *
 * 用于修改目标方法内某个表达式产生的值（语义参考 Mixin Extras 的 `@ModifyExpressionValue`）。
 * 当前实现支持 [InjectionPoint.INVOKE]、[InjectionPoint.INVOKE_ASSIGN]、[InjectionPoint.FIELD]、
 * [InjectionPoint.NEW]、[InjectionPoint.CAST]、[InjectionPoint.INSTANCEOF] 与 [InjectionPoint.THROW]，可修改匹配普通方法调用或
 * `invokedynamic` 调用完成后的非 `void` 返回值、字段读取值、数组元素读取值、数组长度值、对象构造完成后的实例、
 * `CHECKCAST` 完成后的类型转换结果或 `INSTANCEOF` 判断后的 boolean 结果，也可在 `ATHROW` 前改写即将抛出的异常对象。
 * 相比 [Redirect]，该注解不替换原调用、字段读取、数组读取、构造器调用、类型转换、类型判断或抛异常指令，只在表达式产生值后
 * 把原值交给 handler 改写。
 * [InjectionPoint.INVOKE] / [InjectionPoint.INVOKE_ASSIGN] 调用返回、[InjectionPoint.FIELD] 字段读取、
 * 数组元素读取、数组长度、
 * [InjectionPoint.NEW]、[InjectionPoint.CAST]、[InjectionPoint.INSTANCEOF] 与 [InjectionPoint.THROW] 表达式可使用 [slice] 把候选点限制在
 * 一段 INVOKE 边界内，边界指令本身不参与匹配。
 *
 * ASM 方法要求：
 *
 * - 第一个参数必须接收匹配表达式的原始值；对象或数组表达式可用原值类型的父类、接口、`Any` 或 `Object` 接收
 * - primitive 返回类型必须与匹配表达式的值类型一致；引用类型表达式可返回匹配类型的子类型；[InjectionPoint.THROW] 可返回 `Throwable` 或其子类
 * - 后续参数可按顺序接收目标方法参数前缀
 * - 方法调用目标的 [At.target] 必须指定调用签名；`invokedynamic` 目标按 bootstrap owner、动态调用名或 bootstrap 名，
 *   以及动态调用点描述符匹配，例如 `java/lang/invoke/StringConcatFactory.makeConcatWithConstants(Ljava/lang/String;)Ljava/lang/String;`
 * - 字段读取目标必须指定字段签名
 * - 数组元素读取目标通过 [At.value] = [InjectionPoint.FIELD]、数组字段 [At.target] 与 [At.args] 中的 `array=get` 指定
 * - 数组长度目标通过 [At.value] = [InjectionPoint.FIELD]、数组字段 [At.target] 与 [At.args] 中的 `array=length` 指定
 * - [InjectionPoint.NEW] 的 [At.target] 为类型 internal name 或 binary name；handler 接收已初始化对象
 * - [InjectionPoint.CAST] 的 [At.target] 为类型 internal name 或 binary name；handler 接收转换完成后的同类型对象
 * - [InjectionPoint.INSTANCEOF] 的 [At.target] 为类型 internal name 或 binary name；handler 接收 `Boolean` 判断结果
 * - [InjectionPoint.THROW] 不需要 [At.target]；handler 接收即将抛出的 `Throwable` 并返回新的 `Throwable` 或具体异常子类
 * - [require] / [allow] 可约束实际表达式值修改数量，目标字节码漂移时会在转换阶段失败
 * - [expect] 用于调试期望表达式值修改数量，不一致时只输出警告，不阻断转换
 *
 * @param method 目标方法签名
 * @param at 表达式定位；当前支持 [InjectionPoint.INVOKE]、[InjectionPoint.INVOKE_ASSIGN]、[InjectionPoint.FIELD]、
 * [InjectionPoint.NEW]、[InjectionPoint.CAST]、[InjectionPoint.INSTANCEOF] 与 [InjectionPoint.THROW]
 * @param ordinal 匹配表达式序号；`-1` 表示修改全部匹配表达式，`0` 及以上表示只修改第 N 个匹配表达式
 * @param slice 切片范围；当前 [InjectionPoint.INVOKE] / [InjectionPoint.INVOKE_ASSIGN] 调用返回、
 * [InjectionPoint.FIELD] 字段读取、数组元素读取、数组长度、[InjectionPoint.NEW]、[InjectionPoint.CAST] 与
 * [InjectionPoint.INSTANCEOF] / [InjectionPoint.THROW] 表达式支持用 [Slice.from] / [Slice.to] 的 [InjectionPoint.INVOKE]
 * 边界缩小查找范围
 * @param require 最小命中数；大于 0 时实际表达式值修改数必须不少于该值
 * @param expect 期望命中数；设置为非默认值时不一致会输出警告
 * @param allow 最大命中数；大于等于 0 时实际表达式值修改数不能超过该值
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
    val require: Int = 0,
    val expect: Int = 1,
    val allow: Int = -1,
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
 * - 显式指定 [index] 时，对象/数组变量或目标方法参数对应的 handler 参数可声明为原值类型的父类、接口、`Any` 或 `Object`；返回类型对基础类型仍需精确匹配，对象/数组类型可返回可赋值给原变量类型的子类型
 * - [InjectionPoint.HEAD] 会在目标方法体执行前写回选中的参数槽位
 * - [InjectionPoint.LOAD] 会在匹配的 xLOAD 指令前加载当前槽位值、调用 handler，并写回同一槽位
 * - [InjectionPoint.STORE] 会在匹配的 xSTORE 指令后加载新存入的值、调用 handler，并写回同一槽位
 * - [InjectionPoint.LOAD] / [InjectionPoint.STORE] 可使用 [slice] 把候选读取点或写入点限制在一段 INVOKE 边界之间，
 *   边界指令本身不参与匹配
 * - [index] 为负数时，按 handler 第一个参数类型筛选入口参数、读取点或写入点，并用 [ordinal] 选择第 N 个同类型匹配项
 * - [require] / [allow] 可约束实际变量修改数量，目标字节码漂移时会在转换阶段失败
 * - [expect] 用于调试期望变量修改数量，不一致时只输出警告，不阻断转换
 *
 * @param method 目标方法签名
 * @param at 修改位置；当前支持 [InjectionPoint.HEAD]、[InjectionPoint.LOAD] 与 [InjectionPoint.STORE]
 * @param index 要修改的局部变量槽位索引
 * @param ordinal 未指定 [index] 时，同类型入口参数、读取点或写入点的序号
 * @param slice 切片范围；当前 [InjectionPoint.LOAD] 局部变量读取改写与 [InjectionPoint.STORE] 局部变量写入改写
 * 支持用 [Slice.from] / [Slice.to] 的 [InjectionPoint.INVOKE] 边界缩小查找范围
 * @param require 最小命中数；大于 0 时实际变量修改数必须不少于该值
 * @param expect 期望命中数；设置为非默认值时不一致会输出警告
 * @param allow 最大命中数；大于等于 0 时实际变量修改数不能超过该值
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
    val require: Int = 0,
    val expect: Int = 1,
    val allow: Int = -1,
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
 * - primitive 返回类型必须与目标方法返回类型一致；对象/数组返回类型可为目标类型的子类型
 * - 参数可选：可以只接收原始返回值，也可以追加目标方法的部分参数
 * - 原返回值或目标方法参数为对象/数组类型时，对应 handler 参数可声明为原值类型的父类、接口、`Any` 或 `Object`
 * - [require] / [allow] 可约束实际返回值修改数量，目标字节码漂移时会在转换阶段失败
 * - [expect] 用于调试期望返回值修改数量，不一致时只输出警告，不阻断转换
 *
 * @param method 目标方法签名
 * @param at 预留参数，当前实现未使用
 * @param ordinal 返回点序号；`-1` 表示修改全部非 void 返回点，`0` 及以上表示只修改第 N 个返回点
 * @param require 最小命中数；大于 0 时实际返回值修改数必须不少于该值
 * @param expect 期望命中数；设置为非默认值时不一致会输出警告
 * @param allow 最大命中数；大于等于 0 时实际返回值修改数不能超过该值
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
    val require: Int = 0,
    val expect: Int = 1,
    val allow: Int = -1,
    val remap: Boolean = false,
)

/**
 * 修改常量注解。
 *
 * 用于修改目标方法中的常量值（语义参考 Mixin 的 `@ModifyConstant`）。
 * 当前实现会遍历字节码中的常量指令，并在匹配时用 ASM 方法返回值替换原常量。
 * 支持 `LDC` 字符串、数字、类字面量（handler 使用 `Class<*>` / `java.lang.Class`）、
 * 方法类型字面量（handler 使用 `java.lang.invoke.MethodType`）
 * 与方法句柄字面量（handler 使用 `java.lang.invoke.MethodHandle`），以及动态常量、
 * `ACONST_NULL`、`ICONST_*`、`LCONST_*`、`FCONST_*`、`DCONST_*`、`BIPUSH` 与 `SIPUSH`
 * 形式的常量加载；显式 [constant] 为 `"true"` 或 `"false"` 时，会将 `ICONST_1` 或 `ICONST_0`
 * 按 boolean 常量处理。
 *
 * ASM 方法要求：
 *
 * - 第一个参数接收原始常量值，后续参数可按顺序接收目标方法的部分参数
 * - 返回类型必须与被修改常量的类型一致；当常量类型为引用或数组时，返回值可为该常量类型的可赋值子类型
 * - [slice] 可把候选常量限制在 [Slice.from] / [Slice.to] 的 [InjectionPoint.INVOKE] 边界之间，
 *   边界指令本身不参与匹配，且 [ordinal] 会在切片内重新计数
 * - [require] / [allow] 可约束实际替换的常量数量，目标字节码漂移时会在转换阶段失败
 * - [expect] 用于调试期望命中数，不一致时只输出警告，不阻断转换
 *
 * @param method 目标方法签名
 * @param constant 常量匹配过滤；为空时不做值过滤（仅按类型匹配）；`"true"` / `"false"` 可匹配 boolean 常量；
 * 类字面量可使用 internal name 或 binary name，例如 `java/lang/String` 或 `java.lang.String`；
 * 方法类型常量可使用 JVM 方法描述符，例如 `(I)Ljava/lang/String;`；
 * 方法句柄常量可使用 `owner.name(desc)`，例如 `java/lang/String.valueOf(I)Ljava/lang/String;`；
 * 动态常量可使用常量名或 `name:descriptor`，例如 `dynamicText:Ljava/lang/String;`
 * @param ordinal 匹配常量序号；`-1` 表示修改全部匹配常量，`0` 及以上表示只修改第 N 个匹配常量
 * @param slice 切片范围；当前常量修改支持用 [Slice.from] / [Slice.to] 的
 * [InjectionPoint.INVOKE] 边界缩小查找范围
 * @param require 最小命中数；大于 0 时实际命中数必须不少于该值
 * @param expect 期望命中数；设置为非默认值时不一致会输出警告
 * @param allow 最大命中数；大于等于 0 时实际命中数不能超过该值
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
    val slice: Slice = Slice(),
    val require: Int = 0,
    val expect: Int = 1,
    val allow: Int = -1,
    val remap: Boolean = false,
)

/**
 * 重定向方法调用、`invokedynamic` 调用、构造器调用、NEW 构造表达式、字段访问、类型转换或类型判断注解。
 *
 * 用于将目标方法中的某个方法调用、`invokedynamic` 调用、构造器调用、NEW 构造表达式、字段读取、字段写入、简单数组元素访问、数组长度读取、
 * `CHECKCAST` 类型转换或 `INSTANCEOF` 类型判断重定向到当前 ASM 方法（语义参考 Mixin 的 `@Redirect`）。
 * 当前实现会在字节码中查找匹配的调用指令、动态调用指令、构造器调用指令、字段访问指令、数组元素访问指令、数组长度指令或
 * 类型转换/类型判断指令，并用重定向处理器调用替换原指令。
 *
 * [InjectionPoint.INVOKE] 可匹配普通方法调用、构造器调用或 `invokedynamic` 调用。
 * `invokedynamic` 目标会按 bootstrap owner、动态调用名或 bootstrap 方法名，以及动态调用点描述符匹配。
 * 字段读取重定向通过 [At.value] 指定 [InjectionPoint.FIELD]，并通过 [At.target] 指定
 * `owner.field:desc`、`field:desc` 或 `field`；字段写入重定向通过 [At.value] 指定
 * [InjectionPoint.FIELD_ASSIGN]，目标格式相同。数组元素访问与数组长度重定向通过 [At.value] 指定 [InjectionPoint.FIELD]，
 * [At.target] 指定产生数组引用的字段，并通过 [At.args] 中的 `array=get`、`array=set` 或 `array=length` 区分读取、写入与长度读取。
 * 构造器重定向可通过 [At.value] 指定 [InjectionPoint.INVOKE] 并使用 `<init>` 目标匹配，也可通过
 * [InjectionPoint.NEW] 与构造类型目标匹配，当前支持常见 `NEW/DUP/args/<init>` 构造表达式。
 * 类型转换重定向通过 [At.value] 指定 [InjectionPoint.CAST]，并通过 [At.target] 指定要替换的类型
 * internal name 或 binary name。
 * 类型判断重定向通过 [At.value] 指定 [InjectionPoint.INSTANCEOF]，并通过 [At.target] 指定要替换的类型
 * internal name 或 binary name。
 *
 * 重定向处理器要求：
 *
 * - 方法调用、`invokedynamic` 调用、构造器调用、NEW 构造表达式、字段读取、字段写入、数组元素访问、数组长度、类型转换与类型判断重定向支持静态方法、`@JvmStatic` 方法，或 Kotlin `object` 中的实例方法
 * - 方法调用重定向的参数栈形态需先与原调用保持一致（包括实例方法的 `this`），后续参数可按顺序接收目标方法的部分参数
 * - `invokedynamic` 重定向没有 receiver，handler 先接收动态调用点描述符中的参数，后续参数可按顺序接收目标方法的部分参数
 * - handler 参数接收引用或数组栈值时，可声明为原值类型的父类、接口、`Any` 或 `Object`；基础类型参数仍需精确匹配
 * - 构造器重定向的参数栈形态需先与原构造器参数保持一致，不接收未初始化 receiver，并返回构造器 owner 类型兼容对象；对象或数组返回值可为原 owner 类型的可赋值子类型，
 *   也可用 `Any` / `Object` 作为泛型引用返回类型
 * - 实例字段读取重定向的第一个参数为字段所属实例；静态字段读取重定向不需要接收者参数，后续参数可按顺序接收目标方法的部分参数
 * - 字段读取重定向的返回值类型需与字段类型兼容；基础类型需精确匹配，引用或数组返回值可为原返回类型的可赋值子类型，也可用 `Any` / `Object` 作为泛型引用返回类型
 * - 实例字段写入重定向参数为字段所属实例与原写入值；静态字段写入重定向参数为原写入值，后续参数可按顺序接收目标方法的部分参数
 * - 字段写入重定向必须返回 `void`
 * - 数组读取重定向参数为数组引用与 `Int` 索引，返回元素值；数组写入重定向参数为数组引用、`Int` 索引与原元素值，返回 `void`
 * - 数组长度重定向参数为数组引用，返回 `Int`
 * - 类型转换重定向参数为原待转换对象，返回目标类型兼容对象，后续参数可按顺序接收目标方法的部分参数
 * - 类型判断重定向参数为原被判断对象，返回 `Boolean`，后续参数可按顺序接收目标方法的部分参数
 * - [require] / [allow] 可约束实际重定向数量，目标字节码漂移时会在转换阶段失败
 * - [expect] 用于调试期望重定向数量，不一致时只输出警告，不阻断转换
 *
 * @param method 目标方法签名
 * @param target 目标调用、动态调用、构造器、字段、构造类型或类型签名组件；会与 [At.target] 组合构建最终的匹配签名
 * @param at 调用点信息；[At.value] 决定重定向方法调用、`invokedynamic` 调用、构造器调用、NEW 构造表达式、字段读取、字段写入、数组元素访问、数组长度读取、类型转换还是类型判断，[At.target] 用于指定匹配签名
 * @param ordinal 匹配点序号；`-1` 表示重定向全部匹配点，当前在方法调用、`invokedynamic` 调用、构造器调用、NEW 构造表达式、字段读取、字段写入、数组元素访问、数组长度读取、类型转换与类型判断中生效
 * @param slice 切片范围；当前方法调用、`invokedynamic` 调用、构造器调用、NEW 构造表达式、字段读取、字段写入、数组元素访问、数组长度、类型转换与类型判断重定向
 * 支持用 [Slice.from] / [Slice.to] 的 [InjectionPoint.INVOKE] 边界缩小查找范围
 * @param require 最小命中数；大于 0 时实际重定向数必须不少于该值
 * @param expect 期望命中数；设置为非默认值时不一致会输出警告
 * @param allow 最大命中数；大于等于 0 时实际重定向数不能超过该值
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
    val require: Int = 0,
    val expect: Int = 1,
    val allow: Int = -1,
    val remap: Boolean = false,
)

/**
 * Shadow 字段/方法注解。
 *
 * 用于在 ASM 类中声明对目标类字段/方法的“引用占位”，以便在转换阶段进行校验或修饰符调整（语义参考 Mixin 的 `@Shadow`）。
 * 当前实现会基于 [method] 与 [prefix] 解析目标名称：
 *
 * - 当 [method] 为空时，默认使用字段名/方法名
 * - 当 [method] 以 [prefix] 开头时，目标名称为去掉前缀后的部分
 * - 否则将 [method] 作为显式目标字段名/方法名
 *
 * `@Overwrite` 等复制方法体的场景会把对 Shadow 成员的引用改写为目标类对应成员。
 *
 * @param method 目标名称提示；为空时使用声明名，非空时可直接指定目标名或使用 `shadow_` 前缀
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
 * 用于在目标类上生成字段访问器方法（语义参考 Mixin 的 `@Accessor`）。
 *
 * 无参数且返回字段类型的方法会生成 getter；一个参数且返回 `void` 的方法会生成 setter。
 * 当 [value] 为空时，会按 `getXxx`、`setXxx` 或 `isXxx` 方法名推断目标字段名。
 *
 * ## 使用边界
 *
 * - 目标字段不存在时转换失败。
 * - getter 返回类型、setter 参数类型必须与目标字段类型一致。
 * - 静态字段要求访问器方法也是静态方法；Kotlin `object` 中通常需要配合 `@JvmStatic`。
 * - setter 标记 [Mutable] 时会移除目标字段的 `final` 标志。
 * - 生成的方法若与目标类已有同名同描述符方法冲突，转换会失败。
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
 * 用于生成“私有/受保护方法或构造器的调用器”（语义参考 Mixin 的 `@Invoker`）。
 *
 * 普通方法调用器会在目标类中生成一个同签名桥接方法，并按目标方法的静态性选择
 * `INVOKEVIRTUAL`、`INVOKESPECIAL`、`INVOKESTATIC` 或 `INVOKEINTERFACE`。当 [value] 为空时，
 * 会从 `callXxx` 或 `invokeXxx` 方法名推断目标方法名。
 *
 * 构造器调用器使用 `@Invoker("<init>")` 声明。此时 ASM 方法必须是静态方法，
 * 参数用于匹配目标构造器，返回类型必须是目标类或 `Any` / `java.lang.Object`，
 * 转换后会生成创建目标类实例的静态工厂方法。
 *
 * ## 使用边界
 *
 * - 目标方法或构造器不存在时转换失败。
 * - 普通调用器的参数与返回值必须与目标方法描述符一致。
 * - 普通调用器的静态性必须与目标方法一致。
 * - 生成的方法若与目标类已有同名同描述符方法冲突，转换会失败。
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

