package com.llamatik.teammate.external.research

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.llamatik.teammate.external.ExternalKnowledgeLimits
import com.llamatik.teammate.external.citation.Citation
import com.llamatik.teammate.external.citation.SourceConfidence
import com.llamatik.teammate.external.citation.classifySourceType
import com.llamatik.teammate.external.reader.ExternalPageReaderService
import com.llamatik.teammate.external.reader.PageContent
import com.llamatik.teammate.external.reader.PageReadRequest
import com.llamatik.teammate.external.search.ExternalSearchService
import com.llamatik.teammate.external.search.SearchQuery
import com.llamatik.teammate.external.search.SearchResult
import com.llamatik.teammate.settings.LlamatikAppSettings

@Service(Service.Level.APP)
class ResearchOrchestratorService {

    private val searchService by lazy { service<ExternalSearchService>() }
    private val readerService by lazy { service<ExternalPageReaderService>() }
    private val routerService by lazy { service<ResearchSourceRouter>() }

    fun canRunResearch(isPro: Boolean): Boolean {
        if (!service<LlamatikAppSettings>().data().externalResearchEnabled) return false
        return searchService.canSearch(isPro)
    }

    fun planResearch(task: ResearchTask): ResearchPlan {
        val queries = mutableListOf<String>()
        val question = task.question.trim()

        // Primary query — the question as-is (trimmed to 100 chars)
        queries.add(question.take(100))

        // Secondary query — intent-specific refinement
        val secondary = when (task.intent) {
            ResearchIntent.LIBRARY_DOCS -> "$question documentation"
            ResearchIntent.BUG_ERROR -> "$question error fix"
            ResearchIntent.API_BEHAVIOR -> "$question API example"
            ResearchIntent.MIGRATION -> "$question migration guide"
            ResearchIntent.LATEST_VERSION -> "$question latest version release"
            ResearchIntent.GENERAL -> null
        }
        if (secondary != null && secondary != question) queries.add(secondary.take(100))

        // Tertiary: append local context summary keywords if meaningful
        if (task.localContextSummary.isNotBlank()) {
            val contextKeywords = task.localContextSummary.split(" ")
                .filter { it.length > 4 }.take(3).joinToString(" ")
            if (contextKeywords.isNotBlank()) {
                queries.add("${question.take(60)} $contextKeywords".take(100))
            }
        }

        val expectedSources = routerService.routeSources(question, task.preferredSources)
        return ResearchPlan(
            searchQueries = queries.distinct().take(3),
            expectedSources = expectedSources,
            reason = "Generated ${queries.size} queries based on intent=${task.intent}",
        )
    }

    fun executeResearch(task: ResearchTask): ResearchReport {
        return try {
            val plan = planResearch(task)
            val allResults = mutableListOf<SearchResult>()

            // Run searches
            for (query in plan.searchQueries) {
                if (!canRunResearch(task.isPro)) break
                val results = searchService.search(
                    SearchQuery(
                        query = query,
                        domains = plan.expectedSources,
                        blockedDomains = routerService.blockedDomains(),
                        maxResults = ExternalKnowledgeLimits.maxResultCount(task.isPro),
                    ),
                    task.isPro,
                )
                allResults.addAll(results)
            }

            // Pick best N results
            val pageLimit = ExternalKnowledgeLimits.pagesPerTask(task.isPro)
            val bestResults = allResults
                .filter { it.url.isNotBlank() && !it.url.startsWith("system") }
                .sortedByDescending { it.relevanceScore }
                .distinctBy { it.url }
                .take(pageLimit)

            // Read pages
            val pages = readerService.readPages(bestResults.map { PageReadRequest(it.url) })

            // Synthesize
            val findings = synthesizeFindings(pages, task)
            val citations = buildCitations(bestResults, pages)

            val overallConfidence = when {
                citations.any { it.confidence == SourceConfidence.HIGH } -> SourceConfidence.HIGH
                citations.any { it.confidence == SourceConfidence.MEDIUM } -> SourceConfidence.MEDIUM
                else -> SourceConfidence.LOW
            }

            val answer = buildAnswer(task, findings, pages)

            ResearchReport(
                answer = answer,
                findings = findings,
                sources = citations,
                confidence = overallConfidence,
                limitations = if (bestResults.isEmpty()) "No search results found." else "",
            )
        } catch (e: Exception) {
            ResearchReport(
                answer = "Research failed: ${e.message ?: "Unknown error"}",
                findings = emptyList(),
                sources = emptyList(),
                confidence = SourceConfidence.LOW,
            )
        }
    }

    fun suggestSearchNeeded(question: String, localContextAvailable: Boolean): Boolean {
        val lower = question.lowercase()
        // Always suggest search for version/release/latest queries
        if (lower.containsAny("latest", "version", "release", "changelog", "migration")) return true
        // Suggest search when asking about errors or unknown APIs
        if (lower.containsAny("error", "exception", "failed", "cannot", "unresolved")) return true
        // If no local context, prefer search
        if (!localContextAvailable) return true
        // Otherwise rely on local context
        return false
    }

    fun synthesizeFindings(pages: List<PageContent>, task: ResearchTask): List<ResearchFinding> {
        val findings = mutableListOf<ResearchFinding>()
        val queryWords = task.question.lowercase().split(" ")
            .filter { it.length > 3 }

        for (page in pages) {
            if (page.error != null || page.text.isBlank()) continue
            val paragraphs = page.text.split("\n\n").filter { it.isNotBlank() }
            for (para in paragraphs.take(5)) {
                val paraLower = para.lowercase()
                val matchCount = queryWords.count { paraLower.contains(it) }
                if (matchCount >= 2 && para.length > 50) {
                    findings.add(
                        ResearchFinding(
                            claim = para.take(300).trim(),
                            evidenceUrls = listOf(page.url),
                            confidence = SourceConfidence.MEDIUM,
                            appliesToProject = true,
                        )
                    )
                    break // one finding per page
                }
            }
        }
        return findings.take(5)
    }

    fun buildCitations(results: List<SearchResult>, pages: List<PageContent>): List<Citation> {
        return results.map { result ->
            Citation(
                title = result.title,
                url = result.url,
                sourceType = classifySourceType(result.url),
            )
        }.distinctBy { it.url }
    }

    private fun buildAnswer(task: ResearchTask, findings: List<ResearchFinding>, pages: List<PageContent>): String {
        if (findings.isEmpty() && pages.all { it.error != null || it.text.isBlank() }) {
            return "No relevant information found for: ${task.question}"
        }
        return buildString {
            appendLine("Research findings for: ${task.question}")
            appendLine()
            if (findings.isNotEmpty()) {
                appendLine("Key findings:")
                findings.take(3).forEachIndexed { i, f ->
                    appendLine("${i + 1}. ${f.claim.take(200)}")
                }
            } else {
                val pageSummaries = pages.filter { it.error == null && it.text.isNotBlank() }
                if (pageSummaries.isNotEmpty()) {
                    appendLine("From ${pageSummaries.size} source(s):")
                    pageSummaries.take(2).forEach { p ->
                        appendLine("- ${p.title.ifBlank { p.url }}: ${p.text.take(150)}...")
                    }
                }
            }
        }.trim()
    }

    private fun String.containsAny(vararg keywords: String): Boolean =
        keywords.any { this.contains(it) }

    companion object {
        @JvmStatic
        fun getInstance(): ResearchOrchestratorService = service()
    }
}
