# ASM-Framework 使用指南

本文档提供 ASM-Framework 的详细使用指南和最佳实践。

## 目录

- [快速开始](#快速开始)
- [常见场景](#常见场景)
- [最佳实践](#最佳实践)
- [调试技巧](#调试技巧)
- [故障排除](#故障排除)

## 快速开始

### 添加依赖

在 `build.gradle.kts` 中添加：

```kotlin
dependencies {
    implementation("kim.der:asm-framework:版本号")
}
```

### 使用 ASM 注解

1. **创建 ASM 类**

```kotlin
import kim.der.asm.api.annotation.*

@AsmMixin("com/example/TargetClass")
object MyAsm {
    
    @AsmInject(
        method = "targetMethod(Ljava/lang/String;)V",
        target = InjectionPoint.HEAD,
        cancellable = true
    )
    fun injectAtHead(callback: CallbackInfo) {
        if (shouldCancel()) {
            callback.cancel() // 取消原方法执行
        }
    }
    
    @ModifyArg(
        method = "process(Ljava/lang/String;)V",
        index = 0
    )
    fun modifyArg(original: String): String {
        return "modified: $original"
    }
    
    @Redirect(
        method = "doSomething()V",
        target = "java/lang/System.exit(I)V"
    )
    fun redirectExit(code: Int) {
        // 重定向方法调用
        println("Redirected: $code")
    }
}
```

2. **注册 ASM 类**

```kotlin
import kim.der.asm.AsmRegistry
import kim.der.asm.AsmScanner

// 方式1: 手动注册
AsmRegistry.register(MyAsm::class.java)

// 方式2: 扫描包
AsmScanner.scanPackage("com.example.asms")

// 方式3: 扫描 JAR 文件
AsmScanner.scanJar(File("asms.jar"), "com.example.asms")

// 方式4: 使用路径匹配器注册
AsmRegistry.registerWithPathMatcher(MyAsm::class.java) { className ->
    className.startsWith("com/example/")
}
```

3. **应用转换**

```kotlin
import kim.der.asm.transformer.AsmProcessor

val processor = AsmProcessor()
val transformedBytes = processor.transform(
    className = "com/example/TargetClass",
    classBytes = originalBytes,
    classLoader = null
)
```

### 支持的注解

- **@AsmMixin** - 标记 Mixin 类
- **@AddInterface** - 为目标类追加接口声明
- **@RemoveInterface** - 从目标类移除接口声明
- **@AsmInject** - 在指定位置注入代码
- **@Overwrite** - 完全覆盖方法
- **@ModifyArg** - 修改方法参数
- **@ModifyArgs** - 修改方法调用参数组
- **@ModifyReceiver** - 修改实例方法调用或实例字段访问 receiver
- **@WrapOperation** - 用可调用原操作的 handler 包裹方法调用、构造器调用、字段读取、字段写入、数组元素读写或数组长度读取
- **@WrapMethod** - 用可调用原方法的 handler 包裹整个目标方法体
- **@WrapWithCondition** - 按条件跳过 `void` 调用、字段写入或数组元素写入
- **@ModifyExpressionValue** - 修改表达式值
- **@ModifyVariable** - 修改方法参数或局部变量
- **@ModifyReturnValue** - 修改返回值
- **@ModifyConstant** - 修改常量值
- **@Redirect** - 重定向方法调用、构造器调用、字段访问、简单数组元素访问或数组长度读取
- **@Shadow** - 引用目标类的字段/方法
- **@Accessor** - 生成字段访问器
- **@Invoker** - 调用私有方法
- **@Copy** - 复制方法到目标类
- **@Unique** - 避免复制辅助方法与目标类已有方法冲突
- **@AddField** - 添加字段
- **@RemoveField** - 移除字段
- **@RemoveMethod** - 移除方法
- **@RemoveSynchronized** - 移除 synchronized
- **@ReplaceAllMethods** - 替换所有方法

详细说明请参考 [API.md](API.md)

## 常见场景

### 场景 1: 拦截方法调用

```kotlin
@AsmMixin("com/example/Service")
object LoggingMixin {
    @AsmInject(method = "process(Ljava/lang/String;)V", target = InjectionPoint.HEAD)
    fun logProcess(callback: CallbackInfo, param: String) {
        println("Processing: $param")
    }

    @AsmInject(
        method = "process(Ljava/lang/String;)V",
        target = InjectionPoint.INVOKE,
        at = At(
            value = InjectionPoint.INVOKE,
            target = "java/io/PrintStream.println(Ljava/lang/String;)V",
            shift = Shift.BEFORE,
        ),
    )
    fun beforePrintln(message: String, param: String) {
        println("About to print $message for $param")
    }
}
```

普通 `@AsmInject` handler 首参可以是 `CallbackInfo`。`HEAD`、`TAIL`、`RETURN` 与字段、`NEW`、`CAST`、`THROW`
等指令点注入可在 `CallbackInfo` 后继续接收目标方法参数前缀。`INVOKE` 的 `Shift.BEFORE` / `Shift.AFTER`
注入会先接收匹配调用的方法参数前缀，再追加目标方法参数前缀，例如上面的 `message` 来自 `println` 调用点，
`param` 来自 `process` 目标方法。

当目标方法内有多个相同调用或多个局部变量读写点时，可以用 `Slice` 把普通 `INVOKE`、`LOAD` 或 `STORE`
注入限制在一段调用边界内：

```kotlin
@AsmInject(
    method = "process(Ljava/lang/String;)V",
    target = InjectionPoint.INVOKE,
    at = At(
        value = InjectionPoint.INVOKE,
        target = "java/lang/String.trim()Ljava/lang/String;",
    ),
    slice = Slice(
        from = At(value = InjectionPoint.INVOKE, target = "com/example/Trace.begin()V"),
        to = At(value = InjectionPoint.INVOKE, target = "com/example/Trace.end()V"),
    ),
    require = 1,
    allow = 1,
)
fun beforeTrim(value: String) {
    println(value)
}
```

`from` 边界之后、`to` 边界之前的调用点或局部变量读写指令才会参与匹配，边界调用本身不会被注入；
`ordinal` 会在切片内重新计数。

### 场景 2: 修改参数与局部变量

```kotlin
@AsmMixin("com/example/Validator")
object ValidationMixin {
    @ModifyArg(method = "validate(Ljava/lang/String;Ljava/lang/String;)Z", index = 1)
    fun sanitizeInput(candidate: String, userId: String): String = "$userId:${candidate.trim().lowercase()}"

    @ModifyArg(
        method = "buildMessage(Ljava/lang/String;I)Ljava/lang/String;",
        index = 0,
        at = At(
            value = InjectionPoint.INVOKE,
            target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
        ),
        ordinal = 0,
    )
    fun rewriteConcatArgument(input: String, prefix: String, count: Int): String = "$prefix:${input.trim()}#$count"

    @ModifyArg(
        method = "buildMessage(Ljava/lang/String;I)Ljava/lang/String;",
        index = 0,
        at = At(
            value = InjectionPoint.INVOKE,
            target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
        ),
        slice = Slice(
            from = At(value = InjectionPoint.INVOKE, target = "com/example/Trace.begin()V"),
            to = At(value = InjectionPoint.INVOKE, target = "com/example/Trace.end()V"),
        ),
    )
    fun rewriteConcatArgumentInTrace(input: String): String = input.trim()

    @ModifyArgs(
        method = "join(Ljava/lang/String;I)Ljava/lang/String;",
        at = At(
            value = InjectionPoint.INVOKE,
            target = "com/example/Text.combine(Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/String;",
        ),
        ordinal = 0,
    )
    fun rewriteJoinArguments(args: Args, prefix: String, count: Int) {
        args.set(0, "$prefix:${args.get<String>(0).trim()}")
        args.set(1, "normalized")
        args.set(2, count)
    }

    @ModifyArgs(
        method = "join(Ljava/lang/String;I)Ljava/lang/String;",
        at = At(
            value = InjectionPoint.INVOKE,
            target = "com/example/Text.combine(Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/String;",
        ),
        slice = Slice(
            from = At(value = InjectionPoint.INVOKE, target = "com/example/Trace.begin()V"),
            to = At(value = InjectionPoint.INVOKE, target = "com/example/Trace.end()V"),
        ),
    )
    fun rewriteJoinArgumentsInTrace(args: Args) {
        args.set(1, "traced")
    }

    @ModifyReceiver(
        method = "format(Ljava/lang/String;I)Ljava/lang/String;",
        at = At(
            value = InjectionPoint.INVOKE,
            target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
        ),
    )
    fun rewriteConcatReceiver(receiver: String, prefix: String, count: Int): String = "$prefix$count"

    @ModifyReceiver(
        method = "decorate(Ljava/lang/String;I)Ljava/lang/String;",
        at = At(
            value = InjectionPoint.INVOKE,
            target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
        ),
        slice = Slice(
            from = At(value = InjectionPoint.INVOKE, target = "com/example/Trace.begin()V"),
            to = At(value = InjectionPoint.INVOKE, target = "com/example/Trace.end()V"),
        ),
    )
    fun rewriteConcatReceiverInTrace(receiver: String): String = "traced-$receiver"

    @ModifyReceiver(
        method = "displayName(Ljava/lang/String;)Ljava/lang/String;",
        at = At(
            value = InjectionPoint.FIELD,
            target = "com/example/Player.name:Ljava/lang/String;",
        ),
    )
    fun rewriteNameReadReceiver(player: Any, fallback: String): Any =
        selectFallbackPlayer(player, fallback)

    @ModifyReceiver(
        method = "rename(Ljava/lang/String;Ljava/lang/String;)V",
        at = At(
            value = InjectionPoint.FIELD_ASSIGN,
            target = "com/example/Player.name:Ljava/lang/String;",
        ),
    )
    fun rewriteNameWriteReceiver(player: Any, name: String, source: String): Any =
        selectWritablePlayer(player, name, source)

    @WrapOperation(
        method = "decorate(Ljava/lang/String;I)Ljava/lang/String;",
        at = At(
            value = InjectionPoint.INVOKE,
            target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
        ),
    )
    fun wrapConcat(
        receiver: String,
        value: String,
        operation: Operation<String>,
        prefix: String,
        count: Int,
    ): String = operation.call("$prefix$count", value)

    @WrapOperation(
        method = "decorate(Ljava/lang/String;I)Ljava/lang/String;",
        at = At(
            value = InjectionPoint.INVOKE,
            target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;",
        ),
        slice = Slice(
            from = At(value = InjectionPoint.INVOKE, target = "com/example/Trace.begin()V"),
            to = At(value = InjectionPoint.INVOKE, target = "com/example/Trace.end()V"),
        ),
    )
    fun wrapConcatInTrace(
        receiver: String,
        value: String,
        operation: Operation<String>,
    ): String = operation.call(receiver, "traced-$value")

    @WrapOperation(
        method = "displayName(Ljava/lang/String;)Ljava/lang/String;",
        at = At(
            value = InjectionPoint.FIELD,
            target = "com/example/Player.name:Ljava/lang/String;",
        ),
    )
    fun wrapNameRead(
        player: Any,
        operation: Operation<String>,
        fallback: String,
    ): String = operation.call(player).ifBlank { fallback }

    @WrapOperation(
        method = "rename(Ljava/lang/String;Ljava/lang/String;)V",
        at = At(
            value = InjectionPoint.FIELD_ASSIGN,
            target = "com/example/Player.name:Ljava/lang/String;",
        ),
    )
    fun wrapNameWrite(
        player: Any,
        value: String,
        operation: Operation<Unit>,
        name: String,
        source: String,
    ) {
        operation.call(player, "$source:$value")
        operation.call(player, name)
    }

    @WrapOperation(
        method = "routeName(I)Ljava/lang/String;",
        at = At(
            value = InjectionPoint.FIELD,
            target = "com/example/Player.routes:[Ljava/lang/String;",
            args = ["array=get"],
        ),
    )
    fun wrapRouteRead(
        routes: Array<String>,
        index: Int,
        operation: Operation<String>,
    ): String = "route:${operation.call(routes, index)}"

    @WrapOperation(
        method = "routeCount()I",
        at = At(
            value = InjectionPoint.FIELD,
            target = "com/example/Player.routes:[Ljava/lang/String;",
            args = ["array=length"],
        ),
    )
    fun wrapRouteCount(
        routes: Array<String>,
        operation: Operation<Int>,
    ): Int = operation.call(routes).coerceAtLeast(1)

    @WrapOperation(
        method = "setRoute(ILjava/lang/String;Z)V",
        at = At(
            value = InjectionPoint.FIELD_ASSIGN,
            target = "com/example/Player.routes:[Ljava/lang/String;",
            args = ["array=set"],
        ),
    )
    fun wrapRouteWrite(
        routes: Array<String>,
        index: Int,
        value: String,
        operation: Operation<Unit>,
        routeIndex: Int,
        route: String,
        writable: Boolean,
    ) {
        if (writable && index == routeIndex) {
            operation.call(routes, index, "checked:$route")
        }
    }

    @WrapOperation(
        method = "createRoute(Ljava/lang/String;)Ljava/lang/StringBuilder;",
        at = At(
            value = InjectionPoint.INVOKE,
            target = "java/lang/StringBuilder.<init>(Ljava/lang/String;)V",
        ),
    )
    fun wrapRouteBuilder(
        value: String,
        operation: Operation<StringBuilder>,
        prefix: String,
    ): StringBuilder = operation.call("$prefix:$value")

    @WrapMethod(method = "decorateWhole(Ljava/lang/String;I)Ljava/lang/String;")
    fun wrapDecorateWhole(
        prefix: String,
        count: Int,
        operation: Operation<String>,
    ): String = operation.call(prefix.trim(), count + 1).uppercase()

    @WrapWithCondition(
        method = "notify(Ljava/lang/String;I)V",
        at = At(
            value = InjectionPoint.INVOKE,
            target = "com/example/Audit.emit(Ljava/lang/String;)V",
        ),
    )
    fun shouldEmitAudit(message: String, prefix: String, count: Int): Boolean =
        count > 0 && message.startsWith(prefix)

    @WrapWithCondition(
        method = "notify(Ljava/lang/String;I)V",
        at = At(
            value = InjectionPoint.INVOKE,
            target = "com/example/Audit.emit(Ljava/lang/String;)V",
        ),
        slice = Slice(
            from = At(value = InjectionPoint.INVOKE, target = "com/example/Trace.begin()V"),
            to = At(value = InjectionPoint.INVOKE, target = "com/example/Trace.end()V"),
        ),
    )
    fun shouldEmitAuditInsideTrace(message: String, prefix: String, count: Int): Boolean =
        count > 0 && message.startsWith(prefix)

    @WrapWithCondition(
        method = "setEndpoint(Ljava/lang/String;Ljava/lang/String;)V",
        at = At(
            value = InjectionPoint.FIELD_ASSIGN,
            target = "com/example/Client.endpoint:Ljava/lang/String;",
        ),
    )
    fun shouldWriteEndpoint(client: Any, value: String, endpoint: String, profile: String): Boolean {
        client.hashCode()
        return value == endpoint && profile != "readonly"
    }

    @WrapWithCondition(
        method = "setRoute(ILjava/lang/String;Z)V",
        at = At(
            value = InjectionPoint.FIELD_ASSIGN,
            target = "com/example/Player.routes:[Ljava/lang/String;",
            args = ["array=set"],
        ),
    )
    fun shouldWriteRoute(
        routes: Array<String>,
        index: Int,
        value: String,
        routeIndex: Int,
        route: String,
        writable: Boolean,
    ): Boolean = index == routeIndex && value == route && writable

    @ModifyExpressionValue(
        method = "format(Ljava/lang/String;I)Ljava/lang/String;",
        at = At(
            value = InjectionPoint.INVOKE,
            target = "java/lang/String.trim()Ljava/lang/String;",
        ),
    )
    fun rewriteTrimResult(value: String, prefix: String, count: Int): String = "$prefix:${value.lowercase()}#$count"

    @ModifyExpressionValue(
        method = "format(Ljava/lang/String;I)Ljava/lang/String;",
        at = At(
            value = InjectionPoint.INVOKE,
            target = "java/lang/String.trim()Ljava/lang/String;",
        ),
        slice = Slice(
            from = At(value = InjectionPoint.INVOKE, target = "com/example/Trace.begin()V"),
            to = At(value = InjectionPoint.INVOKE, target = "com/example/Trace.end()V"),
        ),
    )
    fun rewriteTrimResultInTrace(value: String, prefix: String, count: Int): String =
        "$prefix:${value.lowercase()}#$count"

    @ModifyExpressionValue(
        method = "displayName(Ljava/lang/String;)Ljava/lang/String;",
        at = At(
            value = InjectionPoint.FIELD,
            target = "com/example/Player.name:Ljava/lang/String;",
        ),
    )
    fun rewriteFieldValue(value: String, fallback: String): String = value.ifBlank { fallback }

    @ModifyExpressionValue(
        method = "routeName(I)Ljava/lang/String;",
        at = At(
            value = InjectionPoint.FIELD,
            target = "com/example/Player.routes:[Ljava/lang/String;",
            args = ["array=get"],
        ),
    )
    fun rewriteRouteValue(value: String, index: Int): String = "$index:$value"

    @ModifyExpressionValue(
        method = "routeCount(Ljava/lang/String;)I",
        at = At(
            value = InjectionPoint.FIELD,
            target = "com/example/Player.routes:[Ljava/lang/String;",
            args = ["array=length"],
        ),
    )
    fun rewriteRouteCount(value: Int, prefix: String): Int = value + prefix.length

    @ModifyExpressionValue(
        method = "newBuffer(Ljava/lang/String;)Ljava/lang/StringBuilder;",
        at = At(
            value = InjectionPoint.NEW,
            target = "java/lang/StringBuilder",
        ),
    )
    fun rewriteConstructedBuffer(value: StringBuilder, prefix: String): StringBuilder =
        StringBuilder("$prefix:${value.length}")

    @ModifyExpressionValue(
        method = "castName(Ljava/lang/Object;)Ljava/lang/String;",
        at = At(
            value = InjectionPoint.CAST,
            target = "java/lang/String",
        ),
    )
    fun rewriteCastValue(value: String, raw: Any): String = "$raw:${value.trim()}"

    @ModifyExpressionValue(
        method = "isNamed(Ljava/lang/Object;Z)Z",
        at = At(
            value = InjectionPoint.INSTANCEOF,
            target = "java/lang/String",
        ),
    )
    fun rewriteInstanceofResult(value: Boolean, raw: Any, force: Boolean): Boolean =
        value || force

    @ModifyVariable(
        method = "validate(Ljava/lang/String;)Z",
        at = At(value = InjectionPoint.HEAD),
        index = 1,
    )
    fun rewriteInputAtHead(input: String): String = input.trim()

    @ModifyVariable(
        method = "merge(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
        at = At(value = InjectionPoint.HEAD),
        ordinal = 1,
    )
    fun rewriteSecondString(input: String): String = input.trim()

    @ModifyVariable(
        method = "normalize(Ljava/lang/String;)Ljava/lang/String;",
        at = At(value = InjectionPoint.STORE),
        ordinal = 0,
    )
    fun rewriteStoredLocal(value: String, rawInput: String): String = "$rawInput:${value.trim()}"

    @ModifyVariable(
        method = "normalizeInTrace(Ljava/lang/String;)Ljava/lang/String;",
        at = At(value = InjectionPoint.STORE),
        ordinal = 0,
        slice = Slice(
            from = At(value = InjectionPoint.INVOKE, target = "com/example/Trace.begin()V"),
            to = At(value = InjectionPoint.INVOKE, target = "com/example/Trace.end()V"),
        ),
    )
    fun rewriteStoredLocalInTrace(value: String): String = value.trim()

    @ModifyVariable(
        method = "readNormalized()Ljava/lang/String;",
        at = At(value = InjectionPoint.LOAD),
        index = 1,
    )
    fun rewriteBeforeLoad(value: String): String = value.trim()

    @ModifyVariable(
        method = "readNormalized()Ljava/lang/String;",
        at = At(value = InjectionPoint.LOAD),
        index = 1,
        slice = Slice(
            from = At(value = InjectionPoint.INVOKE, target = "com/example/Trace.begin()V"),
            to = At(value = InjectionPoint.INVOKE, target = "com/example/Trace.end()V"),
        ),
    )
    fun rewriteBeforeLoadInTrace(value: String): String = value.trim()
}
```

`@ModifyArg` 默认使用目标方法入口参数索引；当 `at.value = InjectionPoint.INVOKE` 时，会用 `at.target` 匹配目标调用，并把 `index` 解释为目标调用的参数索引。handler 第一个参数接收被修改的原参数并返回同类型的新值，后续参数可继续接收目标方法参数前缀；调用点模式可用 `ordinal` 只选择第 N 个匹配调用点，也可用 `Slice` 限制匹配范围。关键参数补丁可设置 `require` / `allow` 约束实际修改数量，目标字节码漂移时会在转换阶段失败；`expect` 适合调试期记录期望命中数，不一致时只输出警告。`@ModifyArgs` 用于同一个调用点需要同时改写多个参数的场景，handler 第一个参数为 `Args`，可通过 `args.get<T>(index)` 读取调用参数，通过 `args.set(index, value)` 写回兼容类型的新值；后续参数同样可接收目标方法参数前缀，也支持用 `Slice` 限制匹配范围。关键参数组补丁同样可设置 `require` / `allow` / `expect`，按实际改写的调用点数量校验命中契约。`from` 边界之后、`to` 边界之前的调用才会参与匹配，边界调用本身不会被修改，`ordinal` 会在切片内重新计数。`@ModifyReceiver` 用于只替换实例方法调用、实例字段读取或实例字段写入的 receiver，handler 第一个参数接收原 receiver 并返回兼容的新 receiver；`INVOKE` 会保留原调用参数，也可用 `Slice` 限制 receiver 改写范围，`FIELD` 会继续读取新 receiver 上的字段，`FIELD_ASSIGN` 会把原待写入值写到新 receiver，后续参数可接收目标方法参数前缀。关键 receiver 补丁同样可设置 `require` / `allow` / `expect`，按实际改写的 receiver 数量校验命中契约；设置 `ordinal` 时最多命中对应序号的 1 个 receiver。静态方法、构造器调用和静态字段没有可改写 receiver，会在转换阶段失败。

`@WrapOperation` 用于把匹配方法调用、构造器调用、字段读取、字段写入、数组元素读写或数组长度读取替换为 handler，并通过 `Operation`
保留执行原操作的能力。实例调用 handler 先接收 receiver 和调用参数，静态调用 handler 只接收调用参数；
构造器模式通过 `INVOKE + <init>` 目标指定，handler 先接收构造器参数，不接收未初始化 receiver；
`GETFIELD` handler 先接收字段 owner，`GETSTATIC` handler 不接收字段 owner；`PUTFIELD` handler
先接收字段 owner 和待写入值，`PUTSTATIC` handler 先接收待写入值；数组读取模式通过
`FIELD + args = ["array=get"]` 指定，handler 接收数组引用、`Int` 索引与 `Operation<R>`；数组写入模式
通过 `FIELD_ASSIGN + args = ["array=set"]` 指定，handler 接收数组引用、`Int` 索引、待写入元素值与
`Operation<Unit>`；数组长度模式通过 `FIELD + args = ["array=length"]` 指定，handler 接收数组引用与
`Operation<Int>`。handler 可用 `operation.call(...)` 调用、跳过或多次执行原操作，后续可接收目标方法
参数前缀；构造器调用的 `operation.call(...)` 只传构造器参数，并返回原构造器 owner 类型兼容对象。`INVOKE`
操作包裹可用 `Slice` 限制匹配范围；`from` 边界之后、`to` 边界之前的调用才会参与匹配，边界调用本身不会被包裹，
`ordinal` 会在切片内重新计数。关键操作包裹可设置 `require` / `allow` / `expect`，按实际替换为 handler 调用的操作点数量校验命中契约；设置 `ordinal` 时最多命中对应序号的 1 个操作点。

`@WrapMethod` 用于包裹整个目标方法，而不是某一个调用点或字段访问点。handler 先按目标方法声明顺序接收原方法参数，
下一参数必须是 `Operation<R>`，返回类型必须兼容目标方法返回类型；目标方法为 `void` 时 handler 返回 `Unit` / `void`。
静态目标方法的 `operation.call(...)` 只传目标方法参数；实例目标方法的 `Operation` 已绑定当前 `this`，
handler 不接收 receiver，`operation.call(...)` 同样只传目标方法参数。该注解会把原方法体迁移到私有 synthetic 方法，
再用原方法名与原描述符生成 wrapper，因此不支持构造器、类初始化器、abstract 方法或 native 方法。
关键整方法包裹可设置 `require` / `allow` / `expect`，实际命中数按目标方法计数。

`@WrapWithCondition` 用于保留原 `void` 调用、字段写入或数组元素写入但按条件跳过副作用的场景。
handler 返回 `true` 时继续执行原指令，返回 `false` 时跳过；调用模式下 handler 先接收原调用
receiver（仅实例调用）和调用参数，字段写入模式下 handler 先接收字段 owner（仅实例字段）和待写入值。
数组写入模式通过 `args = ["array=set"]` 指定，并让 handler 接收数组引用、`Int` 索引与待写入元素值，
后续都可接收目标方法参数前缀。`INVOKE` 条件包裹可用 `Slice` 限制匹配范围；`from` 边界之后、
`to` 边界之前的调用才会参与匹配，边界调用本身不会被包裹，`ordinal` 会在切片内重新计数。关键条件包裹可设置 `require` / `allow` / `expect`，按实际插入条件判断的操作点数量校验命中契约；设置 `ordinal` 时最多命中对应序号的 1 个操作点。
`@ModifyExpressionValue` 用于保留原调用、字段读取、数组读取、数组长度、对象构造、类型转换或类型判断但
改写表达式结果的场景，handler 第一个参数接收匹配调用返回值、字段读取值、数组元素读取值、数组长度值、已初始化对象、转换后的对象或 `INSTANCEOF` 判断结果并
返回同类型新值，后续参数可接收目标方法参数前缀；它不会接收原调用参数、`GETFIELD` receiver、数组引用或
数组索引，`NEW` 模式会在对应 `<init>` 完成后改写对象表达式，`CAST` 模式会在 `CHECKCAST` 后改写类型转换结果，数组读取模式通过 `args = ["array=get"]`
指定，数组长度模式通过 `args = ["array=length"]` 指定并接收 `Int` 长度，`INSTANCEOF` 模式接收 `Boolean` 判断结果。`INVOKE` 表达式值修改可用
`Slice` 限制匹配范围；`from` 边界之后、`to` 边界之前的调用才会参与匹配，边界调用本身不会被改写，
`ordinal` 会在切片内重新计数。
关键表达式值补丁可设置 `require` / `allow` / `expect`，命中数按实际改写的表达式值数量计数。

`@ModifyVariable` 支持 `HEAD` 入口参数改写、`LOAD` 局部变量读取前改写和 `STORE` 局部变量写入后改写。`HEAD` 适合在方法体执行前重写参数值；`LOAD` 会在匹配的 `xLOAD` 指令前读取当前局部变量，调用 handler，并写回同一槽位；`STORE` 会在匹配的 `xSTORE` 指令后读取刚写入的局部变量，调用 handler，并写回同一槽位。`LOAD` / `STORE` 模式可用 `Slice` 限制读取点或写入点匹配范围；`from` 边界之后、`to` 边界之前的读取或写入才会参与匹配，边界调用本身不会被修改，`ordinal` 会在切片内重新计数。`@ModifyVariable` handler 第一个参数接收原变量值并返回同类型的新值，后续参数可继续接收目标方法参数前缀。`@ModifyVariable.index` 使用 JVM 局部变量槽位索引，实例方法槽位 0 是 `this`，第一个参数从槽位 1 开始；静态方法第一个参数从槽位 0 开始。未指定 `index` 时，会按 handler 第一个参数类型筛选入口参数、读取点或写入点，并用 `ordinal` 选择第 N 个同类型匹配项。关键变量补丁可设置 `require` / `allow` / `expect`，`HEAD` 按入口改写计 1 次，`LOAD` / `STORE` 按实际改写的读写点数量计数。`HEAD` 当前不使用 `slice`。

普通 `@AsmInject` 也可以使用 `InjectionPoint.LOAD` 或 `InjectionPoint.STORE` 在局部变量读取前或写入后插入
无参 handler，适合记录日志、打点或触发旁路逻辑。普通注入不会把局部变量值传给 handler，也不会写回变量；
可以用 `Slice` 把候选读写指令限制在一段 `INVOKE` 边界内，也可以用
`at.args = ["index=N"]` 或 `["var=N"]` 只匹配指定 JVM 局部变量槽位。需要基于变量值计算新值时，
应使用 `@ModifyVariable`。

### 场景 3: 修改返回值

```kotlin
@AsmMixin("com/example/Cache")
object CacheMixin {
    @ModifyReturnValue(method = "get(Ljava/lang/String;)Ljava/lang/Object;", ordinal = 0)
    fun wrapResult(original: Any?): Any? = original ?: createDefault()
}
```

`@ModifyReturnValue` 默认会修改目标方法的全部非 void 返回点；`ordinal` 可限定只修改第 N 个返回点。
关键返回值补丁可设置 `require` / `allow` / `expect`，命中数按实际改写的非 void 返回点数量计数。

### 场景 4: 修改常量

```kotlin
@AsmMixin("com/example/Rules")
object RulesMixin {
    @ModifyConstant(method = "maxPlayers()I", constant = "20", ordinal = 0)
    @JvmStatic
    fun expandLimit(original: Int): Int = original * 2

    @ModifyConstant(method = "message(Ljava/lang/String;)Ljava/lang/String;", constant = "prefix-")
    @JvmStatic
    fun rewritePrefix(original: String, name: String): String = "$original$name"

    @ModifyConstant(
        method = "messageInTrace(Ljava/lang/String;)Ljava/lang/String;",
        constant = "prefix-",
        require = 1,
        allow = 1,
        slice = Slice(
            from = At(value = InjectionPoint.INVOKE, target = "com/example/Trace.begin()V"),
            to = At(value = InjectionPoint.INVOKE, target = "com/example/Trace.end()V"),
        ),
    )
    @JvmStatic
    fun rewritePrefixInTrace(original: String): String = "trace-$original"
}
```

`@ModifyConstant` 会把匹配到的常量值作为 handler 的第一个参数，并用 handler 返回值替换原常量。handler 后续参数可按顺序接收目标方法参数前缀，例如上面的 `name`。`ordinal` 可限定只修改第 N 个匹配常量，默认 `-1` 会修改全部匹配项。它支持 `LDC` 字符串、数字、类常量，以及 JVM 短常量指令，包括 `ACONST_NULL`、`ICONST_*`、`LCONST_*`、`FCONST_*`、`DCONST_*`、`BIPUSH` 与 `SIPUSH`。当目标方法中有多个相同常量时，可用 `Slice` 限制常量匹配范围；`from` 边界之后、`to` 边界之前的常量才会参与匹配，边界调用本身不会被修改，`ordinal` 会在切片内重新计数。关键常量补丁可同时设置 `require` / `allow` 约束实际替换数量，目标字节码漂移时会在转换阶段失败；`expect` 适合调试期记录期望命中数，不一致时只输出警告。

### 场景 5: 完全替换方法

```kotlin
@AsmMixin("com/example/LegacyClass")
object ModernizeMixin {
    @Overwrite(method = "oldMethod()V")
    @JvmStatic
    fun oldMethod() = println("Modern implementation")

    @Overwrite(method = "displayName()Ljava/lang/String;")
    @JvmStatic
    fun displayName(): String = normalizeName()

    @Copy("normalizeName()Ljava/lang/String;")
    @Unique
    @JvmStatic
    fun normalizeName(): String = "modern"
}
```

`@Overwrite` 会把 handler 方法体复制到目标方法中，目标方法不存在或签名无法适配时会在转换阶段失败。
如果需要先为整类建立默认实现，再保留少量关键方法，可以把 `@ReplaceAllMethods` 放在同一个 Mixin 类上，
再用 `@Overwrite` 覆盖指定方法：

```kotlin
@ReplaceAllMethods
@AsmMixin("com/example/LegacyClass")
object SafeDefaultMixin {
    @Overwrite(method = "healthCheck()Ljava/lang/String;")
    @JvmStatic
    fun healthCheck(): String = "ok"
}
```

`@Copy` 可把 Mixin 中的辅助方法复制到目标类中，供 `@Overwrite` 或其他被复制方法调用。默认情况下，
目标类已存在同名同描述符方法时 `@Copy` 会跳过，避免覆盖目标类逻辑；如果该辅助方法只服务于当前 Mixin，
可以同时标记 `@Unique`。当发生同签名冲突时，框架会把该辅助方法复制为私有 synthetic 唯一方法，
并改写同一个 Mixin 内 `@Overwrite` / `@Copy` / inline `@AsmInject` 方法体中对该辅助方法的调用。当前 `@Unique` 支持的是
`@Copy` 方法冲突重命名，不会处理字段唯一化。

### 场景 6: 访问私有字段

```kotlin
@AsmMixin("com/example/PrivateClass")
class AccessMixin {
    @Shadow()
    private val privateField: String? = null

    @Shadow("actualSecret")
    private val secretAlias: String? = null
    
    @Accessor("privateField")
    fun getPrivateField(): String = throw UnsupportedOperationException()
    
    @AsmInject(method = "method()V", target = InjectionPoint.HEAD)
    fun inject(callback: CallbackInfo) {
        println("Field value: $privateField")
    }
}
```

`@Shadow()` 默认按声明名匹配目标字段或方法；如果 ASM 侧需要使用别名，可直接写目标名，例如
`@Shadow("actualSecret") private val secretAlias: String? = null`。兼容 Mixin 风格的 `shadow_` 前缀：
`@Shadow("shadow_privateField")` 会匹配目标成员 `privateField`。

`@Accessor` 可按方法形态生成 getter 或 setter。getter 必须无参数并返回字段类型，
setter 必须接收一个字段类型参数并返回 `void`。
访问静态字段时，访问器方法也必须是静态方法；Kotlin `object` 中通常需要添加 `@JvmStatic`。

### 场景 7: 调用私有方法或构造器

```kotlin
@AsmMixin("com/example/PrivateClass")
object InvokerMixin {
    @Invoker("privateMethod")
    fun invokePrivateMethod(param: String): String = throw UnsupportedOperationException()

    @Invoker("<init>")
    @JvmStatic
    fun create(param: String): Any = throw UnsupportedOperationException()
    
    @AsmInject(method = "publicMethod()V", target = InjectionPoint.HEAD)
    fun inject(callback: CallbackInfo) {
        invokePrivateMethod("test")
    }
}
```

普通 `@Invoker` 的参数和返回值必须与目标方法一致；静态性也必须一致。
`@Invoker("<init>")` 会生成静态工厂方法，参数用于匹配目标构造器，返回类型使用目标类或
`Any` / `java.lang.Object`。

### 场景 8: 重定向方法调用与字段访问

```kotlin
@AsmMixin("com/example/NetworkClient")
object RedirectMixin {
    @Redirect(
        method = "connect(Ljava/lang/String;)V",
        target = "java/net/Socket.connect(Ljava/net/SocketAddress;I)V",
        ordinal = 0,
        require = 1,
        allow = 1,
    )
    fun redirectConnect(socket: Socket, address: SocketAddress, timeout: Int, profile: String) {
        println("Custom connection logic")
    }

    @Redirect(
        method = "normalize(Ljava/lang/String;)Ljava/lang/String;",
        at = At(
            value = InjectionPoint.INVOKE,
            target = "java/lang/String.trim()Ljava/lang/String;",
        ),
        slice = Slice(
            from = At(value = InjectionPoint.INVOKE, target = "com/example/Trace.begin()V"),
            to = At(value = InjectionPoint.INVOKE, target = "com/example/Trace.end()V"),
        ),
    )
    fun redirectTrimInTrace(value: String): String = value.lowercase()

    @Redirect(
        method = "openBuilder(Ljava/lang/String;)Ljava/lang/StringBuilder;",
        at = At(
            value = InjectionPoint.INVOKE,
            target = "java/lang/StringBuilder.<init>(Ljava/lang/String;)V",
        ),
    )
    fun redirectBuilder(value: String, profile: String): StringBuilder =
        StringBuilder("$profile:$value")

    @Redirect(
        method = "getEndpoint(Ljava/lang/String;)Ljava/lang/String;",
        at = At(value = InjectionPoint.FIELD, target = "endpoint"),
    )
    fun redirectEndpoint(client: Any, profile: String): String = "local-$profile"

    @Redirect(
        method = "setEndpoint(Ljava/lang/String;Ljava/lang/String;)V",
        at = At(value = InjectionPoint.FIELD_ASSIGN, target = "endpoint"),
    )
    fun redirectEndpointWrite(client: Any, value: String, endpoint: String, profile: String) {
        println("blocked endpoint update: $value")
    }

    @Redirect(
        method = "getRoute(I)Ljava/lang/String;",
        at = At(
            value = InjectionPoint.FIELD,
            target = "com/example/NetworkClient.routes:[Ljava/lang/String;",
            args = ["array=get"],
        ),
    )
    fun redirectRouteRead(routes: Array<String>, index: Int, profile: String): String = "$profile-${routes[index]}"

    @Redirect(
        method = "routeCount(Ljava/lang/String;)I",
        at = At(
            value = InjectionPoint.FIELD,
            target = "com/example/NetworkClient.routes:[Ljava/lang/String;",
            args = ["array=length"],
        ),
    )
    fun redirectRouteCount(routes: Array<String>, profile: String): Int =
        routes.size + profile.length

    @Redirect(
        method = "setRoute(ILjava/lang/String;)V",
        at = At(
            value = InjectionPoint.FIELD,
            target = "com/example/NetworkClient.routes:[Ljava/lang/String;",
            args = ["array=set"],
        ),
    )
    fun redirectRouteWrite(routes: Array<String>, index: Int, value: String) {
        routes[index] = value.trim()
    }
}
```

方法调用、构造器调用、字段读取、字段写入、简单数组元素访问与数组长度重定向都可用 `ordinal` 只替换第 N 个匹配点，默认 `-1` 会替换全部匹配点。handler 都可以是静态方法、`@JvmStatic` 方法，或 Kotlin `object` 中的实例方法。handler 先接收原调用、构造器、字段访问、数组元素访问或数组长度需要的栈参数，后续可按顺序接收目标方法参数前缀。构造器重定向使用 `INVOKE + <init>` 目标，handler 接收构造器参数，不接收未初始化 receiver，并返回构造器 owner 类型兼容对象。字段写入的原写入值已经作为字段访问参数传入；如果还追加目标方法参数前缀，目标方法的第一个参数会再次出现，例如上面的 `endpoint`。数组元素读取使用 `args = ["array=get"]`，handler 先接收数组引用与 `Int` 索引并返回元素值；数组元素写入使用 `args = ["array=set"]`，handler 先接收数组引用、`Int` 索引与原元素值，并返回 `Unit`；数组长度读取使用 `args = ["array=length"]`，handler 接收数组引用并返回 `Int`。
普通方法调用重定向可用 `Slice` 限制匹配范围；`from` 边界之后、`to` 边界之前的调用才会参与匹配，边界调用本身不会被重定向，`ordinal` 会在切片内重新计数。构造器、字段、数组元素与数组长度重定向当前不使用 `slice`。关键重定向可同时设置 `require` / `allow` 约束实际替换数量，目标字节码漂移时会在转换阶段失败；`expect` 适合调试期记录期望命中数，不一致时只输出警告。

### 场景 9: 条件取消执行

```kotlin
@AsmMixin("com/example/Service")
object ConditionalMixin {
    @AsmInject(method = "process(Ljava/lang/String;)V", target = InjectionPoint.HEAD, cancellable = true)
    fun conditionalProcess(callback: CallbackInfo, input: String) {
        if (input.isEmpty() || input == "skip") {
            callback.cancel()
        }
    }
}
```

`@WrapWithCondition` 可用于按条件跳过 `void` 调用、字段写入或数组元素写入。字段写入模式需要使用
`At(value = InjectionPoint.FIELD_ASSIGN, target = "...")`，`PUTFIELD` handler 参数为字段 owner 与待写入值，
`PUTSTATIC` handler 参数只包含待写入值，后续仍可追加目标方法参数前缀。数组元素写入使用
`args = ["array=set"]`，handler 参数为数组引用、`Int` 索引与待写入元素值，返回 `false` 时跳过原 `xASTORE`。

### 场景 10: 修改返回值

```kotlin
@AsmMixin("com/example/Calculator")
object CalculatorMixin {
    @AsmInject(method = "calculate(I)I", target = InjectionPoint.RETURN)
    fun modifyResult(callback: CallbackInfo) {
        val original = callback.getReturnValue<Int>() ?: 0
        callback.setReturnValue(original * 2)
    }
}
```

### 场景 11: 多个注入点

```kotlin
@AsmMixin("com/example/Service")
object MultiInjectMixin {
    @AsmInject(method = "process()V", target = InjectionPoint.HEAD)
    fun injectHead(callback: CallbackInfo) = println("Before process")
    
    @AsmInject(method = "process()V", target = InjectionPoint.TAIL)
    fun injectTail(callback: CallbackInfo) = println("After process")
}
```

### 场景 12: 指令点注入

`FIELD`、`FIELD_ASSIGN`、`CAST`、`THROW` 可以把 handler 插入到具体字节码指令前后，`NEW` 可以插入到对象创建指令之前，适合观察字段访问、字段写入、类型转换、对象创建或异常抛出位置。

```kotlin
@AsmMixin("com/example/Player")
object FieldPointMixin {
    @AsmInject(
        method = "getName()Ljava/lang/String;",
        target = InjectionPoint.FIELD,
        at = At(
            value = InjectionPoint.FIELD,
            target = "com/example/Player.name:Ljava/lang/String;",
            shift = Shift.BEFORE,
        ),
    )
    @JvmStatic
    fun beforeNameRead() {
        println("name field will be read")
    }

    @AsmInject(
        method = "createBuffer()Ljava/lang/StringBuilder;",
        target = InjectionPoint.NEW,
        at = At(value = InjectionPoint.NEW, target = "java/lang/StringBuilder"),
    )
    @JvmStatic
    fun beforeNewStringBuilder() {
        println("StringBuilder will be created")
    }

    @AsmInject(
        method = "castName(Ljava/lang/Object;)Ljava/lang/String;",
        target = InjectionPoint.CAST,
        at = At(value = InjectionPoint.CAST, target = "java/lang/String"),
    )
    @JvmStatic
    fun beforeStringCast() {
        println("value will be cast to String")
    }
}
```

指令点注入不会替换原始指令，也不会自动把栈顶字段值、待写入值、局部变量值、new 出来的对象、类型转换对象或异常对象传给 handler。普通 `LOAD` / `STORE` 可用 `Slice` 缩小候选读写指令范围，也可用 `at.args = ["index=N"]` 或 `["var=N"]` 按 JVM 局部变量槽位过滤；字段、对象创建、类型转换与抛异常指令点当前不使用 `slice`。`NEW` 不支持 `Shift.AFTER`，因为此时未初始化对象仍在栈上，插入普通 handler 可能生成无法通过 JVM 校验的字节码。`INSTANCEOF` 当前不作为普通 `@AsmInject` 指令点使用；如果需要改写类型判断结果，应使用 `@ModifyExpressionValue(at = At(value = InjectionPoint.INSTANCEOF, target = "..."))`。如果需要替换方法调用、修改调用参数或改写构造完成后的对象表达式、类型转换结果，优先使用 `@Redirect`、`@ModifyArg` 或 `@ModifyExpressionValue`。

## 最佳实践

### 1. 组织 Mixin 类

按功能模块组织：

```
com.example.mixins/
├── logging/LoggingMixin.kt
├── validation/ValidationMixin.kt
└── enhancement/CacheMixin.kt
```

### 2. 使用包扫描

```kotlin
AsmScanner.scanPackage("com.example.mixins")
```

### 3. 约束注入命中数

对关键补丁显式声明命中数，能在目标字节码变动时更早失败：

```kotlin
@AsmInject(
    method = "value(Z)Ljava/lang/String;",
    target = InjectionPoint.RETURN,
    require = 2,
    allow = 2,
)
fun onEveryReturn() {
}
```

同样的契约也适用于关键参数修改、关键参数组修改、关键 receiver 修改、关键操作包裹、关键条件包裹、关键变量修改、关键返回值修改、关键表达式值修改、关键常量修改和关键重定向：

```kotlin
@ModifyArg(
    method = "buildMessage(Ljava/lang/String;)Ljava/lang/String;",
    index = 0,
    at = At(value = InjectionPoint.INVOKE, target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;"),
    require = 1,
    allow = 1,
)
fun normalizeArgument(original: String): String = original.trim()

@ModifyArgs(
    method = "join(Ljava/lang/String;I)Ljava/lang/String;",
    at = At(value = InjectionPoint.INVOKE, target = "com/example/Text.combine(Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/String;"),
    require = 1,
    allow = 1,
)
fun normalizeArguments(args: Args) {
    args.set(1, args.get<String>(1).trim())
}

@ModifyReceiver(
    method = "render(Ljava/lang/String;)Ljava/lang/String;",
    at = At(value = InjectionPoint.INVOKE, target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;"),
    require = 1,
    allow = 1,
)
fun replaceReceiver(original: String): String = original.trim()

@WrapOperation(
    method = "render(Ljava/lang/String;)Ljava/lang/String;",
    at = At(value = InjectionPoint.INVOKE, target = "java/lang/String.concat(Ljava/lang/String;)Ljava/lang/String;"),
    require = 1,
    allow = 1,
)
fun wrapRenderConcat(
    receiver: String,
    value: String,
    operation: Operation<String>,
): String = operation.call(receiver.trim(), value)

@WrapWithCondition(
    method = "notify(Ljava/lang/String;)V",
    at = At(value = InjectionPoint.INVOKE, target = "com/example/Audit.emit(Ljava/lang/String;)V"),
    require = 1,
    allow = 1,
)
fun shouldEmitAudit(message: String): Boolean = message.isNotBlank()

@ModifyVariable(
    method = "normalize(Ljava/lang/String;)Ljava/lang/String;",
    at = At(value = InjectionPoint.STORE),
    ordinal = 0,
    require = 1,
    allow = 1,
)
fun normalizeStoredLocal(value: String): String = value.trim()

@ModifyReturnValue(
    method = "lookup(Ljava/lang/String;)Ljava/lang/String;",
    ordinal = 0,
    require = 1,
    allow = 1,
)
fun fallbackLookupResult(original: String?): String = original ?: "default"

@ModifyExpressionValue(
    method = "format(Ljava/lang/String;)Ljava/lang/String;",
    at = At(value = InjectionPoint.INVOKE, target = "java/lang/String.trim()Ljava/lang/String;"),
    require = 1,
    allow = 1,
)
fun normalizeTrimResult(original: String): String = original.lowercase()

@ModifyConstant(method = "maxPlayers()I", constant = "20", require = 1, allow = 1)
fun expandLimit(original: Int): Int = original * 2

@Redirect(
    method = "connect(Ljava/lang/String;)V",
    target = "java/net/Socket.connect(Ljava/net/SocketAddress;I)V",
    require = 1,
    allow = 1,
)
fun redirectConnect(socket: Socket, address: SocketAddress, timeout: Int) {
    socket.connect(address, timeout)
}
```

`require` 限制最少命中数，`allow` 限制最多命中数；违反时转换失败。`expect` 可用于调试期望值，设置为非默认值时不一致只输出警告。

### 4. 错误处理

```kotlin
try {
    val transformed = processor.transform(className, classBytes, classLoader)
} catch (e: Exception) {
    logger.error("Failed to transform: $className", e)
}
```

### 5. 方法签名格式

使用 JVM 内部名称格式：

- `"method()V"` - 无参数，返回 void
- `"method(Ljava/lang/String;)V"` - String 参数，返回 void
- `"method(Ljava/lang/String;I)Ljava/lang/String;"` - String 和 int 参数，返回 String

### 6. 静态方法处理

覆盖或注入静态方法时必须使用 `@JvmStatic`：

```kotlin
@Overwrite(method = "staticMethod()V")
@JvmStatic
fun staticMethod() { }
```

### 7. Shadow 字段使用

Shadow 字段必须在 `class` 中声明，不能在 `object` 中：

```kotlin
@AsmMixin("Target")
class MyMixin {  // ✅ 使用 class
    @Shadow()
    private val field: String? = null

    @Shadow("actualName")
    private val aliasName: String? = null
}
```

Shadow 目标名称解析规则：

- `@Shadow()` 使用声明名匹配目标成员
- `@Shadow("shadow_name")` 去掉 `shadow_` 前缀后匹配目标成员 `name`
- `@Shadow("actualName")` 直接匹配显式目标成员 `actualName`

### 8. 性能考虑

- 避免在注入方法中执行耗时操作
- 使用 `inline = true` 减少方法调用开销
- 合理使用 `require` / `allow` 约束关键注入点数量

### 9. 测试 Mixin

```kotlin
@Test
fun testMixin() {
    AsmRegistry.clear()
    AsmRegistry.register(MyMixin::class.java)
    val transformed = processor.transform(className, originalBytes, null)
    // 测试转换后的行为
}
```

## 调试技巧

### 生成转换后的类文件

```kotlin
val transformed = processor.transform(className, classBytes, classLoader)
Files.write(Paths.get("output/$className.class"), transformed)
```

然后使用反编译工具（如 JD-GUI）查看。

### 添加日志

```kotlin
@AsmInject(method = "test()V", target = InjectionPoint.HEAD)
fun inject(callback: CallbackInfo) {
    println("[Mixin] Injecting into test()")
}
```

### 验证方法签名

使用 `javap -s` 查看实际的方法签名：

```bash
javap -s com.example.TargetClass
```

## 常见问题

**Q: 如何调试 ASM 转换？**  
A: 生成转换后的类文件，使用反编译工具查看。详见 [调试技巧](#调试技巧)

**Q: Shadow 字段为什么必须是可空类型？**  
A: Shadow 字段只是引用，不会实际初始化，必须声明为可空类型并初始化为 `null`。

**Q: 如何访问目标类的 `this`？**  
A: 在 `@AsmInject` 中，如果方法签名包含目标类类型作为第一个参数，可以接收 `this`。

**Q: 支持 Kotlin 协程吗？**  
A: 目前不支持，建议在注入方法中使用同步代码。

## 故障排除

### Mixin 没有生效

- 检查是否已注册：`AsmRegistry.getForTarget("com/example/TargetClass")`
- 验证方法签名：使用 `javap -s` 查看实际签名
- 确保在类加载前注册 Mixin

### 方法签名错误

- 使用 `javap -s` 查看实际的方法签名
- 确保使用 JVM 内部名称格式（如 `Ljava/lang/String;`）
- 检查参数类型和返回类型是否匹配

### Shadow 字段无法访问

- 将 Mixin 改为 `class` 而不是 `object`
- 确保字段类型与目标类匹配
- 若 ASM 字段名与目标字段名不同，使用 `@Shadow("目标字段名")` 或 `shadow_` 前缀声明目标名

### 静态方法注入失败

添加 `@JvmStatic` 注解：

```kotlin
@Overwrite(method = "staticMethod()V")
@JvmStatic
fun staticMethod() { }
```

### 转换后的类无法加载

- 检查转换后的字节码是否正确
- 使用 ASM 工具验证字节码
- 检查类依赖关系

### 多个 Mixin 冲突

- 检查 Mixin 注册顺序
- 使用 `ordinal` 参数选择第 N 个匹配点，避免同一个注入处理器命中过多返回点、调用点或指令点
- 考虑合并冲突的 Mixin

## 示例代码

完整的示例代码请参考 `src/test/kotlin/kim/der/asm/` 目录下的测试用例：

- `TestMixin.kt` - 基础注入、覆盖、访问器、调用器和 Shadow 示例
- `FrameworkReliabilityTest.kt` - `@ModifyVariable`、`@WrapOperation`、`@WrapMethod`、`@WrapWithCondition`、
  构造器调用器等可靠性测试
- `AsmScannerTest.kt` - 扫描和注册示例

## 更多资源

- [README.md](README.md) - 项目概述
- [API.md](API.md) - 完整的 API 参考
- [测试用例](../src/test/kotlin/kim/der/asm/) - 更多示例代码

