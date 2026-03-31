package com.llamatik.teammate.tools.impl

import com.intellij.openapi.project.Project
import com.llamatik.teammate.tools.ToolCall
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolResult

class ReadFileTool(project: Project) : BaseProjectTool(project) {
    override val name: String = "read_file"
    override val description: String = "Read a UTF-8 project file. Arguments: path"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val path = call.arguments["path"] ?: return ToolResult(name, false, "Missing path")
        return ToolResult(name, true, contextService.readFile(path))
    }
}
