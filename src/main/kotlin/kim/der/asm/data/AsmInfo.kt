/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.data

import kim.der.asm.func.Find

/**
 * ASM 信息
 * 存储 ASM 类的相关信息，包括目标类和路径匹配器
 */
data class AsmInfo(
    val asmClass: Class<*>,
    val targets: List<String>,
    val pathMatcher: Find<String, Boolean>? = null,
)
