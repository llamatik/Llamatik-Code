package com.llamatik.agent.core.agent

import com.llamatik.agent.core.testing.AgentScenarios
import com.llamatik.agent.core.testing.agentLoopRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Golden transcripts of the agent loop: every request sent to the model (system prompt, messages,
 * token caps, prefill), every streamed token, tool call, trace step and the final session history.
 *
 * The goldens were recorded from the original IntelliJ AgentOrchestrator before it was extracted
 * (see docs/agent-core-extraction.md, Phase 3). Local models are sensitive to the exact prompt text,
 * so any diff here is a behaviour change: review it deliberately, never just re-record it.
 */
class AgentLoopGoldenTest {
    private val runner = agentLoopRunner(ToolPolicy.PORTABLE)

    private fun golden(name: String): String {
        val stream = javaClass.getResourceAsStream("/golden/$name.txt")
        assertNotNull("missing golden file golden/$name.txt", stream)
        return stream!!.bufferedReader().readText().removeSuffix("\n")
    }

    @Test
    fun `every scenario reproduces its golden transcript`() {
        val failures = AgentScenarios.all.mapNotNull { s ->
            val actual = runner.transcript(s)
            val expected = golden(s.name)
            if (actual == expected) null else s.name
        }
        assertEquals("scenarios whose transcript changed", emptyList<String>(), failures)
    }

    @Test
    fun `a single scenario reports a readable diff`() {
        // Same assertion as above for the most prompt-heavy scenario, so a failure shows the text.
        val s = AgentScenarios.all.first { it.name == "system-prompt-extras" }
        assertEquals(golden(s.name), runner.transcript(s))
    }

    @Test
    fun `every golden file belongs to a scenario`() {
        val names = AgentScenarios.all.map { it.name }
        assertEquals("duplicate scenario names", names.size, names.toSet().size)
    }
}
