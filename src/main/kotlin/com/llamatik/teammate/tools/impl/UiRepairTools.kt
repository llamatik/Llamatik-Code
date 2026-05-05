package com.llamatik.teammate.tools.impl

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.tools.LlamatikTool
import com.llamatik.teammate.tools.ToolCall
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolResult
import com.llamatik.teammate.ui_repair.UiRepairService

class UiImageAnalyzeTool(project: Project) : BaseProjectTool(project) {
    override val name = "ui_image_analyze"
    override val description = "Identify the UI framework and find candidate files for an attached screenshot/mockup. Arguments: screen_hint (optional screen name)"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val hint = call.arguments["screen_hint"] ?: ""
        val svc = context.project.service<UiRepairService>()
        val framework = svc.detectFramework()
        val candidates = svc.findCandidateFiles(hint)
        val text = buildString {
            appendLine("Detected UI framework: $framework")
            if (candidates.isEmpty()) {
                appendLine("No candidate UI files found. Provide the file path explicitly.")
            } else {
                appendLine("Candidate files (${candidates.size}):")
                candidates.forEach { c ->
                    appendLine("  [${c.confidence}] ${c.path} — ${c.reason}")
                }
            }
        }.trim()
        return ToolResult(name, true, text)
    }
}

class UiFindCandidateFilesTool(project: Project) : BaseProjectTool(project) {
    override val name = "ui_find_candidate_files"
    override val description = "Find UI files relevant to a screen name. Arguments: screen_hint"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val hint = call.arguments["screen_hint"] ?: ""
        val svc = context.project.service<UiRepairService>()
        val candidates = svc.findCandidateFiles(hint)
        if (candidates.isEmpty()) return ToolResult(name, true, "No UI files found matching '$hint'")
        return ToolResult(name, true, candidates.joinToString("\n") { c ->
            "[${c.confidence}] ${c.path}  ${c.framework}  ${c.reason}"
        })
    }
}

class UiGeneratePatchPlanTool(project: Project) : BaseProjectTool(project) {
    override val name = "ui_generate_patch_plan"
    override val description = "Build prompt context for generating a UI patch from an image description. Arguments: description, screen_hint (optional)"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val description = call.arguments["description"] ?: return ToolResult(name, false, "Missing description")
        val hint = call.arguments["screen_hint"] ?: ""
        val svc = context.project.service<UiRepairService>()
        val analysisContext = svc.buildAnalysisPrompt(hint)
        val prompt = buildString {
            appendLine("UI change description from image:")
            appendLine(description)
            appendLine()
            appendLine(analysisContext)
            appendLine()
            appendLine("Apply the described UI changes to the most relevant candidate file.")
            appendLine("Use write_file or replace_in_file to produce the patch.")
        }.trim()
        return ToolResult(name, true, prompt)
    }
}
