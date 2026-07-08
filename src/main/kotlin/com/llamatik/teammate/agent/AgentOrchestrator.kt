package com.llamatik.teammate.agent

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.backend.AssistantBackend
import com.llamatik.teammate.backend.BackendConfig
import com.llamatik.teammate.backend.GenerationCallbacks
import com.llamatik.teammate.backend.GenerationHandle
import com.llamatik.teammate.backend.GenerationRequest
import com.llamatik.teammate.backend.SessionTag
import com.llamatik.teammate.context.ProjectContextService
import com.llamatik.teammate.llamatik.LlamatikBackendService
import com.llamatik.teammate.llamatik.MultimodalBackendService
import com.llamatik.teammate.model.AttachedImage
import com.llamatik.teammate.model.ChatMessage
import com.llamatik.teammate.model.ChatRole
import com.llamatik.teammate.model.ChatSession
import com.llamatik.teammate.model.EditProposal
import com.llamatik.teammate.settings.LlamatikAppSettings
import com.llamatik.teammate.memory.ProjectMemoryService
import com.llamatik.teammate.rules.CodebaseRulesService
import com.llamatik.teammate.skills.SkillService
import com.llamatik.teammate.tools.ToolCallParser
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolRegistry
import com.llamatik.teammate.tools.ToolResult
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.PROJECT)
class AgentOrchestrator(private val project: Project) {
    private val backend: AssistantBackend = service<LlamatikBackendService>()
    private val multimodalBackend = service<MultimodalBackendService>()
    private val toolRegistry = project.service<ToolRegistry>()
    private val contextService = ProjectContextService(project)
    private val skillService = service<SkillService>()
    private val rulesService = project.service<CodebaseRulesService>()
    private val memoryService = project.service<ProjectMemoryService>()

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
        onTrace: ((AgentTrace) -> Unit)? = null,
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

        // Budget for file content sent to the model.
        // contextSize is the actual model context window in tokens; multiply by 3 for a char estimate
        // (UTF-8 source code is ~3 chars per token on average).
        // batchSize is a KV-cache batching parameter, not the context limit — it must NOT constrain
        // how much file content we send, so it is excluded from this calculation.
        //   SYSTEM_PROMPT_BASE_CHARS  — identity, tools, format rules, skills, rules, memory
        //   AGENT_HISTORY_BUDGET_CHARS — prior turn history
        //   USER_INSTRUCTION_MAX_CHARS — current user turn text
        //   1_200                      — context hint + file-section wrappers + safety margin
        val effectiveContextChars = settings.contextSize * 3
        // Mirror the 90% safety margin used in LlamatikBackendService so budget and check agree.
        val safeContextChars = (effectiveContextChars * 0.9).toInt()
        val nonFileOverheadChars = SYSTEM_PROMPT_BASE_CHARS + AGENT_HISTORY_BUDGET_CHARS + USER_INSTRUCTION_MAX_CHARS + 1_200
        val fileBudgetChars = (safeContextChars - nonFileOverheadChars).coerceAtLeast(0)
        val fileAttachmentMax    = fileBudgetChars
        val fileContextMax       = fileBudgetChars
        // Per-result cap: the full file budget, floored so small contextSize settings never zero out.
        val toolResultContentMax = fileBudgetChars.coerceAtLeast(TOOL_RESULT_CONTENT_MAX_CHARS)
        // Round 2 user turn holds BOTH source re-injection AND tool results in the same message.
        // Budget them jointly against safeContextChars so their sum never exceeds the backend limit.
        // The second-pass system prompt is tiny (~500 chars); reserve 1_200 for it + misc overhead.
        val round2ContentBudget  = (safeContextChars - 1_200).coerceAtLeast(0)
        val toolResultsMax       = (round2ContentBudget * 2 / 3).coerceAtLeast(TOOL_RESULTS_MAX_CHARS)
        val round2FileContextMax = (round2ContentBudget / 3).coerceAtLeast(3_000)

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
                    val openIdx = buf.indexOf(THINK_OPEN)
                    if (openIdx >= 0) {
                        inThink.set(true)
                        tokenBuffer.clear()
                        tokenBuffer.append(buf.substring(openIdx + THINK_OPEN.length))
                        continue
                    }
                    // Discard answer text — hold only a partial tag prefix tail to avoid split tags.
                    val holdLen = holdLenForTags(buf, ANSWER_HOLD_TAGS)
                    if (buf.length > holdLen) {
                        tokenBuffer.clear()
                        if (holdLen > 0) tokenBuffer.append(buf.takeLast(holdLen))
                    }
                    break
                } else {
                    // Any of these terminates the think block; <wait covers all <wait...> variants.
                    val bufLow = buf.lowercase()
                    val closeMatch = THINK_CLOSERS.mapNotNull { tag ->
                        val idx = bufLow.indexOf(tag.lowercase())
                        if (idx >= 0) idx to tag else null
                    }.minByOrNull { it.first }
                    if (closeMatch != null) {
                        val (idx, tag) = closeMatch
                        val thinkContent = buf.substring(0, idx)
                        if (thinkContent.isNotEmpty()) onThinkingToken?.invoke(thinkContent)
                        inThink.set(false)
                        tokenBuffer.clear()
                        if (tag == THINK_CLOSE) tokenBuffer.append(buf.substring(idx + tag.length))
                        continue
                    }
                    val holdLen = holdLenForStartsWith(buf, THINK_CLOSERS)
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
        val (afterAtRefs, atFileContext) = resolveFileReferences(userPrompt, fileAttachmentMax)
        // Rewrite vague "current file" / "this file" phrases to the actual filename so small
        // models don't search_code for the instruction text instead of reading the file.
        val (cleanInstruction, currentFileContext) = resolveCurrentFileReference(afterAtRefs, fileAttachmentMax)
        val fileContext = listOf(atFileContext, currentFileContext).filter { it.isNotBlank() }.joinToString("\n\n")

        val contextHint = buildContextHint()

        // If the user didn't attach a file this turn and didn't reference "this file", inherit the
        // last file context from session history so follow-up prompts ("add more tests", "fix that
        // error") keep working on the same file without requiring the user to re-attach it.
        // File context is stored inside the persisted user turn (after a sentinel line) so it can
        // be recovered by subsequent turns without the user having to re-attach anything.
        val inheritedFileContext = if (fileContext.isBlank()) {
            session.messages.asReversed()
                .firstOrNull { it.role == ChatRole.USER && it.content.contains(HISTORY_FILE_SENTINEL) }
                ?.let { msg ->
                    val start = msg.content.indexOf(HISTORY_FILE_SENTINEL) + HISTORY_FILE_SENTINEL.length
                    msg.content.substring(start).trimStart('\n').take(fileContextMax)
                }.orEmpty()
        } else ""

        val effectiveFileContext = fileContext.ifBlank { inheritedFileContext }

        val systemPrompt = buildString {
            append(buildSystemPrompt())
            if (isEnterpriseMission(userPrompt)) {
                append(buildEnterpriseMissionSystemPromptAddition())
            }
            if (effectiveFileContext.isNotBlank()) {
                appendLine()
                appendLine()
                appendLine("=== Attached files ===")
                appendLine("The following file contents are ALREADY provided below. Do NOT call read_file, search_code, or list_files to retrieve them again — use the content directly.")
                append(effectiveFileContext.take(fileContextMax))
                appendLine()
                appendLine("=== End attached files ===")
            }
        }

        // Snapshot history before appending the current turn so recentMessages only contains
        // prior exchanges and we can append userTurnMessage once without duplication.
        val recentMessages = fitMessagesInBudget(session.messages, AGENT_HISTORY_BUDGET_CHARS)

        // The user turn stored in session.messages embeds the file context after a sentinel so
        // subsequent turns can inherit it. The model never sees this embedded context via history —
        // it comes through the system prompt — but it is there for our own context propagation.
        val userContent = buildString {
            append(cleanInstruction.take(USER_INSTRUCTION_MAX_CHARS))
            if (contextHint.isNotBlank()) {
                appendLine()
                appendLine()
                append(contextHint)
            }
            // When a file is explicitly attached, the content is already in the system prompt.
            // Allow at most one read_file call if the model needs to check a related symbol,
            // then it must call write_file. The prefill mechanism ensures the write pass fires.
            if (effectiveFileContext.isNotBlank()) {
                appendLine()
                appendLine()
                appendLine("The file content is already provided above in the system prompt. If you need to look up a symbol in a related file call read_file ONCE, then immediately call write_file with the complete modified file. Do NOT loop — one optional read then write.")
                append(HISTORY_FILE_SENTINEL)
                appendLine()
                append(effectiveFileContext.take(fileContextMax))
            }
        }
        val userTurnMessage = ChatMessage(role = ChatRole.USER, content = userContent)
        session.messages += userTurnMessage

        // recentMessages was captured before appending userTurnMessage, so appending it here
        // gives exactly [prior history..., current user turn] with no duplication.
        // Strip the sentinel + file payload from userTurnMessage before sending to the model —
        // the file content is already in the system prompt; sending it again would waste budget.
        val userTurnForModel = if (userTurnMessage.content.contains(HISTORY_FILE_SENTINEL)) {
            userTurnMessage.copy(content = userTurnMessage.content.substringBefore(HISTORY_FILE_SENTINEL).trimEnd())
        } else userTurnMessage
        val toolAugmentedMessages = mutableListOf<ChatMessage>()
        toolAugmentedMessages += recentMessages
        toolAugmentedMessages += userTurnForModel

        val config = BackendConfig(
            modelIdOrPath = settings.modelPath.ifBlank { settings.modelIdentifier },
            temperature = settings.temperature,
            maxTokens = settings.maxTokens,
            contextLength = settings.contextSize,
            numThreads = settings.numThreads,
            useMmap = settings.useMmap,
            flashAttention = settings.flashAttention,
            batchSize = settings.batchSize,
            gpuLayers = settings.gpuLayers,
            localOnly = settings.localOnly,
        )

        // Wall-clock deadline: cancel the entire agent run if it has been running for too long.
        // This is a safety net against model hangs, infinite thinking, or unexpected token floods.
        val sessionStartMs = System.currentTimeMillis()

        // Collects rejection reasons across all rounds so they can be shown in the final message
        // if the agent exhausts rounds without producing a valid proposal.
        val accumulatedRejections = mutableListOf<String>()
        val agentTrace = AgentTrace()
        // Tracks (toolName, primaryArg) pairs already executed across all rounds.
        // Duplicate read-only calls get a synthetic "already called" result instead of re-running,
        // which breaks the search_code/read_file repetition loop without consuming a full round.
        // Pre-seed with read_file paths that are already injected as attached files, so the model
        // can't waste a round calling read_file on content it already has in the system prompt.
        val executedToolKeys = mutableSetOf<Pair<String, String>>()
        Regex("""@\{([^}]+)}""").findAll(userPrompt).forEach { m ->
            executedToolKeys += TOOL_READ_FILE to m.groupValues[1].trim()
        }
        contextService.getCurrentFile()?.let { executedToolKeys += TOOL_READ_FILE to it }

        // Files read by the model across rounds — re-injected into every subsequent round so
        // the model always has the source it already read when it comes time to write.
        // Keyed by path so later reads overwrite earlier ones for the same file.
        val accumulatedReadFiles = linkedMapOf<String, String>()

        // For the write pass, pre-fill the assistant turn with the start of a write_file call
        // so the model cannot emit prose — it must continue from the open tag with file content.
        // Derived lazily from effectiveFileContext (attached file path) or accumulatedReadFiles.
        fun writePassPrefill(): String {
            val path = run {
                // Prefer the explicit @{path} attachment
                Regex("""@\{([^}]+)}""").find(userPrompt)?.groupValues?.get(1)?.trim()
                    ?: run {
                        // Extract path from effectiveFileContext header line "### File: <path>"
                        effectiveFileContext.lineSequence()
                            .firstOrNull { it.startsWith(FILE_SECTION_PREFIX) }
                            ?.removePrefix(FILE_SECTION_PREFIX)?.trim()
                    }
                    ?: accumulatedReadFiles.keys.firstOrNull()
            } ?: return ""
            return """<tool_call name="write_file"><arg name="path">$path</arg><arg name="content">"""
        }

        // Multi-round agentic loop: model can call tools across up to MAX_ROUNDS before answering.
        // Each round feeds tool results back so the model can: list files -> read files -> write files.
        fun runRound(messages: List<ChatMessage>, round: Int, accumulatedProposals: List<EditProposal>, hasReadContent: Boolean = false, prefill: String = "") {
            if (cancelled.get()) return
            // Global wall-clock safety net — kills the session if the total time exceeds the cap.
            if (System.currentTimeMillis() - sessionStartMs > AGENT_SESSION_TIMEOUT_MS) {
                val msg = ChatMessage(role = ChatRole.ASSISTANT, content = "Agent timed out after ${AGENT_SESSION_TIMEOUT_MS / 60_000} minutes. Try a shorter task or attach the file directly with @filename.")
                session.messages += msg
                onFinished(msg, accumulatedProposals)
                return
            }
            val roundSystemPrompt = if (round == 1) systemPrompt else buildSecondPassSystemPrompt()
            // Disable thinking on ALL agent rounds — iterative tool calls are the reasoning loop,
            // so per-round <think> blocks only burn token budget and add latency without improving quality.
            // Use a tighter token cap for exploration rounds (tool calls need few tokens to express);
            // give the full budget only on the write pass so large file content fits in the output.
            val isWriteRound = round >= MAX_ROUNDS - 1
            val roundMaxTokens = if (isWriteRound) maxOf(config.maxTokens, WRITE_ROUND_MIN_TOKENS) else minOf(config.maxTokens, EXPLORATION_ROUND_MAX_TOKENS)
            val handle = backend.generate(
                GenerationRequest(
                    systemPrompt = roundSystemPrompt,
                    messages = messages,
                    config = config.copy(maxTokens = roundMaxTokens),
                    noThink = prefill.isNotBlank() || effectiveFileContext.isNotBlank(),
                    sessionTag = SessionTag.AGENT,
                    assistantPrefill = prefill,
                ),
                object : GenerationCallbacks {
                    override fun onToken(token: String) {
                        countToken()
                        if (!cancelled.get()) filteredToken(token)
                    }

                    override fun onCompleted(fullText: String) {
                        if (cancelled.get()) return
                        // When assistant prefill was used, the model generated text AFTER the prefill.
                        // Reconstruct the full tool call by prepending the prefill back.
                        val textWithPrefill = if (prefill.isNotBlank()) prefill + fullText else fullText
                        // Base models echo injected context/prompts verbatim before generating.
                        // Round 1: strip leading echo of the user instruction and any echoed file context.
                        // Round > 1: strip echo of the injected tool-results user turn.
                        val echoStripped = when {
                            round > 1 -> ToolCallParser.stripPromptEcho(textWithPrefill, cleanInstruction.take(40))
                            else -> {
                                val afterInstructionEcho = ToolCallParser.stripLeadingEcho(textWithPrefill, cleanInstruction)
                                if (fileContext.isNotBlank())
                                    ToolCallParser.stripContextEcho(afterInstructionEcho, fileContext)
                                else afterInstructionEcho
                            }
                        }
                        val answerText = ToolCallParser.stripThinking(echoStripped)
                        // Filter to known tool names — hallucinated names (e.g. "diff_tool") would loop forever.
                        // Dynamic MCP bridged tools are also allowed (they are in the registry's dynamicTools map).
                        val dynamicMcpNames = toolRegistry.dynamicToolNames()
                        val toolCalls = ToolCallParser.parseIncludingIncomplete(answerText)
                            .filter { it.name in KNOWN_TOOL_NAMES || it.name in dynamicMcpNames }
                            .take(MAX_TOOL_CALLS_PER_ROUND)

                        // Mid-loop prose guard: model wrote text instead of a tool call after having
                        // read content. Give it one explicit write pass before the normal terminal path.
                        // Bounds: round > 1 (not first Q&A round) and round < MAX_ROUNDS - 1 (not already
                        // at the penultimate write round) to avoid re-entering this path recursively.
                        if (toolCalls.isEmpty() && round > 1 && round < MAX_ROUNDS - 1 && hasReadContent) {
                            val finalDirective = RoundDirective.get(RoundDirective.RoundState(
                                round = MAX_ROUNDS - 1,
                                maxRounds = MAX_ROUNDS,
                                hasReadContent = hasReadContent,
                                wroteFilesThisRound = false,
                                validProposalsProduced = false,
                                allToolsWereReadOnly = true,
                                rejectionReasons = emptyList(),
                                hasFileContext = effectiveFileContext.isNotBlank() || accumulatedReadFiles.isNotEmpty(),
                            ))
                            val proseContext = ToolCallParser.cleanForDisplay(answerText, allowThinkFallback = false).take(400)
                            val finalContent = buildString {
                                appendLine("Task: ${cleanInstruction.take(TASK_EXCERPT_MAX_CHARS)}")
                                if (effectiveFileContext.isNotBlank()) {
                                    appendLine()
                                    appendLine("=== Source file ===")
                                    appendLine(effectiveFileContext.take(round2FileContextMax))
                                    appendLine("=== End source file ===")
                                } else if (accumulatedReadFiles.isNotEmpty()) {
                                    appendLine()
                                    var budget = round2FileContextMax
                                    for ((path, content) in accumulatedReadFiles) {
                                        if (budget <= 0) break
                                        val chunk = content.take(budget)
                                        appendLine("=== File: $path ===")
                                        appendLine(chunk)
                                        appendLine("=== End file ===")
                                        budget -= chunk.length + path.length + 40
                                    }
                                }
                                if (proseContext.isNotBlank()) {
                                    appendLine()
                                    appendLine("Your previous analysis: $proseContext")
                                }
                                appendLine()
                                append(finalDirective)
                            }
                            runRound(listOf(ChatMessage(role = ChatRole.USER, content = finalContent)), MAX_ROUNDS - 1, accumulatedProposals, hasReadContent, writePassPrefill())
                            return
                        }

                        if (toolCalls.isEmpty() || round >= MAX_ROUNDS) {
                            // On the final round the model may emit write_file/create_file/replace_in_file
                            // tool calls instead of <edit_proposal> blocks. Execute those edit calls so
                            // their proposals are not silently dropped.
                            val finalRoundToolProposals: List<EditProposal> = if (round >= MAX_ROUNDS && toolCalls.isNotEmpty()) {
                                val editCalls = toolCalls.filter { it.name in EDIT_TOOL_NAMES }
                                if (editCalls.isNotEmpty()) {
                                    val editMessages = editCalls.map { call ->
                                        val mainArg = call.arguments.values.firstOrNull().orEmpty().take(TOOL_ARG_DISPLAY_MAX_CHARS)
                                        if (!cancelled.get()) onToken("${TOOL_PREFIX}${call.name}:$mainArg")
                                        val result = toolRegistry.execute(call, ToolExecutionContext(project))
                                        agentTrace.add(AgentTraceStep(
                                            type = AgentTraceStep.Type.VERIFICATION,
                                            title = "${call.name}($mainArg)",
                                            summary = result.content.take(120).replace('\n', ' '),
                                            status = AgentTraceStep.Status.SUCCESS,
                                            durationMs = 0,
                                        ))
                                        ChatMessage(role = ChatRole.TOOL, content = result.content, toolName = result.name)
                                    }
                                    buildProposalsFromToolMessages(editMessages).proposals
                                } else emptyList()
                            } else emptyList()
                            val xmlProposals = extractEditProposals(answerText)
                            val proposals = (finalRoundToolProposals + xmlProposals + accumulatedProposals).distinctBy { it.filePath }
                            // After tool-use rounds the model's final answer is unreliable (hallucinated
                            // tool syntax, echoed system prompt markers, etc.). Only surface it on round 1
                            // with no proposals — every other terminal case uses the structured fallback.
                            val clean = if (round == 1 && proposals.isEmpty()) {
                                ToolCallParser.cleanForDisplay(echoStripped, allowThinkFallback = true)
                            } else if (proposals.isNotEmpty()) {
                                ""
                            } else if (accumulatedRejections.isEmpty() && hasReadContent) {
                                // Read-only tasks (review, analysis, explanation) — surface the model's
                                // answer text. Allow think fallback so base models (which put their entire
                                // answer inside <think> blocks) still show output.
                                val answer = ToolCallParser.cleanForDisplay(echoStripped, allowThinkFallback = true)
                                answer.ifBlank {
                                    "The agent reviewed the files but did not produce a written summary. " +
                                    "Try asking again or use @filename to attach a specific file."
                                }
                            } else {
                                buildString {
                                    append("The agent completed without producing a file change. ")
                                    if (accumulatedRejections.isNotEmpty()) {
                                        append("All write attempts were rejected: ")
                                        append(accumulatedRejections.joinToString("; "))
                                        append(". Try using @filename to attach the source file directly.")
                                    } else {
                                        append("The model may not have found the right file. Try using @filename to attach it.")
                                    }
                                }
                            }
                            if (!agentTrace.isEmpty()) onTrace?.invoke(agentTrace)
                            val assistantMessage = ChatMessage(role = ChatRole.ASSISTANT, content = clean, editProposalId = proposals.firstOrNull()?.id)
                            session.messages += assistantMessage
                            onFinished(assistantMessage, proposals)
                            return
                        }

                        if (!cancelled.get()) onToken("${STATUS_PREFIX}Gathering project context…")
                        resetStreamState()

                        var allCallsDuplicate = true
                        val toolMessages = toolCalls.map { call ->
                            val mainArg = call.arguments.values.firstOrNull().orEmpty().take(TOOL_ARG_DISPLAY_MAX_CHARS)
                            if (!cancelled.get()) onToken("${TOOL_PREFIX}${call.name}:$mainArg")
                            val toolKey = call.name to call.arguments.values.firstOrNull().orEmpty()
                            val isDuplicateReadOnly = call.name in READ_ONLY_TOOL_NAMES && !executedToolKeys.add(toolKey)
                            if (!isDuplicateReadOnly) allCallsDuplicate = false
                            val t0 = System.currentTimeMillis()
                            val result = if (isDuplicateReadOnly) {
                                ToolResult(name = call.name, success = true, content = "Already called ${call.name}(${call.arguments.values.firstOrNull().orEmpty()}) in a previous round. Use the result you already received — do not call this tool again.")
                            } else {
                                toolRegistry.execute(call, ToolExecutionContext(project))
                            }
                            val elapsed = System.currentTimeMillis() - t0
                            val traceType = when {
                                call.name == TOOL_READ_FILE -> AgentTraceStep.Type.FILE_READ
                                call.name in setOf(TOOL_SEARCH_CODE, TOOL_SEARCH_FILENAMES, "index_search", "smart_search") -> AgentTraceStep.Type.SEARCH
                                call.name in EDIT_TOOL_NAMES -> AgentTraceStep.Type.VERIFICATION
                                call.name.startsWith("mcp_") -> AgentTraceStep.Type.MCP_CALL
                                else -> AgentTraceStep.Type.TOOL_CALL
                            }
                            val traceStatus = if (!result.success || result.content.startsWith("Error") || result.content.startsWith("No ") || result.content.startsWith("Missing")) {
                                AgentTraceStep.Status.WARNING
                            } else {
                                AgentTraceStep.Status.SUCCESS
                            }
                            agentTrace.add(AgentTraceStep(
                                type = traceType,
                                title = "${call.name}($mainArg)",
                                summary = result.content.take(120).replace('\n', ' '),
                                status = traceStatus,
                                durationMs = elapsed,
                            ))
                            ChatMessage(role = ChatRole.TOOL, content = result.content, toolName = result.name)
                        }

                        val proposalResult = buildProposalsFromToolMessages(toolMessages)
                        val roundProposals = proposalResult.proposals
                        accumulatedRejections += proposalResult.rejectionReasons
                        // roundProposals wins over older accumulated proposals for the same file.
                        val allProposals = (roundProposals + accumulatedProposals).distinctBy { it.filePath }

                        // Accumulate read_file results so the model can reference source in later rounds.
                        toolCalls.zip(toolMessages).forEach { (call, msg) ->
                            if (call.name == TOOL_READ_FILE && msg.content.isNotBlank() && !msg.content.startsWith("Already called") && !msg.content.startsWith("Error")) {
                                val path = call.arguments["path"] ?: return@forEach
                                accumulatedReadFiles[path] = msg.content
                            }
                        }

                        if (cancelled.get()) return

                        // Valid proposals produced → task done. Stop immediately.
                        val wroteFiles = toolCalls.any { it.name in EDIT_TOOL_NAMES }
                        // All calls this round were dedup synthetic — no new information can arrive.
                        // Escalate directly to the final-round write pass instead of burning another round.
                        if (allCallsDuplicate && roundProposals.isEmpty()) {
                            val finalDirective = RoundDirective.get(RoundDirective.RoundState(
                                round = MAX_ROUNDS - 1,
                                maxRounds = MAX_ROUNDS,
                                hasReadContent = hasReadContent,
                                wroteFilesThisRound = false,
                                validProposalsProduced = false,
                                allToolsWereReadOnly = true,
                                rejectionReasons = emptyList(),
                                hasFileContext = effectiveFileContext.isNotBlank() || accumulatedReadFiles.isNotEmpty(),
                            ))
                            val finalContent = buildString {
                                appendLine("Task: ${cleanInstruction.take(TASK_EXCERPT_MAX_CHARS)}")
                                if (effectiveFileContext.isNotBlank()) {
                                    appendLine()
                                    appendLine("=== Source file ===")
                                    appendLine(effectiveFileContext.take(round2FileContextMax))
                                    appendLine("=== End source file ===")
                                } else if (accumulatedReadFiles.isNotEmpty()) {
                                    appendLine()
                                    var budget = round2FileContextMax
                                    for ((path, content) in accumulatedReadFiles) {
                                        if (budget <= 0) break
                                        val chunk = content.take(budget)
                                        appendLine("=== File: $path ===")
                                        appendLine(chunk)
                                        appendLine("=== End file ===")
                                        budget -= chunk.length + path.length + 40
                                    }
                                }
                                appendLine()
                                append(finalDirective)
                            }
                            runRound(listOf(ChatMessage(role = ChatRole.USER, content = finalContent)), MAX_ROUNDS - 1, allProposals, hasReadContent, writePassPrefill())
                            return
                        }
                        if (wroteFiles && roundProposals.isNotEmpty()) {
                            if (!agentTrace.isEmpty()) onTrace?.invoke(agentTrace)
                            val fileList = allProposals.joinToString(", ") { it.filePath }
                            val assistantMessage = ChatMessage(role = ChatRole.ASSISTANT, content = "Applied changes to: $fileList", editProposalId = allProposals.firstOrNull()?.id)
                            session.messages += assistantMessage
                            onFinished(assistantMessage, allProposals)
                            return
                        }

                        val allReadOnly = toolCalls.all { it.name in READ_ONLY_TOOL_NAMES }
                        val nowHasReadContent = hasReadContent || toolCalls.any { it.name in READ_ONLY_TOOL_NAMES }

                        // Build tool results summary now — used by both the escalation path and the normal next-round path.
                        val toolResultsSummary = toolMessages.joinToString("\n") { msg ->
                            "<result tool=\"${msg.toolName}\">\n${msg.content.take(toolResultContentMax)}\n</result>"
                        }

                        // Directed write escalation: when a file is explicitly attached the model has
                        // the source in context from round 1 — one exploration round is enough. When a
                        // file was only found via tool calls, allow two exploration rounds. In both cases,
                        // if the model is STILL doing read-only operations after that threshold, escalate
                        // directly to the write pass rather than burning more rounds on exploration that
                        // the directive text already asked it to stop.
                        val hasFileContextNow = effectiveFileContext.isNotBlank() || accumulatedReadFiles.isNotEmpty()
                        // With file explicitly attached: escalate after round 1 (file already in context, no exploration needed).
                        // Without attachment but file read via tools: escalate after round 2.
                        val minWriteEscalationRound = if (effectiveFileContext.isNotBlank()) 1 else 2
                        if (hasFileContextNow && nowHasReadContent && allReadOnly && roundProposals.isEmpty()
                            && round >= minWriteEscalationRound && round < MAX_ROUNDS - 1) {
                            val writeDirective = RoundDirective.get(RoundDirective.RoundState(
                                round = MAX_ROUNDS - 1, maxRounds = MAX_ROUNDS, hasReadContent = true,
                                wroteFilesThisRound = false, validProposalsProduced = false,
                                allToolsWereReadOnly = true, rejectionReasons = emptyList(), hasFileContext = true,
                            ))
                            val writeContent = buildString {
                                appendLine("Task: ${cleanInstruction.take(TASK_EXCERPT_MAX_CHARS)}")
                                if (effectiveFileContext.isNotBlank()) {
                                    appendLine()
                                    appendLine("=== Source file ===")
                                    appendLine(effectiveFileContext.take(round2FileContextMax))
                                    appendLine("=== End source file ===")
                                } else if (accumulatedReadFiles.isNotEmpty()) {
                                    appendLine()
                                    var budget = round2FileContextMax
                                    for ((path, content) in accumulatedReadFiles) {
                                        if (budget <= 0) break
                                        val chunk = content.take(budget)
                                        appendLine("=== File: $path ===")
                                        appendLine(chunk)
                                        appendLine("=== End file ===")
                                        budget -= chunk.length + path.length + 40
                                    }
                                }
                                if (toolResultsSummary.isNotBlank()) {
                                    appendLine()
                                    appendLine("Tool results:")
                                    appendLine(toolResultsSummary.take(toolResultsMax))
                                }
                                appendLine()
                                append(writeDirective)
                            }
                            runRound(listOf(ChatMessage(role = ChatRole.USER, content = writeContent)), MAX_ROUNDS - 1, allProposals, nowHasReadContent, writePassPrefill())
                            return
                        }

                        if (!cancelled.get()) onToken("${STATUS_PREFIX}Thinking…")

                        val directive = RoundDirective.get(RoundDirective.RoundState(
                            round = round,
                            maxRounds = MAX_ROUNDS,
                            hasReadContent = nowHasReadContent,
                            wroteFilesThisRound = wroteFiles,
                            validProposalsProduced = roundProposals.isNotEmpty(),
                            allToolsWereReadOnly = allReadOnly,
                            rejectionReasons = proposalResult.rejectionReasons,
                            hasFileContext = effectiveFileContext.isNotBlank() || accumulatedReadFiles.isNotEmpty(),
                        ))
                        // Each round sends only the current tool results as the user turn — NOT the full
                        // accumulated history. This keeps every round's prompt small and prevents the
                        // "Prompt too long" batch-size error when rounds accumulate.
                        // Re-attach file content: prefer the explicitly attached file; fall back to any
                        // files the model read during earlier rounds so it always has the source when writing.
                        val reinjectedFiles = buildString {
                            if (effectiveFileContext.isNotBlank()) {
                                appendLine("=== Source file ===")
                                appendLine(effectiveFileContext.take(round2FileContextMax))
                                appendLine("=== End source file ===")
                            } else if (accumulatedReadFiles.isNotEmpty()) {
                                var budget = round2FileContextMax
                                for ((path, content) in accumulatedReadFiles) {
                                    if (budget <= 0) break
                                    val chunk = content.take(budget)
                                    appendLine("=== File: $path ===")
                                    appendLine(chunk)
                                    appendLine("=== End file ===")
                                    budget -= chunk.length + path.length + 40
                                }
                            }
                        }.trimEnd()
                        val nextUserContent = buildString {
                            appendLine("Task: ${cleanInstruction.take(TASK_EXCERPT_MAX_CHARS)}")
                            if (reinjectedFiles.isNotBlank()) {
                                appendLine()
                                appendLine(reinjectedFiles)
                            }
                            appendLine()
                            appendLine("Tool results:")
                            appendLine(toolResultsSummary.take(toolResultsMax))
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

    /** Returns how many trailing characters of [buf] must be held back because they could be the start of any [tags] (suffix match). */
    private fun holdLenForTags(buf: String, tags: List<String>): Int {
        val maxLen = tags.maxOf { it.length }
        return (1..minOf(maxLen, buf.length)).reversed()
            .firstOrNull { len -> tags.any { tag -> buf.lowercase().endsWith(tag.lowercase().take(len)) } } ?: 0
    }

    /** Returns how many trailing characters of [buf] must be held back because they could be the start of any [tags] (startsWith match). */
    private fun holdLenForStartsWith(buf: String, tags: List<String>): Int {
        val bufLow = buf.lowercase()
        val maxLen = tags.maxOf { it.length }
        return (1..minOf(maxLen, buf.length)).reversed()
            .firstOrNull { len -> tags.any { tag -> tag.lowercase().startsWith(bufLow.takeLast(len)) } } ?: 0
    }

    /**
     * Minimal system prompt for the second (tool-results) pass.
     * Must stay small — the second pass already carries tool results + user prompt,
     * and the entire prompt must fit within n_batch tokens (≈ batchSize × 3 chars).
     * Target: ≤ 500 chars so tool results get the most budget.
     */
    private fun buildSecondPassSystemPrompt(): String = buildString {
        appendLine(AGENT_IDENTITY)
        val lang = detectProjectLanguage()
        if (lang != null) appendLine("Language: $lang. Write ALL code in $lang only.")
        appendLine("Respond with tool calls OR a plain-text answer. Optional: ${THINK_OPEN}reasoning${THINK_CLOSE} then tool XML or answer text.")
        appendLine("Tool format: <tool_call name=\"search_code\"><arg name=\"query\">ClassName</arg></tool_call>")
        appendLine("<tool_call name=\"write_file\"><arg name=\"path\">p</arg><arg name=\"content\">c</arg></tool_call>")
        appendLine("Use ONLY paths from the results. Do NOT read the same file twice. Write immediately after reading.")
        appendLine("Tools: ${toolRegistry.listToolNames()}")
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
        // Open file is intentionally omitted — the model infers it from @{file} attachments or
        // the get_current_file tool. Injecting it here causes the model to treat every unrelated
        // query (e.g. "benchmark the model") as a request about the open file.
        val selected = contextService.getSelectedText()?.take(SELECTED_TEXT_MAX_CHARS)
        if (!selected.isNullOrBlank()) appendLine("- selected text: ${selected}")
        val pinned = contextService.pinnedInstructions()
        if (pinned.isNotBlank()) appendLine("- instructions: ${pinned.take(PINNED_INSTRUCTIONS_MAX_CHARS)}")
    }

    /**
     * Returns the most recent messages that fit within [budgetChars] total content length,
     * always keeping at least the last user message.
     */
    private fun fitMessagesInBudget(messages: List<ChatMessage>, budgetChars: Int): List<ChatMessage> {
        if (messages.isEmpty()) return emptyList()
        val recent = messages.takeLast(MAX_HISTORY_MESSAGES)
        var used = 0
        val result = mutableListOf<ChatMessage>()
        for (msg in recent.asReversed()) {
            // Strip the embedded file context sentinel before sending to the model — the file
            // content is already in the current turn's system prompt and must not be duplicated
            // through history turns (it would bloat the context significantly).
            val stripped = if (msg.role == ChatRole.USER && msg.content.contains(HISTORY_FILE_SENTINEL)) {
                msg.copy(content = msg.content.substringBefore(HISTORY_FILE_SENTINEL).trimEnd())
            } else msg
            val len = stripped.content.length
            if (used + len > budgetChars && result.isNotEmpty()) break
            result.add(0, stripped)
            used += len
        }
        return result
    }

    private fun buildSystemPrompt(): String = buildString {
        appendLine(AGENT_IDENTITY)
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
        // Compact tool list keeps the system prompt within n_batch budget (models often use n_batch=2048).
        // The model infers each tool's purpose from its name; the format example shows the XML syntax.
        appendLine("Tools: ${toolRegistry.listToolNames()}")
        appendLine("Tool call format: <tool_call name=\"TOOL\"><arg name=\"ARG\">VALUE</arg></tool_call>")
        appendLine("Examples:")
        appendLine("  <tool_call name=\"search_code\"><arg name=\"query\">ClassName</arg></tool_call>")
        appendLine("  <tool_call name=\"list_files\"><arg name=\"path\">src</arg></tool_call>")
        appendLine("  <tool_call name=\"read_file\"><arg name=\"path\">src/Main.kt</arg></tool_call>")
        appendLine("  <tool_call name=\"git_status\"></tool_call>")
        appendLine("  <tool_call name=\"write_file\"><arg name=\"path\">src/Main.kt</arg><arg name=\"content\">…</arg></tool_call>")
        val skillsAddition = skillService.buildSkillsPromptAddition()
        if (skillsAddition.isNotBlank()) append(skillsAddition.take(SKILLS_PROMPT_MAX_CHARS))
        val rulesBlock = rulesService.buildPromptBlock()
        if (rulesBlock.isNotBlank()) { appendLine(); append(rulesBlock) }
        val memoryBlock = memoryService.buildPromptBlock()
        if (memoryBlock.isNotBlank()) { appendLine(); append(memoryBlock) }
        if (service<LlamatikAppSettings>().data().agentExecutionMode == AgentExecutionMode.AGGRESSIVE.name) {
            appendLine()
            append(AgentFallbackPolicy.aggressiveModeSystemPromptAddition().take(600))
        }
    }

    private fun isEnterpriseMission(prompt: String): Boolean {
        val lower = prompt.lowercase()
        return ENTERPRISE_MODE_TRIGGERS.any { lower.contains(it) }
    }

    private fun buildEnterpriseMissionSystemPromptAddition(): String = buildString {
        appendLine()
        appendLine("=== Offline Enterprise Pair Programmer Mode ===")
        appendLine("Enforce this workflow on EVERY response:")
        appendLine("1. Inspect before editing: use read_file, git_status, diagnostics_run first.")
        appendLine("2. Call enterprise_mission_create at the start of any new mission.")
        appendLine("3. Create a plan and explain risks BEFORE any file changes.")
        appendLine("4. Use small, reviewable patches (replace_in_file over write_file when possible).")
        appendLine("5. Show diff using show_diff after every file change.")
        appendLine("6. Verify using run_config_last_output, terminal_run, or diagnostics_run.")
        appendLine("7. Call enterprise_mission_report as the final step — include rollback instructions.")
        appendLine("8. External knowledge is DISABLED unless explicitly allowed.")
        appendLine("9. Preserve ALL existing behavior unless the mission explicitly requires a change.")
        appendLine("10. Never skip the plan step. Never mutate without prior context gathering.")
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
    private fun resolveFileReferences(prompt: String, attachmentMax: Int = FILE_ATTACHMENT_MAX_CHARS): Pair<String, String> {
        val tokenRegex = Regex("""@\{([^}]+)}""")
        val matches = tokenRegex.findAll(prompt).toList()
        if (matches.isEmpty()) return Pair(prompt, "")

        val attachments = StringBuilder()
        matches.forEach { match ->
            val path = match.groupValues[1].trim()
            val content = runCatching { contextService.readFile(path) }.getOrNull()
            if (!content.isNullOrBlank()) {
                attachments.appendLine("$FILE_SECTION_PREFIX$path")
                attachments.appendLine("```")
                attachments.appendLine(content.take(attachmentMax))
                attachments.appendLine("```")
                attachments.appendLine()
            } else {
                attachments.appendLine("### File: $path")
                attachments.appendLine("(File not found or unreadable — do NOT search for it; ask the user to verify the path.)")
                attachments.appendLine()
            }
        }

        // Replace @{path} tokens with just the filename so the instruction reads naturally
        val cleanInstruction = tokenRegex.replace(prompt) { match ->
            match.groupValues[1].substringAfterLast('/')
        }

        return Pair(cleanInstruction, attachments.trimEnd().toString())
    }

    /**
     * Rewrites vague "current file" / "this file" / "open file" phrases in [prompt] to the
     * actual filename, and returns the file content as fenced context.
     *
     * This prevents small models from using the instruction text as a search_code query
     * instead of reading the file the user actually has open.
     *
     * Returns (rewritten prompt, fenced file content string).
     * If no current file is open, returns the original prompt unchanged with empty context.
     */
    private fun resolveCurrentFileReference(prompt: String, attachmentMax: Int = FILE_ATTACHMENT_MAX_CHARS): Pair<String, String> {
        val currentFilePath = contextService.getCurrentFile() ?: return Pair(prompt, "")
        val fileName = currentFilePath.substringAfterLast('/')

        val hasVagueRef = CURRENT_FILE_PHRASES.any { prompt.contains(it, ignoreCase = true) }
        if (!hasVagueRef) return Pair(prompt, "")

        val content = runCatching { contextService.readFile(currentFilePath) }.getOrNull()
            ?: return Pair(prompt, "")

        // Replace vague references with the concrete filename
        var rewritten = prompt
        for (phrase in CURRENT_FILE_PHRASES) {
            rewritten = rewritten.replace(phrase, fileName, ignoreCase = true)
        }

        val context = buildString {
            appendLine("$FILE_SECTION_PREFIX$currentFilePath")
            appendLine("```")
            appendLine(content.take(attachmentMax))
            appendLine("```")
        }.trimEnd()

        return Pair(rewritten, context)
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
        val cdataIdx = afterHeader.indexOf(CDATA_OPEN)
        if (cdataIdx >= 0) {
            val raw = afterHeader.substring(cdataIdx + CDATA_OPEN.length)
            val closing = raw.indexOf(CDATA_CLOSE)
            return if (closing >= 0) raw.substring(0, closing).trim() else raw.trim()
        }
        // Plain <new_content>…</new_content> — strip any stray CDATA markers
        val openIdx = afterHeader.indexOf(NEW_CONTENT_OPEN)
        if (openIdx >= 0) {
            val raw = afterHeader.substring(openIdx + NEW_CONTENT_OPEN.length)
            val closeIdx = raw.indexOf(NEW_CONTENT_CLOSE)
            val content = if (closeIdx >= 0) raw.substring(0, closeIdx) else raw
            return content.trim().removePrefix(CDATA_OPEN).removeSuffix(CDATA_CLOSE).trim()
        }
        return ""
    }

    private fun buildProposal(path: String, summary: String, newContent: String): EditProposal? {
        val trimmedPath = path.trim()
        if (!trimmedPath.contains('.')) return null
        val oldContent = runCatching { contextService.readFile(trimmedPath) }.getOrDefault("")
        val cleanContent = newContent.trim()
            .removePrefix(CDATA_OPEN).removeSuffix(CDATA_CLOSE).trim()
        return EditProposal(filePath = trimmedPath, oldContent = oldContent, newContent = cleanContent, summary = summary)
    }

    data class ProposalResult(
        val proposals: List<EditProposal>,
        val rejectionReasons: List<String>,
    )

    /**
     * Extracts EditProposals directly from tool result messages.
     * Only hard-rejects structurally broken proposals (corrupt path, hallucinated tool syntax).
     * Stub or low-quality content is surfaced as a proposal so the user can review and decide.
     */
    private fun buildProposalsFromToolMessages(toolMessages: List<ChatMessage>): ProposalResult {
        val proposals = mutableListOf<EditProposal>()
        val rejections = mutableListOf<String>()

        for (msg in toolMessages) {
            val toolName = msg.toolName ?: continue
            if (toolName !in EDIT_TOOL_NAMES) continue
            val content = msg.content

            // Silently skip results whose format we don't recognise (e.g. tool returned an error).
            val path = when {
                content.startsWith("Prepared file replacement for ") ->
                    content.removePrefix("Prepared file replacement for ").substringBefore("\n")
                content.startsWith("Prepared replacement for ") ->
                    content.removePrefix("Prepared replacement for ").substringBefore("\n")
                content.startsWith("Prepared new file for ") ->
                    content.removePrefix("Prepared new file for ").substringBefore("\n")
                else -> continue
            }.trim()

            val newContent = when {
                content.contains("\n---NEW---\n") -> content.substringAfter("\n---NEW---\n").trim()
                content.startsWith("Prepared new file for ") -> content.substringAfter("\n").trim()
                else -> continue
            }
            val oldContent = when {
                content.contains("\n---OLD---\n") -> content.substringAfter("\n---OLD---\n").substringBefore("\n---NEW---\n").trim()
                else -> runCatching { contextService.readFile(path) }.getOrDefault("")
            }

            when (val v = ProposalValidator.validate(path, newContent)) {
                is ProposalValidator.Result.Invalid -> {
                    rejections += "write to '$path' rejected: ${v.reason}"
                    continue
                }
                is ProposalValidator.Result.Valid -> Unit
            }
            proposals += EditProposal(filePath = path, oldContent = oldContent, newContent = newContent, summary = "Changes from $toolName")
        }

        return ProposalResult(proposals, rejections)
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
                    appendLine(content.take(OPEN_FILE_CONTENT_MAX_CHARS))
                }
            }
            val selected = contextService.getSelectedText()?.take(SELECTED_TEXT_MAX_CHARS)
            if (!selected.isNullOrBlank()) appendLine("Selected text: $selected")
            val pinned = contextService.pinnedInstructions()
            if (pinned.isNotBlank()) appendLine("Project instructions: ${pinned.take(PINNED_INSTRUCTIONS_MAX_CHARS)}")
            if (fileContext.isNotBlank()) {
                appendLine("=== Attached files ===")
                append(fileContext.take(FILE_CONTEXT_MAX_CHARS))
                appendLine("=== End attached files ===")
            }
        }

        // session.messages already contains the current user message (added by the caller before
        // invoking chat()), so we must not append it again here.
        val recentMessages = fitMessagesInBudget(session.messages, HISTORY_BUDGET_CHARS)

        val config = BackendConfig(
            modelIdOrPath = settings.modelPath.ifBlank { settings.modelIdentifier },
            temperature = settings.temperature,
            maxTokens = settings.maxTokens,
            contextLength = settings.contextSize,
            numThreads = settings.numThreads,
            useMmap = settings.useMmap,
            flashAttention = settings.flashAttention,
            batchSize = settings.batchSize,
            gpuLayers = settings.gpuLayers,
            localOnly = settings.localOnly,
        )

        val tokenBuffer = StringBuilder()
        val inThink = java.util.concurrent.atomic.AtomicBoolean(false)
        fun filteredChatToken(token: String) {
            tokenBuffer.append(token)
            while (tokenBuffer.isNotEmpty()) {
                val buf = tokenBuffer.toString()
                if (!inThink.get()) {
                    val openIdx = buf.indexOf(THINK_OPEN)
                    if (openIdx >= 0) {
                        if (openIdx > 0) onToken(buf.substring(0, openIdx))
                        inThink.set(true)
                        tokenBuffer.clear()
                        tokenBuffer.append(buf.substring(openIdx + THINK_OPEN.length))
                        continue
                    }
                    val holdLen = holdLenForTags(buf, ANSWER_HOLD_TAGS)
                    val safeEnd = buf.length - holdLen
                    if (safeEnd > 0) {
                        onToken(buf.substring(0, safeEnd))
                        tokenBuffer.clear()
                        tokenBuffer.append(buf.substring(safeEnd))
                    }
                    break
                } else {
                    val closeIdx = buf.indexOf(THINK_CLOSE)
                    if (closeIdx >= 0) {
                        inThink.set(false)
                        tokenBuffer.clear()
                        tokenBuffer.append(buf.substring(closeIdx + THINK_CLOSE.length))
                        continue
                    }
                    tokenBuffer.clear()
                    break
                }
            }
        }

        return backend.generate(
            GenerationRequest(
                systemPrompt = systemPrompt,
                messages = recentMessages,
                config = config,
                sessionTag = SessionTag.CHAT,
            ),
            object : GenerationCallbacks {
                override fun onToken(token: String) = filteredChatToken(token)
                override fun onCompleted(fullText: String) {
                    val echoStripped = ToolCallParser.stripLeadingEcho(fullText, cleanInstruction)
                    val answer = ToolCallParser.stripThinking(echoStripped).trim()
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
            gpuLayers = settings.gpuLayers,
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
        val uiBlockedPrefixes = listOf(TAG_TOOL_CALL_LC, TAG_EDIT_PROPOSAL, TAG_TOOL_CALL_TC, TAG_TOOL_CALL_UC, TAG_END_OF_TURN, TAG_EOS)
        val holdTags = listOf(THINK_OPEN, TAG_EDIT_PROPOSAL, TAG_TOOL_CALL_LC, TAG_TOOL_CALL_TC, TAG_TOOL_CALL_UC, TAG_END_OF_TURN, TAG_EOS)
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
                    val openIdx = buf.indexOf(THINK_OPEN)
                    if (openIdx >= 0) {
                        if (openIdx > 0) flushAnswerBuffer(buf.substring(0, openIdx))
                        if (streamBlocked.get()) { tokenBuffer.clear(); break }
                        inThink.set(true)
                        tokenBuffer.clear()
                        tokenBuffer.append(buf.substring(openIdx + THINK_OPEN.length))
                        continue
                    }
                    val holdLen = holdLenForTags(buf, holdTags)
                    val safeEnd = buf.length - holdLen
                    if (safeEnd > 0) {
                        flushAnswerBuffer(buf.substring(0, safeEnd))
                        tokenBuffer.clear()
                        if (!streamBlocked.get()) tokenBuffer.append(buf.substring(safeEnd))
                    }
                    break
                } else {
                    val bufLow = buf.lowercase()
                    val closeMatch = THINK_CLOSERS.mapNotNull { tag ->
                        val idx = bufLow.indexOf(tag.lowercase())
                        if (idx >= 0) idx to tag else null
                    }.minByOrNull { it.first }
                    if (closeMatch != null) {
                        val (idx, tag) = closeMatch
                        val thinkContent = buf.substring(0, idx)
                        if (thinkContent.isNotEmpty()) onThinkingToken?.invoke(thinkContent)
                        inThink.set(false)
                        tokenBuffer.clear()
                        if (tag == THINK_CLOSE) tokenBuffer.append(buf.substring(idx + tag.length))
                        continue
                    }
                    val holdLen = holdLenForStartsWith(buf, THINK_CLOSERS)
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

        onToken("${STATUS_PREFIX}Analyzing image…")
        val firstHandle = multimodalBackend.generateWithImages(
            systemPrompt = visionSystemPrompt,
            userPrompt = cleanInstruction.take(USER_INSTRUCTION_MAX_CHARS),
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

                    if (!cancelled.get()) onToken("${STATUS_PREFIX}Applying changes…")

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
                        appendLine(visionDescription.take(VISION_DESCRIPTION_MAX_CHARS))
                        if (fileContext.isNotBlank()) {
                            appendLine()
                            appendLine("=== File to modify ===")
                            append(fileContext.take(FILE_CONTEXT_MULTIMODAL_MAX_CHARS))
                            appendLine()
                            appendLine("=== End file ===")
                        }
                        appendLine()
                        appendLine("Task: ${cleanInstruction.take(SECOND_PASS_TASK_MAX_CHARS)}")
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
                            sessionTag = SessionTag.AGENT,
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
                                val answer = if (proposals.isNotEmpty() || text.contains(TAG_EDIT_PROPOSAL, ignoreCase = true)) "" else ToolCallParser.cleanForDisplay(text)
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

    companion object {
        // Agent identity
        private const val AGENT_IDENTITY = "You are Llamatik Code, an autonomous local development agent for IntelliJ. You plan, patch, review, and verify code locally — all source code stays on the user's machine."

        // Enterprise / mission mode trigger phrases
        private val ENTERPRISE_MODE_TRIGGERS = setOf(
            "offline enterprise pair programmer",
            "autonomous local development",
            "enterprise_mission_create",
            "enterprise mission",
            "local coding mission",
        )

        // XML tag constants
        private const val THINK_OPEN = "<think>"
        private const val THINK_CLOSE = "</think>"
        private const val CDATA_OPEN = "<![CDATA["
        private const val CDATA_CLOSE = "]]>"
        private const val NEW_CONTENT_OPEN = "<new_content>"
        private const val NEW_CONTENT_CLOSE = "</new_content>"
        private const val FILE_SECTION_PREFIX = "### File: "
        // Sentinel appended to stored user-turn messages to carry file context across turns.
        // Everything after this line is file content and must be stripped before sending to the model.
        private const val HISTORY_FILE_SENTINEL = "\n\n[attached_file_context]\n"

        // XML streaming tag constants
        private const val TAG_EDIT_PROPOSAL = "<edit_proposal"
        private const val TAG_TOOL_CALL_LC = "<tool_call"
        private const val TAG_TOOL_CALL_TC = "<Tool_call"
        private const val TAG_TOOL_CALL_UC = "<TOOL_CALL"
        private const val TAG_END_OF_TURN = "<end_of_turn>"
        private const val TAG_EOS = "<eos>"

        // Tool name constants (referenced in sets and direct comparisons)
        private const val TOOL_LIST_FILES = "list_files"
        private const val TOOL_READ_FILE = "read_file"
        private const val TOOL_SEARCH_CODE = "search_code"
        private const val TOOL_SEARCH_FILENAMES = "search_filenames"
        private const val TOOL_GET_CURRENT_FILE = "get_current_file"
        private const val TOOL_GET_SELECTED_TEXT = "get_selected_text"
        private const val TOOL_WRITE_FILE = "write_file"
        private const val TOOL_CREATE_FILE = "create_file"
        private const val TOOL_REPLACE_IN_FILE = "replace_in_file"

        // Agentic loop limits
        private const val MAX_ROUNDS = 5
        private const val MAX_TOOL_CALLS_PER_ROUND = 4
        private const val MAX_HISTORY_MESSAGES = 20
        // Exploration rounds only need enough tokens to emit 1–4 tool calls (~80 tokens each).
        // Capping them prevents the model burning the entire token budget on intermediate reasoning.
        private const val EXPLORATION_ROUND_MAX_TOKENS = 512
        // Write pass must be able to output a full file. User maxTokens setting is often 512–1024
        // which is far too small for a Compose screen. Always allow at least this many tokens to write.
        private const val WRITE_ROUND_MIN_TOKENS = 8192
        // Global wall-clock cap per agent session — safety net against true infinite hangs only.
        // Legitimate write tasks on a local 27B model can take 10+ minutes; set the cap high enough
        // that real work always completes, while still breaking runaway loops.
        private const val AGENT_SESSION_TIMEOUT_MS = 20 * 60 * 1000L // 20 minutes

        // Character budgets for prompt sections
        private const val HISTORY_BUDGET_CHARS = 1200        // chat mode
        private const val AGENT_HISTORY_BUDGET_CHARS = 4000  // agent mode — larger to retain file context across turns
        private const val USER_INSTRUCTION_MAX_CHARS = 800
        // Conservative upper bound for the static system prompt (identity + tools + format rules +
        // skills + codebase rules + memory).  Used in the file-budget calculation to ensure the
        // total rendered prompt stays within batchSize * 3 chars.
        private const val SYSTEM_PROMPT_BASE_CHARS = 3_000
        private const val OPEN_FILE_CONTENT_MAX_CHARS = 800
        private const val SELECTED_TEXT_MAX_CHARS = 300
        private const val PINNED_INSTRUCTIONS_MAX_CHARS = 200
        private const val TASK_EXCERPT_MAX_CHARS = 600
        private const val FILE_CONTEXT_MAX_CHARS = 3000
        private const val FILE_CONTEXT_MULTIMODAL_MAX_CHARS = 2400
        private const val FILE_ATTACHMENT_MAX_CHARS = 2000
        private const val VISION_DESCRIPTION_MAX_CHARS = 1200
        private const val SECOND_PASS_TASK_MAX_CHARS = 300
        private const val SKILLS_PROMPT_MAX_CHARS = 400
        private const val TOOL_RESULTS_MAX_CHARS = 8000          // floor; scales with contextSize at runtime
        private const val TOOL_RESULT_CONTENT_MAX_CHARS = 8000   // floor; scales with contextSize at runtime
        private const val TOOL_ARG_DISPLAY_MAX_CHARS = 60

        // Sentinel prefixes used to route special tokens through the onToken callback
        const val STATUS_PREFIX = " STATUS:"
        const val TOOL_PREFIX = " TOOL:"

        // Token streaming: tags that signal end-of-answer or start of a think block
        private val ANSWER_HOLD_TAGS = listOf(THINK_OPEN, TAG_END_OF_TURN, TAG_EOS)
        // Tags that close a <think> block; <wait covers all <wait...> variants
        private val THINK_CLOSERS = listOf(THINK_CLOSE, "<|im_end|>", "<|endoftext|>", "<wait")

        // Tool name sets used in the agentic loop
        private val KNOWN_TOOL_NAMES = setOf(
            TOOL_LIST_FILES, TOOL_READ_FILE, TOOL_SEARCH_CODE, TOOL_SEARCH_FILENAMES,
            TOOL_GET_CURRENT_FILE, TOOL_GET_SELECTED_TEXT, TOOL_WRITE_FILE, TOOL_CREATE_FILE,
            TOOL_REPLACE_IN_FILE, "show_diff", "apply_patch",
            // Git tools
            "git_status", "git_diff", "git_changed_files", "git_branch", "git_commit_message_suggestion",
            // PSI tools
            "psi_file_structure", "psi_find_symbol", "psi_find_usages",
            // Index tools
            "index_search", "index_relevant_files", "index_refresh", "index_status",
            // Run config tools
            "run_config_list", "run_config_last_output", "run_config_analyze_failure",
            // Codebase rules tools
            "codebase_rules_get", "codebase_rules_refresh", "codebase_rules_explain",
            // Terminal
            "terminal_allowed_commands", "terminal_run", "terminal_last_output",
            // Static analysis
            "static_analysis_detect_tools", "static_analysis_run", "static_analysis_parse_results",
            // Dependencies
            "deps_list", "deps_check_updates", "deps_plan_upgrade",
            // Build failure
            "build_failure_parse", "build_failure_explain", "build_failure_fix_plan",
            // Changelog / PR
            "pr_summary_generate", "changelog_generate", "commit_summary_generate",
            // Multi-file plan
            "plan_preview", "plan_current", "plan_approve", "plan_reject",
            // Agent memory
            "project_memory_get", "project_memory_add", "project_memory_update",
            "project_memory_delete", "project_memory_search",
            // UI repair
            "ui_image_analyze", "ui_find_candidate_files", "ui_generate_patch_plan",
            // Benchmark
            "model_benchmark_list", "model_benchmark_run", "model_benchmark_results",
            // Diagnostics
            "diagnostics_run", "diagnostics_fix_suggestions",
            // Smart search
            "smart_search",
            // Test coverage
            "tests_missing_analyze",
            // Project snapshot
            "project_snapshot_get", "project_snapshot_refresh",
            // KMP/native
            "kmp_native_diagnose",
            // MCP agent tools
            "mcp_list_servers", "mcp_list_tools", "mcp_call_tool",
            "mcp_list_resources", "mcp_read_resource", "mcp_list_prompts",
            // External search
            "external_search", "external_search_official_docs", "external_search_github_issues",
            // External page reader
            "external_read_page", "external_read_pages",
            // Research
            "research_plan", "research_execute", "research_summarize", "research_route_sources", "research_fix_plan",
            // Docs sources
            "docs_sources_list", "docs_source_add", "docs_index_refresh", "docs_search",
            // Error research
            "error_research_analyze", "error_research_fix",
            // Enterprise Pair Programmer
            "enterprise_mission_create", "enterprise_mission_context",
            "enterprise_mission_audit", "enterprise_mission_report",
            // Code Health Monitor
            "code_health_status", "code_health_refresh", "code_health_list_insights",
            "code_health_explain_insight", "code_health_create_fix_plan", "code_health_ignore_insight",
            "watch_area_add", "watch_area_remove", "watch_area_list",
            "session_context_get", "session_context_clear",
            "before_commit_review", "regression_risk_analyze", "refactor_analyze_file",
        )
        private val READ_ONLY_TOOL_NAMES = setOf(
            TOOL_LIST_FILES, TOOL_READ_FILE, TOOL_SEARCH_CODE, TOOL_SEARCH_FILENAMES,
            TOOL_GET_CURRENT_FILE, TOOL_GET_SELECTED_TEXT,
            "git_status", "git_diff", "git_changed_files", "git_branch", "git_commit_message_suggestion",
            "psi_file_structure", "psi_find_symbol", "psi_find_usages",
            "index_search", "index_relevant_files", "index_status",
            "run_config_list", "run_config_last_output", "run_config_analyze_failure",
            "codebase_rules_get", "codebase_rules_explain",
            "terminal_allowed_commands", "terminal_last_output",
            "static_analysis_detect_tools", "static_analysis_parse_results",
            "deps_list", "deps_check_updates",
            "build_failure_parse", "build_failure_explain",
            "pr_summary_generate", "changelog_generate", "commit_summary_generate",
            "plan_current",
            "project_memory_get", "project_memory_search",
            "ui_image_analyze", "ui_find_candidate_files",
            "model_benchmark_list", "model_benchmark_results",
            "diagnostics_run", "diagnostics_fix_suggestions",
            "smart_search",
            "tests_missing_analyze",
            "project_snapshot_get", "project_snapshot_refresh",
            "kmp_native_diagnose",
            // MCP agent tools (read-only: listing and reading)
            "mcp_list_servers", "mcp_list_tools",
            "mcp_list_resources", "mcp_read_resource", "mcp_list_prompts",
            // External search (read-only)
            "external_search", "external_search_official_docs", "external_search_github_issues",
            // External page reader (read-only)
            "external_read_page", "external_read_pages",
            // Research (read-only)
            "research_plan", "research_execute", "research_summarize", "research_route_sources", "research_fix_plan",
            // Docs sources (read-only for list/search)
            "docs_sources_list", "docs_search",
            // Error research (read-only)
            "error_research_analyze", "error_research_fix",
            // Code Health Monitor (read-only subset)
            "code_health_status", "code_health_list_insights", "code_health_explain_insight",
            "code_health_create_fix_plan", "watch_area_list", "session_context_get",
            "before_commit_review", "regression_risk_analyze", "refactor_analyze_file",
            // Enterprise Pair Programmer (context/audit/report are read-only; create is planning only)
            "enterprise_mission_create", "enterprise_mission_context",
            "enterprise_mission_audit", "enterprise_mission_report",
        )
        private val EDIT_TOOL_NAMES = setOf(TOOL_WRITE_FILE, TOOL_REPLACE_IN_FILE, TOOL_CREATE_FILE)

        // Vague phrases that refer to the currently open file; replaced with the actual filename.
        internal val CURRENT_FILE_PHRASES = listOf(
            "the current file",
            "this file",
            "the open file",
            "the opened file",
            "current file",
            "open file",
        )
    }
}
