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

`@ReplaceAllMethods` 仍可用于“整方法替换”为默认返回值，但内部不再走旧式 Redirection manager 分派。

## 迁移对照

| 旧意图 | 新注解 | 选型说明 |
| --- | --- | --- |
| 完全替换一次调用、字段访问、局部变量表达式或分支结果 | `@Redirect` | handler 直接提供替代结果或替代副作用，不保留原操作句柄 |
| 在 handler 内按需调用、跳过或多次执行原操作 | `@WrapOperation` | 需要 `Operation` 句柄、要组合原参数或多次调用时优先使用 |
| 只按条件决定是否保留原调用、字段读写、数组元素读取/写入、数组长度、变量读写、类型转换、类型判断、常量、分支、switch 分派或抛异常 | `@WrapWithCondition` | handler 返回 `Boolean`，框架负责保留原值或写入默认值 |
| 保留原操作，只改写表达式结果或待写入值 | `@ModifyExpressionValue` | 适合字段读取值、字段待写入值、调用返回值、局部变量表达式等后置调整 |
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
数组元素读取和数组长度读取可分别使用 `FIELD + args = ["array=get"]` 与 `FIELD + args = ["array=length"]`；
handler 只接收已经读取出的元素值或 `Int` 长度，不接收数组引用或索引。

类型转换这类“原 `CHECKCAST` 仍然执行，但某些上下文下不要采纳转换结果”的监听/替换意图，优先迁移为
`@WrapWithCondition(at = At(value = InjectionPoint.CAST, target = "..."))`。handler 首参接收转换完成后的引用，
返回 `true` 时保留该引用，返回 `false` 时把本次转换结果替换为 `null`；`At.target` 可写类型 internal
name 或 binary name，也可省略以匹配切片内兼容的类型转换，必要时继续使用 `Slice` 限制范围。

类型判断这类“原 `INSTANCEOF` 仍然执行，但某些上下文下不要采纳原判断结果”的监听/替换意图，优先迁移为
`@WrapWithCondition(at = At(value = InjectionPoint.INSTANCEOF, target = "..."))`。handler 首参接收原始 `Boolean`
判断结果，返回 `true` 时保留该结果，返回 `false` 时把本次判断替换为 `false`；`At.target` 可写类型 internal
name 或 binary name，也可省略以匹配切片内兼容的类型判断，必要时继续使用 `Slice` 限制范围。

字符串实参调用监听应迁移为普通 `@AsmInject(INVOKE_STRING)`。它只匹配调用实参中的直接 `LDC String`，
且 `At.target` 必须写包含 owner 的 `owner.name(desc)`，
不会把该字符串传给 handler，也不会匹配局部变量、字符串拼接、方法返回值或 `invokedynamic` 生成的字符串。
需要替换字符串实参时，应使用 `@ModifyArg` 或 `@ModifyArgs`，而不是把 `INVOKE_STRING` 当作替换能力。

### 3. 旧的目标方法描述

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

1. 先把调用替换改成注解式 `@Redirect` / `@WrapOperation`
2. 再把监听逻辑改成 `@AsmInject` / `@WrapWithCondition` / `@ModifyExpressionValue`
3. 最后移除旧的描述对象和运行期 manager 引用
