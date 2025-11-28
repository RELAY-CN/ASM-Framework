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

    @JvmStatic
    fun fallback(
        type: Class<*>?,
        redirectionReplace: RedirectionReplace,
    ): RedirectionReplace = when (type) {
        Void.TYPE -> NULL
        Boolean::class.javaPrimitiveType -> BOOLEANF
        Byte::class.javaPrimitiveType -> BYTE
        Short::class.javaPrimitiveType -> SHORT
        Int::class.javaPrimitiveType -> INT
        Long::class.javaPrimitiveType -> LONG
        Float::class.javaPrimitiveType -> FLOAT
        Double::class.javaPrimitiveType -> DOUBLE
        Char::class.javaPrimitiveType -> CHAR
        String::class.java -> STRING
        CharSequence::class.java -> EQUALS
        else -> redirectionReplace
    }
}
