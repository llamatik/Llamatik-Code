package com.llamatik.teammate.tools.impl

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.search.SmartSearchService
import com.llamatik.teammate.tools.LlamatikTool
import com.llamatik.teammate.tools.ToolCall
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolResult

class SmartSearchTool(project: Project) : BaseProjectTool(project) {
    override val name = "smart_search"
    override val description = "Intent-aware search: semantic → PSI → filename → text. Arguments: query"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val query = call.arguments["query"] ?: return ToolResult(name, false, "Missing query")
        val svc = context.project.service<SmartSearchService>()
        val result = svc.search(query)

        if (result.hits.isEmpty()) {
            val fallbackNote = if (result.fallbackUsed) " All fallback strategies were exhausted." else ""
            return ToolResult(name, true,
                "No results found for '$query' using strategy: ${result.strategy}.$fallbackNote\n" +
                "Detected intent: ${result.intent.name.lowercase()}.")
        }

        val text = buildString {
            appendLine("Smart search results for '$query' (intent: ${result.intent.name.lowercase()}, strategy: ${result.strategy}):")
            result.hits.forEachIndexed { i, hit ->
                appendLine("${i + 1}. ${hit.path}  [score: ${"%.3f".format(hit.score)}]")
                appendLine("   Reason: ${hit.reason}")
                if (hit.snippet.isNotBlank()) appendLine("   Snippet: ${hit.snippet}")
            }
            if (result.suggestedFilesToRead.isNotEmpty()) {
                appendLine()
                appendLine("Suggested files to read next: ${result.suggestedFilesToRead.joinToString(", ")}")
            }
            if (result.fallbackUsed) appendLine("Note: fallback strategy was used.")
        }.trim()
        return ToolResult(name, true, text)
    }
}
