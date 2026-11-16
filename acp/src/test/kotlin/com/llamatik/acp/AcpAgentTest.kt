package com.llamatik.acp

import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.llamatik.agent.core.backend.AssistantBackend
import com.llamatik.agent.core.backend.GenerationCallbacks
import com.llamatik.agent.core.backend.GenerationHandle
import com.llamatik.agent.core.backend.GenerationRequest
import com.llamatik.agent.core.testing.FakeBackend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The real SDK client against the agent, in memory: protocol, permissions, editor files and terminal. */
class AcpAgentTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun call(name: String, vararg args: Pair<String, String>) =
        "<tool_call name=\"$name\">" + args.joinToString("") { "<arg name=\"${it.first}\">${it.second}</arg>" } + "</tool_call>"

    private fun project(vararg files: Pair<String, String>): File {
        val root = tmp.newFolder("project").canonicalFile
        files.forEach { (path, text) -> File(root, path).apply { parentFile.mkdirs(); writeText(text) } }
        return root
    }

    private fun <T> test(block: suspend () -> T): T = runBlocking { withTimeout(60_000) { block() } }

    @Test
    fun `a question is answered and the turn ends`() = test {
        val root = project("README.md" to "# Demo\n")
        val backend = FakeBackend(call("read_file", "path" to "README.md"), "This project is only a tiny demo with a readme file.")
        AcpConnection.open { AcpConnection.handle(backend) }.use { acp ->
            val editor = FakeEditor(buffers = mutableMapOf("${root}/README.md" to "# Demo (unsaved)\n"))
            val turn = acp.newSession(root.path, editor).ask("what is this project?")
            assertEquals(StopReason.END_TURN, turn.response.stopReason)
            assertEquals("This project is only a tiny demo with a readme file.", turn.message)

            // The tool shows up as a call that starts and completes.
            val tool = turn.updates.filterIsInstance<SessionUpdate.ToolCall>().single()
            assertEquals("read_file: README.md", tool.title)
            assertEquals(ToolKind.READ, tool.kind)
            val done = turn.updates.filterIsInstance<SessionUpdate.ToolCallUpdate>().first { it.status == ToolCallStatus.COMPLETED }
            assertTrue(done.content!!.isNotEmpty())
            // The model saw the editor's unsaved buffer, not the disk.
            assertTrue(backend.capturedRequests[1].messages.first().content.contains("# Demo (unsaved)"))
        }
    }

    @Test
    fun `an approved edit is shown as a diff and written through the editor`() = test {
        val root = project("Calc.kt" to "fun add(a: Int, b: Int) = a - b\n")
        val backend = FakeBackend(call("write_file", "path" to "Calc.kt", "content" to "fun add(a: Int, b: Int) = a + b\n"))
        AcpConnection.open { AcpConnection.handle(backend) }.use { acp ->
            val editor = FakeEditor(buffers = mutableMapOf("$root/Calc.kt" to "fun add(a: Int, b: Int) = a - b\n"), permissionAnswers = listOf("allow"))
            val turn = acp.newSession(root.path, editor).ask("fix Calc.kt")
            assertEquals(StopReason.END_TURN, turn.response.stopReason)

            val edit = turn.updates.filterIsInstance<SessionUpdate.ToolCall>().single { it.kind == ToolKind.EDIT && it.title.startsWith("Edit") }
            val diff = edit.content.single() as ToolCallContent.Diff
            assertEquals("$root/Calc.kt", diff.path)
            assertEquals("fun add(a: Int, b: Int) = a - b\n", diff.oldText)
            assertEquals("fun add(a: Int, b: Int) = a + b\n", diff.newText)

            assertEquals(listOf("$root/Calc.kt" to "fun add(a: Int, b: Int) = a + b\n"), editor.written)
            assertEquals("the file on disk is the editor's to change", "fun add(a: Int, b: Int) = a - b\n", File(root, "Calc.kt").readText())
            assertTrue(editor.permissionRequests.single().second.any { it.optionId.value == "allow" })
        }
    }

    @Test
    fun `a rejected edit writes nothing`() = test {
        val root = project("Calc.kt" to "old\n")
        val backend = FakeBackend(call("write_file", "path" to "Calc.kt", "content" to "new content\n"))
        AcpConnection.open { AcpConnection.handle(backend) }.use { acp ->
            val editor = FakeEditor(buffers = mutableMapOf("$root/Calc.kt" to "old\n"), permissionAnswers = listOf("reject"))
            val turn = acp.newSession(root.path, editor).ask("fix Calc.kt")
            assertEquals(StopReason.END_TURN, turn.response.stopReason)
            assertTrue(editor.written.isEmpty())
            assertTrue(turn.updates.filterIsInstance<SessionUpdate.ToolCallUpdate>().any { it.status == ToolCallStatus.FAILED })
        }
    }

    @Test
    fun `--auto-approve applies edits without asking`() = test {
        val root = project("Calc.kt" to "old\n")
        val backend = FakeBackend(call("write_file", "path" to "Calc.kt", "content" to "new content\n"))
        AcpConnection.open(AcpOptions(autoApprove = true)) { AcpConnection.handle(backend) }.use { acp ->
            val editor = FakeEditor(buffers = mutableMapOf("$root/Calc.kt" to "old\n"))
            acp.newSession(root.path, editor).ask("fix Calc.kt")
            assertEquals(1, editor.written.size)
            assertTrue(editor.permissionRequests.isEmpty())
        }
    }

    @Test
    fun `without editor file access the agent reads and writes the disk itself`() = test {
        val root = project("Calc.kt" to "old\n")
        val backend = FakeBackend(call("write_file", "path" to "Calc.kt", "content" to "new content\n"))
        AcpConnection.open(AcpOptions(autoApprove = true), capabilities = com.agentclientprotocol.model.ClientCapabilities()) { AcpConnection.handle(backend) }.use { acp ->
            val editor = FakeEditor()
            acp.newSession(root.path, editor).ask("fix Calc.kt")
            assertTrue(editor.written.isEmpty())
            assertEquals("new content\n", File(root, "Calc.kt").readText())
        }
    }

    @Test
    fun `commands run in the editor terminal after the user agrees`() = test {
        val root = project("README.md" to "x\n")
        val backend = FakeBackend(call("terminal_run", "command" to "./gradlew test"), "The test run succeeded and everything passed as expected.")
        AcpConnection.open { AcpConnection.handle(backend) }.use { acp ->
            val editor = FakeEditor(permissionAnswers = listOf("allow"), terminalOutput = "BUILD SUCCESSFUL in 3s")
            val turn = acp.newSession(root.path, editor).ask("run the tests")
            assertEquals(listOf(listOf("./gradlew", "test")), editor.terminalCommands)
            assertEquals("The test run succeeded and everything passed as expected.", turn.message)
            assertTrue(backend.capturedRequests[1].messages.first().content.contains("BUILD SUCCESSFUL in 3s"))
            assertTrue(editor.permissionRequests.single().first!!.contains("./gradlew test"))
        }
    }

    @Test
    fun `a declined command is not run`() = test {
        val root = project("README.md" to "x\n")
        val backend = FakeBackend(call("terminal_run", "command" to "./gradlew test"), "I could not run the tests because you declined to allow that.")
        AcpConnection.open { AcpConnection.handle(backend) }.use { acp ->
            val editor = FakeEditor(permissionAnswers = listOf("reject"))
            acp.newSession(root.path, editor).ask("run the tests")
            assertTrue(editor.terminalCommands.isEmpty())
        }
    }

    @Test
    fun `files the editor attaches become attachments, using its unsaved text`() = test {
        val root = project("src/Util.kt" to "object Util\n")
        val backend = FakeBackend("It is a Kotlin object named Util that has no members at all.")
        AcpConnection.open { AcpConnection.handle(backend) }.use { acp ->
            val editor = FakeEditor(buffers = mutableMapOf("$root/src/Util.kt" to "object Util { val unsaved = 1 }\n"))
            val session = acp.newSession(root.path, editor)
            val events = session.prompt(listOf(
                com.agentclientprotocol.model.ContentBlock.Text("explain"),
                com.agentclientprotocol.model.ContentBlock.ResourceLink("Util.kt", File(root, "src/Util.kt").toURI().toString()),
            )).toList()
            assertTrue(events.isNotEmpty())
            assertTrue(backend.capturedRequests.first().systemPrompt.contains("object Util { val unsaved = 1 }"))
        }
    }

    @Test
    fun `a conversation continues across prompts in one session`() = test {
        val root = project("README.md" to "x\n")
        val backend = FakeBackend("The project is a small demo used to try out the agent.", "Yes, as I said before it is only a demo project.")
        AcpConnection.open { AcpConnection.handle(backend) }.use { acp ->
            val session = acp.newSession(root.path, FakeEditor())
            session.ask("what is this?")
            session.ask("is it big?")
            assertTrue(backend.capturedRequests[1].messages.any { it.content.contains("what is this?") })
        }
    }

    @Test
    fun `cancelling ends the turn as cancelled`() = test {
        val root = project("README.md" to "x\n")
        val started = CompletableDeferred<Unit>()
        val hanging = object : AssistantBackend {
            override fun isModelLoaded() = true
            override fun selectedModelLabel() = "slow"
            override fun generate(request: GenerationRequest, callbacks: GenerationCallbacks): GenerationHandle {
                started.complete(Unit) // never completes
                return object : GenerationHandle { override fun cancel() {}; override val isCancelled = false }
            }
        }
        AcpConnection.open { AcpConnection.handle(hanging) }.use { acp ->
            val session = acp.newSession(root.path, FakeEditor())
            val turn = CompletableDeferred<Turn>()
            val job = kotlinx.coroutines.GlobalScope.launch { turn.complete(session.ask("think forever")) }
            started.await()
            session.cancel()
            assertEquals(StopReason.CANCELLED, turn.await().response.stopReason)
            job.join()
        }
    }

    @Test
    fun `a missing model fails the session with the reason, not the connection`() = test {
        val root = project()
        AcpConnection.open { throw com.llamatik.agent.backends.BackendException("No model to run. Pass --model.") }.use { acp ->
            val failure = runCatching { acp.newSession(root.path, FakeEditor()) }.exceptionOrNull()
            assertTrue("session creation must fail", failure != null)
            assertTrue(failure!!.toString(), failure.toString().contains("No model to run"))
        }
    }
}
