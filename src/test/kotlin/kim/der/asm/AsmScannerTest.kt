/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm

import kim.der.asm.scanner.fixture.ScanState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
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
    private val mixinClassName = "$packageName.ScanMixin"
    private val companionClassName = "$packageName.ScanMixin\$Companion"
    private val stateClassName = "$packageName.ScanState"

    @AfterEach
    fun tearDown() {
        AsmRegistry.clear()
        ScanState.reset()
    }

    @Test
    @DisplayName("类加载器扫描不应触发 Mixin 类初始化")
    fun testScanClassLoaderDoesNotInitializeMixinClass() {
        resetState()

        AsmScanner.scanClassLoader(javaClass.classLoader, packageName)

        assertRegisteredWithoutInitialization()
    }

    @Test
    @DisplayName("JAR 扫描不应触发 Mixin 类初始化")
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

    @Test
    @DisplayName("目录扫描诊断结果应区分已注册、已跳过与失败状态")
    fun scanDirectoryWithResultReportsRegisteredSkippedAndFailures() {
        // Given
        resetState()
        val packagePath = packageName.replace('.', '/')
        val directory =
            requireNotNull(javaClass.classLoader.getResource(packagePath)) {
                "找不到扫描测试包目录: $packagePath"
            }.toURI().let { Path.of(it).toFile() }

        // When
        val result = AsmScanner.scanDirectoryWithResult(directory, packageName)

        // Then
        assertThat(result.registeredClasses)
            .`as`("Then: 目录扫描应把带 @AsmMixin 的 fixture 注册到结果快照")
            .containsExactly(mixinClassName)
        assertThat(result.skippedClasses)
            .`as`("Then: 目录扫描应把成功加载但未标注 @AsmMixin 的真实 fixture 记录为跳过")
            .contains(companionClassName, stateClassName)
        assertThat(result.failures)
            .`as`("Then: 正常测试 fixture 目录不应产生类加载失败")
            .isEmpty()
        assertRegisteredWithoutInitialization()
    }

    @Test
    @DisplayName("JAR 扫描诊断结果应返回注册、跳过与失败快照")
    fun scanJarWithResultReportsRegisteredSkippedAndFailures() {
        // Given
        resetState()
        val tempJar = Files.createTempFile("asm-scanner-", ".jar")

        try {
            createFixtureJar(tempJar)

            // When
            val result = AsmScanner.scanJarWithResult(tempJar.toFile(), packageName)

            // Then
            assertThat(result.registeredClasses)
                .`as`("Then: JAR 扫描应报告成功注册的 Mixin fixture")
                .containsExactly(mixinClassName)
            assertThat(result.skippedClasses)
                .`as`("Then: JAR 扫描应报告同包内未标注 @AsmMixin 的类")
                .containsExactlyInAnyOrder(companionClassName, stateClassName)
            assertThat(result.failures)
                .`as`("Then: 合法 fixture JAR 不应产生扫描失败")
                .isEmpty()
            assertRegisteredWithoutInitialization()
        } finally {
            Files.deleteIfExists(tempJar)
        }
    }

    @Test
    @DisplayName("类加载器扫描诊断结果应返回注册、跳过与失败快照")
    fun scanClassLoaderWithResultReportsRegisteredSkippedAndFailures() {
        // Given
        resetState()

        // When
        val result = AsmScanner.scanClassLoaderWithResult(javaClass.classLoader, packageName)

        // Then
        assertThat(result.registeredClasses)
            .`as`("Then: 类加载器扫描应报告成功注册的 Mixin fixture")
            .contains(mixinClassName)
        assertThat(result.skippedClasses)
            .`as`("Then: 类加载器扫描应报告同包内未标注 @AsmMixin 的类")
            .contains(companionClassName, stateClassName)
        assertThat(result.failures)
            .`as`("Then: 合法 fixture 包不应产生扫描失败")
            .isEmpty()
        assertRegisteredWithoutInitialization()
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
                "kim/der/asm/scanner/fixture/ScanState.class",
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
