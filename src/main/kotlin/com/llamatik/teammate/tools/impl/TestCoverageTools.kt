package com.llamatik.teammate.tools.impl

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.testing.TestCoverageHeuristicService
import com.llamatik.teammate.tools.LlamatikTool
import com.llamatik.teammate.tools.ToolCall
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolResult

class TestsMissingAnalyzeTool(project: Project) : BaseProjectTool(project) {
    override val name = "tests_missing_analyze"
    override val description = "Heuristic analysis of which source files lack test coverage. No arguments required."

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val svc = context.project.service<TestCoverageHeuristicService>()
        val report = runCatching { svc.analyze() }.getOrElse { e ->
            return ToolResult(name, false, "Test analysis failed: ${e.message}")
        }

        val text = buildString {
            appendLine("Missing test analysis (${report.confidence.name} confidence)")
            appendLine("Disclaimer: ${report.disclaimer}")
            appendLine()
            appendLine("Test framework detected: ${report.detectedFramework.name}")
            appendLine()
            appendLine("Evidence:")
            report.evidence.forEach { appendLine("  • $it") }
            appendLine()
            if (report.untestedFiles.isNotEmpty()) {
                appendLine("Untested source files (${report.untestedFiles.size}):")
                report.untestedFiles.take(20).forEach { appendLine("  - $it") }
                if (report.untestedFiles.size > 20) appendLine("  … and ${report.untestedFiles.size - 20} more")
            } else {
                appendLine("No obviously untested files detected.")
            }
            if (report.partiallyTestedFiles.isNotEmpty()) {
                appendLine()
                appendLine("Partially tested (large classes, may have missing method coverage):")
                report.partiallyTestedFiles.take(10).forEach { appendLine("  - $it") }
            }
            if (report.suggestedTestCases.isNotEmpty()) {
                appendLine()
                appendLine("Suggested test files to create:")
                report.suggestedTestCases.take(10).forEach { s ->
                    appendLine("  ${s.className} → ${s.suggestedTestFile}")
                    appendLine("    Reason: ${s.reason}")
                }
            }
        }.trim()
        return ToolResult(name, true, text)
    }
}
