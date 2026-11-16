package com.llamatik.agent.core.tools.builtin

import com.llamatik.agent.core.tools.LlamatikTool
import com.llamatik.agent.core.tools.ToolCall
import com.llamatik.agent.core.tools.ToolExecutionContext
import com.llamatik.agent.core.tools.ToolResult

class SearchFilenamesTool : LlamatikTool {
    override val name: String = "search_filenames"
    override val description: String = "Search project filenames. Arguments: query"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val query = call.arguments["query"] ?: return ToolResult(name, false, "Missing query")
        val results = context.env.files.searchFilenames(query)
        return ToolResult(name, true, results.joinToString("\n"))
    }
}
