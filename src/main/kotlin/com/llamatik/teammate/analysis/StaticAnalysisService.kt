package com.llamatik.teammate.analysis

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.util.PluginLogger
import java.io.File

@Service(Service.Level.PROJECT)
class StaticAnalysisService(private val project: Project) {

    private val log = PluginLogger.logger<StaticAnalysisService>()

    enum class Severity { ERROR, WARNING, INFO }

    data class Finding(
        val tool: String,
        val file: String,
        val line: Int,
        val column: Int,
        val severity: Severity,
        val rule: String,
        val message: String,
    )

    data class AnalysisResult(
        val tool: String,
        val findings: List<Finding>,
        val rawOutput: String,
        val succeeded: Boolean,
        val errorMessage: String = "",
    )

    data class DetectedTool(
        val id: String,
        val displayName: String,
        val command: String,
    )

    fun detectTools(): List<DetectedTool> {
        val base = project.basePath ?: return emptyList()
        val tools = mutableListOf<DetectedTool>()
        val gradlew = if (File(base, "gradlew").exists()) "./gradlew" else null
        if (gradlew != null) {
            val buildContent = runCatching { File(base, "build.gradle.kts").readText() }.getOrNull()
                ?: runCatching { File(base, "build.gradle").readText() }.getOrNull() ?: ""
            if (buildContent.contains("ktlint", ignoreCase = true))
                tools += DetectedTool("ktlint", "ktlint", "$gradlew ktlintCheck")
            if (buildContent.contains("detekt", ignoreCase = true))
                tools += DetectedTool("detekt", "Detekt", "$gradlew detekt")
            if (File(base, "lint.xml").exists() || buildContent.contains("com.android", ignoreCase = true))
                tools += DetectedTool("android-lint", "Android Lint", "$gradlew lint")
            tools += DetectedTool("gradle-check", "Gradle Check", "$gradlew check -x test")
        }
        if (File(base, "package.json").exists()) {
            val pkgContent = runCatching { File(base, "package.json").readText() }.getOrNull() ?: ""
            if (pkgContent.contains("eslint")) tools += DetectedTool("eslint", "ESLint", "npm run lint")
        }
        if (File(base, "tsconfig.json").exists())
            tools += DetectedTool("tsc", "TypeScript", "npx tsc --noEmit")
        return tools
    }

    fun run(tool: DetectedTool, onDone: (AnalysisResult) -> Unit) {
        val basePath = project.basePath ?: run {
            onDone(AnalysisResult(tool.id, emptyList(), "", false, "No project root"))
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val parts = tool.command.split(" ")
                val cmd = GeneralCommandLine(parts).withWorkDirectory(basePath)
                val result = CapturingProcessHandler(cmd).runProcess(300_000)
                val raw = (result.stdout + result.stderr).take(100_000)
                val findings = parseFindings(tool.id, raw)
                onDone(AnalysisResult(tool.id, findings, raw, result.exitCode == 0))
            } catch (e: Exception) {
                log.warn("static_analysis [${tool.id}] failed: ${e.message}")
                onDone(AnalysisResult(tool.id, emptyList(), "", false, e.message ?: "Failed"))
            }
        }
    }

    fun parseFindings(toolId: String, raw: String): List<Finding> = when (toolId) {
        "ktlint" -> parseKtlint(raw)
        "detekt" -> parseDetekt(raw)
        "android-lint" -> parseLintXml(raw)
        "eslint" -> parseEslint(raw)
        else -> parseGeneric(toolId, raw)
    }

    private fun parseKtlint(raw: String): List<Finding> {
        // Format: path/to/File.kt:10:4: Rule message (rule-id)
        val regex = Regex("""^(.+?):(\d+):(\d+): (.+?) \((.+?)\)$""", RegexOption.MULTILINE)
        return regex.findAll(raw).map { m ->
            Finding("ktlint", m.groupValues[1], m.groupValues[2].toIntOrNull() ?: 0,
                m.groupValues[3].toIntOrNull() ?: 0, Severity.WARNING, m.groupValues[5], m.groupValues[4])
        }.take(200).toList()
    }

    private fun parseDetekt(raw: String): List<Finding> {
        // Format: RuleId - path/to/File.kt:10:4
        val regex = Regex("""^(\S+) - (.+?):(\d+):(\d+)""", RegexOption.MULTILINE)
        return regex.findAll(raw).map { m ->
            Finding("detekt", m.groupValues[2], m.groupValues[3].toIntOrNull() ?: 0,
                m.groupValues[4].toIntOrNull() ?: 0, Severity.WARNING, m.groupValues[1], m.groupValues[1])
        }.take(200).toList()
    }

    private fun parseLintXml(raw: String): List<Finding> {
        // Android Lint XML: <issue id="..." severity="..." message="..." ...><location file="..." line="..."/>
        val issueRegex = Regex("""<issue\s[^>]*id="([^"]*)"[^>]*severity="([^"]*)"[^>]*message="([^"]*)"[^>]*>.*?<location\s[^>]*file="([^"]*)"[^>]*line="(\d*)"[^>]*/?>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE))
        return issueRegex.findAll(raw).map { m ->
            val sev = when (m.groupValues[2].lowercase()) {
                "error", "fatal" -> Severity.ERROR
                "warning" -> Severity.WARNING
                else -> Severity.INFO
            }
            Finding("android-lint", m.groupValues[4], m.groupValues[5].toIntOrNull() ?: 0,
                0, sev, m.groupValues[1], m.groupValues[3])
        }.take(200).toList()
    }

    private fun parseEslint(raw: String): List<Finding> {
        // ESLint default format: /path/file.ts  line:col  severity  message  rule
        val regex = Regex("""^\s+(\d+):(\d+)\s+(error|warning)\s+(.+?)\s{2,}(.+)$""", RegexOption.MULTILINE)
        var currentFile = ""
        val findings = mutableListOf<Finding>()
        raw.lines().forEach { line ->
            if (line.isNotBlank() && !line.trimStart().first().isDigit() && line.contains("/")) {
                currentFile = line.trim()
            }
            val m = regex.find(line) ?: return@forEach
            val sev = if (m.groupValues[3] == "error") Severity.ERROR else Severity.WARNING
            findings += Finding("eslint", currentFile, m.groupValues[1].toIntOrNull() ?: 0,
                m.groupValues[2].toIntOrNull() ?: 0, sev, m.groupValues[5].trim(), m.groupValues[4].trim())
        }
        return findings.take(200)
    }

    private fun parseGeneric(tool: String, raw: String): List<Finding> {
        // Generic: file:line: message
        val regex = Regex("""^(.+?):(\d+): (.+)$""", RegexOption.MULTILINE)
        return regex.findAll(raw).map { m ->
            Finding(tool, m.groupValues[1], m.groupValues[2].toIntOrNull() ?: 0, 0, Severity.WARNING, "", m.groupValues[3])
        }.take(100).toList()
    }
}
