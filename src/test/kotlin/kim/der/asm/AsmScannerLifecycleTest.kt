/*
 * Copyright 2020-2026 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm

import kim.der.asm.api.annotation.AsmInject
import kim.der.asm.api.annotation.AsmMixin
import kim.der.asm.api.annotation.Copy
import kim.der.asm.api.annotation.Overwrite
import kim.der.asm.transformer.AsmProcessor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AsmScannerLifecycleTest {
    @TempDir
    lateinit var directory: Path

    @BeforeEach
    @AfterEach
    fun clearRegistry() {
        AsmRegistry.clear()
    }

    @Test
    fun registeredJarLoaderClosesWhenRegistryClears() {
        assertNull(javaClass.classLoader.getResource("$MIXIN.class"))
        val jar = createJar(mixinBytes(42))
        val result = AsmScanner.scanJarWithResult(jar.toFile(), "lifecycle.external")

        assertEquals(listOf(MIXIN.replace('/', '.')), result.registeredClasses)
        assertTrue(result.failures.isEmpty(), result.failures.toString())
        val info = AsmRegistry.getForTarget(TARGET).single()

        val target = transformAndLoad()
        assertEquals(42, target.getMethod("value").invoke(null))
        assertEquals(21, target.getMethod("copiedValue").invoke(null))
        assertEquals(1, target.getMethod("inlineValue").invoke(null))
        assertEquals(1, target.getField("touches").getInt(null))

        AsmRegistry.clear()
        assertNull(info.asmClass.classLoader.getResourceAsStream("$MIXIN.class"))
        // 注册生命周期结束后必须释放句柄，Windows 下也应允许删除原 JAR。
        Files.delete(jar)
    }

    @Test
    fun parentDefinedMixinDoesNotUseShadowedJarBytecode() {
        val parentBytes = mixinBytes(7)
        val parent = object : ClassLoader(javaClass.classLoader) {
            override fun findClass(name: String): Class<*> {
                if (name == MIXIN.replace('/', '.')) {
                    return defineClass(name, parentBytes, 0, parentBytes.size)
                }
                return super.findClass(name)
            }

            override fun getResourceAsStream(name: String): InputStream? =
                if (name == "$MIXIN.class") ByteArrayInputStream(parentBytes) else super.getResourceAsStream(name)
        }
        val jar = createJar(mixinBytes(42))
        val result = scanWithParent(jar, parent)

        assertTrue(result.failures.isEmpty(), result.failures.toString())
        assertSame(parent, AsmRegistry.getForTarget(TARGET).single().asmClass.classLoader)
        assertEquals(0, scannerLoaderCount())
        assertEquals(7, transformAndLoad().getMethod("value").invoke(null))
        Files.delete(jar)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "lifecycle.external"])
    fun multiReleaseJarUsesRuntimeSelectedBytecode(packageName: String) {
        val jar = createJar(mixinBytes(42), mixinBytes(7))
        val result = AsmScanner.scanJarWithResult(jar.toFile(), packageName)

        assertTrue(result.failures.isEmpty(), result.failures.toString())
        assertEquals(listOf(MIXIN.replace('/', '.')), result.registeredClasses)
        val mixin = AsmRegistry.getForTarget(TARGET).single().asmClass
        assertEquals(7, mixin.getMethod("value").invoke(null))
        assertEquals(7, transformAndLoad().getMethod("value").invoke(null))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "lifecycle.external"])
    fun multiReleaseJarFindsVersionOnlyMixin(packageName: String) {
        val jar = createJar(null, mixinBytes(7))
        val result = AsmScanner.scanJarWithResult(jar.toFile(), packageName)

        assertTrue(result.failures.isEmpty(), result.failures.toString())
        assertEquals(listOf(MIXIN.replace('/', '.')), result.registeredClasses)
        assertEquals(7, transformAndLoad().getMethod("value").invoke(null))
    }

    @Test
    fun multiReleaseJarIgnoresFutureVersions() {
        val futureVersion = Runtime.version().feature() + 1
        val jar = createJar(
            mixinBytes(42), mixinBytes(7),
            extraClasses = mapOf(
                "META-INF/versions/$futureVersion/$MIXIN.class" to byteArrayOf(0),
                "META-INF/versions/$futureVersion/lifecycle/external/FutureOnly.class" to byteArrayOf(0),
            ),
        )
        val result = AsmScanner.scanJarWithResult(jar.toFile(), "")

        assertTrue(result.failures.isEmpty(), result.failures.toString())
        assertEquals(listOf(MIXIN.replace('/', '.')), result.registeredClasses)
        assertEquals(7, transformAndLoad().getMethod("value").invoke(null))
    }

    @Test
    fun ordinaryJarIgnoresVersionedClassesAndMetadata() {
        val jar = createJar(
            mixinBytes(42), mixinBytes(7),
            extraClasses = mapOf("META-INF/tools/Hidden.class" to byteArrayOf(0)),
            multiRelease = false,
        )
        val result = AsmScanner.scanJarWithResult(jar.toFile(), "")

        assertTrue(result.failures.isEmpty(), result.failures.toString())
        assertEquals(listOf(MIXIN.replace('/', '.')), result.registeredClasses)
        assertTrue(result.skippedClasses.isEmpty())
        assertEquals(42, transformAndLoad().getMethod("value").invoke(null))
    }

    @Test
    fun moduleDescriptorIsNotLoadedAsAClass() {
        val jar = createJar(mixinBytes(42), extraClasses = mapOf("module-info.class" to moduleDescriptorBytes()))
        val result = AsmScanner.scanJarWithResult(jar.toFile(), "")

        assertTrue(result.failures.isEmpty(), result.failures.toString())
        assertEquals(listOf(MIXIN.replace('/', '.')), result.registeredClasses)
        assertTrue(result.skippedClasses.isEmpty())
    }

    @Test
    fun emptyDirectoryPackageScansRootAndNestedClasses() {
        val nestedClass = directory.resolve("$MIXIN.class")
        Files.createDirectories(nestedClass.parent)
        Files.write(nestedClass, mixinBytes(42))
        Files.write(directory.resolve("RootMixin.class"), mixinBytes(7, mixinName = "RootMixin"))
        Files.write(directory.resolve("module-info.class"), moduleDescriptorBytes())
        val metadataClass = directory.resolve("META-INF/versions/11/$MIXIN.class")
        Files.createDirectories(metadataClass.parent)
        Files.write(metadataClass, byteArrayOf(0))
        URLClassLoader(arrayOf(directory.toUri().toURL()), javaClass.classLoader).use { loader ->
            val thread = Thread.currentThread()
            val originalLoader = thread.contextClassLoader
            val result = try {
                thread.contextClassLoader = loader
                AsmScanner.scanDirectoryWithResult(directory.toFile(), "")
            } finally {
                thread.contextClassLoader = originalLoader
            }

            assertTrue(result.failures.isEmpty(), result.failures.toString())
            assertEquals(setOf("RootMixin", MIXIN.replace('/', '.')), result.registeredClasses.toSet())
            assertTrue(result.skippedClasses.isEmpty())
            assertEquals(2, AsmRegistry.getForTarget(TARGET).size)
        }
    }

    @Test
    fun pathMatcherCanWaitForJarScanOnAnotherThread() {
        val jar = createJar(mixinBytes(42))
        val executor = Executors.newSingleThreadExecutor()
        AsmRegistry.registerWithPathMatcher(javaClass) {
            // 使用有超时的等待暴露状态锁被回调占用的问题，失败时仍能释放测试线程。
            val scan = executor.submit<AsmScanResult> {
                AsmScanner.scanJarWithResult(jar.toFile(), "lifecycle.external")
            }.get(5, TimeUnit.SECONDS)
            assertTrue(scan.failures.isEmpty(), scan.failures.toString())
            false
        }
        try {
            assertTrue(AsmRegistry.getForTarget(TARGET).isEmpty())
            assertEquals(listOf(MIXIN.replace('/', '.')), AsmRegistry.getForTarget(TARGET).map { it.asmClass.name })
            assertEquals(42, transformAndLoad().getMethod("value").invoke(null))
        } finally {
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun scanningSameJarTwiceKeepsOneRegistration() {
        val jar = createJar(mixinBytes(42))
        repeat(2) { index ->
            val source = if (index == 0) jar else directory.resolve(".").resolve(jar.fileName)
            val result = AsmScanner.scanJarWithResult(source.toFile(), "lifecycle.external")
            assertTrue(result.failures.isEmpty(), result.failures.toString())
        }

        assertEquals(1, AsmRegistry.getForTarget(TARGET).size)
        assertEquals(1, scannerLoaderCount())
        val target = transformAndLoad()
        assertEquals(1, target.getMethod("inlineValue").invoke(null))
        assertEquals(1, target.getField("touches").getInt(null))
    }

    @Test
    fun scannedMixinResolvesDependenciesOutsideScannedPackage() {
        val jar = createJar(
            mixinBytes(42, withDependencies = true),
            extraClasses = dependencyClasses(),
        )
        val result = AsmScanner.scanJarWithResult(jar.toFile(), "lifecycle.external")

        assertEquals(listOf(MIXIN.replace('/', '.')), result.registeredClasses)
        assertTrue(result.failures.isEmpty(), result.failures.toString())
        val mixin = AsmRegistry.getForTarget(TARGET).single().asmClass
        // 签名和非内联 handler 分别依赖不同的包外类，避免签名解析提前加载运行时依赖。
        val target = transformAndLoad(mixin.classLoader)
        assertEquals(42, target.getMethod("value").invoke(null))
        assertEquals(1, target.getMethod("dependencyValue").invoke(null))
        val runtimeHelper = Class.forName(RUNTIME_HELPER.replace('/', '.'), false, mixin.classLoader)
        assertEquals(1, runtimeHelper.getField("calls").getInt(null))
        assertEquals(73, mixin.getMethod("dependentValue").invoke(null))
    }

    @Test
    fun clearingRegistryAllowsReloadingUpdatedJar() {
        val jar = createJar(mixinBytes(42))
        AsmScanner.scanJarWithResult(jar.toFile(), "lifecycle.external")
        val original = AsmRegistry.getForTarget(TARGET).single().asmClass

        AsmRegistry.clear()
        assertEquals(0, scannerLoaderCount())
        createJar(mixinBytes(7))
        val result = AsmScanner.scanJarWithResult(jar.toFile(), "lifecycle.external")

        assertTrue(result.failures.isEmpty(), result.failures.toString())
        assertNotSame(original, AsmRegistry.getForTarget(TARGET).single().asmClass)
        assertEquals(7, transformAndLoad().getMethod("value").invoke(null))
    }

    @Test
    fun differentParentsRemainIsolatedAndAreNotClosedByRegistry() {
        val jar = createJar(mixinBytes(42))
        TrackingParent(javaClass.classLoader).use { first ->
            TrackingParent(javaClass.classLoader).use { second ->
                assertTrue(scanWithParent(jar, first).failures.isEmpty())
                assertTrue(scanWithParent(jar, second).failures.isEmpty())
                val entries = AsmRegistry.getForTarget(TARGET)
                assertEquals(2, entries.size)
                assertNotSame(entries[0].asmClass, entries[1].asmClass)
                assertSame(first, entries[0].asmClass.classLoader.parent)
                assertSame(second, entries[1].asmClass.classLoader.parent)

                AsmRegistry.clear()
                assertFalse(first.closed)
                assertFalse(second.closed)
                assertEquals(0, scannerLoaderCount())
            }
        }
    }

    @Test
    fun scanWithoutMatchesDoesNotRetainLoader() {
        val jar = createJar(mixinBytes(42))
        repeat(5) {
            val result = AsmScanner.scanJarWithResult(jar.toFile(), "lifecycle.missing")
            assertTrue(result.registeredClasses.isEmpty())
            assertTrue(result.failures.isEmpty())
        }

        assertEquals(0, scannerLoaderCount())
        Files.delete(jar)
    }

    @Test
    fun classLoadingDoesNotBlockRegistryQueriesOnAnotherThread() {
        val jar = createJar(mixinBytes(42))
        val executor = Executors.newSingleThreadExecutor()
        val parent = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == MIXIN.replace('/', '.')) {
                    // 模拟另一个类加载线程在 agent 转换回调中查询注册表。
                    executor.submit<Boolean> { AsmRegistry.getForTarget(TARGET).isEmpty() }.get(5, TimeUnit.SECONDS)
                }
                return super.loadClass(name, resolve)
            }
        }

        val result = try {
            scanWithParent(jar, parent)
        } finally {
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
        assertTrue(result.failures.isEmpty(), result.failures.toString())
        assertEquals(listOf(MIXIN.replace('/', '.')), result.registeredClasses)
    }

    @Test
    fun clearingRegistryWaitsForActiveJarScan() {
        val jar = createJar(mixinBytes(42))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val clearStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val parent = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == MIXIN.replace('/', '.')) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                return super.loadClass(name, resolve)
            }
        }
        try {
            val scanning = executor.submit<AsmScanResult> { scanWithParent(jar, parent) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val clearing = executor.submit {
                clearStarted.countDown()
                AsmRegistry.clear()
            }
            assertTrue(clearStarted.await(5, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { clearing.get(200, TimeUnit.MILLISECONDS) }
            release.countDown()
            assertTrue(scanning.get(5, TimeUnit.SECONDS).failures.isEmpty())
            clearing.get(5, TimeUnit.SECONDS)
            assertTrue(AsmRegistry.getForTarget(TARGET).isEmpty())
            assertEquals(0, scannerLoaderCount())
        } finally {
            release.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun registeredMixinsRemainUsableAfterAnotherEntryFails() {
        val jar = createJar(
            mixinBytes(42, withDependencies = true),
            extraClasses = dependencyClasses() + ("lifecycle/external/Broken.class" to byteArrayOf(0)),
        )
        val result = AsmScanner.scanJarWithResult(jar.toFile(), "lifecycle.external")

        assertEquals(listOf(MIXIN.replace('/', '.')), result.registeredClasses)
        assertEquals("lifecycle.external.Broken", result.failures.single().className)
        val mixin = AsmRegistry.getForTarget(TARGET).single().asmClass
        val target = transformAndLoad(mixin.classLoader)
        assertEquals(42, target.getMethod("value").invoke(null))
        assertEquals(1, target.getMethod("dependencyValue").invoke(null))
    }

    private fun createJar(
        bytes: ByteArray?,
        versionedBytes: ByteArray? = null,
        extraClasses: Map<String, ByteArray> = emptyMap(),
        multiRelease: Boolean = versionedBytes != null,
    ): Path {
        val jar = directory.resolve("mixin.jar")
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            if (multiRelease) mainAttributes[Attributes.Name.MULTI_RELEASE] = "true"
        }
        JarOutputStream(Files.newOutputStream(jar), manifest).use { output ->
            if (bytes != null) {
                output.putNextEntry(JarEntry("$MIXIN.class"))
                output.write(bytes)
                output.closeEntry()
            }
            if (versionedBytes != null) {
                output.putNextEntry(JarEntry("META-INF/versions/11/$MIXIN.class"))
                output.write(versionedBytes)
                output.closeEntry()
            }
            extraClasses.forEach { (name, classBytes) ->
                output.putNextEntry(JarEntry(name))
                output.write(classBytes)
                output.closeEntry()
            }
        }
        return jar
    }

    private fun moduleDescriptorBytes(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V11, Opcodes.ACC_MODULE, "module-info", null, null, null)
        writer.visitModule("lifecycle.fixture", 0, null).apply {
            visitRequire("java.base", Opcodes.ACC_MANDATED, null)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun mixinBytes(value: Int, withDependencies: Boolean = false, mixinName: String = MIXIN): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, mixinName, null, "java/lang/Object", null)
        writer.visitAnnotation(Type.getDescriptor(AsmMixin::class.java), true).apply {
            visit("value", TARGET)
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "value", "()I", null, null).apply {
            visitAnnotation(Type.getDescriptor(Overwrite::class.java), true).apply {
                visit("method", "value()I")
                visitEnd()
            }
            visitCode()
            visitLdcInsn(value)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(1, 0)
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "copiedValue", "()I", null, null).apply {
            visitAnnotation(Type.getDescriptor(Copy::class.java), true).visitEnd()
            visitCode()
            visitLdcInsn(21)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(1, 0)
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "beforeInline", "()V", null, null).apply {
            visitAnnotation(Type.getDescriptor(AsmInject::class.java), true).apply {
                visit("method", "inlineValue()I")
                visit("inline", true)
                visitEnd()
            }
            visitCode()
            visitFieldInsn(Opcodes.GETSTATIC, TARGET, "touches", "I")
            visitInsn(Opcodes.ICONST_1)
            visitInsn(Opcodes.IADD)
            visitFieldInsn(Opcodes.PUTSTATIC, TARGET, "touches", "I")
            visitInsn(Opcodes.RETURN)
            visitMaxs(2, 0)
            visitEnd()
        }
        if (withDependencies) {
            writer.visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "identity", "(L$HELPER;)L$HELPER;", null, null,
            ).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitInsn(Opcodes.ARETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
            writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "dependentValue", "()I", null, null).apply {
                visitCode()
                visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME_HELPER, "value", "()I", false)
                visitInsn(Opcodes.IRETURN)
                visitMaxs(1, 0)
                visitEnd()
            }
            writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "beforeDependency", "()V", null, null).apply {
                visitAnnotation(Type.getDescriptor(AsmInject::class.java), true).apply {
                    visit("method", "dependencyValue()I")
                    visitEnd()
                }
                visitCode()
                visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME_HELPER, "value", "()I", false)
                visitInsn(Opcodes.POP)
                visitInsn(Opcodes.RETURN)
                visitMaxs(1, 0)
                visitEnd()
            }
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun dependencyClasses(): Map<String, ByteArray> =
        mapOf("$HELPER.class" to helperBytes(HELPER), "$RUNTIME_HELPER.class" to helperBytes(RUNTIME_HELPER))

    private fun helperBytes(name: String): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
        writer.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "calls", "I", null, null).visitEnd()
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "value", "()I", null, null).apply {
            visitCode()
            visitFieldInsn(Opcodes.GETSTATIC, name, "calls", "I")
            visitInsn(Opcodes.ICONST_1)
            visitInsn(Opcodes.IADD)
            visitFieldInsn(Opcodes.PUTSTATIC, name, "calls", "I")
            visitLdcInsn(73)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(2, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun transformAndLoad(parent: ClassLoader = javaClass.classLoader): Class<*> {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, TARGET, null, "java/lang/Object", null)
        writer.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "touches", "I", null, null).visitEnd()
        for (name in listOf("value", "inlineValue", "dependencyValue")) {
            writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, name, "()I", null, null).apply {
                visitCode()
                visitInsn(Opcodes.ICONST_1)
                visitInsn(Opcodes.IRETURN)
                visitMaxs(1, 0)
                visitEnd()
            }
        }
        writer.visitEnd()
        val bytes = AsmProcessor().transform(TARGET, writer.toByteArray(), parent)
        return object : ClassLoader(parent) {
            fun loadTarget(): Class<*> = defineClass(TARGET.replace('/', '.'), bytes, 0, bytes.size)
        }.loadTarget()
    }

    private fun scanWithParent(jar: Path, parent: ClassLoader): AsmScanResult {
        val thread = Thread.currentThread()
        val originalLoader = thread.contextClassLoader
        return try {
            thread.contextClassLoader = parent
            AsmScanner.scanJarWithResult(jar.toFile(), "lifecycle.external")
        } finally {
            thread.contextClassLoader = originalLoader
        }
    }

    private fun scannerLoaderCount(): Int {
        val field = AsmRegistry::class.java.getDeclaredField("scannedClassLoaders")
        field.isAccessible = true
        return (field.get(null) as Map<*, *>).values.sumOf { (it as Map<*, *>).size }
    }

    private class TrackingParent(parent: ClassLoader) : URLClassLoader(emptyArray(), parent) {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }

    companion object {
        private const val MIXIN = "lifecycle/external/JarMixin"
        private const val TARGET = "lifecycle/JarTarget"
        private const val HELPER = "lifecycle/support/Helper"
        private const val RUNTIME_HELPER = "lifecycle/runtime/Helper"
    }
}
