package com.llamatik.agent.core.tools

data class ToolCall(
    val name: String,
    val arguments: Map<String, String>,
)

data class ToolResult(
    val name: String,
    val success: Boolean,
    val content: String,
)
