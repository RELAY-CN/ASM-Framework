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
 * RedirectAllMethods 示例：将目标类所有方法中的 System.gc() 调用替换成空操作
 * 
 * 使用 @RedirectAllMethods 后，@Redirect 会自动应用到目标类的所有方法，
 * 无需在 @Redirect 中指定 method 参数
 */
@AsmMixin("Test")
@RedirectAllMethods
object RedirectAllMethodsMixin {
    
    /**
     * 将所有方法中的 System.gc() 调用替换成空操作
     */
    @Redirect(
        at = At(
            value = InjectionPoint.INVOKE,
            target = "java/lang/System.gc()V"
        )
    )
    @JvmStatic
    fun redirectSystemGc() {
        // 空操作，阻止 GC 调用
        // 或者可以添加日志：
        // println("System.gc() call blocked")
    }
    
    /**
     * 将所有方法中的 println 调用统一添加前缀
     */
    @Redirect(
        at = At(
            value = InjectionPoint.INVOKE,
            target = "java/io/PrintStream.println(Ljava/lang/String;)V"
        )
    )
    @JvmStatic
    fun redirectAllPrintln(printStream: java.io.PrintStream, message: String) {
        printStream.println("[GLOBAL] $message")
    }
}
