package com.llamatik.teammate.search

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.context.ProjectContextService
import com.llamatik.teammate.index.ProjectIndexService
import com.llamatik.teammate.psi.PsiCodeInsightService
import com.llamatik.teammate.util.PluginLogger

@Service(Service.Level.PROJECT)
class SmartSearchService(private val project: Project) {

    private val log = PluginLogger.logger<SmartSearchService>()
    private val contextService = ProjectContextService(project)

    data class SearchHit(
        val path: String,
        val score: Double,
        val reason: String,
        val snippet: String = "",
    )

    data class SmartSearchResult(
        val hits: List<SearchHit>,
        val intent: SearchIntent,
        val strategy: String,
        val suggestedFilesToRead: List<String>,
        val fallbackUsed: Boolean = false,
    )

    enum class SearchIntent {
        ARCHITECTURE,
        SYMBOL,
        USAGE,
        TEST,
        ERROR,
        GENERAL,
    }

    fun search(query: String, maxResults: Int = 10): SmartSearchResult {
        if (query.isBlank()) return empty(query)

        val intent = detectIntent(query)
        log.debug("SmartSearch: query='$query' intent=$intent")

        // Strategy 1: semantic index search
        val indexHits = runCatching {
            project.service<ProjectIndexService>().search(query, maxResults)
        }.getOrElse { emptyList() }

        if (indexHits.isNotEmpty()) {
            val hits = indexHits.map { r ->
                SearchHit(r.path, r.score, "semantic match (score ${r.score.format()})", r.snippet)
            }
            return SmartSearchResult(
                hits = hits.take(maxResults),
                intent = intent,
                strategy = "semantic index",
                suggestedFilesToRead = hits.take(3).map { it.path },
            )
        }

        // Strategy 2: PSI symbol search for symbol/usage intents
        if (intent == SearchIntent.SYMBOL || intent == SearchIntent.USAGE) {
            val symbolQuery = extractSymbol(query)
            val psiHits = runCatching {
                val svc = project.service<PsiCodeInsightService>()
                val decl = svc.findDeclaration(symbolQuery)
                if (decl != null) {
                    listOf(SearchHit(
                        path = "(PSI) line ${decl.line}",
                        score = 1.0,
                        reason = "PSI declaration found: ${decl.kind} '${decl.name}' at line ${decl.line}",
                    ))
                } else emptyList()
            }.getOrElse { emptyList() }

            if (psiHits.isNotEmpty()) {
                return SmartSearchResult(
                    hits = psiHits,
                    intent = intent,
                    strategy = "PSI symbol search",
                    suggestedFilesToRead = emptyList(),
                )
            }
        }

        // Strategy 3: filename/module search
        val filenameHits = runCatching {
            contextService.searchFilenames(extractSymbol(query))
        }.getOrElse { emptyList() }
            .map { path ->
                SearchHit(path, 0.8, "filename match for '${extractSymbol(query)}'")
            }

        if (filenameHits.isNotEmpty()) {
            return SmartSearchResult(
                hits = filenameHits.take(maxResults),
                intent = intent,
                strategy = "filename search",
                suggestedFilesToRead = filenameHits.take(3).map { it.path },
            )
        }

        // Strategy 4: text (grep) search
        val textHits = runCatching {
            contextService.searchCode(query)
        }.getOrElse { emptyList() }
            .map { path -> SearchHit(path, 0.5, "text match for '$query'") }

        if (textHits.isNotEmpty()) {
            return SmartSearchResult(
                hits = textHits.take(maxResults),
                intent = intent,
                strategy = "text search",
                suggestedFilesToRead = textHits.take(3).map { it.path },
            )
        }

        // Strategy 5: Gradle/package structure clues (architecture queries)
        if (intent == SearchIntent.ARCHITECTURE) {
            val buildFiles = listOf("build.gradle.kts", "settings.gradle.kts", "build.gradle")
            val buildHits = buildFiles.filter { file ->
                runCatching { contextService.readFile(file) }.isSuccess
            }.map { path -> SearchHit(path, 0.3, "build/project structure file") }

            if (buildHits.isNotEmpty()) {
                return SmartSearchResult(
                    hits = buildHits,
                    intent = intent,
                    strategy = "build file fallback",
                    suggestedFilesToRead = buildHits.map { it.path },
                    fallbackUsed = true,
                )
            }
        }

        return empty(query)
    }

    fun detectIntent(query: String): SearchIntent = Companion.detectIntent(query)

    private fun extractSymbol(query: String): String = Companion.extractSymbol(query)

    private fun empty(query: String) = SmartSearchResult(
        hits = emptyList(),
        intent = detectIntent(query),
        strategy = "none (all strategies exhausted)",
        suggestedFilesToRead = emptyList(),
        fallbackUsed = true,
    )

    private fun Double.format() = "%.3f".format(this)

    companion object {
        fun detectIntent(query: String): SearchIntent {
            val q = query.lowercase()
            return when {
                q.containsAny("architecture", "structure", "layer", "module", "pattern", "design",
                    "overview", "understand", "how is", "how does") -> SearchIntent.ARCHITECTURE
                q.containsAny("where is", "find class", "find function", "defined", "declaration",
                    "locate", "symbol") -> SearchIntent.SYMBOL
                q.containsAny("where used", "usages", "callers", "references", "who calls",
                    "who uses") -> SearchIntent.USAGE
                q.containsAny("test", "spec", "junit", "mock", "coverage") -> SearchIntent.TEST
                q.containsAny("error", "exception", "failure", "crash", "stack trace",
                    "build error") -> SearchIntent.ERROR
                else -> SearchIntent.GENERAL
            }
        }

        fun extractSymbol(query: String): String {
            val camelCase = Regex("[A-Z][a-zA-Z0-9]+").find(query)?.value
            if (camelCase != null) return camelCase
            return query.split(Regex("\\s+"))
                .filter { it.length > 3 }
                .maxByOrNull { it.length } ?: query.take(30)
        }

        private fun String.containsAny(vararg terms: String) = terms.any { this.contains(it) }
    }
}
