package com.llamatik.teammate.server

import com.llamatik.agent.backends.remote.RemoteBackend
import com.llamatik.agent.backends.remote.ServerApi
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
 * The plugin does not bundle coroutines, serialization or ktor: it runs on the IDE platform's copies. This
 * test runs the Llamatik server client and remote backend on the plugin's own classpath (the platform's jars
 * plus the plugin's dependencies) to prove that combination works, including a resumed stream.
 */
class RemoteBackendOnPlatformTest {
    private lateinit var server: FakeLlamatikServer

    @Before fun setUp() { server = FakeLlamatikServer() }
    @After fun tearDown() = server.close()

    /** The jar a class is loaded from (the IDE's test class loader reports no code source, so ask for the class file). */
    private fun sourceOf(className: String): String =
        Class.forName(className).getResource("/" + className.replace('.', '/') + ".class")!!.toString()

    @Test
    fun `the HTTP and coroutine libraries come from the IDE platform, not from the plugin`() {
        // If these resolved to Gradle's copies this test would prove nothing about the packaged plugin.
        listOf("kotlinx.coroutines.Job", "kotlinx.serialization.json.Json", "io.ktor.client.HttpClient").forEach { cls ->
            val path = sourceOf(cls)
            assertTrue("$cls loaded from $path; expected the IDE platform's library", path.contains("/idea-") || path.contains("intellij"))
        }
        // The library under test is the plugin's own dependency.
        assertTrue(sourceOf("com.llamatik.network.client.LlamatikServerClient").contains("network-jvm"))
    }

    @Test
    fun `status probe works on the platform classpath`() {
        val probe = ServerApi(ServerEndpoint("127.0.0.1", server.port)).probe()
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
