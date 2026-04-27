package com.llamatik.teammate.agent

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the "current file reference" resolution logic in AgentOrchestrator.
 *
 * The production code lives in resolveCurrentFileReference() but the phrase list
 * is testable via AgentOrchestrator.CURRENT_FILE_PHRASES.
 *
 * We test the pure transformation: given a phrase, does it match and get rewritten?
 */
class CurrentFileResolverTest {

    private val phrases = AgentOrchestrator.CURRENT_FILE_PHRASES

    // Helper: simulates the rewrite that resolveCurrentFileReference does
    private fun rewrite(prompt: String, fileName: String): String {
        var result = prompt
        for (phrase in phrases) {
            result = result.replace(phrase, fileName, ignoreCase = true)
        }
        return result
    }

    @Test
    fun `'create tests for the current file' rewrites to filename`() {
        val result = rewrite("create tests for the current file", "DCSAuthenticationRepository.kt")
        assertEquals("create tests for DCSAuthenticationRepository.kt", result)
    }

    @Test
    fun `'write tests for this file' rewrites to filename`() {
        val result = rewrite("write tests for this file", "Foo.kt")
        assertEquals("write tests for Foo.kt", result)
    }

    @Test
    fun `'review the open file' rewrites to filename`() {
        val result = rewrite("review the open file", "Bar.kt")
        assertEquals("review Bar.kt", result)
    }

    @Test
    fun `'review the opened file' rewrites to filename`() {
        val result = rewrite("review the opened file", "Bar.kt")
        assertEquals("review Bar.kt", result)
    }

    @Test
    fun `rewrite is case-insensitive`() {
        val result = rewrite("Create tests for THE CURRENT FILE", "Baz.kt")
        assertEquals("Create tests for Baz.kt", result)
    }

    @Test
    fun `prompt with explicit filename is unchanged — no vague phrase`() {
        val prompt = "create tests for DCSAuthenticationRepository.kt"
        val hasVagueRef = phrases.any { prompt.contains(it, ignoreCase = true) }
        assertFalse("Explicit filename should not trigger vague-ref rewrite", hasVagueRef)
    }

    @Test
    fun `all standard vague phrases are detected`() {
        val examples = listOf(
            "create tests for the current file",
            "review this file",
            "refactor the open file",
            "the opened file needs cleanup",
            "current file has a bug",
            "open file is missing tests",
        )
        for (example in examples) {
            val detected = phrases.any { example.contains(it, ignoreCase = true) }
            assertTrue("'$example' should be detected as a vague file reference", detected)
        }
    }
}
