/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm

import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.data.AsmInfo
import kim.der.asm.func.Find

/**
 * ASM 注册器
 * 扫描并注册带 @AsmMixin 注解的类
 *
 * @author Dr (dr@der.kim)
 */
object AsmRegistry {
    private val asms = mutableMapOf<String, MutableList<AsmInfo>>()
    private val pathMatchers = mutableListOf<AsmInfo>()
    private val targetCache = mutableMapOf<String, List<AsmInfo>>()

    /**
     * 注册 ASM 类
     */
    @JvmStatic
    @Synchronized
    fun register(asmClass: Class<*>) {
        val annotation = asmClass.getAnnotation(AsmMixin::class.java) ?: return

        val targets =
            when {
                annotation.targets.isNotEmpty() -> annotation.targets.asList()
                annotation.value.isNotEmpty() -> listOf(annotation.value)
                else -> return
            }

        val asmInfo = AsmInfo(asmClass = asmClass, targets = targets, pathMatcher = null)

        targets.forEach { target ->
            asms.getOrPut(target) { mutableListOf() }.add(asmInfo)
        }

        // 注册关系变更后，清空缓存以避免返回过期结果
        targetCache.clear()
    }

    /**
     * 注册使用路径匹配的 ASM 类
     *
     * @param asmClass ASM 类
     * @param pathMatcher 路径匹配器，返回 true 表示匹配
     */
    @JvmStatic
    @Synchronized
    fun registerWithPathMatcher(
        asmClass: Class<*>,
        pathMatcher: Find<String, Boolean>,
    ) {
        val asmInfo =
            AsmInfo(
                asmClass = asmClass,
                targets = emptyList(),
                pathMatcher = pathMatcher,
            )

        pathMatchers.add(asmInfo)
        // 路径匹配可能影响任意目标类，直接清空整个缓存
        targetCache.clear()
    }

    /**
     * 获取目标类的所有 ASM
     */
    @JvmStatic
    @Synchronized
    fun getForTarget(targetClass: String): List<AsmInfo> {
        return targetCache.getOrPut(targetClass) {
            buildList {
                // 不能调换顺序：路径匹配在前，精确匹配在后（避免模糊覆盖精准）
                pathMatchers.forEach { asmInfo ->
                    if (asmInfo.pathMatcher?.invoke(targetClass) == true) {
                        add(asmInfo)
                    }
                }
                asms[targetClass]?.let(::addAll)
            }
        }
    }

    /**
     * 清除所有注册的 ASM
     */
    @JvmStatic
    @Synchronized
    fun clear() {
        asms.clear()
        pathMatchers.clear()
        targetCache.clear()
    }
}
