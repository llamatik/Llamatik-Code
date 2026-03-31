package com.llamatik.teammate.tools.impl

import com.intellij.openapi.project.Project
import com.llamatik.teammate.tools.ToolCall
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolResult

class ShowDiffTool(project: Project) : BaseProjectTool(project) {
    override val name: String = "show_diff"
    override val description: String = "Prepare a diff payload. Arguments: path, oldContent, newContent"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val path = call.arguments["path"] ?: return ToolResult(name, false, "Missing path")
        return ToolResult(name, true, "Diff ready for $path")
    }
}
