package com.llamatik.teammate.external

import com.llamatik.teammate.external.citation.Citation
import com.llamatik.teammate.external.citation.SourceConfidence
import com.llamatik.teammate.external.citation.SourceType
import com.llamatik.teammate.external.citation.classifySourceType
import com.llamatik.teammate.external.reader.PageContent
import com.llamatik.teammate.external.research.ResearchFinding
import com.llamatik.teammate.external.research.ResearchIntent
import com.llamatik.teammate.external.research.ResearchReport
import com.llamatik.teammate.external.research.ResearchTask
import com.llamatik.teammate.external.search.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the pure logic extracted from ResearchOrchestratorService.
 * Each private helper is replicated here so no IntelliJ services are needed.
 */
class ResearchOrchestratorLogicTest {

    // ── planResearch logic ─────────────────────────────────────────────────

    private fun planQueries(task: ResearchTask): List<String> {
        val queries = mutableListOf<String>()
        val question = task.question.trim()
        queries.add(question.take(100))

        val secondary = when (task.intent) {
            ResearchIntent.LIBRARY_DOCS -> "$question documentation"
            ResearchIntent.BUG_ERROR -> "$question error fix"
            ResearchIntent.API_BEHAVIOR -> "$question API example"
            ResearchIntent.MIGRATION -> "$question migration guide"
            ResearchIntent.LATEST_VERSION -> "$question latest version release"
            ResearchIntent.GENERAL -> null
        }
        if (secondary != null && secondary != question) queries.add(secondary.take(100))

        if (task.localContextSummary.isNotBlank()) {
            val contextKeywords = task.localContextSummary.split(" ")
                .filter { it.length > 4 }.take(3).joinToString(" ")
            if (contextKeywords.isNotBlank()) {
                queries.add("${question.take(60)} $contextKeywords".take(100))
            }
        }

        return queries.distinct().take(3)
    }

    @Test
    fun `planResearch includes primary query`() {
        val task = ResearchTask(question = "kotlin coroutines")
        val queries = planQueries(task)
        assertTrue("Should contain original question as primary query", queries.contains("kotlin coroutines"))
    }

    @Test
    fun `planResearch LIBRARY_DOCS adds documentation suffix`() {
        val task = ResearchTask(question = "ktor client", intent = ResearchIntent.LIBRARY_DOCS)
        val queries = planQueries(task)
        assertTrue("Should add documentation secondary query", queries.any { it.contains("documentation") })
    }

    @Test
    fun `planResearch BUG_ERROR adds error fix suffix`() {
        val task = ResearchTask(question = "gradle build failed", intent = ResearchIntent.BUG_ERROR)
        val queries = planQueries(task)
        assertTrue("Should add error fix secondary query", queries.any { it.contains("error fix") })
    }

    @Test
    fun `planResearch API_BEHAVIOR adds API example suffix`() {
        val task = ResearchTask(question = "flow collect", intent = ResearchIntent.API_BEHAVIOR)
        val queries = planQueries(task)
        assertTrue("Should add API example secondary query", queries.any { it.contains("API example") })
    }

    @Test
    fun `planResearch MIGRATION adds migration guide suffix`() {
        val task = ResearchTask(question = "kotlin 1 to 2", intent = ResearchIntent.MIGRATION)
        val queries = planQueries(task)
        assertTrue("Should add migration guide secondary query", queries.any { it.contains("migration guide") })
    }

    @Test
    fun `planResearch LATEST_VERSION adds latest version release suffix`() {
        val task = ResearchTask(question = "compose version", intent = ResearchIntent.LATEST_VERSION)
        val queries = planQueries(task)
        assertTrue("Should add version release secondary query", queries.any { it.contains("latest version release") })
    }

    @Test
    fun `planResearch GENERAL does not add secondary query`() {
        val task = ResearchTask(question = "what is this", intent = ResearchIntent.GENERAL)
        val queries = planQueries(task)
        assertEquals("GENERAL intent should produce only primary query", 1, queries.size)
    }

    @Test
    fun `planResearch adds tertiary query from context summary`() {
        val task = ResearchTask(
            question = "kotlin error",
            intent = ResearchIntent.GENERAL,
            localContextSummary = "Android project using coroutines flows",
        )
        val queries = planQueries(task)
        assertTrue("Should have tertiary query from context", queries.size >= 2)
        assertTrue("Tertiary query should contain context keywords", queries.last().contains("kotlin error"))
    }

    @Test
    fun `planResearch returns at most 3 queries`() {
        val task = ResearchTask(
            question = "test question",
            intent = ResearchIntent.BUG_ERROR,
            localContextSummary = "Android project build gradle failed",
        )
        val queries = planQueries(task)
        assertTrue("Should not exceed 3 queries", queries.size <= 3)
    }

    @Test
    fun `planResearch deduplicates queries`() {
        val task = ResearchTask(question = "null", intent = ResearchIntent.GENERAL)
        val queries = planQueries(task)
        assertEquals("Queries should be distinct", queries.size, queries.distinct().size)
    }

    @Test
    fun `planResearch ignores context summary with only short words`() {
        val task = ResearchTask(
            question = "test error",
            intent = ResearchIntent.GENERAL,
            localContextSummary = "a in of", // all words ≤ 4 chars
        )
        val queries = planQueries(task)
        assertEquals("Context with only short words should not add tertiary query", 1, queries.size)
    }

    // ── synthesizeFindings logic ──────────────────────────────────────────

    private fun synthesizeFindings(pages: List<PageContent>, task: ResearchTask): List<ResearchFinding> {
        val findings = mutableListOf<ResearchFinding>()
        val queryWords = task.question.lowercase().split(" ").filter { it.length > 3 }

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
                    break
                }
            }
        }
        return findings.take(5)
    }

    @Test
    fun `synthesizeFindings extracts finding from matching paragraph`() {
        val task = ResearchTask(question = "kotlin coroutines flow")
        val page = PageContent(
            url = "https://kotlinlang.org/docs/coroutines.html",
            text = "Kotlin coroutines provide a powerful way to handle asynchronous flow programming in Android applications.",
        )
        val findings = synthesizeFindings(listOf(page), task)
        assertFalse("Should find at least one finding", findings.isEmpty())
        assertEquals("https://kotlinlang.org/docs/coroutines.html", findings[0].evidenceUrls[0])
    }

    @Test
    fun `synthesizeFindings skips pages with errors`() {
        val task = ResearchTask(question = "kotlin coroutines flow")
        val page = PageContent(
            url = "https://kotlinlang.org/docs",
            error = "HTTP 404",
            text = "kotlin coroutines flow suspend functions async",
        )
        val findings = synthesizeFindings(listOf(page), task)
        assertTrue("Should skip pages with errors", findings.isEmpty())
    }

    @Test
    fun `synthesizeFindings skips pages with blank text`() {
        val task = ResearchTask(question = "kotlin coroutines flow")
        val page = PageContent(url = "https://kotlinlang.org/docs", text = "")
        val findings = synthesizeFindings(listOf(page), task)
        assertTrue("Should skip pages with blank text", findings.isEmpty())
    }

    @Test
    fun `synthesizeFindings skips paragraph shorter than 50 chars`() {
        val task = ResearchTask(question = "kotlin coroutines flow")
        val page = PageContent(
            url = "https://kotlinlang.org/docs",
            text = "kotlin coroutines flow", // matches but too short
        )
        val findings = synthesizeFindings(listOf(page), task)
        assertTrue("Should not include too-short paragraphs", findings.isEmpty())
    }

    @Test
    fun `synthesizeFindings requires at least 2 query word matches`() {
        val task = ResearchTask(question = "kotlin coroutines suspended")
        val page = PageContent(
            url = "https://kotlinlang.org/docs",
            text = "This article is about kotlin language features. Here we discuss the syntax and semantics of the language in great detail.",
        )
        val findings = synthesizeFindings(listOf(page), task)
        // "kotlin" matches, "coroutines" does not, "suspended" does not → matchCount == 1, not 2
        assertTrue("Should not match with only 1 query word", findings.isEmpty())
    }

    @Test
    fun `synthesizeFindings returns at most one finding per page`() {
        val task = ResearchTask(question = "kotlin coroutines flow")
        val twoMatchingParas = "Kotlin coroutines provide flow suspend functions for async programming.\n\nKotlin coroutines and flow operators allow building reactive stream processing pipelines."
        val page = PageContent(url = "https://kotlinlang.org/docs", text = twoMatchingParas)
        val findings = synthesizeFindings(listOf(page), task)
        assertEquals("Should return at most one finding per page", 1, findings.size)
    }

    @Test
    fun `synthesizeFindings returns at most 5 findings total`() {
        val task = ResearchTask(question = "kotlin coroutines flow")
        val matchingText = "Kotlin coroutines provide flow suspend functions for async programming and reactive streams."
        val pages = (1..10).map { i ->
            PageContent(url = "https://kotlinlang.org/page$i", text = matchingText)
        }
        val findings = synthesizeFindings(pages, task)
        assertTrue("Should return at most 5 findings", findings.size <= 5)
    }

    @Test
    fun `synthesizeFindings ignores query words of 3 chars or less`() {
        val task = ResearchTask(question = "fix the bug") // "fix" and "the" are ≤ 3 chars; "bug" is 3 chars
        val page = PageContent(
            url = "https://example.com",
            text = "fix the bug in your code by checking the stack trace carefully and reviewing the logs.",
        )
        val findings = synthesizeFindings(listOf(page), task)
        // All query words are ≤ 3 chars → queryWords is empty → matchCount 0 → no findings
        assertTrue("Words with ≤3 chars should not count as query words", findings.isEmpty())
    }

    // ── buildCitations logic ───────────────────────────────────────────────

    private fun buildCitations(results: List<SearchResult>): List<Citation> {
        return results.map { result ->
            Citation(
                title = result.title,
                url = result.url,
                sourceType = classifySourceType(result.url),
            )
        }.distinctBy { it.url }
    }

    @Test
    fun `buildCitations maps search result to citation`() {
        val results = listOf(
            SearchResult(title = "Kotlin Docs", url = "https://kotlinlang.org/docs", snippet = "...", source = "kotlinlang.org"),
        )
        val citations = buildCitations(results)
        assertEquals(1, citations.size)
        assertEquals("Kotlin Docs", citations[0].title)
        assertEquals("https://kotlinlang.org/docs", citations[0].url)
        assertEquals(SourceType.OFFICIAL_DOCS, citations[0].sourceType)
    }

    @Test
    fun `buildCitations deduplicates by URL`() {
        val results = listOf(
            SearchResult(title = "Kotlin Docs A", url = "https://kotlinlang.org/docs", snippet = "...", source = "kotlinlang.org"),
            SearchResult(title = "Kotlin Docs B", url = "https://kotlinlang.org/docs", snippet = "...", source = "kotlinlang.org"),
        )
        val citations = buildCitations(results)
        assertEquals("Should deduplicate by URL", 1, citations.size)
    }

    @Test
    fun `buildCitations classifies source types correctly`() {
        val results = listOf(
            SearchResult(title = "KL", url = "https://kotlinlang.org/docs", snippet = "", source = "kotlinlang.org"),
            SearchResult(title = "GH", url = "https://github.com/user/repo/issues/1", snippet = "", source = "github.com"),
            SearchResult(title = "SO", url = "https://stackoverflow.com/questions/1", snippet = "", source = "stackoverflow.com"),
        )
        val citations = buildCitations(results)
        assertEquals(SourceType.OFFICIAL_DOCS, citations.find { it.url.contains("kotlinlang") }!!.sourceType)
        assertEquals(SourceType.GITHUB_ISSUES, citations.find { it.url.contains("issues") }!!.sourceType)
        assertEquals(SourceType.STACKOVERFLOW, citations.find { it.url.contains("stackoverflow") }!!.sourceType)
    }

    @Test
    fun `buildCitations returns empty list for empty results`() {
        val citations = buildCitations(emptyList())
        assertTrue("Empty results should produce empty citations", citations.isEmpty())
    }
}
