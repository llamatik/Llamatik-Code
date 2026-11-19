package com.llamatik.cli

import com.llamatik.agent.backends.remote.DiscoveredServer
import com.llamatik.agent.backends.remote.DiscoverySource
import com.llamatik.agent.backends.remote.ServerDiscovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerPickerTest {
    private fun node(name: String, host: String, port: Int = 8080) = DiscoveredServer(name, host, port, name)

    private fun picker(terminal: Terminal, vararg nodes: DiscoveredServer) = ServerPicker(
        terminal,
        ServerDiscovery(DiscoverySource { onFound, _, _ -> nodes.forEach(onFound); AutoCloseable { } }),
        scanMs = 200,
    )

    @Test
    fun `nothing found explains how to fix it`() {
        val result = picker(ScriptedTerminal()).pick()
        assertTrue(result is ServerPicker.Result.Failed)
        val reason = (result as ServerPicker.Result.Failed).reason
        assertTrue(reason.contains("Share this device"))
        assertTrue(reason.contains("--server"))
    }

    @Test
    fun `a single server is confirmed by the user, or taken when nobody can be asked`() {
        val t = ScriptedTerminal("")
        assertEquals(ServerPicker.Result.Picked("192.168.1.5:8080", "Mac Studio"), picker(t, node("Mac Studio", "192.168.1.5")).pick())
        assertTrue(t.out.contains("found Mac Studio (192.168.1.5:8080)"))
        assertEquals(ServerPicker.Result.Cancelled, picker(ScriptedTerminal("n"), node("Mac Studio", "192.168.1.5")).pick())
        assertEquals(ServerPicker.Result.Cancelled, picker(ScriptedTerminal(), node("Mac Studio", "192.168.1.5")).pick())
        val piped = picker(ScriptedTerminal(interactive = false), node("Mac Studio", "192.168.1.5")).pick()
        assertEquals(ServerPicker.Result.Picked("192.168.1.5:8080", "Mac Studio"), piped)
    }

    @Test
    fun `several servers are listed and the user picks a number`() {
        val t = ScriptedTerminal("2")
        val result = picker(t, node("Mac Studio", "192.168.1.5"), node("Pixel", "192.168.1.9", 9000)).pick()
        assertEquals(ServerPicker.Result.Picked("192.168.1.9:9000", "Pixel"), result)
        assertTrue(t.out.contains("1) Mac Studio"))
        assertTrue(t.out.contains("2) Pixel"))
    }

    @Test
    fun `a bad choice cancels, and with several servers and nobody to ask it fails with advice`() {
        val two = arrayOf(node("A", "10.0.0.1"), node("B", "10.0.0.2"))
        assertEquals(ServerPicker.Result.Cancelled, picker(ScriptedTerminal("7"), *two).pick())
        assertEquals(ServerPicker.Result.Cancelled, picker(ScriptedTerminal(""), *two).pick())
        val piped = picker(ScriptedTerminal(interactive = false), *two).pick()
        assertTrue((piped as ServerPicker.Result.Failed).reason.contains("--server"))
    }

    @Test
    fun `an IPv6 server address is bracketed so it can be used with --server`() {
        val result = picker(ScriptedTerminal(""), node("Box", "fe80::1", 7000)).pick()
        assertEquals(ServerPicker.Result.Picked("[fe80::1]:7000", "Box"), result)
    }
}
