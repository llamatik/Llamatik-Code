package com.llamatik.acp

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.llamatik.agent.core.agent.ToolExecutor
import com.llamatik.agent.core.tools.ToolCall
import com.llamatik.agent.core.tools.ToolExecutionContext
import com.llamatik.agent.core.tools.ToolResult
import java.util.concurrent.atomic.AtomicInteger

/** Shows every tool the agent runs in the editor, with its status and a short result. */
class ToolReporter(
    private val delegate: ToolExecutor,
    private val send: (SessionUpdate) -> Unit,
) : ToolExecutor by delegate {
    private val counter = AtomicInteger()

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val id = ToolCallId("tool-${counter.incrementAndGet()}")
        val arg = call.arguments.values.firstOrNull().orEmpty().take(60)
        send(SessionUpdate.ToolCall(id, if (arg.isBlank()) call.name else "${call.name}: $arg", kindOf(call.name), ToolCallStatus.IN_PROGRESS))
        val result = delegate.execute(call, context)
        send(
            SessionUpdate.ToolCallUpdate(
                toolCallId = id,
                status = if (result.success) ToolCallStatus.COMPLETED else ToolCallStatus.FAILED,
                content = listOf(ToolCallContent.Content(ContentBlock.Text(result.content.take(MAX_RESULT_CHARS)))),
            ),
        )
        return result
    }

    private fun kindOf(tool: String) = when {
        tool == "read_file" || tool == "list_files" || tool == "get_current_file" || tool == "get_selected_text" -> ToolKind.READ
        tool.startsWith("search") -> ToolKind.SEARCH
        tool == "write_file" || tool == "create_file" || tool == "replace_in_file" || tool == "apply_patch" -> ToolKind.EDIT
        tool.startsWith("terminal") -> ToolKind.EXECUTE
        tool.startsWith("git_") -> ToolKind.READ
        else -> ToolKind.OTHER
    }

    private companion object {
        const val MAX_RESULT_CHARS = 2_000
    }
}
