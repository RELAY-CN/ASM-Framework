/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.mixin

import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.CallbackInfo
import kim.der.asm.api.annotation.InjectionPoint

/**
 * Inject ASM: 在所有方法的 HEAD、TAIL、RETURN 位置注入
 */
@AsmMixin("Test")
object InjectMixin {
    // HEAD 注入 - testA0
    @AsmInject(
        method = "testA0()Ljava/lang/String;",
        target = InjectionPoint.HEAD,
        cancellable = true,
    )
    fun injectHeadA0(callback: CallbackInfo) {
        callback.setReturnValue("InjectedAtHeadA0")
        callback.cancel()
    }

    // RETURN 注入 - testA0
    @AsmInject(
        method = "testA0()Ljava/lang/String;",
        target = InjectionPoint.RETURN,
    )
    fun injectReturnA0(callback: CallbackInfo) {
        val original = callback.getReturnValue<String>()
        callback.setReturnValue("${original}_Return")
    }

    // HEAD 注入 - testB0 (静态方法)
    @AsmInject(
        method = "testB0()Ljava/lang/String;",
        target = InjectionPoint.HEAD,
        cancellable = true,
    )
    @JvmStatic
    fun injectHeadB0(callback: CallbackInfo) {
        callback.setReturnValue("InjectedAtHeadB0")
        callback.cancel()
    }

    // HEAD 注入 - testC0
    @AsmInject(
        method = "testC0(Ljava/lang/String;)Ljava/lang/String;",
        target = InjectionPoint.HEAD,
    )
    fun injectHeadC0(
        callback: CallbackInfo,
        string: String,
    ) {
        println("Inject HEAD in testC0 with param: $string")
    }

    // HEAD 注入 - testC1 (静态方法)
    @AsmInject(
        method = "testC1(Ljava/lang/String;)Ljava/lang/String;",
        target = InjectionPoint.HEAD,
    )
    @JvmStatic
    fun injectHeadC1(
        callback: CallbackInfo,
        string: String,
    ) {
        println("Inject HEAD in testC1 with param: $string")
    }
}
