package com.llamatik.teammate.ui_repair

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.context.ProjectContextService

@Service(Service.Level.PROJECT)
class UiRepairService(private val project: Project) {

    data class UiCandidateFile(
        val path: String,
        val framework: String,
        val confidence: String,
        val reason: String,
    )

    fun detectFramework(): String {
        val ctx = ProjectContextService(project)
        val files = runCatching { ctx.listFiles(null) }.getOrDefault(emptyList())
        val sample = files.take(20).mapNotNull { runCatching { ctx.readFile(it) }.getOrNull() }.joinToString("\n").take(10000)
        return when {
            sample.contains("@Composable") -> "Compose"
            files.any { it.endsWith(".xml") && it.contains("layout") } -> "Android XML Layout"
            files.any { it.endsWith(".tsx") || it.endsWith(".jsx") } -> "React"
            files.any { it.endsWith(".swift") } -> "SwiftUI"
            else -> "Unknown"
        }
    }

    fun findCandidateFiles(screenHint: String = ""): List<UiCandidateFile> {
        val ctx = ProjectContextService(project)
        val files = runCatching { ctx.listFiles(null) }.getOrDefault(emptyList())
        val framework = detectFramework()
        val candidates = mutableListOf<UiCandidateFile>()
        files.forEach { path ->
            val lower = path.lowercase()
            val isUiFile = when (framework) {
                "Compose" -> path.endsWith(".kt") && (lower.contains("screen") || lower.contains("composable") || lower.contains("ui"))
                "Android XML Layout" -> path.endsWith(".xml") && lower.contains("layout")
                "React" -> path.endsWith(".tsx") || path.endsWith(".jsx")
                "SwiftUI" -> path.endsWith(".swift") && lower.contains("view")
                else -> false
            }
            if (!isUiFile) return@forEach
            val matchesHint = screenHint.isBlank() || lower.contains(screenHint.lowercase())
            val confidence = when {
                matchesHint && lower.contains("screen") -> "high"
                matchesHint -> "medium"
                lower.contains("screen") -> "medium"
                else -> "low"
            }
            if (confidence != "low" || candidates.size < 5) {
                candidates += UiCandidateFile(
                    path = path,
                    framework = framework,
                    confidence = confidence,
                    reason = buildReason(path, screenHint),
                )
            }
        }
        return candidates.sortedByDescending { it.confidence }.take(10)
    }

    fun buildAnalysisPrompt(screenHint: String, maxFileChars: Int = 2000): String {
        val ctx = ProjectContextService(project)
        val candidates = findCandidateFiles(screenHint)
        val framework = detectFramework()
        return buildString {
            appendLine("Framework: $framework")
            if (candidates.isEmpty()) {
                appendLine("No candidate UI files found. Provide the file path explicitly.")
                return@buildString
            }
            appendLine("Candidate UI files:")
            candidates.take(3).forEach { c ->
                appendLine("  ${c.path} [${c.confidence}] — ${c.reason}")
                val content = runCatching { ctx.readFile(c.path) }.getOrNull()
                if (!content.isNullOrBlank()) {
                    appendLine("  ```")
                    appendLine(content.take(maxFileChars))
                    appendLine("  ```")
                }
            }
        }.trim()
    }

    private fun buildReason(path: String, hint: String): String {
        val name = path.substringAfterLast('/')
        return when {
            hint.isNotBlank() && path.contains(hint, ignoreCase = true) -> "matches '$hint'"
            path.contains("screen", ignoreCase = true) -> "contains 'screen'"
            path.contains("composable", ignoreCase = true) -> "Composable file"
            path.contains("layout", ignoreCase = true) -> "layout file"
            else -> "UI file"
        }
    }
}
