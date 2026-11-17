package com.llamatik.agent.backends.remote

import com.llamatik.agent.backends.testing.FakeLlamatikServer
import com.llamatik.agent.backends.testing.FakeLlamatikServer.Companion.delta
import com.llamatik.agent.backends.testing.FakeLlamatikServer.Companion.done
import com.llamatik.agent.backends.testing.FakeLlamatikServer.Companion.error
import com.llamatik.agent.backends.testing.FakeLlamatikServer.StreamReply
import com.llamatik.network.client.RemoteInferenceException
import com.llamatik.network.client.StreamExpiredException
import com.llamatik.network.client.StreamRetryPolicy
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * The adapter over the library's server client, against an in-process server: status probing, the
 * chat-template call, and the library's resumable stream as the hosts rely on it.
 */
class ServerApiTest {
    private lateinit var server: FakeLlamatikServer

    @Before fun setUp() { server = FakeLlamatikServer() }
    @After fun tearDown() = server.close()

    private val fast = StreamRetryPolicy(maxAttempts = 3, baseDelayMs = 10, maxDelayMs = 20)
    private fun api(token: String? = null, port: Int = server.port) = ServerApi(ServerEndpoint("127.0.0.1", port, token), fast)

    @Test
    fun `probe reads the servers capabilities`() {
        val probe = api().probe()
        assertTrue(probe is ServerProbe.Ok)
        val caps = (probe as ServerProbe.Ok).capabilities
        assertTrue(caps.hasText)
        assertEquals("qwen.gguf", caps.textModel)
        assertEquals("instruct", caps.finetuneType)
        assertTrue(caps.hasChatTemplate)
        assertFalse(caps.hasImage)
        assertFalse(caps.hasAudio)
    }

    @Test
    fun `probe tells needs-approval from unreachable, and a token gets in`() {
        server.requiredToken = "secret"
        assertTrue(api().probe() is ServerProbe.Unauthorized)
        assertTrue(api("secret").probe() is ServerProbe.Ok)
        assertTrue(api("wrong").probe() is ServerProbe.Unauthorized)
        val dead = ServerApi(ServerEndpoint("127.0.0.1", 1)).probe()
        assertTrue(dead is ServerProbe.Unreachable)
    }

    @Test
    fun `a server without a text model is still probed successfully`() {
        server.textModel = null
        val caps = (api().probe() as ServerProbe.Ok).capabilities
        assertFalse(caps.hasText)
        assertNull(caps.textModel)
    }

    @Test
    fun `chat template is rendered by the server, null when its model has none`() = runBlocking {
        assertEquals(server.template, api().applyChatTemplate(listOf("user" to "hi")))
        server.template = null
        assertNull(api().applyChatTemplate(listOf("user" to "hi")))
    }

    @Test
    fun `a failed template request is an error, not a missing template`() = runBlocking {
        try { ServerApi(ServerEndpoint("127.0.0.1", 1)).applyChatTemplate(listOf("user" to "hi")); fail("expected an IOException") }
        catch (_: IOException) {}
        server.requiredToken = "secret"
        try { api().applyChatTemplate(listOf("user" to "hi")); fail("expected ServerUnauthorizedException") }
        catch (_: ServerUnauthorizedException) {}
    }

    @Test
    fun `sampling parameters reach the server and cancel is accepted`() = runBlocking {
        assertTrue(api().updateParams(0.2f, 99, 2048, 3, true, false, 256, -1))
        assertTrue(server.paramBodies.single().contains("\"maxTokens\":99"))
        assertTrue(api().cancelGeneration())
        assertEquals(1, server.cancelCalls.get())
    }

    @Test
    fun `a stream that drops is resumed with the same id and position, losing nothing`() = runBlocking {
        server.streamReplies += StreamReply.Events(listOf(delta(1, "Hel"), delta(2, "lo ")))
        server.streamReplies += StreamReply.Events(listOf(delta(3, "world"), done(4)))
        assertEquals("Hello world", api().streamGenerate("p").toList().joinToString(""))
        assertEquals(server.streamRequests[0].streamId, server.streamRequests[1].streamId)
        assertEquals("2", server.streamRequests[1].lastEventId)
    }

    @Test
    fun `a stream the server cannot resume is reported as expired`() {
        server.streamReplies += StreamReply.Events(listOf(delta(1, "x")))
        server.streamReplies += StreamReply.Gone
        try { runBlocking { api().streamGenerate("p").toList() }; fail("expected StreamExpiredException") }
        catch (_: StreamExpiredException) {}
    }

    @Test
    fun `a server error event ends the stream without retrying`() {
        server.streamReplies += StreamReply.Events(listOf(error(1, "out of memory")))
        try { runBlocking { api().streamGenerate("p").toList() }; fail("expected RemoteInferenceException") }
        catch (e: RemoteInferenceException) { assertTrue(e.message!!.contains("out of memory")) }
        assertEquals(1, server.streamRequests.size)
    }
}
