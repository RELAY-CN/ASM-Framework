/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.mixin

import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.annotation.InjectionPoint

/**
 * 演示如何在 Mixin 中访问目标类的 this 实例
 *
 * 使用方法：
 * 1. 在 Mixin 方法中，第一个参数（跳过 CallbackInfo 后）声明为目标类的类型
 * 2. 框架会自动传递目标类的 this 实例作为该参数
 * 3. 可以通过这个参数访问目标类的字段和方法
 *
 * 本例只需观察对象身份，因此用 Any 接收目标 this，直接调用 hashCode，不依赖反射查找成员。
 * 需要访问目标私有状态时可用 inline + Shadow；普通 handler 的 this 仍属于 Mixin。
 */
@AsmMixin("Test")
class ThisAccessMixin {
    /**
     * 示例：在 HEAD 注入中访问目标类的 this
     *
     * 注意：参数顺序必须是：
     * 1. CallbackInfo（如果需要）
     * 2. 目标类的 this（如果需要，类型必须是目标类或 Object）
     * 3. 其他参数（如果有）
     *
     * Any 可跨隔离 ClassLoader 接收目标；这里不访问目标专属字段。
     */
    @AsmInject(
        method = "testA0()Ljava/lang/String;",
        target = InjectionPoint.HEAD,
    )
    fun injectHeadWithThis(
        callback: CallbackInfo,
        test: Any,
    ) {
        // test 就是目标类 Test 的 this 实例
        // 由于 Test 类没有包名，我们使用 Any/Object 类型

        // javaClass 仅用于日志；成员映射由其他直接字段/方法调用用例验证。
        val testClass = test.javaClass

        // 示例：记录目标实例的信息
        println("Target instance: $test")
        println("Target class: ${testClass.name}")
    }

    /**
     * 示例：在 RETURN 注入中访问目标类的 this 并修改返回值
     */
    @AsmInject(
        method = "testA0()Ljava/lang/String;",
        target = InjectionPoint.RETURN,
    )
    fun injectReturnWithThis(
        callback: CallbackInfo,
        test: Any,
    ) {
        // 可以通过 test 访问目标类的状态
        // 基于目标类的状态修改返回值

        // 示例：基于目标实例的 hashCode 修改返回值
        val instanceHash = test.hashCode()
        callback.setReturnValue("Modified by ThisAccess: $instanceHash")
    }

    /**
     * 示例：在带参数的方法中访问目标类的 this
     */
    @AsmInject(
        method = "testC0(Ljava/lang/String;)Ljava/lang/String;",
        target = InjectionPoint.HEAD,
    )
    fun injectWithThisAndParams(
        callback: CallbackInfo,
        test: Any,
        string: String,
    ) {
        // 参数顺序：
        // 1. CallbackInfo
        // 2. 目标类的 this (test)
        // 3. 目标方法的参数 (string)

        println("Target instance: $test")
        println("Method parameter: $string")

        // 可以基于目标实例和参数进行一些操作
    }
}
