/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.mixin

import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.ModifyReturnValue

/**
 * ModifyReturnValue ASM: 修改所有方法的返回值
 * - testA0(): 返回 "ModifiedReturnA0"
 * - testB0(): 返回 "ModifiedReturnB0"
 * - testC0(String): 返回 "ModifiedReturnC0"
 * - testC1(String): 返回 "ModifiedReturnC1"
 */
@AsmMixin("Test")
object ModifyReturnValueMixin {
    @ModifyReturnValue(method = "testA0()Ljava/lang/String;")
    @JvmStatic
    fun modifyReturnA0(original: String): String = "ModifiedReturnA0"

    @ModifyReturnValue(method = "testB0()Ljava/lang/String;")
    @JvmStatic
    fun modifyReturnB0(original: String): String = "ModifiedReturnB0"

    @ModifyReturnValue(method = "testC0(Ljava/lang/String;)Ljava/lang/String;")
    @JvmStatic
    fun modifyReturnC0(original: String, string: String): String = "ModifiedReturnC0"

    @ModifyReturnValue(method = "testC1(Ljava/lang/String;)Ljava/lang/String;")
    @JvmStatic
    fun modifyReturnC1(original: String, string: String): String = "ModifiedReturnC1"
}

