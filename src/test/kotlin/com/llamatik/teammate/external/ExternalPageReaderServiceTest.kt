package com.llamatik.teammate.external

import com.llamatik.teammate.external.reader.ExternalPageReaderService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalPageReaderServiceTest {

    private fun makeService(): ExternalPageReaderService = ExternalPageReaderService()

    @Test
    fun `extractContent strips HTML tags from body`() {
        val svc = makeService()
        val html = "<html><body><p>Hello <b>World</b></p></body></html>"
        val content = svc.extractContent(html, "https://example.com", 8000)
        assertFalse("Should strip HTML tags", content.text.contains("<p>"))
        assertFalse("Should strip bold tags", content.text.contains("<b>"))
        assertTrue("Should retain text content", content.text.contains("Hello"))
        assertTrue("Should retain text content", content.text.contains("World"))
        assertNull("No error expected", content.error)
    }

    @Test
    fun `extractContent extracts title correctly`() {
        val svc = makeService()
        val html = "<html><head><title>My Page Title</title></head><body>Content</body></html>"
        val content = svc.extractContent(html, "https://example.com", 8000)
        assertEquals("My Page Title", content.title)
    }

    @Test
    fun `extractContent extracts headings`() {
        val svc = makeService()
        val html = """
            <html><body>
            <h1>Main Heading</h1>
            <h2>Sub Heading</h2>
            <h3>Third Level</h3>
            </body></html>
        """.trimIndent()
        val content = svc.extractContent(html, "https://example.com", 8000)
        assertTrue("Should contain h1", content.headings.any { it.contains("Main Heading") })
        assertTrue("Should contain h2", content.headings.any { it.contains("Sub Heading") })
    }

    @Test
    fun `extractContent extracts code blocks`() {
        val svc = makeService()
        val html = """
            <html><body>
            <pre>fun hello() { println("hi") }</pre>
            <code>val x = 42</code>
            </body></html>
        """.trimIndent()
        val content = svc.extractContent(html, "https://example.com", 8000)
        assertTrue("Should contain code block content",
            content.codeBlocks.any { it.contains("hello") || it.contains("42") })
    }

    @Test
    fun `extractContent removes script content`() {
        val svc = makeService()
        val html = """
            <html><body>
            <script>var x = "secret script";</script>
            <p>Visible content</p>
            </body></html>
        """.trimIndent()
        val content = svc.extractContent(html, "https://example.com", 8000)
        assertFalse("Should remove script content", content.text.contains("secret script"))
        assertTrue("Should retain visible content", content.text.contains("Visible content"))
    }

    @Test
    fun `extractContent removes style content`() {
        val svc = makeService()
        val html = "<html><head><style>.foo { color: red; }</style></head><body><p>Text</p></body></html>"
        val content = svc.extractContent(html, "https://example.com", 8000)
        assertFalse("Should remove style content", content.text.contains("color: red"))
    }

    @Test
    fun `extractContent removes nav header footer aside`() {
        val svc = makeService()
        val html = """
            <html><body>
            <nav>Navigation menu items</nav>
            <header>Site header</header>
            <footer>Footer copyright</footer>
            <aside>Sidebar content</aside>
            <main>Main content here</main>
            </body></html>
        """.trimIndent()
        val content = svc.extractContent(html, "https://example.com", 8000)
        assertFalse("Should remove nav", content.text.contains("Navigation menu items"))
        assertFalse("Should remove footer", content.text.contains("Footer copyright"))
        assertTrue("Should keep main content", content.text.contains("Main content here"))
    }

    @Test
    fun `extractContent truncates text to maxChars`() {
        val svc = makeService()
        val longText = "a".repeat(10000)
        val html = "<html><body><p>$longText</p></body></html>"
        val content = svc.extractContent(html, "https://example.com", 500)
        assertTrue("Text should be truncated", content.text.length <= 500)
    }

    @Test
    fun `extractContent handles empty HTML gracefully`() {
        val svc = makeService()
        val content = svc.extractContent("", "https://example.com", 8000)
        assertNotNull("Should return PageContent", content)
    }

    @Test
    fun `extractContent handles malformed HTML without crashing`() {
        val svc = makeService()
        val malformed = "<html><body><p>Unclosed paragraph<br><div>Nested<p>Text"
        val content = svc.extractContent(malformed, "https://example.com", 8000)
        assertNotNull("Should not crash on malformed HTML", content)
    }
}
