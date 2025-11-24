/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.agent

import java.security.ProtectionDomain

open class AsmCore {
    protected val setAgent = { agent: AsmBootstrap -> Companion.agent = agent }

    companion object {
        private var agent: AsmBootstrap? = null

        @JvmStatic
        fun transform(
            loader: ClassLoader?,
            className: String,
            classfileBuffer: ByteArray,
        ): ByteArray = transform(loader, className, null, null, classfileBuffer)

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
