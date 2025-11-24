/*
 * Copyright 2020-2025 Dr (dr@der.kim) and contributors.
 */

import org.gradle.api.Project
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.2.21"
    id("java-library")
    id("maven-publish")
}

group = "kim.der"
version = getGitCommitHash()

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

tasks.withType<JavaCompile> {
    // 使用Java11做标准语法并编译
    sourceCompatibility = JvmTarget.JVM_11.target
    targetCompatibility = JvmTarget.JVM_11.target

    options.encoding = "UTF-8"
}

tasks.jar {
    doLast {
        makeGitCommitHashFile()
    }
}

tasks.test {
    useJUnitPlatform()
}

configureGraalVmAgent()

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "kim.der"
            artifactId = project.name
            version = getGitCommitHash()
            
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

// Helper functions from buildSrc
fun getGitCommitHash(): String {
    return try {
        Runtime
            .getRuntime()
            .exec(arrayOf("git", "rev-parse", "--short", "HEAD"))
            .inputStream
            .bufferedReader()
            .readText()
            .trim()
    } catch (_: Exception) {
        "unknown"
    }
}

fun Project.makeGitCommitHashFile() {
    try {
        val gitHashFile = File("${rootDir}/src/main/resources/gradleRes/${name}/GitCommitHash.txt")
        gitHashFile.fuckGithub()
        gitHashFile.writeText(getGitCommitHash())
    } catch (_: Exception) {
        // 忽略
    }
}

fun File.fuckGithub() {
    try {
        if (this.parentFile == null || this.parentFile!!.delete()) {
            return
        }
        this.parentFile?.mkdirs()

        if (this.isDirectory) {
            return
        }

        if (!this.exists()) {
            try {
                Files.createFile(toPath())
            } catch (e: IOException) {
                println(path)
                println(canWrite())
                error(e)
            }
        } else {
            this.delete()
        }
    } catch (_: Exception) {
        println("remove Files Error???")
    }
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
            val agentOutputDir = File(project.layout.buildDirectory.get().asFile, "native-image-agent/$name")
            agentOutputDir.mkdirs()

            jvmArgs(
                "-XX:+EnableDynamicAgentLoading",
                "-Djdk.instrument.traceUsage=false",
                "-agentlib:native-image-agent=" +
                    "config-output-dir=${agentOutputDir.absolutePath}," +
                    "experimental-class-define-support," +
                    "access-filter-file=${createAccessFilterFile(project).absolutePath}",
            )

            doLast {
                project.logger.lifecycle("复制 GraalVM Agent 配置: ${agentOutputDir.absolutePath}")
                project.copyGraalConfigs(agentOutputDir, nativeImageDir)
            }
        }
    }
}

private fun createAccessFilterFile(project: Project): File {
    val buildDir = project.layout.buildDirectory.get().asFile
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

private fun Project.copyGraalConfigs(sourceDir: File, targetDir: File) {
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

    sourceDir.listFiles()
        ?.filter { it.isFile && it.name.endsWith(".json") }
        ?.forEach { sourceFile ->
            val targetFile = File(targetDir, sourceFile.name)
            Files.copy(
                sourceFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
    }
}
