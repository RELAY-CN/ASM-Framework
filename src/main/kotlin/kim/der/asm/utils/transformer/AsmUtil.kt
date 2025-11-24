/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.utils.transformer

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode

internal object AsmUtil {
    fun read(
        clazz: ByteArray?,
        vararg flags: Int,
    ): ClassNode {
        val result = ClassNode()
        val reader = ClassReader(clazz)
        reader.accept(result, toFlag(*flags))
        return result
    }

    fun write(
        loader: ClassLoader?,
        classNode: ClassNode,
        vararg flags: Int,
    ): ByteArray {
        val writer = SafeClassWriter(null, loader, toFlag(*flags))
        classNode.accept(writer)
        return writer.toByteArray()
    }

    private fun toFlag(vararg flags: Int): Int = flags.fold(0) { acc, flag -> acc or flag }
}
