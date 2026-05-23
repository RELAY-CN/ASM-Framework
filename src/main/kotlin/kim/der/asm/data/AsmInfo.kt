/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.data

import kim.der.asm.func.Find

/**
 * ASM 注册信息。
 *
 * 该类型是 [kim.der.asm.AsmRegistry] 写入注册表后的不可变条目，描述一个 ASM 类如何匹配目标类。
 * 精确目标注册会填充 [targets]；路径匹配注册会填充 [pathMatcher] 并保持 [targets] 为空。
 *
 * @param asmClass ASM 类，通常带有 `@AsmMixin` 或由路径匹配入口显式注册
 * @param targets 精确匹配的目标类 internal name 列表
 * @param pathMatcher 路径匹配器；返回 `true` 表示该 ASM 应用于给定目标类
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
data class AsmInfo(
    val asmClass: Class<*>,
    val targets: List<String>,
    val pathMatcher: Find<String, Boolean>? = null,
)
