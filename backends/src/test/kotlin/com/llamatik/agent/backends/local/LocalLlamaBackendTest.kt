package com.llamatik.agent.backends.local

import com.llamatik.agent.core.backend.BackendConfig
import com.llamatik.agent.core.backend.GenerationCallbacks
import com.llamatik.agent.core.backend.GenerationRequest
import com.llamatik.agent.core.backend.SessionTag
import com.llamatik.agent.core.model.ChatMessage
import com.llamatik.agent.core.model.ChatRole
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LocalLlamaBackendTest {
    @get:Rule val tmp = TemporaryFolder()
    private val opened = mutableListOf<LocalLlamaBackend>()

    @After fun release() = opened.forEach { it.close() } // a loaded model must be freed before the JVM exits

    private fun backend(fixed: String? = null, options: LocalLlamaBackend.Options = LocalLlamaBackend.Options()) =
        LocalLlamaBackend(fixed, options).also { opened += it }

    /** A small instruct model for real inference; the tests needing it are skipped when there is none. */
    private val model: File? = listOfNotNull(
        System.getenv("LLAMATIK_TEST_MODEL"),
        System.getProperty("user.home") + "/.llamatik/models/Gemma_3_1B_Instruct_Q4_KM.gguf",
    ).map(::File).firstOrNull { it.isFile }

    private fun config(model: String, ctx: Int = 2048, maxTokens: Int = 24) =
        BackendConfig(model, 0.0, maxTokens, ctx, 2, true, false, 512, -1, true)

    private fun request(model: String, text: String, tag: SessionTag = SessionTag.AGENT, cfg: BackendConfig = config(model)) =
        GenerationRequest("You are a terse assistant.", listOf(ChatMessage(role = ChatRole.USER, content = text)), cfg, sessionTag = tag)

    private class Result {
        val latch = CountDownLatch(1)
        @Volatile var text: String? = null
        @Volatile var error: String? = null
        val callbacks = object : GenerationCallbacks {
            override fun onToken(token: String) {}
            override fun onCompleted(fullText: String) { text = fullText; latch.countDown() }
            override fun onError(message: String, throwable: Throwable?) { error = message; latch.countDown() }
        }
        fun await() = assertTrue("generation did not finish", latch.await(180, TimeUnit.SECONDS))
    }

    // ── Validation: no native code, no model needed ──────────────────────────

    @Test
    fun `model paths are validated with readable messages`() {
        fun failure(path: String, hint: String = "NO MODEL"): String = try {
            LocalLlamaBackend.validateModelPath(path, hint); fail("expected a failure for '$path'"); ""
        } catch (e: IllegalArgumentException) { e.message!! }
        assertEquals("NO MODEL", failure("  "))
        assertTrue(failure("org/repo").contains("repository identifier"))
        assertTrue(failure("/nope/x.gguf").contains("does not exist"))
        assertTrue(failure(tmp.newFile("m.bin").path).contains(".gguf"))
        assertTrue(failure(tmp.newFolder("d.gguf").path).contains("not a file"))
        val ok = tmp.newFile("ok.gguf")
        assertEquals(ok.canonicalFile.toPath(), LocalLlamaBackend.validateModelPath(ok.path).toRealPath())
    }

    @Test
    fun `a fixed model is validated up front and labels use the hosts wording`() {
        try { backend("/nope/x.gguf"); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("does not exist")) }
        val file = tmp.newFile("tiny.gguf")
        assertEquals("tiny.gguf", backend(file.path).selectedModelLabel())
        val labelled = backend(null, LocalLlamaBackend.Options(messages = LocalLlamaBackend.Messages(noModelLabel = "Nothing loaded")))
        assertEquals("Nothing loaded", labelled.selectedModelLabel())
        assertFalse(labelled.isModelLoaded())
    }

    @Test
    fun `invalid settings are reported before any model is touched, with the hosts hint`() {
        val file = tmp.newFile("tiny.gguf")
        val hinted = backend(file.path, LocalLlamaBackend.Options(messages = LocalLlamaBackend.Messages(settingsHint = " See Settings.")))
        val result = Result()
        hinted.generate(request(file.path, "hi", cfg = config(file.path, ctx = 100)), result.callbacks)
        result.await()
        assertEquals("Context size must be at least 512 tokens (current: 100). See Settings.", result.error)
        assertNull(result.text)
    }

    // ── Real inference ───────────────────────────────────────────────────────

    @Test
    fun `generates with a real model and reports its name`() {
        assumeTrue("no test model available", model != null)
        val b = backend(model!!.path)
        val r = Result()
        b.generate(request(model.path, "Reply with the single word: pong"), r.callbacks)
        r.await()
        assertNull(r.error)
        assertTrue("reply was '${r.text}'", !r.text.isNullOrBlank())
        assertTrue(b.isModelLoaded())
        assertEquals(model.name, b.selectedModelLabel())
    }

    @Test
    fun `a model chosen per request can be unloaded and loads again on the next request`() {
        assumeTrue("no test model available", model != null)
        val b = backend(null, LocalLlamaBackend.Options(executorPerSession = true))
        val first = Result()
        b.generate(request(model!!.path, "Say hi."), first.callbacks); first.await()
        assertNull(first.error)
        assertTrue(b.isModelLoaded())

        b.unload()
        val until = System.currentTimeMillis() + 20_000
        while (b.isModelLoaded() && System.currentTimeMillis() < until) Thread.sleep(50)
        assertFalse("unload should free the model", b.isModelLoaded())

        val second = Result()
        b.generate(request(model.path, "Say hi again."), second.callbacks); second.await()
        assertNull(second.error)
        assertTrue(b.isModelLoaded())
    }

    @Test
    fun `agent and chat requests run on separate sessions without interfering`() {
        assumeTrue("no test model available", model != null)
        val b = backend(null, LocalLlamaBackend.Options(executorPerSession = true))
        val agent = Result(); val chat = Result()
        b.generate(request(model!!.path, "Name a colour.", SessionTag.AGENT), agent.callbacks)
        b.generate(request(model.path, "Name an animal.", SessionTag.CHAT), chat.callbacks)
        agent.await(); chat.await()
        assertNull(agent.error); assertNull(chat.error)
        assertTrue(!agent.text.isNullOrBlank() && !chat.text.isNullOrBlank())
    }

    @Test
    fun `a prompt longer than the context is refused instead of crashing the native library`() {
        assumeTrue("no test model available", model != null)
        val b = backend(model!!.path)
        val r = Result()
        b.generate(request(model.path, "word ".repeat(5000), cfg = config(model.path, ctx = 512)), r.callbacks)
        r.await()
        assertTrue(r.error!!, r.error!!.contains("Prompt too long"))
    }
}
