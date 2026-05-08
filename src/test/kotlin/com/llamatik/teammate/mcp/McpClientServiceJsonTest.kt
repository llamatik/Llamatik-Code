package com.llamatik.teammate.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpClientServiceJsonTest {

    @Test fun `build JSON-RPC request`() {
        val json = McpClientService.buildJsonRpcRequest(1L, "initialize", mapOf("version" to "1.0"))
        assertTrue(json.contains("\"method\":\"initialize\""))
        assertTrue(json.contains("\"id\":1"))
        assertTrue(json.contains("\"version\":\"1.0\""))
    }

    @Test fun `escapeString handles special chars`() {
        val result = McpClientService.escapeString("hello\nworld\t\"test\"")
        assertEquals("\"hello\\nworld\\t\\\"test\\\"\"", result)
    }

    @Test fun `parseJson parses simple object`() {
        val result = McpClientService.parseJson("""{"id":1,"method":"tools/list","result":{"tools":[]}}""")
        assertEquals(1L, result["id"])
        assertEquals("tools/list", result["method"])
    }

    @Test fun `parseJson parses nested object`() {
        val result = McpClientService.parseJson("""{"result":{"tools":[{"name":"foo","description":"bar"}]}}""")
        @Suppress("UNCHECKED_CAST")
        val tools = ((result["result"] as Map<*, *>)["tools"] as List<*>)
        assertEquals(1, tools.size)
        assertEquals("foo", (tools[0] as Map<*, *>)["name"])
    }

    @Test fun `parseJson returns empty map for invalid input`() {
        val result = McpClientService.parseJson("not json")
        assertTrue(result.isEmpty())
    }

    @Test fun `redactSecrets removes token values`() {
        val line = """{"Authorization":"Bearer abc123secret","other":"value"}"""
        val redacted = McpClientService.redactSecrets(line)
        assertTrue(!redacted.contains("abc123secret"))
        assertTrue(redacted.contains("REDACTED"))
    }

    @Test fun `redactSecrets preserves non-secret content`() {
        val line = """{"name":"my_tool","description":"does stuff"}"""
        val redacted = McpClientService.redactSecrets(line)
        assertEquals(line, redacted)
    }

    @Test fun `mapToJson serialises nested structures`() {
        val result = McpClientService.mapToJson(mapOf("a" to listOf(1, 2, 3), "b" to null))
        assertTrue(result.contains("\"a\":[1,2,3]"))
        assertTrue(result.contains("\"b\":null"))
    }
}
