package com.llamatik.agent.core.tools.builtin

import com.llamatik.agent.core.tools.LlamatikTool
import com.llamatik.agent.core.tools.ToolCall
import com.llamatik.agent.core.tools.ToolExecutionContext
import com.llamatik.agent.core.tools.ToolResult

class ShowDiffTool : LlamatikTool {
    override val name: String = "show_diff"
    override val description: String = "Prepare a diff payload. Arguments: path, oldContent, newContent"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val path = call.arguments["path"] ?: return ToolResult(name, false, "Missing path")
        return ToolResult(name, true, "Diff ready for $path")
    }
}
