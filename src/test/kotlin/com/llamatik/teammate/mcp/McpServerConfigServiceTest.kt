package com.llamatik.teammate.mcp

import org.junit.Assert.assertEquals
import org.junit.Test

class McpServerConfigServiceTest {

    @Test fun `StoredMcpServer round-trips through toConfig and fromConfig`() {
        val original = McpServerConfig(
            id = "id-1",
            name = "Test Server",
            command = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-filesystem", "/tmp"),
            env = mapOf("DEBUG" to "1", "API_KEY" to "secret"),
            workingDirectory = "/home/user",
            enabled = true,
            autoStart = true,
            trustLevel = McpTrustLevel.TRUST_SERVER,
        )
        val stored = McpServerConfigService.StoredMcpServer.fromConfig(original)
        val restored = stored.toConfig()

        assertEquals(original.id, restored.id)
        assertEquals(original.name, restored.name)
        assertEquals(original.command, restored.command)
        assertEquals(original.args, restored.args)
        assertEquals(original.env, restored.env)
        assertEquals(original.workingDirectory, restored.workingDirectory)
        assertEquals(original.enabled, restored.enabled)
        assertEquals(original.autoStart, restored.autoStart)
        assertEquals(original.trustLevel, restored.trustLevel)
    }

    @Test fun `args with blank lines are filtered out`() {
        val stored = McpServerConfigService.StoredMcpServer(args = "-y\n\n--port\n8080")
        val config = stored.toConfig()
        assertEquals(listOf("-y", "--port", "8080"), config.args)
    }

    @Test fun `env with no equals sign is ignored`() {
        val stored = McpServerConfigService.StoredMcpServer(env = "GOOD=yes\nBAD_LINE\nOTHER=no")
        val config = stored.toConfig()
        assertEquals(mapOf("GOOD" to "yes", "OTHER" to "no"), config.env)
    }

    @Test fun `unknown trust level falls back to ASK_EVERY_TIME`() {
        val stored = McpServerConfigService.StoredMcpServer(trustLevel = "BOGUS_LEVEL")
        assertEquals(McpTrustLevel.ASK_EVERY_TIME, stored.toConfig().trustLevel)
    }
}
