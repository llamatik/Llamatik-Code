package com.llamatik.teammate.tools.impl

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.kmp.KmpNativeDiagnosticService
import com.llamatik.teammate.tools.LlamatikTool
import com.llamatik.teammate.tools.ToolCall
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolResult

class KmpNativeDiagnoseTool(project: Project) : BaseProjectTool(project) {
    override val name = "kmp_native_diagnose"
    override val description = "Diagnose KMP/native build failures: unresolved symbols, CInterop, .def, CMake, architecture issues. Arguments: build_log (optional)"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val buildLog = call.arguments["build_log"] ?: ""
        val svc = context.project.service<KmpNativeDiagnosticService>()
        val report = runCatching { svc.diagnose(buildLog) }.getOrElse { e ->
            return ToolResult(name, false, "KMP diagnostic failed: ${e.message}")
        }

        val text = buildString {
            if (!report.isKmpProject) {
                appendLine("No Kotlin Multiplatform project detected in this directory.")
                appendLine("Evidence: ${report.evidence.joinToString("; ")}")
                return@buildString
            }
            appendLine("KMP/Native Diagnostic Report")
            appendLine("============================")
            appendLine("Detected native targets: ${report.detectedTargets.joinToString(", ").ifBlank { "none" }}")
            appendLine("Gradle files: ${report.gradleFiles.joinToString(", ")}")
            appendLine(".def files: ${report.defFiles.joinToString(", ").ifBlank { "none" }}")
            appendLine("CMakeLists: ${report.cmakeFiles.joinToString(", ").ifBlank { "none" }}")
            appendLine()
            if (report.issues.isEmpty()) {
                appendLine("No issues detected from static analysis.")
                if (buildLog.isBlank()) appendLine("Provide a build_log argument to enable log-based diagnosis.")
            } else {
                appendLine("Issues found (${report.issues.size}):")
                report.issues.forEachIndexed { i, issue ->
                    appendLine()
                    appendLine("${i + 1}. [${issue.category.name}] ${issue.description}")
                    appendLine("   File: ${issue.affectedFile}")
                    if (issue.lineHint.isNotBlank()) appendLine("   Log line: ${issue.lineHint}")
                    appendLine("   Fix: ${issue.suggestedFix}")
                }
            }
            if (report.suggestedFixes.isNotEmpty()) {
                appendLine()
                appendLine("Suggested fixes:")
                report.suggestedFixes.forEachIndexed { i, fix ->
                    appendLine("${i + 1}. $fix")
                }
            }
            appendLine()
            appendLine("Verification command:")
            appendLine("  ${report.verificationCommand}")
            appendLine()
            appendLine("Evidence:")
            report.evidence.forEach { appendLine("  • $it") }
        }.trim()

        return ToolResult(name, true, text)
    }
}
