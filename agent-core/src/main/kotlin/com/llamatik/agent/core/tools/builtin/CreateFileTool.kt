package com.llamatik.agent.core.tools.builtin

import com.llamatik.agent.core.tools.LlamatikTool
import com.llamatik.agent.core.tools.ToolCall
import com.llamatik.agent.core.tools.ToolExecutionContext
import com.llamatik.agent.core.tools.ToolResult

class CreateFileTool : LlamatikTool {
    override val name: String = "create_file"
    override val description: String = "Prepare a new file creation. Arguments: path, content"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val path = call.arguments["path"] ?: return ToolResult(name, false, "Missing path")
        val content = call.arguments["content"] ?: return ToolResult(name, false, "Missing content")
        return ToolResult(name, true, "Prepared new file for $path\n$content")
    }
}
