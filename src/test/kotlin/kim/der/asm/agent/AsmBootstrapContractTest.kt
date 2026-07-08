/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

package kim.der.asm.agent

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.instrument.Instrumentation
import java.nio.file.Files
import java.nio.file.Path

/**
 * Agent 入口与文档契约测试。
 */
class AsmBootstrapContractTest {
    @Test
    @DisplayName("应暴露 JVM 标准 premain/agentmain 双参数入口")
    fun exposesStandardJvmAgentEntryPoints() {
        val methods = AsmBootstrap::class.java.methods.map { it.name to it.parameterTypes.toList() }

        assertThat(methods)
            .`as`("Then: 应提供 premain(String?, Instrumentation)")
            .contains("premain" to listOf(String::class.java, Instrumentation::class.java))
        assertThat(methods)
            .`as`("Then: 应提供 agentmain(String?, Instrumentation)")
            .contains("agentmain" to listOf(String::class.java, Instrumentation::class.java))
        assertThat(methods)
            .`as`("Then: 应保留兼容宿主直接调用的单参数 agentmain")
            .contains("agentmain" to listOf(Instrumentation::class.java))
    }

    @Test
    @DisplayName("公开文档与构建应声明 Agent Manifest 与标准入口")
    fun documentationAndBuildKeepAgentUsabilityAligned() {
        val readme = Files.readString(Path.of("README.md"))
        val guide = Files.readString(Path.of("GUIDE.md"))
        val api = Files.readString(Path.of("API.md"))
        val build = Files.readString(Path.of("build.gradle.kts"))
        val bootstrap =
            Files.readString(
                Path.of("src", "main", "kotlin", "kim", "der", "asm", "agent", "AsmBootstrap.kt"),
            )

        assertThat(build)
            .`as`("Then: jar Manifest 应声明 Premain-Class / Agent-Class")
            .contains("\"Premain-Class\" to \"kim.der.asm.agent.AsmBootstrap\"")
            .contains("\"Agent-Class\" to \"kim.der.asm.agent.AsmBootstrap\"")
            .contains("\"Can-Retransform-Classes\" to \"true\"")
        assertThat(readme)
            .`as`("Then: README 快速开始应提到 -javaagent")
            .contains("-javaagent:ASM-Framework.jar")
            .contains("Premain-Class")
        assertThat(guide)
            .`as`("Then: GUIDE 应说明 Agent 挂载与注册时序")
            .contains("可选：以 Java Agent 挂载")
            .contains("premain(String?, Instrumentation)")
            .contains("目标类加载前")
        assertThat(api)
            .`as`("Then: API 应记录标准双参数入口")
            .contains("premain(agentArgs: String?, instrumentation: Instrumentation)")
            .contains("agentmain(agentArgs: String?, instrumentation: Instrumentation)")
            .contains("Can-Retransform-Classes: true")
        assertThat(bootstrap)
            .`as`("Then: AsmBootstrap KDoc 应说明标准入口与 Manifest")
            .contains("premain")
            .contains("Premain-Class")
            .contains("addTransformer(AsmBootstrap(), true)")
    }
}
