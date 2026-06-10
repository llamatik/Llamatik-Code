package com.llamatik.teammate.agent

import com.llamatik.teammate.backend.AssistantBackend
import com.llamatik.teammate.backend.BackendConfig
import com.llamatik.teammate.backend.GenerationCallbacks
import com.llamatik.teammate.backend.GenerationHandle
import com.llamatik.teammate.backend.GenerationRequest
import com.llamatik.teammate.model.ChatMessage
import com.llamatik.teammate.model.ChatRole
import com.llamatik.teammate.model.EditProposal
import com.llamatik.teammate.tools.ToolCall
import com.llamatik.teammate.tools.ToolCallParser
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the agent pipeline that do NOT require the IntelliJ platform.
 *
 * The tests use [FakeBackend] to simulate model responses and [FakeToolRegistry] to
 * simulate tool execution. They drive [AgentLoop] — a pure-logic extraction of the
 * runRound() logic from AgentOrchestrator — so the loop can be tested without
 * IntelliJ services.
 */
class AgentLoopTest {

    // ── FakeBackend ──────────────────────────────────────────────────────────

    /**
     * A fake [AssistantBackend] that returns scripted responses in sequence.
     * Each response is delivered synchronously (no threads) to keep tests deterministic.
     */
    class FakeBackend(private vararg val responses: String) : AssistantBackend {
        private var callIndex = 0
        val capturedRequests = mutableListOf<GenerationRequest>()

        override fun isModelLoaded() = true
        override fun selectedModelLabel() = "fake-model"

        override fun generate(request: GenerationRequest, callbacks: GenerationCallbacks): GenerationHandle {
            capturedRequests += request
            val response = responses.getOrElse(callIndex) { "" }
            callIndex++
            callbacks.onCompleted(response)
            return object : GenerationHandle {
                override fun cancel() {}
                override val isCancelled = false
            }
        }
    }

    // ── FakeToolRegistry ─────────────────────────────────────────────────────

    /**
     * A fake tool registry that records which tools were called and returns scripted results.
     */
    class FakeToolRegistry(
        private val results: Map<String, String> = emptyMap(),
        sequentialResults: List<String> = emptyList(),
    ) {
        val executedCalls = mutableListOf<ToolCall>()
        private val queue = ArrayDeque(sequentialResults)

        fun execute(call: ToolCall): String {
            executedCalls += call
            if (queue.isNotEmpty()) return queue.removeFirst()
            return results[call.name] ?: "result for ${call.name}"
        }

        fun describeTools() = "- list_files: list\n- read_file: read\n- write_file: write\n- create_file: create"
    }

    // ── AgentLoop ────────────────────────────────────────────────────────────

    /**
     * Pure-logic loop extracted from AgentOrchestrator for testability.
     * Mirrors the real runRound() logic without any IntelliJ service dependencies.
     */
    class AgentLoop(
        private val backend: FakeBackend,
        private val toolRegistry: FakeToolRegistry,
        private val maxRounds: Int = 5,
        private val maxToolCallsPerRound: Int = 4,
        private val toolResultsMaxChars: Int = 4000,
        private val toolResultContentMaxChars: Int = 1200,
    ) {
        private val knownToolNames = setOf(
            "list_files", "read_file", "search_code", "search_filenames",
            "get_current_file", "get_selected_text", "write_file", "create_file",
            "replace_in_file", "show_diff", "apply_patch",
            "git_status", "git_diff", "git_changed_files", "git_branch",
        )
        private val editToolNames = setOf("write_file", "replace_in_file", "create_file")
        private val readOnlyToolNames = setOf(
            "list_files", "read_file", "search_code", "search_filenames",
            "get_current_file", "get_selected_text",
            "git_status", "git_diff", "git_changed_files", "git_branch",
        )

        data class LoopResult(
            val finalMessage: String,
            val rounds: Int,
            val toolsExecuted: List<String>,
            val proposalPaths: List<String> = emptyList(),
        )

        fun run(userPrompt: String, systemPrompt: String = "You are a coding agent.", preSeedReadPaths: Set<String> = emptySet()): LoopResult {
            val tokens = mutableListOf<String>()
            var finalMessage = ""
            var totalRounds = 0
            val roundProposals = mutableListOf<Pair<String, String>>()
            // Mirrors AgentOrchestrator.executedToolKeys: dedup set across all rounds.
            val executedToolKeys = mutableSetOf<Pair<String, String>>()
            preSeedReadPaths.forEach { executedToolKeys += "read_file" to it }
            // Tracks whether any read-only tool has been called (hasReadContent across rounds).
            var hasReadContentAccum = false

            fun extractProposals(toolMessages: List<ChatMessage>): List<Pair<String, String>> {
                return toolMessages.mapNotNull { msg ->
                    if (msg.toolName !in editToolNames) return@mapNotNull null
                    val c = msg.content
                    val path = when {
                        c.startsWith("Prepared file replacement for ") ->
                            c.removePrefix("Prepared file replacement for ").substringBefore("\n")
                        c.startsWith("Prepared new file for ") ->
                            c.removePrefix("Prepared new file for ").substringBefore("\n")
                        else -> return@mapNotNull null
                    }.trim()
                    val newContent = when {
                        c.contains("\n---NEW---\n") -> c.substringAfter("\n---NEW---\n").trim()
                        c.startsWith("Prepared new file for ") -> c.substringAfter("\n").trim()
                        else -> return@mapNotNull null
                    }
                    if (ProposalValidator.validate(path, newContent) !is ProposalValidator.Result.Valid)
                        return@mapNotNull null
                    path to newContent
                }
            }

            fun runRound(messages: List<ChatMessage>, round: Int, task: String, hasReadContent: Boolean) {
                if (round > maxRounds) return
                totalRounds = round

                val config = BackendConfig(
                    modelIdOrPath = "fake.gguf",
                    temperature = 0.0,
                    maxTokens = 2048,
                    contextLength = 4096,
                    numThreads = 4,
                    useMmap = true,
                    flashAttention = false,
                    batchSize = 2048,
                    localOnly = true,
                )

                backend.generate(
                    GenerationRequest(systemPrompt = systemPrompt, messages = messages, config = config,
                        noThink = round + 1 >= maxRounds),
                    object : GenerationCallbacks {
                        override fun onToken(t: String) { tokens += t }
                        override fun onCompleted(fullText: String) {
                            val answerText = ToolCallParser.stripThinking(fullText)
                            val toolCalls = ToolCallParser.parseIncludingIncomplete(answerText)
                                .filter { it.name in knownToolNames }
                                .take(maxToolCallsPerRound)

                            // Mid-loop prose guard: fires when the model wrote prose instead of a tool call
                            // after at least two rounds of reading. round > 2 (not round > 1) so that
                            // simple Q&A responses (1 read round + 1 prose round) terminate normally
                            // rather than being escalated to a write pass.
                            // round < maxRounds - 1 prevents re-entry on the escalated write pass.
                            if (toolCalls.isEmpty() && round > 2 && round < maxRounds - 1 && hasReadContent) {
                                runRound(
                                    listOf(ChatMessage(role = ChatRole.USER, content = "Task: $task\nNow write the file.")),
                                    maxRounds - 1,
                                    task,
                                    hasReadContent,
                                )
                                return
                            }

                            if (toolCalls.isEmpty() || round >= maxRounds) {
                                finalMessage = ToolCallParser.cleanForDisplay(fullText, allowThinkFallback = round == 1)
                                return
                            }

                            var allCallsDuplicate = true
                            val toolMessages = toolCalls.map { call ->
                                val toolKey = call.name to (call.arguments.values.firstOrNull().orEmpty())
                                val isDuplicateReadOnly = call.name in readOnlyToolNames && !executedToolKeys.add(toolKey)
                                if (!isDuplicateReadOnly) allCallsDuplicate = false
                                val result = if (isDuplicateReadOnly) {
                                    "Already called ${call.name} — use the result you already received."
                                } else {
                                    toolRegistry.execute(call)
                                }
                                ChatMessage(role = ChatRole.TOOL, content = result, toolName = call.name)
                            }

                            val wroteFiles = toolCalls.any { it.name in editToolNames }
                            val proposals = extractProposals(toolMessages)
                            val nowHasRead = hasReadContent || toolCalls.any { it.name in readOnlyToolNames }
                            hasReadContentAccum = nowHasRead
                            if (wroteFiles) {
                                roundProposals += proposals
                                if (proposals.isNotEmpty()) {
                                    finalMessage = ""
                                    return
                                }
                            }

                            // All-duplicate escalation: only fire if we're not already at the penultimate
                            // round (prevents infinite escalation when maxRounds is small).
                            if (allCallsDuplicate && proposals.isEmpty() && round < maxRounds - 1) {
                                runRound(
                                    listOf(ChatMessage(role = ChatRole.USER, content = "Task: $task\nAll previous reads were duplicates. Now write the file.")),
                                    maxRounds - 1,
                                    task,
                                    nowHasRead,
                                )
                                return
                            }

                            val allReadOnly = toolCalls.all { it.name !in editToolNames }
                            val directive = RoundDirective.get(RoundDirective.RoundState(
                                round = round,
                                maxRounds = maxRounds,
                                hasReadContent = nowHasRead,
                                wroteFilesThisRound = wroteFiles,
                                validProposalsProduced = proposals.isNotEmpty(),
                                allToolsWereReadOnly = allReadOnly,
                            ))

                            val toolResultsSummary = toolMessages.joinToString("\n") { msg ->
                                "<result tool=\"${msg.toolName}\">\n${msg.content.take(toolResultContentMaxChars)}\n</result>"
                            }

                            val nextContent = buildString {
                                appendLine("Task: ${task.take(200)}")
                                appendLine("Tool results:")
                                appendLine(toolResultsSummary.take(toolResultsMaxChars))
                                appendLine()
                                append(directive)
                            }
                            val nextMessages = listOf(ChatMessage(role = ChatRole.USER, content = nextContent))
                            runRound(nextMessages, round + 1, task, nowHasRead)
                        }
                        override fun onError(message: String, throwable: Throwable?) {
                            finalMessage = "ERROR: $message"
                        }
                    }
                )
            }

            val initMessages = listOf(ChatMessage(role = ChatRole.USER, content = userPrompt))
            runRound(initMessages, 1, userPrompt, hasReadContent = preSeedReadPaths.isNotEmpty())

            return LoopResult(
                finalMessage = finalMessage,
                rounds = totalRounds,
                toolsExecuted = toolRegistry.executedCalls.map { it.name },
                proposalPaths = roundProposals.map { it.first },
            )
        }
    }

    // ── Tests: loop termination on prose ────────────────────────────────────

    @Test
    fun `loop terminates in round 1 when model returns plain prose`() {
        val backend = FakeBackend("Here is my analysis of your code.")
        val loop = AgentLoop(backend, FakeToolRegistry())
        val result = loop.run("Review my code")
        assertEquals(1, result.rounds)
        assertEquals("Here is my analysis of your code.", result.finalMessage)
        assertTrue(result.toolsExecuted.isEmpty())
    }

    @Test
    fun `loop terminates in round 1 when model returns only think block`() {
        // A model response that is pure reasoning — after stripping <think>, nothing remains.
        // This is the "halts after reasoning" bug: the loop should return the think content
        // as fallback rather than a blank string.
        val backend = FakeBackend("<think>I should call list_files to explore the project structure.</think>")
        val loop = AgentLoop(backend, FakeToolRegistry())
        val result = loop.run("Review my code")
        assertEquals(1, result.rounds)
        // cleanForDisplay falls back to think content when nothing else remains
        assertTrue("Should not be blank", result.finalMessage.isNotBlank())
    }

    // ── Tests: tool call execution ───────────────────────────────────────────

    @Test
    fun `loop executes list_files tool call and continues to round 2`() {
        val backend = FakeBackend(
            // Round 1: model calls list_files
            """<tool_call name="list_files"><arg name="path">.</arg></tool_call>""",
            // Round 2: model returns prose answer after seeing tool results
            "I found the following files in your project.",
        )
        val loop = AgentLoop(backend, FakeToolRegistry(mapOf("list_files" to "src/Main.kt\nsrc/Foo.kt")))
        val result = loop.run("What files are in the project?")
        assertEquals(2, result.rounds)
        assertEquals(listOf("list_files"), result.toolsExecuted)
        assertEquals("I found the following files in your project.", result.finalMessage)
    }

    @Test
    fun `loop executes read_file then write_file across three rounds`() {
        val backend = FakeBackend(
            // Round 1: list_files
            """<tool_call name="list_files"><arg name="path">src</arg></tool_call>""",
            // Round 2: read_file
            """<tool_call name="read_file"><arg name="path">src/Main.kt</arg></tool_call>""",
            // Round 3: write_file
            """<tool_call name="write_file"><arg name="path">src/Main.kt</arg><arg name="content">fun main() { println("fixed") }</arg></tool_call>""",
            // Round 4: prose answer
            "I have applied the fix.",
        )
        val fakeTools = FakeToolRegistry(mapOf(
            "list_files" to "src/Main.kt",
            "read_file" to "fun main() { println(\"broken\") }",
            "write_file" to "Prepared file replacement for src/Main.kt\n---OLD---\nfun main() { println(\"broken\") }\n---NEW---\nfun main() {\n    println(\"fixed\")\n}",
        ))
        val loop = AgentLoop(backend, fakeTools)
        val result = loop.run("Fix the main function")
        // Loop exits after write_file in round 3 — no round 4 prose needed
        assertEquals(3, result.rounds)
        assertEquals(listOf("list_files", "read_file", "write_file"), result.toolsExecuted)
    }

    @Test
    fun `loop filters out hallucinated tool names`() {
        val backend = FakeBackend(
            // Round 1: model calls a hallucinated tool
            """<tool_call name="diff_tool"><arg name="path">src/A.kt</arg></tool_call>""",
            // Since hallucinated tools are filtered, the loop should terminate after round 1
        )
        val loop = AgentLoop(backend, FakeToolRegistry())
        val result = loop.run("Show me the diff")
        assertEquals(1, result.rounds)
        assertTrue("Hallucinated tool should not be executed", result.toolsExecuted.isEmpty())
    }

    @Test
    fun `loop respects MAX_TOOL_CALLS_PER_ROUND cap`() {
        // Model emits 6 tool calls; only first 4 should be executed
        val sixCalls = (1..6).joinToString("\n") { i ->
            """<tool_call name="read_file"><arg name="path">src/File$i.kt</arg></tool_call>"""
        }
        val backend = FakeBackend(sixCalls, "Done reviewing all files.")
        val fakeTools = FakeToolRegistry(mapOf("read_file" to "file content"))
        val loop = AgentLoop(backend, fakeTools, maxToolCallsPerRound = 4)
        val result = loop.run("Review all files")
        assertEquals(4, result.toolsExecuted.size)
    }

    // ── Tests: MAX_ROUNDS hard cap ───────────────────────────────────────────

    @Test
    fun `loop stops at MAX_ROUNDS even if model keeps emitting tool calls`() {
        // Model always responds with a tool call — loop must stop at maxRounds
        val infiniteToolCall = """<tool_call name="list_files"><arg name="path">.</arg></tool_call>"""
        val backend = FakeBackend(
            infiniteToolCall, infiniteToolCall, infiniteToolCall,
            infiniteToolCall, infiniteToolCall, infiniteToolCall,
        )
        val loop = AgentLoop(backend, FakeToolRegistry(), maxRounds = 3)
        val result = loop.run("Do something")
        assertEquals(3, result.rounds)
    }

    // ── Tests: tool result content in next prompt ────────────────────────────

    @Test
    fun `tool result content is included in the next round prompt`() {
        val fileContent = "class Foo { fun bar(): Int = 42 }"
        val backend = FakeBackend(
            """<tool_call name="read_file"><arg name="path">src/Foo.kt</arg></tool_call>""",
            "The class Foo has a bar method.",
        )
        val fakeTools = FakeToolRegistry(mapOf("read_file" to fileContent))
        val loop = AgentLoop(backend, fakeTools)
        loop.run("Review Foo.kt")

        // Round 2 request should contain the file content in the user message
        val round2Request = backend.capturedRequests[1]
        val round2Content = round2Request.messages.first().content
        assertTrue(
            "Round 2 prompt should contain file content",
            round2Content.contains("class Foo"),
        )
    }

    @Test
    fun `tool result content is truncated to TOOL_RESULT_CONTENT_MAX_CHARS`() {
        val longContent = "x".repeat(5000)
        val backend = FakeBackend(
            """<tool_call name="read_file"><arg name="path">big.kt</arg></tool_call>""",
            "Done.",
        )
        val fakeTools = FakeToolRegistry(mapOf("read_file" to longContent))
        val loop = AgentLoop(backend, fakeTools, toolResultContentMaxChars = 1200)
        loop.run("Read big.kt")

        val round2Content = backend.capturedRequests[1].messages.first().content
        // Full 5000-char content must NOT appear; max 1200 chars per result
        assertFalse(
            "Tool result should be truncated",
            round2Content.contains("x".repeat(1201)),
        )
    }

    // ── Tests: think-block round-trip ────────────────────────────────────────

    @Test
    fun `tool call after think block is correctly extracted`() {
        val response = "<think>I should list files first.</think>\n" +
            """<tool_call name="list_files"><arg name="path">src</arg></tool_call>"""
        val backend = FakeBackend(response, "Here are the files.")
        val fakeTools = FakeToolRegistry(mapOf("list_files" to "src/Main.kt"))
        val loop = AgentLoop(backend, fakeTools)
        val result = loop.run("List files")
        assertEquals(listOf("list_files"), result.toolsExecuted)
        assertEquals(2, result.rounds)
    }

    @Test
    fun `multiple tool calls in single response are all executed`() {
        val response = """
            <tool_call name="list_files"><arg name="path">src</arg></tool_call>
            <tool_call name="read_file"><arg name="path">src/Main.kt</arg></tool_call>
        """.trimIndent()
        val backend = FakeBackend(response, "Reviewed.")
        val fakeTools = FakeToolRegistry(mapOf(
            "list_files" to "src/Main.kt",
            "read_file" to "fun main() {}",
        ))
        val loop = AgentLoop(backend, fakeTools)
        val result = loop.run("Review project")
        assertEquals(listOf("list_files", "read_file"), result.toolsExecuted)
    }

    // ── Tests: early-exit after write/create ─────────────────────────────────

    @Test
    fun `loop stops immediately after create_file without a further round`() {
        // Simulates the Phi-3 bug: model creates the test file correctly in round 2,
        // then hallucinates a second task (test-logs.kt) in round 3 which must never run.
        val backend = FakeBackend(
            """<tool_call name="list_files"><arg name="path">src/test</arg></tool_call>""",
            """<tool_call name="create_file"><arg name="path">src/test/FooTest.kt</arg><arg name="content">class FooTest { @Test fun test1() {} }</arg></tool_call>""",
            // Round 3 response — must never be reached
            """<tool_call name="write_file"><arg name="path">test-logs.kt</arg><arg name="content">garbage</arg></tool_call>""",
        )
        val fakeTools = FakeToolRegistry(mapOf(
            "list_files" to "src/test/",
            "create_file" to "Prepared new file for src/test/FooTest.kt\nclass FooTest { @Test fun test1() {} }",
        ))
        val loop = AgentLoop(backend, fakeTools)
        val result = loop.run("Create tests")
        assertEquals("Loop must stop after create_file, not launch round 3", 2, result.rounds)
        assertEquals(listOf("list_files", "create_file"), result.toolsExecuted)
        assertFalse("test-logs.kt must never be written", result.toolsExecuted.contains("write_file"))
    }

    @Test
    fun `loop stops immediately after write_file`() {
        val backend = FakeBackend(
            """<tool_call name="write_file"><arg name="path">src/Foo.kt</arg><arg name="content">class Foo { fun bar() = 42 }</arg></tool_call>""",
            // Should never run
            """<tool_call name="write_file"><arg name="path">unrelated.kt</arg><arg name="content">x</arg></tool_call>""",
        )
        val fakeTools = FakeToolRegistry(mapOf(
            "write_file" to "Prepared file replacement for src/Foo.kt\n---OLD---\n\n---NEW---\nclass Foo { fun bar() = 42 }",
        ))
        val loop = AgentLoop(backend, fakeTools)
        val result = loop.run("Write Foo")
        assertEquals(1, result.rounds)
        assertEquals(listOf("write_file"), result.toolsExecuted)
    }

    @Test
    fun `proposal is rejected when model hallucinated tool invocations into file content`() {
        // Simulates the LamPatikCodeTest.kt bug: model wrote `autorun(search_code("..."))` inside
        // the file body — tool-calling syntax confused with actual source code. Must be filtered out.
        val garbageContent = """
            package com.example.lampatikcode
            import org.junit.jupiter.api.*
            class LamPatikCodeTest {
                // Write tests here...
                autorun(search_code("// TODO: Add test f
            }
        """.trimIndent()
        val backend = FakeBackend(
            """<tool_call name="write_file"><arg name="path">test/LamPatikCodeTest.kt</arg><arg name="content">$garbageContent</arg></tool_call>""",
            // Round 2: model produces prose after rejection (loop continues)
            "I could not write the tests.",
        )
        val fakeTools = FakeToolRegistry(
            sequentialResults = listOf(
                "Prepared new file for test/LamPatikCodeTest.kt\n$garbageContent",
            )
        )
        val loop = AgentLoop(backend, fakeTools)
        val result = loop.run("Create tests")
        // Garbage proposal must be absent regardless of how many rounds ran
        assertTrue(
            "Proposal with tool invocation in content must be rejected",
            result.proposalPaths.isEmpty(),
        )
    }

    // ── Tests: think-leak suppression after tool rounds ──────────────────────

    @Test
    fun `think content does not leak into answer panel after tool rounds`() {
        // Simulates the code-review scenario from the bug report:
        // round 1 → list_files + read_file → round 2 returns only <think> with no answer/write call.
        // The reasoning must NOT appear in the answer panel.
        val backend = FakeBackend(
            """<tool_call name="list_files"><arg name="path">.</arg></tool_call>""",
            "<think>Okay let me plan... the search_filenames are empty. Wait maybe I should check again.</think>",
        )
        val loop = AgentLoop(backend, FakeToolRegistry(mapOf("list_files" to "src/Main.kt")))
        val result = loop.run("Code review")
        assertEquals(2, result.rounds)
        assertTrue(
            "Reasoning must not leak into answer panel after tool rounds",
            result.finalMessage.isBlank(),
        )
    }

    @Test
    fun `think content shows as fallback on round 1 when no tools and no answer`() {
        // If the model outputs only a think block with no tools and no answer text at all,
        // surface it rather than showing a blank panel — this is round 1 so no tools ran.
        val backend = FakeBackend("<think>I cannot help with this request.</think>")
        val loop = AgentLoop(backend, FakeToolRegistry())
        val result = loop.run("Do something")
        assertEquals(1, result.rounds)
        assertTrue("Should surface think content on round 1 with no tools", result.finalMessage.isNotBlank())
    }

    // ── Tests: prompt-too-long guard ─────────────────────────────────────────

    // ── Tests: git/read-only tools count as hasReadContent ───────────────────

    @Test
    fun `git_status tool call sets hasReadContent so loop does not demand a file write`() {
        // Before the fix, only read_file/search_code set hasReadContent. git_status was ignored,
        // so the agent would say "completed without producing a file change" for git queries.
        val backend = FakeBackend(
            """<tool_call name="git_status"></tool_call>""",
            "Your working tree is clean.",
        )
        val fakeTools = FakeToolRegistry(mapOf("git_status" to "nothing to commit"))
        val loop = AgentLoop(backend, fakeTools)
        val result = loop.run("Show me git status")
        assertEquals(2, result.rounds)
        assertEquals(listOf("git_status"), result.toolsExecuted)
        assertEquals("Your working tree is clean.", result.finalMessage)
    }

    @Test
    fun `TOOL_RESULTS_MAX_CHARS cap prevents oversized next-round prompt`() {
        val hugeResult = "A".repeat(10_000)
        val backend = FakeBackend(
            """<tool_call name="read_file"><arg name="path">big.kt</arg></tool_call>""",
            "Done.",
        )
        val fakeTools = FakeToolRegistry(mapOf("read_file" to hugeResult))
        val loop = AgentLoop(backend, fakeTools, toolResultsMaxChars = 4000, toolResultContentMaxChars = 1200)
        loop.run("Read")
        val round2Msg = backend.capturedRequests[1].messages.first().content
        assertTrue(
            "Total tool results in round-2 prompt must be capped",
            round2Msg.length <= 4000 + 500, // 4000 for results + ~500 for task + directive overhead
        )
    }

    // ── Tests: cross-round read-only dedup ───────────────────────────────────

    @Test
    fun `duplicate read_file call in round 2 returns synthetic result without re-executing`() {
        val backend = FakeBackend(
            // Round 1: read the file
            """<tool_call name="read_file"><arg name="path">src/Foo.kt</arg></tool_call>""",
            // Round 2: model asks for the same file again
            """<tool_call name="read_file"><arg name="path">src/Foo.kt</arg></tool_call>""",
            // Round 3: prose (termination)
            "Done reviewing.",
        )
        val fakeTools = FakeToolRegistry(mapOf("read_file" to "class Foo {}"))
        val loop = AgentLoop(backend, fakeTools)
        val result = loop.run("Review Foo.kt")
        // read_file should only be executed once — the second call is dedup synthetic
        assertEquals("read_file should only be executed once", 1, result.toolsExecuted.count { it == "read_file" })
    }

    @Test
    fun `pre-seeded read path is treated as already-executed on first call`() {
        // Simulate: file content is already in the system prompt (like @{file} attachment).
        // The model asks for it anyway — should get synthetic result, not real execution.
        val backend = FakeBackend(
            """<tool_call name="read_file"><arg name="path">src/Attached.kt</arg></tool_call>""",
            "I reviewed the attached file.",
        )
        val fakeTools = FakeToolRegistry(mapOf("read_file" to "class Attached {}"))
        val loop = AgentLoop(backend, fakeTools)
        val result = loop.run("Review Attached.kt", preSeedReadPaths = setOf("src/Attached.kt"))
        assertEquals("Pre-seeded path must not trigger real read_file execution", 0, result.toolsExecuted.size)
    }

    // ── Tests: all-duplicate escalation ─────────────────────────────────────

    @Test
    fun `all-duplicate round escalates to write pass instead of continuing normally`() {
        // Round 1: read_file (real)
        // Round 2: same read_file again → all-duplicate → escalate to write pass (round 4 = MAX_ROUNDS-1)
        // Round 4 (write pass): write_file produces valid proposal
        val backend = FakeBackend(
            // Round 1
            """<tool_call name="read_file"><arg name="path">src/Foo.kt</arg></tool_call>""",
            // Round 2 — duplicate read, triggers escalation
            """<tool_call name="read_file"><arg name="path">src/Foo.kt</arg></tool_call>""",
            // Write pass (escalated round)
            """<tool_call name="write_file"><arg name="path">src/Foo.kt</arg><arg name="content">class Foo { fun fixed() = 1 }</arg></tool_call>""",
        )
        val fakeTools = FakeToolRegistry(mapOf(
            "read_file" to "class Foo {}",
            "write_file" to "Prepared file replacement for src/Foo.kt\n---OLD---\nclass Foo {}\n---NEW---\nclass Foo { fun fixed() = 1 }",
        ))
        val loop = AgentLoop(backend, fakeTools, maxRounds = 5)
        val result = loop.run("Fix Foo.kt")
        assertTrue("write_file must be executed in the escalated write pass", result.toolsExecuted.contains("write_file"))
        assertTrue("Proposal for Foo.kt must be present", result.proposalPaths.contains("src/Foo.kt"))
    }

    // ── Tests: mid-loop prose escalation ────────────────────────────────────

    @Test
    fun `mid-loop prose after multiple read rounds escalates to write pass`() {
        // Round 1: list_files, Round 2: read_file, Round 3: prose (guard fires at round > 2)
        // Write pass (maxRounds-1 = 4): write_file produces valid proposal.
        val backend = FakeBackend(
            // Round 1: list
            """<tool_call name="list_files"><arg name="path">src</arg></tool_call>""",
            // Round 2: read
            """<tool_call name="read_file"><arg name="path">src/Bar.kt</arg></tool_call>""",
            // Round 3: model returns prose mid-loop
            "The class Bar looks good, but I should add a method.",
            // Write pass (escalated round 4)
            """<tool_call name="write_file"><arg name="path">src/Bar.kt</arg><arg name="content">class Bar { fun added() = 2 }</arg></tool_call>""",
        )
        val fakeTools = FakeToolRegistry(mapOf(
            "list_files" to "src/Bar.kt",
            "read_file" to "class Bar {}",
            "write_file" to "Prepared file replacement for src/Bar.kt\n---OLD---\nclass Bar {}\n---NEW---\nclass Bar { fun added() = 2 }",
        ))
        val loop = AgentLoop(backend, fakeTools, maxRounds = 5)
        val result = loop.run("Improve Bar.kt")
        assertTrue("write_file must be executed after mid-loop prose escalation", result.toolsExecuted.contains("write_file"))
        assertTrue("Proposal for Bar.kt must be present", result.proposalPaths.contains("src/Bar.kt"))
    }

    @Test
    fun `mid-loop prose on round 1 does NOT escalate — it is a normal Q&A answer`() {
        // Round 1 prose is a legitimate answer (user asked a question, model answered).
        // The guard must not fire on round == 1.
        val backend = FakeBackend("Here is my answer to your question.")
        val loop = AgentLoop(backend, FakeToolRegistry(), maxRounds = 5)
        val result = loop.run("What does this code do?")
        assertEquals(1, result.rounds)
        assertEquals("Here is my answer to your question.", result.finalMessage)
        assertTrue(result.toolsExecuted.isEmpty())
    }

    @Test
    fun `mid-loop prose on round 2 does NOT escalate — simple read-then-answer pattern`() {
        // 1 read round + prose answer is normal Q&A and must NOT be treated as mid-loop prose.
        val backend = FakeBackend(
            """<tool_call name="read_file"><arg name="path">src/Qux.kt</arg></tool_call>""",
            "The class Qux does X.",
        )
        val loop = AgentLoop(backend, FakeToolRegistry(mapOf("read_file" to "class Qux {}")), maxRounds = 5)
        val result = loop.run("Explain Qux.kt")
        assertEquals(2, result.rounds)
        assertEquals("The class Qux does X.", result.finalMessage)
        assertFalse("write_file must NOT be called for a simple read+answer", result.toolsExecuted.contains("write_file"))
    }

    @Test
    fun `mid-loop prose on penultimate round does NOT re-enter escalation`() {
        // When the escalated write pass itself returns prose, the guard must not
        // fire again (round == MAX_ROUNDS - 1, so round < maxRounds - 1 is false).
        // The loop must surface the final message, not loop indefinitely.
        val backend = FakeBackend(
            // Round 1: list, Round 2: read → prose at round 3 triggers escalation to round 4
            """<tool_call name="list_files"><arg name="path">src</arg></tool_call>""",
            """<tool_call name="read_file"><arg name="path">src/Baz.kt</arg></tool_call>""",
            "I see the file now.",
            // Round 4 (write pass): model STILL returns prose — must terminate, not re-escalate
            "I cannot write this file.",
        )
        val fakeTools = FakeToolRegistry(mapOf(
            "list_files" to "src/Baz.kt",
            "read_file" to "class Baz {}",
        ))
        val loop = AgentLoop(backend, fakeTools, maxRounds = 5)
        val result = loop.run("Fix Baz.kt")
        // Must terminate without looping — proposalPaths empty is fine, loop must stop
        assertTrue("Loop must terminate, not re-escalate indefinitely", result.rounds <= 5)
        assertTrue("No proposal expected when write pass also returns prose", result.proposalPaths.isEmpty())
    }

    // ── Tests: noThink on final round ────────────────────────────────────────

    @Test
    fun `final round request has noThink set to true`() {
        // maxRounds = 3 → final round = 3 → isFinalRound when round + 1 >= maxRounds, i.e. round 2
        val backend = FakeBackend(
            """<tool_call name="read_file"><arg name="path">src/X.kt</arg></tool_call>""",
            "Done.",
        )
        val loop = AgentLoop(backend, FakeToolRegistry(mapOf("read_file" to "class X {}")), maxRounds = 3)
        loop.run("Review X.kt")
        // Round 2 is the penultimate round (round + 1 = 3 = maxRounds) — noThink must be true
        val round2Request = backend.capturedRequests.getOrNull(1)
        assertNotNull("Round 2 request must exist", round2Request)
        assertTrue("noThink must be true on the final round", round2Request!!.noThink)
    }
}
