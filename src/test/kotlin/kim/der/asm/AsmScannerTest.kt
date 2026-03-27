/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm

import kim.der.asm.scanner.fixture.ScanState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * AsmScanner 回归测试
 * 验证扫描阶段可以注册 Mixin，但不会触发类初始化
 */
class AsmScannerTest {
    private val packageName = "kim.der.asm.scanner.fixture"
    private val targetClassName = "test/ScanTarget"

    @AfterEach
    fun tearDown() {
        AsmRegistry.clear()
        ScanState.reset()
    }

    @Test
    fun testScanClassLoaderDoesNotInitializeMixinClass() {
        resetState()

        AsmScanner.scanClassLoader(javaClass.classLoader, packageName)

        assertRegisteredWithoutInitialization()
    }

    @Test
    fun testScanJarDoesNotInitializeMixinClass() {
        resetState()

        val tempJar = Files.createTempFile("asm-scanner-", ".jar")
        try {
            createFixtureJar(tempJar)
            AsmScanner.scanJar(tempJar.toFile(), packageName)

            assertRegisteredWithoutInitialization()
        } finally {
            Files.deleteIfExists(tempJar)
        }
    }

    private fun resetState() {
        AsmRegistry.clear()
        ScanState.reset()
    }

    private fun assertRegisteredWithoutInitialization() {
        val matches = AsmRegistry.getForTarget(targetClassName)
        assertEquals(1, matches.size, "应该只注册一个扫描测试 Mixin")
        assertEquals("$packageName.ScanMixin", matches.single().asmClass.name, "扫描结果应命中测试 Mixin")
        assertEquals(0, ScanState.initializedCount, "扫描阶段不应触发 Mixin 类初始化")
    }

    private fun createFixtureJar(jarPath: Path) {
        val classEntries =
            listOf(
                "kim/der/asm/scanner/fixture/ScanMixin.class",
                "kim/der/asm/scanner/fixture/ScanMixin\$Companion.class",
            )

        JarOutputStream(Files.newOutputStream(jarPath)).use { output ->
            classEntries.forEach { entryName ->
                val classBytes =
                    requireNotNull(javaClass.classLoader.getResourceAsStream(entryName)) {
                        "找不到测试类资源: $entryName"
                    }.use { it.readBytes() }

                output.putNextEntry(JarEntry(entryName))
                output.write(classBytes)
                output.closeEntry()
            }
        }
    }
}
