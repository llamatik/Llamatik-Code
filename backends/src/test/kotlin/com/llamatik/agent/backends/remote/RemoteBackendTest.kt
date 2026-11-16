package com.llamatik.agent.backends.remote

import com.llamatik.agent.backends.testing.FakeLlamatikServer
import com.llamatik.agent.backends.testing.FakeLlamatikServer.Companion.delta
import com.llamatik.agent.backends.testing.FakeLlamatikServer.Companion.done
import com.llamatik.agent.backends.testing.FakeLlamatikServer.Companion.error
import com.llamatik.agent.backends.testing.FakeLlamatikServer.StreamReply
import com.google.gson.JsonParser
import com.llamatik.agent.core.backend.BackendConfig
import com.llamatik.agent.core.backend.GenerationCallbacks
import com.llamatik.agent.core.backend.GenerationRequest
import com.llamatik.agent.core.model.ChatMessage
import com.llamatik.agent.core.model.ChatRole
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RemoteBackendTest {
    private lateinit var server: FakeLlamatikServer

    @Before fun setUp() { server = FakeLlamatikServer() }
    @After fun tearDown() = server.close()

    private class Run {
        val tokens = StringBuffer()
        @Volatile var completed: String? = null
        @Volatile var error: String? = null
        @Volatile var errorCause: Throwable? = null
        val finished = CountDownLatch(1)
        val callbacks = object : GenerationCallbacks {
            override fun onToken(token: String) { tokens.append(token) }
            override fun onCompleted(fullText: String) { completed = fullText; finished.countDown() }
            override fun onError(message: String, throwable: Throwable?) { error = message; errorCause = throwable; finished.countDown() }
        }
        fun await(seconds: Long = 20) = assertTrue("generation did not finish", finished.await(seconds, TimeUnit.SECONDS))
    }

    private fun backend(): RemoteBackend {
        val caps = (LlamatikServerClient(ServerEndpoint("127.0.0.1", server.port)).probe() as ServerProbe.Ok).capabilities
        return RemoteBackend(StaticServerLink(ServerEndpoint("127.0.0.1", server.port), caps, "box"))
    }

    private val config = BackendConfig("m", 0.5, 64, 4096, 2, true, false, 512, 0, true)
    private fun request(maxTokens: Int = 64, noThink: Boolean = false, prefill: String = "") = GenerationRequest(
        systemPrompt = "sys", messages = listOf(ChatMessage(role = ChatRole.USER, content = "hi")),
        config = config.copy(maxTokens = maxTokens), noThink = noThink, assistantPrefill = prefill,
    )

    @Test
    fun `streams a reply built from the servers chat template and sets sampling parameters`() {
        server.streamReplies += StreamReply.Events(listOf(delta(1, "Hel"), delta(2, "lo"), done(3)))
        val run = Run()
        backend().generate(request(), run.callbacks)
        run.await()
        assertEquals("Hello", run.completed)
        assertEquals("Hello", run.tokens.toString())
        val prompt = JsonParser.parseString(server.streamRequests.single().body).asJsonObject.get("prompt").asString
        assertTrue(prompt.contains("<|im_start|>user"))
        assertEquals(64, JsonParser.parseString(server.paramBodies.single()).asJsonObject.get("maxTokens").asInt)
    }

    @Test
    fun `think and prefill are appended to the rendered prompt and stop markers end the reply`() {
        server.streamReplies += StreamReply.Events(listOf(delta(1, "answer"), delta(2, "<|im_end|>"), delta(3, "ignored"), done(4)))
        val run = Run()
        backend().generate(request(noThink = true, prefill = "<pre>"), run.callbacks)
        run.await()
        assertEquals("answer", run.completed)
        val prompt = JsonParser.parseString(server.streamRequests.single().body).asJsonObject.get("prompt").asString
        assertTrue(prompt.endsWith("<think>\n\n</think>\n<pre>"))
        val until = System.currentTimeMillis() + 5_000
        while (server.cancelCalls.get() == 0 && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue("the server is told to stop generating", server.cancelCalls.get() >= 1)
    }

    @Test
    fun `a dropped connection resumes with the same stream id and last event id, losing and duplicating nothing`() {
        // First connection delivers two events and closes without "done"; the retry replays the rest.
        server.streamReplies += StreamReply.Events(listOf(delta(1, "Hel"), delta(2, "lo ")))
        server.streamReplies += StreamReply.Events(listOf(delta(3, "world"), done(4)))
        val run = Run()
        backend().generate(request(), run.callbacks)
        run.await()
        assertEquals("Hello world", run.completed)
        assertEquals("Hello world", run.tokens.toString())
        assertEquals(2, server.streamRequests.size)
        assertEquals(server.streamRequests[0].streamId, server.streamRequests[1].streamId)
        assertEquals(null, server.streamRequests[0].lastEventId)
        assertEquals("2", server.streamRequests[1].lastEventId)
    }

    @Test
    fun `an expired stream restarts and only the final text is authoritative`() {
        server.streamReplies += StreamReply.Events(listOf(delta(1, "partial ")))
        server.streamReplies += StreamReply.Gone
        server.streamReplies += StreamReply.Events(listOf(delta(1, "fresh answer"), done(2)))
        val run = Run()
        backend().generate(request(), run.callbacks)
        run.await()
        assertEquals("fresh answer", run.completed)
        // Text already streamed cannot be taken back, so the restarted run is not streamed again.
        assertEquals("partial ", run.tokens.toString())
        assertEquals(3, server.streamRequests.size)
        assertFalse(server.streamRequests[0].streamId == server.streamRequests[2].streamId)
    }

    @Test
    fun `a server error is reported and not retried`() {
        server.streamReplies += StreamReply.Events(listOf(delta(1, "x"), error(2, "out of memory")))
        val run = Run()
        backend().generate(request(), run.callbacks)
        run.await()
        assertEquals(null, run.completed)
        assertTrue(run.error!!.contains("out of memory"))
        assertEquals(1, server.streamRequests.size)
    }

    @Test
    fun `the token budget ends the reply and tells the server to stop`() {
        server.streamReplies += StreamReply.Events(listOf(delta(1, "a"), delta(2, "b"), delta(3, "c"), done(4)))
        val run = Run()
        backend().generate(request(maxTokens = 2), run.callbacks)
        run.await()
        assertEquals("ab", run.completed)
        val until = System.currentTimeMillis() + 5_000
        while (server.cancelCalls.get() == 0 && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue(server.cancelCalls.get() >= 1)
    }

    @Test
    fun `cancelling stops delivery and cancels on the server`() {
        server.streamReplies += StreamReply.Events(listOf(delta(1, "a")))
        val run = Run()
        val handle = backend().generate(request(), run.callbacks)
        handle.cancel()
        assertTrue(handle.isCancelled)
        val until = System.currentTimeMillis() + 5_000
        while (server.cancelCalls.get() == 0 && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue(server.cancelCalls.get() >= 1)
        assertEquals(null, run.completed)
        assertEquals(null, run.error)
    }

    @Test
    fun `an unreachable server keeps retrying quietly and stops when cancelled`() {
        val link = StaticServerLink(ServerEndpoint("127.0.0.1", 1), ServerCapabilities(hasText = true), "dead")
        val run = Run()
        val handle = RemoteBackend(link).generate(request(), run.callbacks)
        Thread.sleep(1500)
        assertEquals("still retrying, no error yet", null, run.error)
        handle.cancel()
        Thread.sleep(300)
        assertEquals(null, run.completed)
        assertEquals(null, run.error)
    }

    @Test
    fun `labels and capabilities come from the link`() {
        val b = backend()
        assertEquals("qwen.gguf @ box", b.selectedModelLabel())
        assertTrue(b.isModelLoaded())
        assertFalse(b.isBaseModel())
    }
}
