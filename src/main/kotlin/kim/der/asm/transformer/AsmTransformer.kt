/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.transformer

import kim.der.asm.utils.transformer.SafeClassWriter
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode

/**
 * ASM 字节码转换器基类。
 *
 * 基于 ASM Tree API 将 classfile 字节码读入 [ClassNode] 并输出改写后的字节码。
 * 读写上下文由单次转换持有，避免复用同一个转换器实例时出现跨线程缓存污染。
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
abstract class AsmTransformer {
    /**
     * 单次类读取结果。
     */
    protected data class ReadClassResult(
        val classNode: ClassNode,
        val classReader: ClassReader,
    )

    /**
     * 读取类字节码为 [ClassNode]。
     *
     * @param className 类名（内部名称）
     * @param basicClass 原始字节码
     * @return 解析后的 [ClassNode]
     */
    protected fun readClass(
        className: String,
        basicClass: ByteArray,
    ): ClassNode = readClassWithReader(className, basicClass).classNode

    /**
     * 读取类字节码为 [ClassNode]，同时返回对应 [ClassReader] 供同一次写入复用。
     *
     * @param className 类名（内部名称）
     * @param basicClass 原始字节码
     * @return 解析结果
     */
    protected fun readClassWithReader(
        className: String,
        basicClass: ByteArray,
    ): ReadClassResult {
        val reader = ClassReader(basicClass)
        val node = ClassNode()
        reader.accept(node, ClassReader.EXPAND_FRAMES)
        return ReadClassResult(node, reader)
    }

    /**
     * 将 [ClassNode] 写入为字节码。
     *
     * @param classNode ClassNode 要写入
     * @param loader 类加载器（可选）
     * @return 生成的字节码
     */
    protected fun writeClass(
        classNode: ClassNode,
        loader: ClassLoader? = null,
    ): ByteArray = writeClass(classNode, loader, null)

    /**
     * 将 [ClassNode] 写入为字节码，可复用本次读取得到的 [ClassReader]。
     *
     * @param classNode ClassNode 要写入
     * @param loader 类加载器（可选）
     * @param classReader 同一次读取产生的 ClassReader（可选）
     * @return 生成的字节码
     */
    protected fun writeClass(
        classNode: ClassNode,
        loader: ClassLoader? = null,
        classReader: ClassReader? = null,
    ): ByteArray {
        val writer = SafeClassWriter(classReader, loader, ClassWriter.COMPUTE_FRAMES)
        classNode.accept(writer)
        return writer.toByteArray()
    }

    /**
     * 转换类字节码。
     *
     * @param className 类名（内部名称）
     * @param classfileBuffer 原始字节码
     * @param loader 类加载器（可选）
     * @return 转换后的字节码，如果未转换则返回原字节码
     */
    abstract fun transform(
        className: String,
        classfileBuffer: ByteArray,
        loader: ClassLoader?,
    ): ByteArray

    /**
     * 检查是否需要转换指定类。
     *
     * @param className 类名（内部名称）
     * @return 如果需要转换则返回 true
     */
    open fun shouldTransform(className: String): Boolean = true
}
