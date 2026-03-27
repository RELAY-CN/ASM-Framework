/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.scanner.fixture

import kim.der.asm.api.annotation.AsmMixin

/**
 * 扫描测试用 Mixin
 * 通过伴生对象初始化计数，验证扫描时是否发生类初始化
 */
@AsmMixin("test/ScanTarget")
class ScanMixin {
    companion object {
        init {
            ScanState.initializedCount++
        }
    }
}
