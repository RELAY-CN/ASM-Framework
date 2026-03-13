/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.mixin

import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.At
import kim.der.asm.api.annotation.InjectionPoint
import kim.der.asm.api.annotation.Redirect

/**
 * Redirect ASM: 重定向方法内部调用
 */
@AsmMixin("Test")
object RedirectMixin {
    
    @Redirect(
        method = "comprehensiveTest()Ljava/lang/String;",
        at = At(
            value = InjectionPoint.INVOKE,
            target = "testA0()Ljava/lang/String;"
        )
    )
    @JvmStatic
    fun redirectTestA0(instance: Any): String {
        return "Redirected-testA0"
    }
    
    @Redirect(
        method = "comprehensiveTest()Ljava/lang/String;",
        at = At(
            value = InjectionPoint.INVOKE,
            target = "testB0()Ljava/lang/String;"
        )
    )
    @JvmStatic
    fun redirectTestB0(): String {
        return "Redirected-testB0"
    }
    
    @Redirect(
        method = "comprehensiveTest()Ljava/lang/String;",
        at = At(
            value = InjectionPoint.INVOKE,
            target = "parentMethod()Ljava/lang/String;"
        )
    )
    @JvmStatic
    fun redirectParentMethod(instance: Any): String {
        return "Redirected-parentMethod"
    }

    @Redirect(
        method = "comprehensiveTest()Ljava/lang/String;",
        at = At(
            value = InjectionPoint.INVOKE,
            target = "interfaceMethod(Ljava/lang/String;)Ljava/lang/String;"
        )
    )
    @JvmStatic
    fun redirectInterfaceMethod(instance: Any, input: String): String {
        return "Redirected-interfaceMethod-$input"
    }
    
    @Redirect(
        method = "testC0(Ljava/lang/String;)Ljava/lang/String;",
        at = At(
            value = InjectionPoint.INVOKE,
            target = "java/io/PrintStream.println(Ljava/lang/String;)V"
        )
    )
    @JvmStatic
    fun redirectPrintln(printStream: java.io.PrintStream, message: String) {
        printStream.println("[REDIRECTED] $message")
    }
    
    @Redirect(
        method = "enumTest()Ljava/lang/String;",
        at = At(
            value = InjectionPoint.INVOKE,
            target = "Test\$TestEnum.getDescription()Ljava/lang/String;"
        )
    )
    @JvmStatic
    fun redirectGetDescription(enumValue: Any): String {
        return "Redirected-EnumDescription"
    }
    
    @Redirect(
        method = "lambdaTest()Ljava/lang/String;",
        at = At(
            value = InjectionPoint.INVOKE,
            target = "java/util/function/Supplier.get()Ljava/lang/Object;"
        )
    )
    @JvmStatic
    fun redirectSupplierGet(supplier: java.util.function.Supplier<*>): Any {
        return "Redirected-Lambda"
    }
}
