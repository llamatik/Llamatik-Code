package com.llamatik.teammate.server

import com.llamatik.agent.backends.remote.RemoteBackend
import com.llamatik.agent.backends.remote.LlamatikServerClient
import com.llamatik.agent.backends.remote.ServerCapabilities
import com.llamatik.agent.backends.remote.ServerEndpoint
import com.llamatik.agent.backends.remote.ServerProbe
import com.llamatik.agent.backends.remote.StaticServerLink
import com.llamatik.agent.backends.testing.FakeLlamatikServer
import com.llamatik.agent.backends.testing.FakeLlamatikServer.Companion.delta
import com.llamatik.agent.backends.testing.FakeLlamatikServer.Companion.done
import com.llamatik.agent.backends.testing.FakeLlamatikServer.StreamReply
import com.llamatik.agent.core.backend.BackendConfig
import com.llamatik.agent.core.backend.GenerationCallbacks
import com.llamatik.agent.core.backend.GenerationRequest
import com.llamatik.agent.core.model.ChatMessage
import com.llamatik.agent.core.model.ChatRole
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The plugin runs on the IDE platform's own coroutines, ktor and serialization, which are older than what the
 * Llamatik network library (ktor 3.6, coroutines 1.11) was built against: that library fails inside the IDE
 * (`NoSuchMethodError` on `runBlockingK` and on ktor's `TypeInfo`). So the server client in :backends must be plain
 * JDK code. These tests run it on the plugin's real classpath (the platform's jars plus the plugin's own
 * dependencies, nothing else) and guard against such a library creeping back in.
 */
class RemoteBackendOnPlatformTest {
    private lateinit var server: FakeLlamatikServer

    @Before fun setUp() { server = FakeLlamatikServer() }
    @After fun tearDown() = server.close()

    private fun classFile(className: String): String? =
        Class.forName(className).getResource("/" + className.replace('.', '/') + ".class")?.toString()

    @Test
    fun `the libraries on the plugin classpath are the platforms, and the network library is not there`() {
        val coroutines = classFile("kotlinx.coroutines.Job")!!
        assertTrue("coroutines loaded from $coroutines; expected the IDE platform's library", coroutines.contains("/idea-") || coroutines.contains("intellij"))
        // Not just "the platform's wins": nothing of ours may need a newer version at all.
        assertTrue("the Llamatik network library must not be a plugin dependency", runCatching { Class.forName("com.llamatik.network.client.LlamatikServerClient") }.isFailure)
        val coroutinesJars = System.getProperty("java.class.path").split(java.io.File.pathSeparator).filter { it.contains("kotlinx-coroutines-core") }
        assertTrue("Gradle's coroutines on the plugin classpath: $coroutinesJars", coroutinesJars.none { it.contains("modules-2") })
    }

    @Test
    fun `status probe works on the platform classpath`() {
        val probe = LlamatikServerClient(ServerEndpoint("127.0.0.1", server.port)).probe()
        assertTrue(probe.toString(), probe is ServerProbe.Ok)
        assertEquals("qwen.gguf", (probe as ServerProbe.Ok).capabilities.textModel)
    }

    @Test
    fun `a generation that drops mid-stream resumes on the platform classpath`() {
        server.streamReplies += StreamReply.Events(listOf(delta(1, "Hel"), delta(2, "lo ")))
        server.streamReplies += StreamReply.Events(listOf(delta(3, "from the IDE"), done(4)))
        val endpoint = ServerEndpoint("127.0.0.1", server.port)
        val link = StaticServerLink(endpoint, ServerCapabilities(hasText = true, textModel = "qwen.gguf"), "test")
        val finished = CountDownLatch(1)
        var completed: String? = null
        var error: String? = null
        RemoteBackend(link).generate(
            GenerationRequest(
                "sys", listOf(ChatMessage(role = ChatRole.USER, content = "hi")),
                BackendConfig("m", 0.5, 64, 4096, 2, true, false, 512, 0, true),
            ),
            object : GenerationCallbacks {
                override fun onToken(token: String) {}
                override fun onCompleted(fullText: String) { completed = fullText; finished.countDown() }
                override fun onError(message: String, throwable: Throwable?) { error = message; finished.countDown() }
            },
        )
        assertTrue("generation did not finish", finished.await(30, TimeUnit.SECONDS))
        assertEquals(error, "Hello from the IDE", completed)
        assertEquals(2, server.streamRequests.size)
        assertEquals("2", server.streamRequests[1].lastEventId)
    }
}
