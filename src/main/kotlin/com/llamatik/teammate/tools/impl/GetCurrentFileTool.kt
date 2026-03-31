package com.llamatik.teammate.tools.impl

import com.intellij.openapi.project.Project
import com.llamatik.teammate.tools.ToolCall
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolResult

class GetCurrentFileTool(project: Project) : BaseProjectTool(project) {
    override val name: String = "get_current_file"
    override val description: String = "Get current selected editor file path. No arguments."

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        return ToolResult(name, true, contextService.getCurrentFile() ?: "")
    }
}
