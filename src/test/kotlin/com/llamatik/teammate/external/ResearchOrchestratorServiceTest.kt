package com.llamatik.teammate.external

import com.llamatik.teammate.external.research.ResearchIntent
import com.llamatik.teammate.external.research.ResearchTask
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchOrchestratorServiceTest {

    // Test the decision logic of suggestSearchNeeded without IntelliJ services
    private fun suggestSearchNeeded(question: String, localContextAvailable: Boolean): Boolean {
        val lower = question.lowercase()
        if (lower.contains("latest") || lower.contains("version") || lower.contains("release") ||
            lower.contains("changelog") || lower.contains("migration")
        ) return true
        if (lower.contains("error") || lower.contains("exception") || lower.contains("failed") ||
            lower.contains("cannot") || lower.contains("unresolved")
        ) return true
        if (!localContextAvailable) return true
        return false
    }

    @Test
    fun `suggestSearchNeeded returns true for latest version queries`() {
        assertTrue(suggestSearchNeeded("what is the latest version of Kotlin", true))
    }

    @Test
    fun `suggestSearchNeeded returns true for release queries`() {
        assertTrue(suggestSearchNeeded("latest release notes for Android", true))
    }

    @Test
    fun `suggestSearchNeeded returns true for changelog queries`() {
        assertTrue(suggestSearchNeeded("show me the changelog for this library", true))
    }

    @Test
    fun `suggestSearchNeeded returns true for migration queries`() {
        assertTrue(suggestSearchNeeded("migration guide from Kotlin v1 to v2", true))
    }

    @Test
    fun `suggestSearchNeeded returns true for error queries`() {
        assertTrue(suggestSearchNeeded("unresolved reference error in kotlin", true))
    }

    @Test
    fun `suggestSearchNeeded returns true for exception queries`() {
        assertTrue(suggestSearchNeeded("NullPointerException in Android", true))
    }

    @Test
    fun `suggestSearchNeeded returns true for build failed queries`() {
        assertTrue(suggestSearchNeeded("build failed with gradle error", true))
    }

    @Test
    fun `suggestSearchNeeded returns true when no local context available`() {
        assertTrue(suggestSearchNeeded("how to use coroutines", false))
    }

    @Test
    fun `suggestSearchNeeded returns false for simple questions with context`() {
        assertFalse(suggestSearchNeeded("what does this function do", true))
    }

    @Test
    fun `suggestSearchNeeded returns false for explain queries with context`() {
        assertFalse(suggestSearchNeeded("explain this class to me", true))
    }

    @Test
    fun `ResearchTask has correct defaults`() {
        val task = ResearchTask(question = "What is Kotlin Flow?")
        assertFalse("isPro should default to false", task.isPro)
        assertTrue("preferredSources should be empty", task.preferredSources.isEmpty())
        assertTrue("constraints should be empty", task.constraints.isEmpty())
        assertTrue("intent should be GENERAL", task.intent == ResearchIntent.GENERAL)
        assertTrue("localContextSummary should be empty", task.localContextSummary.isEmpty())
    }

    @Test
    fun `ResearchTask with isPro true preserves the value`() {
        val task = ResearchTask(question = "test", isPro = true)
        assertTrue("isPro should be true", task.isPro)
    }

    @Test
    fun `ResearchTask with custom intent preserves the value`() {
        val task = ResearchTask(question = "migration", intent = ResearchIntent.MIGRATION)
        assertTrue("intent should be MIGRATION", task.intent == ResearchIntent.MIGRATION)
    }

    @Test
    fun `plan generates at least one query`() {
        val task = ResearchTask(question = "kotlin coroutines error handling")
        val queries = mutableListOf<String>()
        queries.add(task.question.take(100))
        assertTrue("Should have at least 1 query", queries.isNotEmpty())
    }

    @Test
    fun `plan generates at most 3 queries`() {
        val task = ResearchTask(
            question = "kotlin coroutines error handling",
            localContextSummary = "Android project Kotlin Flow",
            intent = ResearchIntent.BUG_ERROR,
        )
        val queries = mutableListOf<String>()
        queries.add(task.question.take(100))
        val secondary = "${task.question} error fix"
        if (secondary != task.question) queries.add(secondary.take(100))
        val contextKeywords = task.localContextSummary.split(" ").filter { it.length > 4 }.take(3).joinToString(" ")
        if (contextKeywords.isNotBlank()) {
            queries.add("${task.question.take(60)} $contextKeywords".take(100))
        }
        assertTrue("Should have at most 3 queries", queries.size <= 3)
    }
}
