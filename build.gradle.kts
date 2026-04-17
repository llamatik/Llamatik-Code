import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform
import proguard.gradle.ProGuardTask

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("com.guardsquare:proguard-gradle:7.9.1")
    }
}

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.14.0"
}

group = "com.llamatik.teammate"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2026.1")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        bundledPlugin("org.jetbrains.kotlin")
    }

    implementation("com.llamatik:library:1.0.0")
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
        }
        changeNotes = """
            Initial local-first MVP for Llamatik Code.
        """.trimIndent()
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

// ── ProGuard obfuscation ──────────────────────────────────────────────────────

val proguardJar = layout.buildDirectory.file("libs/${rootProject.name}-${version}-obfuscated.jar")

val obfuscate by tasks.registering(ProGuardTask::class) {
    dependsOn(tasks.named("composedJar"))

    // Input: the assembled plugin JAR
    injars(tasks.named("composedJar").flatMap { task ->
        (task as org.gradle.api.tasks.bundling.AbstractArchiveTask).archiveFile
    })

    // Output: obfuscated JAR
    outjars(proguardJar)

    // Library JARs from runtime classpath (IntelliJ platform + dependencies)
    libraryjars(provider {
        configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
            .map { it.file }
            .filter { it.extension == "jar" }
    })

    // JDK libs
    libraryjars(provider {
        fileTree("${System.getProperty("java.home")}/lib") { include("*.jar") }.files
    })

    configuration(file("proguard-rules.pro"))
    allowaccessmodification()
    verbose()
}

// After obfuscation, overwrite the composedJar with the obfuscated version so
// buildPlugin and prepareSandbox transparently pick up obfuscated bytecode.
tasks.named("prepareSandbox") {
    dependsOn(obfuscate)
    doFirst {
        val obfuscatedFile = proguardJar.get().asFile
        val originalJar = (tasks.named("composedJar").get() as org.gradle.api.tasks.bundling.AbstractArchiveTask)
            .archiveFile.get().asFile
        if (obfuscatedFile.exists()) {
            obfuscatedFile.copyTo(originalJar, overwrite = true)
        }
    }
}
