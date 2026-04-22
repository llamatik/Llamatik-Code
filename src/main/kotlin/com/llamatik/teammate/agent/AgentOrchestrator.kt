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
import com.llamatik.teammate.llamatik.MultimodalBackendService
import com.llamatik.teammate.model.AttachedImage
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
    private val multimodalBackend = service<MultimodalBackendService>()
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
        images: List<AttachedImage> = emptyList(),
        onTokenCount: ((Int) -> Unit)? = null,
    ): GenerationHandle {
        // Route to multimodal pipeline when images are attached
        if (images.isNotEmpty()) {
            return submitMultimodal(
                userPrompt = userPrompt,
                images = images,
                onToken = onToken,
                onThinkingToken = onThinkingToken,
                onFinished = onFinished,
                onError = onError,
            )
        }
        val settings = service<LlamatikAppSettings>().data()
        val cancelled = AtomicBoolean(false)
        // Tracks whichever backend call is currently active so cancel() always reaches the live handle.
        val activeHandle = AtomicReference<GenerationHandle>()

        // Route <think>…</think> tokens to onThinkingToken; answer tokens to onToken.
        // The buffer always holds content that hasn't been dispatched yet.
        val tokenBuffer = StringBuilder()
        val inThink = AtomicBoolean(false)
        // Once a blocked tag prefix is detected, drop all subsequent tokens for that pass.
        // Reset via resetStreamState() before each new generation pass.
        val streamBlocked = AtomicBoolean(false)
        val uiBlockedPrefixes = listOf("<tool_call", "<edit_proposal", "<Tool_call", "<TOOL_CALL", "<end_of_turn>", "<eos>")
        val holdTags = listOf("<think>", "<edit_proposal", "<tool_call", "<Tool_call", "<TOOL_CALL", "<end_of_turn>", "<eos>")
        val maxHoldTagLen = holdTags.maxOf { it.length }
        fun resetStreamState() {
            tokenBuffer.clear()
            inThink.set(false)
            streamBlocked.set(false)
        }
        fun flushAnswerBuffer(buf: String) {
            if (buf.isEmpty()) return
            val bufLower = buf.lowercase()
            val cutAt = uiBlockedPrefixes.mapNotNull { p -> bufLower.indexOf(p.lowercase()).takeIf { it >= 0 } }.minOrNull()
            if (cutAt != null) {
                val safe = buf.substring(0, cutAt).trimEnd()
                if (safe.isNotEmpty()) onToken(safe)
                streamBlocked.set(true)
            } else {
                onToken(buf)
            }
        }
        fun filteredToken(token: String) {
            if (streamBlocked.get()) return
            tokenBuffer.append(token)
            // Keep processing as long as there's actionable content in the buffer
            while (tokenBuffer.isNotEmpty()) {
                val buf = tokenBuffer.toString()
                if (!inThink.get()) {
                    val openIdx = buf.indexOf("<think>")
                    if (openIdx >= 0) {
                        // Flush everything before the tag as answer text
                        if (openIdx > 0) flushAnswerBuffer(buf.substring(0, openIdx))
                        if (streamBlocked.get()) { tokenBuffer.clear(); break }
                        inThink.set(true)
                        tokenBuffer.clear()
                        tokenBuffer.append(buf.substring(openIdx + "<think>".length))
                        continue
                    }
                    // Hold back any buffer tail that is a partial prefix of ANY intercepted tag.
                    val holdLen = (1..minOf(maxHoldTagLen, buf.length)).reversed()
                        .firstOrNull { len -> holdTags.any { tag -> buf.lowercase().endsWith(tag.lowercase().take(len)) } } ?: 0
                    val safeEnd = buf.length - holdLen
                    if (safeEnd > 0) {
                        flushAnswerBuffer(buf.substring(0, safeEnd))
                        tokenBuffer.clear()
                        if (!streamBlocked.get()) tokenBuffer.append(buf.substring(safeEnd))
                    }
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

        var rawTokenCount = 0
        fun countToken() { onTokenCount?.invoke(++rawTokenCount) }

        val compositeHandle = object : GenerationHandle {
            override fun cancel() {
                cancelled.set(true)
                activeHandle.get()?.cancel()
            }
            override val isCancelled: Boolean get() = cancelled.get()
        }

        // Resolve @{path} file references typed in the composer.
        // cleanInstruction = user text with @{…} tokens replaced by bare filenames.
        // fileContext = fenced file contents to append into the system prompt.
        val (cleanInstruction, fileContext) = resolveFileReferences(userPrompt)

        // Build the single system prompt that the renderer will place at the top.
        // File context is appended here so it is always visible to the model and never
        // competes with the user instruction for the USER message character budget.
        val systemPrompt = buildString {
            append(buildSystemPrompt())
            if (fileContext.isNotBlank()) {
                appendLine()
                appendLine()
                appendLine("=== Attached files ===")
                append(fileContext.take(3000))
                appendLine()
                appendLine("=== End attached files ===")
            }
        }
        val contextHint = buildContextHint()
        val historyBudgetChars = 1200
        val recentMessages = fitMessagesInBudget(session.messages, historyBudgetChars)

        // messages list: no leading SYSTEM turn — the renderer takes systemPrompt separately.
        // Context hint is merged into the user turn so Gemma's chat template (which drops
        // ChatRole.SYSTEM messages) still sees it.
        val userContent = buildString {
            append(cleanInstruction.take(800))
            if (contextHint.isNotBlank()) {
                appendLine()
                appendLine()
                append(contextHint)
            }
        }
        val toolAugmentedMessages = mutableListOf<ChatMessage>()
        toolAugmentedMessages += recentMessages
        toolAugmentedMessages += ChatMessage(role = ChatRole.USER, content = userContent)

        val config = BackendConfig(
            modelIdOrPath = settings.modelPath.ifBlank { settings.modelIdentifier },
            temperature = settings.temperature,
            maxTokens = settings.maxTokens,
            contextLength = settings.contextSize,
            numThreads = settings.numThreads,
            useMmap = settings.useMmap,
            flashAttention = settings.flashAttention,
            batchSize = settings.batchSize,
            localOnly = settings.localOnly,
        )

        val firstHandle = backend.generate(
            GenerationRequest(systemPrompt = systemPrompt, messages = toolAugmentedMessages, config = config),
            object : GenerationCallbacks {
                override fun onToken(token: String) {
                    countToken()
                    if (!cancelled.get()) filteredToken(token)
                }

                override fun onCompleted(fullText: String) {
                    if (cancelled.get()) return
                    val answerText = ToolCallParser.stripThinking(fullText)
                    val toolCalls = ToolCallParser.parse(answerText).take(6)
                    if (toolCalls.isEmpty()) {
                        val proposals = extractEditProposals(answerText)
                        val clean = ToolCallParser.cleanForDisplay(answerText)
                        onFinished(
                            ChatMessage(role = ChatRole.ASSISTANT, content = clean, editProposalId = proposals.firstOrNull()?.id),
                            proposals,
                        )
                        return
                    }

                    // Signal to the UI that tool execution is in progress
                    if (!cancelled.get()) onToken(" STATUS:Gathering project context…")

                    val toolMessages = toolCalls.map { call ->
                        val mainArg = call.arguments.values.firstOrNull().orEmpty().take(60)
                        if (!cancelled.get()) onToken(" TOOL:${call.name}:$mainArg")
                        val result = toolRegistry.execute(call, ToolExecutionContext(project))
                        ChatMessage(role = ChatRole.TOOL, content = result.content, toolName = result.name)
                    }

                    if (cancelled.get()) return

                    if (!cancelled.get()) onToken(" STATUS:Writing answer…")

                    // Reset so second-pass answer tokens aren't dropped by streamBlocked set in pass 1.
                    resetStreamState()

                    // Second pass: keep only the immediate user turn + tool results to avoid
                    // doubling history size and exceeding n_batch (2048 tokens ≈ 6000 chars).
                    // Use a compact system prompt here — no full skills text — to stay within budget.
                    // Second-pass budget: systemPrompt ≤600, userPrompt ≤400, toolResults ≤2000
                    // → total ≤3000 chars + chat-template overhead ≤5400 (safely under 2048 tokens).
                    val toolResultsSummary = toolMessages.joinToString("\n") { msg ->
                        "[${msg.toolName}]:\n${msg.content.take(600)}"
                    }
                    val secondPassMessages = listOf(
                        ChatMessage(
                            role = ChatRole.USER,
                            content = buildString {
                                appendLine(cleanInstruction.take(400))
                                appendLine()
                                appendLine("Project: ${contextService.projectName()}")
                                appendLine("Tool results:")
                                appendLine(toolResultsSummary.take(2000))
                                appendLine()
                                appendLine("Write ONE <edit_proposal> per file changed. Full file content only:")
                                append("<edit_proposal path=\"rel/path/File.kt\" summary=\"desc\"><new_content><![CDATA[...full content...]]></new_content></edit_proposal>")
                            },
                        ),
                    )

                    val secondHandle = backend.generate(
                        GenerationRequest(
                            systemPrompt = buildSecondPassSystemPrompt(),
                            messages = secondPassMessages,
                            config = config,
                            noThink = true,
                        ),
                        object : GenerationCallbacks {
                            // Don't stream tokens to the UI — edit proposals surface via onCompleted/onFinished.
                            // Still count every raw token for the counter display.
                            override fun onToken(token: String) { countToken() }

                            override fun onCompleted(fullText: String) {
                                if (cancelled.get()) return
                                val answerText = ToolCallParser.stripThinking(fullText)
                                val proposals = extractEditProposals(answerText)
                                val answer = if (proposals.isNotEmpty() || answerText.contains("<edit_proposal", ignoreCase = true)) "" else ToolCallParser.cleanForDisplay(answerText)
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

    /**
     * Minimal system prompt for the second (tool-results) pass.
     * Must stay small — the second pass already carries tool results + user prompt,
     * and the entire prompt must fit within n_batch = 2048 tokens (≈ 6000 chars).
     */
    private fun buildSecondPassSystemPrompt(): String = buildString {
        appendLine("You are Llamatik Code, a local IntelliJ coding agent.")
        appendLine("Write COMPLETE file content inside each <edit_proposal>. Never truncate.")
        appendLine("Use package/imports/class names that match the existing project files.")
        val activeNames = skillService.activeSkills().joinToString(", ") { it.displayName }
        if (activeNames.isNotEmpty()) appendLine("Active skills (apply their conventions): $activeNames")
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
                appendLine(content.take(800))
            }
        }
        val selected = contextService.getSelectedText()?.take(300)
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
        appendLine("Use tools for project data. Up to 6 tool calls per response. Format:")
        appendLine("<tool_call name=\"read_file\"><arg name=\"path\">src/Main.kt</arg></tool_call>")
        appendLine("Tools: ${toolRegistry.describeTools().take(400)}")
        appendLine("Emit ONE <edit_proposal> per file changed:")
        appendLine("<edit_proposal path=\"rel/path/File.kt\" summary=\"desc\"><new_content><![CDATA[...]]></new_content></edit_proposal>")
        val skillsAddition = skillService.buildSkillsPromptAddition()
        if (skillsAddition.isNotBlank()) append(skillsAddition.take(400))
    }

    /**
     * Finds all `@{path}` tokens in [prompt] and reads each referenced file.
     *
     * Returns a [Pair] of:
     * - `first`  — the clean user instruction with `@{…}` tokens replaced by bare filenames
     * - `second` — fenced file contents to inject as a separate SYSTEM message (empty string if no refs)
     *
     * Keeping them separate ensures the user instruction is never truncated by the char budget.
     */
    private fun resolveFileReferences(prompt: String): Pair<String, String> {
        val tokenRegex = Regex("""@\{([^}]+)}""")
        val matches = tokenRegex.findAll(prompt).toList()
        if (matches.isEmpty()) return Pair(prompt, "")

        val attachments = StringBuilder()
        matches.forEach { match ->
            val path = match.groupValues[1].trim()
            val content = runCatching { contextService.readFile(path) }.getOrNull()
            if (!content.isNullOrBlank()) {
                attachments.appendLine("### File: $path")
                attachments.appendLine("```")
                attachments.appendLine(content.take(2000))
                attachments.appendLine("```")
                attachments.appendLine()
            }
        }

        // Replace @{path} tokens with just the filename so the instruction reads naturally
        val cleanInstruction = tokenRegex.replace(prompt) { match ->
            match.groupValues[1].substringAfterLast('/')
        }

        return Pair(cleanInstruction, attachments.trimEnd().toString())
    }

    // Matches path and summary in any order, with single or double quotes, arbitrary spacing.
    private val editRegex = Regex(
        "<edit_proposal\\b([^>]*)>\\s*<new_content>(?:<!\\[CDATA\\[(.*?)]]>|(.*?))</new_content>\\s*</edit_proposal>",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val attrPath    = Regex("""path\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val attrSummary = Regex("""summary\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)

    private val editHeaderRegex = Regex(
        "<edit_proposal\\b([^>]*)>",
    )

    private fun extractEditProposals(text: String): List<EditProposal> {
        val complete = editRegex.findAll(text).mapNotNull { match ->
            val attrs = match.groupValues[1]
            val path    = attrPath.find(attrs)?.groupValues?.get(1) ?: return@mapNotNull null
            val summary = attrSummary.find(attrs)?.groupValues?.get(1).orEmpty()
            // group 2 = CDATA content, group 3 = plain content
            val content = match.groupValues[2].ifEmpty { match.groupValues[3] }
            buildProposal(path, summary, content)
        }.toList()
        if (complete.isNotEmpty()) return complete

        // Fallback: generation was truncated before closing tags — extract whatever content arrived.
        return editHeaderRegex.findAll(text).mapNotNull { headerMatch ->
            val attrs   = headerMatch.groupValues[1]
            val path    = attrPath.find(attrs)?.groupValues?.get(1) ?: return@mapNotNull null
            val summary = attrSummary.find(attrs)?.groupValues?.get(1).orEmpty()
            val afterHeader = text.substring(headerMatch.range.last + 1)
            val content = extractNewContent(afterHeader)
            buildProposal(path, summary, content)
        }.toList()
    }

    private fun extractNewContent(afterHeader: String): String {
        // Try CDATA first (inside or outside <new_content>)
        val cdataIdx = afterHeader.indexOf("<![CDATA[")
        if (cdataIdx >= 0) {
            val raw = afterHeader.substring(cdataIdx + "<![CDATA[".length)
            val closing = raw.indexOf("]]>")
            return if (closing >= 0) raw.substring(0, closing).trim() else raw.trim()
        }
        // Plain <new_content>…</new_content> — strip any stray CDATA markers
        val openTag = "<new_content>"
        val closeTag = "</new_content>"
        val openIdx = afterHeader.indexOf(openTag)
        if (openIdx >= 0) {
            val raw = afterHeader.substring(openIdx + openTag.length)
            val closeIdx = raw.indexOf(closeTag)
            val content = if (closeIdx >= 0) raw.substring(0, closeIdx) else raw
            return content.trim().removePrefix("<![CDATA[").removeSuffix("]]>").trim()
        }
        return ""
    }

    private fun buildProposal(path: String, summary: String, newContent: String): EditProposal? {
        val trimmedPath = path.trim()
        if (!trimmedPath.contains('.')) return null
        val oldContent = runCatching { contextService.readFile(trimmedPath) }.getOrDefault("")
        val cleanContent = newContent.trim()
            .removePrefix("<![CDATA[").removeSuffix("]]>").trim()
        return EditProposal(filePath = trimmedPath, oldContent = oldContent, newContent = cleanContent, summary = summary)
    }

    /**
     * Simple chat-only generation — no tools, no second pass, no edit proposals.
     * Used by the Ask tab.
     */
    fun chat(
        session: ChatSession,
        userPrompt: String,
        onToken: (String) -> Unit,
        onFinished: (String) -> Unit,
        onError: (String) -> Unit,
        images: List<AttachedImage> = emptyList(),
    ): GenerationHandle {
        if (images.isNotEmpty()) {
            return submitMultimodal(
                userPrompt = userPrompt,
                images = images,
                onToken = onToken,
                onFinished = { msg, _ -> onFinished(msg.content) },
                onError = onError,
            )
        }
        val settings = service<LlamatikAppSettings>().data()
        val (cleanInstruction, fileContext) = resolveFileReferences(userPrompt)

        val systemPrompt = buildString {
            appendLine("You are Llamatik Code, a local AI coding assistant inside IntelliJ.")
            appendLine("Answer the user's question clearly and concisely. You may include code snippets.")
            appendLine("Do not use tool calls or edit proposals — only provide text answers.")
            val currentFilePath = contextService.getCurrentFile()
            if (currentFilePath != null) {
                appendLine("Current file: $currentFilePath")
                val content = runCatching { contextService.readFile(currentFilePath) }.getOrNull()
                if (!content.isNullOrBlank()) {
                    appendLine("File content:")
                    appendLine(content.take(800))
                }
            }
            val selected = contextService.getSelectedText()?.take(300)
            if (!selected.isNullOrBlank()) appendLine("Selected text: $selected")
            val pinned = contextService.pinnedInstructions()
            if (pinned.isNotBlank()) appendLine("Project instructions: ${pinned.take(200)}")
            if (fileContext.isNotBlank()) {
                appendLine("=== Attached files ===")
                append(fileContext.take(3000))
                appendLine("=== End attached files ===")
            }
        }

        // session.messages already contains the current user message (added by the caller before
        // invoking chat()), so we must not append it again here.
        val recentMessages = fitMessagesInBudget(session.messages, 1200)

        val config = BackendConfig(
            modelIdOrPath = settings.modelPath.ifBlank { settings.modelIdentifier },
            temperature = settings.temperature,
            maxTokens = settings.maxTokens,
            contextLength = settings.contextSize,
            numThreads = settings.numThreads,
            useMmap = settings.useMmap,
            flashAttention = settings.flashAttention,
            batchSize = settings.batchSize,
            localOnly = settings.localOnly,
        )

        val tokenBuffer = StringBuilder()
        val inThink = java.util.concurrent.atomic.AtomicBoolean(false)
        fun filteredChatToken(token: String) {
            tokenBuffer.append(token)
            while (tokenBuffer.isNotEmpty()) {
                val buf = tokenBuffer.toString()
                if (!inThink.get()) {
                    val openIdx = buf.indexOf("<think>")
                    if (openIdx >= 0) {
                        if (openIdx > 0) onToken(buf.substring(0, openIdx))
                        inThink.set(true)
                        tokenBuffer.clear()
                        tokenBuffer.append(buf.substring(openIdx + "<think>".length))
                        continue
                    }
                    val holdTags = listOf("<think>", "<end_of_turn>", "<eos>")
                    val maxHoldTagLen = holdTags.maxOf { it.length }
                    val holdLen = (1..minOf(maxHoldTagLen, buf.length)).reversed()
                        .firstOrNull { len -> holdTags.any { tag -> buf.lowercase().endsWith(tag.lowercase().take(len)) } } ?: 0
                    val safeEnd = buf.length - holdLen
                    if (safeEnd > 0) {
                        onToken(buf.substring(0, safeEnd))
                        tokenBuffer.clear()
                        tokenBuffer.append(buf.substring(safeEnd))
                    }
                    break
                } else {
                    val closeIdx = buf.indexOf("</think>")
                    if (closeIdx >= 0) {
                        inThink.set(false)
                        tokenBuffer.clear()
                        tokenBuffer.append(buf.substring(closeIdx + "</think>".length))
                        continue
                    }
                    tokenBuffer.clear()
                    break
                }
            }
        }

        return backend.generate(
            GenerationRequest(systemPrompt = systemPrompt, messages = recentMessages, config = config),
            object : GenerationCallbacks {
                override fun onToken(token: String) = filteredChatToken(token)
                override fun onCompleted(fullText: String) {
                    val answer = ToolCallParser.stripThinking(fullText).trim()
                    onFinished(answer)
                }
                override fun onError(message: String, throwable: Throwable?) = onError(message)
            },
        )
    }

    private fun submitMultimodal(
        userPrompt: String,
        images: List<AttachedImage>,
        onToken: (String) -> Unit,
        onThinkingToken: ((String) -> Unit)? = null,
        onFinished: (ChatMessage, List<EditProposal>) -> Unit,
        onError: (String) -> Unit,
    ): GenerationHandle {
        val cancelled = AtomicBoolean(false)
        val activeHandle = AtomicReference<GenerationHandle>()

        val compositeHandle = object : GenerationHandle {
            override fun cancel() {
                cancelled.set(true)
                activeHandle.get()?.cancel()
            }
            override val isCancelled: Boolean get() = cancelled.get()
        }

        val languageHint = run {
            val ext = contextService.getCurrentFile()?.substringAfterLast('.', "")?.lowercase()
            when (ext) {
                "kt", "kts" -> "Kotlin"
                "java" -> "Java"
                "py" -> "Python"
                "ts", "tsx" -> "TypeScript"
                "js", "jsx" -> "JavaScript"
                "swift" -> "Swift"
                "go" -> "Go"
                "rs" -> "Rust"
                "cpp", "cc", "cxx", "h", "hpp" -> "C++"
                "c" -> "C"
                "cs" -> "C#"
                "rb" -> "Ruby"
                else -> null
            }
        }

        val (cleanInstruction, fileContext) = resolveFileReferences(userPrompt)

        // Pass 1: multimodal model describes the changes shown in the image (plain text, no XML)
        val visionSystemPrompt = buildString {
            appendLine("You are a code-review assistant with vision.")
            appendLine("Describe precisely what code changes are shown in the image.")
            appendLine("List each change: which file, what to add/remove/modify, and the exact new code.")
            appendLine("Be specific and complete. Output plain text only, no XML tags.")
        }.trim()

        val settings = service<LlamatikAppSettings>().data()
        val config = BackendConfig(
            modelIdOrPath = settings.modelPath.ifBlank { settings.modelIdentifier },
            temperature = settings.temperature,
            maxTokens = settings.maxTokens,
            contextLength = settings.contextSize,
            numThreads = settings.numThreads,
            useMmap = settings.useMmap,
            flashAttention = settings.flashAttention,
            batchSize = settings.batchSize,
            localOnly = settings.localOnly,
        )

        val memErr = multimodalBackend.checkCombinedMemoryHeadroom(
            vlmModelPath = settings.modelPath,
            textModelPath = settings.modelPath,
            textContextLength = settings.contextSize,
        )
        if (memErr != null) {
            onError(memErr)
            return compositeHandle
        }

        // Token filter for pass 2: routes <think> to onThinkingToken, blocks edit/tool XML from UI
        val tokenBuffer = StringBuilder()
        val inThink = AtomicBoolean(false)
        val streamBlocked = AtomicBoolean(false)
        val uiBlockedPrefixes = listOf("<tool_call", "<edit_proposal", "<Tool_call", "<TOOL_CALL", "<end_of_turn>", "<eos>")
        val holdTags = listOf("<think>", "<edit_proposal", "<tool_call", "<Tool_call", "<TOOL_CALL", "<end_of_turn>", "<eos>")
        val maxHoldTagLen = holdTags.maxOf { it.length }
        fun flushAnswerBuffer(buf: String) {
            if (buf.isEmpty()) return
            val cutAt = uiBlockedPrefixes.mapNotNull { p -> buf.lowercase().indexOf(p.lowercase()).takeIf { it >= 0 } }.minOrNull()
            if (cutAt != null) {
                val safe = buf.substring(0, cutAt).trimEnd()
                if (safe.isNotEmpty()) onToken(safe)
                streamBlocked.set(true)
            } else {
                onToken(buf)
            }
        }
        fun filteredToken(token: String) {
            if (streamBlocked.get()) return
            tokenBuffer.append(token)
            while (tokenBuffer.isNotEmpty()) {
                val buf = tokenBuffer.toString()
                if (!inThink.get()) {
                    val openIdx = buf.indexOf("<think>")
                    if (openIdx >= 0) {
                        if (openIdx > 0) flushAnswerBuffer(buf.substring(0, openIdx))
                        if (streamBlocked.get()) { tokenBuffer.clear(); break }
                        inThink.set(true)
                        tokenBuffer.clear()
                        tokenBuffer.append(buf.substring(openIdx + "<think>".length))
                        continue
                    }
                    val holdLen = (1..minOf(maxHoldTagLen, buf.length)).reversed()
                        .firstOrNull { len -> holdTags.any { tag -> buf.lowercase().endsWith(tag.lowercase().take(len)) } } ?: 0
                    val safeEnd = buf.length - holdLen
                    if (safeEnd > 0) {
                        flushAnswerBuffer(buf.substring(0, safeEnd))
                        tokenBuffer.clear()
                        if (!streamBlocked.get()) tokenBuffer.append(buf.substring(safeEnd))
                    }
                    break
                } else {
                    val closeIdx = buf.indexOf("</think>")
                    if (closeIdx >= 0) {
                        val thinkContent = buf.substring(0, closeIdx)
                        if (thinkContent.isNotEmpty()) onThinkingToken?.invoke(thinkContent)
                        inThink.set(false)
                        tokenBuffer.clear()
                        tokenBuffer.append(buf.substring(closeIdx + "</think>".length))
                        continue
                    }
                    onThinkingToken?.invoke(buf)
                    tokenBuffer.clear()
                    break
                }
            }
        }

        onToken(" STATUS:Analyzing image…")
        val firstHandle = multimodalBackend.generateWithImages(
            systemPrompt = visionSystemPrompt,
            userPrompt = cleanInstruction.take(800),
            images = images,
            callbacks = object : GenerationCallbacks {
                override fun onToken(token: String) {
                    // Pass-1 description is internal context for pass 2; do not stream it to the UI
                }

                override fun onCompleted(fullText: String) {
                    if (cancelled.get()) return
                    val visionDescription = ToolCallParser.stripThinking(fullText)
                        .replace(Regex("</?start_of_turn>\\s*(user|model)?\\s*"), "")
                        .replace(Regex("</?end_of_turn>"), "")
                        .trim()

                    if (!cancelled.get()) onToken(" STATUS:Applying changes…")

                    // releaseSync() frees the VLM inline; unload() would re-queue on the same
                    // single-thread executor we're already running on, so the VLM would still be
                    // loaded when backend.generate() starts pass 2, causing memory conflicts.
                    multimodalBackend.releaseSync()

                    // Pass 2: text backend applies the described changes and produces edit_proposal blocks.
                    // For Gemma the systemPrompt is injected into the first user turn, so the user turn
                    // must be the actual task. We keep systemPrompt minimal (identity only) and put all
                    // context — vision description, file contents, format instructions — in the user turn.
                    val secondPassSystemPrompt = buildSecondPassSystemPrompt()

                    val secondPassUserContent = buildString {
                        appendLine("Image analysis result:")
                        appendLine(visionDescription.take(1200))
                        if (fileContext.isNotBlank()) {
                            appendLine()
                            appendLine("=== File to modify ===")
                            append(fileContext.take(2400))
                            appendLine()
                            appendLine("=== End file ===")
                        }
                        appendLine()
                        appendLine("Task: ${cleanInstruction.take(300)}")
                        appendLine()
                        appendLine("Apply the changes from the image analysis to the file above.")
                        appendLine("Output ONE <edit_proposal> with the COMPLETE new file content:")
                        append("<edit_proposal path=\"rel/path/File.kt\" summary=\"desc\"><new_content><![CDATA[...full content...]]></new_content></edit_proposal>")
                        if (languageHint != null) { appendLine(); append("Language: $languageHint") }
                    }.trim()

                    val secondPassMessages = listOf(
                        ChatMessage(role = ChatRole.USER, content = secondPassUserContent),
                    )

                    val secondHandle = backend.generate(
                        GenerationRequest(
                            systemPrompt = secondPassSystemPrompt,
                            messages = secondPassMessages,
                            config = config,
                            noThink = true,
                        ),
                        object : GenerationCallbacks {
                            // Don't stream tokens — edit proposals surface via onCompleted/onFinished.
                            override fun onToken(token: String) {}

                            override fun onCompleted(fullText: String) {
                                if (cancelled.get()) return
                                val text = ToolCallParser.stripThinking(fullText)
                                if (text.isBlank()) {
                                    onError(
                                        "Image analysis completed, but the model produced no output.\n" +
                                            "This is usually caused by insufficient memory after loading the text model " +
                                            "at Context Size ${config.contextLength}.\n" +
                                            "Try reducing Context Size in Settings → Inference (e.g. 8192)."
                                    )
                                    return
                                }
                                val proposals = extractEditProposals(text)
                                val answer = if (proposals.isNotEmpty() || text.contains("<edit_proposal", ignoreCase = true)) "" else ToolCallParser.cleanForDisplay(text)
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
}
