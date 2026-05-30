/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.utils.transformer

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode
import java.io.IOException

/**
 * 安全的类方法读取器。
 *
 * 使用 ASM 直接读取 classfile 中的方法信息，而不是通过反射解析方法签名。
 * 这样可以避免扫描阶段加载方法签名中的依赖类型，从而降低 [NoClassDefFoundError] 风险。
 *
 * @author Dr (dr@der.kim)
 * @date 2025-11-24
 */
object SafeClassMethodReader {
    /**
     * 安全读取类的所有方法节点。
     *
     * @param clazz 要读取的类
     * @param loader 读取 classfile 资源时优先使用的类加载器；可为 `null`
     * @return 方法节点列表；读取失败时返回空列表
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
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
     * 获取类的 [ClassReader]。
     *
     * 资源查找顺序为传入类加载器、类自身类加载器、系统类加载器。
     *
     * @param clazz 要读取的类
     * @param loader 读取 classfile 资源时优先使用的类加载器；可为 `null`
     * @return [clazz] 对应的 [ClassReader]
     * @throws IOException 如果无法读取类文件
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
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
     * 检查方法节点是否带有指定注解。
     *
     * @param method 方法节点
     * @param annotationDesc 注解描述符，例如 `Lkim/der/asm/api/annotation/AsmInject;`
     * @return 方法存在可见或不可见的指定注解时返回 `true`
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
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
     * 读取方法节点中指定注解的属性值。
     *
     * @param method 方法节点
     * @param annotationDesc 注解描述符
     * @param key 注解属性名
     * @return 注解属性值；注解或属性不存在时返回 `null`
     *
     * @author Dr (dr@der.kim)
     * @date 2025-11-24
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
