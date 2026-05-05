package com.llamatik.teammate.tools.impl

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.memory.ProjectMemoryService
import com.llamatik.teammate.tools.LlamatikTool
import com.llamatik.teammate.tools.ToolCall
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolResult

class ProjectMemoryGetTool(project: Project) : BaseProjectTool(project) {
    override val name = "project_memory_get"
    override val description = "Get all active project memory entries. No arguments."

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val svc = context.project.service<ProjectMemoryService>()
        if (!svc.isEnabled()) return ToolResult(name, true, "Project memory is disabled")
        val entries = svc.getAll()
        if (entries.isEmpty()) return ToolResult(name, true, "No project memories stored")
        val text = entries.joinToString("\n") { e -> "[${e.id}] ${e.content}" }
        return ToolResult(name, true, "Project memory (${entries.size}):\n$text")
    }
}

class ProjectMemoryAddTool(project: Project) : BaseProjectTool(project) {
    override val name = "project_memory_add"
    override val description = "Add a project memory entry. Arguments: content, tags (optional, comma-separated)"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val content = call.arguments["content"] ?: return ToolResult(name, false, "Missing content")
        val tags = call.arguments["tags"]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        val svc = context.project.service<ProjectMemoryService>()
        val entry = svc.add(content, tags)
        return ToolResult(name, true, "Memory added [${entry.id}]: ${entry.content}")
    }
}

class ProjectMemoryUpdateTool(project: Project) : BaseProjectTool(project) {
    override val name = "project_memory_update"
    override val description = "Update a memory entry. Arguments: id, content"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val id = call.arguments["id"] ?: return ToolResult(name, false, "Missing id")
        val content = call.arguments["content"] ?: return ToolResult(name, false, "Missing content")
        context.project.service<ProjectMemoryService>().update(id, content)
        return ToolResult(name, true, "Memory [$id] updated")
    }
}

class ProjectMemoryDeleteTool(project: Project) : BaseProjectTool(project) {
    override val name = "project_memory_delete"
    override val description = "Delete a memory entry. Arguments: id"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val id = call.arguments["id"] ?: return ToolResult(name, false, "Missing id")
        context.project.service<ProjectMemoryService>().delete(id)
        return ToolResult(name, true, "Memory [$id] deleted")
    }
}

class ProjectMemorySearchTool(project: Project) : BaseProjectTool(project) {
    override val name = "project_memory_search"
    override val description = "Search project memories. Arguments: query"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val query = call.arguments["query"] ?: return ToolResult(name, false, "Missing query")
        val svc = context.project.service<ProjectMemoryService>()
        val results = svc.search(query)
        if (results.isEmpty()) return ToolResult(name, true, "No memories match '$query'")
        return ToolResult(name, true, results.joinToString("\n") { "[${it.id}] ${it.content}" })
    }
}
