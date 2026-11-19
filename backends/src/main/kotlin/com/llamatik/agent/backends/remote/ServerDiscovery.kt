package com.llamatik.agent.backends.remote

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

/** Where discovery events come from: mDNS on the LAN ([LanMdns]), or a fake in tests. */
fun interface DiscoverySource {
    /** Starts listening; events arrive on any thread until the returned handle is closed. */
    fun start(onFound: (DiscoveredServer) -> Unit, onLost: (id: String) -> Unit, onError: (message: String) -> Unit): AutoCloseable
}

/**
 * Finds Llamatik servers on the local network (mDNS service `_llamatik._tcp`), the same way the Llamatik
 * app's "Scan" does: any device with *Share this device* turned on announces itself.
 *
 * Deliberately free of coroutines and HTTP-library types: inside the IDE the plugin runs on the platform's own
 * copies of those, and a different version here breaks at runtime.
 */
class ServerDiscovery(private val source: DiscoverySource = LanMdns) {

    /**
     * Scans until the returned handle is closed, reporting servers as they appear and disappear. Callbacks run
     * on a background thread. Closing stops the scan and releases the network listener.
     */
    fun start(
        onFound: (DiscoveredServer) -> Unit,
        onLost: (id: String) -> Unit = {},
        onError: (message: String) -> Unit = {},
    ): AutoCloseable = source.start(onFound, onLost, onError)

    /** Scans for [timeoutMs] and returns what was found, deduplicated and still present at the end. */
    fun scan(timeoutMs: Long, onFound: (DiscoveredServer) -> Unit = {}): List<DiscoveredServer> {
        val found = LinkedHashMap<String, DiscoveredServer>()
        val failed = CountDownLatch(1)
        val handle = source.start(
            onFound = { synchronized(found) { found[it.id] = it }; onFound(it) },
            onLost = { id -> synchronized(found) { found.remove(id) } },
            onError = { failed.countDown() },
        )
        try {
            failed.await(timeoutMs, TimeUnit.MILLISECONDS) // returns early only if the scan cannot work at all
        } finally {
            handle.close()
        }
        return synchronized(found) { found.values.toList() }
    }
}
