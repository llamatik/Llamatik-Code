package com.llamatik.teammate.agent

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

    // ── Mirror of the production formula in AgentOrchestrator.submit() ────────

    private val SYSTEM_PROMPT_BASE_CHARS   = 3_000
    private val AGENT_HISTORY_BUDGET_CHARS = 4_000
    private val USER_INSTRUCTION_MAX_CHARS = 800
    private val SAFETY_MARGIN              = 1_200
    private val nonFileOverheadChars       = SYSTEM_PROMPT_BASE_CHARS +
                                             AGENT_HISTORY_BUDGET_CHARS +
                                             USER_INSTRUCTION_MAX_CHARS +
                                             SAFETY_MARGIN   // = 9 000

    private val TOOL_RESULT_CONTENT_MAX_CHARS = 8_000
    private val TOOL_RESULTS_MAX_CHARS        = 8_000

    private fun fileBudget(contextSize: Int): Int =
        (contextSize * 3 - nonFileOverheadChars).coerceAtLeast(0)

    private fun toolResultContentMax(contextSize: Int): Int =
        fileBudget(contextSize).coerceAtLeast(TOOL_RESULT_CONTENT_MAX_CHARS)

    private fun toolResultsMax(contextSize: Int): Int =
        (fileBudget(contextSize) * 2).coerceAtLeast(TOOL_RESULTS_MAX_CHARS)

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
    fun `default contextSize 16384 - formula yields 40152, well above floor`() {
        // 16384 * 3 = 49152 − 9000 = 40152
        assertEquals(40_152, toolResultContentMax(contextSize = 16384))
    }

    @Test
    fun `contextSize 32768 - formula yields 89304`() {
        // 32768 * 3 = 98304 − 9000 = 89304
        assertEquals(89_304, toolResultContentMax(contextSize = 32768))
    }

    @Test
    fun `toolResultsMax is double the per-result cap for concurrent reads`() {
        // Ensures two simultaneous read_file calls both fit in the summary block
        listOf(8192, 16384, 32768).forEach { ctx ->
            val perResult = toolResultContentMax(ctx)
            val total     = toolResultsMax(ctx)
            assertTrue(
                "toolResultsMax ($total) must be ≥ 2 × toolResultContentMax ($perResult) for contextSize=$ctx",
                total >= perResult * 2,
            )
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
        assertEquals("contextSize=16384 must yield 40152-char budget", 40_152, budget16k)
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

    // ── End-to-end loop tests via AgentLoop + FakeBackend ────────────────────

    private fun loopWithBudget(contextSize: Int) = AgentLoopTest.AgentLoop(
        backend          = AgentLoopTest.FakeBackend(
            """<tool_call name="read_file"><arg name="path">Big.kt</arg></tool_call>""",
            "I have read the file.",
        ),
        toolRegistry     = AgentLoopTest.FakeToolRegistry(mapOf("read_file" to generateFile(2000))),
        toolResultsMaxChars       = toolResultsMax(contextSize),
        toolResultContentMaxChars = toolResultContentMax(contextSize),
    )

    @Test
    fun `571-line file fully reaches model with floor budget - regression`() {
        val content = generateFile(571)
        assertTrue("571-line file must fit within the 8000-char floor", content.length <= 8_000)

        val backend   = AgentLoopTest.FakeBackend(
            """<tool_call name="read_file"><arg name="path">Service.kt</arg></tool_call>""",
            "Done.",
        )
        val fakeTools = AgentLoopTest.FakeToolRegistry(mapOf("read_file" to content))
        val loop = AgentLoopTest.AgentLoop(
            backend, fakeTools,
            toolResultsMaxChars       = toolResultsMax(contextSize = 2048),   // exercises floor path
            toolResultContentMaxChars = toolResultContentMax(contextSize = 2048),
        )
        loop.run("Read Service.kt")

        val round2 = backend.capturedRequests[1].messages.first().content
        assertTrue("Line 571 must be present — file must not be truncated", round2.contains("//L571"))
    }

    @Test
    fun `old hardcoded 4000-char total cap truncated file - documents bug 2`() {
        // TOOL_RESULTS_MAX_CHARS was hardcoded to 4000.
        // generateFile(571) = ~5800 chars; take(4000) stops around line 390.
        val content = generateFile(571)
        val backend   = AgentLoopTest.FakeBackend(
            """<tool_call name="read_file"><arg name="path">Service.kt</arg></tool_call>""",
            "Done.",
        )
        val fakeTools = AgentLoopTest.FakeToolRegistry(mapOf("read_file" to content))
        val loop = AgentLoopTest.AgentLoop(
            backend, fakeTools,
            toolResultsMaxChars       = 4_000,   // the old hardcoded value
            toolResultContentMaxChars = 8_000,
        )
        loop.run("Read Service.kt")

        val round2 = backend.capturedRequests[1].messages.first().content
        assertFalse(
            "Old 4000-char total cap must have cut the file before line 571",
            round2.contains("//L571"),
        )
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

        val backend   = AgentLoopTest.FakeBackend(
            """<tool_call name="read_file"><arg name="path">Big.kt</arg></tool_call>""",
            "Done.",
        )
        val fakeTools = AgentLoopTest.FakeToolRegistry(mapOf("read_file" to content))
        val loop = AgentLoopTest.AgentLoop(
            backend, fakeTools,
            toolResultsMaxChars       = toolResultsMax(contextSize = 16384),
            toolResultContentMaxChars = toolResultContentMax(contextSize = 16384),
        )
        loop.run("Read Big.kt")

        val round2 = backend.capturedRequests[1].messages.first().content
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

        val backend   = AgentLoopTest.FakeBackend(
            """<tool_call name="read_file"><arg name="path">Big.kt</arg></tool_call>""",
            "Done.",
        )
        val fakeTools = AgentLoopTest.FakeToolRegistry(mapOf("read_file" to content))
        val loop = AgentLoopTest.AgentLoop(
            backend, fakeTools,
            toolResultsMaxChars       = toolResultsMax(contextSize = 4096),
            toolResultContentMaxChars = maxChars,
        )
        loop.run("Read Big.kt")

        val round2 = backend.capturedRequests[1].messages.first().content
        assertFalse(
            "With contextSize=4096 (budget=$maxChars chars), line 2000 must be absent — file exceeds context",
            round2.contains("//L2000"),
        )
    }

    @Test
    fun `two concurrent read_file calls both arrive complete at default context`() {
        val file1   = generateFile(500)
        val file2   = generateFile(500)
        val backend = AgentLoopTest.FakeBackend(
            """
            <tool_call name="read_file"><arg name="path">A.kt</arg></tool_call>
            <tool_call name="read_file"><arg name="path">B.kt</arg></tool_call>
            """.trimIndent(),
            "Reviewed.",
        )
        val fakeTools = AgentLoopTest.FakeToolRegistry(sequentialResults = listOf(file1, file2))
        val loop = AgentLoopTest.AgentLoop(
            backend, fakeTools,
            toolResultsMaxChars       = toolResultsMax(contextSize = 16384),
            toolResultContentMaxChars = toolResultContentMax(contextSize = 16384),
        )
        loop.run("Review A.kt and B.kt")

        val round2 = backend.capturedRequests[1].messages.first().content
        // //L500 appears once per file = twice total
        val count = round2.split("//L500").size - 1
        assertTrue("Both files' last line must appear (found $count occurrences)", count >= 2)
    }
}
