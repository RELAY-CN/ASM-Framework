/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.redirections.replace.def

import kim.der.asm.api.replace.RedirectionReplace

object BasicDataRedirections {
    @JvmField
    val NULL = RedirectionReplace.of(null)

    @JvmField
    val BOOLEANF = RedirectionReplace.of(false)

    @JvmField
    val BOOLEANT = RedirectionReplace.of(true)

    @JvmField
    val BYTE = RedirectionReplace.of(0.toByte())

    @JvmField
    val SHORT = RedirectionReplace.of(0.toShort())

    @JvmField
    val INT = RedirectionReplace.of(0)

    @JvmField
    val LONG = RedirectionReplace.of(0L)

    @JvmField
    val FLOAT = RedirectionReplace.of(0.0f)

    @JvmField
    val DOUBLE = RedirectionReplace.of(0.0)

    @JvmField
    val CHAR = RedirectionReplace.of('a')

    @JvmField
    val STRING = RedirectionReplace.of("")

    @JvmField
    val EQUALS = RedirectionReplace { _: Any, _: String, _: Class<*>, args: Array<out Any?> -> args[0] === args[1] }

    @JvmField
    val HASHCODE = RedirectionReplace { _: Any, _: String, _: Class<*>, args: Array<out Any?> -> System.identityHashCode(args[0]) }

    private val DEFAULTS =
        HashMap<Class<*>?, RedirectionReplace>().apply {
            this[Void.TYPE] = NULL
            this[Boolean::class.javaPrimitiveType] = BOOLEANF
            this[Byte::class.javaPrimitiveType] = BYTE
            this[Short::class.javaPrimitiveType] = SHORT
            this[Int::class.javaPrimitiveType] = INT
            this[Long::class.javaPrimitiveType] = LONG
            this[Float::class.javaPrimitiveType] = FLOAT
            this[Double::class.javaPrimitiveType] = DOUBLE
            this[Char::class.javaPrimitiveType] = CHAR
            this[String::class.java] = STRING
            this[CharSequence::class.java] = STRING
        }

    @JvmStatic
    fun fallback(
        type: Class<*>?,
        redirectionReplace: RedirectionReplace,
    ): RedirectionReplace = DEFAULTS.getOrDefault(type, redirectionReplace)

    internal object ValueClass {
        object NullValue : RedirectionReplace {
            override fun invoke(
                obj: Any,
                desc: String,
                type: Class<*>,
                args: Array<out Any?>,
            ): Any? = null
        }

        object BooleanTrueValue : RedirectionReplace {
            override fun invoke(
                obj: Any,
                desc: String,
                type: Class<*>,
                args: Array<out Any?>,
            ): Boolean = true
        }

        object BooleanFalseValue : RedirectionReplace {
            override fun invoke(
                obj: Any,
                desc: String,
                type: Class<*>,
                args: Array<out Any?>,
            ): Boolean = false
        }

        object ByteValue : RedirectionReplace {
            override fun invoke(
                obj: Any,
                desc: String,
                type: Class<*>,
                args: Array<out Any?>,
            ): Byte = 0
        }

        object ShortValue : RedirectionReplace {
            override fun invoke(
                obj: Any,
                desc: String,
                type: Class<*>,
                args: Array<out Any?>,
            ): Short = 0
        }

        object IntValue : RedirectionReplace {
            override fun invoke(
                obj: Any,
                desc: String,
                type: Class<*>,
                args: Array<out Any?>,
            ): Int = 0
        }

        object LongValue : RedirectionReplace {
            override fun invoke(
                obj: Any,
                desc: String,
                type: Class<*>,
                args: Array<out Any?>,
            ): Long = 0
        }

        object FloatValue : RedirectionReplace {
            override fun invoke(
                obj: Any,
                desc: String,
                type: Class<*>,
                args: Array<out Any?>,
            ): Float = 0.0f
        }

        object DoubleValue : RedirectionReplace {
            override fun invoke(
                obj: Any,
                desc: String,
                type: Class<*>,
                args: Array<out Any?>,
            ): Double = 0.0
        }

        object CharValue : RedirectionReplace {
            override fun invoke(
                obj: Any,
                desc: String,
                type: Class<*>,
                args: Array<out Any?>,
            ): Char = 'D'
        }

        object StringValue : RedirectionReplace {
            override fun invoke(
                obj: Any,
                desc: String,
                type: Class<*>,
                args: Array<out Any?>,
            ): String = ""
        }
    }
}
