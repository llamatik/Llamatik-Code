package com.llamatik.agent.core.tools.builtin

import com.llamatik.agent.core.tools.LlamatikTool
import com.llamatik.agent.core.tools.ToolCall
import com.llamatik.agent.core.tools.ToolExecutionContext
import com.llamatik.agent.core.tools.ToolResult

class ApplyPatchTool : LlamatikTool {
    override val name: String = "apply_patch"
    override val description: String = "Reserved safe apply tool. Returns a pending approval notice in MVP. Arguments: path, content"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val path = call.arguments["path"] ?: return ToolResult(name, false, "Missing path")
        return ToolResult(name, true, "Patch for $path requires explicit approval in the UI before applying.")
    }
}
