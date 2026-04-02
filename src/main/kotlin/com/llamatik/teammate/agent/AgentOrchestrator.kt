package com.llamatik.teammate.agent

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.backend.AssistantBackend
import com.llamatik.teammate.backend.BackendConfig
import com.llamatik.teammate.backend.GenerationCallbacks
import com.llamatik.teammate.backend.GenerationHandle
import com.llamatik.teammate.backend.GenerationRequest
import com.llamatik.teammate.context.ProjectContextService
import com.llamatik.teammate.llamatik.LlamatikBackendService
import com.llamatik.teammate.model.ChatMessage
import com.llamatik.teammate.model.ChatRole
import com.llamatik.teammate.model.ChatSession
import com.llamatik.teammate.model.EditProposal
import com.llamatik.teammate.settings.LlamatikAppSettings
import com.llamatik.teammate.tools.ToolCallParser
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolRegistry
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.PROJECT)
class AgentOrchestrator(private val project: Project) {
    private val backend: AssistantBackend = service<LlamatikBackendService>()
    private val toolRegistry = project.service<ToolRegistry>()
    private val contextService = ProjectContextService(project)

    fun selectedModelLabel(): String = backend.selectedModelLabel()
    fun isModelLoaded(): Boolean = backend.isModelLoaded()

    fun submit(
        session: ChatSession,
        userPrompt: String,
        onToken: (String) -> Unit,
        onThinkingToken: ((String) -> Unit)? = null,
        onFinished: (ChatMessage, EditProposal?) -> Unit,
        onError: (String) -> Unit,
    ): GenerationHandle {
        val settings = service<LlamatikAppSettings>().data()
        val cancelled = AtomicBoolean(false)
        // Tracks whichever backend call is currently active so cancel() always reaches the live handle.
        val activeHandle = AtomicReference<GenerationHandle>()

        // Route <think>…</think> tokens to onThinkingToken; answer tokens to onToken.
        val tokenBuffer = StringBuilder()
        val inThink = AtomicBoolean(false)
        fun filteredToken(token: String) {
            tokenBuffer.append(token)
            val buf = tokenBuffer.toString()
            // Detect opening tag
            if (!inThink.get() && buf.contains("<think>")) {
                // Emit everything before <think> as answer text
                val before = buf.substringBefore("<think>")
                if (before.isNotEmpty()) onToken(before)
                inThink.set(true)
                tokenBuffer.clear()
                // Keep any content after <think> in buffer in case </think> is in the same token
                tokenBuffer.append(buf.substringAfter("<think>"))
                // Re-check for closing tag
                val remaining = tokenBuffer.toString()
                if (remaining.contains("</think>")) {
                    val thinkContent = remaining.substringBefore("</think>")
                    if (thinkContent.isNotEmpty()) onThinkingToken?.invoke(thinkContent)
                    inThink.set(false)
                    val after = remaining.substringAfter("</think>")
                    tokenBuffer.clear()
                    if (after.isNotEmpty()) onToken(after)
                } else if (remaining.isNotEmpty()) {
                    onThinkingToken?.invoke(remaining)
                    tokenBuffer.clear()
                }
                return
            }
            if (inThink.get()) {
                if (buf.contains("</think>")) {
                    val thinkContent = buf.substringBefore("</think>")
                    if (thinkContent.isNotEmpty()) onThinkingToken?.invoke(thinkContent)
                    inThink.set(false)
                    val after = buf.substringAfter("</think>")
                    tokenBuffer.clear()
                    if (after.isNotEmpty()) onToken(after)
                } else {
                    // Still inside think block — forward to thinking callback
                    onThinkingToken?.invoke(buf)
                    tokenBuffer.clear()
                }
                return
            }
            // Not in think block — emit as answer token and clear buffer
            tokenBuffer.clear()
            onToken(token)
        }

        val compositeHandle = object : GenerationHandle {
            override fun cancel() {
                cancelled.set(true)
                activeHandle.get()?.cancel()
            }
            override val isCancelled: Boolean get() = cancelled.get()
        }

        // Keep total prompt well under n_batch (2048 tokens ≈ 6000 chars at ~3 chars/token).
        // Reserve ~1500 chars for the system prompt + context hint, leaving ~4500 for history.
        val systemPrompt = buildSystemPrompt()
        val contextHint = buildContextHint()
        val historyBudgetChars = 4500
        val recentMessages = fitMessagesInBudget(session.messages, historyBudgetChars)

        val toolAugmentedMessages = mutableListOf<ChatMessage>()
        toolAugmentedMessages += ChatMessage(role = ChatRole.SYSTEM, content = systemPrompt)
        toolAugmentedMessages += recentMessages
        toolAugmentedMessages += ChatMessage(role = ChatRole.USER, content = userPrompt.take(2000))
        toolAugmentedMessages += ChatMessage(role = ChatRole.SYSTEM, content = contextHint)

        val config = BackendConfig(
            modelIdOrPath = settings.modelPath.ifBlank { settings.modelIdentifier },
            temperature = settings.temperature,
            maxTokens = settings.maxTokens,
            contextSize = settings.contextSize,
            localOnly = settings.localOnly,
        )

        val firstHandle = backend.generate(
            GenerationRequest(systemPrompt = buildSystemPrompt(), messages = toolAugmentedMessages, config = config),
            object : GenerationCallbacks {
                override fun onToken(token: String) {
                    if (!cancelled.get()) filteredToken(token)
                }

                override fun onCompleted(fullText: String) {
                    if (cancelled.get()) return
                    val toolCalls = ToolCallParser.parse(fullText).take(4)
                    if (toolCalls.isEmpty()) {
                        val proposal = extractEditProposal(fullText)
                        val clean = ToolCallParser.cleanForDisplay(fullText)
                        onFinished(
                            ChatMessage(role = ChatRole.ASSISTANT, content = clean, editProposalId = proposal?.id),
                            proposal,
                        )
                        return
                    }

                    // Signal to the UI that tool execution is in progress
                    if (!cancelled.get()) onToken("\u0000STATUS:Gathering project context…")

                    val toolMessages = toolCalls.map { call ->
                        val result = toolRegistry.execute(call, ToolExecutionContext(project))
                        ChatMessage(role = ChatRole.TOOL, content = result.content, toolName = result.name)
                    }

                    if (cancelled.get()) return

                    if (!cancelled.get()) onToken("\u0000STATUS:Writing answer…")

                    // Second pass: keep only the immediate user turn + tool results to avoid
                    // doubling history size and exceeding n_batch.
                    val toolResultsSummary = toolMessages.joinToString("\n") { msg ->
                        "[${msg.toolName}]: ${msg.content.take(600)}"
                    }
                    val secondPassMessages = listOf(
                        ChatMessage(role = ChatRole.SYSTEM, content = systemPrompt),
                        ChatMessage(role = ChatRole.USER, content = userPrompt.take(2000)),
                        ChatMessage(
                            role = ChatRole.SYSTEM,
                            content = "Tool results:\n${toolResultsSummary.take(2000)}\n\n" +
                                "Answer the user concisely using these results. " +
                                "If proposing a file change use exactly: " +
                                "<edit_proposal path=\"rel/path\" summary=\"desc\">" +
                                "<new_content><![CDATA[...]]></new_content></edit_proposal>",
                        ),
                    )

                    val secondHandle = backend.generate(
                        GenerationRequest(
                            systemPrompt = buildSystemPrompt(),
                            messages = secondPassMessages,
                            config = config,
                        ),
                        object : GenerationCallbacks {
                            override fun onToken(token: String) {
                                if (!cancelled.get()) filteredToken(token)
                            }

                            override fun onCompleted(fullText: String) {
                                if (cancelled.get()) return
                                val proposal = extractEditProposal(fullText)
                                val answer = ToolCallParser.cleanForDisplay(fullText)
                                onFinished(
                                    ChatMessage(role = ChatRole.ASSISTANT, content = answer, editProposalId = proposal?.id),
                                    proposal,
                                )
                            }

                            override fun onError(message: String, throwable: Throwable?) {
                                if (!cancelled.get()) onError(message)
                            }
                        },
                    )
                    activeHandle.set(secondHandle)
                    // If cancel was requested between the two passes, forward it now.
                    if (cancelled.get()) secondHandle.cancel()
                }

                override fun onError(message: String, throwable: Throwable?) {
                    if (!cancelled.get()) onError(message)
                }
            },
        )
        activeHandle.set(firstHandle)

        return compositeHandle
    }

    private fun buildContextHint(): String = buildString {
        appendLine("Project context:")
        appendLine("- project=${contextService.projectName()}")
        appendLine("- currentFile=${contextService.getCurrentFile() ?: "none"}")
        val selected = contextService.getSelectedText()?.take(400)
        if (!selected.isNullOrBlank()) appendLine("- selectedText=${selected}")
        val pinned = contextService.pinnedInstructions()
        if (pinned.isNotBlank()) appendLine("- instructions=${pinned.take(200)}")
    }

    /**
     * Returns the most recent messages that fit within [budgetChars] total content length,
     * always keeping at least the last user message.
     */
    private fun fitMessagesInBudget(messages: List<ChatMessage>, budgetChars: Int): List<ChatMessage> {
        if (messages.isEmpty()) return emptyList()
        val recent = messages.takeLast(20)
        var used = 0
        val result = mutableListOf<ChatMessage>()
        for (msg in recent.asReversed()) {
            val len = msg.content.length
            if (used + len > budgetChars && result.isNotEmpty()) break
            result.add(0, msg)
            used += len
        }
        return result
    }

    private fun buildSystemPrompt(): String = buildString {
        appendLine("You are Llamatik Code, a local IntelliJ coding agent.")
        appendLine("IMPORTANT: Always write your final answer AFTER any </think> block. Never end your response inside a <think> block.")
        appendLine("Use tools for project data. Emit tool calls like:")
        appendLine("<tool_call name=\"read_file\"><arg name=\"path\">src/Main.kt</arg></tool_call>")
        appendLine("Tools: ${toolRegistry.describeTools().take(800)}")
        appendLine("Keep answers concise. At most one file edit proposal per response, using:")
        append("<edit_proposal path=\"rel/path\" summary=\"desc\"><new_content><![CDATA[...]]></new_content></edit_proposal>")
    }

    private val editRegex = Regex(
        "<edit_proposal\\s+path=\\\"([^\\\"]+)\\\"\\s+summary=\\\"([^\\\"]*)\\\">\\s*<new_content><!\\[CDATA\\[(.*?)]]></new_content>\\s*</edit_proposal>",
        RegexOption.DOT_MATCHES_ALL,
    )

    private fun extractEditProposal(text: String): EditProposal? {
        val match = editRegex.find(text) ?: return null
        val path = match.groupValues[1].trim()
        val summary = match.groupValues[2]
        val newContent = match.groupValues[3]
        // Reject paths that don't look like files (no extension = likely a directory)
        if (!path.contains('.')) return null
        val oldContent = runCatching { contextService.readFile(path) }.getOrDefault("")
        return EditProposal(filePath = path, oldContent = oldContent, newContent = newContent, summary = summary)
    }
}
