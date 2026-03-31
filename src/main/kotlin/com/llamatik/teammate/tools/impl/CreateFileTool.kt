package com.llamatik.teammate.tools.impl

import com.intellij.openapi.project.Project
import com.llamatik.teammate.tools.ToolCall
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolResult

class CreateFileTool(project: Project) : BaseProjectTool(project) {
    override val name: String = "create_file"
    override val description: String = "Prepare a new file creation. Arguments: path, content"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val path = call.arguments["path"] ?: return ToolResult(name, false, "Missing path")
        val content = call.arguments["content"] ?: return ToolResult(name, false, "Missing content")
        return ToolResult(name, true, "Prepared new file for $path\n$content")
    }
}
