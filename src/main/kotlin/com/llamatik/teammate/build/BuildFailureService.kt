package com.llamatik.teammate.build

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.util.PluginLogger

@Service(Service.Level.PROJECT)
class BuildFailureService(private val project: Project) {

    private val log = PluginLogger.logger<BuildFailureService>()

    enum class FailureKind {
        KOTLIN_COMPILE, JAVA_COMPILE, GRADLE_CONFIG, ANDROID_RESOURCE,
        TEST_FAILURE, DEPENDENCY_RESOLUTION, UNKNOWN
    }

    data class BuildError(
        val kind: FailureKind,
        val file: String,
        val line: Int,
        val message: String,
        val rawLine: String,
    )

    data class BuildFailureSummary(
        val errors: List<BuildError>,
        val rootCause: String,
        val affectedFiles: List<String>,
        val plainExplanation: String,
    )

    fun parse(rawOutput: String): List<BuildError> {
        val errors = mutableListOf<BuildError>()

        // Kotlin compiler: e: /abs/path/File.kt: (10, 5): Unresolved reference: foo
        val ktRegex = Regex("""^e:\s*(.+?\.kt):\s*\((\d+),\s*\d+\):\s*(.+)$""", RegexOption.MULTILINE)
        ktRegex.findAll(rawOutput).forEach { m ->
            errors += BuildError(FailureKind.KOTLIN_COMPILE,
                relativize(m.groupValues[1]), m.groupValues[2].toIntOrNull() ?: 0,
                m.groupValues[3].trim(), m.value.trim())
        }

        // Java compiler: File.java:42: error: cannot find symbol
        val javaRegex = Regex("""^(.+?\.java):(\d+):\s*error:\s*(.+)$""", RegexOption.MULTILINE)
        javaRegex.findAll(rawOutput).forEach { m ->
            errors += BuildError(FailureKind.JAVA_COMPILE,
                relativize(m.groupValues[1]), m.groupValues[2].toIntOrNull() ?: 0,
                m.groupValues[3].trim(), m.value.trim())
        }

        // Gradle config failure
        if (rawOutput.contains("Could not resolve") || rawOutput.contains("Could not find")) {
            val depRegex = Regex("""Could not (?:resolve|find) ([^\n]+?)\.?\s*$""", RegexOption.MULTILINE)
            depRegex.findAll(rawOutput).forEach { m ->
                errors += BuildError(FailureKind.DEPENDENCY_RESOLUTION, "", 0,
                    "Dependency not found: ${m.groupValues[1]}", m.value.trim())
            }
        }

        // Android resource errors: error: resource ... not found
        val androidRegex = Regex("""^(.+?\.xml):(\d+):\s*error:\s*(.+)$""", RegexOption.MULTILINE)
        androidRegex.findAll(rawOutput).forEach { m ->
            errors += BuildError(FailureKind.ANDROID_RESOURCE,
                relativize(m.groupValues[1]), m.groupValues[2].toIntOrNull() ?: 0,
                m.groupValues[3].trim(), m.value.trim())
        }

        // Test failures
        if (rawOutput.contains("FAILED") && rawOutput.contains("tests")) {
            val testRegex = Regex("""(\w+) > (\w+) FAILED""")
            testRegex.findAll(rawOutput).forEach { m ->
                errors += BuildError(FailureKind.TEST_FAILURE, m.groupValues[1], 0,
                    "Test failed: ${m.groupValues[1]}.${m.groupValues[2]}", m.value.trim())
            }
        }

        return errors.take(50)
    }

    fun summarize(rawOutput: String): BuildFailureSummary {
        val errors = parse(rawOutput)
        val affectedFiles = errors.map { it.file }.filter { it.isNotBlank() }.distinct()

        val rootCause = when {
            errors.isEmpty() -> extractGradleFailureBanner(rawOutput)
            errors.first().kind == FailureKind.DEPENDENCY_RESOLUTION ->
                "Dependency resolution failure: ${errors.first().message}"
            errors.first().kind == FailureKind.KOTLIN_COMPILE ->
                "Kotlin compilation error in ${errors.first().file}:${errors.first().line} — ${errors.first().message}"
            errors.first().kind == FailureKind.JAVA_COMPILE ->
                "Java compilation error in ${errors.first().file}:${errors.first().line} — ${errors.first().message}"
            errors.first().kind == FailureKind.TEST_FAILURE ->
                "Test failures: ${errors.take(3).joinToString("; ") { it.message }}"
            else -> errors.first().message
        }

        val explanation = buildString {
            appendLine("Build failed with ${errors.size} error(s).")
            if (affectedFiles.isNotEmpty()) appendLine("Affected: ${affectedFiles.take(5).joinToString(", ")}")
            appendLine()
            appendLine("Root cause: $rootCause")
            if (errors.size > 1) {
                appendLine()
                appendLine("Other errors:")
                errors.drop(1).take(4).forEach { appendLine("  - ${it.file}:${it.line} ${it.message}") }
            }
        }.trim()

        return BuildFailureSummary(errors, rootCause, affectedFiles, explanation)
    }

    private fun extractGradleFailureBanner(raw: String): String {
        val lines = raw.lines()
        val failIdx = lines.indexOfFirst { it.contains("FAILURE:") }
        if (failIdx >= 0) return lines.drop(failIdx).take(3).joinToString(" ").take(200)
        return "Unknown build failure"
    }

    private fun relativize(path: String): String {
        val base = project.basePath ?: return path
        return if (path.startsWith(base)) path.removePrefix(base).trimStart('/') else path
    }
}
