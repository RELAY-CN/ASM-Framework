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
 * ## Handler 参数
 *
 * - HEAD、TAIL、RETURN 与普通指令点注入可在 [CallbackInfo] 后按顺序接收目标方法参数前缀。
 * - INVOKE 的 BEFORE/AFTER 注入会先接收匹配调用的方法参数前缀，再继续接收目标方法参数前缀；实例调用的 receiver
 *   会被框架保存和恢复，但不会作为普通 handler 参数传入。
 * - INVOKE 的 REPLACE 注入按替换原调用处理，handler 参数对应原调用参数，返回值需要与原调用返回类型兼容。
 *
 * ## 命中数契约
 *
 * - 默认要求至少命中 1 个注入点，以便目标方法或指令点漂移时快速失败。
 * - [require] 大于 0 时作为最小命中数；实际命中数不足会在转换阶段失败。
 * - [allow] 大于等于 0 时作为最大命中数；实际命中数超出会在转换阶段失败。
 * - [expect] 设置为非默认值时作为期望命中数；不一致时输出警告，但不阻断转换。
 *
 * @param method 目标方法签名，格式：`方法名(参数类型)返回类型`，例如 `"methodName(Ljava/lang/String;)V"`
 * @param target 注入点类型；普通注入支持 HEAD/TAIL/RETURN/INVOKE/FIELD/FIELD_ASSIGN/LOAD/STORE/NEW/CAST/THROW，
 * INSTANCEOF 当前仅用于 [ModifyExpressionValue]
 * @param cancellable 是否声明该注入点允许取消（当前实现不会基于该开关屏蔽取消分支，仅作为元数据保留）
 * @param require 最小命中数；大于 0 时实际命中数必须不少于该值
 * @param at 当 [target] 为 INVOKE/FIELD/FIELD_ASSIGN/LOAD/STORE/NEW/CAST 时用于描述具体指令点；
 * 核心字段为 [At.target] 与 [At.shift]，其中 LOAD/STORE 当前只使用 [At.shift]
 * @param ordinal 匹配点序号；-1 表示处理全部匹配点，0 及以上表示只处理第 N 个匹配点（当前对 RETURN/INVOKE/INVOKE_ASSIGN 与指令点注入生效）
 * @param slice 切片范围；当前普通 [InjectionPoint.INVOKE] 注入支持用 [Slice.from] / [Slice.to]
 * 的 [InjectionPoint.INVOKE] 边界缩小查找范围
 * @param allow 最大命中数；大于等于 0 时实际命中数不能超过该值
 * @param expect 期望命中数；设置为非默认值时不一致会输出警告
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
 * 用于描述代码注入的位置。普通注入支持 [HEAD]、[TAIL]、[RETURN]、[INVOKE]、[FIELD]、
 * [FIELD_ASSIGN]、[LOAD]、[STORE]、[NEW]、[CAST] 与 [THROW]；[INSTANCEOF] 当前用于
 * [kim.der.asm.api.annotation.ModifyExpressionValue]。
 * 其中指令点注入会在匹配指令前后插入 handler，
 * 不会替换原始指令、自动传递栈顶操作数或局部变量值。
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

    /** 局部变量读取前 */
    LOAD,

    /** 局部变量写入后 */
    STORE,

    /** NEW 操作前 */
    NEW,

    /** 类型转换后 */
    CAST,

    /** instanceof 判断后 */
    INSTANCEOF,

    /** 抛出异常前 */
    THROW,
}

/**
 * 调用点定位信息。
 *
 * 当前用于精确描述 [InjectionPoint.INVOKE]、[InjectionPoint.FIELD]、[InjectionPoint.FIELD_ASSIGN]、
 * [InjectionPoint.NEW]、[InjectionPoint.CAST] 与 [InjectionPoint.INSTANCEOF] 的匹配目标，
 * 并通过 [shift] 指定在匹配指令前/后插入 handler。
 *
 * 注意：
 *
 * - INVOKE 目标要求包含方法或构造器描述符；owner 可省略，省略时只按方法名与描述符匹配。
 * - FIELD/FIELD_ASSIGN 目标格式为 `Owner.field:Desc`，owner 与 desc 均可省略。
 * - NEW 目标为类型 internal name 或 binary name，例如 `java/lang/StringBuilder` 或 `java.lang.StringBuilder`。
 * - NEW 只支持 BEFORE 或 REPLACE；AFTER 会在未初始化对象仍位于栈顶时插入调用，当前实现会拒绝该配置。
 * - CAST 目标为 `CHECKCAST` 的类型 internal name 或 binary name，例如 `java/lang/String` 或 `java.lang.String`。
 * - INSTANCEOF 目标为 `INSTANCEOF` 的类型 internal name 或 binary name，例如 `java/lang/String` 或 `java.lang.String`。
 * - REPLACE 对指令点注入当前按 BEFORE 处理，不删除原始指令。
 * - [Redirect] 可通过 [args] 中的 `array=get`、`array=set` 或 `array=length`，
 *   把 [InjectionPoint.FIELD] 目标解释为数组元素读取、写入或数组长度读取。
 * - [WrapOperation] 可通过 [args] 中的 `array=get`、`array=set` 或 `array=length`，
 *   把 [InjectionPoint.FIELD] / [InjectionPoint.FIELD_ASSIGN] 目标解释为数组元素读取、写入或数组长度读取。
 * - [WrapWithCondition] 可通过 [args] 中的 `array=set`，把 [InjectionPoint.FIELD_ASSIGN] 目标解释为数组元素写入。
 * - [kim.der.asm.api.annotation.ModifyExpressionValue] 可通过 [args] 中的 `array=get` 或 `array=length`，
 *   把 [InjectionPoint.FIELD] 目标解释为数组元素读取表达式或数组长度表达式。
 *
 * @param value 注入点类型；用于描述当前 [target] 的匹配语义，普通 [InjectionPoint.INVOKE] 注入的 [Slice] 边界
 * 当前仅支持 [InjectionPoint.INVOKE]
 * @param target 目标方法调用、构造器调用、字段、NEW 类型、CHECKCAST 类型或 INSTANCEOF 类型签名
 * @param shift 注入偏移策略
 * @param by 预留参数，当前实现未实现按字节码偏移移动
 * @param args 附加定位参数；当前 [Redirect] 支持 `array=get`、`array=set` 与 `array=length`，
 * [WrapOperation] 支持 `array=get`、`array=set` 与 `array=length`，[WrapWithCondition] 支持 `array=set`，
 * [ModifyExpressionValue] 支持 `array=get` 与 `array=length`
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
 * 用于描述在某段字节码范围内查找注入点的起止条件。当前普通 [AsmInject] 的
 * [InjectionPoint.INVOKE] 注入、[Redirect] 的普通方法调用重定向，[ModifyArg] / [ModifyArgs] 的
 * [InjectionPoint.INVOKE] 调用点参数修改，[ModifyReceiver] 的 [InjectionPoint.INVOKE] receiver 改写，
 * [WrapOperation] / [WrapWithCondition] 的 [InjectionPoint.INVOKE] 操作包裹或条件包裹，以及
 * [ModifyExpressionValue] 的 [InjectionPoint.INVOKE] 表达式值修改、[ModifyVariable] 的
 * [InjectionPoint.LOAD] / [InjectionPoint.STORE] 局部变量读写改写，以及 [ModifyConstant] 常量修改
 * 支持 [from] / [to] 为 [InjectionPoint.INVOKE] 的边界；
 * 起始边界之后、结束边界之前的调用点才会参与匹配，边界指令本身不会作为候选注入点。
 * [AsmInject.ordinal] / [Redirect.ordinal] / [ModifyArg.ordinal] / [ModifyArgs.ordinal] /
 * [ModifyReceiver.ordinal] / [WrapOperation.ordinal] / [WrapWithCondition.ordinal] /
 * [ModifyExpressionValue.ordinal] / [ModifyVariable.ordinal] / [ModifyConstant.ordinal] 会在切片内重新计数。
 * 指定的边界未命中时，切片按空范围处理。
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

