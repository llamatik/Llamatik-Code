package com.llamatik.teammate.tools.impl

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.codehealth.AnalysisMode
import com.llamatik.teammate.codehealth.BackgroundInsightAnalyzer
import com.llamatik.teammate.codehealth.BeforeCommitAssistant
import com.llamatik.teammate.codehealth.CodeHealthBudgetService
import com.llamatik.teammate.codehealth.CodeHealthInsightStore
import com.llamatik.teammate.codehealth.CodeHealthScheduler
import com.llamatik.teammate.codehealth.CurrentWorkContextService
import com.llamatik.teammate.codehealth.InsightSeverity
import com.llamatik.teammate.codehealth.InsightType
import com.llamatik.teammate.codehealth.RefactorOpportunityAnalyzer
import com.llamatik.teammate.codehealth.RegressionRiskAnalyzer
import com.llamatik.teammate.codehealth.RunTrigger
import com.llamatik.teammate.codehealth.WatchedAreaService
import com.llamatik.teammate.codehealth.WatchedAreaType
import com.llamatik.teammate.tools.LlamatikTool
import com.llamatik.teammate.tools.ToolCall
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolResult

private const val DISABLED_MSG =
    "Code Health Monitor is not enabled. Enable it in Settings → Llamatik Code → Code Health Monitor."

private fun Project.budgetSvc() = service<CodeHealthBudgetService>()
private fun Project.insightStore() = service<CodeHealthInsightStore>()
private fun Project.scheduler() = service<CodeHealthScheduler>()
private fun Project.watchSvc() = service<WatchedAreaService>()
private fun Project.contextSvc() = service<CurrentWorkContextService>()
class CodeHealthStatusTool(private val project: Project) : LlamatikTool {
    override val name = "code_health_status"
    override val description = "Get the Code Health Monitor status: enabled state, mode, last run, total insights"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val svc = project.budgetSvc()
        val store = project.insightStore()
        val agoSec = if (store.lastAnalysisMs() == 0L) "never"
        else "${(System.currentTimeMillis() - store.lastAnalysisMs()) / 1000}s ago"
        return ToolResult(name, true, buildString {
            appendLine("Enabled: ${svc.isEnabled()}")
            appendLine("Mode: ${svc.effectiveMode()}")
            appendLine("Total insights: ${store.totalInsights()}")
            appendLine("Last analysis: $agoSec")
            appendLine("Last duration: ${store.lastDurationMs()}ms")
            append("Files scanned last run: ${store.lastFilesScanned()}")
        })
    }
}

class CodeHealthRefreshTool(private val project: Project) : LlamatikTool {
    override val name = "code_health_refresh"
    override val description = "Trigger a manual Code Health analysis. Arguments: files (optional, comma-separated)"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        if (!project.budgetSvc().isEnabled()) return ToolResult(name, false, DISABLED_MSG)
        val files = call.arguments["files"]
            ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        project.scheduler().triggerManual(files)
        val suffix = if (files.isNotEmpty()) " for ${files.size} file(s)" else ""
        return ToolResult(name, true, "Code health analysis triggered$suffix.")
    }
}

class CodeHealthListInsightsTool(private val project: Project) : LlamatikTool {
    override val name = "code_health_list_insights"
    override val description = "List code health insights. Arguments: severity (INFO|WARNING|HIGH), type (InsightType), limit (default 20)"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        if (!project.budgetSvc().isEnabled()) return ToolResult(name, false, DISABLED_MSG)
        var insights = project.insightStore().allInsights()
        call.arguments["severity"]?.let { sev ->
            runCatching { InsightSeverity.valueOf(sev.uppercase()) }.getOrNull()
                ?.let { insights = insights.filter { i -> i.severity == it } }
        }
        call.arguments["type"]?.let { t ->
            runCatching { InsightType.valueOf(t.uppercase()) }.getOrNull()
                ?.let { insights = insights.filter { i -> i.type == it } }
        }
        val limit = call.arguments["limit"]?.toIntOrNull() ?: 20
        val page = insights.take(limit)
        if (page.isEmpty()) return ToolResult(name, true, "No insights found.")
        return ToolResult(name, true, buildString {
            appendLine("${page.size} insight(s) (of ${insights.size} total):")
            page.forEach { i ->
                appendLine("[${i.severity}] ${i.type} — ${i.title}")
                appendLine("  File: ${i.filePath}${if (i.line > 0) ":${i.line}" else ""}")
                appendLine("  ID: ${i.id}")
                if (i.suggestedAction.isNotBlank()) appendLine("  Suggestion: ${i.suggestedAction}")
            }
        }.trim())
    }
}

class CodeHealthExplainInsightTool(private val project: Project) : LlamatikTool {
    override val name = "code_health_explain_insight"
    override val description = "Explain a code health insight in detail. Arguments: id (required)"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        if (!project.budgetSvc().isEnabled()) return ToolResult(name, false, DISABLED_MSG)
        val id = call.arguments["id"] ?: return ToolResult(name, false, "Missing argument: id")
        val insight = project.insightStore().allInsights().firstOrNull { it.id == id }
            ?: return ToolResult(name, false, "Insight not found: $id")
        return ToolResult(name, true, buildString {
            appendLine("Type: ${insight.type}")
            appendLine("Severity: ${insight.severity}")
            appendLine("Title: ${insight.title}")
            appendLine("Description: ${insight.description}")
            appendLine("File: ${insight.filePath}${if (insight.line > 0) ":${insight.line}" else ""}")
            if (insight.suggestedAction.isNotBlank()) appendLine("Suggested action: ${insight.suggestedAction}")
            if (insight.evidence.isNotEmpty()) appendLine("Evidence: ${insight.evidence.joinToString()}")
            append("Can auto-fix: ${insight.canAutoFix}")
        })
    }
}

class CodeHealthCreateFixPlanTool(private val project: Project) : LlamatikTool {
    override val name = "code_health_create_fix_plan"
    override val description = "Create a fix plan for a code health insight. Arguments: id (required)"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        if (!project.budgetSvc().isEnabled()) return ToolResult(name, false, DISABLED_MSG)
        val id = call.arguments["id"] ?: return ToolResult(name, false, "Missing argument: id")
        val insight = project.insightStore().allInsights().firstOrNull { it.id == id }
            ?: return ToolResult(name, false, "Insight not found: $id")
        return ToolResult(name, true, buildString {
            appendLine("Fix plan for: ${insight.title}")
            appendLine()
            appendLine("Issue: ${insight.description}")
            appendLine("File: ${insight.filePath}${if (insight.line > 0) ":${insight.line}" else ""}")
            if (insight.suggestedAction.isNotBlank()) {
                appendLine()
                appendLine("Action: ${insight.suggestedAction}")
            }
        }.trim())
    }
}

class CodeHealthIgnoreInsightTool(private val project: Project) : LlamatikTool {
    override val name = "code_health_ignore_insight"
    override val description = "Permanently ignore a code health insight. Arguments: id (required)"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        if (!project.budgetSvc().isEnabled()) return ToolResult(name, false, DISABLED_MSG)
        val id = call.arguments["id"] ?: return ToolResult(name, false, "Missing argument: id")
        project.insightStore().ignoreInsight(id)
        return ToolResult(name, true, "Insight $id ignored.")
    }
}

class WatchAreaAddTool(private val project: Project) : LlamatikTool {
    override val name = "watch_area_add"
    override val description = "Watch a path/module for Code Health analysis. Arguments: path (required), type (FILE|DIRECTORY|PACKAGE|MODULE)"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val path = call.arguments["path"] ?: return ToolResult(name, false, "Missing argument: path")
        val typeStr = call.arguments["type"] ?: "DIRECTORY"
        val type = runCatching { WatchedAreaType.valueOf(typeStr.uppercase()) }.getOrElse { WatchedAreaType.DIRECTORY }
        val area = project.watchSvc().addArea(path, type)
            ?: return ToolResult(
                name, false,
                "Cannot add watched area: limit reached.",
            )
        return ToolResult(name, true, "Now watching: $path (ID: ${area.id})")
    }
}

class WatchAreaRemoveTool(private val project: Project) : LlamatikTool {
    override val name = "watch_area_remove"
    override val description = "Remove a watched area. Arguments: id (required)"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val id = call.arguments["id"] ?: return ToolResult(name, false, "Missing argument: id")
        project.watchSvc().removeArea(id)
        return ToolResult(name, true, "Removed watched area: $id")
    }
}

class WatchAreaListTool(private val project: Project) : LlamatikTool {
    override val name = "watch_area_list"
    override val description = "List all watched areas"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val areas = project.watchSvc().listAreas()
        if (areas.isEmpty()) return ToolResult(name, true, "No watched areas configured.")
        return ToolResult(name, true, areas.joinToString("\n") { "- [${it.id}] ${it.path} (${it.type})" })
    }
}

class SessionContextGetTool(private val project: Project) : LlamatikTool {
    override val name = "session_context_get"
    override val description = "Get the current work context: recent files, branch, prompts, watched areas"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val ctx = project.contextSvc().getContext()
        return ToolResult(name, true, buildString {
            appendLine("Branch: ${ctx.currentBranch.ifBlank { "unknown" }}")
            appendLine("Recent files: ${ctx.recentFiles.take(5).joinToString()}")
            appendLine("Watched areas: ${ctx.watchedAreas.take(5).joinToString()}")
            appendLine("Recent prompts: ${ctx.recentPrompts.take(3).joinToString("; ") { it.take(60) }}")
            if (ctx.recentBuildFailure.isNotBlank()) {
                append("Recent build failure: ${ctx.recentBuildFailure.take(100)}")
            }
        }.trim())
    }
}

class SessionContextClearTool(private val project: Project) : LlamatikTool {
    override val name = "session_context_clear"
    override val description = "Clear the current work session context"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        project.contextSvc().clearContext()
        return ToolResult(name, true, "Session context cleared.")
    }
}

class BeforeCommitReviewTool(private val project: Project) : LlamatikTool {
    override val name = "before_commit_review"
    override val description = "Run a before-commit review of changed files. Arguments: files (optional, comma-separated)"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val files = call.arguments["files"]
            ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        val review = project.service<BeforeCommitAssistant>().review(files)
        return ToolResult(name, true, buildString {
            appendLine("=== Before-Commit Review ===")
            appendLine(review.diffSummary)
            appendLine()
            appendLine("Suggested commit message: ${review.suggestedCommitMessage}")
            if (review.risks.isNotEmpty()) {
                appendLine()
                appendLine("Risks (${review.risks.size}):")
                review.risks.forEach { appendLine("  [${it.severity}] ${it.title}") }
            }
            if (review.missingTests.isNotEmpty()) {
                appendLine()
                appendLine("Possibly missing tests: ${review.missingTests.take(5).joinToString()}")
            }
            if (review.followUpSuggestions.isNotEmpty()) {
                appendLine()
                appendLine("Follow-up:")
                review.followUpSuggestions.forEach { appendLine("  - $it") }
            }
        }.trim())
    }
}

class RegressionRiskAnalyzeTool(private val project: Project) : LlamatikTool {
    override val name = "regression_risk_analyze"
    override val description = "Analyze a git diff for regression risks. Arguments: diff (required), files (optional, comma-separated)"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val diff = call.arguments["diff"] ?: return ToolResult(name, false, "Missing argument: diff")
        val files = call.arguments["files"]
            ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        val result = project.service<RegressionRiskAnalyzer>().analyzeGitDiff(diff, files)
        if (result.risks.isEmpty()) return ToolResult(name, true, "No regression risks detected.")
        return ToolResult(name, true, buildString {
            appendLine("${result.risks.size} risk(s) detected:")
            result.risks.forEach { appendLine("[${it.severity}] ${it.title}: ${it.description.take(150)}") }
        }.trim())
    }
}

class RefactorAnalyzeFileTool(private val project: Project) : LlamatikTool {
    override val name = "refactor_analyze_file"
    override val description = "Analyze a file for refactor opportunities. Arguments: path (required)"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val path = call.arguments["path"] ?: return ToolResult(name, false, "Missing argument: path")
        val base = project.basePath ?: return ToolResult(name, false, "No project base path")
        val content = try {
            java.io.File("$base/$path").readText()
        } catch (e: Exception) {
            return ToolResult(name, false, "Cannot read file: ${e.message}")
        }
        val suggestions = project.service<RefactorOpportunityAnalyzer>().analyzeFile(path, content)
        if (suggestions.isEmpty()) return ToolResult(name, true, "No refactor opportunities found in $path.")
        return ToolResult(name, true, buildString {
            appendLine("${suggestions.size} refactor opportunity(ies) in $path:")
            suggestions.forEach { s ->
                appendLine("[${s.insight.severity}] ${s.insight.title} (line ${s.insight.line})")
                appendLine("  ${s.insight.description}")
                appendLine("  Benefit: ${s.expectedBenefit}")
            }
        }.trim())
    }
}
