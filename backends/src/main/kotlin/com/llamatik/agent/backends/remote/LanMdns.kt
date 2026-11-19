package com.llamatik.agent.backends.remote

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener
import kotlin.concurrent.thread

/**
 * mDNS discovery of `_llamatik._tcp` servers that listens on the machine's real LAN interfaces.
 *
 * The Llamatik library's own `NodeDiscovery` calls `JmDNS.create()` with no address, which lets JmDNS pick an
 * interface itself. On machines with VPN/tunnel interfaces (macOS `utun*`, `awdl0`, Docker, ...) it often picks
 * an IPv6 link-local address on a tunnel and then never sees a server on the actual network, so nothing is
 * found. Here one JmDNS instance is bound to every suitable IPv4 interface instead.
 */
internal object LanMdns : DiscoverySource {
    private const val SERVICE_TYPE = "_llamatik._tcp.local."

    /** IPv4 addresses of interfaces that are up, multicast capable, not loopback and not point-to-point. */
    fun lanAddresses(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && it.supportsMulticast() && !it.isLoopback && !it.isPointToPoint && !it.isVirtual }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>() }
            .filter { !it.isLinkLocalAddress && !it.isLoopbackAddress }
    }.getOrDefault(emptyList())

    override fun start(
        onFound: (DiscoveredServer) -> Unit,
        onLost: (id: String) -> Unit,
        onError: (message: String) -> Unit,
    ): AutoCloseable {
        val closed = AtomicBoolean(false)
        val active = CopyOnWriteArrayList<Pair<JmDNS, ServiceListener>>()

        // JmDNS can block for a long time while joining the multicast group (and forever on a blocked
        // network), so nothing here may run on the caller's thread.
        thread(isDaemon = true, name = "llamatik-mdns-start") {
            // No suitable interface: let JmDNS try its own default rather than reporting nothing at all.
            for (address in lanAddresses().ifEmpty { listOf(null) }) {
                if (closed.get()) return@thread
                try {
                    val mdns = if (address != null) JmDNS.create(address) else JmDNS.create()
                    val listener = listenerFor(mdns, onFound, onLost)
                    mdns.addServiceListener(SERVICE_TYPE, listener)
                    active += mdns to listener
                    if (closed.get()) { release(active); return@thread }
                } catch (e: Throwable) {
                    if (!closed.get()) onError(e.message ?: "Could not listen on ${address?.hostAddress ?: "the default interface"}")
                }
            }
            if (active.isEmpty() && !closed.get()) onError("No network interface available for discovery")
        }

        return AutoCloseable {
            closed.set(true)
            // JmDNS.close() announces and can take seconds: do not hold up the caller.
            thread(isDaemon = true, name = "llamatik-mdns-close") { release(active) }
        }
    }

    private fun listenerFor(mdns: JmDNS, onFound: (DiscoveredServer) -> Unit, onLost: (String) -> Unit) = object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) { mdns.requestServiceInfo(event.type, event.name) }
        override fun serviceRemoved(event: ServiceEvent) { onLost(event.name) }
        override fun serviceResolved(event: ServiceEvent) {
            val info = event.info
            // Prefer IPv4: a link-local IPv6 address needs a zone id and does not work in a URL.
            val host = info.inet4Addresses.firstOrNull()?.hostAddress ?: info.inetAddresses.firstOrNull()?.hostAddress ?: return
            onFound(DiscoveredServer(info.name, host, info.port, info.name))
        }
    }

    private fun release(active: CopyOnWriteArrayList<Pair<JmDNS, ServiceListener>>) {
        active.forEach { (mdns, listener) ->
            runCatching { mdns.removeServiceListener(SERVICE_TYPE, listener) }
            runCatching { mdns.close() }
        }
        active.clear()
    }
}
