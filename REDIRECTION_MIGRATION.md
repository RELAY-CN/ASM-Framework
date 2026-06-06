# Redirection 迁移说明

## 背景

旧版 `RedirectionReplaceApi`、`RedirectionReplaceManager`、`RedirectionListener`、`MethodTypeInfoValue` 及其 `AsmReplace` / `AsmListener`
适配层已经移除。框架现在只保留注解式 ASM API 与内部默认返回值生成器。

## 新方案

优先使用下列注解完成字节码修改：

- `@Redirect`
- `@WrapOperation`
- `@WrapWithCondition`
- `@ModifyExpressionValue`
- `@ModifyConstant`
- `@ModifyVariable`
- `@ModifyArg`
- `@ModifyArgs`
- `@ModifyReceiver`
- `@WrapMethod`
- `@ReplaceAllMethods`

`@ReplaceAllMethods` 仍可用于“整方法替换”为默认返回值，但内部不再走旧式 Redirection manager 分派。

## 迁移对照

| 旧意图 | 新注解 | 选型说明 |
| --- | --- | --- |
| 完全替换一次调用、字段访问、局部变量表达式或分支结果 | `@Redirect` | handler 直接提供替代结果或替代副作用，不保留原操作句柄 |
| 在 handler 内按需调用、跳过或多次执行原操作 | `@WrapOperation` | 需要 `Operation` 句柄、要组合原参数或多次调用时优先使用 |
| 只按条件决定是否保留原调用、调用返回值、字段读写、数组元素读取/写入、数组长度、构造结果、变量读写、类型转换、类型判断、常量、分支、switch 分派或抛异常 | `@WrapWithCondition` | handler 返回 `Boolean`，框架负责保留原值或写入默认值 |
| 保留原操作，只改写表达式结果或待写入值 | `@ModifyExpressionValue` | 适合字段读取值、字段待写入值、调用返回值、局部变量表达式等后置调整 |
| 只改一个调用、构造器或 invokedynamic 参数 | `@ModifyArg` | 保留原调用，只替换指定实参；字符串参数替换也走此路径 |
| 批量改写一次调用、构造器或 invokedynamic 参数组 | `@ModifyArgs` | handler 接收 `Args` 容器，可一次读取和写回整组参数 |
| 只替换实例方法调用或实例字段访问 receiver | `@ModifyReceiver` | 保留原参数、字段值与原操作逻辑，只把 receiver 换成 handler 返回值 |
| 包裹整个目标方法并按需调用原方法 | `@WrapMethod` | handler 接收 `Operation` 原方法句柄，可跳过、调用一次或多次执行原方法 |
| 把整类方法替换为默认返回值 | `@ReplaceAllMethods` | 只用于默认实现/禁用整类方法，不再经过旧 manager 分派 |
| 只观察注入点或追加副作用代码 | `@AsmInject` | 不替换原指令，也不会自动接收栈顶表达式值 |
| 按直接字符串常量实参观察调用点 | `@AsmInject(INVOKE_STRING)` | 用 `At.target = "owner.name(desc)"` 指定普通方法调用，并用 `ldc=value` 或 `string=value` 过滤直接 `LDC String` 实参 |

### 1. 旧的调用替换逻辑

以前：

- 通过 `RedirectionReplace` / `RedirectionReplaceManager` 组织运行期替换
- 通过 `RedirectionReplaceApi.invoke` / `invokeIgnore` 作为内部入口

现在：

- 直接使用 `@Redirect` 或 `@WrapOperation`
- 如果只是想让方法返回默认值，使用 `@ReplaceAllMethods`

### 2. 旧的监听逻辑

以前：

- 通过 `RedirectionListener` 和 `AsmListener` 观察调用点

现在：

- 用 `@AsmInject`、`@WrapWithCondition`、`@ModifyExpressionValue` 等注解表达明确的插桩语义
- 若只是要观测某个调用点，优先在目标方法上直接写一个注解式 handler
- 若旧监听只关心“调用某方法且某个参数是固定字符串字面量”，迁移为普通
  `@AsmInject(target = InjectionPoint.INVOKE_STRING)`；这是注解式观察点，不回流旧 listener 或 manager 兼容层

字段读取这类“原逻辑仍然执行，但某些情况下不要使用原字段值”的监听/替换意图，优先迁移为
`@WrapWithCondition(at = At(value = InjectionPoint.FIELD, target = "..."))`。handler 首参接收已经读取出的字段值，
返回 `true` 时保留该值，返回 `false` 时框架压入字段类型默认值；该模式不把 `GETFIELD` receiver 传给 handler。
数组元素读取和字段来源数组长度读取可分别使用 `FIELD + args = ["array=get"]` 与 `FIELD + args = ["array=length"]`；
handler 只接收已经读取出的元素值或 `Int` 长度，不接收数组引用或索引。若旧逻辑不限定数组来自字段读取，
可使用 `At(value = InjectionPoint.ARRAY_LENGTH)` 直接匹配裸 `ARRAYLENGTH`。

方法调用这类“原调用必须执行，但某些上下文下不要采纳返回值”的监听/替换意图，优先迁移为
`@WrapWithCondition(at = At(value = InjectionPoint.INVOKE_ASSIGN, target = "..."))`。handler 首参接收调用完成后的返回值，
返回 `true` 时保留该返回值，返回 `false` 时把本次返回表达式替换为返回类型默认值；该模式不会跳过原调用。
若旧逻辑要在条件不满足时连原调用副作用一起跳过，应使用 `@WrapWithCondition(at = At(value = InjectionPoint.INVOKE, target = "..."))`。

对象构造这类“原构造过程仍然执行，但某些上下文下不要采纳构造结果”的监听/替换意图，优先迁移为
`@WrapWithCondition(at = At(value = InjectionPoint.NEW, target = "..."))`。handler 首参接收构造完成后的对象引用，
返回 `true` 时保留该对象，返回 `false` 时把本次构造表达式替换为 `null`；`At.target` 可写类型 internal
name 或 binary name，也可省略以匹配切片内兼容的构造点。若需要替换构造器参数、完全替换构造过程或多次调用原构造，
再使用 `@Redirect` 或 `@WrapOperation`。

类型转换这类“原 `CHECKCAST` 仍然执行，但某些上下文下不要采纳转换结果”的监听/替换意图，优先迁移为
`@WrapWithCondition(at = At(value = InjectionPoint.CAST, target = "..."))`。handler 首参接收转换完成后的引用，
返回 `true` 时保留该引用，返回 `false` 时把本次转换结果替换为 `null`；`At.target` 可写类型 internal
name 或 binary name，也可省略以匹配切片内兼容的类型转换，必要时继续使用 `Slice` 限制范围。

类型判断这类“原 `INSTANCEOF` 仍然执行，但某些上下文下不要采纳原判断结果”的监听/替换意图，优先迁移为
`@WrapWithCondition(at = At(value = InjectionPoint.INSTANCEOF, target = "..."))`。handler 首参接收原始 `Boolean`
判断结果，返回 `true` 时保留该结果，返回 `false` 时把本次判断替换为 `false`；`At.target` 可写类型 internal
name 或 binary name，也可省略以匹配切片内兼容的类型判断，必要时继续使用 `Slice` 限制范围。

其他“原操作仍然执行，但条件不满足时不要采纳表达式结果或控制流结果”的旧监听意图，也应迁移到对应
`@WrapWithCondition` 注入点：局部变量读取/写入使用 `LOAD` / `STORE`，常量读取使用 `CONSTANT`，条件分支使用
`JUMP`，switch selector 使用 `SWITCH`，即将抛出的异常使用 `THROW`。数组元素读取和字段来源数组长度读取跟随
`FIELD + array=get/array=length`，裸数组长度读取使用 `ARRAY_LENGTH`，数组元素写入跟随 `FIELD_ASSIGN + array=set`。

字符串实参调用监听应迁移为普通 `@AsmInject(INVOKE_STRING)`。它只匹配调用实参中的直接 `LDC String`，
且 `At.target` 必须写包含 owner 的 `owner.name(desc)`，
不会把该字符串传给 handler，也不会匹配局部变量、字符串拼接、方法返回值或 `invokedynamic` 生成的字符串。
需要替换字符串实参时，应使用 `@ModifyArg` 或 `@ModifyArgs`，而不是把 `INVOKE_STRING` 当作替换能力。

### 3. 参数与 receiver 迁移

旧 `Redirection` 替换器经常把“调用仍然执行，只是改掉输入数据”的场景写成整段调用替换。迁移时应优先把数据变更意图拆出来：

- 只改单个调用参数时使用 `@ModifyArg`。它会保留原调用，只在原调用前替换 `index` 指定或推断出的实参。
- 批量改写参数组时使用 `@ModifyArgs`。handler 接收 `Args` 容器，可一次读取、校验和写回整组调用参数。
- `INVOKE_STRING` 只用于观察直接字符串常量实参调用点；它不会把字符串传给 handler，也不会替换字符串参数。
  替换字符串实参时使用 `@ModifyArg` 或 `@ModifyArgs`。
- 只替换实例调用或实例字段访问 receiver 时使用 `@ModifyReceiver`。它保留原调用参数、字段读取值或字段待写入值，
  只把执行原操作时使用的 receiver 换成 handler 返回值。
- 需要保留可调用原操作句柄时使用 `@WrapOperation`。这适合“先改 receiver 或参数，再决定是否调用原方法/字段操作”的复杂场景。

字段读取或写入的 `@WrapWithCondition` handler 接收的是字段值或待写入值，不接收 `GETFIELD` / `PUTFIELD` receiver；
如果旧逻辑真正要替换 receiver，不应迁移成条件包裹。

### 4. 整方法迁移

旧 manager/listener 逻辑如果实际是在方法入口统一接管整段业务，应按是否需要原方法句柄拆分：

- 整方法仍需要调用原方法时使用 `@WrapMethod`。handler 通过 `Operation` 原方法句柄决定跳过、调用一次或多次执行原方法，
  也可以在调用前后改写参数、返回值或副作用。
- 只想让整类方法返回默认值时使用 `@ReplaceAllMethods`。该注解适合禁用整类方法或生成默认实现，不再经由旧 Redirection manager 分派，
  也不暴露旧运行期替换入口。

如果只是替换单个确定方法体且不需要原方法句柄，可以评估 `@Overwrite`；但迁移旧 Redirection manager 的默认路径应优先用
`@WrapMethod` 或 `@ReplaceAllMethods` 表达意图。

### 5. 旧的目标方法描述

以前：

- `MethodTypeInfoValue` 同时承载目标方法、替换器、监听器信息

现在：

- 目标方法信息直接写在注解参数里
- 不再使用外部描述列表

## 代码迁移示例

旧写法：

```kotlin
// 伪代码，仅用于说明旧 API
val target = MethodTypeInfoValue("com/example/Target", "run", "()V", RedirectionReplace::class.java)
```

新写法：

```kotlin
@AsmMixin("com/example/Target")
object TargetMixin {
    @Redirect(method = "run()V")
    @JvmStatic
    fun redirect() {
        // ...
    }
}
```

字段读取条件迁移示例：

```kotlin
@AsmMixin("com/example/Target")
object TargetMixin {
    @WrapWithCondition(
        method = "render()Ljava/lang/String;",
        at = At(value = InjectionPoint.FIELD, target = "displayName:Ljava/lang/String;"),
    )
    @JvmStatic
    fun keepDisplayNameWhenVisible(value: String): Boolean {
        return value.isNotBlank()
    }
}
```

上例在 `displayName` 字段读取后插入条件判断。返回 `true` 时继续使用原字段值；返回 `false` 时本次读取表达式变为
`String` 的默认值。

构造结果条件迁移示例：

```kotlin
@AsmMixin("com/example/Target")
object TargetMixin {
    @WrapWithCondition(
        method = "create(Ljava/lang/String;)Ljava/lang/StringBuilder;",
        at = At(value = InjectionPoint.NEW, target = "java/lang/StringBuilder"),
    )
    @JvmStatic
    fun keepBuilderWhenNamed(builder: StringBuilder, name: String): Boolean {
        return name.isNotBlank() && builder.capacity() >= 0
    }
}
```

上例在 `StringBuilder` 构造完成后插入条件判断。返回 `true` 时继续使用构造出的对象；返回 `false` 时本次构造表达式变为 `null`。

字符串调用监听迁移示例：

```kotlin
@AsmMixin("com/example/Target")
object TargetMixin {
    @AsmInject(
        method = "run()V",
        target = InjectionPoint.INVOKE_STRING,
        at = At(
            value = InjectionPoint.INVOKE_STRING,
            target = "com/example/Audit.emit(Ljava/lang/String;)V",
            args = ["ldc=marker"],
        ),
        require = 1,
        allow = 1,
    )
    @JvmStatic
    fun beforeMarkerAudit() {
        // 只观察该调用点；不接收或替换 marker 字符串
    }
}
```

上例只会在 `Audit.emit("marker")` 这类直接字符串常量实参调用点附近插入 handler。同值字符串如果先写入局部变量、
由字符串拼接得到，或来自方法返回值，都不会被该规则命中。

## 兼容性说明

- 旧的运行期 manager / listener ABI 已删除
- 旧的测试适配器 `AsmReplace` / `AsmListener` 已删除
- 现有 `@ReplaceAllMethods` 仅保留为内部默认返回值生成路径

## 迁移建议

如果你的代码还在依赖旧 `Redirection` 设计，请按下面顺序迁移：

1. 先按旧逻辑的真实意图区分调用替换、参数修改、receiver 修改、整方法接管和纯观察点
2. 把调用替换改成注解式 `@Redirect` / `@WrapOperation`
3. 把参数和 receiver 修改改成 `@ModifyArg` / `@ModifyArgs` / `@ModifyReceiver`
4. 把整方法接管改成 `@WrapMethod` / `@ReplaceAllMethods`
5. 把监听逻辑改成 `@AsmInject` / `@WrapWithCondition` / `@ModifyExpressionValue`
6. 最后移除旧的描述对象和运行期 manager 引用
