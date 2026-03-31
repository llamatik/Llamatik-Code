package com.llamatik.teammate.tools.impl

import com.intellij.openapi.project.Project
import com.llamatik.teammate.tools.ToolCall
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolResult

class ReplaceInFileTool(project: Project) : BaseProjectTool(project) {
    override val name: String = "replace_in_file"
    override val description: String = "Prepare a string replacement in an existing file. Arguments: path, oldText, newText"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val path = call.arguments["path"] ?: return ToolResult(name, false, "Missing path")
        val oldText = call.arguments["oldText"] ?: return ToolResult(name, false, "Missing oldText")
        val newText = call.arguments["newText"] ?: return ToolResult(name, false, "Missing newText")
        val oldContent = contextService.readFile(path)
        require(oldContent.contains(oldText)) { "oldText not found in file" }
        val newContent = oldContent.replace(oldText, newText)
        return ToolResult(name, true, "Prepared replacement for $path\n---OLD---\n$oldContent\n---NEW---\n$newContent")
    }
}
