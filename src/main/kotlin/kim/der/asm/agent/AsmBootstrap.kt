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
 * 启动前请先通过 [kim.der.asm.AsmRegistry] 或 [kim.der.asm.AsmScanner] 注册 Mixin；
 * 未注册任何目标时，[transform] 会直接返回原始字节码。
 *
 * JVM 标准入口：
 * - `-javaagent:ASM-Framework.jar` 走 [premain]
 * - 运行期 `VirtualMachine.attach` 走 [agentmain]
 *
 * JAR Manifest 应声明 `Premain-Class` / `Agent-Class` 为 `kim.der.asm.agent.AsmBootstrap`。
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
         * JVM `-javaagent` 启动入口。
         *
         * 在应用 `main` 之前注册 transformer。当前实现忽略 [agentArgs]；
         * Mixin 仍需由宿主在 agent 加载后、目标类加载前完成注册或扫描。
         *
         * @param agentArgs `-javaagent` 等号后的可选参数；当前未使用，可为 `null`
         * @param instrumentation JVM 注入的 instrumentation 实例
         *
         * @author Dr (dr@der.kim)
         * @date 2026-07-09
         */
        @JvmStatic
        fun premain(
            agentArgs: String?,
            instrumentation: Instrumentation,
        ) {
            install(instrumentation)
        }

        /**
         * JVM 运行期 attach agent 入口。
         *
         * 通过 [Instrumentation.addTransformer] 注册 [AsmBootstrap] 实例，并允许后续 retransform。
         * 当前实现忽略 [agentArgs]。
         *
         * @param agentArgs attach 时传入的可选参数；当前未使用，可为 `null`
         * @param instrumentation JVM 注入的 instrumentation 实例
         *
         * @author Dr (dr@der.kim)
         * @date 2026-07-09
         */
        @JvmStatic
        fun agentmain(
            agentArgs: String?,
            instrumentation: Instrumentation,
        ) {
            install(instrumentation)
        }

        /**
         * 兼容旧的单参数 agent 安装入口。
         *
         * JVM 标准 agent 入口需要 `(String, Instrumentation)`；该方法仅供宿主代码直接调用安装 transformer。
         *
         * @param instrumentation JVM 注入的 instrumentation 实例
         *
         * @author Dr (dr@der.kim)
         * @date 2025-11-24
         */
        @JvmStatic
        @Deprecated(
            message = "请使用 agentmain(String?, Instrumentation) 或 premain(String?, Instrumentation)",
            replaceWith = ReplaceWith("agentmain(null, instrumentation)"),
        )
        fun agentmain(instrumentation: Instrumentation) {
            install(instrumentation)
        }

        /**
         * 安装 transformer，并允许后续 retransform 已加载类。
         */
        private fun install(instrumentation: Instrumentation) {
            instrumentation.addTransformer(AsmBootstrap(), true)
        }
    }
}
