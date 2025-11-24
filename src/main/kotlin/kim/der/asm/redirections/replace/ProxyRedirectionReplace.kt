/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.redirections.replace

import kim.der.asm.api.replace.RedirectionReplace
import kim.der.asm.api.replace.RedirectionReplaceManager
import kim.der.asm.redirections.replace.def.BasicDataRedirections
import kim.der.asm.utils.DescriptionUtil.getDesc
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.util.function.Supplier

class ProxyRedirectionReplace(
    private val manager: RedirectionReplaceManager,
    private val internalName: String,
) : InvocationHandler {
    @Throws(Throwable::class)
    override fun invoke(
        proxy: Any,
        method: Method,
        argsIn: Array<Any>?,
    ): Any? {
        val desc = internalName + getDesc(method)
        var fb = Supplier<RedirectionReplace> { manager }

        if (desc.endsWith(";equals(Ljava/lang/Object;)Z")) {
            fb = Supplier { BasicDataRedirections.EQUALS }
        } else if (desc.endsWith(";hashCode()I")) {
            fb = Supplier { BasicDataRedirections.HASHCODE }
        }
        val args = arrayOfNulls<Any>(if (argsIn == null) 1 else argsIn.size + 1)

        // there's basically no way the method is static
        args[0] = proxy
        if (argsIn != null) {
            System.arraycopy(argsIn, 0, args, 1, argsIn.size)
        }
        return manager.invoke(desc, method.returnType, proxy, fb, *args)
    }
}
