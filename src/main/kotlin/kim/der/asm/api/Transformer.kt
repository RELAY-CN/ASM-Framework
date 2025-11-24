/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.api

import kim.der.asm.data.MethodTypeInfoValue
import org.objectweb.asm.tree.ClassNode

/**
 * 提供转换接口, 解析传入的 [ClassNode], 并修改 [Class]
 *
 * @author Dr (dr@der.kim)
 */
fun interface Transformer {
    fun transform(classNode: ClassNode) {
        transform(classNode, null)
    }

    fun transform(
        classNode: ClassNode,
        methodTypeInfoValueList: ArrayList<MethodTypeInfoValue>?,
    )
}
