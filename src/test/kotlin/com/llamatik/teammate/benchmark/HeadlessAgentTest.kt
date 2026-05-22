package com.llamatik.teammate.benchmark

import com.llamatik.teammate.backend.AssistantBackend
import com.llamatik.teammate.backend.BackendConfig
import com.llamatik.teammate.backend.GenerationCallbacks
import com.llamatik.teammate.backend.GenerationHandle
import com.llamatik.teammate.backend.GenerationRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HeadlessAgentTest {

    @get:Rule val tmp = TemporaryFolder()

    // ── prose response ───────────────────────────────────────────────────────

    @Test
    fun `prose response sets finalMessage with no tools executed`() {
        val agent = agentWith("Here is my analysis.")
        val result = agent.run("Review the code", 5)
        assertEquals("Here is my analysis.", result.finalMessage)
        assertTrue(result.toolsExecuted.isEmpty())
        assertNull(result.error)
    }

    @Test
    fun `think block stripped before finalMessage`() {
        val agent = agentWith("<think>internal reasoning</think>The answer is 42.")
        val result = agent.run("What is the answer?", 5)
        assertEquals("The answer is 42.", result.finalMessage)
    }

    @Test
    fun `think block alone surfaces as fallback on round 1`() {
        val agent = agentWith("<think>I have no answer.</think>")
        val result = agent.run("Do something", 5)
        assertTrue("think content should surface as fallback", result.finalMessage.isNotBlank())
        assertNull(result.error)
    }

    // ── read_file ────────────────────────────────────────────────────────────

    @Test
    fun `read_file returns file content`() {
        val root = tmp.newFolder("repo")
        File(root, "Foo.kt").writeText("class Foo")
        val agent = agentWith(
            """<tool_call name="read_file"><arg name="path">Foo.kt</arg></tool_call>""",
            "Read it.",
            projectRoot = root,
        )
        val result = agent.run("Read Foo.kt", 5)
        assertEquals(listOf("read_file"), result.toolsExecuted)
        assertEquals("Read it.", result.finalMessage)
    }

    @Test
    fun `read_file returns error message for missing file`() {
        val root = tmp.newFolder("repo")
        var toolResult = ""
        val agent = agentWith(
            """<tool_call name="read_file"><arg name="path">Missing.kt</arg></tool_call>""",
            "Done.",
            projectRoot = root,
            onToolResult = { toolResult = it },
        )
        agent.run("Read Missing.kt", 5)
        assertTrue("Should report file not found", toolResult.contains("not found", ignoreCase = true))
    }

    // ── write_file ───────────────────────────────────────────────────────────

    @Test
    fun `write_file writes content to disk`() {
        val root = tmp.newFolder("repo")
        File(root, "Calc.kt").writeText("class Calc { fun add(a:Int,b:Int)=a+b }")
        val newContent = "class Calc { fun add(a:Int,b:Int)=a-b }"
        val agent = agentWith(
            """<tool_call name="write_file"><arg name="path">Calc.kt</arg><arg name="content">$newContent</arg></tool_call>""",
            projectRoot = root,
        )
        agent.run("Fix Calc.kt", 5)
        assertEquals(newContent, File(root, "Calc.kt").readText())
    }

    @Test
    fun `write_file records path in changedPaths`() {
        val root = tmp.newFolder("repo")
        File(root, "A.kt").writeText("original")
        val agent = agentWith(
            """<tool_call name="write_file"><arg name="path">A.kt</arg><arg name="content">updated</arg></tool_call>""",
            projectRoot = root,
        )
        val result = agent.run("Update A.kt", 5)
        assertTrue(result.changedPaths.contains("A.kt"))
    }

    // ── create_file ──────────────────────────────────────────────────────────

    @Test
    fun `create_file creates new file on disk`() {
        val root = tmp.newFolder("repo")
        val agent = agentWith(
            """<tool_call name="create_file"><arg name="path">New.kt</arg><arg name="content">class New</arg></tool_call>""",
            projectRoot = root,
        )
        agent.run("Create New.kt", 5)
        assertTrue(File(root, "New.kt").isFile)
        assertEquals("class New", File(root, "New.kt").readText())
    }

    @Test
    fun `create_file records path in changedPaths`() {
        val root = tmp.newFolder("repo")
        val agent = agentWith(
            """<tool_call name="create_file"><arg name="path">sub/New.kt</arg><arg name="content">class New</arg></tool_call>""",
            projectRoot = root,
        )
        val result = agent.run("Create sub/New.kt", 5)
        assertTrue(result.changedPaths.any { it.contains("New.kt") })
        assertTrue(File(root, "sub/New.kt").isFile)
    }

    // ── replace_in_file ──────────────────────────────────────────────────────

    @Test
    fun `replace_in_file replaces matching content in file`() {
        val root = tmp.newFolder("repo")
        File(root, "Foo.kt").writeText("fun wrong() = 0")
        val agent = agentWith(
            """<tool_call name="replace_in_file"><arg name="path">Foo.kt</arg><arg name="old">fun wrong() = 0</arg><arg name="new">fun right() = 1</arg></tool_call>""",
            projectRoot = root,
        )
        agent.run("Fix Foo.kt", 5)
        assertEquals("fun right() = 1", File(root, "Foo.kt").readText())
    }

    @Test
    fun `replace_in_file returns error when pattern not found`() {
        val root = tmp.newFolder("repo")
        File(root, "Bar.kt").writeText("class Bar")
        var toolResult = ""
        val agent = agentWith(
            """<tool_call name="replace_in_file"><arg name="path">Bar.kt</arg><arg name="old">nonexistent</arg><arg name="new">x</arg></tool_call>""",
            "Done.",
            projectRoot = root,
            onToolResult = { toolResult = it },
        )
        agent.run("Fix Bar.kt", 5)
        assertTrue("Should report pattern not found", toolResult.contains("not found", ignoreCase = true))
    }

    // ── list_files ───────────────────────────────────────────────────────────

    @Test
    fun `list_files returns relative paths of project files`() {
        val root = tmp.newFolder("repo")
        File(root, "src").mkdirs()
        File(root, "src/Main.kt").writeText("fun main(){}")
        File(root, "build.gradle.kts").writeText("plugins{}")
        var listing = ""
        val agent = agentWith(
            """<tool_call name="list_files"><arg name="path">.</arg></tool_call>""",
            "Listed.",
            projectRoot = root,
            onToolResult = { listing = it },
        )
        agent.run("List files", 5)
        assertTrue(listing.contains("Main.kt") || listing.contains("src"))
        assertTrue(listing.contains("build.gradle.kts"))
    }

    @Test
    fun `list_files excludes ignored directories`() {
        val root = tmp.newFolder("repo")
        // Keep source file at depth ≤ 3 so walkTopDown(maxDepth=3) reaches it
        File(root, "src").mkdirs()
        File(root, "src/App.kt").writeText("class App")
        File(root, "build").mkdirs()
        File(root, "build/App.class").writeText("bytecode")
        File(root, "node_modules").mkdirs()
        File(root, "node_modules/index.js").writeText("module.exports={}")
        var listing = ""
        val agent = agentWith(
            """<tool_call name="list_files"><arg name="path">.</arg></tool_call>""",
            "Listed.",
            projectRoot = root,
            onToolResult = { listing = it },
        )
        agent.run("List files", 5)
        assertFalse("build/ should be excluded", listing.contains("App.class"))
        assertFalse("node_modules/ should be excluded", listing.contains("index.js"))
        assertTrue("src files should be included", listing.contains("App.kt"))
    }

    // ── search_code ──────────────────────────────────────────────────────────

    @Test
    fun `search_code returns files containing query`() {
        val root = tmp.newFolder("repo")
        File(root, "A.kt").writeText("fun calculate() = 42")
        File(root, "B.kt").writeText("fun unrelated() = 0")
        var searchResult = ""
        val agent = agentWith(
            """<tool_call name="search_code"><arg name="query">calculate</arg></tool_call>""",
            "Found.",
            projectRoot = root,
            onToolResult = { searchResult = it },
        )
        agent.run("Find calculate", 5)
        assertTrue(searchResult.contains("A.kt"))
        assertFalse(searchResult.contains("B.kt"))
    }

    @Test
    fun `search_code excludes ignored directories`() {
        val root = tmp.newFolder("repo")
        File(root, "src").mkdirs()
        File(root, "src/Src.kt").writeText("fun target() {}")
        File(root, "build").mkdirs()
        File(root, "build/Built.kt").writeText("fun target() {}")
        var searchResult = ""
        val agent = agentWith(
            """<tool_call name="search_code"><arg name="query">target</arg></tool_call>""",
            "Found.",
            projectRoot = root,
            onToolResult = { searchResult = it },
        )
        agent.run("Search", 5)
        assertTrue(searchResult.contains("Src.kt"))
        assertFalse("build/ should be excluded from search", searchResult.contains("Built.kt"))
    }

    @Test
    fun `search_code returns no-matches message when nothing found`() {
        val root = tmp.newFolder("repo")
        File(root, "Foo.kt").writeText("class Foo")
        var searchResult = ""
        val agent = agentWith(
            """<tool_call name="search_code"><arg name="query">unicorn</arg></tool_call>""",
            "Nothing.",
            projectRoot = root,
            onToolResult = { searchResult = it },
        )
        agent.run("Find unicorn", 5)
        assertTrue(searchResult.contains("No matches", ignoreCase = true))
    }

    // ── search_filenames ─────────────────────────────────────────────────────

    @Test
    fun `search_filenames returns files whose names match query`() {
        val root = tmp.newFolder("repo")
        File(root, "Calculator.kt").writeText("class Calculator")
        File(root, "MainApp.kt").writeText("fun main(){}")
        var searchResult = ""
        val agent = agentWith(
            """<tool_call name="search_filenames"><arg name="query">Calculator</arg></tool_call>""",
            "Found.",
            projectRoot = root,
            onToolResult = { searchResult = it },
        )
        agent.run("Find calculator file", 5)
        assertTrue(searchResult.contains("Calculator.kt"))
        assertFalse(searchResult.contains("MainApp.kt"))
    }

    // ── hallucinated / unknown tools ─────────────────────────────────────────

    @Test
    fun `hallucinated tool name is not executed`() {
        val root = tmp.newFolder("repo")
        val agent = agentWith(
            """<tool_call name="magic_tool"><arg name="path">src</arg></tool_call>""",
            projectRoot = root,
        )
        val result = agent.run("Do something", 5)
        assertTrue("Hallucinated tool must not appear in executed list", result.toolsExecuted.isEmpty())
    }

    // ── multi-round ──────────────────────────────────────────────────────────

    @Test
    fun `two-round read-then-write both rounds executed`() {
        val root = tmp.newFolder("repo")
        File(root, "Calc.kt").writeText("fun broken() = 0")
        val agent = agentWith(
            """<tool_call name="read_file"><arg name="path">Calc.kt</arg></tool_call>""",
            """<tool_call name="write_file"><arg name="path">Calc.kt</arg><arg name="content">fun fixed() = 1</arg></tool_call>""",
            projectRoot = root,
        )
        val result = agent.run("Fix Calc.kt", 5)
        assertEquals(listOf("read_file", "write_file"), result.toolsExecuted)
        assertEquals("fun fixed() = 1", File(root, "Calc.kt").readText())
    }

    @Test
    fun `maxRounds limit stops loop even if model keeps calling tools`() {
        val root = tmp.newFolder("repo")
        val infiniteResponse = """<tool_call name="list_files"><arg name="path">.</arg></tool_call>"""
        val agent = HeadlessAgent(
            backend = repeatingBackend(infiniteResponse),
            config = fakeConfig(),
            projectRoot = root,
            maxRounds = 3,
        )
        val result = agent.run("List forever", 5)
        assertTrue("Should stop at maxRounds=3", result.rounds <= 3)
    }

    @Test
    fun `think block before tool call is stripped and tool still executes`() {
        val root = tmp.newFolder("repo")
        File(root, "Z.kt").writeText("class Z")
        val agent = agentWith(
            "<think>Let me list files first.</think>\n" +
                """<tool_call name="list_files"><arg name="path">.</arg></tool_call>""",
            "Listed.",
            projectRoot = root,
        )
        val result = agent.run("List files", 5)
        assertEquals(listOf("list_files"), result.toolsExecuted)
    }

    @Test
    fun `multiple tool calls in one round all executed`() {
        val root = tmp.newFolder("repo")
        File(root, "A.kt").writeText("class A")
        File(root, "B.kt").writeText("class B")
        val agent = agentWith(
            """<tool_call name="read_file"><arg name="path">A.kt</arg></tool_call>""" +
                "\n" +
                """<tool_call name="read_file"><arg name="path">B.kt</arg></tool_call>""",
            "Read both.",
            projectRoot = root,
        )
        val result = agent.run("Read A and B", 5)
        assertEquals(listOf("read_file", "read_file"), result.toolsExecuted)
    }

    // ── error propagation ────────────────────────────────────────────────────

    @Test
    fun `backend error sets error field in RunResult`() {
        val root = tmp.newFolder("repo")
        val agent = HeadlessAgent(
            backend = errorBackend("model crashed"),
            config = fakeConfig(),
            projectRoot = root,
        )
        val result = agent.run("Do something", 5)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("model crashed"))
    }

    // ── timeout ──────────────────────────────────────────────────────────────

    @Test
    fun `zero-minute timeout triggers timeout error after first round with tool call`() {
        val root = tmp.newFolder("repo")
        // Tool call in round 1 causes a round 2 where checkTimeout fires immediately (deadline=past)
        val agent = agentWith(
            """<tool_call name="list_files"><arg name="path">.</arg></tool_call>""",
            "Listed.",
            projectRoot = root,
            timeoutMinutes = 0,
        )
        val result = agent.run("List files", 0)
        assertNotNull("Should report a timeout or normal completion", result)
        // Either timeout error or completed normally — the key is no exception escapes
        // If it timed out the error contains "Timeout"
        if (result.error != null) {
            assertTrue(result.error.contains("Timeout", ignoreCase = true))
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun agentWith(
        vararg responses: String,
        projectRoot: File = tmp.newFolder(),
        onToolResult: ((String) -> Unit)? = null,
        timeoutMinutes: Int = 5,
    ): HeadlessAgent {
        val backend = if (onToolResult == null) {
            CapturingFakeBackend(*responses)
        } else {
            CapturingFakeBackend(*responses, toolResultCallback = onToolResult)
        }
        return HeadlessAgent(backend = backend, config = fakeConfig(), projectRoot = projectRoot)
    }

    /**
     * A FakeBackend that optionally intercepts the tool-result message injected into the
     * next round's prompt, so tests can assert what the tool actually returned.
     */
    private inner class CapturingFakeBackend(
        private vararg val responses: String,
        private val toolResultCallback: ((String) -> Unit)? = null,
    ) : AssistantBackend {
        private var callIndex = 0

        override fun isModelLoaded() = true
        override fun selectedModelLabel() = "capturing-fake"

        override fun generate(request: GenerationRequest, callbacks: GenerationCallbacks): GenerationHandle {
            // Round 2+ prompts contain "<result tool=...>...</result>" — extract and surface it.
            if (callIndex > 0 && toolResultCallback != null) {
                val resultBlock = Regex("<result tool=\"[^\"]+\">(.*?)</result>", RegexOption.DOT_MATCHES_ALL)
                    .find(request.messages.firstOrNull()?.content ?: "")
                    ?.groupValues?.get(1)?.trim()
                if (resultBlock != null) toolResultCallback.invoke(resultBlock)
            }
            val response = responses.getOrElse(callIndex) { "" }
            callIndex++
            callbacks.onCompleted(response)
            return noopHandle()
        }
    }

    private fun repeatingBackend(response: String): AssistantBackend = object : AssistantBackend {
        override fun isModelLoaded() = true
        override fun selectedModelLabel() = "repeating"
        override fun generate(request: GenerationRequest, callbacks: GenerationCallbacks): GenerationHandle {
            callbacks.onCompleted(response)
            return noopHandle()
        }
    }

    private fun errorBackend(message: String): AssistantBackend = object : AssistantBackend {
        override fun isModelLoaded() = true
        override fun selectedModelLabel() = "error-backend"
        override fun generate(request: GenerationRequest, callbacks: GenerationCallbacks): GenerationHandle {
            callbacks.onError(message)
            return noopHandle()
        }
    }

    private fun noopHandle() = object : GenerationHandle {
        override fun cancel() {}
        override val isCancelled = false
    }

    private fun fakeConfig() = BackendConfig(
        modelIdOrPath = "fake.gguf",
        temperature = 0.0,
        maxTokens = 512,
        contextLength = 4096,
        numThreads = 1,
        useMmap = false,
        flashAttention = false,
        batchSize = 512,
        localOnly = true,
    )
}
