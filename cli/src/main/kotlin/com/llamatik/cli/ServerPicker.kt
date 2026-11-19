package com.llamatik.cli

import com.llamatik.agent.backends.remote.DiscoveredServer
import com.llamatik.agent.backends.remote.ServerDiscovery

/** Looks for Llamatik servers on the local network and lets the user choose one (`--discover`). */
class ServerPicker(
    private val terminal: Terminal,
    private val discovery: ServerDiscovery = ServerDiscovery(),
    private val scanMs: Long = DEFAULT_SCAN_MS,
) {
    sealed interface Result {
        /** [address] is ready to be used as `--server`. */
        data class Picked(val address: String, val name: String) : Result
        data class Failed(val reason: String) : Result
        data object Cancelled : Result
    }

    fun pick(): Result {
        terminal.println(terminal.style(Style.DIM, "Looking for Llamatik servers on the network…"))
        val servers = discovery.scan(scanMs) { terminal.println(terminal.style(Style.DIM, "  found ${it.name} (${it.address})")) }
        return when {
            servers.isEmpty() -> Result.Failed(
                "No Llamatik server found. Turn on \"Share this device\" in the Llamatik app, make sure both " +
                    "devices are on the same network, or pass --server <host[:port]>.",
            )
            servers.size == 1 -> confirmSingle(servers.single())
            else -> chooseOne(servers)
        }
    }

    private fun confirmSingle(server: DiscoveredServer): Result {
        // Nobody to ask (output piped): the only server on the network is the obvious choice.
        if (!terminal.interactive) return Result.Picked(server.address, server.name)
        val answer = terminal.readLine("Connect to ${server.name} (${server.address})? [Y/n] ") ?: return Result.Cancelled
        return if (answer.trim().lowercase() in setOf("", "y", "yes")) Result.Picked(server.address, server.name) else Result.Cancelled
    }

    private fun chooseOne(servers: List<DiscoveredServer>): Result {
        terminal.println()
        servers.forEachIndexed { i, s -> terminal.println("  ${i + 1}) ${s.name}  ${terminal.style(Style.DIM, s.address)}") }
        if (!terminal.interactive) {
            return Result.Failed("Several Llamatik servers found; choose one with --server <host[:port]>.")
        }
        val answer = terminal.readLine("Connect to which one? [1-${servers.size}, Enter to cancel] ")?.trim().orEmpty()
        val chosen = answer.toIntOrNull()?.let { servers.getOrNull(it - 1) } ?: return Result.Cancelled
        return Result.Picked(chosen.address, chosen.name)
    }

    companion object {
        const val DEFAULT_SCAN_MS = 4_000L
    }
}
