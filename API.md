# ASM-Framework API 文档

完整的 API 参考文档。

## 目录

- [核心 API](#核心-api)
- [注解 API](#注解-api)
- [工具类](#工具类)
- [类型映射](#类型映射)

## 核心 API

### AsmRegistry

ASM 注册器，负责管理所有注册的 ASM 类。

#### 方法

##### `register(asmClass: Class<*>)`

注册一个 ASM 类。

**参数：**
- `asmClass`: 带 `@AsmMixin` 注解的类

**示例：**
```kotlin
AsmRegistry.register(MyMixin::class.java)
AsmRegistry.registerWithPathMatcher(MyMixin::class.java) { className ->
    className.startsWith("com/example/")
}
val asms = AsmRegistry.getForTarget("com/example/TargetClass")
AsmRegistry.clear()
```

### AsmScanner

ASM 扫描器，用于自动扫描和注册 ASM 类。

#### 方法

**方法：**
- `scanPackage(packageName: String)` - 扫描包
- `scanDirectory(directory: File, packageName: String)` - 扫描目录
- `scanJar(jarFile: File, packageName: String)` - 扫描 JAR
- `scanClassLoader(classLoader: ClassLoader, packageName: String)` - 扫描类加载器

**示例：**
```kotlin
AsmScanner.scanPackage("com.example.asms")
AsmScanner.scanDirectory(File("build/classes"), "com.example.asms")
AsmScanner.scanJar(File("asms.jar"), "com.example.asms")
```

### AsmProcessor

ASM 处理器，负责应用所有注册的 ASM 到目标类。

#### 方法

**方法：**
- `transform(className: String, classBytes: ByteArray, classLoader: ClassLoader?): ByteArray` - 转换类字节码
- `applyAsms(className: String, classNode: ClassNode): Boolean` - 应用 ASM 到类节点

**示例：**
```kotlin
val processor = AsmProcessor()
val transformed = processor.transform("com/example/TargetClass", originalBytes, null)
```

## 注解 API

### @AsmMixin

标记一个类为 ASM Mixin 类。

**参数：**
- `value: String = ""` - 单个目标类名（内部名称）
- `targets: Array<String> = []` - 多个目标类名数组
- `remap: Boolean = false` - 是否重映射（暂时不支持）

**示例：**
```kotlin
@AsmMixin("com/example/TargetClass")
object MyMixin

@AsmMixin(targets = ["com/example/Class1", "com/example/Class2"])
object MultiTargetMixin
```

### @AsmInject

在目标方法的指定位置注入代码。

**参数：**
- `method: String = ""` - 目标方法签名
- `target: InjectionPoint = InjectionPoint.HEAD` - 注入点位置
- `cancellable: Boolean = false` - 是否可取消方法执行
- `require: Int = 0` - 是否必须找到目标方法（0=可选，1=必须）
- `at: At = At()` - 精确注入位置
- `ordinal: Int = -1` - 当有多个匹配时，指定使用第几个（从0开始）
- `slice: Slice = Slice()` - 注入点切片
- `allow: Int = -1` - 允许的最大注入次数（-1表示不限制）
- `expect: Int = 1` - 期望的注入次数
- `inline: Boolean = false` - 是否内联代码

**示例：** 见 [GUIDE.md](GUIDE.md#常见场景)

### @Overwrite

完全覆盖目标方法的实现。

**参数：**
- `method: String = ""` - 目标方法签名
- `remap: Boolean = false` - 是否重映射

**示例：** 见 [GUIDE.md](GUIDE.md#常见场景)

### @ModifyArg

修改目标方法的参数值。

**参数：**
- `method: String = ""` - 目标方法签名
- `index: Int = -1` - 参数索引（从0开始）
- `at: At = At()` - 注入位置
- `slice: Slice = Slice()` - 切片范围
- `remap: Boolean = false` - 是否重映射

**示例：** 见 [GUIDE.md](GUIDE.md#常见场景)

### @ModifyReturnValue

修改目标方法的返回值。

**参数：**
- `method: String = ""` - 目标方法签名
- `at: At = At()` - 注入位置
- `remap: Boolean = false` - 是否重映射

**示例：** 见 [GUIDE.md](GUIDE.md#常见场景)

### @ModifyConstant

修改方法中的常量值。

**参数：**
- `method: String = ""` - 目标方法签名
- `constant: String = ""` - 要修改的常量值
- `remap: Boolean = false` - 是否重映射

**示例：** 见 [GUIDE.md](GUIDE.md#常见场景)

### @Redirect

重定向目标方法中的方法调用。

**参数：**
- `method: String = ""` - 目标方法签名
- `target: String = ""` - 要重定向的方法调用
- `at: At = At()` - 注入位置
- `slice: Slice = Slice()` - 切片范围
- `remap: Boolean = false` - 是否重映射

**示例：** 见 [GUIDE.md](GUIDE.md#常见场景)

### @Shadow

在 Mixin 类中引用目标类的字段或方法。

**参数：**
- `method: String = ""` - 目标成员名（字段或方法）
- `remap: Boolean = false` - 是否重映射

**示例：** 见 [GUIDE.md](GUIDE.md#常见场景)

### @Accessor

为字段生成访问器方法。

**参数：**
- `value: String = ""` - 字段名
- `remap: Boolean = false` - 是否重映射

**示例：** 见 [GUIDE.md](GUIDE.md#常见场景)

### @Invoker

生成调用私有/受保护方法的访问器。

**参数：**
- `value: String = ""` - 方法签名
- `remap: Boolean = false` - 是否重映射

**示例：**
```kotlin
@Invoker("privateMethod()V")
fun invokePrivateMethod() {
    throw UnsupportedOperationException()
}
```

### @Copy

将 Mixin 方法复制到目标类中作为新方法。

**参数：**
- `method: String = ""` - 目标方法签名
- `remap: Boolean = false` - 是否重映射

**示例：**
```kotlin
@Copy(method = "newMethod()V")
fun newMethod() {
    // 实现
}
```

### @RemoveMethod

移除目标类中的方法。

**参数：**
- `method: String = ""` - 目标方法签名
- `remap: Boolean = false` - 是否重映射

**示例：**
```kotlin
@RemoveMethod(method = "unwantedMethod()V")
fun removeMethod() {}
```

### @RemoveSynchronized

移除目标方法的 `synchronized` 关键字。

**参数：**
- `method: String = ""` - 目标方法签名
- `remap: Boolean = false` - 是否重映射

**示例：**
```kotlin
@RemoveSynchronized(method = "synchronizedMethod()V")
fun removeSync() {}
```

### @ReplaceAllMethods

替换目标类中的所有方法。

**参数：**
- `removeSync: Boolean = false` - 是否同时移除所有方法的 synchronized
- `remap: Boolean = false` - 是否重映射

**示例：**
```kotlin
@ReplaceAllMethods(removeSync = true)
@AsmMixin("com/example/TargetClass")
object MyMixin
```

### @Mutable

标记字段为可变，用于移除 `final` 修饰符。

**示例：**
```kotlin
@Shadow()
@Mutable
private val finalField: String? = null
```

### @Final

标记字段为最终（添加 `final` 修饰符）。

**示例：**
```kotlin
@Final
@Shadow()
private val field: String? = null
```

## 工具类

### CallbackInfo

回调信息类，用于在注入的方法中控制目标方法的执行流程。

#### 方法

##### `cancel()`

取消方法的继续执行（如果可取消）。

**示例：**
```kotlin
callback.cancel()
```

##### `isCancelled(): Boolean`

检查是否已取消。

**返回：**
- `Boolean`: 是否已取消

**示例：**
```kotlin
if (callback.isCancelled()) {
    return
}
```

##### `getReturnValue<T>(): T?`

获取返回值。

**类型参数：**
- `T`: 返回类型

**返回：**
- `T?`: 返回值，如果未设置则返回 null

**示例：**
```kotlin
val value = callback.getReturnValue<String>()
```

##### `setReturnValue(value: Any?)`

设置返回值（仅在支持返回值修改的注入点有效）。

**参数：**
- `value`: 返回值

**示例：**
```kotlin
callback.setReturnValue("new value")
```

#### 伴生对象方法

##### `cancellable(): CallbackInfo`

创建可取消的回调信息。

**返回：**
- `CallbackInfo`: 已调用 `cancel()` 的回调信息

**示例：**
```kotlin
val callback = CallbackInfo.cancellable()
```

##### `returnable(returnValue: Any?): CallbackInfo`

创建带返回值的回调信息。

**参数：**
- `returnValue`: 返回值

**返回：**
- `CallbackInfo`: 带返回值的回调信息

**示例：**
```kotlin
val callback = CallbackInfo.returnable("value")
```

### InjectionPoint

注入点枚举，定义代码注入的位置。

**值：**
- `HEAD` - 方法开头
- `TAIL` - 方法结尾（所有 RETURN 之前）
- `RETURN` - 返回前（每个 RETURN 之前）
- `INVOKE` - 方法调用前
- `INVOKE_ASSIGN` - 方法调用后
- `FIELD` - 字段访问前
- `FIELD_ASSIGN` - 字段赋值前
- `NEW` - NEW 操作前
- `THROW` - 抛出异常前

### At

用于指定精确的注入位置。

**参数：**
- `value: InjectionPoint = InjectionPoint.HEAD` - 注入点类型
- `target: String = ""` - 目标方法/字段签名
- `shift: Shift = Shift.BEFORE` - 偏移方向
- `by: Int = 0` - 偏移量
- `args: Array<String> = []` - 参数类型数组

**示例：**
```kotlin
At(
    value = InjectionPoint.INVOKE,
    target = "java/lang/System.println(Ljava/lang/String;)V",
    shift = Shift.AFTER
)
```

### Shift

注入位置偏移枚举。

**值：**
- `BEFORE` - 在目标之前
- `AFTER` - 在目标之后
- `REPLACE` - 替换目标

### Slice

用于定义查找范围。

**参数：**
- `from: At = At()` - 起始位置
- `to: At = At()` - 结束位置
- `id: String = ""` - 切片标识符

**示例：**
```kotlin
Slice(
    from = At(value = InjectionPoint.INVOKE, target = "method1()V"),
    to = At(value = InjectionPoint.INVOKE, target = "method2()V")
)
```

## 高级用法

### 路径匹配器

```kotlin
AsmRegistry.registerWithPathMatcher(MyMixin::class.java) { className ->
    className.startsWith("com/example/")
}
```

### 多个 ASM 组合

```kotlin
AsmRegistry.register(ModifyArgMixin::class.java)
AsmRegistry.register(ModifyReturnValueMixin::class.java)
```
它们会按注册顺序应用。

### 内联代码注入

使用 `inline = true` 可以将字节码直接插入，而不是方法调用。

### Transformer API

实现 `Transformer` 接口可以自定义转换逻辑。

## 类型映射参考

### 基本类型

| Java 类型 | JVM 描述符 |
|----------|-----------|
| void     | V         |
| boolean  | Z         |
| byte     | B         |
| short    | S         |
| int      | I         |
| long     | J         |
| float    | F         |
| double   | D         |
| char     | C         |

### 引用类型

| Java 类型 | JVM 描述符 |
|----------|-----------|
| String   | Ljava/lang/String; |
| Object   | Ljava/lang/Object; |
| int[]    | [I |
| String[] | [Ljava/lang/String; |
| com.example.Class | Lcom/example/Class; |

### 方法签名示例

```
()V                                    // void method()
(I)V                                   // void method(int)
(Ljava/lang/String;)V                  // void method(String)
(Ljava/lang/String;I)Ljava/lang/String; // String method(String, int)
([Ljava/lang/String;)V                 // void method(String[])
```

## 注意事项

1. **方法签名必须精确匹配**：包括参数类型和返回类型
2. **静态方法需要 @JvmStatic**：覆盖或注入静态方法时必须使用
3. **Shadow 字段必须是可空类型**：并初始化为 `null`
4. **Shadow 成员必须在 class 中**：不能在 object 中声明
5. **类型使用内部名称**：类名使用 `/` 分隔，如 `com/example/Class`
6. **错误处理**：建议在应用 ASM 时进行适当的错误处理

## 示例项目

完整的示例代码请参考 `src/test/kotlin/kim/der/asm/` 目录下的测试用例。

