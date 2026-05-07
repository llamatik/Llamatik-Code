package com.llamatik.teammate.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolBridgeServiceTest {

    @Test fun `bridged name format is correct`() {
        assertEquals("mcp_my_server_list_files", McpToolBridgeService.bridgedName("my server", "list_files"))
        assertEquals("mcp_github_create_issue", McpToolBridgeService.bridgedName("GitHub", "create_issue"))
    }

    @Test fun `special chars in server name are normalised`() {
        assertEquals("mcp_my_server_tool", McpToolBridgeService.bridgedName("My Server!", "tool"))
        assertEquals("mcp_a_b_c_tool", McpToolBridgeService.bridgedName("a-b-c", "tool"))
    }

    @Test fun `register and unregister tools`() {
        val service = McpToolBridgeService()
        val tools = listOf(
            McpToolDescriptor("srv1", "read_file", "reads a file"),
            McpToolDescriptor("srv1", "write_file", "writes a file"),
        )
        service.registerTools("my_server", "srv1", tools)
        assertEquals(2, service.bridgedTools().size)
        assertTrue(service.allBridgedToolNames().contains("mcp_my_server_read_file"))

        service.unregisterServer("srv1")
        assertTrue(service.bridgedTools().isEmpty())
    }

    @Test fun `second register replaces first for same server`() {
        val service = McpToolBridgeService()
        service.registerTools("s", "srv1", listOf(McpToolDescriptor("srv1", "tool_a", "")))
        service.registerTools("s", "srv1", listOf(McpToolDescriptor("srv1", "tool_b", "")))
        assertEquals(1, service.bridgedTools().size)
        assertTrue(service.allBridgedToolNames().contains("mcp_s_tool_b"))
        assertFalse(service.allBridgedToolNames().contains("mcp_s_tool_a"))
    }
}
