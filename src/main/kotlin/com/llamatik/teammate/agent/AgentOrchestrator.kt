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

        // Route <think>…</think> tokens to onThinkingToken only.
        // Answer text is never streamed during tool-use rounds — it arrives via onFinished.
        // This prevents planning prose written before <tool_call> tags from leaking into the panel.
        val tokenBuffer = StringBuilder()
        val inThink = AtomicBoolean(false)
        fun resetStreamState() {
            tokenBuffer.clear()
            inThink.set(false)
        }
        fun filteredToken(token: String) {
            tokenBuffer.append(token)
            while (tokenBuffer.isNotEmpty()) {
                val buf = tokenBuffer.toString()
                if (!inThink.get()) {
                    val openIdx = buf.indexOf("<think>")
                    if (openIdx >= 0) {
                        inThink.set(true)
                        tokenBuffer.clear()
                        tokenBuffer.append(buf.substring(openIdx + "<think>".length))
                        continue
                    }
                    // Discard answer text — hold only a partial tag prefix tail to avoid split tags.
                    val holdTags = listOf("<think>", "<end_of_turn>", "<eos>")
                    val maxLen = holdTags.maxOf { it.length }
                    val holdLen = (1..minOf(maxLen, buf.length)).reversed()
                        .firstOrNull { len -> holdTags.any { tag -> buf.lowercase().endsWith(tag.lowercase().take(len)) } } ?: 0
                    if (buf.length > holdLen) {
                        tokenBuffer.clear()
                        if (holdLen > 0) tokenBuffer.append(buf.takeLast(holdLen))
                    }
                    break
                } else {
                    // Any of these terminates the think block; <wait covers all <wait...> variants.
                    val thinkClosers = listOf("</think>", "<|im_end|>", "<|endoftext|>", "<wait")
                    val bufLow = buf.lowercase()
                    val closeMatch = thinkClosers.mapNotNull { tag ->
                        val idx = bufLow.indexOf(tag.lowercase())
                        if (idx >= 0) idx to tag else null
                    }.minByOrNull { it.first }
                    if (closeMatch != null) {
                        val (idx, tag) = closeMatch
                        val thinkContent = buf.substring(0, idx)
                        if (thinkContent.isNotEmpty()) onThinkingToken?.invoke(thinkContent)
                        inThink.set(false)
                        tokenBuffer.clear()
                        if (tag == "</think>") tokenBuffer.append(buf.substring(idx + tag.length))
                        continue
                    }
                    // Hold back a suffix that could be the start of a closer (inclusive upper bound).
                    val maxCloserLen = thinkClosers.maxOf { it.length }
                    val holdLen = (1..minOf(maxCloserLen, buf.length)).reversed()
                        .firstOrNull { len -> thinkClosers.any { tag -> tag.lowercase().startsWith(bufLow.takeLast(len)) } } ?: 0
                    val safeEnd = buf.length - holdLen
                    if (safeEnd > 0) {
                        onThinkingToken?.invoke(buf.substring(0, safeEnd))
                        tokenBuffer.clear()
                        tokenBuffer.append(buf.substring(safeEnd))
                    }
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

        // Multi-round agentic loop: model can call tools across up to MAX_ROUNDS before answering.
        // Each round feeds tool results back so the model can: list files -> read files -> write files.
        val MAX_ROUNDS = 5
        val knownToolNames = setOf(
            "list_files", "read_file", "search_code", "search_filenames",
            "get_current_file", "get_selected_text", "write_file", "create_file",
            "replace_in_file", "show_diff", "apply_patch",
        )
        fun runRound(messages: List<ChatMessage>, round: Int, accumulatedProposals: List<EditProposal>, hasReadContent: Boolean = false) {
            if (cancelled.get()) return
            val roundSystemPrompt = if (round == 1) systemPrompt else buildSecondPassSystemPrompt()
            val handle = backend.generate(
                GenerationRequest(
                    systemPrompt = roundSystemPrompt,
                    messages = messages,
                    config = config,
                    noThink = round > 1,
                ),
                object : GenerationCallbacks {
                    override fun onToken(token: String) {
                        countToken()
                        if (!cancelled.get()) filteredToken(token)
                    }

                    override fun onCompleted(fullText: String) {
                        if (cancelled.get()) return
                        val answerText = ToolCallParser.stripThinking(fullText)
                        // Filter to known tool names — hallucinated names (e.g. "diff_tool") would loop forever
                        val toolCalls = ToolCallParser.parseIncludingIncomplete(answerText)
                            .filter { it.name in knownToolNames }
                            .take(4)

                        if (toolCalls.isEmpty() || round >= MAX_ROUNDS) {
                            val xmlProposals = extractEditProposals(answerText)
                            // xmlProposals (from final LLM pass) wins over earlier tool-result proposals.
                            val proposals = (xmlProposals + accumulatedProposals).distinctBy { it.filePath }
                            val clean = ToolCallParser.cleanForDisplay(answerText)
                            onFinished(
                                ChatMessage(role = ChatRole.ASSISTANT, content = clean, editProposalId = proposals.firstOrNull()?.id),
                                proposals,
                            )
                            return
                        }

                        if (!cancelled.get()) onToken(" STATUS:Gathering project context…")
                        resetStreamState()

                        val toolMessages = toolCalls.map { call ->
                            val mainArg = call.arguments.values.firstOrNull().orEmpty().take(60)
                            if (!cancelled.get()) onToken(" TOOL:${call.name}:$mainArg")
                            val result = toolRegistry.execute(call, ToolExecutionContext(project))
                            ChatMessage(role = ChatRole.TOOL, content = result.content, toolName = result.name)
                        }

                        val roundProposals = buildProposalsFromToolMessages(toolMessages)
                        // roundProposals wins over older accumulated proposals for the same file.
                        val allProposals = (roundProposals + accumulatedProposals).distinctBy { it.filePath }

                        if (cancelled.get()) return
                        if (!cancelled.get()) onToken(" STATUS:Thinking…")

                        val toolResultsSummary = toolMessages.joinToString("\n") { msg ->
                            "<result tool=\"${msg.toolName}\">\n${msg.content.take(400)}\n</result>"
                        }
                        val readOnlyTools = setOf("list_files", "read_file", "search_code", "search_filenames", "get_current_file", "get_selected_text")
                        val allReadOnly = toolCalls.all { it.name in readOnlyTools }
                        val nowHasReadContent = hasReadContent || toolCalls.any { it.name == "read_file" || it.name == "search_code" }
                        val directive = when {
                            allReadOnly && nowHasReadContent ->
                                "You have the file content. Call write_file with the complete new content. Use ONLY paths from the results above."
                            allReadOnly && !nowHasReadContent ->
                                "Call read_file on the relevant source files."
                            round + 1 >= MAX_ROUNDS ->
                                "Final round: call write_file or create_file now. ONLY use paths from the results above."
                            else ->
                                "Continue: read more files if needed, or call write_file/create_file."
                        }
                        // Each round sends only the current tool results as the user turn — NOT the full
                        // accumulated history. This keeps every round's prompt small and prevents the
                        // "Prompt too long" batch-size error when rounds accumulate.
                        val nextUserContent = buildString {
                            appendLine("Task: ${cleanInstruction.take(200)}")
                            appendLine("Tool results:")
                            appendLine(toolResultsSummary.take(900))
                            appendLine()
                            append(directive)
                        }
                        val nextMessages = listOf(ChatMessage(role = ChatRole.USER, content = nextUserContent))

                        runRound(nextMessages, round + 1, allProposals, nowHasReadContent)
                    }

                    override fun onError(message: String, throwable: Throwable?) {
                        if (!cancelled.get()) onError(message)
                    }
                },
            )
            activeHandle.set(handle)
            if (cancelled.get()) handle.cancel()
        }

        runRound(toolAugmentedMessages, 1, emptyList())

        return compositeHandle
    }

    /**
     * Minimal system prompt for the second (tool-results) pass.
     * Must stay small — the second pass already carries tool results + user prompt,
     * and the entire prompt must fit within n_batch = 2048 tokens (≈ 6000 chars).
     */
    private fun buildSecondPassSystemPrompt(): String = buildString {
        appendLine("You are Llamatik Code, a local IntelliJ coding agent.")
        val lang = detectProjectLanguage()
        if (lang != null) {
            appendLine("LANGUAGE: $lang project. Write ALL code in $lang ONLY. Never Python or other languages.")
        }
        appendLine("Output ONLY tool call XML. No prose, no explanation, no 'I will...'.")
        appendLine("Tool call format:")
        appendLine("<tool_call name=\"write_file\"><arg name=\"path\">src/Foo.kt</arg><arg name=\"content\">...</arg></tool_call>")
        appendLine("Use the ACTUAL file paths from the project. Do NOT invent or guess paths.")
        appendLine("If you have enough context, call write_file with the complete new file content.")
        appendLine("If you need more context, call read_file first.")
        appendLine("Tools: ${toolRegistry.describeTools().take(300)}")
        val activeNames = skillService.activeSkills().joinToString(", ") { it.displayName }
        if (activeNames.isNotEmpty()) appendLine("Active skills: $activeNames")
    }

    private fun detectProjectLanguage(): String? {
        // Check open file first (most reliable signal)
        val openExt = contextService.getCurrentFile()?.substringAfterLast('.', "")?.lowercase()
        val fromOpenFile = when (openExt) {
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
        if (fromOpenFile != null) return fromOpenFile

        // Fallback: detect from project build files in the root
        val projectFiles = contextService.listFiles(null).map { it.lowercase() }
        return when {
            projectFiles.any { it == "build.gradle.kts" || it.endsWith("/build.gradle.kts") } -> "Kotlin"
            projectFiles.any { it == "cargo.toml" || it.endsWith("/cargo.toml") } -> "Rust"
            projectFiles.any { it == "go.mod" || it.endsWith("/go.mod") } -> "Go"
            projectFiles.any { it == "package.json" || it.endsWith("/package.json") } -> "TypeScript"
            projectFiles.any { it == "build.gradle" || it.endsWith("/build.gradle") } -> "Java"
            projectFiles.any { it == "pom.xml" || it.endsWith("/pom.xml") } -> "Java"
            projectFiles.any { it == "setup.py" || it == "pyproject.toml" } -> "Python"
            else -> null
        }
    }

    private fun buildContextHint(): String = buildString {
        appendLine("Project context:")
        appendLine("- project: ${contextService.projectName()}")
        val lang = detectProjectLanguage()
        if (lang != null) appendLine("- language: $lang — ALL generated code MUST be written in $lang")
        val currentFilePath = contextService.getCurrentFile()
        appendLine("- open file: ${currentFilePath ?: "none"}")
        // Include the content of the open file so the model can write code that matches it
        if (currentFilePath != null) {
            val content = runCatching { contextService.readFile(currentFilePath) }.getOrNull()
            if (!content.isNullOrBlank()) {
                appendLine("- open file content:")
                appendLine(content.take(800))
            }
        }
        val selected = contextService.getSelectedText()?.take(300)
        if (!selected.isNullOrBlank()) appendLine("- selected text: ${selected}")
        val pinned = contextService.pinnedInstructions()
        if (pinned.isNotBlank()) appendLine("- instructions: ${pinned.take(200)}")
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
        val lang = detectProjectLanguage()
        if (lang != null) {
            appendLine("LANGUAGE: This is a $lang project. You MUST write ALL code exclusively in $lang.")
            appendLine("NEVER output Python, Java, or any other language. Only $lang.")
        }
        appendLine("OUTPUT FORMAT — strictly follow this every response:")
        appendLine("  Optional: <think>brief reasoning</think>")
        appendLine("  Then IMMEDIATELY one of:")
        appendLine("    A) One or more tool calls (no prose before or after the XML)")
        appendLine("    B) write_file/create_file tool calls to apply changes")
        appendLine("    C) A plain-text answer (only when no file changes are needed)")
        appendLine("NEVER write prose like 'I will now...' or 'I'll start by...' — go straight to the tool call or answer.")
        appendLine("Use ONLY file paths that exist in the project. Never invent paths.")
        appendLine("Tool call format:")
        appendLine("<tool_call name=\"read_file\"><arg name=\"path\">ACTUAL/PATH/FROM/PROJECT</arg></tool_call>")
        appendLine("Tools: ${toolRegistry.describeTools().take(400)}")
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
     * Extracts EditProposals directly from tool result messages, bypassing the need for the
     * second-pass LLM to re-emit file content inside <edit_proposal> XML (unreliable on small models).
     *
     * Handles write_file / replace_in_file (---OLD--- / ---NEW--- format) and create_file (no separator).
     */
    private fun buildProposalsFromToolMessages(toolMessages: List<ChatMessage>): List<EditProposal> {
        val editTools = setOf("write_file", "replace_in_file", "create_file")
        return toolMessages.mapNotNull { msg ->
            val toolName = msg.toolName ?: return@mapNotNull null
            if (toolName !in editTools) return@mapNotNull null
            val content = msg.content

            val path = when {
                content.startsWith("Prepared file replacement for ") ->
                    content.removePrefix("Prepared file replacement for ").substringBefore("\n")
                content.startsWith("Prepared replacement for ") ->
                    content.removePrefix("Prepared replacement for ").substringBefore("\n")
                content.startsWith("Prepared new file for ") ->
                    content.removePrefix("Prepared new file for ").substringBefore("\n")
                else -> return@mapNotNull null
            }.trim()

            val newContent = when {
                content.contains("\n---NEW---\n") -> content.substringAfter("\n---NEW---\n").trim()
                content.startsWith("Prepared new file for ") -> content.substringAfter("\n").trim()
                else -> return@mapNotNull null
            }
            val oldContent = when {
                content.contains("\n---OLD---\n") -> content.substringAfter("\n---OLD---\n").substringBefore("\n---NEW---\n").trim()
                else -> runCatching { contextService.readFile(path) }.getOrDefault("")
            }

            if (newContent.isEmpty()) return@mapNotNull null
            EditProposal(filePath = path, oldContent = oldContent, newContent = newContent, summary = "Changes from $toolName")
        }
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
                appendLine("Open file: $currentFilePath")
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

        val languageHint = detectProjectLanguage()

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
                    val thinkClosers = listOf("</think>", "<|im_end|>", "<|endoftext|>", "<wait")
                    val bufLow = buf.lowercase()
                    val closeMatch = thinkClosers.mapNotNull { tag ->
                        val idx = bufLow.indexOf(tag.lowercase())
                        if (idx >= 0) idx to tag else null
                    }.minByOrNull { it.first }
                    if (closeMatch != null) {
                        val (idx, tag) = closeMatch
                        val thinkContent = buf.substring(0, idx)
                        if (thinkContent.isNotEmpty()) onThinkingToken?.invoke(thinkContent)
                        inThink.set(false)
                        tokenBuffer.clear()
                        if (tag == "</think>") tokenBuffer.append(buf.substring(idx + tag.length))
                        continue
                    }
                    val maxCloserLen = thinkClosers.maxOf { it.length }
                    val holdLen = (1..minOf(maxCloserLen, buf.length)).reversed()
                        .firstOrNull { len -> thinkClosers.any { tag -> tag.lowercase().startsWith(bufLow.takeLast(len)) } } ?: 0
                    val safeEnd = buf.length - holdLen
                    if (safeEnd > 0) {
                        onThinkingToken?.invoke(buf.substring(0, safeEnd))
                        tokenBuffer.clear()
                        tokenBuffer.append(buf.substring(safeEnd))
                    }
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
