/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.redirections.replace

import kim.der.asm.api.replace.RedirectionReplace
import kim.der.asm.api.replace.RedirectionReplaceManager
import kim.der.asm.redirections.replace.def.BasicDataRedirections
import kim.der.asm.utils.DescriptionUtil.getDesc
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.util.function.Supplier

/**
 * 接口代理重定向处理器。
 *
 * [ObjectRedirectionReplace] 为接口类型生成动态代理时使用该处理器。代理方法会被转换为统一调用点描述符，
 * 再交给 [manager] 执行重定向；`equals` 与 `hashCode` 使用 [BasicDataRedirections] 中的默认实现兜底。
 *
 * @param manager 处理代理方法调用的重定向替换管理器
 * @param internalName 被代理接口的 JVM 类型描述符
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
class ProxyRedirectionReplace(
    private val manager: RedirectionReplaceManager,
    private val internalName: String,
) : InvocationHandler {
    /**
     * 将代理方法调用转发到重定向替换管理器。
     *
     * 传给管理器的参数数组会把代理对象放在第一个位置，随后追加原始方法参数。
     *
     * @param proxy 动态代理实例
     * @param method 被调用的接口方法
     * @param argsIn 原始方法参数；无参数时为 `null`
     * @return 管理器返回的代理方法结果
     * @throws Throwable 管理器或替换器执行失败时透出
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
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
