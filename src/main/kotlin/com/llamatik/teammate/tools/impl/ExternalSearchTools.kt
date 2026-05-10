package com.llamatik.teammate.tools.impl

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.external.ExternalKnowledgeLimits
import com.llamatik.teammate.external.research.ResearchSourceRouter
import com.llamatik.teammate.external.search.ExternalSearchService
import com.llamatik.teammate.external.search.SearchIntent
import com.llamatik.teammate.external.search.SearchQuery
import com.llamatik.teammate.licensing.LicenseChecker
import com.llamatik.teammate.settings.LlamatikAppSettings
import com.llamatik.teammate.tools.LlamatikTool
import com.llamatik.teammate.tools.ToolCall
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolResult
import com.llamatik.teammate.ui.Strings

class ExternalSearchTool(private val project: Project) : LlamatikTool {
    override val name = "external_search"
    override val description = "Search the web using DuckDuckGo. Arguments: query, domains (optional, comma-separated)"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        if (!isEnabled()) return ToolResult(name, false, Strings.ExternalKnowledge.DISABLED_MSG)
        val query = call.arguments["query"] ?: return ToolResult(name, false, "Missing argument: query")
        val isPro = isPro()
        val searchSvc = service<ExternalSearchService>()
        if (!searchSvc.canSearch(isPro)) {
            val limit = searchSvc.dailyLimit(isPro)
            return ToolResult(name, false, "Daily search limit reached ($limit/$limit). Resets tomorrow.")
        }
        val domains = call.arguments["domains"]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        val results = searchSvc.search(
            SearchQuery(query = query, domains = domains, maxResults = ExternalKnowledgeLimits.maxResultCount(isPro)),
            isPro,
        )
        return ToolResult(name, true, formatResults(results))
    }
}

class ExternalSearchOfficialDocsTool(private val project: Project) : LlamatikTool {
    override val name = "external_search_official_docs"
    override val description = "Search official documentation sites. Arguments: query, domains (optional)"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        if (!isEnabled()) return ToolResult(name, false, Strings.ExternalKnowledge.DISABLED_MSG)
        val query = call.arguments["query"] ?: return ToolResult(name, false, "Missing argument: query")
        val isPro = isPro()
        val searchSvc = service<ExternalSearchService>()
        if (!searchSvc.canSearch(isPro)) {
            val limit = searchSvc.dailyLimit(isPro)
            return ToolResult(name, false, "Daily search limit reached ($limit/$limit). Resets tomorrow.")
        }
        val router = service<ResearchSourceRouter>()
        val routedDomains = router.routeSources(query, emptyList())
        val extraDomains = call.arguments["domains"]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        val results = searchSvc.search(
            SearchQuery(
                query = query,
                domains = (routedDomains + extraDomains).distinct(),
                maxResults = ExternalKnowledgeLimits.maxResultCount(isPro),
                intent = SearchIntent.OFFICIAL_DOCS,
            ),
            isPro,
        )
        return ToolResult(name, true, formatResults(results))
    }
}

class ExternalSearchGithubIssuesTool(private val project: Project) : LlamatikTool {
    override val name = "external_search_github_issues"
    override val description = "Search GitHub issues and pull requests. Arguments: query"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        if (!isEnabled()) return ToolResult(name, false, Strings.ExternalKnowledge.DISABLED_MSG)
        val query = call.arguments["query"] ?: return ToolResult(name, false, "Missing argument: query")
        val isPro = isPro()
        val searchSvc = service<ExternalSearchService>()
        if (!searchSvc.canSearch(isPro)) {
            val limit = searchSvc.dailyLimit(isPro)
            return ToolResult(name, false, "Daily search limit reached ($limit/$limit). Resets tomorrow.")
        }
        val results = searchSvc.search(
            SearchQuery(
                query = "$query site:github.com",
                domains = listOf("github.com"),
                maxResults = ExternalKnowledgeLimits.maxResultCount(isPro),
                intent = SearchIntent.GITHUB_ISSUES,
            ),
            isPro,
        )
        return ToolResult(name, true, formatResults(results))
    }
}

private fun isEnabled(): Boolean =
    runCatching { service<LlamatikAppSettings>().data().externalResearchEnabled }.getOrElse { false }

private fun isPro(): Boolean =
    runCatching { LicenseChecker.isLicensed() == true }.getOrElse { false }

private fun formatResults(results: List<com.llamatik.teammate.external.search.SearchResult>): String {
    if (results.isEmpty()) return "No results found."
    return buildString {
        appendLine("Found ${results.size} result(s):")
        results.forEachIndexed { i, r ->
            appendLine("${i + 1}. ${r.title}")
            if (r.url.isNotBlank()) appendLine("   ${r.url}")
            if (r.snippet.isNotBlank()) appendLine("   ${r.snippet.take(200)}")
        }
    }.trim()
}
