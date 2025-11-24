/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.api.listener

/**
 * @author Dr (dr@der.kim)
 */
fun interface RedirectionListener {
    @Throws(Throwable::class)
    operator fun invoke(
        obj: Any,
        desc: String,
        vararg args: Any?,
    )

    companion object {
        const val METHOD_NAME = "invoke"
        const val METHOD_DESC = "(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)V"
    }
}
