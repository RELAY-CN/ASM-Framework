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
- **@AsmInject** - 在指定位置注入代码
- **@Overwrite** - 完全覆盖方法
- **@ModifyArg** - 修改方法参数
- **@ModifyReturnValue** - 修改返回值
- **@ModifyConstant** - 修改常量值
- **@Redirect** - 重定向方法调用
- **@Shadow** - 引用目标类的字段/方法
- **@Accessor** - 生成字段访问器
- **@Invoker** - 调用私有方法
- **@Copy** - 复制方法到目标类
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
}
```

### 场景 2: 修改方法参数

```kotlin
@AsmMixin("com/example/Validator")
object ValidationMixin {
    @ModifyArg(method = "validate(Ljava/lang/String;)Z", index = 0)
    fun sanitizeInput(input: String): String = input.trim().toLowerCase()
}
```

### 场景 3: 修改返回值

```kotlin
@AsmMixin("com/example/Cache")
object CacheMixin {
    @ModifyReturnValue(method = "get(Ljava/lang/String;)Ljava/lang/Object;")
    fun wrapResult(original: Any?): Any? = original ?: createDefault()
}
```

### 场景 4: 完全替换方法

```kotlin
@AsmMixin("com/example/LegacyClass")
object ModernizeMixin {
    @Overwrite(method = "oldMethod()V")
    @JvmStatic
    fun oldMethod() = println("Modern implementation")
}
```

### 场景 5: 访问私有字段

```kotlin
@AsmMixin("com/example/PrivateClass")
class AccessMixin {
    @Shadow()
    private val privateField: String? = null
    
    @Accessor("privateField")
    fun getPrivateField(): String = throw UnsupportedOperationException()
    
    @AsmInject(method = "method()V", target = InjectionPoint.HEAD)
    fun inject(callback: CallbackInfo) {
        println("Field value: $privateField")
    }
}
```

### 场景 6: 调用私有方法

```kotlin
@AsmMixin("com/example/PrivateClass")
object InvokerMixin {
    @Invoker("privateMethod(Ljava/lang/String;)V")
    fun invokePrivateMethod(param: String) = throw UnsupportedOperationException()
    
    @AsmInject(method = "publicMethod()V", target = InjectionPoint.HEAD)
    fun inject(callback: CallbackInfo) {
        invokePrivateMethod("test")
    }
}
```

### 场景 7: 重定向方法调用

```kotlin
@AsmMixin("com/example/NetworkClient")
object RedirectMixin {
    @Redirect(method = "connect()V", target = "java/net/Socket.connect(Ljava/net/SocketAddress;I)V")
    fun redirectConnect(socket: Socket, address: SocketAddress, timeout: Int) {
        println("Custom connection logic")
    }
}
```

### 场景 8: 条件取消执行

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

### 场景 9: 修改返回值

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

### 场景 10: 多个注入点

```kotlin
@AsmMixin("com/example/Service")
object MultiInjectMixin {
    @AsmInject(method = "process()V", target = InjectionPoint.HEAD)
    fun injectHead(callback: CallbackInfo) = println("Before process")
    
    @AsmInject(method = "process()V", target = InjectionPoint.TAIL)
    fun injectTail(callback: CallbackInfo) = println("After process")
}
```

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

### 3. 错误处理

```kotlin
try {
    val transformed = processor.transform(className, classBytes, classLoader)
} catch (e: Exception) {
    logger.error("Failed to transform: $className", e)
}
```

### 4. 方法签名格式

使用 JVM 内部名称格式：
- `"method()V"` - 无参数，返回 void
- `"method(Ljava/lang/String;)V"` - String 参数，返回 void
- `"method(Ljava/lang/String;I)Ljava/lang/String;"` - String 和 int 参数，返回 String

### 5. 静态方法处理

覆盖或注入静态方法时必须使用 `@JvmStatic`：
```kotlin
@Overwrite(method = "staticMethod()V")
@JvmStatic
fun staticMethod() { }
```

### 6. Shadow 字段使用

Shadow 字段必须在 `class` 中声明，不能在 `object` 中：
```kotlin
@AsmMixin("Target")
class MyMixin {  // ✅ 使用 class
    @Shadow()
    private val field: String? = null
}
```

### 7. 性能考虑

- 避免在注入方法中执行耗时操作
- 使用 `inline = true` 减少方法调用开销
- 合理使用 `require` 参数

### 8. 测试 Mixin

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
- 使用 `ordinal` 参数控制注入顺序
- 考虑合并冲突的 Mixin

## 示例代码

完整的示例代码请参考 `src/test/kotlin/kim/der/asm/` 目录下的测试用例：

- `InjectMixin.kt` - 注入示例
- `OverwriteMixin.kt` - 覆盖示例
- `ModifyArgMixin.kt` - 修改参数示例
- `ModifyReturnValueMixin.kt` - 修改返回值示例
- `RedirectMixin.kt` - 重定向示例
- `AccessorMixin.kt` - 访问器示例
- `InvokerMixin.kt` - 调用器示例
- `ShadowMixin.kt` - Shadow 示例

## 更多资源

- [README.md](README.md) - 项目概述
- [API.md](API.md) - 完整的 API 参考
- [测试用例](../src/test/kotlin/kim/der/asm/) - 更多示例代码

