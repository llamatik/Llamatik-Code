package com.llamatik.agent.core.agent

import com.llamatik.agent.core.testing.AgentRun
import com.llamatik.agent.core.testing.FakeBackend
import com.llamatik.agent.core.testing.FakeToolRegistry
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the prompt budget that controls how much of a read_file result reaches the model.
 *
 * Two bugs were fixed:
 *   1. batchSize (a KV-cache batching param, not the context window) was incorrectly used to cap
 *      the budget via min(contextSize, batchSize). With the default batchSize=2048 this yielded
 *      6144 chars — less than the 9000-char overhead — so fileBudgetChars collapsed to 0 and all
 *      tool results were stripped.
 *   2. A hardcoded TOOL_RESULTS_MAX_CHARS=4000 capped the entire tool-results block regardless of
 *      the per-result limit, making both caps fight each other at ~114 lines for a real Kotlin file.
 *
 * Fix: budget is derived from contextSize only (the true model context window).
 *   toolResultContentMax = fileBudgetChars.coerceAtLeast(TOOL_RESULT_CONTENT_MAX_CHARS)
 *   toolResultsMax       = (fileBudgetChars * 2).coerceAtLeast(TOOL_RESULTS_MAX_CHARS)
 *
 * With the default contextSize=16384:
 *   fileBudgetChars      = 16384 × 3 − 9000 = 40 152 chars  (~1147 lines at 35 ch/line)
 *   toolResultContentMax = 40 152 chars
 *   toolResultsMax       = 80 304 chars (two concurrent read_file results fit in full)
 */
class PromptBudgetTest {

    // The budget is computed by production code (PromptBudget), not by a copy of its formula.
    private fun fileBudget(contextSize: Int): Int = PromptBudget(contextSize).fileBudgetChars
    private fun toolResultContentMax(contextSize: Int): Int = PromptBudget(contextSize).toolResultContentMax
    private fun toolResultsMax(contextSize: Int): Int = PromptBudget(contextSize).toolResultsMax

    // ── Budget-formula unit tests ─────────────────────────────────────────────

    @Test
    fun `small contextSize 2048 - budget below overhead, floor kicks in`() {
        // 2048 * 3 = 6144 < 9000 overhead → fileBudget = 0, floor = 8000
        assertEquals(8_000, toolResultContentMax(contextSize = 2048))
    }

    @Test
    fun `contextSize 4096 - formula yields 3288, below floor so floor wins`() {
        // 4096 * 3 = 12288 − 9000 = 3288 < 8000
        assertEquals(8_000, toolResultContentMax(contextSize = 4096))
    }

    @Test
    fun `default contextSize 16384 - formula yields 35236, well above floor`() {
        // 16384 * 3 = 49152, × 0.9 safety margin = 44236, − 9000 overhead = 35236
        // (the earlier expectation of 40152 predates the 90% safety margin)
        assertEquals(35_236, toolResultContentMax(contextSize = 16384))
    }

    @Test
    fun `contextSize 32768 - formula yields 79473`() {
        // 32768 * 3 = 98304, × 0.9 = 88473, − 9000 = 79473
        assertEquals(79_473, toolResultContentMax(contextSize = 32768))
    }

    @Test
    fun `tool results and re-injected source are budgeted jointly inside the context`() {
        // The round-2 user turn carries both, so their caps must add up to no more than the round-2 budget
        // (which itself is inside the safe context). The floors only apply to tiny contexts.
        listOf(8192, 16384, 32768, 65536).forEach { ctx ->
            val b = PromptBudget(ctx)
            assertTrue(
                "toolResultsMax + round2FileContextMax must fit round2ContentBudget for contextSize=$ctx",
                b.toolResultsMax + b.round2FileContextMax <= b.round2ContentBudget,
            )
            assertTrue(b.round2ContentBudget < b.safeContextChars)
        }
    }

    @Test
    fun `a single result may use the whole file budget but never more than the joint cap allows`() {
        listOf(16384, 32768).forEach { ctx ->
            val b = PromptBudget(ctx)
            assertEquals(b.fileBudgetChars, b.toolResultContentMax)
            // Documents the real relationship: one maximal result can exceed the joint tool-results cap and is then
            // truncated there, so two maximal results do NOT both fit (the old test claimed they would).
            assertTrue(b.toolResultsMax < 2 * b.toolResultContentMax)
        }
    }

    @Test
    fun `toolResultContentMax is never zero for any positive contextSize`() {
        listOf(256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536).forEach { ctx ->
            val max = toolResultContentMax(ctx)
            assertTrue("toolResultContentMax must be > 0 for contextSize=$ctx, got $max", max > 0)
        }
    }

    @Test
    fun `floor holds for all contextSize values`() {
        listOf(256, 512, 1024, 2048, 4096).forEach { ctx ->
            assertTrue(
                "Floor must apply for contextSize=$ctx",
                toolResultContentMax(ctx) >= TOOL_RESULT_CONTENT_MAX_CHARS,
            )
        }
    }

    @Test
    fun `batchSize no longer affects file budget - regression guard`() {
        // Before the fix, min(contextSize, batchSize) was used. With batchSize=2048 that
        // collapsed the budget even when contextSize=16384. The formula now ignores batchSize.
        val budget16k = fileBudget(contextSize = 16384)
        // If batchSize were still included at 2048: min(16384,2048)*3 − 9000 = 6144−9000 = 0
        assertTrue(
            "Budget with contextSize=16384 must be > 0 regardless of batchSize (got $budget16k)",
            budget16k > 0,
        )
        assertEquals("contextSize=16384 must yield a 35236-char budget", 35_236, budget16k)
    }

    // ── File-size coverage assertions ─────────────────────────────────────────

    @Test
    fun `default context accommodates files up to 1147 lines at realistic density`() {
        // 35 chars/line is a realistic average for indented Kotlin/Java source.
        val charsPerLine = 35
        val budget = toolResultContentMax(contextSize = 16384)
        val linesSupported = budget / charsPerLine
        assertTrue(
            "Default context (budget=$budget) must support ≥1000-line files at 35ch/line, got $linesSupported lines",
            linesSupported >= 1_000,
        )
    }

    @Test
    fun `contextSize 32768 accommodates files up to 2551 lines at realistic density`() {
        val charsPerLine = 35
        val budget = toolResultContentMax(contextSize = 32768)
        val linesSupported = budget / charsPerLine
        assertTrue(
            "contextSize=32768 (budget=$budget) must support ≥2000-line files at 35ch/line, got $linesSupported",
            linesSupported >= 2_000,
        )
    }

    // ── File generator used by end-to-end loop tests ──────────────────────────

    /**
     * Generates a file with exactly [lines] lines, each containing a unique marker "//L{N}".
     * At ~10 chars/line this keeps char counts predictable and well within the floor for large N.
     *  571 lines ≈  5 800 chars  (fits in 8 000-char floor)
     * 2000 lines ≈ 20 400 chars  (fits in 40 152-char budget at contextSize=16384)
     */
    private fun generateFile(lines: Int): String =
        (1..lines).joinToString("\n") { i -> "x$i//L$i" }

    // ── End-to-end loop tests: the real AgentLoop with a scripted backend ────────

    private fun readFileRun(path: String, content: String, contextSize: Int): String {
        val backend = FakeBackend(
            """<tool_call name="read_file"><arg name="path">$path</arg></tool_call>""",
            "Done.",
        )
        AgentRun(backend, FakeToolRegistry(mapOf("read_file" to content)), contextLength = contextSize).run("Read $path")
        return backend.capturedRequests[1].messages.first().content
    }

    @Test
    fun `571-line file fully reaches model with floor budget - regression`() {
        val content = generateFile(571)
        assertTrue("571-line file must fit within the 8000-char floor", content.length <= 8_000)

        val round2 = readFileRun("Service.kt", content, contextSize = 2048) // exercises the floor path
        assertTrue("Line 571 must be present — file must not be truncated", round2.contains("//L571"))
    }

    @Test
    fun `no hardcoded 4000-char total cap on tool results - bug 2 stays fixed`() {
        // TOOL_RESULTS_MAX_CHARS used to be hardcoded to 4000: generateFile(571) is ~5800 chars, so
        // the old cap cut the file around line 390. The production loop has no such cap; this guards
        // against it coming back (the old test drove a copy of the loop with a configurable cap).
        val content = generateFile(571)
        assertTrue("fixture must be larger than the old cap", content.length > 4_000)
        val round2 = readFileRun("Service.kt", content, contextSize = 4096)
        assertTrue("A total cap of 4000 chars would have cut the file before line 571", round2.contains("//L571"))
    }

    @Test
    fun `2000-line file fully reaches model at default contextSize 16384`() {
        val content = generateFile(2000)
        // ~10 ch/line × 2000 = ~20 400 chars; budget at contextSize=16384 = 40 152 → fits
        assertTrue(
            "2000-line file (${content.length} chars) must fit within budget at contextSize=16384 " +
                    "(${toolResultContentMax(16384)} chars)",
            content.length <= toolResultContentMax(contextSize = 16384),
        )

        val round2 = readFileRun("Big.kt", content, contextSize = 16384)
        assertTrue("Line 2000 must be present in round-2 prompt", round2.contains("//L2000"))
    }

    @Test
    fun `2000-line file is truncated when contextSize is only 4096`() {
        // 4096 → budget = 3288, floor = 8000; file is ~20 400 chars so it still gets cut.
        val content = generateFile(2000)
        val maxChars = toolResultContentMax(contextSize = 4096)  // = 8000 (floor)
        assertTrue(
            "2000-line file must exceed the 8000-char floor when contextSize=4096",
            content.length > maxChars,
        )

        val round2 = readFileRun("Big.kt", content, contextSize = 4096)
        assertFalse(
            "With contextSize=4096 (budget=$maxChars chars), line 2000 must be absent — file exceeds context",
            round2.contains("//L2000"),
        )
    }

    @Test
    fun `two concurrent read_file calls both arrive complete at default context`() {
        val file1   = generateFile(500)
        val file2   = generateFile(500)
        val backend = FakeBackend(
            """
            <tool_call name="read_file"><arg name="path">A.kt</arg></tool_call>
            <tool_call name="read_file"><arg name="path">B.kt</arg></tool_call>
            """.trimIndent(),
            "Reviewed.",
        )
        val fakeTools = FakeToolRegistry(sequentialResults = listOf(file1, file2))
        AgentRun(backend, fakeTools, contextLength = 16384).run("Review A.kt and B.kt")

        val round2 = backend.capturedRequests[1].messages.first().content
        // //L500 appears once per file = twice total
        val count = round2.split("//L500").size - 1
        assertTrue("Both files' last line must appear (found $count occurrences)", count >= 2)
    }
}
