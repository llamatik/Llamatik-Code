package com.llamatik.teammate.tools.impl

import com.intellij.openapi.project.Project
import com.llamatik.agent.core.tools.ToolCall
import com.llamatik.agent.core.tools.ToolExecutionContext
import com.llamatik.agent.core.tools.ToolResult

class GetSelectedTextTool(project: Project) : BaseProjectTool(project) {
    override val name: String = "get_selected_text"
    override val description: String = "Get selected editor text. No arguments."

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        return ToolResult(name, true, context.env.editor?.selectedText() ?: "")
    }
}
