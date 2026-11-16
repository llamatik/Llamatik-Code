package com.llamatik.agent.core.agent

import com.llamatik.agent.core.host.AgentEnvironment
import com.llamatik.agent.core.host.AutoDeny
import com.llamatik.agent.core.model.ChatMessage
import com.llamatik.agent.core.model.ChatRole
import com.llamatik.agent.core.testing.InMemoryFiles
import com.llamatik.agent.core.testing.NoGit
import com.llamatik.agent.core.testing.NoShell
import org.junit.Assert.assertEquals
import org.junit.Test

class ProposalExtractorTest {
    private val extractor = ProposalExtractor(
        AgentEnvironment(InMemoryFiles(), NoShell, NoGit, AutoDeny),
        ToolPolicy.DEFAULT_EDIT_TOOLS,
    )

    private fun tool(name: String, content: String) = ChatMessage(role = ChatRole.TOOL, content = content, toolName = name)

    @Test
    fun `an edit keeps the final newline of the file it replaces`() {
        val result = extractor.buildProposalsFromToolMessages(listOf(
            tool("write_file", "Prepared file replacement for A.kt\n---OLD---\nclass A\n---NEW---\nclass B"),
        ))
        // Tool arguments arrive trimmed; the old file ended with "\n" so the new one must too.
        assertEquals("class B", result.proposals.single().newContent.trimEnd())
        assertEquals("class A", result.proposals.single().oldContent.trimEnd())
    }

    @Test
    fun `a file without a final newline stays without one, a new file gets one`() {
        val replaced = extractor.buildProposalsFromToolMessages(listOf(
            tool("write_file", "Prepared file replacement for A.kt\n---OLD---\nclass A\n---NEW---\nclass B"),
        )).proposals.single()
        // old content here is "class A" (no final newline), so the replacement gets none.
        assertEquals("class B", replaced.newContent)

        val created = extractor.buildProposalsFromToolMessages(listOf(
            tool("create_file", "Prepared new file for N.kt\nclass N"),
        )).proposals.single()
        assertEquals("class N\n", created.newContent)
    }

    @Test
    fun `old content with a final newline is kept and the new content matches it`() {
        val p = extractor.buildProposalsFromToolMessages(listOf(
            tool("write_file", "Prepared file replacement for A.kt\n---OLD---\nclass A\n\n---NEW---\nclass B"),
        )).proposals.single()
        assertEquals("class A\n", p.oldContent)
        assertEquals("class B\n", p.newContent)
    }
}
