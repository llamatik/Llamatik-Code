package com.llamatik.agent.backends.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class ServerDiscoveryTest {
    private fun server(id: String, port: Int = 8080) = DiscoveredServer(id, "192.168.1.$port", port, id)

    private sealed interface Event {
        data class Found(val server: DiscoveredServer) : Event
        data class Lost(val id: String) : Event
        data class Failed(val message: String) : Event
    }

    /** A scripted source: replays [events] from another thread, then stays quiet until closed. */
    private fun source(vararg events: Event, closedFlag: AtomicBoolean = AtomicBoolean(false)) = DiscoverySource { onFound, onLost, onError ->
        thread(isDaemon = true) {
            events.forEach {
                when (it) {
                    is Event.Found -> onFound(it.server)
                    is Event.Lost -> onLost(it.id)
                    is Event.Failed -> onError(it.message)
                }
            }
        }
        AutoCloseable { closedFlag.set(true) }
    }

    @Test
    fun `a timed scan returns the servers still present, deduplicated`() {
        val discovery = ServerDiscovery(source(
            Event.Found(server("Mac Studio", 8080)),
            Event.Found(server("Pixel", 9000)),
            Event.Found(server("Mac Studio", 8081)), // announced again: latest wins
            Event.Lost("Pixel"),
        ))
        val seen = CopyOnWriteArrayList<String>()
        val found = discovery.scan(400) { seen += it.name }
        assertEquals(listOf("Mac Studio"), found.map { it.name })
        assertEquals(8081, found.single().port)
        assertEquals(listOf("Mac Studio", "Pixel", "Mac Studio"), seen)
    }

    @Test
    fun `addresses are usable as --server values, IPv6 included`() {
        assertEquals("192.168.1.5:8080", DiscoveredServer("a", "192.168.1.5", 8080, "a").address)
        val v6 = DiscoveredServer("a", "fe80::1", 9000, "a")
        assertEquals("[fe80::1]:9000", v6.address)
        assertEquals("fe80::1" to 9000, ServerConnector.parseAddress(v6.address))
    }

    @Test
    fun `a timed scan ends on time even when the network is silent, and releases the listener`() {
        val closed = AtomicBoolean(false)
        val started = System.currentTimeMillis()
        assertTrue(ServerDiscovery(source(closedFlag = closed)).scan(200).isEmpty())
        assertTrue(System.currentTimeMillis() - started < 3_000)
        assertTrue("the scan must be closed when it ends", closed.get())
    }

    @Test
    fun `a scan that cannot work ends early`() {
        val started = System.currentTimeMillis()
        assertTrue(ServerDiscovery(source(Event.Failed("no network interface"))).scan(10_000).isEmpty())
        assertTrue("should not wait out the full timeout", System.currentTimeMillis() - started < 5_000)
    }

    @Test
    fun `a continuous scan reports found and lost servers until closed`() {
        val closed = AtomicBoolean(false)
        val gotTwo = CountDownLatch(2)
        val lostA = CountDownLatch(1)
        val found = CopyOnWriteArrayList<DiscoveredServer>()
        val handle = ServerDiscovery(source(Event.Found(server("A", 1)), Event.Found(server("B", 2)), Event.Lost("A"), closedFlag = closed))
            .start(onFound = { found += it; gotTwo.countDown() }, onLost = { if (it == "A") lostA.countDown() })
        assertTrue(gotTwo.await(5, TimeUnit.SECONDS))
        assertTrue(lostA.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("A", "B"), found.map { it.id })
        assertEquals("192.168.1.1:1", found.first().address)
        handle.close()
        assertTrue("closing must stop the underlying scan", closed.get())
    }

    @Test
    fun `a failing scan is reported instead of thrown`() {
        val failed = CountDownLatch(1)
        var message: String? = null
        ServerDiscovery(source(Event.Failed("no network interface"))).start(onFound = {}, onError = { message = it; failed.countDown() })
        assertTrue(failed.await(5, TimeUnit.SECONDS))
        assertEquals("no network interface", message)
    }

    @Test
    fun `discovery listens only on real LAN interfaces, never tunnels or link-local addresses`() {
        // On a Mac with VPN/tunnel interfaces the library's default picked fe80::...%utun2 and found nothing.
        val addresses = LanMdns.lanAddresses()
        assertTrue(addresses.all { it is java.net.Inet4Address })
        assertTrue(addresses.none { it.isLoopbackAddress || it.isLinkLocalAddress })
        val interfaces = addresses.map { java.net.NetworkInterface.getByInetAddress(it) }
        assertTrue(interfaces.none { it.isPointToPoint || it.isLoopback || !it.isUp })
        assertTrue(interfaces.none { it.name.startsWith("utun") || it.name.startsWith("awdl") || it.name.startsWith("llw") })
    }

    @Test
    fun `starting real discovery never blocks the caller`() {
        // JmDNS can block for minutes on a network that drops multicast; start() must return at once regardless.
        val started = System.currentTimeMillis()
        val handle = ServerDiscovery().start(onFound = {})
        assertTrue("start() took ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 1_000)
        handle.close()
    }

    @Test
    fun `real mDNS round trip finds a server announced on this machines LAN`() {
        // Real network stack: opt-in (LLAMATIK_TEST_MDNS=1). Where multicast is blocked (CI, sandboxes, macOS
        // "Local Network" privacy) JmDNS can block for many minutes, so it must never run by default.
        org.junit.Assume.assumeTrue("set LLAMATIK_TEST_MDNS=1 to run against the real network", System.getenv("LLAMATIK_TEST_MDNS") == "1")
        val lan = LanMdns.lanAddresses()
        org.junit.Assume.assumeTrue("no LAN interface with a private IPv4 address", lan.isNotEmpty())
        val name = "Llamatik-Test-${System.nanoTime()}"
        val advertiser = try {
            javax.jmdns.JmDNS.create(lan.first()).also {
                it.registerService(javax.jmdns.ServiceInfo.create("_llamatik._tcp.local.", name, 18080, "test"))
            }
        } catch (e: Throwable) { assumeNoException("mDNS advertising unavailable", e); return }
        val result = CopyOnWriteArrayList<DiscoveredServer>()
        val handle = ServerDiscovery().start(onFound = { if (it.name == name) result += it })
        try {
            val until = System.currentTimeMillis() + 15_000
            while (result.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(200)
            org.junit.Assume.assumeTrue("no mDNS answer within 15 s (multicast blocked?)", result.isNotEmpty())
            assertEquals(18080, result.first().port)
            assertEquals(lan.first().hostAddress, result.first().host)
        } finally {
            handle.close()
            thread(isDaemon = true) { runCatching { advertiser.close() } }
        }
    }
}
