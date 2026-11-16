package com.llamatik.agent.core.tools.builtin

import com.llamatik.agent.core.tools.LlamatikTool
import com.llamatik.agent.core.tools.ToolCall
import com.llamatik.agent.core.tools.ToolExecutionContext
import com.llamatik.agent.core.tools.ToolResult

class SearchCodeTool : LlamatikTool {
    override val name: String = "search_code"
    override val description: String = "Search project contents for text. Arguments: query"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val query = call.arguments["query"] ?: return ToolResult(name, false, "Missing query")
        val results = context.env.files.searchCode(query)
        return ToolResult(name, true, results.joinToString("\n"))
    }
}
