package com.llamatik.agent.core.tools.builtin

import com.llamatik.agent.core.tools.LlamatikTool
import com.llamatik.agent.core.tools.ToolCall
import com.llamatik.agent.core.tools.ToolExecutionContext
import com.llamatik.agent.core.tools.ToolResult

class WriteFileTool : LlamatikTool {
    override val name: String = "write_file"
    override val description: String = "Prepare a full file replacement. Arguments: path, content"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val path = call.arguments["path"] ?: return ToolResult(name, false, "Missing path")
        val newContent = call.arguments["content"] ?: return ToolResult(name, false, "Missing content")
        val oldContent = runCatching { context.env.files.read(path) }.getOrDefault("")
        return ToolResult(name, true, "Prepared file replacement for $path\n---OLD---\n$oldContent\n---NEW---\n$newContent")
    }
}
