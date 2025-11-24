/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.transformer

import kim.der.asm.utils.transformer.SafeClassWriter
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode

/**
 * ASM 字节码转换器基类
 * 基于 ASM Tree API 实现字节码转换
 *
 * @author Dr (dr@der.kim)
 */
abstract class AsmTransformer {
    private var classReader: ClassReader? = null
    private var classNode: ClassNode? = null

    /**
     * 读取类字节码为 ClassNode
     *
     * @param className 类名（内部名称）
     * @param basicClass 原始字节码
     * @return ClassNode
     */
    protected fun readClass(
        className: String,
        basicClass: ByteArray,
    ): ClassNode = readClass(className, basicClass, true)

    /**
     * 读取类字节码为 ClassNode
     *
     * @param className 类名（内部名称）
     * @param basicClass 原始字节码
     * @param cacheReader 是否缓存 ClassReader 以便后续写入时使用
     * @return ClassNode
     */
    protected fun readClass(
        className: String,
        basicClass: ByteArray,
        cacheReader: Boolean,
    ): ClassNode {
        val reader = ClassReader(basicClass)
        if (cacheReader) {
            this.classReader = reader
        }

        val node = ClassNode()
        reader.accept(node, ClassReader.EXPAND_FRAMES)
        if (cacheReader) {
            this.classNode = node
        }
        return node
    }

    /**
     * 将 ClassNode 写入为字节码
     *
     * @param classNode ClassNode 要写入
     * @param loader 类加载器（可选）
     * @return 生成的字节码
     */
    protected fun writeClass(
        classNode: ClassNode,
        loader: ClassLoader? = null,
    ): ByteArray {
        val writer = if (this.classReader != null && this.classNode == classNode) {
            // 使用缓存的 ClassReader 优化
            SafeClassWriter(this.classReader, loader, ClassWriter.COMPUTE_FRAMES).also {
                this.classReader = null
                this.classNode = null
            }
        } else {
            // 使用 SafeClassWriter 处理类加载问题
            this.classNode = null
            this.classReader = null
            SafeClassWriter(null, loader, ClassWriter.COMPUTE_FRAMES)
        }
        classNode.accept(writer)
        return writer.toByteArray()
    }

    /**
     * 转换类字节码
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
     * 检查是否需要转换指定类
     *
     * @param className 类名（内部名称）
     * @return true 如果需要转换
     */
    open fun shouldTransform(className: String): Boolean = true
}
