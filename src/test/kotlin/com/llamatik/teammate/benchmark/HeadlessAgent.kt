package com.llamatik.teammate.benchmark

import com.llamatik.teammate.agent.AgentLoopTest
import com.llamatik.teammate.agent.RoundDirective
import com.llamatik.teammate.backend.AssistantBackend
import com.llamatik.teammate.backend.BackendConfig
import com.llamatik.teammate.backend.GenerationCallbacks
import com.llamatik.teammate.backend.GenerationRequest
import com.llamatik.teammate.model.ChatMessage
import com.llamatik.teammate.model.ChatRole
import com.llamatik.teammate.tools.ToolCall
import com.llamatik.teammate.tools.ToolCallParser
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Headless benchmark agent.
 *
 * Reuses the same agent loop logic as [AgentLoopTest.AgentLoop] (which mirrors
 * AgentOrchestrator.runRound), but operates on a real filesystem rooted at [projectRoot]
 * instead of an IntelliJ Project.  The backend and config are supplied by the caller.
 *
 * This is intentionally test-only code and must never be shipped in the plugin JAR.
 */
class HeadlessAgent(
    private val backend: AssistantBackend,
    private val config: BackendConfig,
    private val projectRoot: File,
    private val maxRounds: Int = 5,
    private val maxToolCallsPerRound: Int = 4,
) {
    data class RunResult(
        val finalMessage: String,
        val rounds: Int,
        val toolsExecuted: List<String>,
        val changedPaths: List<String>,
        val error: String?,
    )

    private val editToolNames = setOf("write_file", "replace_in_file", "create_file")
    private val readOnlyToolNames = setOf(
        "list_files", "read_file", "search_code", "search_filenames",
        "git_status", "git_diff", "git_changed_files", "git_branch",
    )
    private val knownToolNames = editToolNames + readOnlyToolNames

    fun run(prompt: String, timeoutMinutes: Int): RunResult {
        val allToolCalls = mutableListOf<String>()
        val writtenPaths = mutableListOf<String>()
        var finalMessage = ""
        var runError: String? = null
        var totalRounds = 0

        val deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(timeoutMinutes.toLong())

        fun checkTimeout() {
            if (System.currentTimeMillis() > deadline) throw TimeoutException("Benchmark timeout after $timeoutMinutes min")
        }

        val systemPrompt = buildSystemPrompt()

        fun runRound(messages: List<ChatMessage>, round: Int, hasReadContent: Boolean = false) {
            if (round > maxRounds) return
            totalRounds = round
            checkTimeout()

            val roundSystemPrompt = if (round == 1) systemPrompt else buildSecondPassSystemPrompt()

            var completed = false
            var error: String? = null

            backend.generate(
                GenerationRequest(
                    systemPrompt = roundSystemPrompt,
                    messages = messages,
                    config = config,
                ),
                object : GenerationCallbacks {
                    override fun onToken(token: String) {}

                    override fun onCompleted(fullText: String) {
                        completed = true
                        val answerText = ToolCallParser.stripThinking(fullText)
                        val toolCalls = ToolCallParser.parseIncludingIncomplete(answerText)
                            .filter { it.name in knownToolNames }
                            .take(maxToolCallsPerRound)

                        if (toolCalls.isEmpty() || round >= maxRounds) {
                            finalMessage = ToolCallParser.cleanForDisplay(fullText, allowThinkFallback = round == 1)
                            return
                        }

                        val toolMessages = toolCalls.map { call ->
                            allToolCalls += call.name
                            val result = executeTool(call)
                            if (call.name in editToolNames) {
                                extractWrittenPath(result)?.let { writtenPaths += it }
                            }
                            ChatMessage(role = ChatRole.TOOL, content = result, toolName = call.name)
                        }

                        val wroteFiles = toolCalls.any { it.name in editToolNames }
                        val allReadOnly = toolCalls.all { it.name !in editToolNames }
                        val nowHasRead = hasReadContent || toolCalls.any { it.name in readOnlyToolNames }

                        val directive = RoundDirective.get(RoundDirective.RoundState(
                            round = round,
                            maxRounds = maxRounds,
                            hasReadContent = nowHasRead,
                            wroteFilesThisRound = wroteFiles,
                            validProposalsProduced = wroteFiles,
                            allToolsWereReadOnly = allReadOnly,
                        ))

                        val toolResultsSummary = toolMessages.joinToString("\n") { msg ->
                            "<result tool=\"${msg.toolName}\">\n${msg.content.take(8000)}\n</result>"
                        }

                        val nextContent = buildString {
                            appendLine("Task: ${prompt.take(200)}")
                            appendLine("Tool results:")
                            appendLine(toolResultsSummary.take(16000))
                            appendLine()
                            append(directive)
                        }
                        runRound(listOf(ChatMessage(role = ChatRole.USER, content = nextContent)), round + 1, nowHasRead)
                    }

                    override fun onError(message: String, throwable: Throwable?) {
                        completed = true
                        error = message
                    }
                },
            )

            if (error != null) {
                runError = error
                finalMessage = "ERROR: $error"
            }
        }

        try {
            runRound(listOf(ChatMessage(role = ChatRole.USER, content = prompt)), 1)
        } catch (e: TimeoutException) {
            return RunResult("", totalRounds, allToolCalls, writtenPaths, "Timeout: ${e.message}")
        } catch (e: Exception) {
            return RunResult("", totalRounds, allToolCalls, writtenPaths, e.message ?: "Unknown error")
        }

        return RunResult(finalMessage, totalRounds, allToolCalls, writtenPaths, runError)
    }

    private fun executeTool(call: ToolCall): String = when (call.name) {
        "list_files" -> listFiles(call.arguments["path"])
        "read_file" -> readFile(call.arguments["path"] ?: return "Missing path")
        "search_code" -> searchCode(call.arguments["query"] ?: return "Missing query")
        "search_filenames" -> searchFilenames(call.arguments["query"] ?: return "Missing query")
        "write_file" -> writeFile(
            call.arguments["path"] ?: return "Missing path",
            call.arguments["content"] ?: return "Missing content",
        )
        "create_file" -> createFile(
            call.arguments["path"] ?: return "Missing path",
            call.arguments["content"] ?: return "Missing content",
        )
        "replace_in_file" -> replaceInFile(
            call.arguments["path"] ?: return "Missing path",
            call.arguments["old"] ?: return "Missing old",
            call.arguments["new"] ?: return "Missing new",
        )
        "git_status" -> runGit("status", "--short")
        "git_diff" -> runGit("diff")
        "git_changed_files" -> runGit("diff", "--name-only")
        "git_branch" -> runGit("branch", "--show-current")
        else -> "Unknown tool: ${call.name}"
    }

    private fun listFiles(path: String?): String {
        val base = if (path.isNullOrBlank() || path == ".") projectRoot
                   else File(projectRoot, path).takeIf { it.isDirectory } ?: return "No such directory: $path"
        val lines = mutableListOf<String>()
        base.walkTopDown().maxDepth(3).forEach { f ->
            if (f == base) return@forEach
            if (f.relativeTo(projectRoot).path.split(File.separatorChar).any { it in IGNORED }) return@forEach
            lines += f.relativeTo(projectRoot).path + if (f.isDirectory) "/" else ""
        }
        return lines.sorted().take(200).joinToString("\n")
    }

    private fun readFile(path: String): String {
        val file = File(projectRoot, path).takeIf { it.isFile } ?: return "File not found: $path"
        return file.readText()
    }

    private fun searchCode(query: String): String {
        val hits = mutableListOf<String>()
        projectRoot.walkTopDown().forEach { f ->
            if (!f.isFile) return@forEach
            if (f.relativeTo(projectRoot).path.split(File.separatorChar).any { it in IGNORED }) return@forEach
            if (f.readText().contains(query, ignoreCase = true)) hits += f.relativeTo(projectRoot).path
        }
        return hits.take(30).joinToString("\n").ifEmpty { "No matches for: $query" }
    }

    private fun searchFilenames(query: String): String {
        val hits = mutableListOf<String>()
        projectRoot.walkTopDown().forEach { f ->
            if (!f.isFile) return@forEach
            if (f.relativeTo(projectRoot).path.split(File.separatorChar).any { it in IGNORED }) return@forEach
            if (f.name.contains(query, ignoreCase = true)) hits += f.relativeTo(projectRoot).path
        }
        return hits.take(30).joinToString("\n").ifEmpty { "No matches for: $query" }
    }

    private fun writeFile(path: String, content: String): String {
        val file = File(projectRoot, path)
        val oldContent = if (file.isFile) file.readText() else ""
        file.parentFile?.mkdirs()
        file.writeText(content)
        return "Prepared file replacement for $path\n---OLD---\n$oldContent\n---NEW---\n$content"
    }

    private fun createFile(path: String, content: String): String {
        val file = File(projectRoot, path)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return "Prepared new file for $path\n$content"
    }

    private fun replaceInFile(path: String, old: String, new: String): String {
        val file = File(projectRoot, path).takeIf { it.isFile } ?: return "File not found: $path"
        val original = file.readText()
        if (!original.contains(old)) return "Pattern not found in $path"
        val updated = original.replace(old, new)
        file.writeText(updated)
        return "Prepared file replacement for $path\n---OLD---\n$original\n---NEW---\n$updated"
    }

    private fun runGit(vararg args: String): String {
        return try {
            val proc = ProcessBuilder(listOf("git") + args.toList())
                .directory(projectRoot)
                .redirectErrorStream(true)
                .start()
            proc.waitFor(10, TimeUnit.SECONDS)
            proc.inputStream.bufferedReader().readText().trim()
        } catch (e: Exception) {
            "git error: ${e.message}"
        }
    }

    private fun extractWrittenPath(toolResult: String): String? {
        return when {
            toolResult.startsWith("Prepared file replacement for ") ->
                toolResult.removePrefix("Prepared file replacement for ").substringBefore("\n").trim()
            toolResult.startsWith("Prepared new file for ") ->
                toolResult.removePrefix("Prepared new file for ").substringBefore("\n").trim()
            else -> null
        }
    }

    private fun buildSystemPrompt() = buildString {
        appendLine("You are Llamatik Code, a local IntelliJ coding agent.")
        appendLine("Tools: list_files, read_file, search_code, search_filenames, write_file, create_file, replace_in_file, git_status, git_diff")
        appendLine("Tool call format: <tool_call name=\"TOOL\"><arg name=\"ARG\">VALUE</arg></tool_call>")
        appendLine("Examples:")
        appendLine("  <tool_call name=\"read_file\"><arg name=\"path\">src/Main.kt</arg></tool_call>")
        appendLine("  <tool_call name=\"write_file\"><arg name=\"path\">src/Main.kt</arg><arg name=\"content\">…</arg></tool_call>")
    }

    private fun buildSecondPassSystemPrompt() = buildString {
        appendLine("You are Llamatik Code, a local IntelliJ coding agent.")
        appendLine("Respond with tool calls OR a plain-text answer.")
        appendLine("Tools: list_files, read_file, write_file, create_file, replace_in_file")
    }

    companion object {
        private val IGNORED = setOf(".gradle", "build", "out", ".idea", "node_modules", "target", ".git")
    }
}
