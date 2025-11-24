/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.mixin

import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.Overwrite

/**
 * Overwrite ASM: 一次性覆盖所有方法
 * - testA0(): 返回 "OverwrittenA0"
 * - testB0(): 返回 "OverwrittenB0"
 * - testC0(String): 返回 "OverwrittenC0"
 * - testC1(String): 返回 "OverwrittenC1"
 */
@AsmMixin("Test")
object OverwriteMixin {
    @Overwrite(method = "testA0()Ljava/lang/String;")
    @JvmStatic
    fun testA0(): String = "OverwrittenA0"

    @Overwrite(method = "testB0()Ljava/lang/String;")
    @JvmStatic
    fun testB0(): String = "OverwrittenB0"

    @Overwrite(method = "testC0(Ljava/lang/String;)Ljava/lang/String;")
    @JvmStatic
    fun testC0(string: String): String = "OverwrittenC0"

    @Overwrite(method = "testC1(Ljava/lang/String;)Ljava/lang/String;")
    @JvmStatic
    fun testC1(string: String): String = "OverwrittenC1"
}
