package com.llamatik.teammate.tools.impl

import com.intellij.openapi.project.Project
import com.llamatik.teammate.tools.ToolCall
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolResult

class SearchFilenamesTool(project: Project) : BaseProjectTool(project) {
    override val name: String = "search_filenames"
    override val description: String = "Search project filenames. Arguments: query"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val query = call.arguments["query"] ?: return ToolResult(name, false, "Missing query")
        val results = contextService.searchFilenames(query)
        return ToolResult(name, true, results.joinToString("\n"))
    }
}
