/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.mixin

import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.RemoveMethod

/**
 * RemoveMethod ASM: 移除指定的方法
 * 可以一次性移除多个方法
 */
@AsmMixin("Test")
class RemoveMethodMixin {
    @RemoveMethod(method = "testA0()Ljava/lang/String;")
    fun removeTestA0() {
        // 这个方法只是标记，实际不会执行
    }

    @RemoveMethod(method = "testB0()Ljava/lang/String;")
    fun removeTestB0() {
        // 这个方法只是标记，实际不会执行
    }

    @RemoveMethod(method = "testC0(Ljava/lang/String;)Ljava/lang/String;")
    fun removeTestC0() {
        // 这个方法只是标记，实际不会执行
    }

    @RemoveMethod(method = "testC1(Ljava/lang/String;)Ljava/lang/String;")
    fun removeTestC1() {
        // 这个方法只是标记，实际不会执行
    }
}
