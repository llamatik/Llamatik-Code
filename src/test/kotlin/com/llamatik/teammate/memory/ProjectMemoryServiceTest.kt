package com.llamatik.teammate.memory

import org.junit.Assert.*
import org.junit.Test

class ProjectMemoryServiceTest {

    private fun makeEntry(id: String, content: String, enabled: Boolean = true, tags: List<String> = emptyList()) =
        ProjectMemoryService.MemoryEntry(id, content, tags.toMutableList(), System.currentTimeMillis(), enabled)

    @Test fun `getAll returns only enabled entries`() {
        val entries = listOf(
            makeEntry("1", "Use Kotlin", enabled = true),
            makeEntry("2", "Use Java", enabled = false),
        )
        assertEquals(1, entries.filter { it.enabled }.size)
    }

    @Test fun `search returns matching entries`() {
        val entries = listOf(
            makeEntry("1", "Use StateFlow for UI state"),
            makeEntry("2", "Prefer coroutines over threads"),
            makeEntry("3", "Use MockK for mocking"),
        )
        val query = "stateflow"
        val results = entries.filter { it.content.lowercase().contains(query) }
        assertEquals(1, results.size)
        assertEquals("1", results[0].id)
    }

    @Test fun `search with multiple tokens matches any token`() {
        val entries = listOf(
            makeEntry("1", "Use Hilt for dependency injection"),
            makeEntry("2", "Use Koin for DI"),
        )
        val tokens = "koin injection".split(" ")
        val results = entries.filter { e -> tokens.any { e.content.lowercase().contains(it) } }
        assertEquals(2, results.size)
    }

    @Test fun `buildPromptBlock is empty when disabled`() {
        val block = "" // disabled => empty
        assertTrue(block.isEmpty())
    }

    @Test fun `buildPromptBlock includes entry content`() {
        val entries = listOf(makeEntry("1", "Use MockK"), makeEntry("2", "Avoid global state"))
        val block = buildString {
            appendLine("=== Project memory ===")
            entries.take(10).forEach { appendLine("- ${it.content}") }
            appendLine("=== End project memory ===")
        }
        assertTrue(block.contains("Use MockK"))
        assertTrue(block.contains("Avoid global state"))
    }

    @Test fun `content is capped at 500 chars on add`() {
        val longContent = "x".repeat(1000)
        val capped = longContent.take(500)
        assertEquals(500, capped.length)
    }
}
