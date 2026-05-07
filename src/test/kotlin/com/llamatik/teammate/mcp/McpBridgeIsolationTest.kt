package com.llamatik.teammate.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpBridgeIsolationTest {

    private fun tools(serverId: String, vararg names: String) =
        names.map { McpToolDescriptor(serverId, it, "desc") }

    @Test fun `unregister one server leaves other server tools intact`() {
        val bridge = McpToolBridgeService()
        bridge.registerTools("alpha", "srv-a", tools("srv-a", "tool_x", "tool_y"))
        bridge.registerTools("beta",  "srv-b", tools("srv-b", "tool_z"))

        bridge.unregisterServer("srv-a")

        assertFalse(bridge.allBridgedToolNames().contains("mcp_alpha_tool_x"))
        assertFalse(bridge.allBridgedToolNames().contains("mcp_alpha_tool_y"))
        assertTrue(bridge.allBridgedToolNames().contains("mcp_beta_tool_z"))
        assertEquals(1, bridge.bridgedTools().size)
    }

    @Test fun `unregister unknown server id is a no-op`() {
        val bridge = McpToolBridgeService()
        bridge.registerTools("alpha", "srv-a", tools("srv-a", "tool_x"))
        bridge.unregisterServer("srv-doesnt-exist")
        assertEquals(1, bridge.bridgedTools().size)
    }

    @Test fun `registering empty tool list unregisters previous tools for that server`() {
        val bridge = McpToolBridgeService()
        bridge.registerTools("alpha", "srv-a", tools("srv-a", "tool_x"))
        bridge.registerTools("alpha", "srv-a", emptyList())
        assertTrue(bridge.bridgedTools().isEmpty())
    }

    @Test fun `getDescriptor returns correct descriptor by bridged name`() {
        val bridge = McpToolBridgeService()
        val descriptor = McpToolDescriptor("srv-a", "read_file", "reads a file", riskLevel = McpRiskLevel.LOW)
        bridge.registerTools("myserver", "srv-a", listOf(descriptor))

        val retrieved = bridge.getDescriptor("mcp_myserver_read_file")
        assertEquals(descriptor, retrieved)
    }

    @Test fun `getDescriptor returns null for unknown bridged name`() {
        val bridge = McpToolBridgeService()
        assertFalse(bridge.getDescriptor("mcp_nonexistent_tool") != null)
    }

    @Test fun `two servers with same tool name do not collide`() {
        val bridge = McpToolBridgeService()
        bridge.registerTools("serverA", "srv-a", tools("srv-a", "search"))
        bridge.registerTools("serverB", "srv-b", tools("srv-b", "search"))

        assertTrue(bridge.allBridgedToolNames().contains("mcp_serverA_search") ||
                   bridge.allBridgedToolNames().contains("mcp_servera_search"))
        assertTrue(bridge.allBridgedToolNames().contains("mcp_serverB_search") ||
                   bridge.allBridgedToolNames().contains("mcp_serverb_search"))
        assertEquals(2, bridge.bridgedTools().size)
    }
}
