package com.llamatik.agent.backends.remote

import com.llamatik.network.discovery.DiscoveredNode
import com.llamatik.network.discovery.DiscoveryEvent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener
import kotlin.concurrent.thread

/**
 * mDNS discovery of `_llamatik._tcp` servers that listens on the machine's real LAN interfaces.
 *
 * The library's own `NodeDiscovery` calls `JmDNS.create()` with no address, which lets JmDNS pick an
 * interface itself. On machines with VPN/tunnel interfaces (macOS `utun*`, `awdl0`, Docker, ...) it often
 * picks an IPv6 link-local address on a tunnel and then never sees a server on the actual network, so
 * nothing is found. Here one JmDNS instance is bound to every suitable IPv4 interface instead.
 */
internal object LanMdns {
    private const val SERVICE_TYPE = "_llamatik._tcp.local."

    /** IPv4 addresses of interfaces that are up, multicast capable, not loopback and not point-to-point. */
    fun lanAddresses(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && it.supportsMulticast() && !it.isLoopback && !it.isPointToPoint && !it.isVirtual }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>() }
            .filter { !it.isLinkLocalAddress && !it.isLoopbackAddress }
    }.getOrDefault(emptyList())

    fun events(addresses: List<InetAddress> = lanAddresses()): Flow<DiscoveryEvent> = callbackFlow {
        // No suitable interface: let JmDNS try its own default rather than reporting nothing at all.
        val instances = (addresses.ifEmpty { listOf(null) }).mapNotNull { address ->
            runCatching { if (address != null) JmDNS.create(address) else JmDNS.create() }.getOrNull()
        }
        if (instances.isEmpty()) {
            close(java.io.IOException("No network interface available for discovery"))
            return@callbackFlow
        }
        val listeners = instances.map { mdns ->
            val listener = object : ServiceListener {
                override fun serviceAdded(event: ServiceEvent) { mdns.requestServiceInfo(event.type, event.name) }
                override fun serviceRemoved(event: ServiceEvent) { trySend(DiscoveryEvent.NodeLost(event.name)) }
                override fun serviceResolved(event: ServiceEvent) {
                    val info = event.info
                    // Prefer IPv4: a link-local IPv6 address needs a zone id and does not work in a URL.
                    val host = info.inet4Addresses.firstOrNull()?.hostAddress ?: info.inetAddresses.firstOrNull()?.hostAddress ?: return
                    trySend(DiscoveryEvent.NodeFound(DiscoveredNode(info.name, host, info.port, info.name)))
                }
            }
            mdns.addServiceListener(SERVICE_TYPE, listener)
            mdns to listener
        }
        awaitClose {
            // JmDNS.close() announces and can take seconds: do not hold up the caller.
            thread(isDaemon = true, name = "llamatik-mdns-close") {
                listeners.forEach { (mdns, listener) ->
                    runCatching { mdns.removeServiceListener(SERVICE_TYPE, listener) }
                    runCatching { mdns.close() }
                }
            }
        }
    }
}
