package com.llamatik.teammate.tools.impl

import com.intellij.openapi.project.Project
import com.llamatik.teammate.tools.ToolCall
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolResult

class WriteFileTool(project: Project) : BaseProjectTool(project) {
    override val name: String = "write_file"
    override val description: String = "Prepare a full file replacement. Arguments: path, content"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val path = call.arguments["path"] ?: return ToolResult(name, false, "Missing path")
        val newContent = call.arguments["content"] ?: return ToolResult(name, false, "Missing content")
        val oldContent = runCatching { contextService.readFile(path) }.getOrDefault("")
        return ToolResult(name, true, "Prepared file replacement for $path\n---OLD---\n$oldContent\n---NEW---\n$newContent")
    }
}
