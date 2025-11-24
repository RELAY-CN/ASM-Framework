/*
 * Copyright 2020-2024 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.transformer

import kim.der.asm.AsmRegistry
import org.objectweb.asm.tree.ClassNode

/**
 * ASM 处理器
 * 负责应用所有注册的 ASM 到目标类
 *
 * @author Dr (dr@der.kim)
 */
class AsmProcessor : AsmTransformer() {
    /**
     * 应用所有相关的 ASM 到目标类
     *
     * @param className 类名（内部名称）
     * @param classNode 目标类节点
     * @return true 如果进行了转换
     */
    fun applyAsms(
        className: String,
        classNode: ClassNode,
    ): Boolean {
        val asms = AsmRegistry.getForTarget(className)
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
        if (!shouldTransform(className)) {
            return classfileBuffer
        }

        val classNode = readClass(className, classfileBuffer, true)
        val transformed = applyAsms(className, classNode)

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
