package com.llamatik.agent.backends.remote

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

class LlamatikServerClientTest {
    private lateinit var server: HttpServer
    private val port get() = server.address.port
    private fun client(token: String? = null) = LlamatikServerClient(ServerEndpoint("127.0.0.1", port, token))

    @Volatile private var requireToken: String? = null
    @Volatile private var streamHeaders = mutableListOf<Map<String, String?>>()

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/models") { ex ->
            val required = requireToken
            if (required != null && ex.requestHeaders.getFirst("Authorization") != "Bearer $required") {
                ex.reply(401, "{}")
            } else {
                ex.reply(
                    200,
                    """{"generation":{"loaded":true,"model":"qwen.gguf","capabilities":{"finetuneType":"instruct","hasChatTemplate":true}},
                       "embedding":{"loaded":false},"whisper":{"loaded":true,"model":"w.bin"},
                       "stableDiffusion":{"loaded":false},"multimodal":{"loaded":true},"mtp":{"loaded":false},"extra":1}""",
                )
            }
        }
        server.createContext("/v1/generation/model/applyChatTemplate") { ex ->
            ex.reply(200, """{"prompt":"<|im_start|>user\nhi<|im_end|>\n"}""")
        }
        server.createContext("/v1/generation/stream") { ex ->
            streamHeaders += mapOf(
                "id" to ex.requestHeaders.getFirst("X-Stream-Id"),
                "last" to ex.requestHeaders.getFirst("Last-Event-ID"),
            )
            val last = ex.requestHeaders.getFirst("Last-Event-ID")?.toIntOrNull() ?: 0
            when {
                last == 99 -> ex.reply(410, "Stream expired")
                last == 0 -> ex.sse(false, "id: 1\ndata: {\"event\":\"delta\",\"text\":\"Hel\"}\n\n", "id: 2\ndata: {\"event\":\"delta\",\"text\":\"lo\"}\n\n")
                else -> ex.sse(
                    true,
                    "id: 3\ndata: {\"event\":\"delta\",\"text\":\" wor\\n\"}\n\n",
                    "id: 4\ndata: {\"event\":\"done\"}\n\n",
                )
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.stop(0)

    @Test
    fun probeParsesCapabilities() {
        val probe = client().probe()
        assertTrue(probe is ServerProbe.Ok)
        val caps = (probe as ServerProbe.Ok).capabilities
        assertTrue(caps.hasText)
        assertTrue(caps.hasAudio)
        assertTrue(caps.hasVision)
        assertFalse(caps.hasImage)
        assertEquals("qwen.gguf", caps.textModel)
        assertEquals("instruct", caps.finetuneType)
        assertTrue(caps.hasChatTemplate)
    }

    @Test
    fun probeReportsUnauthorizedThenAcceptsToken() {
        requireToken = "secret"
        assertTrue(client().probe() is ServerProbe.Unauthorized)
        assertTrue(client("secret").probe() is ServerProbe.Ok)
    }

    @Test
    fun probeReportsUnreachable() {
        val dead = LlamatikServerClient(ServerEndpoint("127.0.0.1", 1))
        assertTrue(dead.probe() is ServerProbe.Unreachable)
    }

    @Test
    fun appliesChatTemplate() {
        val prompt = client().applyChatTemplate(listOf("user" to "hi"))
        assertEquals("<|im_start|>user\nhi<|im_end|>\n", prompt)
    }

    @Test
    fun interruptedStreamResumesWithoutLosingOrDuplicatingText() {
        val text = StringBuilder()
        var received = 0
        val c = client()
        try {
            c.streamGenerate("p", "sid", received, AbortHandle(), 5_000, { text.append(it) }, { received = it })
            fail("expected the first attempt to be interrupted (no done event)")
        } catch (e: StreamInterruptedException) {
            // expected
        }
        assertEquals("Hello", text.toString())
        assertEquals(2, received)

        c.streamGenerate("p", "sid", received, AbortHandle(), 5_000, { text.append(it) }, { received = it })
        assertEquals("Hello wor\n", text.toString())
        assertEquals(4, received)
        assertEquals("sid", streamHeaders.last()["id"])
        assertEquals("2", streamHeaders.last()["last"])
        assertNull(streamHeaders.first()["last"])
    }

    @Test
    fun goneStreamIsReportedAsExpired() {
        try {
            client().streamGenerate("p", "sid", 99, AbortHandle(), 5_000, {}, {})
            fail("expected StreamExpiredException")
        } catch (e: StreamExpiredException) {
            // expected
        }
    }

    @Test
    fun serverWithoutResumeSupportIsTreatedAsExpired() {
        // The fake server answers resumed requests with X-Stream-Resume: 1; make one that doesn't.
        server.removeContext("/v1/generation/stream")
        server.createContext("/v1/generation/stream") { ex -> ex.sse(false, "id: 1\ndata: {\"event\":\"done\"}\n\n") }
        try {
            client().streamGenerate("p", "sid", 2, AbortHandle(), 5_000, {}, {})
            fail("expected StreamExpiredException")
        } catch (e: StreamExpiredException) {
            // expected
        }
    }

    @Test
    fun serverErrorEventIsNotRetryable() {
        server.removeContext("/v1/generation/stream")
        server.createContext("/v1/generation/stream") { ex ->
            ex.sse(true, "id: 1\ndata: {\"event\":\"error\",\"message\":\"boom\"}\n\n")
        }
        try {
            client().streamGenerate("p", "sid", 0, AbortHandle(), 5_000, {}, {})
            fail("expected RemoteInferenceException")
        } catch (e: RemoteInferenceException) {
            assertEquals("boom", e.message)
        }
    }

    private fun HttpExchange.reply(status: Int, body: String) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun HttpExchange.sse(resumeSupported: Boolean, vararg events: String) {
        responseHeaders.add("Content-Type", "text/event-stream")
        if (resumeSupported) responseHeaders.add("X-Stream-Resume", "1")
        sendResponseHeaders(200, 0)
        responseBody.use { out -> events.forEach { out.write(it.toByteArray()); out.flush() } }
    }
}
