package com.llamatik.teammate.release

import com.llamatik.teammate.backend.BackendConfig
import com.llamatik.teammate.benchmark.HeadlessAgent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Agent workflow integration tests.
 *
 * Each test runs the real [HeadlessAgent] loop with a [ScriptedAssistantBackend]
 * against a small ephemeral fixture project. No real model is required.
 *
 * Run via: ./gradlew agentWorkflowTest
 */
class AgentWorkflowTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val config = BackendConfig(
        modelIdOrPath = "scripted",
        temperature = 0.0,
        maxTokens = 512,
        contextLength = 4096,
        numThreads = 1,
        useMmap = false,
        flashAttention = false,
        batchSize = 512,
        localOnly = true,
    )

    // ── 1: read_file ─────────────────────────────────────────────────────────

    @Test
    fun `read_file returns file content`() {
        val root = tmp.newFolder("proj")
        File(root, "src").mkdirs()
        File(root, "src/Greeting.kt").writeText("fun greet() = \"Hello\"")

        val backend = ScriptedAssistantBackend(listOf(
            """<tool_call name="read_file"><arg name="path">src/Greeting.kt</arg></tool_call>""",
            "The file contains a greet function.",
        ))
        val result = HeadlessAgent(backend, config, root).run("Read src/Greeting.kt", 2)

        assertNull("Should not error", result.error)
        assertTrue("read_file should be used", "read_file" in result.toolsExecuted)
    }

    // ── 2: write_file ────────────────────────────────────────────────────────

    @Test
    fun `write_file creates or overwrites a file`() {
        val root = tmp.newFolder("proj")
        File(root, "src").mkdirs()
        File(root, "src/Main.kt").writeText("fun main() {}")

        val backend = ScriptedAssistantBackend(listOf(
            """<tool_call name="write_file"><arg name="path">src/Main.kt</arg><arg name="content">fun main() { println("hello") }</arg></tool_call>""",
            "Done.",
        ))
        HeadlessAgent(backend, config, root).run("Update main", 2)

        assertEquals("fun main() { println(\"hello\") }", File(root, "src/Main.kt").readText())
    }

    // ── 3: create_file ───────────────────────────────────────────────────────

    @Test
    fun `create_file writes a new file`() {
        val root = tmp.newFolder("proj")
        File(root, "src").mkdirs()

        val backend = ScriptedAssistantBackend(listOf(
            """<tool_call name="create_file"><arg name="path">src/New.kt</arg><arg name="content">object New</arg></tool_call>""",
            "Created.",
        ))
        HeadlessAgent(backend, config, root).run("Create New.kt", 2)

        assertTrue("New.kt should exist", File(root, "src/New.kt").exists())
        assertEquals("object New", File(root, "src/New.kt").readText())
    }

    // ── 4: replace_in_file ───────────────────────────────────────────────────

    @Test
    fun `replace_in_file patches existing content`() {
        val root = tmp.newFolder("proj")
        File(root, "src").mkdirs()
        File(root, "src/Calc.kt").writeText("fun add(a: Int, b: Int) = a - b")

        val backend = ScriptedAssistantBackend(listOf(
            """<tool_call name="replace_in_file"><arg name="path">src/Calc.kt</arg><arg name="old">a - b</arg><arg name="new">a + b</arg></tool_call>""",
            "Fixed.",
        ))
        HeadlessAgent(backend, config, root).run("Fix the subtraction bug", 2)

        assertEquals("fun add(a: Int, b: Int) = a + b", File(root, "src/Calc.kt").readText())
    }

    // ── 5: list_files ────────────────────────────────────────────────────────

    @Test
    fun `list_files returns project structure`() {
        val root = tmp.newFolder("proj")
        File(root, "src/main/kotlin").mkdirs()
        File(root, "src/main/kotlin/App.kt").writeText("fun main() {}")

        val backend = ScriptedAssistantBackend(listOf(
            """<tool_call name="list_files"><arg name="path">.</arg></tool_call>""",
            "I can see App.kt in the project.",
        ))
        val result = HeadlessAgent(backend, config, root).run("List all files", 2)

        assertNull(result.error)
        assertTrue("list_files should be used", "list_files" in result.toolsExecuted)
    }

    // ── 6: search_code ───────────────────────────────────────────────────────

    @Test
    fun `search_code finds files containing a pattern`() {
        val root = tmp.newFolder("proj")
        File(root, "src").mkdirs()
        File(root, "src/Service.kt").writeText("class UserService { fun getUser() {} }")
        File(root, "src/Repo.kt").writeText("class UserRepo { fun findUser() {} }")

        val backend = ScriptedAssistantBackend(listOf(
            """<tool_call name="search_code"><arg name="query">UserService</arg></tool_call>""",
            "Found UserService in src/Service.kt.",
        ))
        val result = HeadlessAgent(backend, config, root).run("Find UserService", 2)

        assertNull(result.error)
        assertTrue("search_code should be used", "search_code" in result.toolsExecuted)
    }

    // ── 7: search_filenames ──────────────────────────────────────────────────

    @Test
    fun `search_filenames finds files by name`() {
        val root = tmp.newFolder("proj")
        File(root, "src").mkdirs()
        File(root, "src/NumberUtils.kt").writeText("object NumberUtils")

        val backend = ScriptedAssistantBackend(listOf(
            """<tool_call name="search_filenames"><arg name="query">NumberUtils</arg></tool_call>""",
            "Found src/NumberUtils.kt.",
        ))
        val result = HeadlessAgent(backend, config, root).run("Find NumberUtils file", 2)

        assertNull(result.error)
        assertTrue("search_filenames should be used", "search_filenames" in result.toolsExecuted)
    }

    // ── 8: read-then-write pipeline ──────────────────────────────────────────

    @Test
    fun `read then write pipeline completes without error`() {
        val root = tmp.newFolder("proj")
        File(root, "src").mkdirs()
        File(root, "src/Stack.kt").writeText("class Stack { fun peek() = items[0] }")

        val backend = ScriptedAssistantBackend(listOf(
            """<tool_call name="read_file"><arg name="path">src/Stack.kt</arg></tool_call>""",
            """<tool_call name="replace_in_file"><arg name="path">src/Stack.kt</arg><arg name="old">items[0]</arg><arg name="new">items[items.size - 1]</arg></tool_call>""",
            "Fixed the off-by-one error.",
        ))
        val result = HeadlessAgent(backend, config, root).run("Fix Stack.peek()", 5)

        assertNull(result.error)
        val content = File(root, "src/Stack.kt").readText()
        assertTrue("File should be patched", content.contains("items.size - 1"))
    }

    // ── 9: list-then-read discovery pipeline ────────────────────────────────

    @Test
    fun `list then read discovery pipeline works`() {
        val root = tmp.newFolder("proj")
        File(root, "src/main/kotlin").mkdirs()
        File(root, "src/main/kotlin/Circle.kt").writeText("class Circle(val r: Double) { fun area() = TODO() }")

        val backend = ScriptedAssistantBackend(listOf(
            """<tool_call name="list_files"><arg name="path">.</arg></tool_call>""",
            """<tool_call name="read_file"><arg name="path">src/main/kotlin/Circle.kt</arg></tool_call>""",
            """<tool_call name="replace_in_file"><arg name="path">src/main/kotlin/Circle.kt</arg><arg name="old">TODO()</arg><arg name="new">Math.PI * r * r</arg></tool_call>""",
            "Implemented area().",
        ))
        val result = HeadlessAgent(backend, config, root).run("Implement Circle.area()", 5)

        assertNull(result.error)
        assertTrue(File(root, "src/main/kotlin/Circle.kt").readText().contains("Math.PI"))
    }

    // ── 10: search-then-fix pipeline ─────────────────────────────────────────

    @Test
    fun `search then fix pipeline resolves a bug`() {
        val root = tmp.newFolder("proj")
        File(root, "src").mkdirs()
        File(root, "src/Math.kt").writeText("fun divide(a: Int, b: Int) = a * b")

        val backend = ScriptedAssistantBackend(listOf(
            """<tool_call name="search_code"><arg name="query">divide</arg></tool_call>""",
            """<tool_call name="read_file"><arg name="path">src/Math.kt</arg></tool_call>""",
            """<tool_call name="replace_in_file"><arg name="path">src/Math.kt</arg><arg name="old">a * b</arg><arg name="new">a / b</arg></tool_call>""",
            "Fixed divide.",
        ))
        val result = HeadlessAgent(backend, config, root).run("Fix division bug", 5)

        assertNull(result.error)
        assertTrue(File(root, "src/Math.kt").readText().contains("a / b"))
    }

    // ── 11: multi-file fix ────────────────────────────────────────────────────

    @Test
    fun `multi-file fix writes two separate files`() {
        val root = tmp.newFolder("proj")
        File(root, "src").mkdirs()
        File(root, "src/Interface.kt").writeText("interface Fmt { fun format(s: String): String }")
        File(root, "src/Impl.kt").writeText("class UpperFmt : Fmt { override fun format(s: String) = s }")

        val backend = ScriptedAssistantBackend(listOf(
            """<tool_call name="read_file"><arg name="path">src/Impl.kt</arg></tool_call>""",
            """<tool_call name="write_file"><arg name="path">src/Impl.kt</arg><arg name="content">class UpperFmt : Fmt { override fun format(s: String) = s.uppercase() }</arg></tool_call>""",
            "Updated Impl.kt.",
        ))
        val result = HeadlessAgent(backend, config, root).run("Fix UpperFmt", 5)

        assertNull(result.error)
        assertTrue(File(root, "src/Impl.kt").readText().contains("uppercase()"))
    }

    // ── 12: agent stops when no tool calls are returned ───────────────────────

    @Test
    fun `agent returns final message when model returns plain text`() {
        val root = tmp.newFolder("proj")

        val backend = ScriptedAssistantBackend(listOf(
            "I cannot find any bugs in the project. Everything looks correct.",
        ))
        val result = HeadlessAgent(backend, config, root).run("Find any bugs", 5)

        assertNull(result.error)
        assertTrue("Should have no tool calls", result.toolsExecuted.isEmpty())
        assertTrue("Final message should be non-empty", result.finalMessage.isNotBlank())
    }

    // ── 13: agent respects maxRounds ─────────────────────────────────────────

    @Test
    fun `agent stops at maxRounds even with infinite tool calls`() {
        val root = tmp.newFolder("proj")
        File(root, "src").mkdirs()
        File(root, "src/A.kt").writeText("// file A")

        // Always return a tool call — agent must stop at maxRounds
        val infiniteRead = """<tool_call name="read_file"><arg name="path">src/A.kt</arg></tool_call>"""
        val backend = ScriptedAssistantBackend(List(20) { infiniteRead })
        val result = HeadlessAgent(backend, config, root, maxRounds = 3).run("Loop forever", 5)

        assertTrue("Agent should stop", result.rounds <= 3)
    }

    // ── 14: replace_in_file on missing pattern reports error but does not crash ─

    @Test
    fun `replace_in_file with missing pattern does not crash the agent`() {
        val root = tmp.newFolder("proj")
        File(root, "src").mkdirs()
        File(root, "src/A.kt").writeText("fun hello() {}")

        val backend = ScriptedAssistantBackend(listOf(
            """<tool_call name="replace_in_file"><arg name="path">src/A.kt</arg><arg name="old">DOES_NOT_EXIST</arg><arg name="new">replacement</arg></tool_call>""",
            "The pattern was not found; nothing changed.",
        ))
        val result = HeadlessAgent(backend, config, root).run("Replace non-existent pattern", 5)

        // File should be unchanged
        assertEquals("fun hello() {}", File(root, "src/A.kt").readText())
        assertNull("Agent should not report a hard error", result.error)
    }

    // ── 15: create_file then read_file round-trip ─────────────────────────────

    @Test
    fun `create file then read it back in next round`() {
        val root = tmp.newFolder("proj")

        val backend = ScriptedAssistantBackend(listOf(
            """<tool_call name="create_file"><arg name="path">src/Hello.kt</arg><arg name="content">fun hello() = "hi"</arg></tool_call>""",
            """<tool_call name="read_file"><arg name="path">src/Hello.kt</arg></tool_call>""",
            "The file was created and contains hello().",
        ))
        val result = HeadlessAgent(backend, config, root).run("Create and verify Hello.kt", 5)

        assertNull(result.error)
        assertTrue("create_file should be used", "create_file" in result.toolsExecuted)
        assertTrue("read_file should be used", "read_file" in result.toolsExecuted)
        assertTrue(File(root, "src/Hello.kt").exists())
    }

    // ── 16: agent records changed files ──────────────────────────────────────

    @Test
    fun `changedPaths tracks files written by the agent`() {
        val root = tmp.newFolder("proj")
        File(root, "src").mkdirs()
        File(root, "src/Counter.kt").writeText("class Counter { var n = 0 }")

        val backend = ScriptedAssistantBackend(listOf(
            """<tool_call name="write_file"><arg name="path">src/Counter.kt</arg><arg name="content">class Counter { var n = 0; fun inc() { n++ } }</arg></tool_call>""",
            "Done.",
        ))
        val result = HeadlessAgent(backend, config, root).run("Add increment method", 5)

        assertTrue("Counter.kt should be in changedPaths",
            result.changedPaths.any { it.contains("Counter.kt") })
    }

    // ── 17: git tools round-trip ─────────────────────────────────────────────

    @Test
    fun `git_status and git_diff are executed without crash`() {
        val root = tmp.newFolder("proj")
        File(root, "src").mkdirs()
        File(root, "src/Stub.kt").writeText("// stub")

        // git may or may not be available; agent should survive either way
        val backend = ScriptedAssistantBackend(listOf(
            """<tool_call name="git_status"></tool_call>""",
            """<tool_call name="git_diff"></tool_call>""",
            "Checked git state.",
        ))
        val result = HeadlessAgent(backend, config, root).run("Check git state", 5)

        assertNull(result.error)
        assertTrue("git_status should be in toolsExecuted", "git_status" in result.toolsExecuted)
    }
}
