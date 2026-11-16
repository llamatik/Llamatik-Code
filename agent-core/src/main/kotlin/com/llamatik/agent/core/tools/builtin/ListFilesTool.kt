package com.llamatik.agent.core.tools.builtin

import com.llamatik.agent.core.tools.LlamatikTool
import com.llamatik.agent.core.tools.ToolCall
import com.llamatik.agent.core.tools.ToolExecutionContext
import com.llamatik.agent.core.tools.ToolResult

class ListFilesTool : LlamatikTool {
    override val name: String = "list_files"
    override val description: String = "List project files under a relative path. Arguments: path"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val files = context.env.files.list(call.arguments["path"])
        return ToolResult(name, true, files.joinToString("\n"))
    }
}
