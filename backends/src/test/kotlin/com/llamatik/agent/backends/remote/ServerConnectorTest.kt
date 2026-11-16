package com.llamatik.agent.backends.remote

import com.llamatik.agent.backends.testing.FakeLlamatikServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ServerConnectorTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: FakeLlamatikServer
    private lateinit var tokens: FileTokenStore

    @Before fun setUp() {
        server = FakeLlamatikServer()
        tokens = FileTokenStore(File(tmp.root, "cli/tokens.properties"))
    }

    @After fun tearDown() = server.close()

    private fun connect() = ServerConnector(tokens, "test-box").connect("127.0.0.1", server.port)

    @Test
    fun `an open server connects straight away and reports its model`() {
        val outcome = connect() as ConnectOutcome.Connected
        assertEquals("qwen.gguf", outcome.connection.capabilities.textModel)
        assertTrue(server.pairingRequests.isEmpty())
    }

    @Test
    fun `a server that needs approval is paired once and the token is reused`() {
        server.requiredToken = "device-token"
        val first = connect() as ConnectOutcome.Connected
        assertEquals(1, server.pairingRequests.size)
        assertTrue(server.pairingRequests.single().contains("test-box"))
        assertEquals("device-token", first.connection.endpoint.token)
        assertEquals("device-token", tokens.get("127.0.0.1", server.port))

        val second = connect() as ConnectOutcome.Connected
        assertEquals("no second approval request", 1, server.pairingRequests.size)
        assertEquals("device-token", second.connection.endpoint.token)
    }

    @Test
    fun `a revoked token is dropped and approval is requested again`() {
        server.requiredToken = "device-token"
        tokens.put("127.0.0.1", server.port, "stale-token")
        connect() as ConnectOutcome.Connected
        assertEquals(1, server.pairingRequests.size)
        assertEquals("device-token", tokens.get("127.0.0.1", server.port))
    }

    @Test
    fun `a denied or expired request fails with the reason`() {
        server.requiredToken = "device-token"
        server.pairingOutcome = "denied"
        assertEquals("The server denied this device.", (connect() as ConnectOutcome.Failed).reason)
        assertNull(tokens.get("127.0.0.1", server.port))
        server.pairingOutcome = "expired"
        assertEquals("The approval request expired.", (connect() as ConnectOutcome.Failed).reason)
    }

    @Test
    fun `a server without a text model is rejected, an unreachable one explained`() {
        server.textModel = null
        assertTrue((connect() as ConnectOutcome.Failed).reason.contains("no text model"))
        val dead = ServerConnector(tokens).connect("127.0.0.1", 1) as ConnectOutcome.Failed
        assertTrue(dead.reason.contains("Could not reach"))
    }

    @Test
    fun `parses server addresses`() {
        assertEquals("box" to 8080, ServerConnector.parseAddress("box"))
        assertEquals("box" to 9000, ServerConnector.parseAddress("box:9000"))
        assertEquals("192.168.1.5" to 8080, ServerConnector.parseAddress("http://192.168.1.5"))
        assertEquals("box" to 1234, ServerConnector.parseAddress("http://box:1234/v1"))
        assertEquals("::1" to 7000, ServerConnector.parseAddress("[::1]:7000"))
        assertNull(ServerConnector.parseAddress(""))
        assertNull(ServerConnector.parseAddress("box:abc"))
        assertNull(ServerConnector.parseAddress("box:99999"))
        assertNull(ServerConnector.parseAddress(":8080"))
    }

    @Test
    fun `the token store round-trips, keeps a stable client id and restricts the file`() {
        assertNull(tokens.get("h", 1))
        tokens.put("h", 1, "secret")
        tokens.put("h", 2, "other")
        assertEquals("secret", tokens.get("h", 1))
        tokens.put("h", 1, null)
        assertNull(tokens.get("h", 1))
        assertEquals("other", tokens.get("h", 2))
        val id = tokens.clientId()
        assertEquals(id, tokens.clientId())
        assertEquals(id, FileTokenStore(File(tmp.root, "cli/tokens.properties")).clientId())
        val perms = runCatching { java.nio.file.Files.getPosixFilePermissions(File(tmp.root, "cli/tokens.properties").toPath()) }.getOrNull()
        if (perms != null) assertEquals(setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE), perms)
    }
}
