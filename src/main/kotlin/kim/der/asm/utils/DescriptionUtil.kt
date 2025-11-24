/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */
package kim.der.asm.utils

import org.objectweb.asm.Type
import java.lang.reflect.Method

/**
 * Basically [Type.getDescriptor] since that class might not be
 * available at Runtime.
 */
@Suppress("UNUSED")
object DescriptionUtil {
    private val PRIMITIVES: MutableMap<Class<*>?, String> = HashMap()
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

    @JvmStatic
    fun getDesc(method: Method): String {
        val desc = StringBuilder(method.name).append("(")
        for (parameter in method.parameterTypes) {
            putDesc(parameter, desc)
        }
        putDesc(method.returnType, desc.append(")"))
        return desc.toString()
    }

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
