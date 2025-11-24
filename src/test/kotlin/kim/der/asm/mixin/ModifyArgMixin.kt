/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.mixin

import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.ModifyArg

/**
 * ModifyArg ASM: 修改所有方法的参数
 * - testC0(String): 修改参数为 "Modified_原参数"
 * - testC1(String): 修改参数为 "Modified_原参数"
 */
@AsmMixin("Test")
object ModifyArgMixin {
    @ModifyArg(
        method = "testC0(Ljava/lang/String;)Ljava/lang/String;",
        index = 0,
    )
    @JvmStatic
    fun modifyArgC0(string: String): String = "Modified_$string"

    @ModifyArg(
        method = "testC1(Ljava/lang/String;)Ljava/lang/String;",
        index = 0,
    )
    @JvmStatic
    fun modifyArgC1(string: String): String = "Modified_$string"
}
