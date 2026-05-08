package com.llamatik.teammate.external.research

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.llamatik.teammate.external.citation.SourceConfidence

@Service(Service.Level.APP)
class ErrorResearchService {

    data class ErrorContext(
        val rawError: String,
        val tool: String = "",
        val framework: String = "",
        val libraryName: String = "",
        val version: String = "",
        val errorCode: String = "",
        val relevantFiles: List<String> = emptyList(),
    )

    fun parseErrorContext(rawError: String): ErrorContext {
        val tool = detectTool(rawError)
        val framework = detectFramework(rawError)
        val libraryName = detectLibraryName(rawError)
        val version = detectVersion(rawError)
        val errorCode = detectErrorCode(rawError)
        val relevantFiles = extractFilePaths(rawError)

        return ErrorContext(
            rawError = rawError,
            tool = tool,
            framework = framework,
            libraryName = libraryName,
            version = version,
            errorCode = errorCode,
            relevantFiles = relevantFiles,
        )
    }

    fun researchError(context: ErrorContext, isPro: Boolean): ResearchReport {
        val orchestrator = service<ResearchOrchestratorService>()

        val query = buildErrorQuery(context)
        val task = ResearchTask(
            question = query,
            localContextSummary = buildContextSummary(context),
            intent = ResearchIntent.BUG_ERROR,
            isPro = isPro,
        )

        return orchestrator.executeResearch(task)
    }

    fun buildFixPlan(context: ErrorContext, report: ResearchReport): String = buildString {
        appendLine("Fix Plan for: ${context.tool.ifBlank { "build" }} error")
        appendLine()
        if (context.errorCode.isNotBlank()) {
            appendLine("Error code: ${context.errorCode}")
        }
        if (context.libraryName.isNotBlank()) {
            appendLine("Library: ${context.libraryName}${if (context.version.isNotBlank()) " v${context.version}" else ""}")
        }
        appendLine()
        appendLine("Diagnosis: ${report.answer.take(500)}")
        appendLine()
        if (report.findings.isNotEmpty()) {
            appendLine("Steps:")
            report.findings.take(3).forEachIndexed { i, f ->
                appendLine("${i + 1}. ${f.claim.take(200)}")
                if (f.suggestedAction.isNotBlank()) appendLine("   Action: ${f.suggestedAction}")
            }
        }
        if (context.relevantFiles.isNotEmpty()) {
            appendLine()
            appendLine("Affected files: ${context.relevantFiles.joinToString(", ")}")
        }
        if (report.sources.isNotEmpty()) {
            appendLine()
            appendLine("Sources:")
            report.sources.take(3).forEach { c ->
                appendLine("- ${c.title}: ${c.url}")
            }
        }
    }.trim()

    private fun detectTool(error: String): String {
        val lower = error.lowercase()
        return when {
            lower.contains("task :") || lower.contains("failure: build failed") || lower.contains("gradlew") -> "Gradle"
            lower.contains("cmake error") || lower.contains("cmakelist") -> "CMake"
            lower.contains("xcodebuild") || lower.contains("clang error") -> "Xcode"
            lower.contains("kotlinc") || lower.contains("error:") && lower.contains(".kt:") -> "Kotlin compiler"
            lower.contains("javac") || lower.contains("error:") && lower.contains(".java:") -> "Java compiler"
            else -> ""
        }
    }

    private fun detectFramework(error: String): String {
        val lower = error.lowercase()
        return when {
            lower.contains("android") || lower.contains("manifest") || lower.contains("aapt") -> "Android"
            lower.contains("compose") && lower.contains("ui") -> "Jetpack Compose"
            lower.contains("multiplatform") || lower.contains("kmp") -> "KMP"
            lower.contains("spring") -> "Spring"
            lower.contains("ktor") -> "Ktor"
            else -> ""
        }
    }

    private fun detectLibraryName(error: String): String {
        // Match common patterns like "Could not resolve: com.foo:bar:1.0"
        val resolveRegex = Regex("""Could not resolve[:\s]+([a-zA-Z0-9._-]+:[a-zA-Z0-9._-]+)""")
        resolveRegex.find(error)?.let { return it.groupValues[1] }

        // Match "Unresolved reference: FooClass"
        val unresolvedRegex = Regex("""Unresolved reference:\s*(\w+)""")
        unresolvedRegex.find(error)?.let { return it.groupValues[1] }

        // Match "Duplicate class com.foo.Bar found in..."
        val duplicateRegex = Regex("""Duplicate class ([a-zA-Z0-9._]+)""")
        duplicateRegex.find(error)?.let { return it.groupValues[1] }

        return ""
    }

    private fun detectVersion(error: String): String {
        val versionRegex = Regex("""(?:version|v|:)(\d+\.\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        return versionRegex.find(error)?.groupValues?.get(1) ?: ""
    }

    private fun detectErrorCode(error: String): String {
        // Gradle task errors
        val taskRegex = Regex("""(Task :[^\s]+)""")
        taskRegex.find(error)?.let { return it.groupValues[1] }

        // Android error codes like AAPT2, D8, R8
        val aaptRegex = Regex("""(AAPT[0-9]+|D8|R8):\s*error""", RegexOption.IGNORE_CASE)
        aaptRegex.find(error)?.let { return it.groupValues[1] }

        // Kotlin/Java error: message
        val kotlinErrorRegex = Regex("""error:\s+(.{0,60})""")
        kotlinErrorRegex.find(error)?.let { return it.groupValues[1].trim() }

        return ""
    }

    private fun extractFilePaths(error: String): List<String> {
        val fileRegex = Regex("""([a-zA-Z0-9_/\\.-]+\.(?:kt|java|gradle|xml|cpp|c|h|swift)(?::\d+)?)""")
        return fileRegex.findAll(error)
            .map { it.groupValues[1] }
            .filter { it.length > 4 }
            .distinct()
            .take(10)
            .toList()
    }

    private fun buildErrorQuery(context: ErrorContext): String {
        val parts = mutableListOf<String>()
        if (context.framework.isNotBlank()) parts.add(context.framework)
        if (context.tool.isNotBlank()) parts.add(context.tool)
        if (context.libraryName.isNotBlank()) parts.add(context.libraryName)
        if (context.errorCode.isNotBlank()) parts.add(context.errorCode.take(60))
        if (parts.isEmpty()) {
            // Fall back to first 100 chars of error
            parts.add(context.rawError.take(100).replace('\n', ' '))
        }
        return parts.joinToString(" ").take(120)
    }

    private fun buildContextSummary(context: ErrorContext): String = buildString {
        if (context.tool.isNotBlank()) append("Tool: ${context.tool}. ")
        if (context.framework.isNotBlank()) append("Framework: ${context.framework}. ")
        if (context.libraryName.isNotBlank()) append("Library: ${context.libraryName}. ")
        if (context.version.isNotBlank()) append("Version: ${context.version}. ")
    }.trim()

    companion object {
        @JvmStatic
        fun getInstance(): ErrorResearchService = service()
    }
}
