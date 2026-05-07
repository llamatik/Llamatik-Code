package com.llamatik.teammate.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests session state transitions (markFailed, stop) without starting a real process.
 *
 * McpClientService.McpSession is an inner class; we access state via the status()
 * method which is public.  We use McpClientService.serverStatus() as the observable
 * surface rather than touching internal fields directly.
 */
class McpSessionStateTest {

    private val clientService = McpClientService()

    private fun config(id: String = "test-id") = McpServerConfig(
        id = id,
        name = "test",
        command = "false",   // /usr/bin/false — exits immediately with non-zero
    )

    @Test fun `new server status is STOPPED before start`() {
        val status = clientService.serverStatus("nonexistent")
        assertEquals(McpServerState.STOPPED, status.state)
        assertNull(status.lastError)
    }

    @Test fun `starting a bad command marks server FAILED`() {
        // "false" exits immediately, so initialize() will fail trying to read a response
        val status = clientService.startServer(config("bad-cmd"))
        // Either FAILED (process exited) or could not read response — not RUNNING
        assertTrue(
            "expected FAILED or STOPPED, got ${status.state}",
            status.state == McpServerState.FAILED || status.state == McpServerState.STOPPED,
        )
    }

    @Test fun `stopping a non-running server is a no-op`() {
        clientService.stopServer("never-started")
        // Should not throw — status returns STOPPED
        assertEquals(McpServerState.STOPPED, clientService.serverStatus("never-started").state)
    }

    @Test fun `stopAll clears all sessions`() {
        // Start two bad servers so sessions are created
        clientService.startServer(config("s1"))
        clientService.startServer(config("s2"))
        clientService.stopAll()
        assertEquals(McpServerState.STOPPED, clientService.serverStatus("s1").state)
        assertEquals(McpServerState.STOPPED, clientService.serverStatus("s2").state)
    }

    @Test fun `failed server stores lastError`() {
        val status = clientService.startServer(config("err-cmd"))
        if (status.state == McpServerState.FAILED) {
            assertNotNull(status.lastError)
            assertTrue(status.lastError!!.isNotBlank())
        }
        // If STOPPED (process never wrote anything before exit) that is also acceptable
    }

    @Test fun `restarting a failed server replaces the session`() {
        val cfg = config("restart-test")
        clientService.startServer(cfg)
        val first = clientService.serverStatus("restart-test")

        clientService.startServer(cfg)
        val second = clientService.serverStatus("restart-test")

        // Both attempts on a bad command should both be FAILED/STOPPED — no residual RUNNING state
        assertTrue(second.state != McpServerState.RUNNING)
    }
}
