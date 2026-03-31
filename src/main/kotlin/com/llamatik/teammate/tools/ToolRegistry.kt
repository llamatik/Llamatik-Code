package com.llamatik.teammate.tools

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.tools.impl.ApplyPatchTool
import com.llamatik.teammate.tools.impl.CreateFileTool
import com.llamatik.teammate.tools.impl.GetCurrentFileTool
import com.llamatik.teammate.tools.impl.GetSelectedTextTool
import com.llamatik.teammate.tools.impl.ListFilesTool
import com.llamatik.teammate.tools.impl.ReadFileTool
import com.llamatik.teammate.tools.impl.ReplaceInFileTool
import com.llamatik.teammate.tools.impl.SearchCodeTool
import com.llamatik.teammate.tools.impl.SearchFilenamesTool
import com.llamatik.teammate.tools.impl.ShowDiffTool
import com.llamatik.teammate.tools.impl.WriteFileTool

@Service(Service.Level.PROJECT)
class ToolRegistry(project: Project) {
    private val tools: Map<String, LlamatikTool> = listOf(
        ListFilesTool(project),
        ReadFileTool(project),
        SearchCodeTool(project),
        SearchFilenamesTool(project),
        GetCurrentFileTool(project),
        GetSelectedTextTool(project),
        WriteFileTool(project),
        CreateFileTool(project),
        ReplaceInFileTool(project),
        ShowDiffTool(project),
        ApplyPatchTool(project),
    ).associateBy { it.name }

    fun describeTools(): String = tools.values.joinToString("\n") { "- ${it.name}: ${it.description}" }

    fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val tool = tools[call.name] ?: return ToolResult(call.name, false, "Unknown tool: ${call.name}")
        return runCatching { tool.execute(call, context) }
            .getOrElse { ToolResult(call.name, false, it.message ?: "Tool execution failed") }
    }
}
