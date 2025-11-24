/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.mixin

import kim.der.asm.api.annotation.AsmMixin

/**
 * Redirect ASM: 重定向方法内部调用
 * 注意：Redirect 用于重定向方法内部对其他方法的调用
 * 由于 testC0 和 testC1 方法内部没有调用其他方法，这里仅作为示例
 * 实际使用时，Redirect 应该重定向方法内部对目标方法的调用
 */
@AsmMixin("Test")
object RedirectMixin {
    // 注意：Redirect 需要方法内部有实际的方法调用才能生效
    // 这里仅作为示例，实际 testC0 和 testC1 方法内部没有方法调用
    // 如果需要测试 Redirect，需要在 Test.java 中添加方法调用
}
