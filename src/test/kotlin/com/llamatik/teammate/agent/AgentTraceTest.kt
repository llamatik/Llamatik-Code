package com.llamatik.teammate.agent

import org.junit.Assert.*
import org.junit.Test

class AgentTraceTest {

    @Test
    fun `empty trace reports isEmpty true`() {
        val trace = AgentTrace()
        assertTrue(trace.isEmpty())
    }

    @Test
    fun `trace with step is not empty`() {
        val trace = AgentTrace()
        trace.add(AgentTraceStep(type = AgentTraceStep.Type.TOOL_CALL, title = "search_code(auth)"))
        assertFalse(trace.isEmpty())
    }

    @Test
    fun `formatted output contains step title`() {
        val trace = AgentTrace()
        trace.add(AgentTraceStep(type = AgentTraceStep.Type.TOOL_CALL, title = "read_file(Main.kt)"))
        val text = trace.formatted()
        assertTrue("Formatted output should contain title", text.contains("read_file(Main.kt)"))
    }

    @Test
    fun `formatted output contains type label`() {
        val trace = AgentTrace()
        trace.add(AgentTraceStep(type = AgentTraceStep.Type.SEARCH, title = "smart_search(login)"))
        val text = trace.formatted()
        assertTrue("Formatted output should contain type label 'search'", text.contains("search", ignoreCase = true))
    }

    @Test
    fun `SUCCESS step has checkmark in formatted output`() {
        val trace = AgentTrace()
        trace.add(AgentTraceStep(type = AgentTraceStep.Type.FILE_READ, title = "read_file(Foo.kt)", status = AgentTraceStep.Status.SUCCESS))
        val text = trace.formatted()
        assertTrue("Should show success checkmark", text.contains("✓"))
    }

    @Test
    fun `FAILED step has cross in formatted output`() {
        val trace = AgentTrace()
        trace.add(AgentTraceStep(type = AgentTraceStep.Type.SEARCH, title = "search_code(missing)", status = AgentTraceStep.Status.FAILED))
        val text = trace.formatted()
        assertTrue("Should show failure cross", text.contains("✗"))
    }

    @Test
    fun `WARNING step has warning icon`() {
        val trace = AgentTrace()
        trace.add(AgentTraceStep(type = AgentTraceStep.Type.TOOL_CALL, title = "list_files(.)", status = AgentTraceStep.Status.WARNING))
        val text = trace.formatted()
        assertTrue("Should show warning icon", text.contains("⚠"))
    }

    @Test
    fun `duration is shown when positive`() {
        val trace = AgentTrace()
        trace.add(AgentTraceStep(type = AgentTraceStep.Type.TOOL_CALL, title = "read_file(Big.kt)", durationMs = 250L))
        val text = trace.formatted()
        assertTrue("Duration should appear in formatted output", text.contains("250ms"))
    }

    @Test
    fun `summary appears when non-blank`() {
        val trace = AgentTrace()
        trace.add(AgentTraceStep(
            type = AgentTraceStep.Type.DECISION,
            title = "Chose read_file",
            summary = "Index was empty so fell back to direct read",
        ))
        val text = trace.formatted()
        assertTrue("Summary should appear in formatted text", text.contains("Index was empty"))
    }

    @Test
    fun `multiple steps all appear in formatted output`() {
        val trace = AgentTrace()
        trace.add(AgentTraceStep(type = AgentTraceStep.Type.SEARCH, title = "search_code(auth)"))
        trace.add(AgentTraceStep(type = AgentTraceStep.Type.FILE_READ, title = "read_file(Auth.kt)"))
        trace.add(AgentTraceStep(type = AgentTraceStep.Type.VERIFICATION, title = "write_file(Auth.kt)"))
        val text = trace.formatted()
        assertTrue(text.contains("search_code(auth)"))
        assertTrue(text.contains("read_file(Auth.kt)"))
        assertTrue(text.contains("write_file(Auth.kt)"))
    }
}
