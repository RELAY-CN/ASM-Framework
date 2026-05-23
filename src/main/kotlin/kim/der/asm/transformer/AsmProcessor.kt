/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.transformer

import kim.der.asm.AsmRegistry
import kim.der.asm.data.AsmInfo
import org.objectweb.asm.tree.ClassNode

/**
 * ASM 转换失败。
 *
 * 该异常会在某个 ASM 应用失败时立即抛出，用于保留目标类、失败 ASM 类和原始异常。
 * 抛出该异常后，本次转换不会继续写出部分改写后的字节码。
 *
 * @param className 正在转换的目标类 internal name
 * @param asmClassName 失败的 ASM 类名
 * @param cause 原始失败原因
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
class AsmTransformException(
    val className: String,
    val asmClassName: String,
    cause: Throwable,
) : RuntimeException("Failed to apply asm $asmClassName to $className", cause)

/**
 * ASM 处理器。
 *
 * 负责按 [AsmRegistry] 的注册结果，为目标类收集匹配的 [AsmInfo] 并依次应用改写。
 * 任一 ASM 应用失败都会终止本次转换，避免输出部分转换后的类。
 *
 * 处理器实例不持有跨转换的可变读写状态，因此可被复用；实际 ASM 方法的副作用与线程安全仍由
 * ASM 实现方负责。
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
class AsmProcessor : AsmTransformer() {
    /**
     * 应用所有相关的 ASM 到目标类。
     *
     * 该方法会直接修改传入的 [classNode]。多个 ASM 的执行顺序由 [AsmRegistry.getForTarget] 返回顺序决定；
     * 任一 ASM 失败时立即抛出 [AsmTransformException]。
     *
     * @param className 类名（内部名称）
     * @param classNode 目标类节点
     * @return 如果至少一个改写生效则返回 true
     * @throws AsmTransformException ASM 应用失败时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    fun applyAsms(
        className: String,
        classNode: ClassNode,
    ): Boolean = applyAsms(className, classNode, AsmRegistry.getForTarget(className))

    private fun applyAsms(
        className: String,
        classNode: ClassNode,
        asms: List<AsmInfo>,
    ): Boolean {
        if (asms.isEmpty()) {
            return false
        }

        var transformed = false

        asms.forEach { asmInfo ->
            try {
                if (TargetClassContext(className, classNode, asmInfo).applyAsm()) {
                    transformed = true
                }
            } catch (throwable: Throwable) {
                throw AsmTransformException(className, asmInfo.asmClass.name, throwable)
            }
        }

        return transformed
    }

    /**
     * 转换目标类字节码。
     *
     * 当注册表中没有匹配 ASM，或匹配 ASM 均未产生实际改写时，会直接返回原始 [classfileBuffer]。
     * 当至少一个 ASM 生效时，会基于同一次读取产生的 `ClassReader` 写回字节码，保证 frame 计算上下文一致。
     *
     * @param className 类名（内部名称）
     * @param classfileBuffer 原始 classfile 字节码
     * @param loader 目标类加载器；可为 `null`
     * @return 转换后的字节码；未转换时返回原始字节码
     * @throws AsmTransformException ASM 应用失败时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun transform(
        className: String,
        classfileBuffer: ByteArray,
        loader: ClassLoader?,
    ): ByteArray {
        val asms = AsmRegistry.getForTarget(className)
        if (asms.isEmpty()) {
            return classfileBuffer
        }

        val readResult = readClassWithReader(className, classfileBuffer)
        val transformed = applyAsms(className, readResult.classNode, asms)

        return if (transformed) {
            writeClass(readResult.classNode, loader, readResult.classReader)
        } else {
            classfileBuffer
        }
    }

    /**
     * 判断目标类是否存在匹配 ASM。
     *
     * 该方法只查询 [AsmRegistry]，不会读取或解析 classfile 字节码。
     *
     * @param className 类名（内部名称）
     * @return 存在至少一个匹配 ASM 时返回 `true`
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    override fun shouldTransform(className: String): Boolean {
        // 检查是否有 ASM 针对这个类（包括精确匹配和路径匹配）
        return AsmRegistry.getForTarget(className).isNotEmpty()
    }
}
