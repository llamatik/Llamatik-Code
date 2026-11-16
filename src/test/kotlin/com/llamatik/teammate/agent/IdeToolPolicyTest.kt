package com.llamatik.teammate.agent

import com.llamatik.agent.core.agent.ToolPolicy
import com.llamatik.teammate.release.ToolInventoryTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdeToolPolicyTest {
    private val ide = IdeToolPolicy.POLICY

    @Test
    fun `the IDE policy contains every portable tool with the same classification`() {
        assertTrue(ide.knownToolNames.containsAll(ToolPolicy.PORTABLE.knownToolNames))
        assertTrue(ide.readOnlyToolNames.containsAll(ToolPolicy.PORTABLE.readOnlyToolNames))
        assertEquals(ToolPolicy.DEFAULT_EDIT_TOOLS, ide.editToolNames)
    }

    @Test
    fun `read-only tools are always known tools`() {
        assertEquals(emptySet<String>(), ide.readOnlyToolNames - ide.knownToolNames)
    }

    @Test
    fun `every tool the model is told about is one the loop accepts`() {
        // MCP tools are registered at runtime and accepted separately (ToolExecutor.dynamicToolNames).
        val rejected = ToolInventoryTest.EXPECTED_BUILTIN_TOOLS - ide.knownToolNames
        assertEquals("registered tools the loop would silently drop", emptySet<String>(), rejected)
    }
}
