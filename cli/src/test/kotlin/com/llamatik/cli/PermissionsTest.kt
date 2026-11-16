package com.llamatik.cli

import com.llamatik.agent.core.agent.ConfirmingToolExecutor
import com.llamatik.agent.core.agent.ToolExecutor
import com.llamatik.agent.core.host.AgentEnvironment
import com.llamatik.agent.core.host.AutoDeny
import com.llamatik.agent.core.host.Decision
import com.llamatik.agent.core.host.PermissionKind
import com.llamatik.agent.core.host.PermissionRequest
import com.llamatik.agent.core.tools.ToolCall
import com.llamatik.agent.core.tools.ToolExecutionContext
import com.llamatik.agent.core.tools.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PermissionsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val edit = PermissionRequest(PermissionKind.EDIT, "Apply?")

    @Test
    fun `answers map to decisions and always is remembered per kind`() {
        val t = ScriptedTerminal("y", "n", "", "a")
        val p = TerminalPermissionPrompt(t)
        assertEquals(Decision.ALLOW_ONCE, p.confirm(edit))
        assertEquals(Decision.DENY, p.confirm(edit))
        assertEquals(Decision.DENY, p.confirm(edit))
        assertEquals(Decision.ALLOW_ALWAYS, p.confirm(edit))
        val asked = t.prompts.size
        assertEquals(Decision.ALLOW_ONCE, p.confirm(edit)) // remembered, not asked
        assertEquals(asked, t.prompts.size)
        // A different kind of action is still asked.
        assertEquals(Decision.DENY, p.confirm(PermissionRequest(PermissionKind.SHELL, "Run?")))
    }

    @Test
    fun `end of input and auto-approve`() {
        assertEquals(Decision.DENY, TerminalPermissionPrompt(ScriptedTerminal()).confirm(edit))
        val auto = ScriptedTerminal()
        assertEquals(Decision.ALLOW_ONCE, TerminalPermissionPrompt(auto, autoApprove = true).confirm(edit))
        assertTrue(auto.prompts.isEmpty())
    }

    @Test
    fun `commands need approval but reads do not`() {
        val ran = mutableListOf<String>()
        val inner = object : ToolExecutor {
            override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult { ran += call.name; return ToolResult(call.name, true, "ok") }
            override fun listToolNames() = "x"
            override fun dynamicToolNames() = emptySet<String>()
        }
        val ctx = ToolExecutionContext(AgentEnvironment.headless(tmp.root.toPath(), AutoDeny))
        val declined = ConfirmingToolExecutor(inner, TerminalPermissionPrompt(ScriptedTerminal("n")))
        val result = declined.execute(ToolCall("terminal_run", mapOf("command" to "./gradlew test")), ctx)
        assertFalse(result.success)
        assertTrue(result.content.contains("declined"))
        declined.execute(ToolCall("read_file", mapOf("path" to "a")), ctx)
        assertEquals(listOf("read_file"), ran)

        val approved = ConfirmingToolExecutor(inner, TerminalPermissionPrompt(ScriptedTerminal("y")))
        assertTrue(approved.execute(ToolCall("terminal_run", mapOf("command" to "./gradlew test")), ctx).success)
        assertEquals(listOf("read_file", "terminal_run"), ran)
    }
}
