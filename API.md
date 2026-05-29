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

### @AddInterface

为目标类追加接口声明。

该注解只修改目标类的 `interfaces` 列表，不会自动生成接口方法实现。若新增接口包含抽象方法，需要目标类已有对应方法，或通过 `@Copy` / `@Overwrite` 等方式补齐实现。

**参数：**

- `value: String = ""` - 单个接口名，可使用 JVM internal name 或 Java binary name
- `interfaces: Array<String> = []` - 多个接口名
- `remap: Boolean = false` - 是否重映射

**示例：**

```kotlin
@AsmMixin("com/example/TargetClass")
@AddInterface("java/io/Closeable")
object CloseableMixin

@AsmMixin("com/example/TargetClass")
@AddInterface(interfaces = ["java.lang.Runnable", "java/io/Serializable"])
object MultiInterfaceMixin
```

### @RemoveInterface

从目标类移除接口声明。

该注解只修改目标类的 `interfaces` 列表，不会删除目标类中已有的方法实现。若目标类没有声明指定接口，会直接跳过，不视为转换失败。

**参数：**

- `value: String = ""` - 单个接口名，可使用 JVM internal name 或 Java binary name
- `interfaces: Array<String> = []` - 多个接口名
- `remap: Boolean = false` - 是否重映射

**示例：**

```kotlin
@AsmMixin("com/example/TargetClass")
@RemoveInterface("java/lang/Runnable")
object RemoveRunnableMixin

@AsmMixin("com/example/TargetClass")
@RemoveInterface(interfaces = ["java.lang.Cloneable", "java/io/Serializable"])
object RemoveInterfacesMixin
```

### @AsmInject

在目标方法的指定位置注入代码。

**参数：**

- `method: String = ""` - 目标方法签名；为空时按 handler 名称、注入点和签名兼容规则推断唯一同名目标方法
- `target: InjectionPoint = InjectionPoint.HEAD` - 注入点位置
- `cancellable: Boolean = false` - 是否允许 handler 调用 `CallbackInfo.cancel()` 或通过 `CallbackInfo.setReturnValue(...)` 触发取消；当前 `HEAD` 注入会据此生成提前返回分支
- `require: Int = 0` - 最小命中数；大于 0 时实际命中数必须不少于该值。默认仍要求至少命中 1 个注入点
- `at: At = At()` - 精确注入位置；普通 `LOAD` / `STORE` 可通过 `at.args = ["index=N"]` 或 `["var=N"]` 按 JVM 局部变量槽位过滤
- `ordinal: Int = -1` - 匹配点序号；`-1` 表示处理全部匹配点，`0` 及以上表示只处理第 N 个匹配点（当前对 `RETURN` / `INVOKE` / `INVOKE_ASSIGN` 与指令点注入生效）
- `slice: Slice = Slice()` - 注入点切片；当前普通 `INVOKE`、`FIELD` / `FIELD_ASSIGN`、`LOAD` / `STORE`、`NEW`、`CAST` / `THROW` 指令点注入支持用 `INVOKE` 边界缩小查找范围
- `allow: Int = -1` - 允许的最大命中数；`-1` 表示不限制
- `expect: Int = 1` - 期望命中数；设置为非默认值时，不一致会输出警告但不阻断转换
- `inline: Boolean = false` - 是否内联代码

handler 首参可以是 `CallbackInfo`。普通 `HEAD` / `TAIL` / `RETURN` 注入以及 `FIELD` / `FIELD_ASSIGN` / `NEW` / `CAST` / `THROW`
指令点注入，可在 `CallbackInfo` 后按顺序接收目标方法参数前缀。
`INVOKE` 的 `Shift.BEFORE` / `Shift.AFTER` 注入会先接收匹配调用的方法参数前缀，再继续接收目标方法参数前缀；
引用或数组调用参数、目标方法参数可用原值类型的父类、接口、`Any` 或 `Object` 接收，基础类型仍需精确匹配。
实例调用 receiver 会被框架保存和恢复，但不会作为普通 handler 参数传入。`Shift.REPLACE` 按替换原调用处理，
handler 参数对应原调用参数，返回值需要与原调用返回类型兼容。
除 `Shift.REPLACE` 这类替换原操作的注入外，普通 `HEAD` / `TAIL` / `RETURN`、`INVOKE` 的 `BEFORE` / `AFTER`
以及普通指令点注入的 handler 返回值不会参与目标方法结果，框架会在调用后丢弃该返回值以保持栈平衡。
省略 `method` 时，handler 名称必须与目标方法名一致，并且只能匹配到一个包含兼容注入点的同名目标方法；存在多个兼容重载时需要显式写出目标方法签名。

需要在 `HEAD` 注入中提前返回时，必须设置 `cancellable = true`。未声明可取消的 `CallbackInfo` 调用 `cancel()` 会抛出
`IllegalStateException`，避免配置错误被静默忽略。对于非 `void` 目标方法，可取消回调调用 `setReturnValue(...)`
会同时标记取消，并提前返回该值。普通 `RETURN` 注入会先把原始返回值预置到 `CallbackInfo`，handler 调用
`setReturnValue(null)` 时，引用类型返回值会被明确替换为 `null`，不会被当作未修改。

`@AsmInject` 会统计实际命中的注入点数量。默认至少需要 1 次命中；`require` 可提高最小命中数，`allow`
可限制最大命中数，违反时会在转换阶段失败。`expect` 用于调试期望值，设置为非默认值且与实际命中数不一致时只输出警告。

普通 `@AsmInject(target = InjectionPoint.INVOKE)`、普通 `@AsmInject(target = InjectionPoint.FIELD / FIELD_ASSIGN)`、
普通 `@AsmInject(target = InjectionPoint.LOAD / STORE)` 与普通 `@AsmInject(target = InjectionPoint.NEW / CAST / INSTANCEOF / THROW)`
支持 `slice.from` / `slice.to` 为 `InjectionPoint.INVOKE` 的切片边界。框架只在起始边界之后、
结束边界之前查找目标调用、字段读写指令、局部变量读写指令、对象创建、类型转换、类型判断或抛异常指令；边界调用本身不会作为候选注入点，`ordinal`
也会在切片内重新计数。指定的边界未命中时，切片按空范围处理，不会回退到全方法查找。

`FIELD` / `FIELD_ASSIGN` / `LOAD` / `STORE` / `NEW` / `CAST` / `INSTANCEOF` / `THROW` 属于指令点注入。它们会在匹配指令附近插入 handler，不会替换原始指令，也不会自动把栈顶字段值、待写入值、局部变量值、new 出来的对象、类型转换对象、类型判断结果或异常对象传给 handler。普通 `FIELD` / `FIELD_ASSIGN` / `LOAD` / `STORE` / `NEW` / `CAST` / `INSTANCEOF` / `THROW` 可用 `Slice` 缩小候选范围；除 `NEW` 外也可用 `At.by` 按真实字节码指令数向前或向后移动插入锚点，偏移会跳过 label、frame 与 line number 等伪指令；普通 `LOAD` / `STORE` 只作为观察 hook，可用 `at.args = ["index=N"]` 或 `["var=N"]` 只匹配指定 JVM 局部变量槽位；普通 `THROW` 可用 `At.target` 的类型 internal name 或 binary name 只匹配 `ATHROW` 前直接构造出的同类型异常，但仍不会把异常对象传给 handler；需要读取并写回变量值时使用 `@ModifyVariable`。普通 `INSTANCEOF` 只观察类型判断位置，不接收也不改写 boolean 结果；需要改写类型判断结果时使用 `@ModifyExpressionValue`。普通 `NEW` 不支持 `At.by`，且只支持 `Shift.BEFORE` 与 `Shift.REPLACE`，避免在未初始化对象仍位于栈顶时插入普通方法调用。

**示例：** 见 [GUIDE.md](GUIDE.md#常见场景)

### @Overwrite

完全覆盖目标方法的实现。

`@Overwrite` 会复制 ASM 方法体到目标方法，并保留目标方法自己的签名。目标方法不存在、方法体无法提取，
或返回值/参数槽位无法适配时，转换会失败，不会静默跳过。

当同一个 Mixin 同时使用 `@ReplaceAllMethods` 时，类级全方法替换会先执行，随后方法级 `@Overwrite`
可以定点覆盖某个关键方法。

**参数：**

- `method: String = ""` - 目标方法签名；为空时按 ASM handler 方法名与 JVM 描述符匹配目标方法
- `remap: Boolean = false` - 是否重映射

**示例：** 见 [GUIDE.md](GUIDE.md#常见场景)

### @Unique

标记 Mixin 成员在复制到目标类时需要避免名称冲突。当前实现支持与 `@Copy` 方法配合使用。

当 `@Copy` 目标方法签名与目标类已有方法冲突时，未标记 `@Unique` 的复制方法会跳过并输出 warning；
标记 `@Unique` 后，框架会为复制方法生成唯一名称，访问级别设为 `private synthetic`，并保留静态性。
同一个 Mixin 中 `@Overwrite`、`@Copy` 与 inline `@AsmInject` 方法体里对该复制方法的调用会被同步改写到唯一名称。

当前 `@Unique` 不处理字段唯一化，也不改变 `@AddField` 的同名字段跳过语义。

**示例：** 见 [GUIDE.md](GUIDE.md#常见场景)

### @ModifyArg

修改目标方法入口参数，或修改目标方法内匹配方法调用、构造器调用或 `invokedynamic` 调用的参数值。

**参数：**

- `method: String = ""` - 目标方法签名；可省略并按 handler 名称、参数类型和实际兼容调用点推断唯一兼容目标
- `index: Int = -1` - 参数索引（从 0 开始）；默认入口模式下为目标方法参数索引，`INVOKE` 模式下为目标调用参数索引
- `at: At = At()` - 注入位置；默认 `HEAD` 改写入口参数，`at.value = InjectionPoint.INVOKE` 时用 `at.target` 匹配目标方法调用、构造器调用或 `invokedynamic` 调用；`at.target` 为空则按 handler 签名推断兼容调用点
- `ordinal: Int = -1` - 调用点匹配序号；`-1` 表示修改全部匹配调用点，当前仅在 `INVOKE` 模式下生效
- `slice: Slice = Slice()` - 切片范围；当前 `INVOKE` 模式支持用 `INVOKE` 边界缩小查找范围
- `require: Int = 0` - 最小命中数；大于 0 时实际参数修改数必须不少于该值
- `expect: Int = 1` - 期望命中数；设置为非默认值时，不一致会输出警告但不阻断转换
- `allow: Int = -1` - 允许的最大命中数；`-1` 表示不限制
- `remap: Boolean = false` - 是否重映射

`@ModifyArg` handler 的第一个参数必须接收被修改的原始参数值，并返回同类型的新值；当被修改参数或追加的目标方法参数是对象/数组类型时，对应 handler 参数可声明为原值类型的父类、接口、`Any` 或 `Object`，返回值则可为原参数类型的可赋值子类型，也可用 `Any` / `Object` 作为泛型引用返回类型，框架会在 handler 调用后转换回原参数类型，基础类型仍需精确匹配。省略 `method` 时，框架会在目标类中查找与 handler 同名的方法：入口参数模式用 `index` 指向的目标参数类型、handler 首参、返回类型和后续目标方法参数前缀筛出唯一兼容目标；调用点模式用实际兼容调用点筛出唯一兼容目标。多个兼容重载会在转换阶段失败，需显式写出 `method`。后续参数可按目标方法声明顺序接收目标方法参数前缀。调用点模式会在原调用执行前保存 receiver 与调用参数，改写 `index` 选中的调用参数后按原顺序恢复并继续执行原调用；省略 `at.target` 时，会用 `index` 指向的调用参数类型、handler 首参、返回类型和后续目标方法参数前缀筛选普通方法调用、构造器调用或 `invokedynamic` 调用，不兼容候选不计入 `ordinal` 或命中数。构造器调用使用 `<init>` 目标，只能修改构造器描述符内的参数，不暴露未初始化 receiver。`invokedynamic` 调用没有 receiver，目标按 bootstrap owner、动态调用名或 bootstrap 名，以及动态调用点描述符匹配，例如 `java/lang/invoke/StringConcatFactory.makeConcatWithConstants(Ljava/lang/String;I)Ljava/lang/String;`。handler 不会自动接收目标调用的其他参数，可用 `ordinal` 只选择第 N 个匹配调用点，也可用 `slice.from` / `slice.to` 把候选调用点限制在一段 `INVOKE` 边界之间。边界调用本身不参与候选匹配，`ordinal` 会在切片内重新计数。

`@ModifyArg` 会统计实际写入参数修改逻辑的数量。入口参数模式最多命中 1 次；`INVOKE` 模式按匹配调用点数量计数。
显式设置 `require` / `allow` / 非默认 `expect` 时按实际参数修改数量校验契约，违反 `require` 或 `allow`
会在转换阶段失败，`expect` 不一致只输出警告。

**示例：** 见 [GUIDE.md](GUIDE.md#常见场景)

### @ModifyArgs

修改目标方法内匹配方法调用、构造器调用或 `invokedynamic` 调用的整组参数。适合一个调用点需要同时改写多个参数的场景。

**参数：**

- `method: String = ""` - 目标方法签名；可省略并按 handler 名称、调用点、handler 签名和实际兼容调用点推断唯一兼容目标
- `at: At = At(value = InjectionPoint.INVOKE)` - 调用点定位；当前仅支持 `INVOKE`，可匹配普通方法调用、构造器调用或 `invokedynamic` 调用；`at.target` 为空时按兼容调用点推断
- `ordinal: Int = -1` - 调用点匹配序号；`-1` 表示修改全部匹配调用点，`0` 及以上表示只修改第 N 个匹配调用点
- `slice: Slice = Slice()` - 切片范围；当前支持用 `INVOKE` 边界缩小查找范围
- `require: Int = 0` - 要求的最小命中数；`0` 表示使用默认至少 1 次的显式契约语义
- `expect: Int = 1` - 期望命中数；非默认值不一致时输出警告
- `allow: Int = -1` - 允许的最大命中数；`-1` 表示不限制
- `remap: Boolean = false` - 是否重映射

`@ModifyArgs` handler 的第一个参数必须是 `Args`，并返回 `Unit` / `void`。`Args` 按目标调用描述符的参数顺序保存参数，不包含实例方法 receiver；构造器调用使用 `<init>` 目标，`Args` 只包含构造器参数，不包含未初始化 receiver；`invokedynamic` 调用没有 receiver，目标按 bootstrap owner、动态调用名或 bootstrap 名，以及动态调用点描述符匹配，例如 `java/lang/invoke/StringConcatFactory.makeConcatWithConstants(Ljava/lang/String;I)Ljava/lang/String;`。可用 `args.get<T>(index)` 读取参数，用 `args.set(index, value)` 写回兼容类型的新值。省略 `method` 时，框架会在目标类中查找与 handler 同名的方法，并用 `at.target` 调用点、handler `Args` 首参、`Unit` 返回类型、后续目标方法参数前缀和实际兼容调用点筛出唯一兼容目标；多个兼容重载会在转换阶段失败，需显式写出 `method`。省略 `at.target` 时，框架会扫描普通方法调用、构造器调用或 `invokedynamic` 调用；若同一目标方法内存在多个兼容候选，应使用 `ordinal`、`slice`、`require` / `allow` 或显式 `at.target` 收窄。handler 后续参数可按目标方法声明顺序接收目标方法参数前缀；对象或数组参数可声明为原值类型的父类、接口、`Any` 或 `Object`。可用 `ordinal` 只选择第 N 个匹配调用点，也可用 `slice.from` / `slice.to` 把候选调用点限制在一段 `INVOKE` 边界之间。边界调用本身不参与候选匹配，`ordinal` 会在切片内重新计数。

`@ModifyArgs` 会统计实际写入参数组修改逻辑的调用点数量。显式设置 `require` / `allow` / 非默认 `expect`
时按实际参数组修改数量校验契约，违反 `require` 或 `allow` 会在转换阶段失败，`expect` 不一致只输出警告。

**示例：** 见 [GUIDE.md](GUIDE.md#常见场景)

### @ModifyReceiver

修改目标方法内匹配实例方法调用、实例字段读取或实例字段写入的 receiver。适合保留原参数、字段值和原操作逻辑，只把操作对象替换为另一个兼容对象的场景。

**参数：**

- `method: String = ""` - 目标方法签名；可省略并按 handler 名称、receiver 操作点、handler 签名和实际兼容候选推断唯一兼容目标
- `at: At = At(value = InjectionPoint.INVOKE)` - 调用点定位；当前支持 `INVOKE`、`FIELD` 与 `FIELD_ASSIGN`
- `ordinal: Int = -1` - 匹配点序号；`-1` 表示修改全部匹配点，`0` 及以上表示只修改第 N 个匹配点
- `slice: Slice = Slice()` - 切片范围；当前 `INVOKE`、`FIELD` 与 `FIELD_ASSIGN` 模式支持用 `INVOKE` 边界缩小查找范围
- `require: Int = 0` - 最小命中数；大于 0 时实际 receiver 修改数必须不少于该值
- `expect: Int = 1` - 期望命中数；设置为非默认值时，不一致会输出警告但不阻断转换
- `allow: Int = -1` - 允许的最大命中数；`-1` 表示不限制
- `remap: Boolean = false` - 是否重映射

`@ModifyReceiver` handler 的第一个参数必须接收原 receiver；对象或数组 receiver 可用原 receiver 类型的父类、接口、`Any` 或 `Object` 接收，并返回兼容的新 receiver。省略 `method` 时，框架会在目标类中查找与 handler 同名的方法，并用 receiver 操作点、实际可兼容候选、handler 首参、返回类型和后续目标方法参数前缀筛出唯一兼容目标；`At.target` 为空的 `INVOKE`、`FIELD` 或 `FIELD_ASSIGN` 同样会按候选兼容性参与方法推断。多个兼容重载会在转换阶段失败，需显式写出 `method`。后续参数可按目标方法声明顺序接收目标方法参数前缀。返回值对基础类型仍需精确匹配，对象或数组类型可返回可赋值给原 receiver 类型的子类型，也可用 `Any` / `Object` 作为泛型引用返回类型，框架会在 handler 调用后转换回 receiver 类型。`INVOKE` 模式只支持实例方法调用，不支持 `INVOKESTATIC` 或构造器调用；省略 `At.target` 时，框架会按 handler 首参与返回类型筛选兼容的实例调用 receiver，静态调用、构造器调用和 handler 不兼容的实例调用不计入 `ordinal` 或命中数；原调用参数会按原顺序恢复并继续传给目标调用。`FIELD` 模式只支持 `GETFIELD`；省略 `At.target` 时，框架会按 handler 首参与返回类型筛选兼容的实例字段读取 receiver，`GETSTATIC` 和 handler 不兼容的字段读取不计入 `ordinal` 或命中数；字段读取不会把字段值传给 handler。`FIELD_ASSIGN` 模式只支持 `PUTFIELD`；省略 `At.target` 时，框架会按 handler 首参与返回类型筛选兼容的实例字段写入 receiver，`PUTSTATIC` 和 handler 不兼容的字段写入不计入 `ordinal` 或命中数；字段写入会保留原待写入值并写入新的 receiver。`INVOKE`、`FIELD` 与 `FIELD_ASSIGN` 模式均可用 `slice.from` / `slice.to` 把候选 receiver 改写限制在一段 `INVOKE` 边界之间，边界调用本身不参与候选匹配，`ordinal` 会在切片内重新计数。

`@ModifyReceiver` 会统计实际写入 receiver 修改逻辑的操作点数量。未设置 `ordinal` 时，`INVOKE`、`FIELD` 与 `FIELD_ASSIGN` 会按实际改写的 receiver 数量计数；省略 `INVOKE` 调用目标、`FIELD` 字段目标或 `FIELD_ASSIGN` 字段目标时，不兼容候选不计入数量；设置 `ordinal` 时最多命中对应序号的 1 个 receiver。显式设置 `require` / `allow` / 非默认 `expect` 时按实际 receiver 修改数量校验契约，违反 `require` 或 `allow` 会在转换阶段失败，`expect` 不一致只输出警告。

**示例：** 见 [GUIDE.md](GUIDE.md#常见场景)

### @WrapOperation

包裹目标方法内匹配的方法调用、构造器调用、字段读取、字段写入、数组元素读写、数组长度读取、类型转换或类型判断，并把原操作替换为 handler 调用。适合需要保留“可调用原操作”能力，同时按条件跳过、改写参数、改写返回值或多次调用原操作的场景。

**参数：**

- `method: String = ""` - 目标方法签名；为空时按 handler 名称、操作点和 `Operation` 签名兼容规则推断唯一同名目标方法
- `at: At = At(value = InjectionPoint.INVOKE)` - 操作点定位；当前支持 `INVOKE`、`FIELD`、`FIELD_ASSIGN`、`NEW`、`CAST` 与 `INSTANCEOF`
- `ordinal: Int = -1` - 操作点匹配序号；`-1` 表示包裹全部匹配操作点，`0` 及以上表示只包裹第 N 个匹配操作点
- `slice: Slice = Slice()` - 切片范围；当前 `INVOKE`、`FIELD`、`FIELD_ASSIGN`、`NEW`、`CAST` 与 `INSTANCEOF` 模式支持用 `INVOKE` 边界缩小查找范围
- `require: Int = 0` - 最小命中数；大于 0 时实际操作包裹数必须不少于该值
- `expect: Int = 1` - 期望命中数；设置为非默认值时，不一致会输出警告但不阻断转换
- `allow: Int = -1` - 允许的最大命中数；`-1` 表示不限制
- `remap: Boolean = false` - 是否重映射

`INVOKE` 模式的 handler 会先接收原调用栈参数：实例调用先接收 receiver，再接收原调用参数；静态调用只接收原调用参数。下一参数必须是 `Operation<R>`，其中 `R` 为原调用返回类型；handler 可通过 `operation.call(...)` 执行原始调用，也可以跳过或多次调用。handler 后续参数可按目标方法声明顺序接收目标方法参数前缀；handler 参数接收引用或数组栈值、目标方法参数时，可声明为原值类型的父类、接口、`Any` 或 `Object`。返回类型必须兼容原调用返回类型；基础类型需精确匹配，引用或数组返回值可为原返回类型的可赋值子类型，也可用 `Any` / `Object` 作为泛型引用返回类型；原调用为 `void` 时 handler 必须返回 `Unit` / `void`。省略 `At.target` 时，框架会按 handler 栈参数、`Operation` 位置与返回类型筛选兼容的普通调用、`invokedynamic` 调用或构造器调用，不兼容候选不计入 `ordinal` 或命中数。可用 `ordinal` 只选择第 N 个匹配调用点，也可用 `slice.from` / `slice.to` 把候选操作点限制在一段 `INVOKE` 边界之间。边界调用本身不参与候选匹配，`ordinal` 会在切片内重新计数。

省略 `method` 时，handler 名称必须与目标方法名一致，并且只能匹配到一个包含兼容操作点的同名目标方法；存在多个兼容重载时需要显式写出目标方法签名。

当 `INVOKE` 的 `At.target` 指向 `<init>` 构造器，或 `NEW` 的 `At.target` 指向构造类型时，`@WrapOperation` 会替换常见 `NEW/DUP/args/INVOKESPECIAL` 构造表达式。`NEW` 目标可写 internal name 或 binary name，例如 `java/lang/StringBuilder` 或 `java.lang.StringBuilder`；若需要限定构造器描述符，继续使用 `INVOKE + <init>` 形式。handler 先接收构造器参数，不接收未初始化 receiver；下一参数必须是 `Operation<T>`，其中 `T` 为构造器 owner 类型；handler 返回类型必须兼容 owner 类型。`operation.call(...)` 只传构造器参数，并通过原构造器创建对象。

`FIELD` 模式匹配 `GETFIELD` / `GETSTATIC` 字段读取。`GETFIELD` handler 先接收字段 owner，再接收 `Operation<R>`；`GETSTATIC` handler 直接接收 `Operation<R>`。后续参数可按目标方法声明顺序接收目标方法参数前缀，handler 返回类型必须兼容字段类型。省略 `At.target` 时，框架会按 handler 字段 owner 参数、`Operation` 位置与返回类型筛选兼容的字段读取，不兼容字段读取不计入 `ordinal` 或命中数。可用 `slice.from` / `slice.to` 把候选字段读取限制在一段 `INVOKE` 边界之间，边界调用本身不参与候选匹配，`ordinal` 会在切片内重新计数。

`FIELD_ASSIGN` 模式匹配 `PUTFIELD` / `PUTSTATIC` 字段写入。`PUTFIELD` handler 先接收字段 owner，再接收待写入值与 `Operation<Unit>`；`PUTSTATIC` handler 接收待写入值与 `Operation<Unit>`。handler 必须返回 `Unit` / `void`，后续参数可按目标方法声明顺序接收目标方法参数前缀。省略 `At.target` 时，框架会按 handler 字段 owner 参数、待写入值、`Operation` 位置与返回类型筛选兼容的字段写入，不兼容字段写入不计入 `ordinal` 或命中数。可用 `slice.from` / `slice.to` 把候选字段写入限制在一段 `INVOKE` 边界之间，边界调用本身不参与候选匹配，`ordinal` 会在切片内重新计数。

数组元素读取使用 `FIELD + at.args = ["array=get"]`，`At.target` 指向产生数组引用的数组字段。
handler 先接收数组引用、`Int` 索引与 `Operation<R>`，返回类型必须兼容数组元素类型。
数组元素写入使用 `FIELD_ASSIGN + at.args = ["array=set"]`，handler 先接收数组引用、`Int` 索引、
待写入元素值与 `Operation<Unit>`，并必须返回 `Unit` / `void`。数组长度读取使用 `FIELD + at.args = ["array=length"]`，
handler 先接收数组引用与 `Operation<Int>`，返回类型必须为 `Int`。数组访问当前匹配简单数组字段访问形态，
即数组引用来自最近的目标 `GETFIELD` / `GETSTATIC`。数组读取、数组长度与数组写入也可用 `slice.from` / `slice.to`
把候选数组访问限制在一段 `INVOKE` 边界之间。

`Operation.call` 的参数形态与原操作栈参数一致：实例调用传入 receiver 与原方法参数，静态调用和 `invokedynamic` 调用只传入原调用参数；
构造器调用只传入构造器参数；
`GETFIELD` 读取传入字段 owner，`GETSTATIC` 读取不传参数；`PUTFIELD` 写入传入字段 owner 与新字段值，
`PUTSTATIC` 写入只传入新字段值；数组读取传入数组引用与 `Int` 索引，数组写入传入数组引用、`Int`
索引与新元素值，数组长度读取只传入数组引用；类型转换与类型判断传入待处理值。当前实现支持 `INVOKE` 普通方法调用、`invokedynamic` 调用和构造器调用、`NEW` 构造表达式、`FIELD` 字段读取、`FIELD_ASSIGN` 字段写入、简单数组元素读写、长度读取、`CAST` 类型转换与 `INSTANCEOF` 类型判断。`invokedynamic` 目标按 bootstrap owner、动态调用名或 bootstrap 名，以及动态调用点描述符匹配，例如 `java/lang/invoke/StringConcatFactory.makeConcatWithConstants(Ljava/lang/String;I)Ljava/lang/String;`。

`CAST` 模式匹配 `CHECKCAST` 类型转换。handler 先接收原待转换对象，再接收 `Operation<T>`，其中 `T` 为目标类型；后续参数可按目标方法声明顺序接收目标方法参数前缀，handler 返回类型必须兼容目标类型。`operation.call(value)` 会执行原始 `CHECKCAST` 语义，传入不兼容对象时仍会抛出 `ClassCastException`。省略 `at.target` 时会遍历所有 `CHECKCAST`，并按 handler 返回类型筛选兼容目标；不兼容的转换目标不会计入 `ordinal` 或命中数。可用 `slice.from` / `slice.to` 把候选类型转换限制在一段 `INVOKE` 边界之间。

`INSTANCEOF` 模式匹配类型判断。handler 先接收原被判断对象，再接收 `Operation<Boolean>`；后续参数可按目标方法声明顺序接收目标方法参数前缀，handler 必须返回 `Boolean`。`operation.call(value)` 会执行原始 `INSTANCEOF` 语义，`null` 返回 `false`。省略 `at.target` 时会遍历所有 `INSTANCEOF` 判断，并按实际包裹的类型判断计入 `ordinal` 和命中数。可用 `slice.from` / `slice.to` 把候选类型判断限制在一段 `INVOKE` 边界之间。

`@WrapOperation` 会统计实际替换为 handler 调用的操作点数量。未设置 `ordinal` 时，方法调用、构造器调用、NEW 构造表达式、字段读取、字段写入、数组元素读写、数组长度读取、类型转换与类型判断均按实际包裹数量计数；省略 `INVOKE` 调用目标、`FIELD` 字段目标、`FIELD_ASSIGN` 字段目标或 `CAST` 类型目标时，不兼容候选不计入数量；设置 `ordinal` 时最多命中对应序号的 1 个操作点。显式设置 `require` / `allow` / 非默认 `expect` 时按实际操作包裹数量校验契约，违反 `require` 或 `allow` 会在转换阶段失败，`expect` 不一致只输出警告。

**示例：** 见 [GUIDE.md](GUIDE.md#常见场景)

### @WrapMethod

把整个目标方法体替换为 handler 调用。适合在保留原方法可调用能力的同时，统一改写入参、跳过原方法、重复调用原方法或包裹返回值。

**参数：**

- `method: String = ""` - 目标方法签名
- `require: Int = 0` - 最小命中数；大于 0 时实际整方法包裹数必须不少于该值
- `expect: Int = 1` - 期望命中数；设置为非默认值时，不一致会输出警告但不阻断转换
- `allow: Int = -1` - 允许的最大命中数；`-1` 表示不限制
- `remap: Boolean = false` - 是否重映射

handler 参数必须先按目标方法声明顺序接收原方法参数；当原方法参数是对象/数组类型时，对应 handler
参数可声明为该参数类型的父类、接口、`Any` 或 `Object`，基础类型仍需精确匹配。下一参数必须是 `Operation<R>`，其中 `R`
为目标方法返回类型。handler 返回类型必须兼容目标方法返回类型；目标方法为 `void` 时 handler
必须返回 `Unit` / `void`。实例目标方法不会把 `this` 作为 handler 参数传入，框架会把当前 receiver
绑定到 `Operation` 内部；因此静态目标方法和实例目标方法调用 `operation.call(...)` 时都只传目标方法参数。
`method` 为空时，框架会先按 handler 名称与精确描述符推断目标方法；若 handler 使用父类型参数或 `Any` / `Object`
泛型引用返回导致精确描述符不一致，会继续按参数与返回类型兼容规则匹配唯一同名目标方法。存在多个兼容重载时需要显式填写 `method`。

转换时，框架会把原方法体迁移到私有 synthetic 方法，再用原方法名与原描述符生成 wrapper。`@WrapMethod`
不支持构造器 `<init>`、类初始化器 `<clinit>`、abstract 方法或 native 方法。

`@WrapMethod` 会按被包裹的目标方法数量计数；单个精确方法签名通常命中 1 次。显式设置 `require` /
`allow` / 非默认 `expect` 时按实际整方法包裹数量校验契约，违反 `require` 或 `allow` 会在转换阶段失败，
`expect` 不一致只输出警告。

**示例：** 见 [GUIDE.md](GUIDE.md#常见场景)

### @WrapWithCondition

在目标方法内匹配 `void` 方法调用、返回 `void` 的 `invokedynamic` 调用、字段写入或简单数组元素写入，并用 boolean handler 决定是否继续执行原指令。适合按条件跳过日志、通知、字段写入、数组写入、广播等副作用。

**参数：**

- `method: String = ""` - 目标方法签名；为空时按 handler 名称、条件包裹操作点和签名兼容规则推断唯一同名目标方法
- `at: At = At(value = InjectionPoint.INVOKE)` - 调用点定位；当前支持 `INVOKE` 与 `FIELD_ASSIGN`
- `ordinal: Int = -1` - 匹配点序号；`-1` 表示包裹全部匹配点，`0` 及以上表示只包裹第 N 个匹配点
- `slice: Slice = Slice()` - 切片范围；当前 `INVOKE` 与 `FIELD_ASSIGN` 模式支持用 `INVOKE` 边界缩小查找范围
- `require: Int = 0` - 最小命中数；大于 0 时实际条件包裹数必须不少于该值
- `expect: Int = 1` - 期望命中数；设置为非默认值时，不一致会输出警告但不阻断转换
- `allow: Int = -1` - 允许的最大命中数；`-1` 表示不限制
- `remap: Boolean = false` - 是否重映射

`@WrapWithCondition` handler 必须返回 `Boolean`。返回 `true` 时恢复原 receiver/参数、字段写入值或数组写入栈参数并继续执行原指令；返回 `false` 时跳过该指令。
handler 的引用类型参数可声明为精确类型、可赋值父类型或 `Any` / `Object`；原始类型参数仍必须按 JVM 栈类型匹配。

`INVOKE` 模式只支持返回 `void` 的目标调用。实例调用 handler 先接收 receiver，再接收原调用参数；静态调用 handler 只接收原调用参数；`invokedynamic` 调用没有 receiver，handler 先接收动态调用点描述符中的参数。动态调用目标按 bootstrap owner、动态调用名或 bootstrap 方法名，以及动态调用点描述符匹配。省略 `At.target` 时，框架会按 handler 参数和 boolean 返回类型筛选兼容的 `void` 普通调用或 `invokedynamic` 调用；构造器、非 `void` 调用和 handler 不兼容的调用不计入 `ordinal` 或命中数。后续参数可按目标方法声明顺序接收目标方法参数前缀。显式目标遇到非 `void` 调用会在转换阶段失败。构造器 `<init>` 虽然返回 `void`，但会消费未初始化对象，不能用 `@WrapWithCondition` 条件跳过；显式命中构造器目标会在转换阶段失败，如需控制构造过程应使用 `@Redirect` 或 `@WrapOperation`。
可用 `ordinal` 只选择第 N 个匹配调用点，也可用 `slice.from` / `slice.to` 把候选调用限制在一段 `INVOKE` 边界之间。边界调用本身不参与候选匹配，`ordinal` 会在切片内重新计数。

省略 `method` 时，handler 名称必须与目标方法名一致，并且只能匹配到一个包含兼容条件包裹操作点的同名目标方法；存在多个兼容重载时需要显式写出目标方法签名。

`FIELD_ASSIGN` 模式默认匹配 `PUTFIELD` / `PUTSTATIC`。字段目标格式支持 `owner.field:desc`、`field:desc` 与 `field`。实例字段写入 handler 先接收字段 owner，再接收待写入值；静态字段写入 handler 接收待写入值；后续参数同样可按目标方法声明顺序接收目标方法参数前缀。省略 `At.target` 时，框架会按 handler 字段 owner 参数、待写入值和 boolean 返回类型筛选兼容的字段写入，不兼容字段写入不计入 `ordinal` 或命中数。

字段写入和数组元素写入同样可用 `slice.from` / `slice.to` 把候选写入限制在一段 `INVOKE` 边界之间，边界调用本身不参与候选匹配，`ordinal` 会在切片内重新计数。

数组元素写入使用 `at.args = ["array=set"]`，`At.target` 指向产生数组引用的数组字段。handler 先接收数组引用、
`Int` 索引与待写入元素值，后续可继续接收目标方法参数前缀。返回 `true` 时执行原 `xASTORE`，返回 `false`
时跳过该数组写入。当前实现匹配简单数组字段访问形态，即数组引用来自最近的目标 `GETFIELD` / `GETSTATIC`。

`@WrapWithCondition` 会统计实际插入条件判断的操作点数量。未设置 `ordinal` 时，`void` 方法调用、返回 `void` 的 `invokedynamic` 调用、字段写入与数组元素写入均按实际条件包裹数量计数；省略 `INVOKE` 调用目标或 `FIELD_ASSIGN` 字段目标时，不兼容候选不计入数量；设置 `ordinal` 时最多命中对应序号的 1 个操作点。显式设置 `require` / `allow` / 非默认 `expect` 时按实际条件包裹数量校验契约，违反 `require` 或 `allow` 会在转换阶段失败，`expect` 不一致只输出警告。

**示例：** 见 [GUIDE.md](GUIDE.md#常见场景)

### @ModifyExpressionValue

修改目标方法内匹配表达式产生的值。当前实现支持修改普通方法调用或 `invokedynamic` 调用完成后的非 `void` 返回值、`GETFIELD` / `GETSTATIC` 字段读取值、数组元素读取值、数组长度值、`NEW` 对象构造完成后的实例、`CHECKCAST` 类型转换完成后的对象值、`INSTANCEOF` 判断后的 boolean 结果，以及 `ATHROW` 前即将抛出的异常对象，适合保留原操作逻辑、只调整表达式结果的场景。

**参数：**

- `method: String = ""` - 目标方法签名；为空时按 handler 名称、表达式定位和签名兼容规则推断唯一同名目标方法
- `at: At = At(value = InjectionPoint.INVOKE)` - 表达式定位；当前支持 `INVOKE`、`INVOKE_ASSIGN`、`FIELD`、`NEW`、`CAST`、`INSTANCEOF`、`CONSTANT` 与 `THROW`
- `ordinal: Int = -1` - 表达式匹配序号；`-1` 表示修改全部匹配表达式，`0` 及以上表示只修改第 N 个匹配表达式
- `slice: Slice = Slice()` - 切片范围；当前 `INVOKE` / `INVOKE_ASSIGN` 调用返回、`FIELD` 字段读取、数组元素读取、数组长度、`NEW`、`CAST`、`INSTANCEOF`、`CONSTANT` 与 `THROW` 表达式支持用 `INVOKE` 边界缩小查找范围
- `require: Int = 0` - 要求的最小命中数；`0` 表示使用默认至少 1 次的显式契约语义
- `expect: Int = 1` - 期望命中数；非默认值不一致时输出警告
- `allow: Int = -1` - 允许的最大命中数；`-1` 表示不限制
- `remap: Boolean = false` - 是否重映射

`@ModifyExpressionValue` handler 的第一个参数必须接收匹配表达式的原始值；对象或数组表达式可用原值类型的父类、接口、`Any` 或 `Object`
接收。primitive 表达式的返回类型必须与表达式类型一致；引用类型表达式可返回表达式类型的子类型，也可用 `Any` / `Object` 作为泛型引用返回类型，框架会在 handler 调用后转换回表达式类型；`THROW` 模式可返回 `Throwable` 或具体异常子类。handler 后续参数可按目标方法声明顺序接收目标方法参数前缀。
该注解不会替换原调用、字段读取、数组读取、构造器调用、类型转换、类型判断、常量加载或抛异常指令，也不会接收原调用参数；`INVOKE` / `INVOKE_ASSIGN` 可省略 `at.target`，框架会按 handler 首参与返回类型筛选兼容的非 `void` 调用返回；`invokedynamic` 调用可通过 bootstrap owner、动态调用名或 bootstrap 名，以及动态调用点描述符匹配，例如 `java/lang/invoke/StringConcatFactory.makeConcatWithConstants(Ljava/lang/String;)Ljava/lang/String;`。字段读取模式不会把 `GETFIELD` 的 receiver 传给 handler；省略字段目标时，框架会按 handler 首参与返回类型筛选兼容的 `GETFIELD` / `GETSTATIC` 字段读取值。数组元素读取使用 `at.args = ["array=get"]`，handler 接收已经读取出的元素值，不接收数组引用或索引。数组长度使用 `at.args = ["array=length"]`，handler 接收 `Int` 长度值，不接收数组引用。`NEW` 模式会在匹配构造器调用完成后接收已初始化对象；省略 `at.target` 时按 handler 首参与返回类型筛选兼容 `NEW`。`CAST` 模式会在匹配 `CHECKCAST` 完成后接收转换后的对象；省略 `at.target` 时按 handler 首参与返回类型筛选兼容 `CHECKCAST`。`CONSTANT` 模式会在常量加载后接收常量表达式值，`At.target` 为常量文本；省略 `at.target` 时按 handler 首参与返回类型筛选兼容常量。不兼容的调用返回、字段读取、`NEW`、`CHECKCAST` 或常量候选不计入 `ordinal` 或命中数。`INSTANCEOF` 模式会在匹配类型判断后接收 `Boolean` 结果，`At.target` 为类型 internal name 或 binary name。`THROW` 模式会在 `ATHROW` 前接收即将抛出的 `Throwable`，也能继续追加目标方法参数；指定 `At.target` 时只匹配 `ATHROW` 前一条真实指令为同类型 `<init>` 的直接构造异常，不追踪局部变量或方法返回值来源。省略 `method` 时，handler 名称必须与目标方法名一致，并且只能匹配到一个包含兼容表达式候选的同名目标方法；存在多个兼容重载时需要显式写出目标方法签名。`INVOKE` / `INVOKE_ASSIGN` 调用返回、字段读取、数组元素读取、数组长度、`NEW`、`CAST`、`INSTANCEOF`、`CONSTANT` 与 `THROW` 表达式可用 `slice.from` / `slice.to` 限制在一段 `INVOKE` 边界之间，边界调用本身不参与候选匹配，`THROW` 只会改写边界内的 `ATHROW` 候选，`ordinal` 会在切片内重新计数。若需要替换调用本身应使用 `@Redirect`，若需要改写调用参数应使用 `@ModifyArg` 或 `@ModifyArgs`。

`@ModifyExpressionValue` 会按实际改写的表达式值数量计数。未设置 `ordinal` 时命中全部匹配表达式；
设置 `ordinal` 时最多命中对应序号的 1 个表达式。显式设置 `require` / `allow` / 非默认 `expect` 时按实际表达式值修改数量校验契约，
违反 `require` 或 `allow` 会在转换阶段失败，`expect` 不一致只输出警告。

**示例：** 见 [GUIDE.md](GUIDE.md#常见场景)

### @ModifyVariable

修改目标方法中的参数或局部变量。

当前实现支持 `HEAD`、`LOAD` 与 `STORE` 三种位置：

- `at.value = InjectionPoint.HEAD`：在方法入口改写已有参数槽位。可以用 JVM 局部变量槽位 `index` 精确定位；当 `index < 0` 时，会按 handler 参数类型筛选目标方法入口参数，并用 `ordinal` 选择第 N 个同类型参数。
- `at.value = InjectionPoint.LOAD`：在匹配的 `xLOAD` 指令前改写即将被读取的局部变量槽位。`index >= 0` 时按局部变量槽位选择；未指定 `index` 时按 handler 参数类型筛选读取点，并用 `ordinal` 选择第 N 个同类型读取点。
- `at.value = InjectionPoint.STORE`：在匹配的 `xSTORE` 指令后改写刚写入局部变量槽位的值。`index >= 0` 时按局部变量槽位选择；未指定 `index` 时按 handler 参数类型筛选写入点，并用 `ordinal` 选择第 N 个同类型写入点。

实例方法中 `this` 占用槽位 0，第一个参数从槽位 1 开始；静态方法第一个参数从槽位 0 开始。handler 第一个参数接收原变量值并返回同类型的新值，后续参数可按目标方法声明顺序接收目标方法参数前缀；未指定 `index` 时的类型筛选只使用 handler 第一个参数。

**参数：**

- `method: String = ""` - 目标方法签名；为空时按 handler 名称、变量类型、返回类型和追加目标参数兼容规则推断唯一同名目标方法
- `at: At = At(value = InjectionPoint.HEAD)` - 修改位置；当前支持 `HEAD`、`LOAD` 与 `STORE`
- `index: Int = -1` - JVM 局部变量槽位索引；大于等于 0 时优先使用
- `ordinal: Int = -1` - 未指定 `index` 时，同类型入口参数、读取点或写入点的序号；`HEAD` 模式同类型入口参数唯一时可保持默认值
- `slice: Slice = Slice()` - 切片范围；当前 `LOAD` / `STORE` 模式支持用 `INVOKE` 边界缩小查找范围
- `require: Int = 0` - 要求的最小命中数；`0` 表示使用默认至少 1 次的显式契约语义
- `expect: Int = 1` - 期望命中数；非默认值不一致时输出警告
- `allow: Int = -1` - 允许的最大命中数；`-1` 表示不限制
- `remap: Boolean = false` - 是否重映射

`@ModifyVariable` 的 `LOAD` / `STORE` 模式可用 `slice.from` / `slice.to` 把候选 `xLOAD` 读取点或 `xSTORE` 写入点限制在一段 `INVOKE` 边界之间；边界调用本身不参与候选匹配，`ordinal` 会在切片内重新计数。`HEAD` 当前不使用 `slice`。

`@ModifyVariable` handler 第一个参数接收原变量值并返回同类型的新值；显式指定 `index` 时，对象/数组变量或追加的目标方法参数可用原值类型的父类、接口、`Any` 或 `Object` 接收，框架会尽量通过目标方法参数、局部变量表与相邻字节码用途保留真实槽位类型再写回，避免把局部变量栈图退化为 `Object`。返回值对引用类型可为原变量类型的可赋值子类型，也可用 `Any` / `Object` 作为泛型引用返回类型，框架会在 handler 调用后转换回原变量类型，基础类型仍需精确匹配。未指定 `index` 时，框架仍按 handler 第一个参数类型筛选入口参数、读取点或写入点，因此需要用实际变量类型参与匹配。`HEAD` 模式下若同类型入口参数唯一，可省略 `ordinal`；存在多个同类型入口参数时仍需用 `ordinal` 明确选择。省略 `method` 时，handler 名称必须与目标方法名一致，并且只能匹配到一个签名兼容的同名目标方法；存在多个兼容重载时需要显式写出目标方法签名。

`@ModifyVariable` 会统计实际写入变量修改逻辑的数量。`HEAD` 模式最多命中 1 次；`LOAD` / `STORE`
模式按匹配读取点或写入点数量计数。显式设置 `require` / `allow` / 非默认 `expect` 时按实际变量修改数量校验契约，
违反 `require` 或 `allow` 会在转换阶段失败，`expect` 不一致只输出警告。

**示例：** 见 [GUIDE.md](GUIDE.md#常见场景)

### @ModifyReturnValue

修改目标方法的返回值。默认修改全部非 void 返回点，可用 `ordinal` 只修改第 N 个返回点。

**参数：**

- `method: String = ""` - 目标方法签名；为空时按 handler 名称与签名兼容性推断唯一目标方法
- `at: At = At()` - 预留注入位置参数；当前实现未使用，返回值修改位置固定为非 `void` 返回点
- `ordinal: Int = -1` - 返回点序号；`-1` 表示修改全部非 void 返回点，`0` 及以上表示只修改第 N 个返回点
- `require: Int = 0` - 要求的最小命中数；`0` 表示使用默认至少 1 次的显式契约语义
- `expect: Int = 1` - 期望命中数；非默认值不一致时输出警告
- `allow: Int = -1` - 允许的最大命中数；`-1` 表示不限制
- `remap: Boolean = false` - 是否重映射

`@ModifyReturnValue` handler 返回类型对 primitive 必须与目标方法返回类型一致；对象/数组返回值可以是目标类型的子类型，也可以用 `Any` / `Object` 作为泛型引用返回类型，框架会在 handler 调用后转换回目标返回类型。参数可选：handler 可以不声明参数并直接返回新值；第一个参数可接收原始返回值，后续参数可按目标方法声明顺序接收目标方法参数前缀；当原返回值或追加的目标方法参数是对象/数组类型时，对应 handler 参数可声明为原值类型的父类、接口、`Any` 或 `Object`。`method` 为空时会先按 handler 名称筛选目标方法，再按返回类型、原返回值参数和目标方法参数前缀的兼容规则匹配唯一同名目标；如果多个重载都兼容，需要显式写出 `method` 签名。

`@ModifyReturnValue` 会按实际改写的非 void 返回点数量计数。未设置 `ordinal` 时命中全部非 void 返回点；
设置 `ordinal` 时最多命中对应序号的 1 个返回点。显式设置 `require` / `allow` / 非默认 `expect` 时按实际返回值修改数量校验契约，
违反 `require` 或 `allow` 会在转换阶段失败，`expect` 不一致只输出警告。

**示例：** 见 [GUIDE.md](GUIDE.md#常见场景)

### @ModifyConstant

修改方法中的常量值。

**参数：**

- `method: String = ""` - 目标方法签名；为空时按 handler 名称、常量过滤、常量类型、返回类型和追加目标参数兼容规则推断唯一同名目标方法
- `constant: String = ""` - 要修改的常量值；`"true"` / `"false"` 会按 boolean 常量匹配；类字面量可写 internal name 或 binary name，例如 `java/lang/String` 或 `java.lang.String`；方法类型常量可写 JVM 方法描述符，例如 `(I)Ljava/lang/String;`；方法句柄常量可写 `owner.name(desc)`，例如 `java/lang/String.valueOf(I)Ljava/lang/String;`；动态常量可写常量名或 `name:descriptor`，例如 `dynamicText:Ljava/lang/String;`
- `ordinal: Int = -1` - 匹配常量序号；`-1` 表示修改全部匹配常量，`0` 及以上表示只修改第 N 个匹配常量
- `slice: Slice = Slice()` - 切片范围；当前支持用 `INVOKE` 边界缩小常量查找范围
- `require: Int = 0` - 最小命中数；大于 0 时实际替换常量数必须不少于该值
- `expect: Int = 1` - 期望命中数；设置为非默认值时，不一致会输出警告但不阻断转换
- `allow: Int = -1` - 允许的最大命中数；`-1` 表示不限制
- `remap: Boolean = false` - 是否重映射

支持 `LDC` 字符串、数字、类字面量（handler 使用 `Class<*>` / `java.lang.Class`）、方法类型字面量（handler 使用 `java.lang.invoke.MethodType`）、方法句柄字面量（handler 使用 `java.lang.invoke.MethodHandle`）与动态常量、`ACONST_NULL`、`ICONST_*`、`LCONST_*`、`FCONST_*`、`DCONST_*`、`BIPUSH` 与 `SIPUSH`
形式的常量加载。显式 `constant = "true"` 或 `"false"` 时，会把 `ICONST_1` / `ICONST_0` 按 boolean
常量匹配；数字 `"1"` / `"0"` 仍按 int 常量匹配。类字面量由 Java `.class` 或 Kotlin `SomeType::class.java` 生成的 `LDC Type` 表示，按类名匹配；方法类型常量由 ASM `Type.getMethodType(...)` 这类方法描述符常量表示，按方法描述符匹配；方法句柄常量由 ASM `Handle` 表示，按 `owner.name(desc)` 匹配；动态常量由 ASM `ConstantDynamic` 表示，按 `name` 或 `name:descriptor` 匹配，并使用动态常量描述符推导 handler 类型。ASM 方法的第一个参数必须接收原始常量；引用类型参数可声明为原值类型的父类、接口、`Any` 或 `Object`，基础类型仍需精确匹配。匹配 `ACONST_NULL` 时，handler 第一个参数可声明为任意引用类型接收原始 `null`，框架会按该首参类型确定替换后的引用栈类型。返回值对基础类型仍需精确匹配，对引用或数组常量则可返回原常量类型的可赋值子类型，也可用 `Any` / `Object` 作为泛型引用返回类型。后续参数可以按目标方法声明顺序接收目标方法参数前缀。未指定 `constant` 时会按 handler 返回类型筛选常量，再用 `ordinal` 选择第 N 个候选；已指定 `constant` 时同样会先按文本匹配，再按 handler 返回类型排除不兼容的 JVM 常量类型，例如 `constant = "1"` 配合 `Int` handler 不会把 `long 1` 计入候选。省略 `method` 时，handler 名称必须与目标方法名一致，并且只能匹配到一个包含兼容常量候选的同名目标方法；存在多个兼容重载时需要显式写出目标方法签名。可用 `slice.from` / `slice.to` 把候选常量限制在一段 `INVOKE` 边界之间；边界调用本身不参与候选匹配，`ordinal` 会在切片内重新计数。指定边界未命中时，切片按空范围处理。`@ModifyConstant` 会统计实际替换常量数量；显式设置 `require` / `allow` / 非默认 `expect` 时按替换数量校验契约，违反 `require` 或 `allow` 会在转换阶段失败，`expect` 不一致只输出警告。

**示例：** 见 [GUIDE.md](GUIDE.md#常见场景)

### @Redirect

重定向目标方法中的方法调用、`invokedynamic` 调用、构造器调用、NEW 构造表达式、字段读取、字段写入、简单数组元素访问、数组长度读取、类型转换或 `INSTANCEOF` 类型判断。

方法调用重定向会替换匹配的调用指令。`invokedynamic` 重定向同样使用 `at.value = InjectionPoint.INVOKE`，
按 bootstrap owner、动态调用名或 bootstrap 方法名，以及动态调用点描述符匹配；handler 不接收 receiver，
只接收动态调用点描述符中的参数。省略 `INVOKE` 调用目标时，框架会按 handler 栈参数、返回类型和可选目标方法参数前缀筛选兼容的普通方法调用、构造器调用或 `invokedynamic` 调用；不兼容候选不计入 `ordinal` 或命中数。构造器重定向可使用 `INVOKE + <init>` 精确匹配构造器，
也可使用 `NEW` 与类型目标直接匹配构造表达式；两种形式都会替换常见 `NEW/DUP/args/INVOKESPECIAL <init>` 构造表达式。
字段读取重定向需要将 `at.value` 设置为 `InjectionPoint.FIELD`，
会替换匹配的 `GETFIELD` / `GETSTATIC` 指令。字段写入重定向需要将 `at.value` 设置为 `InjectionPoint.FIELD_ASSIGN`，
会替换匹配的 `PUTFIELD` / `PUTSTATIC` 指令。数组元素访问与数组长度重定向使用 `at.value = InjectionPoint.FIELD` 匹配产生数组引用的字段，
并通过 `at.args = ["array=get"]`、`at.args = ["array=set"]` 或 `at.args = ["array=length"]` 区分数组读取、写入与长度读取。
类型转换重定向使用 `at.value = InjectionPoint.CAST` 与类型 `at.target` 匹配 `CHECKCAST` 指令，handler 接收原待转换对象并返回目标类型兼容对象。省略 `at.target` 时会遍历所有 `CHECKCAST`，并按 handler 返回类型筛选兼容目标；不兼容的转换目标不会计入 `ordinal` 或命中数。
类型判断重定向使用 `at.value = InjectionPoint.INSTANCEOF` 与类型 `at.target` 匹配 `INSTANCEOF` 指令，handler 接收原被判断对象并返回新的 `Boolean` 结果。省略 `at.target` 时会遍历所有 `INSTANCEOF` 判断，并按实际重定向的类型判断计入 `ordinal` 和命中数。

方法调用、`invokedynamic` 调用、构造器调用、NEW 构造表达式、字段读取、字段写入、数组元素访问、数组长度、类型转换与类型判断重定向的 handler 可以是静态方法、`@JvmStatic` 方法，或 Kotlin `object` 中的实例方法。

方法调用重定向的 handler 参数形态：

- 实例方法调用：原调用 receiver、原调用参数，并可继续追加目标方法参数前缀
- 静态方法调用：原调用参数，并可继续追加目标方法参数前缀
- `invokedynamic` 调用：动态调用点描述符中的参数，并可继续追加目标方法参数前缀
- 构造器调用或 `NEW` 构造表达式：原构造器参数，并可继续追加目标方法参数前缀；handler 必须返回构造器 owner 类型兼容对象，对象或数组返回值可为原 owner 类型的可赋值子类型，也可用 `Any` / `Object` 作为泛型引用返回类型

handler 参数接收引用或数组栈值时，可声明为原值类型的父类、接口、`Any` 或 `Object`；基础类型参数仍需精确匹配。

字段访问重定向的 handler 参数形态：

- 实例字段读取：字段所属实例，并可继续追加目标方法参数前缀，返回字段值
- 静态字段读取：可接收目标方法参数前缀，返回字段值
- 实例字段写入：字段所属实例、原写入值，并可继续追加目标方法参数前缀，返回 `void`
- 静态字段写入：原写入值，并可继续追加目标方法参数前缀，返回 `void`
- 数组元素读取：数组引用、`Int` 索引，并可继续追加目标方法参数前缀，返回元素值
- 数组元素写入：数组引用、`Int` 索引、原元素值，并可继续追加目标方法参数前缀，返回 `void`
- 数组长度读取：数组引用，并可继续追加目标方法参数前缀，返回 `Int`
- 类型转换：原待转换对象，并可继续追加目标方法参数前缀，返回目标类型兼容对象
- 类型判断：原被判断对象，并可继续追加目标方法参数前缀，返回 `Boolean`

**参数：**

- `method: String = ""` - 目标方法签名；为空时按 handler 名称、重定向点和签名兼容规则推断唯一同名目标方法
- `target: String = ""` - 要重定向的方法调用、动态调用、构造器调用、字段访问、构造类型或类型签名
- `at: At = At()` - 注入位置；`at.value = InjectionPoint.INVOKE` 时匹配普通方法调用、构造器调用或 `invokedynamic` 调用，省略 `at.target` 时按 handler 签名筛选兼容调用点；`FIELD` 时按字段读取语义匹配，配合 `at.args = ["array=get"]` / `["array=set"]` / `["array=length"]` 可匹配数组元素访问或数组长度读取，`FIELD_ASSIGN` 时按字段写入语义匹配，`NEW` 时按构造类型匹配，`CAST` 时按类型转换语义匹配，`INSTANCEOF` 时按类型判断语义匹配
- `ordinal: Int = -1` - 匹配点序号；`-1` 表示重定向全部匹配点，当前在方法调用、`invokedynamic` 调用、构造器调用、NEW 构造表达式、字段读取、字段写入、数组元素访问、数组长度、类型转换与类型判断重定向中生效
- `slice: Slice = Slice()` - 切片范围；当前方法调用、`invokedynamic` 调用、构造器调用、NEW 构造表达式、字段读取、字段写入、数组元素访问、数组长度、类型转换与类型判断重定向支持用 `INVOKE` 边界缩小查找范围
- `require: Int = 0` - 最小命中数；大于 0 时实际重定向数必须不少于该值
- `expect: Int = 1` - 期望命中数；设置为非默认值时，不一致会输出警告但不阻断转换
- `allow: Int = -1` - 允许的最大命中数；`-1` 表示不限制
- `remap: Boolean = false` - 是否重映射

方法调用、`invokedynamic` 调用、构造器调用、NEW 构造表达式、字段读取、字段写入、数组元素访问、数组长度、类型转换与类型判断重定向都可用 `ordinal` 只替换第 N 个匹配点。
重定向返回值的兼容规则与操作包裹一致：基础类型需精确匹配，引用或数组返回值可为原返回类型的可赋值子类型，也可用 `Any` / `Object` 作为泛型引用返回类型。
省略 `method` 时，handler 名称必须与目标方法名一致，并且只能匹配到一个包含兼容重定向点的同名目标方法；存在多个兼容重载时需要显式写出目标方法签名。
这些模式均支持 `slice.from` / `slice.to` 为 `InjectionPoint.INVOKE` 的切片边界；框架只在起始边界之后、
结束边界之前查找目标调用、动态调用、NEW 构造表达式、字段访问、数组访问、数组长度、类型转换或类型判断指令，边界调用本身不参与候选匹配，`ordinal` 会在切片内重新计数。
指定的边界未命中时，切片按空范围处理。

`@Redirect` 会统计实际替换的调用点、动态调用点、构造器调用点、NEW 构造表达式、字段访问点、数组元素访问点、数组长度读取点、类型转换点或类型判断点数量；显式设置
`require` / `allow` / 非默认 `expect` 时按实际重定向数量校验契约，违反 `require` 或 `allow` 会在转换阶段失败，
`expect` 不一致只输出警告。

**示例：** 见 [GUIDE.md](GUIDE.md#常见场景)

### @RedirectAllMethods

将目标类所有普通方法中的指定调用统一重定向到 `@Redirect` 标注的方法。

该注解不会用 `method` 字段筛选目标方法；框架会把每个 `@Redirect` 处理器应用到目标类的全部普通方法（跳过构造器和类初始化方法）。`require` / `allow` / `expect` 按整个目标类的总命中数校验，`ordinal` 与 `slice` 仍按单个目标方法生效。

**参数：**

- `remap: Boolean = false` - 是否重映射

**示例：** 见 [GUIDE.md](GUIDE.md#常见场景)

### @Shadow

在 Mixin 类中引用目标类的字段或方法。转换阶段会校验目标成员存在，字段还会校验类型一致；
在 `@Overwrite` 等复制方法体的场景中，对 Shadow 字段/方法的访问会改写为目标类对应成员。
Shadow 字段/方法会先匹配目标类自身成员；若不存在，会沿可加载父类查找可继承成员。
字段还会查找可加载接口中的 `public static` 字段；方法还会查找可加载接口中的默认方法。
`@Mutable` 只会移除目标类自身字段的 `final` 标志，不会改写继承字段的修饰符。

目标成员名解析规则：

- `@Shadow()`：使用 ASM 类中的字段名/方法名
- `@Shadow("shadow_name")`：去掉 `shadow_` 前缀后匹配目标成员 `name`
- `@Shadow("actualName")`：直接匹配显式目标成员 `actualName`

**参数：**

- `method: String = ""` - 目标成员名提示；为空时使用声明名，非空时可直接指定目标名或使用 `shadow_` 前缀
- `remap: Boolean = false` - 是否重映射

**示例：** 见 [GUIDE.md](GUIDE.md#常见场景)

### @Accessor

为字段生成访问器方法。

无参数且返回字段类型的方法会生成 getter；一个参数且返回 `void` 的方法会生成 setter。`value` 为空时，
框架会从 `getXxx`、`setXxx` 或 `isXxx` 方法名推断字段名。目标字段不存在、
getter/setter 类型不匹配、访问器静态性与字段静态性不一致，或生成方法与目标类已有方法冲突时，
转换会失败。

目标类自身没有该字段时，访问器会沿可加载父类查找可继承字段，再查找可加载接口中的 `public static` 字段，
并使用字段实际 owner 生成访问指令；静态继承字段和接口字段都要求访问器方法也是静态方法。
接口字段按 JVM 规则作为静态字段读取，当前只支持 getter；setter 会在转换阶段失败。
setter 可与 `@Mutable` 组合，用于移除目标类自身字段的 `final` 标志后写入字段；继承字段不会被改写修饰符。

**参数：**

- `value: String = ""` - 字段名
- `remap: Boolean = false` - 是否重映射

**示例：** 见 [GUIDE.md](GUIDE.md#常见场景)

### @Invoker

生成调用私有/受保护方法的访问器。

普通调用器会生成一个同签名桥接方法。`value` 为空时会从 `callXxx` 或 `invokeXxx` 方法名推断目标方法名；
目标方法会先在目标类自身查找，找不到时沿可加载父类查找非 `private` 继承方法，再查找可加载接口中的默认方法；
目标方法不存在、参数/返回值不匹配、静态性不匹配，或生成方法与目标类已有方法冲突时，转换会失败。
目标为接口私有方法时，桥接调用会使用 JVM 要求的 `INVOKESPECIAL`；接口默认方法使用
`INVOKEINTERFACE`。接口 `private` 或 `static` 方法不会作为继承默认方法匹配。

构造器调用器使用 `@Invoker("<init>")` 声明。ASM 方法必须是静态方法，参数用于匹配目标构造器，
返回类型必须是目标类、`Any` / `java.lang.Object`，或目标类声明父类型继承链中可加载的父类/接口；
转换后会生成创建目标类实例的静态工厂方法。

**参数：**

- `value: String = ""` - 目标方法名；使用 `"<init>"` 时生成构造器工厂
- `remap: Boolean = false` - 是否重映射

**示例：**

```kotlin
@Invoker("privateMethod")
fun invokePrivateMethod(value: String): String = throw UnsupportedOperationException()

@Invoker("<init>")
@JvmStatic
fun create(value: String): Any = throw UnsupportedOperationException()
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

### @RemoveField

移除目标类中的字段。

该注解可以标在函数或字段上。标在函数上时建议显式指定 `field`；若 `field` 为空，会从 `removeXxx`、`getXxx`、`setXxx`、`isXxx` 方法名推断字段名。标在字段上且 `field` 为空时，使用被标注字段名作为目标字段名。

**参数：**

- `field: String = ""` - 目标字段名
- `remap: Boolean = false` - 是否重映射

**示例：**

```kotlin
@RemoveField(field = "cachedValue")
fun removeCachedValue() {}

@RemoveField
@JvmField
val legacyFlag: Boolean = false
```

### @AddField

向目标类添加字段声明。

该注解标在 ASM 类字段上，只复制字段声明，不复制字段初始化逻辑。非静态字段使用 JVM 默认值初始化，静态字段也不会自动执行 ASM 类中的初始化代码。若目标类已存在同名字段，会跳过并保持原字段不变。

**参数：**

- `field: String = ""` - 目标字段名；为空时使用被标注字段名
- `remap: Boolean = false` - 是否重映射

**示例：**

```kotlin
@AddField
private var cachedValue: String? = null

@AddField(field = "score")
private var mixinScore: Int = 0
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

该注解会在方法级注解处理前运行，把目标类的方法体替换成框架默认返回或 `RedirectionReplaceApi.invokeIgnore`
调用。它适合为整类建立默认实现，再通过同一个 Mixin 中的 `@Overwrite` 恢复少量需要保留逻辑的方法。

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

取消方法的继续执行。该方法只能在可取消回调上调用；未声明 `cancellable = true` 的普通 `@AsmInject`
调用会抛出 `IllegalStateException`。当前实际提前返回分支由 `HEAD` 注入支持。

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

设置返回值（仅在支持返回值修改的注入点有效）。当回调来自 `cancellable = true` 的 `HEAD` 注入时，
该方法也会标记取消，使目标方法提前返回该值；普通 `RETURN` 注入中的非可取消回调只会改写当前返回点。
引用类型返回值可以设置为 `null`，并会作为明确的新返回值写回。

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
- `LOAD` - 局部变量读取前
- `STORE` - 局部变量写入后
- `NEW` - NEW 操作前
- `CAST` - 类型转换后
- `INSTANCEOF` - instanceof 判断指令
- `THROW` - 抛出异常前

其中 `INVOKE_ASSIGN` 当前复用 `INVOKE` 注入器；是否在调用前后插入由 `At.shift` 决定。
`@ModifyExpressionValue` 会把 `INVOKE` / `INVOKE_ASSIGN` 解释为“匹配调用完成后的返回值”；省略调用目标时，会按 handler 首参与返回类型筛选兼容的非 `void` 调用返回；把
`FIELD` 解释为“匹配字段读取完成后的字段值”，当 `args = ["array=get"]` 时解释为“匹配数组元素读取
完成后的元素值”，当 `args = ["array=length"]` 时解释为“匹配数组长度值”，把 `NEW` 解释为“匹配对象构造完成后的实例”，把 `CAST` 解释为“匹配
`CHECKCAST` 完成后的对象表达式值”，把 `INSTANCEOF` 解释为“匹配类型判断后的 boolean 结果”。`@ModifyReceiver` 会把 `INVOKE`
解释为“匹配实例调用前的 receiver 改写”，把 `FIELD` 解释为“匹配实例字段读取前的 receiver 改写”，
把 `FIELD_ASSIGN` 解释为“匹配实例字段写入前的 receiver 改写”。`@WrapOperation` 会把 `INVOKE` 解释为“用可调用原操作的
handler 替换匹配方法调用或构造器创建表达式”，把 `FIELD` 解释为“用可读取原字段值、数组元素值或数组长度的 handler 替换匹配读取”，
把 `FIELD_ASSIGN` 解释为“用可执行原字段写入或数组元素写入的 handler 替换匹配写入”，把 `CAST` 解释为“用可执行原类型转换的 handler 替换匹配 `CHECKCAST`”，把 `INSTANCEOF` 解释为“用可执行原类型判断的 handler 替换匹配类型判断”。
`@WrapWithCondition` 会把 `INVOKE` 解释为“匹配 `void` 调用前的条件判断”，把 `FIELD_ASSIGN`
解释为“匹配字段写入或数组元素写入前的条件判断”。普通 `@AsmInject(FIELD/FIELD_ASSIGN/LOAD/STORE/CAST/INSTANCEOF/THROW)`
使用指令点注入器，支持 `Shift.BEFORE` 与 `Shift.AFTER`，并支持 `At.by` 按真实字节码指令数移动插入锚点；
普通 `@AsmInject(NEW)` 只支持
`Shift.BEFORE` 与 `Shift.REPLACE`，且不支持 `At.by`。普通 `@AsmInject(FIELD/FIELD_ASSIGN/LOAD/STORE/CAST/INSTANCEOF/THROW)` 可用 `Slice`
把候选指令限制在一段 `INVOKE` 边界内；普通 `@AsmInject(LOAD/STORE)` 只作为局部变量读写指令附近的观察 hook，
不会把局部变量值传给 handler，也可以用
`at.args = ["index=N"]` 或 `["var=N"]` 按 JVM 局部变量槽位过滤；需要读取并写回变量值时使用 `@ModifyVariable`。
普通 `@AsmInject(INSTANCEOF)` 只在匹配类型判断指令前后插入 handler，不接收也不修改 boolean 结果；需要替换类型判断时使用 `@Redirect`，需要按需调用原判断时使用 `@WrapOperation`，只需要改写类型判断结果时使用
`@ModifyExpressionValue(at = At(value = InjectionPoint.INSTANCEOF, target = "..."))`。`Shift.REPLACE` 当前按 `BEFORE` 处理。

### At

用于指定精确的注入位置。

**参数：**

- `value: InjectionPoint = InjectionPoint.HEAD` - 注入点类型
- `target: String = ""` - 目标方法、字段或类型签名
- `shift: Shift = Shift.BEFORE` - 偏移方向
- `by: Int = 0` - 额外偏移量；当前普通 `@AsmInject(FIELD/FIELD_ASSIGN/LOAD/STORE/CAST/INSTANCEOF/THROW)` 支持按真实字节码指令数正负移动锚点
- `args: Array<String> = []` - 附加定位参数；`@Redirect` 当前支持 `array=get`、`array=set`
  与 `array=length`，`@WrapOperation` 当前支持 `array=get`、`array=set` 与 `array=length`，`@WrapWithCondition` 当前支持 `array=set`，`@ModifyExpressionValue` 当前支持 `array=get`
  与 `array=length`，普通 `@AsmInject(LOAD/STORE)` 当前支持 `index=N` 与 `var=N`

**`target` 格式：**

- `INVOKE`: `owner.name(desc)` 或 `name(desc)`，例如 `java/lang/String.trim()Ljava/lang/String;`
- `FIELD`: `owner.field:desc`、`field:desc` 或 `field`，例如 `com/example/Target.name:Ljava/lang/String;`
- `FIELD_ASSIGN`: 与 `FIELD` 相同，但只匹配 `PUTFIELD` / `PUTSTATIC`
- `NEW`: 类型 internal name 或 binary name，例如 `java/lang/StringBuilder` 或 `java.lang.StringBuilder`
- `CAST`: 类型 internal name 或 binary name，例如 `java/lang/String` 或 `java.lang.String`
- `INSTANCEOF`: 类型 internal name 或 binary name，例如 `java/lang/String` 或 `java.lang.String`
- `THROW`: 通常不需要 `target`，匹配 `ATHROW`；普通 `@AsmInject` 与 `@ModifyExpressionValue` 可用类型 internal name 或 binary name 只匹配直接构造后抛出的同类型异常

`@Redirect` 可在 `FIELD` 目标上使用 `args = ["array=get"]`、`args = ["array=set"]` 或 `args = ["array=length"]`，
把目标字段解释为产生数组引用的字段，并重定向紧随其后的数组元素读取、数组元素写入或 `ARRAYLENGTH`。`@WrapOperation`
可使用 `FIELD + array=get` 包裹数组元素读取，使用 `FIELD_ASSIGN + array=set` 包裹数组元素写入，使用 `FIELD + array=length` 包裹数组长度读取。
`@ModifyExpressionValue` 可在 `FIELD` 目标上使用 `args = ["array=get"]`，改写紧随目标数组字段后的数组元素读取值；也可使用 `args = ["array=length"]`，改写紧随目标数组字段后的 `ARRAYLENGTH` 结果。
普通 `@AsmInject(LOAD/STORE)` 可使用 `args = ["index=N"]` 或 `args = ["var=N"]`，只在 JVM 局部变量槽位 `N`
的 `xLOAD` / `xSTORE` 指令附近插入 handler；这不会把槽位值传入 handler，也不会写回槽位。

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

用于定义查找范围。当前普通 `@AsmInject(target = InjectionPoint.INVOKE / InjectionPoint.FIELD / InjectionPoint.FIELD_ASSIGN / InjectionPoint.LOAD / InjectionPoint.STORE / InjectionPoint.NEW / InjectionPoint.CAST / InjectionPoint.INSTANCEOF / InjectionPoint.THROW)`、`@Redirect(at.value = InjectionPoint.INVOKE / InjectionPoint.FIELD / InjectionPoint.FIELD_ASSIGN / InjectionPoint.NEW / InjectionPoint.CAST / InjectionPoint.INSTANCEOF)`
以及 `@ModifyArg(at.value = InjectionPoint.INVOKE)`、`@ModifyArgs(at.value = InjectionPoint.INVOKE)`、
`@ModifyReceiver(at.value = InjectionPoint.INVOKE / InjectionPoint.FIELD / InjectionPoint.FIELD_ASSIGN)`、`@WrapOperation(at.value = InjectionPoint.INVOKE / InjectionPoint.FIELD / InjectionPoint.FIELD_ASSIGN / InjectionPoint.NEW / InjectionPoint.CAST / InjectionPoint.INSTANCEOF)`、
`@WrapWithCondition(at.value = InjectionPoint.INVOKE / InjectionPoint.FIELD_ASSIGN)`、
`@ModifyExpressionValue(at.value = InjectionPoint.INVOKE / InjectionPoint.INVOKE_ASSIGN / InjectionPoint.FIELD / InjectionPoint.NEW / InjectionPoint.CAST / InjectionPoint.INSTANCEOF / InjectionPoint.THROW)`、
`@ModifyVariable(at.value = InjectionPoint.LOAD / InjectionPoint.STORE)`、`@ModifyConstant`
支持 `from` / `to` 为 `InjectionPoint.INVOKE`
的边界切片；未在上面列出的注解当前不使用 `slice`。

**参数：**

- `from: At = At()` - 起始位置；命中该边界之后开始查找候选注入点
- `to: At = At()` - 结束位置；命中该边界之前停止查找候选注入点
- `id: String = ""` - 切片标识符

边界调用本身不参与候选匹配，`ordinal` 会在切片范围内重新计数。若指定的 `from` 或 `to` 未命中，
切片按空范围处理。

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
|---------|---------|
| void    | V       |
| boolean | Z       |
| byte    | B       |
| short   | S       |
| int     | I       |
| long    | J       |
| float   | F       |
| double  | D       |
| char    | C       |

### 引用类型

| Java 类型           | JVM 描述符             |
|-------------------|---------------------|
| String            | Ljava/lang/String;  |
| Object            | Ljava/lang/Object;  |
| int[]             | [I                  |
| String[]          | [Ljava/lang/String; |
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

