package com.llamatik.teammate.tools.impl

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.external.docs.DocsIndexerService
import com.llamatik.teammate.external.docs.DocsSource
import com.llamatik.teammate.external.docs.DocsSourceService
import com.llamatik.teammate.external.docs.DocsSourceType
import com.llamatik.teammate.licensing.LicenseChecker
import com.llamatik.teammate.tools.LlamatikTool
import com.llamatik.teammate.tools.ToolCall
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolResult
import com.llamatik.teammate.ui.Strings
import java.util.UUID

class DocsSourcesListTool(private val project: Project) : LlamatikTool {
    override val name = "docs_sources_list"
    override val description = "List configured docs sources. No arguments required."

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val docsSvc = service<DocsSourceService>()
        val sources = docsSvc.listSources()
        if (sources.isEmpty()) return ToolResult(name, true, "No docs sources configured.")
        val text = buildString {
            appendLine("Docs sources (${sources.size}):")
            sources.forEach { s ->
                appendLine("- [${s.id}] ${s.name} (${s.type}): ${s.location}" +
                        if (s.lastIndexedAt > 0) " [last indexed: ${formatTime(s.lastIndexedAt)}]" else " [not indexed]")
            }
        }.trim()
        return ToolResult(name, true, text)
    }
}

class DocsSourceAddTool(private val project: Project) : LlamatikTool {
    override val name = "docs_source_add"
    override val description = "Add a new docs source. Arguments: name, type (URL/WEBSITE/GITHUB/LOCAL_FOLDER), location"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val name = call.arguments["name"] ?: return ToolResult(this.name, false, "Missing argument: name")
        val typeStr = call.arguments["type"]?.uppercase() ?: "URL"
        val location = call.arguments["location"] ?: return ToolResult(this.name, false, "Missing argument: location")
        val sourceType = runCatching { DocsSourceType.valueOf(typeStr) }.getOrElse {
            return ToolResult(this.name, false, "Invalid type '$typeStr'. Valid: URL, WEBSITE, GITHUB, LOCAL_FOLDER")
        }
        val isPro = runCatching { LicenseChecker.isLicensed() == true }.getOrElse { false }
        val docsSvc = service<DocsSourceService>()
        if (!docsSvc.canAddSource(isPro)) {
            val limit = docsSvc.sourcesLimit(isPro)
            return ToolResult(this.name, false, "Docs source limit reached ($limit). ${if (!isPro) Strings.ExternalKnowledge.DOCS_SOURCE_LIMIT_FREE else ""}")
        }
        val source = DocsSource(
            id = UUID.randomUUID().toString().take(8),
            name = name,
            type = sourceType,
            location = location,
        )
        val added = docsSvc.addSource(source)
        return if (added) ToolResult(this.name, true, "Added docs source '${name}' (id: ${source.id}). Use docs_index_refresh to index it.")
        else ToolResult(this.name, false, "Failed to add source. It may already exist.")
    }
}

class DocsIndexRefreshTool(private val project: Project) : LlamatikTool {
    override val name = "docs_index_refresh"
    override val description = "Re-index a docs source. Arguments: source_id (or 'all')"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val sourceId = call.arguments["source_id"] ?: return ToolResult(name, false, "Missing argument: source_id")
        val isPro = runCatching { LicenseChecker.isLicensed() == true }.getOrElse { false }
        val docsSvc = service<DocsSourceService>()
        val indexerSvc = service<DocsIndexerService>()
        val sources = if (sourceId.lowercase() == "all") docsSvc.listSources()
        else docsSvc.listSources().filter { it.id == sourceId }
        if (sources.isEmpty()) return ToolResult(name, false, "No source found with id: $sourceId")
        var total = 0
        val results = sources.map { source ->
            val count = indexerSvc.indexSource(source, isPro)
            total += count
            "${source.name}: $count page(s) indexed"
        }
        return ToolResult(name, true, "Indexed $total page(s) total.\n${results.joinToString("\n")}")
    }
}

class DocsSearchTool(private val project: Project) : LlamatikTool {
    override val name = "docs_search"
    override val description = "Search the local docs index. Arguments: query, source_id (optional)"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val query = call.arguments["query"] ?: return ToolResult(name, false, "Missing argument: query")
        val sourceId = call.arguments["source_id"]
        val indexerSvc = service<DocsIndexerService>()
        val sourceIds = if (sourceId != null) listOf(sourceId) else emptyList()
        val entries = indexerSvc.searchIndex(query, sourceIds)
        if (entries.isEmpty()) return ToolResult(name, true, "No results found in local docs index for: $query")
        val text = buildString {
            appendLine("Found ${entries.size} result(s) in local docs index:")
            entries.take(5).forEachIndexed { i, e ->
                appendLine("${i + 1}. ${e.title.ifBlank { e.url }}")
                appendLine("   URL: ${e.url}")
                if (e.content.isNotBlank()) appendLine("   ${e.content.take(200).replace('\n', ' ')}")
            }
        }.trim()
        return ToolResult(name, true, text)
    }
}

private fun formatTime(epochMs: Long): String {
    val date = java.util.Date(epochMs)
    return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(date)
}
