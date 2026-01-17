/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.utils.transformer

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode
import java.io.IOException

/**
 * 安全的类方法读取器
 * 使用 ASM 字节码读取类的方法信息，而不是通过反射
 * 这样可以避免 NoClassDefFoundError，因为不需要加载方法签名中引用的类
 *
 * @author Dr (dr@der.kim)
 */
object SafeClassMethodReader {
    /**
     * 安全地读取类的所有方法
     *
     * @param clazz 要读取的类
     * @param loader 类加载器
     * @return 方法节点列表，如果读取失败返回空列表
     */
    fun readMethods(
        clazz: Class<*>,
        loader: ClassLoader?,
    ): List<MethodNode> {
        return try {
            val classReader = getClassReader(clazz, loader)
            val classNode = ClassNode()
            classReader.accept(classNode, ClassReader.SKIP_FRAMES)
            classNode.methods ?: emptyList()
        } catch (e: IOException) {
            System.err.println("Warning: Failed to read methods from ${clazz.name}: ${e.message}")
            emptyList()
        } catch (e: Throwable) {
            System.err.println("Warning: Unexpected error reading methods from ${clazz.name}: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * 获取类的 ClassReader
     *
     * @param clazz 要读取的类
     * @param loader 类加载器
     * @return ClassReader
     * @throws IOException 如果无法读取类文件
     */
    @Throws(IOException::class)
    private fun getClassReader(
        clazz: Class<*>,
        loader: ClassLoader?,
    ): ClassReader {
        val className = clazz.name.replace('.', '/')
        val resource = "$className.class"

        // 优先使用传入的 ClassLoader
        val effectiveLoader = loader ?: ClassLoader.getSystemClassLoader()

        val inputStream =
            effectiveLoader.getResourceAsStream(resource)
                ?: clazz.classLoader?.getResourceAsStream(resource)
                ?: ClassLoader.getSystemClassLoader().getResourceAsStream(resource)
                ?: throw IOException("Cannot find class file for ${clazz.name}")

        return inputStream.use { ClassReader(it) }
    }

    /**
     * 检查方法节点是否有指定的注解
     *
     * @param method 方法节点
     * @param annotationDesc 注解描述符，如 "Lkim/der/asm/api/annotation/AsmInject;"
     * @return true 如果方法有该注解
     */
    fun hasAnnotation(
        method: MethodNode,
        annotationDesc: String,
    ): Boolean {
        if (method.visibleAnnotations != null) {
            for (annotation in method.visibleAnnotations) {
                if (annotation.desc == annotationDesc) {
                    return true
                }
            }
        }
        if (method.invisibleAnnotations != null) {
            for (annotation in method.invisibleAnnotations) {
                if (annotation.desc == annotationDesc) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * 获取方法节点的注解值
     *
     * @param method 方法节点
     * @param annotationDesc 注解描述符
     * @param key 注解属性名
     * @return 注解值，如果不存在返回 null
     */
    fun getAnnotationValue(
        method: MethodNode,
        annotationDesc: String,
        key: String,
    ): Any? {
        if (method.visibleAnnotations != null) {
            for (annotation in method.visibleAnnotations) {
                if (annotation.desc == annotationDesc) {
                    if (annotation.values != null) {
                        for (i in annotation.values.indices step 2) {
                            if (annotation.values[i] == key) {
                                return annotation.values[i + 1]
                            }
                        }
                    }
                }
            }
        }
        if (method.invisibleAnnotations != null) {
            for (annotation in method.invisibleAnnotations) {
                if (annotation.desc == annotationDesc) {
                    if (annotation.values != null) {
                        for (i in annotation.values.indices step 2) {
                            if (annotation.values[i] == key) {
                                return annotation.values[i + 1]
                            }
                        }
                    }
                }
            }
        }
        return null
    }
}
