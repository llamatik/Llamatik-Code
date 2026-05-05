package com.llamatik.teammate.context

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.index.ProjectIndexService
import com.llamatik.teammate.settings.LlamatikAppSettings
import com.llamatik.teammate.util.PluginLogger

@Service(Service.Level.PROJECT)
class ContextRecoveryService(private val project: Project) {

    private val log = PluginLogger.logger<ContextRecoveryService>()
    private val contextService = ProjectContextService(project)

    data class RecoveryResult(
        val succeeded: Boolean,
        val diagnosis: String,
        val recoveredFiles: List<String>,
        val nextStep: String,
    )

    /**
     * Run when the agent encounters empty search results or a missing project index.
     * Returns a structured diagnosis so the agent can include it in its response.
     */
    fun recover(failureContext: String = ""): RecoveryResult {
        val steps = mutableListOf<String>()
        val recoveredFiles = mutableListOf<String>()

        // 1. Check project root
        val root = contextService.rootPath()
        if (root.isBlank()) {
            return RecoveryResult(
                succeeded = false,
                diagnosis = "Project root is not set. The project may not be fully loaded.",
                recoveredFiles = emptyList(),
                nextStep = "Close and reopen the project, then try again.",
            )
        }
        steps += "Checked project root: $root"

        // 2. Check ignored directories
        val ignored = service<LlamatikAppSettings>().data().ignoredDirectories
        steps += "Ignored directories configured: $ignored"

        // 3. Try list_files with common source patterns
        val sourcePatterns = listOf("src/main", "src", "app/src/main/kotlin", "app/src/main/java", "lib/src")
        for (pattern in sourcePatterns) {
            val files = runCatching { contextService.listFiles(pattern) }.getOrElse { emptyList() }
            if (files.isNotEmpty()) {
                recoveredFiles += files.take(10)
                steps += "Found ${files.size} files under '$pattern'"
                break
            }
        }

        // 4. Check for Gradle/Maven/npm build files
        val buildFiles = listOf("build.gradle.kts", "build.gradle", "pom.xml", "package.json", "Cargo.toml", "go.mod")
        val foundBuild = buildFiles.firstOrNull { file ->
            runCatching { contextService.readFile(file) }.isSuccess
        }
        if (foundBuild != null) {
            steps += "Detected build system file: $foundBuild"
            recoveredFiles += foundBuild
        }

        // 5. Check index status
        val indexStatus = runCatching {
            project.service<ProjectIndexService>().status()
        }.getOrElse { "index service unavailable" }
        steps += "Index status: $indexStatus"

        val indexStale = indexStatus.contains("idle") || indexStatus.contains("0 chunks")

        val diagnosis = buildString {
            if (failureContext.isNotBlank()) appendLine("Failure context: $failureContext")
            appendLine("Recovery steps attempted:")
            steps.forEach { appendLine("  • $it") }
        }.trim()

        val nextStep = when {
            recoveredFiles.isNotEmpty() -> "Found ${recoveredFiles.size} accessible files. Read them to continue."
            indexStale -> "Project index is empty or stale. Trigger a re-index using index_refresh."
            foundBuild == null -> "No build file found. Verify the project is open and source directories exist."
            else -> "Unable to locate source files. Check that ignored-directories settings are not excluding source roots."
        }

        log.info("ContextRecovery: recovered=${recoveredFiles.size} files, indexStale=$indexStale")
        return RecoveryResult(
            succeeded = recoveredFiles.isNotEmpty(),
            diagnosis = diagnosis,
            recoveredFiles = recoveredFiles,
            nextStep = nextStep,
        )
    }
}
