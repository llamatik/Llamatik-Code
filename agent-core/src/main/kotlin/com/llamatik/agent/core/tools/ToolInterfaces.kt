package com.llamatik.agent.core.tools

import com.llamatik.agent.core.host.AgentEnvironment

/** What a tool is allowed to touch while it runs. */
data class ToolExecutionContext(
    val env: AgentEnvironment,
)

interface LlamatikTool {
    val name: String
    val description: String
    fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult
}
