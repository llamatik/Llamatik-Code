import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform
import proguard.gradle.ProGuardTask
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("com.guardsquare:proguard-gradle:7.9.1")
        classpath("org.ow2.asm:asm:9.7")
    }
}

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.14.0"
}

group = "com.llamatik.teammate"
version = "1.0.3"

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

    implementation("com.llamatik:library:1.1.0")
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252"
        }
        changeNotes = """
            Initial local-first MVP for Llamatik Code.
        """.trimIndent()
    }
    buildSearchableOptions = false
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

    // JDK libs: jrt-fs.jar plus the java.desktop jmod (contains javax.swing, java.awt)
    // needed so ProGuard can resolve method signatures in keepclassmembers rules.
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

// Recompute StackMapTable attributes that ProGuard's -dontpreverify strips.
// Java 21 class files (major version 65) require StackMapTable for any method
// containing branch instructions; without it MethodHandles.Lookup.findConstructor
// throws a VerifyError that IntelliJ's findConstructorOrNull silently swallows,
// producing "Cannot find suitable constructor".
val recomputedJar = layout.buildDirectory.file("libs/${rootProject.name}-${version}-frames.jar")

val recomputeFrames by tasks.registering {
    dependsOn(obfuscate)
    inputs.file(proguardJar)
    outputs.file(recomputedJar)
    // The anonymous ClassWriter subclass is compiled as a Kotlin script inner class,
    // which holds an implicit reference to the Gradle script object.  That reference
    // cannot be serialised by the configuration cache, so we opt this task out.
    notCompatibleWithConfigurationCache("Uses a script-inner ClassWriter that captures Gradle script state")

    doLast {
        val inFile  = proguardJar.get().asFile
        val outFile = recomputedJar.get().asFile
        if (!inFile.exists()) return@doLast

        // Pre-load all class bytes from the JAR so we can resolve superclass names
        // without attempting to load IntelliJ/JDK classes from the Gradle classloader.
        val allClassBytes = mutableMapOf<String, ByteArray>()
        ZipFile(inFile).use { zf ->
            zf.entries().asSequence()
                .filter { it.name.endsWith(".class") }
                .forEach { e -> allClassBytes[e.name.removeSuffix(".class")] = zf.getInputStream(e).readBytes() }
        }

        ZipFile(inFile).use { zin ->
            ZipOutputStream(outFile.outputStream().buffered()).use { zout ->
                zin.entries().asSequence().forEach { entry ->
                    zout.putNextEntry(ZipEntry(entry.name))
                    val bytes = zin.getInputStream(entry).readBytes()
                    if (entry.name.endsWith(".class")) {
                        try {
                            val cr = ClassReader(bytes)
                            // SafeClassWriter overrides getCommonSuperClass to avoid
                            // loading platform classes that are absent from the Gradle classloader.
                            val cw = object : ClassWriter(ClassWriter.COMPUTE_FRAMES) {
                                override fun getCommonSuperClass(type1: String, type2: String): String {
                                    // Walk up the superclass chain for type1 until we find an
                                    // ancestor that is also an ancestor of type2.  For types we
                                    // don't have bytes for (platform / JDK classes) fall back to
                                    // "java/lang/Object", which is always safe.
                                    fun ancestors(name: String): List<String> {
                                        val result = mutableListOf<String>()
                                        var current: String? = name
                                        while (current != null && current != "java/lang/Object") {
                                            result += current
                                            val b = allClassBytes[current] ?: break
                                            current = try { ClassReader(b).superName } catch (_: Exception) { break }
                                        }
                                        result += "java/lang/Object"
                                        return result
                                    }
                                    val ancestors2 = ancestors(type2).toSet()
                                    return ancestors(type1).firstOrNull { it in ancestors2 } ?: "java/lang/Object"
                                }
                            }
                            // ClassReader.SKIP_FRAMES (= 4): discard existing (absent) frame info,
                            // let ClassWriter.COMPUTE_FRAMES regenerate StackMapTable from scratch.
                            cr.accept(cw, ClassReader.SKIP_FRAMES)
                            zout.write(cw.toByteArray())
                        } catch (e: Exception) {
                            logger.warn("recomputeFrames: skipped ${entry.name}: ${e.message}")
                            zout.write(bytes)
                        }
                    } else {
                        zout.write(bytes)
                    }
                    zout.closeEntry()
                }
            }
        }
        logger.lifecycle("StackMapTable frames recomputed → ${outFile.name}")
    }
}

// Obfuscation only runs for buildPlugin (distribution), NOT for runIde (development).
// After Gradle assembles the zip, we rewrite it in-place: replace the unobfuscated
// composedJar inside the archive with the ProGuard output.  This avoids fighting
// Gradle's build cache (mutating composedJar's declared output breaks cache correctness).
// Resolve composedJar archive name at configuration time (outside doLast) so no
// Gradle script object reference is captured, satisfying the configuration cache.
val composedJarArchiveName = tasks.named("composedJar", org.gradle.api.tasks.bundling.AbstractArchiveTask::class)
    .flatMap { it.archiveFileName }

tasks.named("buildPlugin") {
    dependsOn(recomputeFrames)
    // Wire the composedJar archive name as an input so it is recorded in the cache entry.
    val composedJarNameProvider = composedJarArchiveName
    val obfuscatedJarProvider = recomputedJar

    doLast {
        val obfuscatedFile = obfuscatedJarProvider.get().asFile
        if (!obfuscatedFile.exists()) return@doLast

        val zipTask = this as org.gradle.api.tasks.bundling.Zip
        val zipFile = zipTask.archiveFile.get().asFile
        if (!zipFile.exists()) return@doLast

        val composedJarName = composedJarNameProvider.get()
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
