package com.llamatik.teammate.external

import com.llamatik.teammate.external.search.SearchQuery
import com.llamatik.teammate.external.search.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL

/**
 * Tests for the JSON parsing logic of ExternalSearchService.parseResults.
 * The logic is replicated inline so no IntelliJ services are needed.
 */
class ExternalSearchParserTest {

    private fun parseResults(json: String, query: SearchQuery, isPro: Boolean): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val maxResults = ExternalKnowledgeLimits.maxResultCount(isPro).coerceAtMost(query.maxResults)

        val relatedTopicsStart = json.indexOf("\"RelatedTopics\"")
        if (relatedTopicsStart < 0) return results

        val arrayStart = json.indexOf('[', relatedTopicsStart)
        if (arrayStart < 0) return results

        val topicRegex = Regex(""""Text"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        val urlRegex = Regex(""""FirstURL"\s*:\s*"((?:[^"\\]|\\.)*)"""")

        val topicStrings = mutableListOf<String>()
        var i = arrayStart
        var bracketDepth = 0
        var inString = false
        var escape = false
        var objectStart = -1

        while (i < json.length && results.size < maxResults) {
            val c = json[i]
            when {
                escape -> escape = false
                c == '\\' && inString -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '[' -> bracketDepth++
                !inString && c == ']' -> {
                    bracketDepth--
                    if (bracketDepth <= 0) break
                }
                !inString && c == '{' -> {
                    if (bracketDepth == 1) objectStart = i
                    bracketDepth++
                }
                !inString && c == '}' -> {
                    bracketDepth--
                    if (bracketDepth == 1 && objectStart >= 0) {
                        topicStrings.add(json.substring(objectStart, i + 1))
                        objectStart = -1
                    }
                }
            }
            i++
        }

        for (topicJson in topicStrings) {
            if (results.size >= maxResults) break
            val textMatch = topicRegex.find(topicJson) ?: continue
            val urlMatch = urlRegex.find(topicJson) ?: continue

            val text = textMatch.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
            val firstUrl = urlMatch.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")

            if (text.isBlank() || firstUrl.isBlank()) continue

            val urlHost = runCatching { URL(firstUrl).host }.getOrElse { "" }
            if (query.blockedDomains.any { urlHost.contains(it) }) continue
            if (query.domains.isNotEmpty() && !query.domains.any { urlHost.contains(it) }) continue

            val title = text.substringBefore(" - ").take(120).ifBlank { text.take(80) }
            val snippet = text.take(300)

            results.add(
                SearchResult(
                    title = title,
                    url = firstUrl,
                    snippet = snippet,
                    source = urlHost,
                )
            )
        }

        return results
    }

    private fun ddgJson(vararg topics: Pair<String, String>): String {
        val items = topics.joinToString(",") { (text, url) ->
            """{"Text":"$text","FirstURL":"$url","Icon":{"URL":"","Height":"","Width":""}}"""
        }
        return """{"Abstract":"","RelatedTopics":[$items]}"""
    }

    @Test
    fun `parseResults extracts title and url from RelatedTopics`() {
        val json = ddgJson(
            "Kotlin Coroutines - Official documentation for coroutines" to "https://kotlinlang.org/docs/coroutines.html",
        )
        val results = parseResults(json, SearchQuery("kotlin coroutines"), false)
        assertEquals(1, results.size)
        assertEquals("https://kotlinlang.org/docs/coroutines.html", results[0].url)
        assertTrue("Title should be text before ' - '", results[0].title.contains("Kotlin Coroutines"))
    }

    @Test
    fun `parseResults returns multiple results`() {
        val json = ddgJson(
            "Result One - first result" to "https://kotlinlang.org/one",
            "Result Two - second result" to "https://developer.android.com/two",
        )
        val results = parseResults(json, SearchQuery("test"), false)
        assertEquals(2, results.size)
    }

    @Test
    fun `parseResults respects maxResults limit`() {
        val json = ddgJson(
            "Result One" to "https://a.example.com/1",
            "Result Two" to "https://b.example.com/2",
            "Result Three" to "https://c.example.com/3",
            "Result Four" to "https://d.example.com/4",
            "Result Five" to "https://e.example.com/5",
            "Result Six" to "https://f.example.com/6",
        )
        val results = parseResults(json, SearchQuery("test", maxResults = 3), false)
        assertTrue("Should not exceed maxResults=3", results.size <= 3)
    }

    @Test
    fun `parseResults skips entry with blank text`() {
        val json = """{"RelatedTopics":[{"Text":"","FirstURL":"https://example.com/1"},{"Text":"Valid result text","FirstURL":"https://example.com/2"}]}"""
        val results = parseResults(json, SearchQuery("test"), false)
        assertEquals(1, results.size)
        assertEquals("https://example.com/2", results[0].url)
    }

    @Test
    fun `parseResults skips entry with blank URL`() {
        val json = """{"RelatedTopics":[{"Text":"No URL result","FirstURL":""},{"Text":"Has URL result","FirstURL":"https://example.com/valid"}]}"""
        val results = parseResults(json, SearchQuery("test"), false)
        assertEquals(1, results.size)
        assertEquals("https://example.com/valid", results[0].url)
    }

    @Test
    fun `parseResults filters out blocked domains`() {
        val json = ddgJson(
            "Blocked site result" to "https://blocked.example.com/page",
            "Allowed site result" to "https://kotlinlang.org/page",
        )
        val results = parseResults(json, SearchQuery("test", blockedDomains = listOf("blocked.example.com")), false)
        assertEquals(1, results.size)
        assertTrue("Should only contain allowed URL", results[0].url.contains("kotlinlang.org"))
    }

    @Test
    fun `parseResults filters by domain when domains specified`() {
        val json = ddgJson(
            "Kotlin docs result" to "https://kotlinlang.org/docs/page",
            "Stack Overflow result" to "https://stackoverflow.com/questions/123",
        )
        val results = parseResults(json, SearchQuery("test", domains = listOf("kotlinlang.org")), false)
        assertEquals(1, results.size)
        assertTrue("Should only return kotlinlang.org result", results[0].url.contains("kotlinlang.org"))
    }

    @Test
    fun `parseResults returns all results when no domain filter`() {
        val json = ddgJson(
            "Kotlin result" to "https://kotlinlang.org/page",
            "Android result" to "https://developer.android.com/page",
        )
        val results = parseResults(json, SearchQuery("test"), false)
        assertEquals(2, results.size)
    }

    @Test
    fun `parseResults returns empty list when RelatedTopics missing`() {
        val json = """{"Abstract":"No related topics here"}"""
        val results = parseResults(json, SearchQuery("test"), false)
        assertTrue("Should return empty list", results.isEmpty())
    }

    @Test
    fun `parseResults returns empty list on empty JSON`() {
        val results = parseResults("", SearchQuery("test"), false)
        assertTrue("Should return empty list on empty JSON", results.isEmpty())
    }

    @Test
    fun `parseResults returns empty list when RelatedTopics is empty array`() {
        val json = """{"RelatedTopics":[]}"""
        val results = parseResults(json, SearchQuery("test"), false)
        assertTrue("Should return empty list for empty RelatedTopics", results.isEmpty())
    }

    @Test
    fun `parseResults sets source from URL host`() {
        val json = ddgJson("Kotlin docs" to "https://kotlinlang.org/docs/page")
        val results = parseResults(json, SearchQuery("test"), false)
        assertEquals("kotlinlang.org", results[0].source)
    }

    @Test
    fun `parseResults snippet is populated`() {
        val json = ddgJson("Kotlin Coroutines - The full text of this result" to "https://kotlinlang.org/docs")
        val results = parseResults(json, SearchQuery("test"), false)
        assertFalse("Snippet should be populated", results[0].snippet.isBlank())
    }

    @Test
    fun `parseResults title uses text before dash separator`() {
        val json = ddgJson("Kotlin Flows - Official guide to flows" to "https://kotlinlang.org/docs/flow.html")
        val results = parseResults(json, SearchQuery("test"), false)
        assertEquals("Kotlin Flows", results[0].title)
    }

    @Test
    fun `parseResults title falls back to full text when no dash`() {
        val json = ddgJson("Kotlin documentation" to "https://kotlinlang.org/docs")
        val results = parseResults(json, SearchQuery("test"), false)
        assertEquals("Kotlin documentation", results[0].title)
    }

    @Test
    fun `parseResults handles escaped quotes in text`() {
        val json = """{"RelatedTopics":[{"Text":"Result with \"quotes\" inside","FirstURL":"https://example.com/page"}]}"""
        val results = parseResults(json, SearchQuery("test"), false)
        assertEquals(1, results.size)
        assertTrue("Should contain unescaped quotes", results[0].snippet.contains("\"") || results[0].title.contains("\""))
    }

    @Test
    fun `parseResults pro tier allows up to 10 results`() {
        val topics = (1..12).map { i -> "Result $i - description" to "https://example$i.com/page" }
        val json = ddgJson(*topics.toTypedArray())
        val results = parseResults(json, SearchQuery("test", maxResults = 20), isPro = true)
        assertTrue("Pro tier should allow up to 10 results", results.size <= 10)
    }

    @Test
    fun `parseResults free tier limited to 8 results`() {
        val topics = (1..10).map { i -> "Result $i - description" to "https://example$i.com/page" }
        val json = ddgJson(*topics.toTypedArray())
        val results = parseResults(json, SearchQuery("test", maxResults = 20), isPro = false)
        assertTrue("Free tier should be limited to 8 results", results.size <= 8)
    }
}
