package com.llamatik.teammate.tools.impl

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.changelog.ChangelogService
import com.llamatik.teammate.tools.LlamatikTool
import com.llamatik.teammate.tools.ToolCall
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolResult

class PrSummaryGenerateTool(project: Project) : BaseProjectTool(project) {
    override val name = "pr_summary_generate"
    override val description = "Get context to generate a PR description. Arguments: style (concise|detailed|conventional_commits, default detailed)"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val style = when (call.arguments["style"]?.lowercase()) {
            "concise" -> ChangelogService.Style.CONCISE
            "conventional_commits", "conventional" -> ChangelogService.Style.CONVENTIONAL_COMMITS
            else -> ChangelogService.Style.DETAILED
        }
        val svc = context.project.service<ChangelogService>()
        return ToolResult(name, true, svc.buildPrSummaryContext(style))
    }
}

class ChangelogGenerateTool(project: Project) : BaseProjectTool(project) {
    override val name = "changelog_generate"
    override val description = "Get context to generate a CHANGELOG entry. No arguments."

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val svc = context.project.service<ChangelogService>()
        return ToolResult(name, true, svc.buildChangelogContext())
    }
}

class CommitSummaryGenerateTool(project: Project) : BaseProjectTool(project) {
    override val name = "commit_summary_generate"
    override val description = "Get diff context to help write a commit message. No arguments."

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val svc = context.project.service<ChangelogService>()
        return ToolResult(name, true, svc.buildCommitSummaryContext())
    }
}
