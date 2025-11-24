/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.mixin

import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.Invoker

/**
 * Invoker ASM: 调用所有方法
 * - testA0(): 通过 invokeTestA0() 调用（实例方法）
 * - testB0(): 通过 invokeTestB0() 调用（静态方法，需要 @JvmStatic）
 * - testC0(String): 通过 invokeTestC0(String) 调用（实例方法）
 * - testC1(String): 通过 invokeTestC1(String) 调用（静态方法，需要 @JvmStatic）
 */
@AsmMixin("Test")
object InvokerMixin {
    @Invoker("testA0")
    fun invokeTestA0(): String = throw UnsupportedOperationException("Invoker should not be called directly")

    @Invoker("testB0")
    @JvmStatic
    fun invokeTestB0(): String = throw UnsupportedOperationException("Invoker should not be called directly")

    @Invoker("testC0")
    fun invokeTestC0(string: String): String = throw UnsupportedOperationException("Invoker should not be called directly")

    @Invoker("testC1")
    @JvmStatic
    fun invokeTestC1(string: String): String = throw UnsupportedOperationException("Invoker should not be called directly")
}
