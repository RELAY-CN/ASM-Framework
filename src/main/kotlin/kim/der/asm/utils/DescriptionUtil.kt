/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */
package kim.der.asm.utils

import org.objectweb.asm.Type
import java.lang.reflect.Method

/**
 * JVM 描述符工具。
 *
 * 该工具提供与 [Type.getDescriptor] 等价的轻量描述符生成能力，供运行期桥接代码在不直接依赖 ASM `Type`
 * 的场景下生成方法和类型描述符。
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
@Suppress("UNUSED")
object DescriptionUtil {
    private val PRIMITIVES: MutableMap<Class<*>?, String> = HashMap()

    /**
     * `java.lang.Object` 的 JVM internal name。
     */
    const val ObjectClassName = "java/lang/Object"

    init {
        PRIMITIVES[Boolean::class.javaPrimitiveType] = "Z"
        PRIMITIVES[Byte::class.javaPrimitiveType] = "B"
        PRIMITIVES[Short::class.javaPrimitiveType] = "S"
        PRIMITIVES[Int::class.javaPrimitiveType] = "I"
        PRIMITIVES[Long::class.javaPrimitiveType] = "J"
        PRIMITIVES[Float::class.javaPrimitiveType] = "F"
        PRIMITIVES[Double::class.javaPrimitiveType] = "D"
        PRIMITIVES[Char::class.javaPrimitiveType] = "C"
        PRIMITIVES[Void.TYPE] = "V"
    }

    /**
     * 生成反射方法的调用点描述。
     *
     * 返回值格式为 `methodName(paramDesc)returnDesc`，例如 `valueOf(I)Ljava/lang/String;`。
     *
     * @param method 反射方法
     * @return 带方法名的 JVM 方法描述
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    @JvmStatic
    fun getDesc(method: Method): String {
        val desc = StringBuilder(method.name).append("(")
        for (parameter in method.parameterTypes) {
            putDesc(parameter, desc)
        }
        putDesc(method.returnType, desc.append(")"))
        return desc.toString()
    }

    /**
     * 生成 Java 类型的 JVM 类型描述符。
     *
     * 基础类型返回单字符描述符，引用类型返回 `Linternal/name;`，数组类型会保留对应数量的 `[` 前缀。
     *
     * @param type Java 类型
     * @return JVM 类型描述符
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    @JvmStatic
    fun getDesc(type: Class<*>): String {
        val result = StringBuilder()
        putDesc(type, result)
        return result.toString()
    }

    private fun putDesc(
        typeIn: Class<*>,
        builder: StringBuilder,
    ) {
        var type = typeIn
        while (type.isArray) {
            builder.append("[")
            type = type.componentType
        }
        val primitive = PRIMITIVES[type]
        if (primitive == null) {
            builder.append("L")
            val name = type.name
            for (element in name) {
                builder.append(if (element == '.') '/' else element)
            }
            builder.append(";")
        } else {
            builder.append(primitive)
        }
    }
}
