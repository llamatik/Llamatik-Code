package com.llamatik.teammate.tools.impl

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.external.research.ResearchIntent
import com.llamatik.teammate.external.research.ResearchOrchestratorService
import com.llamatik.teammate.external.research.ResearchSourceRouter
import com.llamatik.teammate.external.research.ResearchTask
import com.llamatik.teammate.licensing.LicenseChecker
import com.llamatik.teammate.settings.LlamatikAppSettings
import com.llamatik.teammate.tools.LlamatikTool
import com.llamatik.teammate.tools.ToolCall
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolResult
import com.llamatik.teammate.ui.Strings

class ResearchPlanTool(private val project: Project) : LlamatikTool {
    override val name = "research_plan"
    override val description = "Generate a research plan for a question. Arguments: question, context (optional)"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        if (!isEnabled()) return ToolResult(name, false, Strings.ExternalKnowledge.DISABLED_MSG)
        val question = call.arguments["question"] ?: return ToolResult(name, false, "Missing argument: question")
        val localContext = call.arguments["context"] ?: ""
        val orchestrator = service<ResearchOrchestratorService>()
        val task = ResearchTask(question = question, localContextSummary = localContext, isPro = isPro())
        val plan = orchestrator.planResearch(task)
        val text = buildString {
            appendLine("Research plan:")
            appendLine("Queries (${plan.searchQueries.size}):")
            plan.searchQueries.forEachIndexed { i, q -> appendLine("  ${i + 1}. $q") }
            if (plan.expectedSources.isNotEmpty()) {
                appendLine("Expected sources: ${plan.expectedSources.joinToString(", ")}")
            }
            if (plan.reason.isNotBlank()) appendLine("Reason: ${plan.reason}")
        }.trim()
        return ToolResult(name, true, text)
    }
}

class ResearchExecuteTool(private val project: Project) : LlamatikTool {
    override val name = "research_execute"
    override val description = "Run full web research workflow and return report. Arguments: question, context (optional)"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        if (!isEnabled()) return ToolResult(name, false, Strings.ExternalKnowledge.DISABLED_MSG)
        val question = call.arguments["question"] ?: return ToolResult(name, false, "Missing argument: question")
        val localContext = call.arguments["context"] ?: ""
        val orchestrator = service<ResearchOrchestratorService>()
        val isPro = isPro()
        if (!orchestrator.canRunResearch(isPro)) {
            return ToolResult(name, false, Strings.ExternalKnowledge.DAILY_LIMIT_MSG)
        }
        val task = ResearchTask(question = question, localContextSummary = localContext, isPro = isPro)
        val report = orchestrator.executeResearch(task)
        val text = buildString {
            appendLine(report.answer)
            if (report.findings.isNotEmpty()) {
                appendLine()
                appendLine("Findings:")
                report.findings.take(5).forEachIndexed { i, f ->
                    appendLine("${i + 1}. [${f.confidence}] ${f.claim.take(200)}")
                }
            }
            if (report.sources.isNotEmpty()) {
                appendLine()
                appendLine(Strings.ExternalKnowledge.SOURCES_HEADER)
                report.sources.take(5).forEach { c ->
                    appendLine("- [${c.confidenceLabel}] ${c.title}: ${c.url}")
                }
            }
            if (report.limitations.isNotBlank()) {
                appendLine()
                appendLine("Limitations: ${report.limitations}")
            }
        }.trim()
        return ToolResult(name, true, text)
    }
}

class ResearchSummarizeTool(private val project: Project) : LlamatikTool {
    override val name = "research_summarize"
    override val description = "Summarize provided content or search results. Arguments: content"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        if (!isEnabled()) return ToolResult(name, false, Strings.ExternalKnowledge.DISABLED_MSG)
        val content = call.arguments["content"] ?: return ToolResult(name, false, "Missing argument: content")
        // Simple extraction: find sentences containing key information
        val sentences = content.split(Regex("[.!?]+")).map { it.trim() }.filter { it.length > 30 }
        val summary = sentences.take(5).joinToString(". ")
        return ToolResult(name, true, if (summary.isNotBlank()) summary else content.take(500))
    }
}

class ResearchRouteSourcesTool(private val project: Project) : LlamatikTool {
    override val name = "research_route_sources"
    override val description = "Get suggested search domains for a query. Arguments: query"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        if (!isEnabled()) return ToolResult(name, false, Strings.ExternalKnowledge.DISABLED_MSG)
        val query = call.arguments["query"] ?: return ToolResult(name, false, "Missing argument: query")
        val router = service<ResearchSourceRouter>()
        val sources = router.routeSources(query, emptyList())
        return if (sources.isEmpty()) {
            ToolResult(name, true, "No domain routing configured for this query (will search all).")
        } else {
            ToolResult(name, true, "Suggested domains: ${sources.joinToString(", ")}")
        }
    }
}

class ResearchFixPlanTool(private val project: Project) : LlamatikTool {
    override val name = "research_fix_plan"
    override val description = "Research an error and generate a fix plan. Arguments: error, context (optional)"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        if (!isEnabled()) return ToolResult(name, false, Strings.ExternalKnowledge.DISABLED_MSG)
        val error = call.arguments["error"] ?: return ToolResult(name, false, "Missing argument: error")
        val localContext = call.arguments["context"] ?: ""
        val isPro = isPro()
        val orchestrator = service<ResearchOrchestratorService>()
        if (!orchestrator.canRunResearch(isPro)) {
            return ToolResult(name, false, Strings.ExternalKnowledge.DAILY_LIMIT_MSG)
        }
        val task = ResearchTask(
            question = "How to fix: ${error.take(200)}",
            localContextSummary = localContext,
            intent = ResearchIntent.BUG_ERROR,
            isPro = isPro,
        )
        val report = orchestrator.executeResearch(task)
        val plan = if (report.suggestedFixPlan.isNotBlank()) report.suggestedFixPlan else report.answer
        return ToolResult(name, true, plan)
    }
}

private fun isEnabled(): Boolean =
    runCatching { service<LlamatikAppSettings>().data().externalResearchEnabled }.getOrElse { false }

private fun isPro(): Boolean =
    runCatching { LicenseChecker.isLicensed() == true }.getOrElse { false }
