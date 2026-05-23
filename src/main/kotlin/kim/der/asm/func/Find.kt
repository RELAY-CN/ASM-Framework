/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.func

/**
 * 单参数查找/匹配函数接口。
 *
 * 该接口用于在不依赖 Kotlin 函数类型 ABI 的位置传递匹配逻辑，例如
 * `AsmRegistry.registerWithPathMatcher` 的目标类路径匹配器。
 *
 * @param T 输入类型
 * @param R 返回类型
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
fun interface Find<T, R> {
    /**
     * 执行查找或匹配。
     *
     * @param t 输入内容
     * @return 查找或匹配结果
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    operator fun invoke(t: T): R
}
