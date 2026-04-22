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

// ── Debug config generation ───────────────────────────────────────────────────
// Pass -PforceLicensed=true on the Gradle command line (or in a run config) to
// enable Pro features locally. The generated file must have FORCE_LICENSED=false
// before building a release JAR.
val forceLicensed = providers.gradleProperty("forceLicensed").orNull == "true"
val generateDebugConfig by tasks.registering {
    val outFile = layout.projectDirectory.file("src/main/kotlin/com/llamatik/teammate/licensing/DebugConfig.kt")
    val licensed = forceLicensed
    inputs.property("forceLicensed", licensed)
    outputs.file(outFile)
    doLast {
        outFile.asFile.writeText("""package com.llamatik.teammate.licensing

internal object DebugConfig {
    const val FORCE_LICENSED: Boolean = $licensed
}
""")
    }
}
tasks.named("compileKotlin") { dependsOn(generateDebugConfig) }

// ── ProGuard obfuscation ──────────────────────────────────────────────────────

val proguardJar = layout.buildDirectory.file("libs/${rootProject.name}-${version}-obfuscated.jar")

// Stable symbol mapping kept across releases.  Committing this file means that
// obfuscated names stay consistent between versions, so stack traces from older
// releases can still be decoded with the matching mapping file.
// See: https://plugins.jetbrains.com/docs/marketplace/obfuscate-the-plugin.html
val proguardMappingFile = rootProject.file("proguard-mapping.txt")

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

    // Write the mapping file so obfuscated names can be decoded in crash reports.
    // On subsequent builds, re-apply the existing mapping to keep names stable
    // across releases (JetBrains Marketplace recommendation).
    printmapping(proguardMappingFile)
    if (proguardMappingFile.exists()) {
        applymapping(proguardMappingFile)
    }

    verbose()
}

// Obfuscation only runs for buildPlugin (distribution), NOT for runIde (development).
// After Gradle assembles the zip, we rewrite it in-place: replace the unobfuscated
// composedJar inside the archive with the ProGuard output.  This avoids fighting
// Gradle's build cache (mutating composedJar's declared output breaks cache correctness).
tasks.named("buildPlugin") {
    dependsOn(obfuscate)
    doLast {
        val obfuscatedFile = proguardJar.get().asFile
        if (!obfuscatedFile.exists()) return@doLast

        val zipTask = this as org.gradle.api.tasks.bundling.Zip
        val zipFile = zipTask.archiveFile.get().asFile
        if (!zipFile.exists()) return@doLast

        val composedJarName = (tasks.named("composedJar").get() as org.gradle.api.tasks.bundling.AbstractArchiveTask)
            .archiveFileName.get()
        val entryName = "LlamatikCode/lib/$composedJarName"

        // Rewrite the zip in-place: replace the unobfuscated composedJar entry with the
        // ProGuard output. Uses python3 (always available on macOS) to avoid JDK zip API
        // classloader issues inside the Gradle Kotlin DSL doLast action.
        val pyScript = """
import zipfile, os, sys
src = sys.argv[1]; entry = sys.argv[2]; replacement = sys.argv[3]
tmp = src + '.obftmp'
with zipfile.ZipFile(src, 'r') as zin, zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        if item.filename == entry:
            zout.write(replacement, item.filename)
        else:
            zout.writestr(item, zin.read(item.filename))
os.replace(tmp, src)
print('Replaced', entry, 'with obfuscated jar')
""".trimIndent()
        val proc = ProcessBuilder("python3", "-c", pyScript, zipFile.absolutePath, entryName, obfuscatedFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        val exitCode = proc.waitFor()
        if (exitCode != 0) error("python3 zip rewrite failed (exit $exitCode):\n$output")
        logger.lifecycle("Obfuscated jar injected into ${zipFile.name}")
    }
}
