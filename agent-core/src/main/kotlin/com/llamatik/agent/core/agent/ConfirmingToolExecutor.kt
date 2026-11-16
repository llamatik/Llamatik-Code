package com.llamatik.agent.core.agent

import com.llamatik.agent.core.host.Decision
import com.llamatik.agent.core.host.PermissionKind
import com.llamatik.agent.core.host.PermissionPrompt
import com.llamatik.agent.core.host.PermissionRequest
import com.llamatik.agent.core.tools.ToolCall
import com.llamatik.agent.core.tools.ToolExecutionContext
import com.llamatik.agent.core.tools.ToolResult

/** Asks before a command runs. Reading and searching files, and preparing edits, change nothing and need no approval. */
class ConfirmingToolExecutor(
    private val delegate: ToolExecutor,
    private val permissions: PermissionPrompt,
) : ToolExecutor by delegate {
    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        if (call.name == "terminal_run") {
            val command = call.arguments["command"].orEmpty()
            val decision = permissions.confirm(
                PermissionRequest(PermissionKind.SHELL, "Run `$command`?", "The agent wants to run: $command"),
            )
            if (decision == Decision.DENY) return ToolResult(call.name, false, "The user declined to run: $command")
        }
        return delegate.execute(call, context)
    }
}
