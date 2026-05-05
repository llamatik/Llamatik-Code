package com.llamatik.teammate.index

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the lexical indexing and BM25 search logic extracted from ProjectIndexService.
 * No IntelliJ platform required.
 */
class ProjectIndexServiceTest {

    // ── Tokenizer ─────────────────────────────────────────────────────────────

    private fun tokenize(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^a-zA-Z0-9_]+"))
            .filter { it.length >= 3 }
            .toSet()

    @Test
    fun `tokenize splits on non-word chars`() {
        val tokens = tokenize("fun authenticate(user: String)")
        assertTrue(tokens.contains("fun"))
        assertTrue(tokens.contains("authenticate"))
        assertTrue(tokens.contains("user"))
        assertTrue(tokens.contains("string"))
    }

    @Test
    fun `tokenize filters short tokens`() {
        val tokens = tokenize("a bb ccc dddd")
        assertFalse(tokens.contains("a"))
        assertFalse(tokens.contains("bb"))
        assertTrue(tokens.contains("ccc"))
        assertTrue(tokens.contains("dddd"))
    }

    // ── BM25 scoring ─────────────────────────────────────────────────────────

    private fun bm25Score(queryTokens: Set<String>, docTokens: Set<String>, docLen: Int): Double {
        val k1 = 1.5; val b = 0.75; val avgDocLen = 500.0
        return queryTokens.sumOf { token ->
            if (token !in docTokens) return@sumOf 0.0
            val tf = docTokens.count { it == token }.toDouble()
            val norm = k1 * ((1 - b) + b * docLen / avgDocLen)
            tf * (k1 + 1) / (tf + norm)
        }
    }

    @Test
    fun `bm25 returns 0 for unrelated document`() {
        val query = tokenize("authentication login")
        val doc = tokenize("class Foo { fun bar() = 42 }")
        assertEquals(0.0, bm25Score(query, doc, 50), 0.001)
    }

    @Test
    fun `bm25 scores matching document higher than unrelated`() {
        val query = tokenize("authentication login")
        val relevant = tokenize("fun authenticate(user: String) { /* login logic */ }")
        val unrelated = tokenize("class Foo { fun bar() = 42 }")
        assertTrue(bm25Score(query, relevant, 60) > bm25Score(query, unrelated, 40))
    }

    @Test
    fun `bm25 favors exact token match`() {
        val query = tokenize("StateFlow viewmodel")
        val high = tokenize("val stateflow: StateFlow<UiState> = viewmodel.state")
        val low = tokenize("class SomeRepository { fun fetch() {} }")
        assertTrue(bm25Score(query, high, 80) > bm25Score(query, low, 40))
    }

    // ── Indexable path filter ─────────────────────────────────────────────────

    private fun isIndexable(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        val normalized = path.replace('\\', '/')
        val excluded = setOf(".git", ".idea", ".gradle", "build", "out", "target",
            "node_modules", "generated", "intermediates", "__pycache__")
        if (excluded.any { seg ->
            normalized == seg ||
            normalized.startsWith("$seg/") ||
            normalized.contains("/$seg/") ||
            normalized.endsWith("/$seg")
        }) return false
        return ext in setOf("kt", "java", "py", "ts", "tsx", "js", "jsx",
            "swift", "go", "rs", "cpp", "c", "cs", "rb", "gradle", "kts", "xml", "json", "md")
    }

    @Test
    fun `indexable accepts kotlin files`() {
        assertTrue(isIndexable("src/main/Foo.kt"))
    }

    @Test
    fun `indexable rejects build directory`() {
        assertFalse(isIndexable("build/classes/Foo.class"))
        assertFalse(isIndexable("src/build/output/Bar.kt"))  // /build/ in path
    }

    @Test
    fun `indexable rejects node_modules`() {
        assertFalse(isIndexable("node_modules/react/index.js"))
    }

    @Test
    fun `indexable rejects binary extensions`() {
        assertFalse(isIndexable("app/release.apk"))
        assertFalse(isIndexable("lib/native.so"))
    }

    @Test
    fun `indexable accepts markdown and json`() {
        assertTrue(isIndexable("README.md"))
        assertTrue(isIndexable("config/settings.json"))
    }
}
