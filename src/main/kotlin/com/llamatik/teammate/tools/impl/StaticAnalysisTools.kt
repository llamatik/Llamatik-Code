package com.llamatik.teammate.tools.impl

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.analysis.StaticAnalysisService
import com.llamatik.teammate.tools.LlamatikTool
import com.llamatik.teammate.tools.ToolCall
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StaticAnalysisDetectToolsTool(project: Project) : BaseProjectTool(project) {
    override val name = "static_analysis_detect_tools"
    override val description = "Detect available static analysis tools in this project. No arguments."

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val svc = context.project.service<StaticAnalysisService>()
        val tools = svc.detectTools()
        if (tools.isEmpty()) return ToolResult(name, true, "No static analysis tools detected")
        val text = "Detected tools:\n" + tools.joinToString("\n") { "  ${it.id}: ${it.displayName} → ${it.command}" }
        return ToolResult(name, true, text)
    }
}

class StaticAnalysisRunTool(project: Project) : BaseProjectTool(project) {
    override val name = "static_analysis_run"
    override val description = "Run a static analysis tool. Arguments: tool_id (from static_analysis_detect_tools)"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val toolId = call.arguments["tool_id"] ?: return ToolResult(name, false, "Missing tool_id")
        val svc = context.project.service<StaticAnalysisService>()
        val detected = svc.detectTools().find { it.id == toolId }
            ?: return ToolResult(name, false, "Tool '$toolId' not detected. Call static_analysis_detect_tools first.")
        val latch = CountDownLatch(1)
        var result: StaticAnalysisService.AnalysisResult? = null
        svc.run(detected) { r -> result = r; latch.countDown() }
        latch.await(310, TimeUnit.SECONDS)
        val r = result ?: return ToolResult(name, false, "Analysis timed out")
        val summary = buildString {
            appendLine("Tool: ${r.tool}  Findings: ${r.findings.size}  Success: ${r.succeeded}")
            if (!r.succeeded && r.errorMessage.isNotBlank()) appendLine("Error: ${r.errorMessage}")
            r.findings.take(20).forEach { f ->
                appendLine("  [${f.severity}] ${f.file}:${f.line}  ${f.rule}  ${f.message.take(100)}")
            }
            if (r.findings.size > 20) appendLine("  … and ${r.findings.size - 20} more findings")
        }.trim()
        return ToolResult(name, r.succeeded, summary)
    }
}

class StaticAnalysisParseResultsTool(project: Project) : BaseProjectTool(project) {
    override val name = "static_analysis_parse_results"
    override val description = "Parse static analysis output text. Arguments: tool_id, output"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val toolId = call.arguments["tool_id"] ?: return ToolResult(name, false, "Missing tool_id")
        val output = call.arguments["output"] ?: return ToolResult(name, false, "Missing output")
        val svc = context.project.service<StaticAnalysisService>()
        val findings = svc.parseFindings(toolId, output)
        if (findings.isEmpty()) return ToolResult(name, true, "No findings parsed from output")
        val text = findings.take(30).joinToString("\n") { f ->
            "[${f.severity}] ${f.file}:${f.line} ${f.rule} — ${f.message.take(120)}"
        }
        return ToolResult(name, true, "${findings.size} findings:\n$text")
    }
}
