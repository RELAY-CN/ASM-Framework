/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.scanner.fixture

/**
 * 扫描测试状态
 * 用于验证扫描阶段不会触发 Mixin 类初始化
 */
object ScanState {
    @JvmField
    var initializedCount: Int = 0

    fun reset() {
        initializedCount = 0
    }
}
