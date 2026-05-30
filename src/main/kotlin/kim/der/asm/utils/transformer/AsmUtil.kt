/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.utils.transformer

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode

/**
 * ASM Tree 读写工具。
 *
 * 该工具封装 [ClassReader] 与 [SafeClassWriter] 的常用调用，供 agent 转换流程在读取和写回 classfile 时复用。
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
internal object AsmUtil {
    /**
     * 将 classfile 字节码读取为 [ClassNode]。
     *
     * @param clazz 原始 classfile 字节码
     * @param flags 传给 [ClassReader.accept] 的读取标志
     * @return 读取后的类节点
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    fun read(
        clazz: ByteArray?,
        vararg flags: Int,
    ): ClassNode {
        val result = ClassNode()
        val reader = ClassReader(clazz)
        reader.accept(result, toFlag(*flags))
        return result
    }

    /**
     * 将 [ClassNode] 写回 classfile 字节码。
     *
     * @param loader frame 计算时用于解析类型层级的类加载器；可为 `null`
     * @param classNode 待写回的类节点
     * @param flags 传给 [SafeClassWriter] 的写入标志
     * @return 写回后的 classfile 字节码
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    fun write(
        loader: ClassLoader?,
        classNode: ClassNode,
        vararg flags: Int,
    ): ByteArray {
        val writer = SafeClassWriter(null, loader, toFlag(*flags))
        classNode.accept(writer)
        return writer.toByteArray()
    }

    /**
     * 合并 ASM 读写标志。
     */
    private fun toFlag(vararg flags: Int): Int = flags.fold(0) { acc, flag -> acc or flag }
}
