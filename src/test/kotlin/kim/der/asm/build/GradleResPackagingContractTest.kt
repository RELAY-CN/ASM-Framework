/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.build

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Gradle 生成资源与最终 JAR 的真实制品契约。
 */
class GradleResPackagingContractTest {
    @Test
    @DisplayName("应从 build 目录生成并打包完整 gradleRes 四件套")
    fun packagesCompleteGradleResFromBuildOutput() {
        val gitHash = currentGitHash()
        val generatedRoot = Path.of("build", "generated-resources", "gradleRes", PROJECT_NAME)
        val resourceNames =
            listOf(
                "FileList.txt",
                "compileOnly.txt",
                "implementation.txt",
                "GitCommitHash.txt",
            )
        val generatedContents =
            resourceNames.associateWith { resourceName ->
                val resource = generatedRoot.resolve(resourceName)
                assertThat(resource)
                    .`as`("Then: $resourceName 应由 generateGradleRes 写入 build/generated-resources")
                    .isRegularFile()
                Files.readString(resource, StandardCharsets.UTF_8)
            }
        val jar = Path.of(requireNotNull(System.getProperty("asmFramework.mainJar")))
        assertThat(jar)
            .`as`("Given: 当前 HEAD 的主 JAR 应已生成，不能误读 build/libs 中的旧版本")
            .isRegularFile()

        val jarContents =
            ZipFile(jar.toFile()).use { zip ->
                val gradleResEntries =
                    zip.entries().asSequence()
                        .map { it.name }
                        .filter { it.startsWith("$RESOURCE_ROOT/") && !it.endsWith("/") }
                        .sorted()
                        .toList()
                assertThat(zip.getEntry("kim/der/asm/agent/AsmBootstrap.class"))
                    .`as`("Given: Gradle 必须传入主 JAR，不能误测 sources JAR")
                    .isNotNull()
                assertThat(gradleResEntries)
                    .`as`("Then: 兼容资源目录中只能包含完整四件套")
                    .containsExactlyElementsOf(resourceNames.map { "$RESOURCE_ROOT/$it" }.sorted())

                resourceNames.associateWith { resourceName ->
                    val entryName = "$RESOURCE_ROOT/$resourceName"
                    val entry = zip.getEntry(entryName)
                    assertThat(entry)
                        .`as`("Then: JAR 应包含 $entryName")
                        .isNotNull()
                    zip.getInputStream(requireNotNull(entry)).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                }
            }

        assertThat(jarContents)
            .`as`("Then: JAR 内四件套应与生成目录内容完全一致")
            .isEqualTo(generatedContents)
        assertThat(generatedContents.getValue("GitCommitHash.txt"))
            .`as`("Then: GitCommitHash.txt 应记录当前提交短哈希")
            .isEqualTo(gitHash)

        assertThat(generatedContents.getValue("FileList.txt"))
            .`as`("Then: FileList.txt 应以固定 LF 精确、稳定地描述三个 main 输出根")
            .isEqualTo(expectedFileList().joinToString("\n"))

        val expectedImplementation =
            listOf(
                "jar:org.jetbrains.kotlin:kotlin-stdlib:2.3.10:null",
                "jar:org.jetbrains:annotations:13.0:null",
                "jar:org.ow2.asm:asm-analysis:9.9:null",
                "jar:org.ow2.asm:asm-commons:9.9:null",
                "jar:org.ow2.asm:asm-tree:9.9:null",
                "jar:org.ow2.asm:asm-util:9.9:null",
                "jar:org.ow2.asm:asm:9.9:null",
            )
        assertThat(generatedContents.getValue("implementation.txt"))
            .`as`("Then: implementation.txt 应以固定 LF 稳定记录完整 compileClasspath 传递闭包")
            .isEqualTo(expectedImplementation.joinToString("\n"))
        assertThat(generatedContents.getValue("compileOnly.txt"))
            .`as`("Then: 当前没有 compileOnly 依赖时仍应打包空清单")
            .isEmpty()

        URLClassLoader(arrayOf(jar.toUri().toURL()), null).use { loader ->
            val stream = loader.getResourceAsStream("$RESOURCE_ROOT/FileList.txt")
            assertThat(stream)
                .`as`("Then: 使用最终 JAR 的 ClassLoader 应能按兼容路径读取 gradleRes")
                .isNotNull()
            val runtimeContent =
                requireNotNull(stream).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            assertThat(runtimeContent).isEqualTo(generatedContents.getValue("FileList.txt"))
        }
    }

    private fun currentGitHash(): String {
        val process =
            ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                .directory(Path.of("").toAbsolutePath().toFile())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText().trim() }

        assertThat(process.waitFor())
            .`as`("Given: 测试必须能确定当前 HEAD，避免错误选择历史 JAR")
            .isZero()
        assertThat(output).isNotBlank()
        return output
    }

    private fun expectedFileList(): List<String> =
        mainOutputs.flatMap { (root, prefix) ->
            if (!Files.isDirectory(root)) {
                emptyList()
            } else {
                Files.walk(root).use { paths ->
                    paths.iterator().asSequence()
                        .filter { Files.isRegularFile(it) }
                        .map { file -> "$prefix/${root.relativize(file).toString().replace('\\', '/')}" }
                        .toList()
                }
            }
        }.sorted()

    private companion object {
        const val PROJECT_NAME = "ASM-Framework"
        const val RESOURCE_ROOT = "gradleRes/$PROJECT_NAME"

        val mainOutputs =
            listOf(
                Path.of("build", "classes", "kotlin", "main") to "kotlin/main",
                Path.of("build", "classes", "java", "main") to "java/main",
                Path.of("build", "resources", "main") to "main",
            )
    }
}
