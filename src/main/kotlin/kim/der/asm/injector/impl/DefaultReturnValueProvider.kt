/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.injector.impl

import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/**
 * 默认返回值提供器。
 *
 * 该对象是 `@ReplaceAllMethods` 在遇到非基础对象返回值时调用的内部运行期入口。
 * 它只负责为被清空的方法体生成一个可验证的默认返回值，不再承载旧版 Redirection manager、
 * listener 或按调用描述符分派的扩展语义。
 *
 * ## 默认值策略
 *
 * - `void` 返回 `null`
 * - 基础类型返回 JVM 默认零值
 * - 接口返回动态代理，代理方法继续按返回类型生成默认值
 * - 数组返回对应维度的空数组
 * - 抽象类返回 `null`
 * - 普通类优先调用无参构造；没有无参构造时使用第一个构造器并填充参数默认值
 *
 * @author Dr (dr@der.kim)
 * @date 2026-06-01
 */
object DefaultReturnValueProvider {
    /**
     * 根据返回类型生成默认值。
     *
     * @param type 目标方法返回类型
     * @return 可作为该类型返回值使用的默认对象；无法构造时返回 `null`
     *
     * @author Dr (dr@der.kim)
     * @date 2026-06-01
     */
    @JvmStatic
    @Suppress("UNUSED")
    fun defaultValue(type: Class<*>): Any? =
        when {
            type == Void.TYPE -> null
            type.isPrimitive -> primitiveValue(type)
            type == String::class.java -> ""
            type == CharSequence::class.java -> ""
            type.isInterface -> proxyFor(type)
            type.isArray -> emptyArrayValue(type)
            Modifier.isAbstract(type.modifiers) -> null
            else -> instantiate(type)
        }

    private fun primitiveValue(type: Class<*>): Any =
        when (type) {
            Boolean::class.javaPrimitiveType -> false
            Byte::class.javaPrimitiveType -> 0.toByte()
            Short::class.javaPrimitiveType -> 0.toShort()
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0.0f
            Double::class.javaPrimitiveType -> 0.0
            Char::class.javaPrimitiveType -> 0.toChar()
            else -> error("Unsupported primitive return type: ${type.name}")
        }

    private fun proxyFor(type: Class<*>): Any =
        Proxy.newProxyInstance(
            type.classLoader,
            arrayOf(type),
            DefaultReturnInvocationHandler,
        )

    private fun emptyArrayValue(type: Class<*>): Any {
        var componentType = type
        var dimension = 0
        while (componentType.isArray) {
            dimension++
            componentType = componentType.componentType
        }
        return ReflectArray.newInstance(componentType, *IntArray(dimension))
    }

    private fun instantiate(type: Class<*>): Any? =
        try {
            val constructor = type.getDeclaredConstructor()
            constructor.isAccessible = true
            constructor.newInstance()
        } catch (_: NoSuchMethodException) {
            instantiateWithFirstConstructor(type)
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: SecurityException) {
            null
        }

    private fun instantiateWithFirstConstructor(type: Class<*>): Any? =
        try {
            val constructor = type.declaredConstructors.firstOrNull() ?: return null
            constructor.isAccessible = true
            val arguments = constructor.parameterTypes.map(::constructorArgumentValue).toTypedArray()
            constructor.newInstance(*arguments)
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: SecurityException) {
            null
        }

    private fun constructorArgumentValue(type: Class<*>): Any? =
        if (type.isPrimitive) {
            primitiveValue(type)
        } else {
            null
        }

    private object DefaultReturnInvocationHandler : InvocationHandler {
        override fun invoke(
            proxy: Any,
            method: Method,
            args: Array<out Any?>?,
        ): Any? =
            when {
                method.name == "equals" && method.parameterTypes.contentEquals(arrayOf(Any::class.java)) ->
                    proxy === args?.firstOrNull()
                method.name == "hashCode" && method.parameterTypes.isEmpty() ->
                    System.identityHashCode(proxy)
                method.name == "toString" && method.parameterTypes.isEmpty() ->
                    "${proxy.javaClass.interfaces.firstOrNull()?.name ?: "Proxy"}@${System.identityHashCode(proxy)}"
                else -> defaultValue(method.returnType)
            }
    }
}
