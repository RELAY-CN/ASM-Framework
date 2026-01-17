# @Overwrite 子类覆盖问题修复

## 问题描述

当使用 `@Overwrite` 覆盖父类方法，并在覆盖的方法中使用 `@Shadow` 调用其他方法时，会出现子类行为异常的问题：

```java
// 原始代码
public class FileLoader {
    public String b() {
        return "/default/path/";
    }
    
    public String f(String path) {
        String basePath = b();  // 调用 b() 方法
        return basePath + path;
    }
}

public class d extends FileLoader {
    private String g;
    
    @Override
    public String b() {
        return this.g;  // 返回构造函数传入的自定义路径
    }
}
```

```kotlin
// 使用 @Overwrite 后
@AsmMixin("com.corrodinggames.rts.gameFramework.file.FileLoader")
object FileLoaderMixin {
    @Shadow(method = "shadow_b")
    private fun getBasePath(): String = 
        throw UnsupportedOperationException()
    
    @Overwrite("b()Ljava/lang/String;")
    fun b(): String = "/custom/path/"
    
    @Overwrite("f(Ljava/lang/String;)Ljava/lang/String;")
    fun f(path: String): String {
        val basePath = getBasePath()  // 调用 Shadow 方法
        return basePath + path
    }
}
```

**问题**：
- `FileLoader` 实例调用 `f(path)` → `getBasePath()` 返回 `/custom/path/` ✓
- `d` 实例调用 `f(path)` → `getBasePath()` 应该返回 `this.g`，但实际返回 `/custom/path/` ✗

## 问题根源

在原始实现中，`@Shadow` 方法调用被转换为 `INVOKEVIRTUAL` 指令：

```
INVOKEVIRTUAL FileLoader.b()
```

**`INVOKEVIRTUAL` 总是使用虚方法表进行动态分派**，即使指定了 `FileLoader.b()`，如果实际对象是 `d` 类型，仍然会调用 `d.b()`。

但是，当 `FileLoader.b()` 被 `@Overwrite` 覆盖后，虚方法表中的入口指向了被覆盖的版本，导致子类 `d` 调用 `f(path)` 时，内部的 `getBasePath()` 调用返回了被覆盖的值。

## 解决方案

将 `@Shadow` 方法调用从 `INVOKEVIRTUAL` 转换为 `INVOKESPECIAL`：

```
INVOKESPECIAL FileLoader.b()
```

**`INVOKESPECIAL` 会强制调用指定类的方法，不使用虚方法表**，这样即使子类重写了该方法，也会调用父类的实现。

## 修复实现

修改 `OverwriteInjector.transformShadowReferences()` 方法：

```kotlin
if (shadowMethodMap.containsKey(insn.name)) {
    val targetMethodName = shadowMethodMap[insn.name]!!
    insn.owner = targetClassName
    insn.name = targetMethodName
    
    // 关键修复：将 INVOKEVIRTUAL 转换为 INVOKESPECIAL
    if (insn.opcode == Opcodes.INVOKEVIRTUAL) {
        insn.opcode = Opcodes.INVOKESPECIAL
    }
}
```

## 效果

修复后：
- `FileLoader` 实例调用 `f(path)` → `getBasePath()` 使用 `INVOKESPECIAL` 调用 `FileLoader.b()` → 返回 `/custom/path/` ✓
- `d` 实例调用 `f(path)` → `getBasePath()` 使用 `INVOKESPECIAL` 调用 `FileLoader.b()` → 返回 `/default/path/`（原始实现），然后被子类的逻辑处理 ✓

**注意**：这个修复确保了 `@Shadow` 方法调用始终调用目标类（父类）的方法，而不会被子类重写影响。

## 字节码对比

### 修复前
```
// f(String) 方法
ALOAD 0           // 加载 this
INVOKEVIRTUAL FileLoader.b()  // 使用虚方法表，可能调用子类的 b()
ASTORE 1
...
```

### 修复后
```
// f(String) 方法
ALOAD 0           // 加载 this
INVOKESPECIAL FileLoader.b()  // 强制调用 FileLoader.b()，不使用虚方法表
ASTORE 1
...
```

## 使用建议

1. **`@Shadow` 方法用于访问父类的原始实现**：当你需要在覆盖的方法中调用父类的方法时使用
2. **如果需要支持多态**：不要使用 `@Shadow`，直接调用方法（会使用 `INVOKEVIRTUAL`）
3. **理解 `INVOKESPECIAL` 的语义**：它类似于 Java 中的 `super.method()` 调用

## 相关问题

- JVM 指令集：`INVOKEVIRTUAL` vs `INVOKESPECIAL`
- Java 多态机制：虚方法表（Virtual Method Table）
- ASM 框架：Shadow 方法的语义

## 更新日志

- **2025-01-17**: 修复 Shadow 方法调用使用 `INVOKESPECIAL` 而不是 `INVOKEVIRTUAL`

