/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.mixin

import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.ReplaceAllMethods

/**
 * 针对测试类 `com/example/VoidTarget` 的 ReplaceAllMethods Mixin。
 * 该测试目标类由单元测试动态生成，用于验证 void 返回方法不会注入 RedirectionReplaceApi。
 */
@AsmMixin("Test")
@ReplaceAllMethods
class ReplaceAllMethodsVoidMixin

