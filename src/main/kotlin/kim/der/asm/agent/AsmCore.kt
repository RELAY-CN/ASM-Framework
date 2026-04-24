/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.agent

import java.security.ProtectionDomain

/**
 * ASM 改写入口门面。
 *
 * 该类通过静态持有 [AsmBootstrap] 实例，将 `ClassFileTransformer.transform` 复用为可被普通类加载流程调用的工具方法。
 * 当 agent 尚未初始化时，[transform] 系列方法会直接返回原始字节码。
 *
 * @author Dr (dr@der.kim)
 * @date 2025-02-09
 */
open class AsmCore {
    protected val setAgent = { agent: AsmBootstrap -> Companion.agent = agent }

    companion object {
        private var agent: AsmBootstrap? = null

        /**
         * 对指定类字节码执行已注册的 ASM 改写。
         *
         * 该重载适用于普通类加载路径：不提供重定义与保护域信息。
         *
         * @param loader 目标类的 [ClassLoader]；可能为 `null`
         * @param className 目标类 internal name，例如 `"java/util/List"`
         * @param classfileBuffer 原始 classfile 字节码
         * @return 转换后的字节码；若未启用 agent 或无需改写则返回原始字节码
         */
        @JvmStatic
        fun transform(
            loader: ClassLoader?,
            className: String,
            classfileBuffer: ByteArray,
        ): ByteArray = transform(loader, className, null, null, classfileBuffer)

        /**
         * 对指定类字节码执行已注册的 ASM 改写。
         *
         * @param loader 目标类的 [ClassLoader]；可能为 `null`
         * @param className 目标类 internal name，例如 `"java/util/List"`
         * @param classBeingRedefined 触发重定义/重转换时为原 class；普通加载时为 `null`
         * @param protectionDomain 保护域信息；可能为 `null`
         * @param classfileBuffer 原始 classfile 字节码
         * @return 转换后的字节码；若未启用 agent 或无需改写则返回原始字节码
         */
        @JvmStatic
        fun transform(
            loader: ClassLoader?,
            className: String,
            classBeingRedefined: Class<*>?,
            protectionDomain: ProtectionDomain?,
            classfileBuffer: ByteArray,
        ): ByteArray = agent?.transform(loader, className, classBeingRedefined, protectionDomain, classfileBuffer) ?: classfileBuffer
    }
}
