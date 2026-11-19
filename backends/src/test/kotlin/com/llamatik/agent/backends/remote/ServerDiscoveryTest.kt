package com.llamatik.agent.backends.remote

import com.llamatik.network.discovery.DiscoveredNode
import com.llamatik.network.discovery.DiscoveryEvent
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ServerDiscoveryTest {
    private fun node(id: String, port: Int = 8080) = DiscoveredNode(id, "192.168.1.$port", port, id)

    private fun events(vararg e: DiscoveryEvent, thenWait: Boolean = true): () -> Flow<DiscoveryEvent> = {
        flow { e.forEach { emit(it) }; if (thenWait) awaitCancellation() }
    }

    @Test
    fun `a timed scan returns the servers still present, deduplicated`() {
        val discovery = ServerDiscovery(events(
            DiscoveryEvent.NodeFound(node("Mac Studio", 8080)),
            DiscoveryEvent.NodeFound(node("Pixel", 9000)),
            DiscoveryEvent.NodeFound(node("Mac Studio", 8081)), // announced again: latest wins
            DiscoveryEvent.NodeLost("Pixel"),
        ))
        val seen = CopyOnWriteArrayList<String>()
        val found = discovery.scan(300) { seen += it.name }
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
    fun `a timed scan ends on time even when the network is silent`() {
        val started = System.currentTimeMillis()
        assertTrue(ServerDiscovery(events()).scan(200).isEmpty())
        assertTrue(System.currentTimeMillis() - started < 3_000)
    }

    @Test
    fun `a continuous scan reports found and lost servers until closed`() {
        val cancelled = AtomicBoolean(false)
        val gotTwo = CountDownLatch(2)
        val found = CopyOnWriteArrayList<DiscoveredServer>()
        val lost = CopyOnWriteArrayList<String>()
        val discovery = ServerDiscovery {
            flow {
                try {
                    emit(DiscoveryEvent.NodeFound(node("A", 1)))
                    emit(DiscoveryEvent.NodeFound(node("B", 2)))
                    emit(DiscoveryEvent.NodeLost("A"))
                    awaitCancellation()
                } finally { cancelled.set(true) }
            }
        }
        val handle = discovery.start(onFound = { found += it; gotTwo.countDown() }, onLost = { lost += it })
        assertTrue(gotTwo.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("A", "B"), found.map { it.id })
        assertEquals("192.168.1.1:1", found.first().address)
        Thread.sleep(100)
        assertEquals(listOf("A"), lost)

        handle.close()
        val until = System.currentTimeMillis() + 5_000
        while (!cancelled.get() && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue("closing must stop the underlying scan", cancelled.get())
    }

    @Test
    fun `a failing scan is reported instead of thrown`() {
        val error = CompletableErrors()
        ServerDiscovery { flow { delay(10); throw java.io.IOException("no network interface") } }
            .start(onFound = {}, onError = error::set)
        assertEquals("no network interface", error.await())
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
            assertTrue("host should be the LAN IPv4 address: ${result.first().host}", result.first().host == lan.first().hostAddress)
        } finally {
            handle.close()
            Thread { runCatching { advertiser.close() } }.apply { isDaemon = true }.start()
        }
    }

    private class CompletableErrors {
        private val latch = CountDownLatch(1)
        @Volatile private var message: String? = null
        fun set(m: String) { message = m; latch.countDown() }
        fun await(): String? { latch.await(5, TimeUnit.SECONDS); return message }
    }
}
