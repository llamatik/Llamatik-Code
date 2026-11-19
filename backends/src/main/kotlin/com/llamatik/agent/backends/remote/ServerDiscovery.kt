package com.llamatik.agent.backends.remote

import com.llamatik.network.discovery.DiscoveryEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** A Llamatik server announced on the local network. */
data class DiscoveredServer(
    /** Unique per announcement (the mDNS service name); a server that goes away is reported by this id. */
    val id: String,
    val host: String,
    val port: Int,
    val name: String,
) {
    /** `host:port`, with an IPv6 host in brackets: what `--server` and [ServerConnector.parseAddress] take. */
    val address: String get() = if (host.contains(':') && !host.startsWith("[")) "[$host]:$port" else "$host:$port"
}

/**
 * Finds Llamatik servers on the local network (mDNS service `_llamatik._tcp`), the same way the Llamatik
 * app's "Scan" does: any device with *Share this device* turned on announces itself.
 *
 * @param source stream of discovery events; [LanMdns] (the library's protocol on the real LAN interfaces) by default
 */
class ServerDiscovery(
    private val source: () -> Flow<DiscoveryEvent> = { LanMdns.events() },
) {
    /**
     * Scans until the returned handle is closed, reporting servers as they appear and disappear. Callbacks run
     * on a background thread. Closing stops the scan and releases the network listener.
     */
    fun start(
        onFound: (DiscoveredServer) -> Unit,
        onLost: (id: String) -> Unit = {},
        onError: (message: String) -> Unit = {},
    ): AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                source().collect { event -> dispatch(event, onFound, onLost) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                onError(e.message ?: "Discovery failed")
            }
        }
        return AutoCloseable { scope.cancel() }
    }

    /** Scans for [timeoutMs] and returns what was found, deduplicated and still present at the end. */
    fun scan(timeoutMs: Long, onFound: (DiscoveredServer) -> Unit = {}): List<DiscoveredServer> {
        val found = LinkedHashMap<String, DiscoveredServer>()
        runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs) {
                source().collect { event ->
                    dispatch(
                        event,
                        onFound = { found[it.id] = it; onFound(it) },
                        onLost = { found.remove(it) },
                    )
                }
            }
        }
        return found.values.toList()
    }

    private fun dispatch(event: DiscoveryEvent, onFound: (DiscoveredServer) -> Unit, onLost: (String) -> Unit) {
        when (event) {
            is DiscoveryEvent.NodeFound -> with(event.node) { onFound(DiscoveredServer(id, host, port, name)) }
            is DiscoveryEvent.NodeLost -> onLost(event.nodeId)
        }
    }
}
