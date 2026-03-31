package com.llamatik.teammate.tools

import com.intellij.openapi.project.Project

data class ToolCall(
    val name: String,
    val arguments: Map<String, String>,
)

data class ToolResult(
    val name: String,
    val success: Boolean,
    val content: String,
)

data class ToolExecutionContext(
    val project: Project,
)

interface LlamatikTool {
    val name: String
    val description: String
    fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult
}
