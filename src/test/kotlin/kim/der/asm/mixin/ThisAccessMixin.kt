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
 * 注意：目标类 Test 没有包名，所以直接使用 Object 类型或者通过反射访问
 * 为了简化，这里使用 Object 类型，然后通过反射访问
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
     * 由于 Test 类没有包名，我们使用 Object 类型，然后通过反射访问
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

        // 可以通过反射访问目标类的字段和方法
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
