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
 * [priority] 与 [registrationOrder] 共同决定同一匹配来源内的稳定应用顺序。
 *
 * @param asmClass ASM 类，通常带有 `@AsmMixin` 或由路径匹配入口显式注册
 * @param targets 精确匹配的目标类 internal name 列表
 * @param pathMatcher 路径匹配器；返回 `true` 表示该 ASM 应用于给定目标类
 * @param priority Mixin 应用优先级；数值越高越先应用
 * @param registrationOrder 注册序号；同优先级时按该值保持注册顺序
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
data class AsmInfo(
    /**
     * ASM 类。
     *
     * 通常是带 [kim.der.asm.api.annotation.AsmMixin] 的 Kotlin `object` 或 class。
     */
    val asmClass: Class<*>,

    /**
     * 精确匹配的目标类 internal name 列表。
     *
     * 路径匹配注册会保持该列表为空。
     */
    val targets: List<String>,

    /**
     * 路径匹配器。
     *
     * 返回 `true` 表示该 ASM 应用于给定目标类；精确目标注册时为 `null`。
     */
    val pathMatcher: Find<String, Boolean>? = null,

    /**
     * Mixin 应用优先级。
     *
     * 同一匹配来源内数值越高越先应用；路径匹配来源和精确匹配来源仍由注册器分别排序。
     */
    val priority: Int = 1000,

    /**
     * 注册序号。
     *
     * 用于在 [priority] 相同时保持稳定注册顺序，避免排序后同优先级 Mixin 出现非确定性。
     */
    val registrationOrder: Long = 0L,
)
