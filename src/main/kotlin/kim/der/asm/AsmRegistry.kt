/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm

import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.data.AsmInfo
import kim.der.asm.func.Find

/**
 * ASM 注册器。
 *
 * 负责维护目标类 internal name 到 ASM 类的映射，并为转换阶段提供稳定的匹配列表。
 * 注册表同时支持精确目标注册与路径匹配注册；查询时会先返回路径匹配命中的 ASM，再返回精确目标命中的 ASM。
 * 同一匹配来源内按 [AsmMixin.priority] 从高到低排序，同优先级保持注册顺序，因此一个目标类存在多个 Mixin
 * 时会按该顺序依次应用。
 *
 * 该对象的公开注册、查询与清理入口均使用同步锁保护。注册关系变化会清空目标缓存，
 * 以避免类加载器或 agent 场景下扫描注册与转换查询交错时读取到过期匹配结果。
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
object AsmRegistry {
    private val asms = mutableMapOf<String, MutableList<AsmInfo>>()
    private val pathMatchers = mutableListOf<AsmInfo>()
    private val targetCache = mutableMapOf<String, List<AsmInfo>>()
    private var nextRegistrationOrder = 0L

    /**
     * 注册带 [AsmMixin] 的 ASM 类。
     *
     * 当类未标注 [AsmMixin]，或注解中既没有 [AsmMixin.value] 也没有 [AsmMixin.targets] 时，本方法静默跳过。
     * 若同时提供单目标与多目标，当前实现以 [AsmMixin.targets] 为准。注册成功会清空目标缓存，
     * 后续查询会重新计算匹配列表。
     *
     * @param asmClass ASM 类，通常是 Kotlin `object` 或包含静态处理方法的类
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
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

        val asmInfo =
            AsmInfo(
                asmClass = asmClass,
                targets = targets,
                pathMatcher = null,
                priority = annotation.priority,
                registrationOrder = nextRegistrationOrder++,
            )

        targets.forEach { target ->
            asms.getOrPut(target) { mutableListOf() }.add(asmInfo)
        }

        // 注册关系变更后，清空缓存以避免返回过期结果
        targetCache.clear()
    }

    /**
     * 注册使用路径匹配的 ASM 类。
     *
     * 路径匹配注册适合批量处理某个包、前缀或运行时才能确定的目标类。该入口不会强制校验 [asmClass]
     * 是否带 [AsmMixin]，调用方需要保证传入的类符合 ASM 方法声明约定。路径匹配可能影响任意目标类，
     * 因此每次注册都会清空目标缓存。
     *
     * @param asmClass ASM 类
     * @param pathMatcher 路径匹配器，入参为目标类 internal name，返回 `true` 表示匹配
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    @JvmStatic
    @Synchronized
    fun registerWithPathMatcher(
        asmClass: Class<*>,
        pathMatcher: Find<String, Boolean>,
    ) {
        val annotation = asmClass.getAnnotation(AsmMixin::class.java)
        val asmInfo =
            AsmInfo(
                asmClass = asmClass,
                targets = emptyList(),
                pathMatcher = pathMatcher,
                priority = annotation?.priority ?: DEFAULT_PRIORITY,
                registrationOrder = nextRegistrationOrder++,
            )

        pathMatchers.add(asmInfo)
        // 路径匹配可能影响任意目标类，直接清空整个缓存
        targetCache.clear()
    }

    /**
     * 获取目标类的所有 ASM。
     *
     * 返回结果会被缓存，直到下一次注册或清理操作。结果顺序固定为路径匹配命中在前、精确目标命中在后；
     * 同一匹配来源内按 priority 从高到低排序，priority 相同时保持注册顺序。
     * 该顺序会直接影响 [kim.der.asm.transformer.AsmProcessor] 对多个 Mixin 的应用顺序。
     * 若路径匹配器抛出异常，异常会原样传播给调用方，本方法不会吞掉匹配阶段错误。
     *
     * @param targetClass 目标类 internal name，例如 `com/example/Target`
     * @return 匹配到的 ASM 列表；没有匹配时返回空列表
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    @JvmStatic
    @Synchronized
    fun getForTarget(targetClass: String): List<AsmInfo> {
        return targetCache.getOrPut(targetClass) {
            buildList {
                // 不能调换顺序：路径匹配在前，精确匹配在后（避免模糊覆盖精准）
                pathMatchers
                    .filter { asmInfo -> asmInfo.pathMatcher?.invoke(targetClass) == true }
                    .sortedByPriority()
                    .forEach(::add)
                asms[targetClass]?.sortedByPriority()?.let(::addAll)
            }
        }
    }

    /**
     * 清除所有注册的 ASM 与目标缓存。
     *
     * 该方法主要用于测试隔离、重新扫描或 agent 生命周期重置。调用后已创建的处理器不会持有注册快照，
     * 后续转换查询会基于新的空注册表重新计算。
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    @JvmStatic
    @Synchronized
    fun clear() {
        asms.clear()
        pathMatchers.clear()
        targetCache.clear()
        nextRegistrationOrder = 0L
    }

    /**
     * 按 Mixin priority 生成稳定应用顺序。
     *
     * priority 越高越先执行；priority 相同时使用注册序号保持用户注册顺序。
     */
    private fun Iterable<AsmInfo>.sortedByPriority(): List<AsmInfo> =
        sortedWith(compareByDescending<AsmInfo> { it.priority }.thenBy { it.registrationOrder })

    private const val DEFAULT_PRIORITY = 1000
}
