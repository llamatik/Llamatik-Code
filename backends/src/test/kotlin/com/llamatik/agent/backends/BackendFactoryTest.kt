package com.llamatik.agent.backends

import com.llamatik.agent.backends.remote.FileTokenStore
import com.llamatik.agent.backends.testing.FakeLlamatikServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BackendFactoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val tokens by lazy { FileTokenStore(File(tmp.root, "t.properties")) }

    private fun failure(model: String?, server: String?): String {
        try {
            BackendFactory.create(model, server, InferenceParams(), tokens)
            fail("expected a BackendException")
        } catch (e: BackendException) {
            return e.message!!
        }
        error("unreachable")
    }

    @Test
    fun `explains what is missing or wrong`() {
        assertTrue(failure(null, null).contains("--model"))
        assertTrue(failure("a.gguf", "box").contains("not both"))
        assertTrue(failure("/nope/model.gguf", null).contains("does not exist"))
        assertTrue(failure(tmp.newFile("model.bin").path, null).contains(".gguf"))
        assertTrue(failure(null, "box:abc").contains("Invalid server address"))
        assertTrue(failure(null, "127.0.0.1:1").contains("Could not reach"))
    }

    @Test
    fun `a local model path is validated before any native code is loaded`() {
        val dir = tmp.newFolder("models")
        assertTrue(failure(dir.absolutePath + "/x.gguf", null).contains("does not exist"))
        File(dir, "dir.gguf").mkdirs()
        assertTrue(failure(File(dir, "dir.gguf").absolutePath, null).contains("not a file"))
    }

    @Test
    fun `a server backend describes itself and uses the inference parameters`() {
        FakeLlamatikServer().use { server ->
            val params = InferenceParams(temperature = 0.1, maxTokens = 99, contextSize = 2048, threads = 2)
            BackendFactory.create(null, "127.0.0.1:${server.port}", params, tokens).use { handle ->
                assertTrue(handle.description.contains("qwen.gguf"))
                assertEquals(99, handle.config.maxTokens)
                assertEquals(2048, handle.config.contextLength)
                assertEquals(0.1, handle.config.temperature, 0.0)
            }
        }
    }
}
