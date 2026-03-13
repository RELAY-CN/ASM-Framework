/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.mixin

import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.At
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.Redirect
import kim.der.asm.api.annotation.RedirectAllMethods

/**
 * RedirectAllMethods 高级示例：
 * 同时重定向多个方法调用，应用到目标类的所有方法
 */
@AsmMixin("Test")
@RedirectAllMethods
object RedirectAllMethodsAdvancedMixin {
    
    /**
     * 重定向所有 testA0 调用
     */
    @Redirect(
        at = At(
            value = InjectionPoint.INVOKE,
            target = "testA0()Ljava/lang/String;"
        )
    )
    @JvmStatic
    fun redirectTestA0(instance: Any): String {
        return "Global-Redirected-testA0"
    }
    
    /**
     * 重定向所有 testB0 调用
     */
    @Redirect(
        at = At(
            value = InjectionPoint.INVOKE,
            target = "testB0()Ljava/lang/String;"
        )
    )
    @JvmStatic
    fun redirectTestB0(): String {
        return "Global-Redirected-testB0"
    }
    
    /**
     * 重定向所有 System.out.println 调用，添加统一前缀
     */
    @Redirect(
        at = At(
            value = InjectionPoint.INVOKE,
            target = "java/io/PrintStream.println(Ljava/lang/String;)V"
        )
    )
    @JvmStatic
    fun redirectPrintln(printStream: java.io.PrintStream, message: String) {
        printStream.println("[GLOBAL-REDIRECT] $message")
    }
}
