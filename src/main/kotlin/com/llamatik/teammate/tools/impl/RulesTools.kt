package com.llamatik.teammate.tools.impl

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.rules.CodebaseRulesService
import com.llamatik.teammate.tools.LlamatikTool
import com.llamatik.teammate.tools.ToolCall
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolResult

class CodebaseRulesGetTool(project: Project) : BaseProjectTool(project) {
    override val name = "codebase_rules_get"
    override val description = "Get active project conventions and coding rules. No arguments needed."

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val svc = context.project.service<CodebaseRulesService>()
        val rules = svc.getRules()
        if (rules.isEmpty()) return ToolResult(name, true, "No rules yet — call codebase_rules_refresh to detect conventions")
        val text = rules.joinToString("\n") { r ->
            "- [${r.confidence}] ${r.text}"
        }
        return ToolResult(name, true, "Project conventions:\n$text")
    }
}

class CodebaseRulesRefreshTool(project: Project) : BaseProjectTool(project) {
    override val name = "codebase_rules_refresh"
    override val description = "Scan project to detect conventions and update rules. No arguments needed."

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val svc = context.project.service<CodebaseRulesService>()
        svc.refresh()
        val rules = svc.getAllRules()
        val text = if (rules.isEmpty()) "No conventions detected"
            else "Detected ${rules.size} rules:\n" + rules.joinToString("\n") { r -> "- [${r.confidence}] ${r.text}" }
        return ToolResult(name, true, text)
    }
}

class CodebaseRulesExplainTool(project: Project) : BaseProjectTool(project) {
    override val name = "codebase_rules_explain"
    override val description = "Explain the evidence behind a rule. Arguments: id"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val id = call.arguments["id"] ?: return ToolResult(name, false, "Missing id")
        val svc = context.project.service<CodebaseRulesService>()
        val rule = svc.getAllRules().find { it.id == id }
            ?: return ToolResult(name, false, "Rule '$id' not found")
        val text = buildString {
            appendLine("Rule: ${rule.text}")
            appendLine("Confidence: ${rule.confidence}")
            appendLine("Evidence: ${rule.evidence}")
            appendLine("Enabled: ${rule.enabled}")
            appendLine("Source: ${rule.source}")
        }.trim()
        return ToolResult(name, true, text)
    }
}
