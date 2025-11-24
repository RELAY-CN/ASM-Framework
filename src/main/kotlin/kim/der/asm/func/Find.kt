/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.func

fun interface Find<T, R> {
    /**
     * @param t 内容
     */
    operator fun invoke(t: T): R
}
