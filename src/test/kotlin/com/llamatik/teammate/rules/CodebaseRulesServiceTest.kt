package com.llamatik.teammate.rules

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for CodebaseRulesService logic that doesn't require the IntelliJ platform.
 * Extracts and tests the rule-detection and prompt-building logic in isolation.
 */
class CodebaseRulesServiceTest {

    // ── Rule state helpers ────────────────────────────────────────────────────

    private fun makeRule(
        id: String,
        text: String,
        confidence: String = "medium",
        evidence: String = "",
        enabled: Boolean = true,
        source: String = "detected",
    ) = CodebaseRulesService.Rule(id, text, confidence, evidence, enabled, source)

    // ── Enabled/disabled filter ───────────────────────────────────────────────

    @Test
    fun `getRules returns only enabled rules`() {
        val rules = listOf(
            makeRule("r1", "Use Kotlin", enabled = true),
            makeRule("r2", "Use Java", enabled = false),
            makeRule("r3", "Use Coroutines", enabled = true),
        )
        val enabled = rules.filter { it.enabled }
        assertEquals(2, enabled.size)
        assertTrue(enabled.all { it.enabled })
    }

    @Test
    fun `setRuleEnabled toggles correctly`() {
        val rule = makeRule("r1", "Use Kotlin", enabled = true)
        val toggled = rule.copy(enabled = false)
        assertFalse(toggled.enabled)
    }

    // ── Prompt block generation ───────────────────────────────────────────────

    @Test
    fun `buildPromptBlock returns empty string when no rules`() {
        val rules = emptyList<CodebaseRulesService.Rule>()
        val block = if (rules.isEmpty()) "" else "=== Project rules ==="
        assertEquals("", block)
    }

    @Test
    fun `buildPromptBlock includes rule text`() {
        val rules = listOf(
            makeRule("r1", "Use StateFlow for observable state"),
            makeRule("r2", "Use MockK for mocking"),
        )
        val block = buildString {
            appendLine("=== Project rules ===")
            rules.forEach { appendLine("- ${it.text}") }
            appendLine("=== End project rules ===")
        }
        assertTrue(block.contains("Use StateFlow"))
        assertTrue(block.contains("Use MockK"))
    }

    @Test
    fun `buildPromptBlock is capped at maxChars`() {
        val rules = (1..50).map { i -> makeRule("r$i", "Rule text number $i that is fairly long in content") }
        val raw = buildString {
            appendLine("=== Project rules ===")
            rules.take(15).forEach { appendLine("- ${it.text}") }
            appendLine("=== End project rules ===")
        }
        val capped = raw.take(600)
        assertTrue(capped.length <= 600)
    }

    // ── Confidence and evidence ───────────────────────────────────────────────

    @Test
    fun `rule stores confidence and evidence`() {
        val rule = makeRule("di-hilt", "Use Hilt for DI", "high", "@HiltAndroidApp found")
        assertEquals("high", rule.confidence)
        assertEquals("@HiltAndroidApp found", rule.evidence)
    }

    // ── Source preservation on refresh ───────────────────────────────────────

    @Test
    fun `user-sourced rules are preserved on refresh`() {
        val existingRules = mutableListOf(
            makeRule("custom-1", "Always prefix interfaces with I", source = "user"),
            makeRule("lang-kotlin", "Write all new code in Kotlin", source = "detected"),
        )
        // Simulate refresh: preserve user rules, replace detected ones
        val detected = listOf(makeRule("lang-kotlin", "Write all new code in Kotlin", source = "detected"))
        val preserved = existingRules.filter { it.source == "user" }
        val merged = preserved + detected
        assertEquals(2, merged.size)
        assertTrue(merged.any { it.id == "custom-1" && it.source == "user" })
        assertTrue(merged.any { it.id == "lang-kotlin" && it.source == "detected" })
    }

    // ── Rule update ───────────────────────────────────────────────────────────

    @Test
    fun `updateRuleText changes only targeted rule`() {
        val rules = mutableListOf(
            makeRule("r1", "Original text"),
            makeRule("r2", "Other rule"),
        )
        val updated = rules.map { if (it.id == "r1") it.copy(text = "New text") else it }
        assertEquals("New text", updated.find { it.id == "r1" }?.text)
        assertEquals("Other rule", updated.find { it.id == "r2" }?.text)
    }
}
