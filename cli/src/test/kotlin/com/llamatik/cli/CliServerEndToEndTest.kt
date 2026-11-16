package com.llamatik.cli

import com.llamatik.agent.backends.BackendFactory
import com.llamatik.agent.backends.remote.FileTokenStore
import com.llamatik.agent.backends.testing.FakeLlamatikServer
import com.llamatik.agent.backends.testing.FakeLlamatikServer.Companion.delta
import com.llamatik.agent.backends.testing.FakeLlamatikServer.Companion.done
import com.llamatik.agent.backends.testing.FakeLlamatikServer.StreamReply
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The whole CLI against an in-process Llamatik server: pairing, streaming, tools, edit. */
class CliServerEndToEndTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: FakeLlamatikServer

    @Before fun setUp() { server = FakeLlamatikServer() }
    @After fun tearDown() = server.close()

    private fun runCli(options: CliOptions, terminal: Terminal): Int {
        val tokens = FileTokenStore(File(tmp.root, "tokens.properties"))
        return CliApp(options, terminal) {
            BackendFactory.create(options.model, options.server, options.params, tokens, onWaitingForApproval = { terminal.println("waiting for approval") })
        }.run()
    }

    @Test
    fun `answers a question using a paired server`() {
        server.requiredToken = "device-token"
        server.streamReplies += StreamReply.Events(listOf(delta(1, "The project is "), delta(2, "a tiny demo of the terminal agent."), done(3)))
        val root = tmp.newFolder("proj").also { File(it, "README.md").writeText("# Demo\n") }
        val terminal = ScriptedTerminal()
        val code = runCli(CliOptions(server = "127.0.0.1:${server.port}", prompt = "what is this project?", workDir = root.toPath()), terminal)
        assertEquals(terminal.err.toString(), 0, code)
        assertTrue(terminal.out.contains("waiting for approval"))
        assertTrue(terminal.out.contains("The project is a tiny demo of the terminal agent."))
        assertEquals("the device was approved exactly once", 1, server.pairingRequests.size)
    }

    @Test
    fun `reads a file and edits it through the server`() {
        val root = tmp.newFolder("proj").also { File(it, "Calc.kt").writeText("fun add(a: Int, b: Int) = a - b\n") }
        server.streamReplies += StreamReply.Events(listOf(
            delta(1, """<tool_call name="read_file"><arg name="path">Calc.kt</arg></tool_call>"""), done(2),
        ))
        server.streamReplies += StreamReply.Events(listOf(
            delta(1, """<tool_call name="replace_in_file"><arg name="path">Calc.kt</arg><arg name="oldText">a - b</arg><arg name="newText">a + b</arg></tool_call>"""), done(2),
        ))
        val terminal = ScriptedTerminal("y")
        val code = runCli(CliOptions(server = "127.0.0.1:${server.port}", prompt = "fix the add function in Calc.kt", workDir = root.toPath()), terminal)
        assertEquals(terminal.err.toString(), 0, code)
        assertEquals("fun add(a: Int, b: Int) = a + b\n", File(root, "Calc.kt").readText())
        assertTrue(terminal.out.contains("→ read_file"))
        assertTrue(terminal.out.contains("+fun add(a: Int, b: Int) = a + b"))
    }

    @Test
    fun `an unreachable server is a startup error, not a hang`() {
        val terminal = ScriptedTerminal()
        val code = runCli(CliOptions(server = "127.0.0.1:1", prompt = "hi"), terminal)
        assertEquals(2, code)
        assertTrue(terminal.err.contains("Could not reach"))
    }
}
