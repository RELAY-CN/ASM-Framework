package kim.der.asm.utils.transformer

import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 验证类型读取的资源开销、失败重试与转换生命周期隔离。 */
class SafeClassWriterTest {
    @Test
    fun readsEachHierarchyResourceOncePerWriterAndClosesStreams() {
        val loader = ResourceLoader(hierarchy(BASE_A))
        val writer = SafeClassWriter(null, loader, ClassWriter.COMPUTE_FRAMES)

        repeat(3) {
            assertEquals(BASE_A, commonSuperClass(writer, LEFT, RIGHT))
            assertEquals(BASE_A, commonSuperClass(writer, RIGHT, LEFT))
        }

        assertEquals(setOf(LEFT, RIGHT, BASE_A, OBJECT).map { "$it.class" }.toSet(), loader.reads.keys)
        assertTrue(loader.reads.values.all { it == 1 }, "每种类型只应打开一次：${loader.reads}")
        assertEquals(loader.reads, loader.closes)
        assertEquals(0, loader.classLoads)
    }

    @Test
    fun reusesReadersDuringRecursiveInterfaceResolution() {
        val root = "$PREFIX/Root"
        val middle = "$PREFIX/Middle"
        val loader = ResourceLoader(hierarchy(BASE_A).apply {
            put(root, typeBytes(root, interfaces = emptyArray(), isInterface = true))
            put(middle, typeBytes(middle, interfaces = arrayOf(root), isInterface = true))
            put(LEFT, typeBytes(LEFT, BASE_A, arrayOf(middle)))
        })
        val writer = SafeClassWriter(null, loader, ClassWriter.COMPUTE_FRAMES)

        repeat(2) {
            assertEquals(root, commonSuperClass(writer, root, LEFT))
            assertEquals(root, commonSuperClass(writer, LEFT, root))
            assertEquals(OBJECT, commonSuperClass(writer, root, RIGHT))
        }

        assertTrue(loader.reads.values.all { it == 1 }, "接口递归不应重复读取：${loader.reads}")
        assertEquals(loader.reads, loader.closes)
        assertEquals(0, loader.classLoads)
    }

    @Test
    fun keepsSameNamesInDifferentLoadersIndependent() {
        val loaderA = ResourceLoader(hierarchy(BASE_A))
        val loaderB = ResourceLoader(hierarchy(BASE_B))
        val writerA = SafeClassWriter(null, loaderA, ClassWriter.COMPUTE_FRAMES)
        val writerB = SafeClassWriter(null, loaderB, ClassWriter.COMPUTE_FRAMES)

        assertEquals(BASE_A, commonSuperClass(writerA, LEFT, RIGHT))
        assertEquals(BASE_B, commonSuperClass(writerB, LEFT, RIGHT))
        assertEquals(BASE_A, commonSuperClass(writerA, LEFT, RIGHT))
        assertTrue(loaderB.reads.containsKey("$BASE_B.class"))
    }

    @Test
    fun nextWriterReadsFreshResourcesEvenWhenLoaderIsReused() {
        val loader = ResourceLoader(hierarchy(BASE_A))
        val firstWriter = SafeClassWriter(null, loader, ClassWriter.COMPUTE_FRAMES)
        assertEquals(BASE_A, commonSuperClass(firstWriter, LEFT, RIGHT))

        loader.bytes.putAll(hierarchy(BASE_B))
        val secondWriter = SafeClassWriter(null, loader, ClassWriter.COMPUTE_FRAMES)
        assertEquals(BASE_B, commonSuperClass(secondWriter, LEFT, RIGHT))
        assertEquals(2, loader.reads["$LEFT.class"])
        assertEquals(2, loader.reads["$RIGHT.class"])
    }

    @Test
    fun missingResourcesFailAndCanBeRetriedWithoutNegativeCaching() {
        val loader = ResourceLoader(hierarchy(BASE_A).apply { remove(LEFT) })
        val writer = SafeClassWriter(null, loader, ClassWriter.COMPUTE_FRAMES)

        val failure = assertFailsWith<RuntimeException> { commonSuperClass(writer, LEFT, RIGHT) }
        assertTrue(failure.message.orEmpty().contains("Cannot create ClassReader for type $LEFT"))
        loader.bytes[LEFT] = typeBytes(LEFT, BASE_A)

        assertEquals(BASE_A, commonSuperClass(writer, LEFT, RIGHT))
        assertEquals(2, loader.reads["$LEFT.class"])
        assertEquals(1, loader.closes["$LEFT.class"])
    }

    @Test
    fun invalidClassfilesCloseTheirStreamAndAreNotCached() {
        val loader = ResourceLoader(hierarchy(BASE_A).apply { put(LEFT, byteArrayOf(1, 2, 3)) })
        val writer = SafeClassWriter(null, loader, ClassWriter.COMPUTE_FRAMES)

        assertFailsWith<IndexOutOfBoundsException> { commonSuperClass(writer, LEFT, RIGHT) }
        assertEquals(1, loader.closes["$LEFT.class"])
        loader.bytes[LEFT] = typeBytes(LEFT, BASE_A)

        assertEquals(BASE_A, commonSuperClass(writer, LEFT, RIGHT))
        assertEquals(2, loader.reads["$LEFT.class"])
        assertEquals(2, loader.closes["$LEFT.class"])
    }

    private fun commonSuperClass(writer: SafeClassWriter, first: String, second: String): String {
        // 只调用 ClassWriter 的受保护边界，不依赖缓存字段或其它实现细节。
        val method = SafeClassWriter::class.java.getDeclaredMethod(
            "getCommonSuperClass", String::class.java, String::class.java,
        ).apply { isAccessible = true }
        return try {
            method.invoke(writer, first, second) as String
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
    }

    private fun hierarchy(base: String): MutableMap<String, ByteArray> = mutableMapOf(
        OBJECT to ClassLoader.getSystemResourceAsStream("$OBJECT.class")!!.use { it.readBytes() },
        base to typeBytes(base),
        LEFT to typeBytes(LEFT, base),
        RIGHT to typeBytes(RIGHT, base),
    )

    private fun typeBytes(
        name: String,
        parent: String = OBJECT,
        interfaces: Array<String> = emptyArray(),
        isInterface: Boolean = false,
    ): ByteArray = ClassWriter(0).apply {
        val access = Opcodes.ACC_PUBLIC or if (isInterface) {
            Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT
        } else {
            Opcodes.ACC_SUPER
        }
        visit(Opcodes.V11, access, name, null, parent, interfaces)
        visitEnd()
    }.toByteArray()

    private class ResourceLoader(val bytes: MutableMap<String, ByteArray>) : ClassLoader(null) {
        val reads = mutableMapOf<String, Int>()
        val closes = mutableMapOf<String, Int>()
        var classLoads = 0

        override fun getResourceAsStream(name: String): InputStream? {
            reads[name] = reads.getOrDefault(name, 0) + 1
            val content = bytes[name.removeSuffix(".class")] ?: return null
            return object : ByteArrayInputStream(content) {
                override fun close() {
                    closes[name] = closes.getOrDefault(name, 0) + 1
                    super.close()
                }
            }
        }

        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            classLoads++
            error("frame 解析不应加载类型：$name")
        }
    }

    private companion object {
        const val PREFIX = "asm/writercachefixture"
        const val LEFT = "$PREFIX/Left"
        const val RIGHT = "$PREFIX/Right"
        const val BASE_A = "$PREFIX/BaseA"
        const val BASE_B = "$PREFIX/BaseB"
        const val OBJECT = "java/lang/Object"
    }
}
