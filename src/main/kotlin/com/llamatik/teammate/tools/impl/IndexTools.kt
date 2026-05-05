package com.llamatik.teammate.tools.impl

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.index.ProjectIndexService
import com.llamatik.teammate.tools.LlamatikTool
import com.llamatik.teammate.tools.ToolCall
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolResult

class IndexSearchTool(project: Project) : BaseProjectTool(project) {
    override val name = "index_search"
    override val description = "Semantic search over project files. Arguments: query"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val query = call.arguments["query"] ?: return ToolResult(name, false, "Missing query")
        val svc = context.project.service<ProjectIndexService>()
        if (!svc.isReady()) return ToolResult(name, false, "Index not ready — call index_refresh first (status: ${svc.status()})")
        val results = svc.search(query)
        if (results.isEmpty()) return ToolResult(name, true, "No results for: $query")
        val text = results.joinToString("\n") { r ->
            "${r.path} (score: ${"%.2f".format(r.score)})\n  ${r.snippet}"
        }
        return ToolResult(name, true, text)
    }
}

class IndexRelevantFilesTool(project: Project) : BaseProjectTool(project) {
    override val name = "index_relevant_files"
    override val description = "Return top relevant file paths for a query (no snippets). Arguments: query, max (default 8)"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val query = call.arguments["query"] ?: return ToolResult(name, false, "Missing query")
        val max = call.arguments["max"]?.toIntOrNull() ?: 8
        val svc = context.project.service<ProjectIndexService>()
        if (!svc.isReady()) return ToolResult(name, false, "Index not ready — call index_refresh first")
        val results = svc.search(query, maxResults = max)
        if (results.isEmpty()) return ToolResult(name, true, "No relevant files found for: $query")
        return ToolResult(name, true, results.joinToString("\n") { it.path })
    }
}

class IndexRefreshTool(project: Project) : BaseProjectTool(project) {
    override val name = "index_refresh"
    override val description = "Build or rebuild the project semantic index. No arguments needed."

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val svc = context.project.service<ProjectIndexService>()
        svc.build()
        return ToolResult(name, true, "Index build started in background. Use index_status to check progress.")
    }
}

class IndexStatusTool(project: Project) : BaseProjectTool(project) {
    override val name = "index_status"
    override val description = "Check project index status. No arguments needed."

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val svc = context.project.service<ProjectIndexService>()
        return ToolResult(name, true, svc.status())
    }
}
