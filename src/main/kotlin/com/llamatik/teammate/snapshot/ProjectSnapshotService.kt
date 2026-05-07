package com.llamatik.teammate.snapshot

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.context.ProjectContextService
import com.llamatik.teammate.util.PluginLogger
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.PROJECT)
class ProjectSnapshotService(private val project: Project) {

    private val log = PluginLogger.logger<ProjectSnapshotService>()
    private val contextService = ProjectContextService(project)
    private val cachedSnapshot = AtomicReference<ProjectSnapshot?>(null)

    data class ProjectSnapshot(
        val projectName: String,
        val techStack: List<String>,
        val modules: List<String>,
        val architectureStyle: String,
        val mainEntryPoints: List<String>,
        val testSetup: String,
        val buildTools: List<String>,
        val uiFramework: String,
        val dependencyInjection: String,
        val networkingLayer: String,
        val storageLayer: String,
        val risks: List<String>,
        val recommendedImprovements: List<String>,
        val inspectedFiles: List<String>,
        val confidence: String,
        val generatedAtMs: Long = System.currentTimeMillis(),
    ) {
        fun formattedTimestamp(): String {
            val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneId.systemDefault())
            return fmt.format(Instant.ofEpochMilli(generatedAtMs))
        }

        fun toMarkdown(): String = buildString {
            appendLine("## Project Snapshot: $projectName")
            appendLine("_Generated ${formattedTimestamp()} · Confidence: $confidence")
            appendLine()
            appendLine("**Tech stack:** ${techStack.joinToString(", ")}")
            appendLine("**Build tools:** ${buildTools.joinToString(", ")}")
            appendLine("**Modules:** ${if (modules.isEmpty()) "single-module" else modules.joinToString(", ")}")
            appendLine("**Architecture:** $architectureStyle")
            appendLine("**UI framework:** $uiFramework")
            appendLine("**Dependency injection:** $dependencyInjection")
            appendLine("**Networking:** $networkingLayer")
            appendLine("**Storage:** $storageLayer")
            appendLine("**Test setup:** $testSetup")
            if (mainEntryPoints.isNotEmpty()) {
                appendLine()
                appendLine("**Main entry points:**")
                mainEntryPoints.forEach { appendLine("  - $it") }
            }
            if (risks.isNotEmpty()) {
                appendLine()
                appendLine("**Risks / code smells:**")
                risks.forEach { appendLine("  - $it") }
            }
            if (recommendedImprovements.isNotEmpty()) {
                appendLine()
                appendLine("**Recommended next improvements:**")
                recommendedImprovements.forEach { appendLine("  - $it") }
            }
            appendLine()
            appendLine("_Files inspected: ${inspectedFiles.size}_")
        }.trimEnd()
    }

    fun getCached(): ProjectSnapshot? = cachedSnapshot.get()

    fun buildSnapshot(onDone: (ProjectSnapshot) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val snapshot = buildSnapshotSync()
                cachedSnapshot.set(snapshot)
                onDone(snapshot)
            } catch (e: Exception) {
                log.warn("ProjectSnapshot build failed: ${e.message}")
            }
        }
    }

    fun buildSnapshotSync(): ProjectSnapshot {
        val files = runCatching { contextService.listFiles(null) }.getOrElse { emptyList() }
        val inspected = mutableListOf<String>()

        fun readOrNull(path: String): String? {
            return runCatching { contextService.readFile(path).also { inspected += path } }.getOrNull()
        }

        val buildGradle = readOrNull("build.gradle.kts") ?: readOrNull("build.gradle") ?: ""
        val settings = readOrNull("settings.gradle.kts") ?: readOrNull("settings.gradle") ?: ""
        val allContent = (buildGradle + settings).lowercase()
        val fileNames = files.map { it.lowercase() }

        // Tech stack
        val techStack = mutableListOf<String>()
        if (fileNames.any { it.endsWith(".kt") || it.endsWith(".kts") }) techStack += "Kotlin"
        if (fileNames.any { it.endsWith(".java") }) techStack += "Java"
        if (fileNames.any { it.endsWith(".swift") }) techStack += "Swift"
        if (fileNames.any { it.endsWith(".py") }) techStack += "Python"
        if (fileNames.any { it.endsWith(".ts") || it.endsWith(".tsx") }) techStack += "TypeScript"
        if ("kotlin-multiplatform" in allContent || "kmp" in allContent) techStack += "KMP"
        if ("android" in allContent) techStack += "Android"
        if (fileNames.any { it.contains("compose") }) techStack += "Jetpack Compose"

        // Build tools
        val buildTools = mutableListOf<String>()
        if (buildGradle.isNotBlank()) buildTools += "Gradle"
        if (fileNames.any { it.endsWith("pom.xml") }) buildTools += "Maven"
        if (fileNames.any { it.endsWith("package.json") }) buildTools += "npm"

        // Modules
        val modules = Regex("""include\s*\(\s*["']([^"']+)["']""")
            .findAll(settings)
            .map { it.groupValues[1] }
            .toList()

        // Architecture
        val architectureStyle = when {
            allContent.contains("clean architecture") || fileNames.any { "domain" in it && "usecase" in it } ->
                "Clean Architecture"
            fileNames.any { "viewmodel" in it } && fileNames.any { "repository" in it } ->
                "MVVM + Repository"
            fileNames.any { "presenter" in it } -> "MVP"
            fileNames.any { "viewmodel" in it } -> "MVVM"
            else -> "Unknown (inspect source directories)"
        }

        // UI framework
        val uiFramework = when {
            "compose" in allContent -> "Jetpack Compose"
            fileNames.any { it.endsWith(".xml") && "layout" in it } -> "XML Layouts (View system)"
            fileNames.any { it.endsWith(".swift") } -> "SwiftUI / UIKit"
            fileNames.any { it.endsWith(".tsx") || it.endsWith(".jsx") } -> "React"
            else -> "Unknown"
        }

        // DI
        val di = when {
            "hilt" in allContent  -> "Hilt"
            "koin" in allContent  -> "Koin"
            "dagger" in allContent -> "Dagger"
            "kodein" in allContent -> "Kodein"
            else -> "None detected"
        }

        // Networking
        val networking = when {
            "retrofit" in allContent -> "Retrofit"
            "ktor" in allContent     -> "Ktor"
            "okhttp" in allContent   -> "OkHttp"
            "volley" in allContent   -> "Volley"
            else -> "None detected"
        }

        // Storage
        val storage = when {
            "room" in allContent      -> "Room"
            "realm" in allContent     -> "Realm"
            "sqldelight" in allContent -> "SQLDelight"
            "datastore" in allContent  -> "DataStore"
            else -> "None detected"
        }

        // Test setup
        val testSetup = buildString {
            if ("junit" in allContent) append("JUnit ")
            if ("kotest" in allContent) append("Kotest ")
            if ("mockk" in allContent) append("MockK ")
            if ("mockito" in allContent) append("Mockito ")
        }.trim().ifBlank { "Unknown" }

        // Entry points
        val mainEntryPoints = files.filter { f ->
            val base = f.substringAfterLast('/').lowercase()
            base == "main.kt" || base == "app.kt" || base == "application.kt" ||
                base == "mainactivity.kt" || base == "mainviewmodel.kt" ||
                base.contains("application") || base.contains("appmodule")
        }.take(5)

        // Risks
        val risks = mutableListOf<String>()
        val srcFiles = files.filter { it.endsWith(".kt") || it.endsWith(".java") }
        if (srcFiles.size > 200) risks += "Large codebase (${srcFiles.size} source files) — consider modularisation"
        if (modules.isEmpty() && srcFiles.size > 50) risks += "Single-module project — multi-module split may improve build times"
        if (testSetup == "Unknown") risks += "No test framework detected — add tests"
        val testFiles = files.filter { "test" in it.lowercase() }
        if (srcFiles.isNotEmpty() && testFiles.size < srcFiles.size / 10) {
            risks += "Low test-to-source ratio (${testFiles.size} tests vs ${srcFiles.size} source files)"
        }

        val improvements = mutableListOf<String>()
        if ("Unknown" in architectureStyle) improvements += "Define a clear architecture pattern (e.g. MVVM, Clean)"
        if (di == "None detected" && srcFiles.size > 20) improvements += "Add dependency injection (Hilt or Koin)"
        if (networking == "None detected" && srcFiles.size > 10) improvements += "Evaluate adding a typed HTTP client (Retrofit or Ktor)"
        if (risks.any { "test" in it.lowercase() }) improvements += "Increase test coverage — use 'Which tests are missing?' to find gaps"

        return ProjectSnapshot(
            projectName = project.name,
            techStack = techStack.ifEmpty { listOf("Unknown") },
            modules = modules,
            architectureStyle = architectureStyle,
            mainEntryPoints = mainEntryPoints,
            testSetup = testSetup,
            buildTools = buildTools.ifEmpty { listOf("Unknown") },
            uiFramework = uiFramework,
            dependencyInjection = di,
            networkingLayer = networking,
            storageLayer = storage,
            risks = risks,
            recommendedImprovements = improvements,
            inspectedFiles = inspected,
            confidence = when {
                buildGradle.isNotBlank() && files.size > 5 -> "HIGH"
                files.isNotEmpty() -> "MEDIUM"
                else -> "LOW"
            },
        )
    }
}
