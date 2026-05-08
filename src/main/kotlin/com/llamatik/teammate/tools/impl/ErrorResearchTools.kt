package com.llamatik.teammate.tools.impl

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.external.research.ErrorResearchService
import com.llamatik.teammate.external.research.ResearchOrchestratorService
import com.llamatik.teammate.licensing.LicenseChecker
import com.llamatik.teammate.settings.LlamatikAppSettings
import com.llamatik.teammate.tools.LlamatikTool
import com.llamatik.teammate.tools.ToolCall
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolResult
import com.llamatik.teammate.ui.Strings

class ErrorResearchAnalyzeTool(private val project: Project) : LlamatikTool {
    override val name = "error_research_analyze"
    override val description = "Research a build/compile error and cite sources. Arguments: error"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        if (!isEnabled()) return ToolResult(name, false, Strings.ExternalKnowledge.DISABLED_MSG)
        val error = call.arguments["error"] ?: return ToolResult(name, false, "Missing argument: error")
        val isPro = isPro()
        val orchestrator = service<ResearchOrchestratorService>()
        if (!orchestrator.canRunResearch(isPro)) {
            return ToolResult(name, false, Strings.ExternalKnowledge.DAILY_LIMIT_MSG)
        }
        val errorSvc = service<ErrorResearchService>()
        val errorContext = errorSvc.parseErrorContext(error)
        val report = errorSvc.researchError(errorContext, isPro)
        val text = buildString {
            appendLine("Error analysis:")
            if (errorContext.tool.isNotBlank()) appendLine("Tool: ${errorContext.tool}")
            if (errorContext.framework.isNotBlank()) appendLine("Framework: ${errorContext.framework}")
            if (errorContext.libraryName.isNotBlank()) appendLine("Library: ${errorContext.libraryName}")
            if (errorContext.errorCode.isNotBlank()) appendLine("Error: ${errorContext.errorCode.take(120)}")
            appendLine()
            appendLine(report.answer.take(500))
            if (report.sources.isNotEmpty()) {
                appendLine()
                appendLine(Strings.ExternalKnowledge.SOURCES_HEADER)
                report.sources.take(3).forEach { c ->
                    appendLine("- [${c.confidenceLabel}] ${c.title}: ${c.url}")
                }
            }
        }.trim()
        return ToolResult(name, true, text)
    }
}

class ErrorResearchFixTool(private val project: Project) : LlamatikTool {
    override val name = "error_research_fix"
    override val description = "Research error and produce a fix plan. Arguments: error, context (optional)"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        if (!isEnabled()) return ToolResult(name, false, Strings.ExternalKnowledge.DISABLED_MSG)
        val error = call.arguments["error"] ?: return ToolResult(name, false, "Missing argument: error")
        val projectContext = call.arguments["context"] ?: ""
        val isPro = isPro()
        val orchestrator = service<ResearchOrchestratorService>()
        if (!orchestrator.canRunResearch(isPro)) {
            return ToolResult(name, false, Strings.ExternalKnowledge.DAILY_LIMIT_MSG)
        }
        val errorSvc = service<ErrorResearchService>()
        val errorContext = errorSvc.parseErrorContext(error)
        val report = errorSvc.researchError(errorContext, isPro)

        return if (!isPro) {
            // Free: preview only
            val preview = buildString {
                appendLine("Fix plan preview (upgrade to Pro for full plan):")
                appendLine()
                appendLine(report.answer.take(200))
                if (report.sources.isNotEmpty()) {
                    appendLine()
                    appendLine("Source: ${report.sources.first().url}")
                }
            }.trim()
            ToolResult(name, true, preview)
        } else {
            // Pro: full fix plan
            val fixPlan = errorSvc.buildFixPlan(errorContext, report)
            ToolResult(name, true, fixPlan)
        }
    }
}

private fun isEnabled(): Boolean =
    runCatching { service<LlamatikAppSettings>().data().externalResearchEnabled }.getOrElse { false }

private fun isPro(): Boolean =
    runCatching { LicenseChecker.isLicensed() == true }.getOrElse { false }
