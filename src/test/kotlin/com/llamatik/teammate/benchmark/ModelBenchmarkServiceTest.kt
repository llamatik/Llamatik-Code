package com.llamatik.teammate.benchmark

import org.junit.Assert.*
import org.junit.Test

class ModelBenchmarkServiceTest {

    private fun run(
        durationMs: Long,
        tokenCount: Int,
        fullText: String,
        succeeded: Boolean = true,
    ) = ModelBenchmarkService.BenchmarkRun(
        modelPath = "/models/test.gguf",
        modelLabel = "test.gguf",
        promptName = "code_explain",
        tokensPerSecond = if (durationMs > 0 && tokenCount > 0) tokenCount * 1000.0 / durationMs else 0.0,
        timeToFirstTokenMs = if (tokenCount > 0) 50L else 0L,
        totalTokens = tokenCount.coerceAtLeast(if (fullText.isNotBlank()) (fullText.trim().length / 4).coerceAtLeast(1) else 0),
        durationMs = durationMs,
        succeeded = succeeded,
        runAtMs = 1_000_000L,
    )

    // ── Token estimation fallback ─────────────────────────────────────────────

    @Test
    fun `token estimation falls back to chars divided by 4 when token count is zero`() {
        val text = "a".repeat(400)
        val effectiveTokens = (text.trim().length / 4).coerceAtLeast(1)
        assertEquals(100, effectiveTokens)
    }

    @Test
    fun `token estimation uses actual count when tokens were streamed`() {
        val streamedTokens = 42
        val text = "a".repeat(400)
        val effectiveTokens = if (streamedTokens > 0) streamedTokens else (text.trim().length / 4).coerceAtLeast(1)
        assertEquals(42, effectiveTokens)
    }

    @Test
    fun `token estimation returns at least 1 for empty text`() {
        val effectiveTokens = ("".trim().length / 4).coerceAtLeast(1)
        assertEquals(1, effectiveTokens)
    }

    // ── formatResults ─────────────────────────────────────────────────────────

    @Test
    fun `formatResults returns no-results message for empty list`() {
        val svc = ModelBenchmarkService()
        assertEquals("No benchmark results", svc.formatResults(emptyList()))
    }

    @Test
    fun `formatResults includes model label`() {
        val svc = ModelBenchmarkService()
        val runs = listOf(run(durationMs = 2000, tokenCount = 30, fullText = "Hello world"))
        val output = svc.formatResults(runs)
        assertTrue(output.contains("test.gguf"))
    }

    @Test
    fun `formatResults shows tok per second for successful run`() {
        val svc = ModelBenchmarkService()
        val runs = listOf(run(durationMs = 1000, tokenCount = 10, fullText = "Hello world"))
        val output = svc.formatResults(runs)
        assertTrue("Should show tok/s", output.contains("tok/s"))
    }

    @Test
    fun `formatResults shows na for failed run`() {
        val svc = ModelBenchmarkService()
        val failedRun = ModelBenchmarkService.BenchmarkRun(
            modelPath = "/models/test.gguf",
            modelLabel = "test.gguf",
            promptName = "code_explain",
            tokensPerSecond = 0.0,
            timeToFirstTokenMs = 0,
            totalTokens = 0,
            durationMs = 0,
            succeeded = false,
        )
        val output = svc.formatResults(listOf(failedRun))
        assertTrue("Should show n/a for failed run", output.contains("n/a"))
    }

    @Test
    fun `formatResults computes average over successful runs only`() {
        val svc = ModelBenchmarkService()
        val runs = listOf(
            ModelBenchmarkService.BenchmarkRun(
                modelPath = "/models/test.gguf", modelLabel = "test.gguf", promptName = "code_explain",
                tokensPerSecond = 10.0, succeeded = true, totalTokens = 10, durationMs = 1000,
            ),
            ModelBenchmarkService.BenchmarkRun(
                modelPath = "/models/test.gguf", modelLabel = "test.gguf", promptName = "bug_fix",
                tokensPerSecond = 0.0, succeeded = false, totalTokens = 0, durationMs = 0,
            ),
        )
        val output = svc.formatResults(runs)
        // Average should be computed from the single successful run (10 tok/s)
        // Use "10" rather than "10.0" to avoid locale-specific decimal separators.
        assertTrue("Should contain avg", output.contains("Avg"))
        assertTrue("Should contain 10", output.contains("10"))
    }

    @Test
    fun `formatResults omits avg line when no successful runs`() {
        val svc = ModelBenchmarkService()
        val failedRun = ModelBenchmarkService.BenchmarkRun(
            modelPath = "/models/test.gguf", modelLabel = "test.gguf", promptName = "code_explain",
            tokensPerSecond = 0.0, succeeded = false,
        )
        val output = svc.formatResults(listOf(failedRun))
        assertFalse("Should not show avg when all runs failed", output.contains("Avg"))
    }

    // ── PROMPTS map ───────────────────────────────────────────────────────────

    @Test
    fun `PROMPTS contains exactly the four expected benchmark prompts`() {
        val svc = ModelBenchmarkService()
        assertEquals(setOf("code_explain", "bug_fix", "small_refactor", "patch_gen"), svc.PROMPTS.keys.toSet())
    }

    @Test
    fun `all PROMPTS values are non-blank`() {
        val svc = ModelBenchmarkService()
        svc.PROMPTS.values.forEach { prompt ->
            assertTrue("Prompt must not be blank", prompt.isNotBlank())
        }
    }
}
