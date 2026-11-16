package com.llamatik.cli

import com.llamatik.agent.backends.BackendHandle
import com.llamatik.agent.backends.InferenceParams
import com.llamatik.agent.core.backend.AssistantBackend
import com.llamatik.agent.core.backend.GenerationCallbacks
import com.llamatik.agent.core.backend.GenerationHandle
import com.llamatik.agent.core.backend.GenerationRequest
import com.llamatik.agent.core.testing.FakeBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CliAppTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun call(name: String, vararg args: Pair<String, String>) =
        "<tool_call name=\"$name\">" + args.joinToString("") { "<arg name=\"${it.first}\">${it.second}</arg>" } + "</tool_call>"

    private fun handle(backend: AssistantBackend) =
        BackendHandle(backend, InferenceParams(contextSize = 4096, maxTokens = 512, threads = 1).toConfig("fake"), "fake backend")

    private fun app(options: CliOptions, terminal: Terminal, backend: AssistantBackend) =
        CliApp(options, terminal) { handle(backend) }

    private fun project(vararg files: Pair<String, String>): File {
        val root = tmp.newFolder("project")
        files.forEach { (path, text) -> File(root, path).apply { parentFile.mkdirs(); writeText(text) } }
        return root
    }

    @Test
    fun `help and version need no backend`() {
        val t = ScriptedTerminal()
        assertEquals(0, CliApp(CliOptions(help = true), t) { error("no backend expected") }.run())
        assertTrue(t.out.contains("Usage: llamatik"))
        val v = ScriptedTerminal()
        assertEquals(0, CliApp(CliOptions(version = true), v) { error("no backend expected") }.run())
        assertTrue(v.out.startsWith("llamatik "))
    }

    @Test
    fun `a missing model is a clear error and exit code 2`() {
        val t = ScriptedTerminal()
        val code = CliApp(CliOptions(prompt = "hi"), t) { throw com.llamatik.agent.backends.BackendException("No model to run.") }.run()
        assertEquals(2, code)
        assertTrue(t.err.contains("No model to run."))
    }

    @Test
    fun `a question prints the answer and the tools used`() {
        val root = project("src/Calc.kt" to "fun add(a: Int, b: Int) = a - b\n")
        val t = ScriptedTerminal()
        val backend = FakeBackend(call("read_file", "path" to "src/Calc.kt"), "The add function subtracts instead of adding, which is a bug.")
        val code = app(CliOptions(prompt = "what does src/Calc.kt do?", workDir = root.toPath()), t, backend).run()
        assertEquals(0, code)
        assertTrue(t.out.contains("→ read_file"))
        assertTrue(t.out.contains("The add function subtracts instead of adding, which is a bug."))
    }

    @Test
    fun `an approved edit is written to disk after showing the diff`() {
        val root = project("src/Calc.kt" to "fun add(a: Int, b: Int) = a - b\n")
        val t = ScriptedTerminal("y")
        val backend = FakeBackend(call("write_file", "path" to "src/Calc.kt", "content" to "fun add(a: Int, b: Int) = a + b\n"))
        val code = app(CliOptions(prompt = "fix src/Calc.kt", workDir = root.toPath()), t, backend).run()
        assertEquals(0, code)
        assertEquals("fun add(a: Int, b: Int) = a + b\n", File(root, "src/Calc.kt").readText())
        assertTrue(t.out.contains("-fun add(a: Int, b: Int) = a - b"))
        assertTrue(t.out.contains("+fun add(a: Int, b: Int) = a + b"))
        assertTrue(t.out.contains("Updated src/Calc.kt"))
    }

    @Test
    fun `a declined edit leaves the file alone`() {
        val root = project("src/Calc.kt" to "fun add(a: Int, b: Int) = a - b\n")
        val backend = FakeBackend(call("write_file", "path" to "src/Calc.kt", "content" to "fun add(a: Int, b: Int) = a + b\n"))
        val t = ScriptedTerminal("n")
        app(CliOptions(prompt = "fix src/Calc.kt", workDir = root.toPath()), t, backend).run()
        assertEquals("fun add(a: Int, b: Int) = a - b\n", File(root, "src/Calc.kt").readText())
        assertTrue(t.out.contains("Skipped src/Calc.kt"))
    }

    @Test
    fun `without a person to ask, edits are skipped rather than applied`() {
        val root = project("src/Calc.kt" to "old\n")
        val backend = FakeBackend(call("write_file", "path" to "src/Calc.kt", "content" to "class Calc\n"))
        val t = ScriptedTerminal(interactive = false) // no answers: end of input
        app(CliOptions(prompt = "fix src/Calc.kt", workDir = root.toPath()), t, backend).run()
        assertEquals("old\n", File(root, "src/Calc.kt").readText())
    }

    @Test
    fun `--yes applies edits without asking`() {
        val root = project("src/Calc.kt" to "old\n")
        val backend = FakeBackend(call("create_file", "path" to "src/New.kt", "content" to "class New\n"))
        val t = ScriptedTerminal()
        val code = app(CliOptions(prompt = "create src/New.kt", workDir = root.toPath(), yes = true), t, backend).run()
        assertEquals(0, code)
        assertEquals("class New\n", File(root, "src/New.kt").readText())
        assertTrue("nothing should have been asked", t.prompts.isEmpty())
    }

    @Test
    fun `a backend error is reported with exit code 1`() {
        val failing = object : AssistantBackend {
            override fun isModelLoaded() = true
            override fun selectedModelLabel() = "x"
            override fun generate(request: GenerationRequest, callbacks: GenerationCallbacks): GenerationHandle {
                callbacks.onError("model exploded", null)
                return object : GenerationHandle { override fun cancel() {}; override val isCancelled = false }
            }
        }
        val t = ScriptedTerminal()
        assertEquals(1, app(CliOptions(prompt = "hi", workDir = project().toPath()), t, failing).run())
        assertTrue(t.err.contains("model exploded"))
    }

    @Test
    fun `an interactive session answers several prompts, keeps context and understands slash commands`() {
        val root = project("README.md" to "# Demo\n")
        val backend = FakeBackend(
            "This project is a small demo used for testing the terminal agent.",
            "Yes, the readme only has a title and nothing else in it right now.",
        )
        val t = ScriptedTerminal("what is this project?", "/help", "is there a readme?", "/clear", "/bogus", "/yes", "/exit")
        val code = app(CliOptions(workDir = root.toPath()), t, backend).run()
        assertEquals(0, code)
        assertTrue(t.out.contains("small demo used for testing"))
        assertTrue(t.out.contains("readme only has a title"))
        assertTrue(t.out.contains("Conversation cleared."))
        assertTrue(t.out.contains("Unknown command: /bogus"))
        assertTrue(t.out.contains("Auto-approve is ON."))
        // The second task saw the first one in its history.
        val secondRequest = backend.capturedRequests[1]
        assertTrue(secondRequest.messages.any { it.content.contains("what is this project?") })
    }

    @Test
    fun `end of input leaves the session`() {
        val t = ScriptedTerminal()
        assertEquals(0, app(CliOptions(workDir = project().toPath()), t, FakeBackend()).run())
    }

    @Test
    fun `cancelling stops a running task`() {
        val started = CountDownLatch(1)
        val hanging = object : AssistantBackend {
            override fun isModelLoaded() = true
            override fun selectedModelLabel() = "slow"
            override fun generate(request: GenerationRequest, callbacks: GenerationCallbacks): GenerationHandle {
                started.countDown() // never completes
                return object : GenerationHandle { override fun cancel() {}; override val isCancelled = false }
            }
        }
        val t = ScriptedTerminal()
        val app = app(CliOptions(prompt = "think forever", workDir = project().toPath()), t, hanging)
        var code = -1
        val runner = Thread { code = app.run() }.also { it.start() }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertTrue(app.cancelCurrent())
        runner.join(5_000)
        assertFalse(runner.isAlive)
        assertEquals(1, code)
        assertTrue(t.out.contains("Cancelled."))
        assertFalse("nothing is running any more", app.cancelCurrent())
    }
}
