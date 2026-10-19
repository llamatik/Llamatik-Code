package com.llamatik.teammate.tools.impl

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.build.BuildFailureService
import com.llamatik.teammate.terminal.TerminalService
import com.llamatik.teammate.tools.LlamatikTool
import com.llamatik.teammate.tools.ToolCall
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolResult

class BuildFailureParseTool(project: Project) : BaseProjectTool(project) {
    override val name = "build_failure_parse"
    override val description = "Parse raw build output into structured errors. Arguments: output"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val output = call.arguments["output"] ?: return ToolResult(name, false, "Missing output")
        val svc = context.project.service<BuildFailureService>()
        val errors = svc.parse(output)
        if (errors.isEmpty()) return ToolResult(name, true, "No structured errors found in output")
        val text = errors.take(20).joinToString("\n") { e ->
            "[${e.kind}] ${e.file}:${e.line} — ${e.message.take(120)}"
        }
        return ToolResult(name, true, "${errors.size} error(s):\n$text")
    }
}

class BuildFailureExplainTool(project: Project) : BaseProjectTool(project) {
    override val name = "build_failure_explain"
    override val description = "Explain a build failure in plain language. Arguments: output (or leave empty to use last terminal output)"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val rawOutput = call.arguments["output"]
            ?: context.project.service<TerminalService>().lastOutputAny()?.let {
                "${it.stdout}\n${it.stderr}"
            }
            ?: return ToolResult(name, false, "No output provided and no terminal history. Run a build first.")
        val svc = context.project.service<BuildFailureService>()
        val summary = svc.summarize(rawOutput)
        return ToolResult(name, true, summary.plainExplanation)
    }
}

class BuildFailureFixPlanTool(project: Project) : BaseProjectTool(project) {
    override val name = "build_failure_fix_plan"
    override val description = "Get structured fix plan for build errors. Arguments: output (or empty for last terminal output)"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val rawOutput = call.arguments["output"]
            ?: context.project.service<TerminalService>().lastOutputAny()?.let {
                "${it.stdout}\n${it.stderr}"
            }
            ?: return ToolResult(name, false, "No output provided")
        val svc = context.project.service<BuildFailureService>()
        val summary = svc.summarize(rawOutput)
        val failingTask = svc.extractFailingTask(rawOutput)
        val verifyCommand = if (failingTask != null) "./gradlew $failingTask" else "./gradlew build"
        val text = buildString {
            appendLine("Root cause: ${summary.rootCause}")
            if (summary.affectedFiles.isNotEmpty()) {
                appendLine("Affected files: ${summary.affectedFiles.joinToString(", ")}")
            }
            appendLine()
            appendLine("Suggested approach:")
            summary.errors.take(5).forEachIndexed { i, e ->
                appendLine("${i+1}. Fix ${e.file}:${e.line} — ${e.message.take(100)}")
            }
            appendLine()
            appendLine("After fixing: run terminal_run with '$verifyCommand' to verify.")
        }.trim()
        return ToolResult(name, true, text)
    }
}
