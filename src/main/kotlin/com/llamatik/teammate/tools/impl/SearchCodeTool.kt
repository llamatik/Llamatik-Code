package com.llamatik.teammate.tools.impl

import com.intellij.openapi.project.Project
import com.llamatik.teammate.tools.ToolCall
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolResult

class SearchCodeTool(project: Project) : BaseProjectTool(project) {
    override val name: String = "search_code"
    override val description: String = "Search project contents for text. Arguments: query"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val query = call.arguments["query"] ?: return ToolResult(name, false, "Missing query")
        val results = contextService.searchCode(query)
        return ToolResult(name, true, results.joinToString("\n"))
    }
}
