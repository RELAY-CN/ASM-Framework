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
| 只按条件决定是否保留原调用、字段读写、数组元素读取/写入、数组长度、变量读写、常量、分支或抛异常 | `@WrapWithCondition` | handler 返回 `Boolean`，框架负责保留原值或写入默认值 |
| 保留原操作，只改写表达式结果或待写入值 | `@ModifyExpressionValue` | 适合字段读取值、字段待写入值、调用返回值、局部变量表达式等后置调整 |
| 只观察注入点或追加副作用代码 | `@AsmInject` | 不替换原指令，也不会自动接收栈顶表达式值 |

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

字段读取这类“原逻辑仍然执行，但某些情况下不要使用原字段值”的监听/替换意图，优先迁移为
`@WrapWithCondition(at = At(value = InjectionPoint.FIELD, target = "..."))`。handler 首参接收已经读取出的字段值，
返回 `true` 时保留该值，返回 `false` 时框架压入字段类型默认值；该模式不把 `GETFIELD` receiver 传给 handler。
数组元素读取和数组长度读取可分别使用 `FIELD + args = ["array=get"]` 与 `FIELD + args = ["array=length"]`；
handler 只接收已经读取出的元素值或 `Int` 长度，不接收数组引用或索引。

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

## 兼容性说明

- 旧的运行期 manager / listener ABI 已删除
- 旧的测试适配器 `AsmReplace` / `AsmListener` 已删除
- 现有 `@ReplaceAllMethods` 仅保留为内部默认返回值生成路径

## 迁移建议

如果你的代码还在依赖旧 `Redirection` 设计，请按下面顺序迁移：

1. 先把调用替换改成注解式 `@Redirect` / `@WrapOperation`
2. 再把监听逻辑改成 `@AsmInject` / `@WrapWithCondition` / `@ModifyExpressionValue`
3. 最后移除旧的描述对象和运行期 manager 引用
