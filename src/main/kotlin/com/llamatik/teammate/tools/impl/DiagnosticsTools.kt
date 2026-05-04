package com.llamatik.teammate.tools.impl

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.diagnostics.DiagnosticsService
import com.llamatik.teammate.tools.LlamatikTool
import com.llamatik.teammate.tools.ToolCall
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolResult

class DiagnosticsRunTool(project: Project) : BaseProjectTool(project) {
    override val name = "diagnostics_run"
    override val description = "Run plugin self-diagnostics. No arguments."

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val svc = context.project.service<DiagnosticsService>()
        val report = svc.run()
        val text = svc.formatReport(report, includeSensitive = false)
        return ToolResult(name, report.isHealthy, text)
    }
}

class DiagnosticsFixSuggestionsTool(project: Project) : BaseProjectTool(project) {
    override val name = "diagnostics_fix_suggestions"
    override val description = "Get actionable fix suggestions from diagnostics. No arguments."

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val svc = context.project.service<DiagnosticsService>()
        val report = svc.run()
        val suggestions = svc.fixSuggestions(report)
        if (suggestions.isEmpty()) return ToolResult(name, true, "All diagnostics passed — no fixes needed")
        return ToolResult(name, true, "Suggested fixes:\n" + suggestions.joinToString("\n") { "  - $it" })
    }
}
