/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.agent

import kim.der.asm.transformer.AsmProcessor
import kim.der.asm.utils.transformer.AsmUtil
import org.objectweb.asm.ClassWriter
import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.IllegalClassFormatException
import java.lang.instrument.Instrumentation
import java.security.ProtectionDomain

/**
 * ASM Java Agent 启动入口。
 *
 * 该 agent 作为 [ClassFileTransformer] 挂载到 JVM 类加载链路中，在满足条件时对类字节码应用 ASM 改写。
 * 改写集合与匹配逻辑由 [AsmProcessor] 与 [kim.der.asm.AsmRegistry] 共同决定。
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
@Suppress("UNUSED")
class AsmBootstrap :
    AsmCore(),
    ClassFileTransformer {
    private val asmProcessor: AsmProcessor = AsmProcessor()

    init {
        setAgent(this)
    }

    /**
     * 对目标类字节码执行 ASM 改写。
     *
     * 当 [AsmProcessor.shouldTransform] 返回 `true` 时读取 `classfileBuffer` 为 ASM Tree 的 [org.objectweb.asm.tree.ClassNode]，
     * 并应用所有匹配的改写；若没有任何改写生效，则返回原始字节码以避免不必要的重写。
     * 当前实现只使用 [loader]、[className] 与 [classfileBuffer]，重定义类与保护域参数仅保留 Java agent 契约。
     *
     * @param loader 定义该类的 [ClassLoader]；当使用 bootstrap loader 时可能为 `null`
     * @param className 目标类 internal name，例如 `"java/util/List"`
     * @param classBeingRedefined 触发重定义/重转换时为原 class；普通加载时为 `null`
     * @param protectionDomain 保护域信息；可能为 `null`
     * @param classfileBuffer 原始 classfile 字节码；不得修改入参数组内容
     * @return 转换后的字节码；若不需要转换则返回原始字节码
     * @throws IllegalClassFormatException 当输入不是合法 classfile 时抛出
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
     */
    @Throws(IllegalClassFormatException::class)
    override fun transform(
        loader: ClassLoader?,
        className: String,
        classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain?,
        classfileBuffer: ByteArray,
    ): ByteArray {
        // 检查是否需要应用 ASM
        if (!asmProcessor.shouldTransform(className)) {
            return classfileBuffer
        }

        val node = AsmUtil.read(classfileBuffer)
        return if (asmProcessor.applyAsms(className, node)) {
            AsmUtil.write(loader, node, ClassWriter.COMPUTE_FRAMES)
        } else {
            classfileBuffer
        }
    }

    companion object {
        /**
         * JVM agent main 入口。
         *
         * 通过 [Instrumentation.addTransformer] 注册 [AsmBootstrap] 实例。
         *
         * @param instrumentation JVM 注入的 instrumentation 实例
         *
         * @author Dr (dr@der.kim)
         * @date 2025-11-24
         */
        @JvmStatic
        fun agentmain(instrumentation: Instrumentation) {
            instrumentation.addTransformer(AsmBootstrap())
        }
    }
}

