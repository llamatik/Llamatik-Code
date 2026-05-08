package com.llamatik.teammate.mcp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpServerLimitTest {

    private fun config(id: String, enabled: Boolean) = McpServerConfig(id = id, name = id, enabled = enabled)

    // ── Free tier ─────────────────────────────────────────────────────────────

    @Test fun `free - first enabled server is allowed`() {
        val result = McpServerConfigService.checkServerLimit(
            existingEnabled = emptyList(),
            newConfig = config("srv1", enabled = true),
            isPro = false,
        )
        assertTrue(result.isSuccess)
    }

    @Test fun `free - second enabled server is blocked`() {
        val result = McpServerConfigService.checkServerLimit(
            existingEnabled = listOf(config("srv1", enabled = true)),
            newConfig = config("srv2", enabled = true),
            isPro = false,
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is McpProLimitException)
    }

    @Test fun `free - updating the only enabled server is allowed`() {
        // srv1 is already in the list; saving it again (edit) should not count as a second server
        val result = McpServerConfigService.checkServerLimit(
            existingEnabled = listOf(config("srv1", enabled = true)),
            newConfig = config("srv1", enabled = true),
            isPro = false,
        )
        assertTrue(result.isSuccess)
    }

    @Test fun `free - saving a disabled server is always allowed`() {
        val result = McpServerConfigService.checkServerLimit(
            existingEnabled = listOf(config("srv1", enabled = true)),
            newConfig = config("srv2", enabled = false),
            isPro = false,
        )
        assertTrue(result.isSuccess)
    }

    // ── Pro tier ──────────────────────────────────────────────────────────────

    @Test fun `pro - multiple enabled servers are allowed`() {
        val existing = listOf(config("srv1", true), config("srv2", true), config("srv3", true))
        val result = McpServerConfigService.checkServerLimit(
            existingEnabled = existing,
            newConfig = config("srv4", enabled = true),
            isPro = true,
        )
        assertTrue(result.isSuccess)
    }

    @Test fun `pro - no limit even with many enabled servers`() {
        val existing = (1..10).map { config("srv$it", enabled = true) }
        val result = McpServerConfigService.checkServerLimit(
            existingEnabled = existing,
            newConfig = config("srv11", enabled = true),
            isPro = true,
        )
        assertTrue(result.isSuccess)
    }
}
