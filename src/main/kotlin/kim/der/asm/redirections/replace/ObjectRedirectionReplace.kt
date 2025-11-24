/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.redirections.replace

import kim.der.asm.api.replace.RedirectionReplace
import kim.der.asm.api.replace.RedirectionReplaceManager
import kim.der.asm.utils.DescriptionUtil.getDesc
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.*
import java.lang.reflect.Array as ReflectArray

@Suppress("UNUSED")
class ObjectRedirectionReplace(
    private val manager: RedirectionReplaceManager,
) : RedirectionReplace {
    override operator fun invoke(
        obj: Any,
        desc: String,
        type: Class<*>,
        vararg args: Any?,
    ): Any? {
        var typePrivate = type

        return when {
            typePrivate.isInterface -> {
                return Proxy.newProxyInstance(
                    typePrivate.classLoader,
                    arrayOf(typePrivate),
                    ProxyRedirectionReplace(manager, getDesc(typePrivate)),
                )
            }
            typePrivate.isArray -> {
                var dimension = 0

                while (typePrivate.isArray) {
                    dimension++
                    typePrivate = typePrivate.componentType
                }

                val dimensions = IntArray(dimension)
                Arrays.fill(dimensions, 0)
                ReflectArray.newInstance(typePrivate, *dimensions)
            }
            Modifier.isAbstract(typePrivate.modifiers) -> {
                System.err.println("Can't return abstract class: $desc")
                null
            }
            else -> {
                try {
                    // 首先尝试无参构造函数
                    val constructor = typePrivate.getDeclaredConstructor()
                    constructor.isAccessible = true
                    constructor.newInstance()
                } catch (e: NoSuchMethodException) {
                    // 如果没有无参构造函数，尝试查找其他构造函数
                    val constructors = typePrivate.declaredConstructors
                    if (constructors.isNotEmpty()) {
                        // 尝试使用第一个构造函数，使用默认值填充参数
                        val constructor = constructors[0]
                        constructor.isAccessible = true
                        val paramTypes = constructor.parameterTypes
                        val args =
                            paramTypes
                                .map { paramType ->
                                    // 为每个参数提供默认值
                                    when {
                                        paramType == Boolean::class.javaPrimitiveType || paramType == Boolean::class.javaObjectType -> false
                                        paramType == Byte::class.javaPrimitiveType || paramType == Byte::class.javaObjectType -> 0.toByte()
                                        paramType == Short::class.javaPrimitiveType || paramType == Short::class.javaObjectType ->
                                            0
                                                .toShort()
                                        paramType == Int::class.javaPrimitiveType || paramType == Int::class.javaObjectType -> 0
                                        paramType == Long::class.javaPrimitiveType || paramType == Long::class.javaObjectType -> 0L
                                        paramType == Float::class.javaPrimitiveType || paramType == Float::class.javaObjectType -> 0.0f
                                        paramType == Double::class.javaPrimitiveType || paramType == Double::class.javaObjectType -> 0.0
                                        paramType == Char::class.javaPrimitiveType || paramType == Char::class.javaObjectType -> '\u0000'
                                        else -> null
                                    }
                                }.toTypedArray<Any?>()
                        constructor.newInstance(*args)
                    } else {
                        System.err.println("No constructor found for class: ${typePrivate.name}")
                        null
                    }
                } catch (e: SecurityException) {
                    e.printStackTrace()
                    null
                } catch (e: ReflectiveOperationException) {
                    e.printStackTrace()
                    null
                }
            }
        }
    }
}
