package com.llamatik.teammate.tools

import com.intellij.openapi.project.Project
import com.llamatik.agent.core.tools.ToolCall
import com.llamatik.agent.core.tools.ToolResult

data class ToolExecutionContext(
    val project: Project,
)

interface LlamatikTool {
    val name: String
    val description: String
    fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult
}
