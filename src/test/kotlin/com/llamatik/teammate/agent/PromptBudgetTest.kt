package com.llamatik.teammate.agent

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the prompt budget calculation that determines how many characters of a
 * read_file result are forwarded to the model in the next round.
 *
 * Bug history: with batchSize=2048 the fileBudgetChars formula yields a negative number
 * (overhead 9000 > effectiveBatchChars 6144), which coerceAtLeast(0) clips to zero.
 * With batchSize=4096 it yields ~3 288 chars, which cuts a 571-line Kotlin file at ~line 299.
 *
 * Fix: toolResultContentMax is floored at TOOL_RESULT_CONTENT_MAX_CHARS (8 000) so that
 * read_file results are never stripped regardless of the batch-size setting.
 */
class PromptBudgetTest {

    // ── Mirrors the production formula in AgentOrchestrator.submit() ──────────

    private val SYSTEM_PROMPT_BASE_CHARS   = 3_000
    private val AGENT_HISTORY_BUDGET_CHARS = 4_000
    private val USER_INSTRUCTION_MAX_CHARS = 800
    private val SAFETY_MARGIN              = 1_200
    private val nonFileOverheadChars       = SYSTEM_PROMPT_BASE_CHARS +
                                             AGENT_HISTORY_BUDGET_CHARS +
                                             USER_INSTRUCTION_MAX_CHARS +
                                             SAFETY_MARGIN              // = 9 000

    private val TOOL_RESULT_CONTENT_MAX_CHARS = 8_000   // floor / minimum guarantee

    /** Replicates the exact expression from AgentOrchestrator. */
    private fun computeToolResultContentMax(batchSize: Int, contextSize: Int): Int {
        val effectiveBatchChars = minOf(contextSize, batchSize) * 3
        val fileBudgetChars     = (effectiveBatchChars - nonFileOverheadChars).coerceAtLeast(0)
        return fileBudgetChars.coerceAtLeast(TOOL_RESULT_CONTENT_MAX_CHARS)
    }

    // ── Budget-formula unit tests ─────────────────────────────────────────────

    @Test
    fun `default batchSize 2048 - overhead exceeds budget so floor kicks in`() {
        // effectiveBatchChars = 2048 * 3 = 6144; 6144 - 9000 = -2856 → 0 without floor
        val max = computeToolResultContentMax(batchSize = 2048, contextSize = 16384)
        assertEquals(
            "With batchSize=2048 the floor must apply: expected $TOOL_RESULT_CONTENT_MAX_CHARS",
            TOOL_RESULT_CONTENT_MAX_CHARS, max,
        )
    }

    @Test
    fun `batchSize 4096 - small budget produced by formula is overridden by floor`() {
        // effectiveBatchChars = 4096 * 3 = 12288; 12288 - 9000 = 3288
        // 3288 < 8000, so floor must win
        val max = computeToolResultContentMax(batchSize = 4096, contextSize = 16384)
        assertEquals(
            "With batchSize=4096 formula yields 3288 which is below the 8000 floor",
            TOOL_RESULT_CONTENT_MAX_CHARS, max,
        )
    }

    @Test
    fun `batchSize 4096 floor prevents truncation of 571-line file - regression`() {
        // At ~11 chars/line the reported 571-line file is ~6281 chars.
        // Before the fix: 3288 chars → truncates at ~line 299.
        // After the fix:  8000 chars → full file fits.
        val maxChars = computeToolResultContentMax(batchSize = 4096, contextSize = 16384)
        val approximateFileSize = 571 * 11          // conservative average chars per line
        assertTrue(
            "Floor ($maxChars) must accommodate a 571-line file (~$approximateFileSize chars)",
            maxChars >= approximateFileSize,
        )
    }

    @Test
    fun `batchSize 8192 - generous budget is used as-is when above floor`() {
        // effectiveBatchChars = 8192 * 3 = 24576; 24576 - 9000 = 15576 > 8000 → no clamping
        val max = computeToolResultContentMax(batchSize = 8192, contextSize = 16384)
        assertEquals("With batchSize=8192 the formula result 15576 should be used directly", 15576, max)
    }

    @Test
    fun `toolResultContentMax is never zero for any positive batchSize`() {
        listOf(256, 512, 1024, 2048, 4096, 8192).forEach { bs ->
            val max = computeToolResultContentMax(batchSize = bs, contextSize = 16384)
            assertTrue("toolResultContentMax must be > 0 for batchSize=$bs, got $max", max > 0)
        }
    }

    @Test
    fun `floor guarantee holds regardless of contextSize setting`() {
        listOf(2048, 4096, 8192, 16384, 32768).forEach { ctx ->
            val max = computeToolResultContentMax(batchSize = 2048, contextSize = ctx)
            assertTrue(
                "Floor $TOOL_RESULT_CONTENT_MAX_CHARS must always apply when batchSize is small (contextSize=$ctx), got $max",
                max >= TOOL_RESULT_CONTENT_MAX_CHARS,
            )
        }
    }

    // ── End-to-end: tool result forwarding via AgentLoop ─────────────────────

    /**
     * Generates a mock file with exactly [lines] lines.
     * Format "x$i//L$i" is ~10 chars per line so 571 lines ≈ 5 700 chars, safely under the 8 000-char floor.
     * Line N contains the unique marker "//L{N}" so tests can check exact line presence.
     */
    private fun generateKotlinFile(lines: Int): String =
        (1..lines).joinToString("\n") { i -> "x$i//L$i" }

    /**
     * Returns the char offset where line [lineNumber] ends in a file generated by
     * [generateKotlinFile], so tests can verify precise truncation boundaries.
     */
    private fun charOffsetAtLine(lineNumber: Int): Int =
        generateKotlinFile(lineNumber).length

    @Test
    fun `571-line file is fully forwarded to model in round 2 - regression`() {
        val fileContent = generateKotlinFile(571)
        // ~11 chars/line × 571 = ~6281 chars — fits within the 8000-char floor
        assertTrue(
            "Generated 571-line file must fit within the 8000-char floor",
            fileContent.length <= 8_000,
        )

        val backend = AgentLoopTest.FakeBackend(
            """<tool_call name="read_file"><arg name="path">Service.kt</arg></tool_call>""",
            "I have read the file.",
        )
        val fakeTools = AgentLoopTest.FakeToolRegistry(mapOf("read_file" to fileContent))
        // toolResultContentMaxChars = 8000 = the floor the fix guarantees
        val loop = AgentLoopTest.AgentLoop(
            backend, fakeTools,
            toolResultsMaxChars    = 100_000,
            toolResultContentMaxChars = 8_000,
        )
        loop.run("Analyse Service.kt")

        val round2Content = backend.capturedRequests[1].messages.first().content
        assertTrue(
            "Line 571 must appear in round-2 prompt — file must not be truncated",
            round2Content.contains("//L571"),
        )
    }

    @Test
    fun `with old batchSize 4096 formula without floor file is truncated before line 571 - documents the bug`() {
        // The old formula yields 3288 chars for batchSize=4096.
        // With ~10 chars/line, 3288 chars covers ~319 lines — line 571 is missing.
        val oldToolResultContentMax = 3_288          // what batchSize=4096 yielded before the fix
        val fileContent = generateKotlinFile(571)

        val linesVisibleUnderOldMax = fileContent.take(oldToolResultContentMax).lines().size
        assertTrue(
            "Old 3288-char limit must cut the file before line 571 (visible lines: $linesVisibleUnderOldMax)",
            linesVisibleUnderOldMax < 571,
        )

        val backend = AgentLoopTest.FakeBackend(
            """<tool_call name="read_file"><arg name="path">Service.kt</arg></tool_call>""",
            "I have read the file.",
        )
        val fakeTools = AgentLoopTest.FakeToolRegistry(mapOf("read_file" to fileContent))
        val loop = AgentLoopTest.AgentLoop(
            backend, fakeTools,
            toolResultsMaxChars    = 100_000,
            toolResultContentMaxChars = oldToolResultContentMax,
        )
        loop.run("Analyse Service.kt")

        val round2Content = backend.capturedRequests[1].messages.first().content
        assertFalse(
            "Without the floor fix, line 571 must be absent from the round-2 prompt",
            round2Content.contains("//L571"),
        )
    }

    @Test
    fun `with the fix applied floor of 8000 chars 571-line file is fully readable`() {
        val fileContent = generateKotlinFile(571)
        // Verify the file actually fits within the floor value
        assertTrue(
            "571-line file (${fileContent.length} chars) must fit within 8000-char floor",
            fileContent.length <= 8_000,
        )

        val backend = AgentLoopTest.FakeBackend(
            """<tool_call name="read_file"><arg name="path">Service.kt</arg></tool_call>""",
            "I have read the file.",
        )
        val fakeTools = AgentLoopTest.FakeToolRegistry(mapOf("read_file" to fileContent))
        val fixedMax = computeToolResultContentMax(batchSize = 2048, contextSize = 16384)
        assertEquals("Fix must yield the 8000-char floor for batchSize=2048", 8_000, fixedMax)

        val loop = AgentLoopTest.AgentLoop(
            backend, fakeTools,
            toolResultsMaxChars    = 100_000,
            toolResultContentMaxChars = fixedMax,
        )
        loop.run("Analyse Service.kt")

        val round2Content = backend.capturedRequests[1].messages.first().content
        assertTrue(
            "With fix applied, line 571 must be present in round-2 prompt",
            round2Content.contains("//L571"),
        )
    }

    @Test
    fun `multiple read_file calls in one round each respect the floor independently`() {
        // Two 200-line files — each fits within the 8000-char floor individually
        val file1 = generateKotlinFile(200)
        val file2 = generateKotlinFile(200)
        val backend = AgentLoopTest.FakeBackend(
            """
            <tool_call name="read_file"><arg name="path">A.kt</arg></tool_call>
            <tool_call name="read_file"><arg name="path">B.kt</arg></tool_call>
            """.trimIndent(),
            "Reviewed both files.",
        )
        val fakeTools = AgentLoopTest.FakeToolRegistry(
            sequentialResults = listOf(file1, file2),
        )
        val loop = AgentLoopTest.AgentLoop(
            backend, fakeTools,
            toolResultsMaxChars    = 100_000,
            toolResultContentMaxChars = 8_000,
        )
        loop.run("Review A.kt and B.kt")

        val round2Content = backend.capturedRequests[1].messages.first().content
        assertTrue("Last line of first file must be present",  round2Content.contains("//L200"))
        val occurrences = round2Content.split("//L200").size - 1
        assertTrue("Both files' last line marker must appear (found $occurrences times)", occurrences >= 2)
    }
}
