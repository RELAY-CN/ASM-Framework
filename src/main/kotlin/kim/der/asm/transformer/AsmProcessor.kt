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
 * @param className 正在转换的目标类 internal name
 * @param asmClassName 失败的 ASM 类名
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
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
class AsmProcessor : AsmTransformer() {
    /**
     * 应用所有相关的 ASM 到目标类。
     *
     * @param className 类名（内部名称）
     * @param classNode 目标类节点
     * @return 如果至少一个改写生效则返回 true
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

    override fun shouldTransform(className: String): Boolean {
        // 检查是否有 ASM 针对这个类（包括精确匹配和路径匹配）
        return AsmRegistry.getForTarget(className).isNotEmpty()
    }
}
