/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.api.replace

fun interface RedirectionReplace {
    @Throws(Throwable::class)
    operator fun invoke(
        obj: Any,
        desc: String,
        type: Class<*>,
        vararg args: Any?,
    ): Any?

    companion object {
        fun of(value: Any?): RedirectionReplace = RedirectionReplace { _: Any, _: String, _: Class<*>, _: Array<out Any?> -> value }

        const val CAST_PREFIX = "<cast> "
        const val METHOD_NAME = "invoke"
        const val METHOD_SPACE_NAME = "invokeIgnore"
        const val METHOD_DESC = "(Ljava/lang/Object;Ljava/lang/String;" + "Ljava/lang/Class;[Ljava/lang/Object;)Ljava/lang/Object;"
    }
}
