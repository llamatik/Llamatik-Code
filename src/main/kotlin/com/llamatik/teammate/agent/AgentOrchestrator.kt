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
import com.llamatik.teammate.skills.SkillService
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
    private val skillService = service<SkillService>()

    fun selectedModelLabel(): String = backend.selectedModelLabel()
    fun isModelLoaded(): Boolean = backend.isModelLoaded()

    fun submit(
        session: ChatSession,
        userPrompt: String,
        onToken: (String) -> Unit,
        onThinkingToken: ((String) -> Unit)? = null,
        onFinished: (ChatMessage, List<EditProposal>) -> Unit,
        onError: (String) -> Unit,
    ): GenerationHandle {
        val settings = service<LlamatikAppSettings>().data()
        val cancelled = AtomicBoolean(false)
        // Tracks whichever backend call is currently active so cancel() always reaches the live handle.
        val activeHandle = AtomicReference<GenerationHandle>()

        // Route <think>…</think> tokens to onThinkingToken; answer tokens to onToken.
        // The buffer always holds content that hasn't been dispatched yet.
        val tokenBuffer = StringBuilder()
        val inThink = AtomicBoolean(false)
        fun flushAnswerBuffer(buf: String) {
            if (buf.isNotEmpty()) onToken(buf)
        }
        fun filteredToken(token: String) {
            tokenBuffer.append(token)
            // Keep processing as long as there's actionable content in the buffer
            while (tokenBuffer.isNotEmpty()) {
                val buf = tokenBuffer.toString()
                if (!inThink.get()) {
                    val openIdx = buf.indexOf("<think>")
                    if (openIdx >= 0) {
                        // Flush everything before the tag as answer text
                        if (openIdx > 0) flushAnswerBuffer(buf.substring(0, openIdx))
                        inThink.set(true)
                        tokenBuffer.clear()
                        tokenBuffer.append(buf.substring(openIdx + "<think>".length))
                        continue
                    }
                    // Hold back any buffer tail that is a prefix of '<think>'
                    val thinkOpen = "<think>"
                    val holdLen = (1..minOf(thinkOpen.length, buf.length)).reversed()
                        .firstOrNull { len -> buf.endsWith(thinkOpen.take(len)) } ?: 0
                    val safeEnd = buf.length - holdLen
                    if (safeEnd > 0) {
                        flushAnswerBuffer(buf.substring(0, safeEnd))
                        tokenBuffer.clear()
                        tokenBuffer.append(buf.substring(safeEnd))
                    }
                    // else: entire buffer looks like start of '<think>' — hold everything
                    break
                } else {
                    // Inside think block — look for closing tag
                    val closeIdx = buf.indexOf("</think>")
                    if (closeIdx >= 0) {
                        val thinkContent = buf.substring(0, closeIdx)
                        if (thinkContent.isNotEmpty()) onThinkingToken?.invoke(thinkContent)
                        inThink.set(false)
                        tokenBuffer.clear()
                        tokenBuffer.append(buf.substring(closeIdx + "</think>".length))
                        continue
                    }
                    // Still inside think — forward all accumulated content
                    onThinkingToken?.invoke(buf)
                    tokenBuffer.clear()
                    break
                }
            }
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
                    val toolCalls = ToolCallParser.parse(fullText).take(6)
                    if (toolCalls.isEmpty()) {
                        val proposals = extractEditProposals(fullText)
                        val clean = ToolCallParser.cleanForDisplay(fullText)
                        onFinished(
                            ChatMessage(role = ChatRole.ASSISTANT, content = clean, editProposalId = proposals.firstOrNull()?.id),
                            proposals,
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
                        "[${msg.toolName}]:\n${msg.content.take(2500)}"
                    }
                    val secondPassMessages = listOf(
                        ChatMessage(role = ChatRole.SYSTEM, content = systemPrompt),
                        ChatMessage(role = ChatRole.USER, content = userPrompt.take(2000)),
                        ChatMessage(role = ChatRole.SYSTEM, content = contextHint),
                        ChatMessage(
                            role = ChatRole.SYSTEM,
                            content = "Tool results:\n${toolResultsSummary.take(12000)}\n\n" +
                                "You now have all the information needed. You MAY use <think>…</think> briefly to plan your code. " +
                                "Then write ONE <edit_proposal> block for EACH file that must be created or modified — do not skip any file. " +
                                "Do NOT end your response with only prose. Write every file in full:\n" +
                                "<edit_proposal path=\"path/to/FileA.kt\" summary=\"desc\">\n" +
                                "<new_content><![CDATA[...full content...]]></new_content>\n" +
                                "</edit_proposal>\n" +
                                "<edit_proposal path=\"path/to/FileB.kt\" summary=\"desc\">\n" +
                                "<new_content><![CDATA[...full content...]]></new_content>\n" +
                                "</edit_proposal>",
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
                                val proposals = extractEditProposals(fullText)
                                val answer = ToolCallParser.cleanForDisplay(fullText)
                                onFinished(
                                    ChatMessage(role = ChatRole.ASSISTANT, content = answer, editProposalId = proposals.firstOrNull()?.id),
                                    proposals,
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
        val currentFilePath = contextService.getCurrentFile()
        appendLine("- currentFile=${currentFilePath ?: "none"}")
        // Include the content of the open file so the model can write code that matches it
        if (currentFilePath != null) {
            val content = runCatching { contextService.readFile(currentFilePath) }.getOrNull()
            if (!content.isNullOrBlank()) {
                appendLine("- currentFileContent:")
                appendLine(content.take(3000))
            }
        }
        val selected = contextService.getSelectedText()?.take(600)
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
        appendLine("CRITICAL RULES:")
        appendLine("1. You MAY use <think>…</think> to reason briefly. Keep it under 15 sentences.")
        appendLine("2. After </think> (or immediately if you skip thinking), you MUST produce your actual answer — tool calls OR edit_proposal blocks OR a direct answer. NEVER end after </think> with only prose describing what you plan to do.")
        appendLine("3. When writing code in <edit_proposal>, write the COMPLETE file — do not truncate.")
        appendLine("4. Use currentFileContent to write code matching the actual project (correct package, imports, class names). Never use placeholder or dummy code.")
        appendLine("5. Multi-file changes: call list_files to discover the project structure, then read_file for EVERY file you need to see — all tool calls in the SAME response. Never assume file contents you haven't read.")
        appendLine("Use tools for project data. You may emit up to 6 tool calls in one response. Format:")
        appendLine("<tool_call name=\"read_file\"><arg name=\"path\">src/Main.kt</arg></tool_call>")
        appendLine("Tools: ${toolRegistry.describeTools().take(800)}")
        appendLine("When proposing changes: emit ONE <edit_proposal> block for EACH file that must be created or modified. Do not skip any file — if a task requires 4 files, write 4 proposals.")
        appendLine("Each proposal uses exactly this format:")
        appendLine("<edit_proposal path=\"rel/path/to/File.kt\" summary=\"short description of change\">")
        appendLine("<new_content><![CDATA[...full new file content...]]></new_content>")
        appendLine("</edit_proposal>")
        appendLine("The user will review a diff for each file and approve or reject individually before any change is written.")
        val skillsAddition = skillService.buildSkillsPromptAddition()
        if (skillsAddition.isNotBlank()) append(skillsAddition)
    }

    private val editRegex = Regex(
        "<edit_proposal\\s+path=\\\"([^\\\"]+)\\\"\\s+summary=\\\"([^\\\"]*)\\\">\\s*<new_content><!\\[CDATA\\[(.*?)]]></new_content>\\s*</edit_proposal>",
        RegexOption.DOT_MATCHES_ALL,
    )

    private val editHeaderRegex = Regex(
        "<edit_proposal\\s+path=\\\"([^\\\"]+)\\\"\\s+summary=\\\"([^\\\"]*)\\\">",
    )

    private fun extractEditProposals(text: String): List<EditProposal> {
        val complete = editRegex.findAll(text).mapNotNull { match ->
            buildProposal(match.groupValues[1], match.groupValues[2], match.groupValues[3])
        }.toList()
        if (complete.isNotEmpty()) return complete

        // Fallback: generation was truncated before closing tags — extract whatever content arrived.
        return editHeaderRegex.findAll(text).mapNotNull { headerMatch ->
            val path = headerMatch.groupValues[1]
            val summary = headerMatch.groupValues[2]
            val afterHeader = text.substring(headerMatch.range.last + 1)
            val cdataIdx = afterHeader.indexOf("<![CDATA[")
            val content = if (cdataIdx >= 0) {
                val raw = afterHeader.substring(cdataIdx + "<![CDATA[".length)
                val closing = raw.indexOf("]]>")
                if (closing >= 0) raw.substring(0, closing) else raw.trimEnd()
            } else ""
            buildProposal(path, summary, content)
        }.toList()
    }

    private fun buildProposal(path: String, summary: String, newContent: String): EditProposal? {
        val trimmedPath = path.trim()
        if (!trimmedPath.contains('.')) return null
        val oldContent = runCatching { contextService.readFile(trimmedPath) }.getOrDefault("")
        return EditProposal(filePath = trimmedPath, oldContent = oldContent, newContent = newContent, summary = summary)
    }
}
