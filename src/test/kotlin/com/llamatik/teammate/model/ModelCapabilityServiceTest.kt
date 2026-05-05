package com.llamatik.teammate.model

import org.junit.Assert.*
import org.junit.Test

class ModelCapabilityServiceTest {

    private val service = ModelCapabilityService()

    // ── estimateComplexity ────────────────────────────────────────────────────

    @Test
    fun `simple prompt is SIMPLE`() {
        val c = service.estimateComplexity("Fix the null pointer in MainActivity")
        assertEquals(ModelCapabilityService.TaskComplexity.SIMPLE, c)
    }

    @Test
    fun `prompt with review is MODERATE`() {
        val c = service.estimateComplexity("Review the login flow")
        assertEquals(ModelCapabilityService.TaskComplexity.MODERATE, c)
    }

    @Test
    fun `prompt with refactor is COMPLEX or higher`() {
        val c = service.estimateComplexity("Refactor the payment module")
        assertTrue("refactor should be at least COMPLEX", c >= ModelCapabilityService.TaskComplexity.COMPLEX)
    }

    @Test
    fun `prompt with architecture is VERY_COMPLEX`() {
        val c = service.estimateComplexity("Redesign the architecture and modularize by feature")
        assertEquals(ModelCapabilityService.TaskComplexity.VERY_COMPLEX, c)
    }

    @Test
    fun `multiple files with deep analysis is VERY_COMPLEX`() {
        val c = service.estimateComplexity("Analyze all files and plan a migration", fileCount = 5)
        assertEquals(ModelCapabilityService.TaskComplexity.VERY_COMPLEX, c)
    }

    @Test
    fun `long prompt exceeding 60 words is MODERATE`() {
        val longPrompt = List(65) { "word" }.joinToString(" ")
        val c = service.estimateComplexity(longPrompt)
        assertEquals(ModelCapabilityService.TaskComplexity.MODERATE, c)
    }

    @Test
    fun `multiple files is at least COMPLEX`() {
        val c = service.estimateComplexity("Update the service layer", fileCount = 2)
        assertTrue("Multiple files should be at least COMPLEX", c >= ModelCapabilityService.TaskComplexity.COMPLEX)
    }

    // ── getHintAfterPoorResult ────────────────────────────────────────────────

    @Test
    fun `hint after poor result from large model is null`() {
        val hint = service.getHintAfterPoorResult("qwen3-14b-q4_k_m")
        assertNull("Large model should not get a poor-result hint", hint)
    }

    @Test
    fun `hint after poor result from small model is WARNING`() {
        val hint = service.getHintAfterPoorResult("qwen2.5-1b-instruct")
        assertNotNull("Small model should get a poor-result hint", hint)
        assertEquals(ModelCapabilityService.CapabilityHint.Level.WARNING, hint!!.level)
    }

    @Test
    fun `hint after poor result from mini model is WARNING`() {
        val hint = service.getHintAfterPoorResult("gemma-mini-it")
        assertNotNull("Mini model should get a hint", hint)
    }

    // ── getHintForContextTruncation ───────────────────────────────────────────

    @Test
    fun `context truncation hint is WARNING level`() {
        val hint = service.getHintForContextTruncation()
        assertEquals(ModelCapabilityService.CapabilityHint.Level.WARNING, hint.level)
    }

    @Test
    fun `context truncation hint message mentions truncated`() {
        val hint = service.getHintForContextTruncation()
        assertTrue(hint.message.contains("truncated", ignoreCase = true))
    }
}
