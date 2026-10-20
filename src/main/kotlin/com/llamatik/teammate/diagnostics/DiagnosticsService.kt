package com.llamatik.teammate.diagnostics

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.context.ProjectIndexingState
import com.llamatik.teammate.git.GitContextService
import com.llamatik.teammate.infrastructure.HardwareDetector
import com.llamatik.teammate.infrastructure.ModelRuntimeTracker
import com.llamatik.teammate.settings.LlamatikAppSettings
import com.llamatik.teammate.util.PluginLogger
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class DiagnosticsService(private val project: Project) {

    private val log = PluginLogger.logger<DiagnosticsService>()

    enum class CheckStatus { PASS, WARN, FAIL }

    data class DiagnosticCheck(
        val name: String,
        val status: CheckStatus,
        val detail: String,
        val suggestion: String = "",
    )

    data class DiagnosticsReport(
        val checks: List<DiagnosticCheck>,
        val passCount: Int,
        val warnCount: Int,
        val failCount: Int,
    ) {
        val isHealthy: Boolean get() = failCount == 0
    }

    fun run(): DiagnosticsReport {
        val checks = listOf(
            checkModelFile(),
            checkNativeLib(),
            checkMmprojIfConfigured(),
            checkRam(),
            checkDisk(),
            checkJavaVersion(),
            checkGradleWrapper(),
            checkGitAvailability(),
            checkProjectIndexing(),
            checkWritePermissions(),
            checkSettings(),
            checkGpuLayers(),
        )
        return DiagnosticsReport(
            checks = checks,
            passCount = checks.count { it.status == CheckStatus.PASS },
            warnCount = checks.count { it.status == CheckStatus.WARN },
            failCount = checks.count { it.status == CheckStatus.FAIL },
        )
    }

    fun formatReport(report: DiagnosticsReport, includeSensitive: Boolean = false): String = buildString {
        appendLine("Llamatik Code Diagnostics")
        appendLine("=".repeat(40))
        appendLine("Pass: ${report.passCount}  Warn: ${report.warnCount}  Fail: ${report.failCount}")
        appendLine()
        report.checks.forEach { c ->
            val icon = when (c.status) { CheckStatus.PASS -> "✓"; CheckStatus.WARN -> "⚠"; CheckStatus.FAIL -> "✗" }
            appendLine("$icon ${c.name}")
            if (!includeSensitive && c.detail.contains("/Users") && c.detail.contains(".gguf")) {
                appendLine("  [path redacted — enable 'include sensitive' to show]")
            } else {
                appendLine("  ${c.detail}")
            }
            if (c.suggestion.isNotBlank() && c.status != CheckStatus.PASS) {
                appendLine("  → ${c.suggestion}")
            }
        }
    }.trim()

    fun fixSuggestions(report: DiagnosticsReport): List<String> =
        report.checks.filter { it.status != CheckStatus.PASS && it.suggestion.isNotBlank() }
            .map { "${it.name}: ${it.suggestion}" }

    private fun checkModelFile(): DiagnosticCheck {
        val settings = service<LlamatikAppSettings>().data()
        val path = settings.modelPath.ifBlank { settings.modelIdentifier }
        if (path.isBlank()) return DiagnosticCheck("Model file", CheckStatus.FAIL,
            "No model configured", "Select a .gguf model in Settings → Llamatik Code")
        val file = Path.of(path)
        return when {
            !Files.exists(file) -> DiagnosticCheck("Model file", CheckStatus.FAIL,
                "File not found: ${file.fileName}", "Re-download or re-select the model file")
            !path.lowercase().endsWith(".gguf") -> DiagnosticCheck("Model file", CheckStatus.WARN,
                "File does not have .gguf extension", "Ensure you selected a valid GGUF model")
            Files.size(file) < 1_000_000 -> DiagnosticCheck("Model file", CheckStatus.WARN,
                "File is very small (${Files.size(file)} bytes) — may be corrupt", "Re-download the model")
            else -> {
                val tracker = service<ModelRuntimeTracker>()
                val loaded = if (tracker.isModelLoaded()) " (currently loaded)" else " (not loaded)"
                DiagnosticCheck("Model file", CheckStatus.PASS, "${file.fileName}$loaded")
            }
        }
    }

    private fun checkNativeLib(): DiagnosticCheck {
        return try {
            Class.forName("com.llamatik.core.platform.LlamaBridge")
            DiagnosticCheck("Native library", CheckStatus.PASS, "LlamaBridge available")
        } catch (e: Exception) {
            val root = generateSequence(e as Throwable) { it.cause }
                .firstOrNull { it is UnsatisfiedLinkError || it is LinkageError }
            val detail = root?.message ?: e.message ?: e.javaClass.simpleName
            DiagnosticCheck(
                "Native library", CheckStatus.FAIL,
                "LlamaBridge not available: $detail",
                "Reinstall the plugin. If the problem persists, report it at github.com/ferranpons/Llamatik-Code-Issue-Tracker",
            )
        }
    }

    private fun checkMmprojIfConfigured(): DiagnosticCheck {
        val settings = service<LlamatikAppSettings>().data()
        val mmprojPath = settings.mmprojPath
        if (mmprojPath.isBlank()) return DiagnosticCheck("mmproj (vision)", CheckStatus.PASS,
            "Not configured — vision disabled")
        val file = Path.of(mmprojPath)
        return if (Files.exists(file)) DiagnosticCheck("mmproj (vision)", CheckStatus.PASS,
            "${file.fileName} found")
        else DiagnosticCheck("mmproj (vision)", CheckStatus.WARN,
            "mmproj file not found: ${file.fileName}",
            "Re-download or clear the mmproj path in settings")
    }

    private fun checkRam(): DiagnosticCheck {
        val hw = HardwareDetector.detect()
        val ramGb = hw.availableRamMb / 1024
        return when {
            hw.availableRamMb < 4096 -> DiagnosticCheck("RAM", CheckStatus.FAIL,
                "${ramGb}GB system RAM detected", "Minimum 8GB recommended; 4B models need ~4GB")
            hw.availableRamMb < 8192 -> DiagnosticCheck("RAM", CheckStatus.WARN,
                "${ramGb}GB RAM — may run slowly", "Use a smaller model (e.g. 4B Q4_K_M)")
            else -> DiagnosticCheck("RAM", CheckStatus.PASS, "${ramGb}GB RAM")
        }
    }

    private fun checkDisk(): DiagnosticCheck {
        val hw = HardwareDetector.detect()
        val freeGb = hw.freeDiskMb / 1024
        return when {
            hw.freeDiskMb < 2048 -> DiagnosticCheck("Disk space", CheckStatus.FAIL,
                "${freeGb}GB free", "Free at least 5GB for model storage")
            hw.freeDiskMb < 5120 -> DiagnosticCheck("Disk space", CheckStatus.WARN,
                "${freeGb}GB free", "Consider freeing disk space before downloading large models")
            else -> DiagnosticCheck("Disk space", CheckStatus.PASS, "${freeGb}GB free")
        }
    }

    private fun checkJavaVersion(): DiagnosticCheck {
        val version = System.getProperty("java.version") ?: "unknown"
        val major = version.substringBefore(".").toIntOrNull()
            ?: version.substringBefore("-").toIntOrNull() ?: 0
        return when {
            major >= 17 -> DiagnosticCheck("Java version", CheckStatus.PASS, "Java $version")
            major > 0 -> DiagnosticCheck("Java version", CheckStatus.WARN, "Java $version — 17+ recommended")
            else -> DiagnosticCheck("Java version", CheckStatus.WARN, "Could not detect Java version")
        }
    }

    private fun checkGradleWrapper(): DiagnosticCheck {
        val base = project.basePath ?: return DiagnosticCheck("Gradle wrapper", CheckStatus.WARN,
            "No project root")
        val gradlew = File(base, "gradlew")
        return if (gradlew.exists()) DiagnosticCheck("Gradle wrapper", CheckStatus.PASS,
            "gradlew found at project root")
        else DiagnosticCheck("Gradle wrapper", CheckStatus.WARN,
            "No gradlew in project root",
            "Build tools (build verification, static analysis) require gradlew")
    }

    private fun checkGitAvailability(): DiagnosticCheck {
        val git = project.service<GitContextService>()
        return if (git.isGitRepo()) DiagnosticCheck("Git repository", CheckStatus.PASS,
            "Git repo detected, branch: ${git.branch()}")
        else DiagnosticCheck("Git repository", CheckStatus.WARN,
            "No .git directory found",
            "Git tools (git_status, git_diff) require a git repository")
    }

    private fun checkProjectIndexing(): DiagnosticCheck {
        val state = project.service<ProjectIndexingState>()
        return when (state.state) {
            ProjectIndexingState.State.READY -> DiagnosticCheck("Project index", CheckStatus.PASS,
                "Index ready — ${state.indexedFileCount} files")
            ProjectIndexingState.State.INDEXING -> DiagnosticCheck("Project index", CheckStatus.WARN,
                "Indexing in progress (${state.indexedFileCount}/${state.totalFileCount})")
            ProjectIndexingState.State.FAILED -> DiagnosticCheck("Project index", CheckStatus.WARN,
                "Index build failed", "Call index_refresh to rebuild")
            ProjectIndexingState.State.IDLE -> DiagnosticCheck("Project index", CheckStatus.WARN,
                "Index not built", "Call index_refresh to enable semantic search")
        }
    }

    private fun checkWritePermissions(): DiagnosticCheck {
        val base = project.basePath ?: return DiagnosticCheck("Write permissions", CheckStatus.WARN, "No project root")
        val canWrite = File(base).canWrite()
        return if (canWrite) DiagnosticCheck("Write permissions", CheckStatus.PASS, "Project root is writable")
        else DiagnosticCheck("Write permissions", CheckStatus.FAIL,
            "Project root is read-only: $base",
            "Check file system permissions — the agent cannot apply edits")
    }

    private fun checkGpuLayers(): DiagnosticCheck {
        val s = service<LlamatikAppSettings>().data()
        val gpuLayers = s.gpuLayers
        return when {
            gpuLayers == 0 -> DiagnosticCheck(
                "GPU acceleration", CheckStatus.WARN,
                "GPU layers = 0 — model runs entirely on CPU",
                "Set GPU layers to -1 (all) in Settings → Inference to enable Metal acceleration. " +
                    "CPU-only inference of large models (≥14B) with Low priority can stall for hours.",
            )
            gpuLayers < 0 -> DiagnosticCheck("GPU acceleration", CheckStatus.PASS,
                "GPU layers = all (Metal acceleration enabled)")
            else -> DiagnosticCheck("GPU acceleration", CheckStatus.PASS,
                "GPU layers = $gpuLayers")
        }
    }

    private fun checkSettings(): DiagnosticCheck {
        val s = service<LlamatikAppSettings>().data()
        val issues = mutableListOf<String>()
        if (s.contextSize < 512) issues += "contextSize=${s.contextSize} (min 512)"
        if (s.batchSize > s.contextSize) issues += "batchSize(${s.batchSize}) > contextSize(${s.contextSize})"
        if (s.numThreads < 1) issues += "numThreads=${s.numThreads} (min 1)"
        return if (issues.isEmpty()) DiagnosticCheck("Settings", CheckStatus.PASS,
            "All inference settings valid")
        else DiagnosticCheck("Settings", CheckStatus.WARN,
            "Potential issues: ${issues.joinToString("; ")}",
            "Fix in Settings → Llamatik Code → Inference")
    }
}
