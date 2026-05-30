/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.utils.transformer

import kim.der.asm.utils.DescriptionUtil.ObjectClassName
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.IOException

/**
 * 安全的 ClassWriter。
 *
 * 通过读取 classfile 字节码计算两个类型的公共父类，避免在 frame 计算阶段直接加载目标类型及其依赖。
 *
 * @author Eric Bruneton
 */
@Suppress("UNUSED")
internal class SafeClassWriter(
    cr: ClassReader?,
    loader: ClassLoader?,
    flags: Int,
) : ClassWriter(cr, flags) {
    private val loader = loader ?: ClassLoader.getSystemClassLoader()

    override fun getCommonSuperClass(
        type1: String,
        type2: String,
    ): String {
        try {
            val info1: ClassReader = typeInfo(type1)
            val info2: ClassReader = typeInfo(type2)
            if (info1.access and Opcodes.ACC_INTERFACE != 0) {
                return if (typeImplements(type2, info2, type1)) {
                    type1
                } else {
                    ObjectClassName
                }
            }
            if (info2.access and Opcodes.ACC_INTERFACE != 0) {
                return if (typeImplements(type1, info1, type2)) {
                    type2
                } else {
                    ObjectClassName
                }
            }
            val b1 = typeAncestors(type1, info1)
            val b2 = typeAncestors(type2, info2)
            var result = ObjectClassName
            var end1 = b1.length
            var end2 = b2.length
            while (true) {
                val start1 = b1.lastIndexOf(";", end1 - 1)
                val start2 = b2.lastIndexOf(";", end2 - 1)
                if (start1 != -1 && start2 != -1 && end1 - start1 == end2 - start2) {
                    val p1 = b1.substring(start1 + 1, end1)
                    val p2 = b2.substring(start2 + 1, end2)
                    if (p1 == p2) {
                        result = p1
                        end1 = start1
                        end2 = start2
                    } else {
                        return result
                    }
                } else {
                    return result
                }
            }
        } catch (e: IOException) {
            throw RuntimeException(e.toString())
        }
    }

    /**
     * 读取指定类型的继承链。
     *
     * 返回值使用 `;` 分隔 internal name，格式为 `;type1;type2...;typeN`。`type1` 是传入类型，
     * `typeN` 是 `java/lang/Object` 的直接子类；若传入类型本身就是 `java/lang/Object`，返回空串。
     *
     * @param typeIn 类或接口的 internal name
     * @param infoIn [typeIn] 对应的 [ClassReader]
     * @return 包含继承链 internal name 的字符串构建器
     * @throws IOException 当指定类型或其父类字节码无法读取时抛出
     */
    @Throws(IOException::class)
    private fun typeAncestors(
        typeIn: String,
        infoIn: ClassReader,
    ): StringBuilder {
        var type = typeIn
        var info: ClassReader = infoIn
        val b = StringBuilder()
        while (ObjectClassName != type) {
            b.append(';').append(type)
            type = info.superName
            info = typeInfo(type)
        }
        return b
    }

    /**
     * 判断指定类型是否实现目标接口。
     *
     * 会递归检查当前类型声明的接口、接口继承链以及父类继承链。
     *
     * @param typeIn 类或接口的 internal name
     * @param infoIn [typeIn] 对应的 [ClassReader]
     * @param itf 目标接口的 internal name
     * @return 直接或间接实现 [itf] 时返回 `true`
     * @throws IOException 当指定类型、接口或父类字节码无法读取时抛出
     */
    @Throws(IOException::class)
    private fun typeImplements(
        typeIn: String,
        infoIn: ClassReader,
        itf: String,
    ): Boolean {
        var type = typeIn
        var info: ClassReader = infoIn
        while (ObjectClassName != type) {
            val itfs: Array<String> = info.interfaces
            for (i in itfs.indices) {
                if (itfs[i] == itf) {
                    return true
                }
            }
            for (i in itfs.indices) {
                if (typeImplements(itfs[i], typeInfo(itfs[i]), itf)) {
                    return true
                }
            }
            type = info.superName
            info = typeInfo(type)
        }
        return false
    }

    /**
     * 读取指定类型的 [ClassReader]。
     *
     * 该方法先使用当前 writer 的类加载器读取资源，再回退到系统类加载器。
     *
     * @param type 类或接口的 internal name
     * @return [type] 对应的 [ClassReader]
     * @throws IOException 当字节码资源无法读取时抛出
     */
    @Throws(IOException::class)
    private fun typeInfo(type: String): ClassReader {
        val resource = "$type.class"
        val inputStream =
            this.loader.getResourceAsStream(resource) ?: ClassLoader
                .getSystemClassLoader()
                .getResourceAsStream(resource) ?: throw IOException("Cannot create ClassReader for type $type")
        return inputStream.use {
            ClassReader(it)
        }
    }
}
