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
        hasFileContext: Boolean = false,
    ) = RoundDirective.RoundState(
        round = round,
        maxRounds = maxRounds,
        hasReadContent = hasReadContent,
        wroteFilesThisRound = wroteFilesThisRound,
        validProposalsProduced = validProposalsProduced,
        allToolsWereReadOnly = allToolsWereReadOnly,
        hasFileContext = hasFileContext,
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
    fun `has-read-content early in rounds with no file context encourages more reading`() {
        val directive = RoundDirective.get(state(
            round = 2,
            maxRounds = 5,
            hasReadContent = true,
            hasFileContext = false,
        ))
        assertTrue("Should encourage reading more files", directive.contains("continue reading", ignoreCase = true))
    }

    @Test
    fun `has-read-content nearing end of rounds stops reading and demands output`() {
        val directive = RoundDirective.get(state(
            round = 3,
            maxRounds = 5,
            hasReadContent = true,
            hasFileContext = false,
        ))
        assertTrue("Should stop reading", directive.contains("Do NOT", ignoreCase = true))
        assertTrue("Should allow write or text answer", directive.contains("write_file", ignoreCase = true) || directive.contains("plain-text answer", ignoreCase = true))
    }

    @Test
    fun `has-read-content with specific file attached stops reading immediately`() {
        val directive = RoundDirective.get(state(
            round = 2,
            maxRounds = 5,
            hasReadContent = true,
            hasFileContext = true,
        ))
        assertTrue("Should stop reading when focused on one file", directive.contains("Do NOT", ignoreCase = true))
    }

    @Test
    fun `has-read-content directive also allows text answer for review tasks`() {
        val directive = RoundDirective.get(state(
            round = 4,
            maxRounds = 5,
            hasReadContent = true,
        ))
        assertTrue("Should mention plain-text answer option", directive.contains("plain-text answer", ignoreCase = true))
    }

    @Test
    fun `no reads yet with file context tells model to read source file`() {
        val directive = RoundDirective.get(state(
            hasReadContent = false,
            allToolsWereReadOnly = true,
            hasFileContext = true,
        ))
        assertTrue("Should mention read_file", directive.contains("read_file", ignoreCase = true))
        assertTrue("Should mention source file", directive.contains("source file", ignoreCase = true))
    }

    @Test
    fun `no reads yet without file context tells model to explore project`() {
        val directive = RoundDirective.get(state(
            hasReadContent = false,
            allToolsWereReadOnly = true,
            hasFileContext = false,
        ))
        assertTrue("Should mention list_files", directive.contains("list_files", ignoreCase = true))
        assertTrue("Should mention read_file", directive.contains("read_file", ignoreCase = true))
    }

    @Test
    fun `default directive proceeds with task`() {
        val directive = RoundDirective.get(state(
            hasReadContent = false,
            wroteFilesThisRound = false,
            allToolsWereReadOnly = false,
        ))
        assertTrue("Should mention write_file or create_file", directive.contains("write_file", ignoreCase = true) || directive.contains("create_file", ignoreCase = true))
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
}
