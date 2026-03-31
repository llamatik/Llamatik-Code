package com.llamatik.teammate.tools.impl

import com.intellij.openapi.project.Project
import com.llamatik.teammate.tools.ToolCall
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolResult

class ApplyPatchTool(project: Project) : BaseProjectTool(project) {
    override val name: String = "apply_patch"
    override val description: String = "Reserved safe apply tool. Returns a pending approval notice in MVP. Arguments: path, content"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val path = call.arguments["path"] ?: return ToolResult(name, false, "Missing path")
        return ToolResult(name, true, "Patch for $path requires explicit approval in the UI before applying.")
    }
}
