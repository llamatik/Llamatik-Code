package com.llamatik.teammate.external

import com.llamatik.teammate.external.reader.ExternalPageReaderService
import com.llamatik.teammate.external.reader.PageContent
import com.llamatik.teammate.external.reader.PageReadRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalPageReaderExtendedTest {

    private fun makeService(): ExternalPageReaderService = ExternalPageReaderService()

    // ── HTML entity decoding ───────────────────────────────────────────────

    @Test
    fun `extractContent decodes amp entity`() {
        val svc = makeService()
        val html = "<html><body><p>Bread &amp; Butter</p></body></html>"
        val content = svc.extractContent(html, "https://example.com", 8000)
        assertTrue("Should decode &amp; to &", content.text.contains("Bread & Butter"))
    }

    @Test
    fun `extractContent decodes lt and gt entities`() {
        val svc = makeService()
        val html = "<html><body><p>Type &lt;T&gt; is generic</p></body></html>"
        val content = svc.extractContent(html, "https://example.com", 8000)
        assertTrue("Should decode &lt; to <", content.text.contains("<"))
        assertTrue("Should decode &gt; to >", content.text.contains(">"))
    }

    @Test
    fun `extractContent decodes quot entity`() {
        val svc = makeService()
        val html = """<html><body><p>He said &quot;hello&quot;</p></body></html>"""
        val content = svc.extractContent(html, "https://example.com", 8000)
        assertTrue("Should decode &quot; to \"", content.text.contains("\"hello\""))
    }

    @Test
    fun `extractContent decodes nbsp entity`() {
        val svc = makeService()
        val html = "<html><body><p>Word1&nbsp;Word2</p></body></html>"
        val content = svc.extractContent(html, "https://example.com", 8000)
        assertTrue("Should decode &nbsp; to space", content.text.contains("Word1") && content.text.contains("Word2"))
        assertFalse("Should not contain raw &nbsp;", content.text.contains("&nbsp;"))
    }

    @Test
    fun `extractContent decodes apostrophe entity`() {
        val svc = makeService()
        val html = "<html><body><p>It&#39;s working</p></body></html>"
        val content = svc.extractContent(html, "https://example.com", 8000)
        assertTrue("Should decode &#39; to apostrophe", content.text.contains("'"))
        assertFalse("Should not contain raw &#39;", content.text.contains("&#39;"))
    }

    // ── Links extraction ───────────────────────────────────────────────────

    @Test
    fun `extractContent extracts http links`() {
        val svc = makeService()
        val html = """<html><body><a href="https://kotlinlang.org/docs">Kotlin</a></body></html>"""
        val content = svc.extractContent(html, "https://example.com", 8000)
        assertTrue("Should extract http links", content.links.contains("https://kotlinlang.org/docs"))
    }

    @Test
    fun `extractContent does not include anchor-only links`() {
        val svc = makeService()
        val html = """<html><body><a href="#section">Jump</a><a href="https://example.com/page">Real link</a></body></html>"""
        val content = svc.extractContent(html, "https://example.com", 8000)
        assertFalse("Should not include # anchors", content.links.any { it.startsWith("#") })
    }

    @Test
    fun `extractContent deduplicates links`() {
        val svc = makeService()
        val html = """<html><body>
            <a href="https://kotlinlang.org/docs">Link 1</a>
            <a href="https://kotlinlang.org/docs">Link 1 duplicate</a>
            </body></html>"""
        val content = svc.extractContent(html, "https://example.com", 8000)
        assertEquals("Links should be deduplicated", content.links.size, content.links.distinct().size)
    }

    // ── readPages mixed success/failure ────────────────────────────────────

    @Test
    fun `readPages returns a result for every input request`() {
        val svc = makeService()
        val requests = listOf(
            PageReadRequest("https://this-domain-should-not-exist-abc123xyz.invalid/page"),
            PageReadRequest("https://another-invalid-domain-xyz987.invalid/page"),
        )
        val results = svc.readPages(requests)
        assertEquals("Should return one result per request", 2, results.size)
    }

    @Test
    fun `readPages does not throw when one request fails`() {
        val svc = makeService()
        val requests = listOf(
            PageReadRequest("https://completely.invalid.domain.xyz/page"),
        )
        var results: List<PageContent>? = null
        var threw = false
        try {
            results = svc.readPages(requests)
        } catch (e: Exception) {
            threw = true
        }
        assertFalse("readPages should not throw", threw)
        assertNotNull("readPages should return a list", results)
    }

    @Test
    fun `readPages sets error on failed page content`() {
        val svc = makeService()
        val requests = listOf(
            PageReadRequest("https://completely.invalid.domain.xyz.test/page"),
        )
        val results = svc.readPages(requests)
        assertEquals(1, results.size)
        assertNotNull("Error field should be set for failed page", results[0].error)
    }

    @Test
    fun `readPages preserves url in each result`() {
        val svc = makeService()
        val url = "https://some.invalid.xyz/path"
        val results = svc.readPages(listOf(PageReadRequest(url)))
        assertEquals("URL should be preserved in result", url, results[0].url)
    }

    // ── Edge cases ─────────────────────────────────────────────────────────

    @Test
    fun `extractContent with no links returns empty links list`() {
        val svc = makeService()
        val html = "<html><body><p>No links here</p></body></html>"
        val content = svc.extractContent(html, "https://example.com", 8000)
        assertTrue("Links list should be empty", content.links.isEmpty())
    }

    @Test
    fun `extractContent handles multiple HTML entities in same text`() {
        val svc = makeService()
        val html = "<html><body><p>A &amp; B &lt; C &gt; D</p></body></html>"
        val content = svc.extractContent(html, "https://example.com", 8000)
        assertTrue("Should decode all entities", content.text.contains("&"))
        assertTrue("Should decode all entities", content.text.contains("<"))
        assertTrue("Should decode all entities", content.text.contains(">"))
        assertFalse("Should not contain raw entity &amp;", content.text.contains("&amp;"))
        assertFalse("Should not contain raw entity &lt;", content.text.contains("&lt;"))
    }
}
