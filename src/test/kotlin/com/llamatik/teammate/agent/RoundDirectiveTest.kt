package com.llamatik.teammate.agent

import org.junit.Assert.*
import org.junit.Test

class RoundDirectiveTest {

    private fun state(
        round: Int = 2,
        maxRounds: Int = 5,
        hasReadContent: Boolean = false,
        wroteFilesThisRound: Boolean = false,
        validProposalsProduced: Boolean = false,
        allToolsWereReadOnly: Boolean = true,
        readFilePath: String? = null,
    ) = RoundDirective.RoundState(
        round = round,
        maxRounds = maxRounds,
        hasReadContent = hasReadContent,
        wroteFilesThisRound = wroteFilesThisRound,
        validProposalsProduced = validProposalsProduced,
        allToolsWereReadOnly = allToolsWereReadOnly,
        readFilePath = readFilePath,
    )

    @Test
    fun `final round directive demands write now`() {
        val directive = RoundDirective.get(state(round = 4, maxRounds = 5))
        assertTrue("Should mention FINAL ROUND", directive.contains("FINAL ROUND", ignoreCase = true))
    }

    @Test
    fun `rejected write directive tells model its write was bad`() {
        val directive = RoundDirective.get(state(
            wroteFilesThisRound = true,
            validProposalsProduced = false,
        ))
        assertTrue("Should mention rejection", directive.contains("rejected", ignoreCase = true))
        assertTrue("Should demand real code", directive.contains("COMPLETE", ignoreCase = true))
    }

    @Test
    fun `rejection reason is echoed back to model`() {
        val directive = RoundDirective.get(state(
            wroteFilesThisRound = true,
            validProposalsProduced = false,
        ).copy(rejectionReasons = listOf("only 1 non-blank line(s) — likely a placeholder stub")))
        assertTrue("Rejection reason should appear in directive", directive.contains("non-blank line"))
    }

    @Test
    fun `has-read-content directive demands write without reading again`() {
        val directive = RoundDirective.get(state(hasReadContent = true))
        assertTrue("Should demand write now", directive.contains("write_file", ignoreCase = true) || directive.contains("create_file", ignoreCase = true))
        assertTrue("Should forbid further reads", directive.contains("Do NOT", ignoreCase = true))
    }

    @Test
    fun `has-read-content with readFilePath includes concrete test path`() {
        val directive = RoundDirective.get(state(
            hasReadContent = true,
            readFilePath = "shared/src/commonMain/kotlin/com/foo/Bar.kt",
        ))
        assertTrue("Should include derived test path", directive.contains("BarTest.kt"))
        assertTrue("Should use commonTest directory", directive.contains("commonTest"))
    }

    @Test
    fun `no reads yet directive tells model to read first`() {
        val directive = RoundDirective.get(state(
            hasReadContent = false,
            allToolsWereReadOnly = true,
        ))
        assertTrue("Should mention read_file", directive.contains("read_file", ignoreCase = true))
    }

    @Test
    fun `default directive demands complete write`() {
        val directive = RoundDirective.get(state(
            hasReadContent = false,
            wroteFilesThisRound = false,
            allToolsWereReadOnly = false,
        ))
        assertTrue("Should demand write", directive.contains("write_file", ignoreCase = true) || directive.contains("create_file", ignoreCase = true))
    }

    @Test
    fun `final round takes priority over rejected-write`() {
        val directive = RoundDirective.get(state(
            round = 4, maxRounds = 5,
            wroteFilesThisRound = true,
            validProposalsProduced = false,
        ))
        assertTrue("Final round should take priority", directive.contains("FINAL ROUND", ignoreCase = true))
    }

    @Test
    fun `rejected write takes priority over has-read-content`() {
        val directive = RoundDirective.get(state(
            round = 2, maxRounds = 5,
            hasReadContent = true,
            wroteFilesThisRound = true,
            validProposalsProduced = false,
        ))
        assertTrue("Rejected-write should take priority", directive.contains("rejected", ignoreCase = true))
    }

    // ── deriveTestPath via directive ─────────────────────────────────────────

    @Test
    fun `commonMain source maps to commonTest`() {
        val directive = RoundDirective.get(state(
            hasReadContent = true,
            readFilePath = "shared/src/commonMain/kotlin/com/app/Repo.kt",
        ))
        assertTrue(directive.contains("commonTest"))
        assertTrue(directive.contains("RepoTest.kt"))
    }

    @Test
    fun `main-kotlin source maps to test-kotlin`() {
        val directive = RoundDirective.get(state(
            hasReadContent = true,
            readFilePath = "app/src/main/kotlin/com/app/Foo.kt",
        ))
        assertTrue(directive.contains("test/kotlin"))
        assertTrue(directive.contains("FooTest.kt"))
    }

    @Test
    fun `file already named FooTest kt is not double-suffixed`() {
        val directive = RoundDirective.get(state(
            hasReadContent = true,
            readFilePath = "shared/src/commonMain/kotlin/com/app/FooTest.kt",
        ))
        assertFalse("Must not double-suffix", directive.contains("FooTestTest.kt"))
        assertTrue(directive.contains("FooTest.kt"))
    }
}
