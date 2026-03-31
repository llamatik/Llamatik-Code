package com.llamatik.teammate.tools.impl

import com.intellij.openapi.project.Project
import com.llamatik.teammate.tools.ToolCall
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolResult

class ListFilesTool(project: Project) : BaseProjectTool(project) {
    override val name: String = "list_files"
    override val description: String = "List project files under a relative path. Arguments: path"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val files = contextService.listFiles(call.arguments["path"])
        return ToolResult(name, true, files.joinToString("\n"))
    }
}
