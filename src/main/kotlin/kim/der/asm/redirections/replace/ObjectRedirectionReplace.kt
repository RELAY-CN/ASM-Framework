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

/**
 * 对象类型默认替换器。
 *
 * 该替换器用于为未显式指定替换逻辑的引用类型调用点生成兜底返回值：
 *
 * - 接口类型返回动态代理，并把代理方法继续委托给 [manager]
 * - 数组类型返回对应维度的空数组
 * - 抽象类无法直接构造时返回 `null`
 * - 普通类优先调用无参构造函数，缺失时尝试使用第一个构造函数和基础类型默认值构造实例
 *
 * @param manager 代理方法与嵌套重定向调用使用的替换管理器
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
@Suppress("UNUSED")
class ObjectRedirectionReplace(
    private val manager: RedirectionReplaceManager,
) : RedirectionReplace {
    /**
     * 生成目标引用类型的默认返回值。
     *
     * @param obj 原始调用所属对象；当前实现不使用该参数
     * @param desc 调用点描述符，仅用于诊断输出
     * @param type 目标返回类型
     * @param args 原调用参数；当前实现不使用该参数
     * @return 目标类型的兜底对象；无法构造时返回 `null`
     */
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
