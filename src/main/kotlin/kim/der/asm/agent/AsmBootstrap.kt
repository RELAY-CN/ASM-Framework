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
 * Java Agent 用于应用 ASM 转换
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
     * Transforms the given class file and returns a new replacement class file.
     * This method is invoked when the {@link Module} bearing {@link
     * ClassFileTransformer#transform(Module,ClassLoader,String,Class,ProtectionDomain,byte[])
     * transform} is not overridden.
     *
     * @param loader                the defining loader of the class to be transformed,
     *                              may be {@code null} if the bootstrap loader
     * @param className             the name of the class in the internal form of fully
     *                              qualified class and interface names as defined in
     *                              <i>The Java Virtual Machine Specification</i>.
     *                              For example, <code>"java/util/List"</code>.
     * @param classBeingRedefined   if this is triggered by a redefine or retransform,
     *                              the class being redefined or retransformed;
     *                              if this is a class load, {@code null}
     * @param protectionDomain      the protection domain of the class being defined or redefined
     * @param classfileBuffer       the input byte buffer in class file format - must not be modified
     *
     * @throws IllegalClassFormatException
     *         if the input does not represent a well-formed class file
     * @return a well-formed class file buffer (the result of the transform),
     *         or {@code null} if no transform is performed
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
        @JvmStatic
        fun agentmain(instrumentation: Instrumentation) {
            instrumentation.addTransformer(AsmBootstrap())
        }
    }
}
