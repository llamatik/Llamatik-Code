package com.llamatik.teammate.codehealth

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.settings.LlamatikAppSettings
import com.llamatik.teammate.testing.TestCoverageHeuristicService
import com.llamatik.teammate.util.PluginLogger
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class BackgroundInsightAnalyzer(private val project: Project) {

    private val log = PluginLogger.logger<BackgroundInsightAnalyzer>()
    private val running = AtomicBoolean(false)

    data class AnalysisResult(
        val insights: List<CodeHealthInsight>,
        val filesScanned: Int,
        val skippedReason: String = "",
        val durationMs: Long = 0L,
    )

    fun analyzeFiles(files: List<String>, trigger: RunTrigger): AnalysisResult {
        val budgetSvc = service<CodeHealthBudgetService>()
        if (!budgetSvc.isEnabled()) {
            log.info("BackgroundInsightAnalyzer: skipped — feature disabled")
            return AnalysisResult(emptyList(), 0, "feature disabled")
        }
        val mode = budgetSvc.effectiveMode()
        if (mode == AnalysisMode.OFF) {
            log.info("BackgroundInsightAnalyzer: skipped — mode is OFF")
            return AnalysisResult(emptyList(), 0, "mode is OFF")
        }
        if (!running.compareAndSet(false, true)) {
            log.info("BackgroundInsightAnalyzer: skipped — already running")
            return AnalysisResult(emptyList(), 0, "already running")
        }

        val start = System.currentTimeMillis()
        val maxFiles = budgetSvc.maxFiles()
        val maxRuntime = budgetSvc.maxRuntimeMs()
        val insights = mutableListOf<CodeHealthInsight>()
        val ignoredDirs = ignoredDirs()
        val filesToScan = files
            .filter { !isIgnored(it, ignoredDirs) }
            .take(maxFiles)

        try {
            log.info("BackgroundInsightAnalyzer: scanning ${filesToScan.size} files (trigger=$trigger, mode=$mode)")
            for (file in filesToScan) {
                if (System.currentTimeMillis() - start > maxRuntime) {
                    log.info("BackgroundInsightAnalyzer: runtime budget exceeded at ${insights.size} insights")
                    break
                }
                insights.addAll(analyzeFile(file, mode))
            }

            if (service<LlamatikAppSettings>().data().codeHealthTestAwareness) {
                insights.addAll(analyzeMissingTests(filesToScan))
            }

            val duration = System.currentTimeMillis() - start
            project.service<CodeHealthInsightStore>().recordAnalysisRun(duration, filesToScan.size)
            log.info("BackgroundInsightAnalyzer: done in ${duration}ms, ${insights.size} insights")
            return AnalysisResult(insights, filesToScan.size, durationMs = duration)
        } finally {
            running.set(false)
        }
    }

    private fun analyzeFile(filePath: String, mode: AnalysisMode): List<CodeHealthInsight> {
        if (!filePath.endsWith(".kt") && !filePath.endsWith(".java")) return emptyList()
        val content = readFile(filePath) ?: return emptyList()
        val lines = content.lines()
        val insights = mutableListOf<CodeHealthInsight>()

        val longFileThreshold = if (mode == AnalysisMode.LIGHT) 500 else 300
        if (lines.size > longFileThreshold) {
            insights.add(CodeHealthInsight(
                id = "complexity-${filePath.hashCode()}-size",
                type = InsightType.COMPLEXITY,
                severity = InsightSeverity.WARNING,
                title = "Large file (${lines.size} lines)",
                description = "This file is ${lines.size} lines long. Consider splitting it into smaller, focused classes.",
                filePath = filePath,
                suggestedAction = "Extract classes or functions into separate files",
            ))
        }

        val todoLines = lines.mapIndexedNotNull { i, line ->
            val upper = line.uppercase()
            if ("TODO" in upper || "FIXME" in upper || "HACK" in upper || "XXX" in upper) i + 1 else null
        }
        if (todoLines.size >= 3) {
            insights.add(CodeHealthInsight(
                id = "complexity-${filePath.hashCode()}-todos",
                type = InsightType.COMPLEXITY,
                severity = InsightSeverity.INFO,
                title = "${todoLines.size} TODO/FIXME comments",
                description = "Found ${todoLines.size} TODO/FIXME markers (lines: ${todoLines.take(5).joinToString()}).",
                filePath = filePath,
                line = todoLines.first(),
            ))
        }

        val emptyCatchRegex = Regex("""catch\s*\([^)]+\)\s*\{\s*\}""")
        emptyCatchRegex.find(content)?.let { match ->
            val lineNum = content.substring(0, match.range.first).count { it == '\n' } + 1
            insights.add(CodeHealthInsight(
                id = "errorhandling-${filePath.hashCode()}-emptycatch",
                type = InsightType.ERROR_HANDLING,
                severity = InsightSeverity.WARNING,
                title = "Empty catch block",
                description = "An empty catch block silently swallows exceptions.",
                filePath = filePath,
                line = lineNum,
                suggestedAction = "Log the exception or handle it explicitly",
            ))
        }

        if (content.contains("@Deprecated") || content.contains("// deprecated", ignoreCase = true)) {
            insights.add(CodeHealthInsight(
                id = "deprecated-${filePath.hashCode()}",
                type = InsightType.DEPRECATED_API,
                severity = InsightSeverity.INFO,
                title = "Deprecated API usage",
                description = "This file may contain or use deprecated API.",
                filePath = filePath,
            ))
        }

        return insights
    }

    private fun analyzeMissingTests(files: List<String>): List<CodeHealthInsight> {
        return try {
            val svc = project.service<TestCoverageHeuristicService>()
            val report = svc.analyze()
            val checkedPaths = files.toSet()
            report.untestedFiles
                .filter { checkedPaths.isEmpty() || it in checkedPaths }
                .take(10)
                .map { srcFile ->
                    CodeHealthInsight(
                        id = "missingtests-${srcFile.hashCode()}",
                        type = InsightType.MISSING_TESTS,
                        severity = InsightSeverity.WARNING,
                        title = "No tests found for ${srcFile.substringAfterLast('/')}",
                        description = "No matching test file was found for this source file.",
                        filePath = srcFile,
                        suggestedAction = "Generate tests with 'Generate Tests for File' action",
                        source = "test-heuristic",
                    )
                }
        } catch (e: Exception) {
            log.warn("BackgroundInsightAnalyzer: test analysis failed: ${e.message}")
            emptyList()
        }
    }

    private fun readFile(path: String): String? {
        return try {
            val base = project.basePath ?: return null
            java.io.File("$base/$path")
                .takeIf { it.exists() && it.length() < 500_000 }
                ?.readText()
        } catch (e: Exception) {
            null
        }
    }

    private fun ignoredDirs(): Set<String> {
        val raw = runCatching { service<LlamatikAppSettings>().data().ignoredDirectories }.getOrElse { "" }
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
    }

    private fun isIgnored(path: String, ignoredDirs: Set<String>): Boolean =
        ignoredDirs.any { dir -> path.contains("/$dir/") || path.startsWith("$dir/") }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): BackgroundInsightAnalyzer = project.service()
    }
}
