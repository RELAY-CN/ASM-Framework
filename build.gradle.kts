/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.attributes.Attribute
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.bundling.Jar
import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.10"
    id("java-library")
    id("maven-publish")
}

group = "kim.der"
version =
    providers
        .exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
        }.standardOutput.asText
        .map(String::trim)
        .get()

repositories {
    maven(url = "https://mirrors.cloud.tencent.com/nexus/repository/maven-public")
    maven(url = "https://repo.huaweicloud.com/repository/maven")
    maven(url = "https://jitpack.io")
    maven(url = "https://plugins.gradle.org/m2")
    maven(url = "https://files.minecraftforge.net/maven")
    mavenCentral()
    google()
    gradlePluginPortal()
}

dependencies {
    // Users should not operate Quartz
    // Hence the RunTime
    implementation("org.ow2.asm:asm-tree:9.9")
    implementation("org.ow2.asm:asm-commons:9.9")
    implementation("org.ow2.asm:asm-util:9.9")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("org.mockito:mockito-core:5.18.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

java {
    withSourcesJar()
}

configureGradleRes()

tasks.withType<JavaCompile> {
    // 使用Java11做标准语法并编译
    sourceCompatibility = JvmTarget.JVM_11.target
    targetCompatibility = JvmTarget.JVM_11.target

    options.encoding = "UTF-8"
}

tasks.jar {
    manifest {
        attributes(
            mapOf(
                "Premain-Class" to "kim.der.asm.agent.AsmBootstrap",
                "Agent-Class" to "kim.der.asm.agent.AsmBootstrap",
                "Can-Redefine-Classes" to "true",
                "Can-Retransform-Classes" to "true",
            ),
        )
    }
}

val mainJar = tasks.named<Jar>("jar")

tasks.test {
    useJUnitPlatform()
    dependsOn(mainJar)
    inputs.file(mainJar.flatMap { it.archiveFile }).withPropertyName("mainJar")
    doFirst {
        systemProperty("asmFramework.mainJar", mainJar.get().archiveFile.get().asFile.absolutePath)
    }
}

configureGraalVmAgent()

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "kim.der"
            artifactId = project.name
            version = project.version.toString()

            from(project.components.getByName("java"))

            pom {
                scm {
                    url.set("https://github.com/RELAY-CN/ASM-Framework")
                    connection.set("scm:https://github.com/RELAY-CN/ASM-Framework.git")
                    developerConnection.set("scm:git@github.com:RELAY-CN/ASM-Framework.git")
                }

                licenses {
                    license {
                        name.set("RELAY-CN LICENSE")
                        url.set("https://github.com/RELAY-CN/ASM-Framework/blob/master/LICENSE")
                    }
                }

                developers {
                    developer {
                        id.set("ASM-Framework")
                        name.set("Dr (RELAY-CN Technologies)")
                        email.set("dr@der.kim")
                    }
                }
            }

            pom.withXml {
                val root = asNode()
                root.appendNode("description", "ASM-Framework")
                root.appendNode("name", project.name)
                root.appendNode("url", "https://github.com/RELAY-CN/ASM-Framework")
            }
        }
    }

    repositories {
        maven {
            name = "maven-releases"
            url = uri((project.findProperty("mavenCentralUrl") ?: "").toString() + "$name/")

            credentials {
                username = (project.findProperty("mavenCentralUsername") ?: "").toString()
                password = (project.findProperty("mavenCentralPassword") ?: "").toString()
            }
        }
    }
}

/**
 * 生成可供下游运行时读取的构建资源，并只在 JAR 阶段注入兼容路径。
 *
 * FileList 依赖 classes，因此不能把 generated-resources 反向挂到 processResources，
 * 否则会形成 `processResources -> generateGradleRes -> classes -> processResources` 任务环。
 */
fun Project.configureGradleRes() {
    val generatedDirectory = layout.buildDirectory.dir("generated-resources/gradleRes/$name")
    val compileClasspath = configurations.getByName("compileClasspath")
    val compileOnly = configurations.getByName("compileOnly")
    val runtimeClasspath = configurations.getByName("runtimeClasspath")
    val mainOutput = extensions.getByType(JavaPluginExtension::class.java).sourceSets.getByName("main").output
    val dependencyLines =
        providers.provider {
            resolveGradleResDependencyLines(compileClasspath, compileOnly, runtimeClasspath)
        }
    val implementationLines = dependencyLines.map { it.first }
    val compileOnlyLines = dependencyLines.map { it.second }
    val gitCommitHash = version.toString()
    val fileListFile = generatedDirectory.get().file("FileList.txt").asFile
    val compileOnlyFile = generatedDirectory.get().file("compileOnly.txt").asFile
    val implementationFile = generatedDirectory.get().file("implementation.txt").asFile
    val gitCommitHashFile = generatedDirectory.get().file("GitCommitHash.txt").asFile
    val buildDirectory = layout.buildDirectory.get().asFile
    val fileListRoots =
        listOf(
            File(buildDirectory, "classes/kotlin/main") to "kotlin/main",
            File(buildDirectory, "classes/java/main") to "java/main",
            File(buildDirectory, "resources/main") to "main",
        )

    val generateGradleRes =
        tasks.register("generateGradleRes") {
            group = "build"
            description = "生成 gradleRes 四件套到 build/generated-resources"
            dependsOn(tasks.named("classes"))

            inputs.property("formatVersion", 1)
            inputs.property("gitCommitHash", gitCommitHash)
            inputs.property("implementationDependencies", implementationLines)
            inputs.property("compileOnlyDependencies", compileOnlyLines)
            inputs.files(mainOutput).withPathSensitivity(PathSensitivity.RELATIVE)
            inputs.files(compileClasspath).withPathSensitivity(PathSensitivity.NONE)
            inputs.files(runtimeClasspath).withPathSensitivity(PathSensitivity.NONE)
            outputs.files(fileListFile, compileOnlyFile, implementationFile, gitCommitHashFile)

            doLast {
                val fileList =
                    fileListRoots
                        .flatMap { (root, prefix) ->
                            if (!root.isDirectory) {
                                emptyList()
                            } else {
                                root.walkTopDown()
                                    .filter(File::isFile)
                                    .map { file -> "$prefix/${file.relativeTo(root).invariantSeparatorsPath}" }
                                    .toList()
                            }
                        }.sorted()
                        .joinToString("\n")
                val contents =
                    mapOf(
                        fileListFile to fileList,
                        compileOnlyFile to compileOnlyLines.get().joinToString("\n"),
                        implementationFile to implementationLines.get().joinToString("\n"),
                        gitCommitHashFile to gitCommitHash,
                    )

                contents.forEach { (file, content) ->
                    file.parentFile.mkdirs()
                    if (!file.isFile || file.readText(Charsets.UTF_8) != content) {
                        file.writeText(content, Charsets.UTF_8)
                    }
                }
            }
        }

    // 迁移期忽略源树遗留副本，避免旧 Git hash 与 generated 四件套同时进入 JAR。
    tasks.named<ProcessResources>("processResources") {
        exclude("gradleRes/**")
    }

    tasks.named<Jar>("jar") {
        dependsOn(generateGradleRes)
        from(generatedDirectory) {
            include("FileList.txt", "compileOnly.txt", "implementation.txt", "GitCommitHash.txt")
            into("gradleRes/${project.name}")
        }
    }
}

private fun Project.resolveGradleResDependencyLines(
    compileClasspath: Configuration,
    compileOnly: Configuration,
    runtimeClasspath: Configuration,
): Pair<List<String>, List<String>> {
    val compileOnlyArtifacts: Set<List<String?>> =
        compileOnly.allDependencies
            .flatMap { dependency ->
                val detachedConfiguration = configurations.detachedConfiguration(dependency)
                compileClasspath.attributes.keySet().forEach { attribute ->
                    @Suppress("UNCHECKED_CAST")
                    val typedAttribute = attribute as Attribute<Any>
                    compileClasspath.attributes.getAttribute(typedAttribute)?.let { value ->
                        detachedConfiguration.attributes.attribute(typedAttribute, value)
                    }
                }
                detachedConfiguration.resolvedConfiguration
                    .resolvedArtifacts
                    .map(ResolvedArtifact::gradleResKey)
            }.toSet()
    val runtimeArtifacts =
        runtimeClasspath.resolvedConfiguration.resolvedArtifacts
            .map(ResolvedArtifact::gradleResKey)
            .toSet()
    val classified: List<Pair<Boolean, String>> =
        compileClasspath.resolvedConfiguration.resolvedArtifacts.map { artifact: ResolvedArtifact ->
            val dependencyKind =
                if (artifact.id.componentIdentifier is ProjectComponentIdentifier) "project" else artifact.type
            val id = artifact.moduleVersion.id
            val line = "$dependencyKind:${id.group}:${id.name}:${id.version}:${artifact.classifier}"
            val key = artifact.gradleResKey()
            (key in compileOnlyArtifacts && key !in runtimeArtifacts) to line
        }
    val (compileOnlyEntries, implementationEntries) = classified.partition { it.first }

    return implementationEntries.map { it.second }.distinct().sorted() to
        compileOnlyEntries.map { it.second }.distinct().sorted()
}

private fun ResolvedArtifact.gradleResKey(): List<String?> {
    val id = moduleVersion.id
    return listOf(id.group, id.name, id.version, type, classifier)
}

/*
 * GraalVM Native Image Support
 */
fun Project.configureGraalVmAgent() {
    val resourcesDir = File(projectDir, "src/main/resources")
    val nativeImageDir = File(resourcesDir, "META-INF/native-image/${project.group}/${project.name}")
    nativeImageDir.mkdirs()

    tasks.withType<Test>().configureEach {
        if (project.findProperty("disableGraalVmAgent") != "true") {
            val agentOutputDir =
                File(
                    project.layout.buildDirectory
                        .get()
                        .asFile,
                    "native-image-agent/$name",
                )
            agentOutputDir.mkdirs()

            jvmArgs(
                "-XX:+EnableDynamicAgentLoading",
                "-Djdk.instrument.traceUsage=false",
                "-agentlib:native-image-agent=" + "config-output-dir=${agentOutputDir.absolutePath}," +
                    "access-filter-file=${
                        createAccessFilterFile(project).absolutePath
                    }",
            )

            doLast {
                project.logger.lifecycle("复制 GraalVM Agent 配置: ${agentOutputDir.absolutePath}")
                project.copyGraalConfigs(agentOutputDir, nativeImageDir)
            }
        }
    }
}

private fun createAccessFilterFile(project: Project): File {
    val buildDir =
        project.layout.buildDirectory
            .get()
            .asFile
    val filterDir = File(buildDir, "graalvm-filters")
    filterDir.mkdirs()

    val filterFile = File(filterDir, "access-filter.json")
    if (!filterFile.exists()) {
        filterFile.createNewFile()
    }
    filterFile.writeText(
        """
        {
            "rules": [
                {"excludeClasses": "gradle.**"},
                {"excludeClasses": "org.gradle.**"},
                {"excludeClasses": "junit.**"},
                {"excludeClasses": "org.junit.**"},
                {"excludeClasses": "org.mockito.**"},
                {"excludeClasses": "net.bytebuddy.**"},
                {"excludeClasses": "com.sun.tools.attach.**"},
                {"excludeClasses": "org.opentest4j.**"},
                {"excludeClasses": "org.apiguardian.**"}
            ],
            "regexRules": [
                {"excludeClasses": ".*Test"},
                {"excludeClasses": ".*Test\\$.*"},
                {"excludeClasses": ".*Mock.*"},
                {"excludeClasses": ".*\\'$'MockitoMock\\$.*"}
            ]
        }
        """.trimIndent(),
    )
    return filterFile
}

private fun Project.copyGraalConfigs(
    sourceDir: File,
    targetDir: File,
) {
    targetDir.mkdirs()

    val propertiesFile = File(targetDir, "native-image.properties")
    if (!propertiesFile.exists()) {
        propertiesFile.createNewFile()
        propertiesFile.writeText(
            """
            Args = --no-fallback
            """.trimIndent(),
        )
    }

    sourceDir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }?.forEach { sourceFile ->
        val targetFile = File(targetDir, sourceFile.name)
        Files.copy(
            sourceFile.toPath(),
            targetFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}
