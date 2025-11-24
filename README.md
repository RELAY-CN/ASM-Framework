<div align="center">
<h1>IronCore ASM-Framework</h1>

----
**IronCore ASM-Framework** 是一个 **IronCore** 的依赖项目  
为 `IronCore` 提供 ASM 支持  
同样的, 你也可以应用于自己的项目之中

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9+-blue.svg)](https://kotlinlang.org)
[![ASM](https://img.shields.io/badge/ASM-9.7-green.svg)](https://asm.ow2.io)
[![License](https://img.shields.io/badge/License-Custom-yellow.svg)](LICENSE)

</div>

## 简介

IronCore ASM-Framework 是一个基于 ASM 的字节码操作框架，提供了类似 Fabric Mixin 的注解驱动编程模型。它允许开发者通过简单的注解来修改、增强或替换目标类的行为，而无需直接操作复杂的字节码。

框架采用 Kotlin 编写，充分利用了 Kotlin 的类型系统和语言特性，提供了类型安全、易于使用的 API。通过注解系统，开发者可以声明式地定义字节码转换规则，框架会自动处理字节码的解析、转换和生成。

## 特性

- **类似 Fabric Mixin 的注解系统** - 简洁易用的注解驱动编程，无需直接操作字节码
- **强大的注入点支持** - HEAD, TAIL, RETURN, INVOKE 等多种注入位置，满足各种场景需求
- **丰富的转换类型** - Inject, Overwrite, Redirect, ModifyArg, ModifyReturnValue 等十多种转换类型
- **高性能** - 基于 ASM 9.7 的高效字节码转换，最小化运行时开销
- **类型安全** - 完善的 Kotlin 类型系统支持，编译时类型检查
- **灵活注册** - 支持手动注册、包扫描、JAR 扫描、类加载器扫描等多种注册方式
- **路径匹配** - 支持使用路径匹配器动态匹配目标类，实现批量处理
- **Kotlin 友好** - 原生支持 Kotlin object 和 class，自动处理实例化

## 架构设计

框架采用分层架构设计，主要包含以下核心组件：

- **AsmRegistry** - ASM 注册器，负责管理和索引所有注册的 Mixin 类
- **AsmScanner** - ASM 扫描器，提供自动扫描和注册功能
- **AsmProcessor** - ASM 处理器，负责应用所有注册的转换规则
- **AsmTransformer** - 字节码转换器基类，封装了 ASM Tree API 的常用操作
- **Injector 系统** - 各种注入器实现，处理不同类型的字节码转换

框架通过注解处理器扫描 `@AsmMixin` 注解，将 Mixin 类注册到 `AsmRegistry` 中。当需要转换类时，`AsmProcessor` 会查找所有相关的 Mixin，按照预定义的顺序应用各种转换规则，最终生成转换后的字节码。

## 工作原理

1. **注册阶段** - Mixin 类通过 `AsmRegistry` 或 `AsmScanner` 注册到框架中
2. **匹配阶段** - 当类需要转换时，框架根据类名查找所有匹配的 Mixin（支持精确匹配和路径匹配）
3. **转换阶段** - 框架按照特定顺序应用各种转换规则（RETURN/TAIL 注入优先于 HEAD 注入）
4. **生成阶段** - 将转换后的 ClassNode 写回字节码数组

框架确保转换的顺序性和一致性，避免不同注入点之间的冲突，同时提供了完善的错误处理机制。

## 适用场景

- **游戏服务器开发** - 修改游戏逻辑、添加功能、修复 Bug
- **框架扩展** - 在不修改源码的情况下扩展第三方框架
- **性能优化** - 通过字节码级别优化提升性能
- **兼容性处理** - 适配不同版本的库或框架
- **功能增强** - 为现有类添加新功能或修改行为
- **调试和监控** - 注入日志、性能监控等代码

## 文档

- **[API.md](API.md)** - 完整的 API 参考，包含所有注解和类的详细说明
- **[GUIDE.md](GUIDE.md)** - 使用指南，包含常见场景、最佳实践和故障排除

## 贡献

欢迎提交 Issue 和 Pull Request！

## 许可证

Copyright 2020-2024 Dr (dr@der.kim) and contributors.