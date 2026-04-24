/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.transformer

import kim.der.asm.AsmRegistry
import kim.der.asm.data.AsmInfo
import org.objectweb.asm.tree.ClassNode

/**
 * ASM 处理器。
 *
 * 负责按 [AsmRegistry] 的注册结果，为目标类收集匹配的 [AsmInfo] 并依次应用改写。
 * 单个 ASM 应用失败时会捕获异常并继续处理剩余条目（仅输出到 stderr）。
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
            } catch (e: Exception) {
                System.err.println("Error applying asm ${asmInfo.asmClass.name} to $className: ${e.message}")
                e.printStackTrace()
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

        val classNode = readClass(className, classfileBuffer, true)
        val transformed = applyAsms(className, classNode, asms)

        return if (transformed) {
            writeClass(classNode, loader)
        } else {
            classfileBuffer
        }
    }

    override fun shouldTransform(className: String): Boolean {
        // 检查是否有 ASM 针对这个类（包括精确匹配和路径匹配）
        return AsmRegistry.getForTarget(className).isNotEmpty()
    }
}

