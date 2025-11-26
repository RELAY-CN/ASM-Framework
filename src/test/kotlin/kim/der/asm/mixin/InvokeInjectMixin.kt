/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.mixin

import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.At
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.Shift

/**
 * 测试 INVOKE 注入点，验证 @at 参数是否可用
 */
@AsmMixin("Test")
object InvokeInjectMixin {
    // 在 System.out.println 调用前注入
    @AsmInject(
        method = "testC0(Ljava/lang/String;)Ljava/lang/String;",
        target = InjectionPoint.INVOKE,
        at = At(
            value = InjectionPoint.INVOKE,
            target = "java/io/PrintStream.println(Ljava/lang/String;)V",
            shift = Shift.BEFORE
        )
    )
    fun injectBeforePrintln(callback: CallbackInfo, string: String) {
        println("[INVOKE INJECT] Before println with param: $string")
    }

    // 在 System.out.println 调用后注入
    @AsmInject(
        method = "testC0(Ljava/lang/String;)Ljava/lang/String;",
        target = InjectionPoint.INVOKE,
        at = At(
            value = InjectionPoint.INVOKE,
            target = "java/io/PrintStream.println(Ljava/lang/String;)V",
            shift = Shift.AFTER
        )
    )
    fun injectAfterPrintln(callback: CallbackInfo, string: String) {
        println("[INVOKE INJECT] After println with param: $string")
    }
}

