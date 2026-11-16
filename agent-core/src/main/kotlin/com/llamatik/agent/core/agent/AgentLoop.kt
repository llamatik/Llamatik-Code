package com.llamatik.agent.core.agent

import com.llamatik.agent.core.backend.AssistantBackend
import com.llamatik.agent.core.backend.BackendConfig
import com.llamatik.agent.core.backend.GenerationCallbacks
import com.llamatik.agent.core.backend.GenerationHandle
import com.llamatik.agent.core.backend.GenerationRequest
import com.llamatik.agent.core.backend.SessionTag
import com.llamatik.agent.core.host.AgentEnvironment
import com.llamatik.agent.core.model.ChatMessage
import com.llamatik.agent.core.model.ChatRole
import com.llamatik.agent.core.model.ChatSession
import com.llamatik.agent.core.model.EditProposal
import com.llamatik.agent.core.tools.ToolCallParser
import com.llamatik.agent.core.tools.ToolExecutionContext
import com.llamatik.agent.core.tools.ToolResult
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The agent: a tool-driven, multi-round loop on top of an [AssistantBackend]. It has no IDE
 * dependency; everything it touches comes in through [env], [tools] and [sources].
 *
 * Extracted from the IntelliJ plugin's AgentOrchestrator without behaviour changes.
 */
class AgentLoop(
    private val backend: AssistantBackend,
    private val env: AgentEnvironment,
    private val tools: ToolExecutor,
    private val policy: ToolPolicy,
    sources: PromptSources,
    private val limits: AgentLimits = AgentLimits(),
) {
    val promptBuilder = PromptBuilder(env, tools, sources)
    val fileReferences = FileReferences(env)
    val proposalExtractor = ProposalExtractor(env, policy.editToolNames)

    /**
     * Runs the multi-round agent loop for one user prompt: the model may call tools across up to
     * [AgentLimits.maxRounds] rounds before it answers or proposes edits.
     *
     * Special tokens routed through [onToken]: text starting with [STATUS_PREFIX] is a progress
     * message, text starting with [TOOL_PREFIX] announces a tool call (`name:firstArgument`).
     */
    fun submit(
        session: ChatSession,
        userPrompt: String,
        config: BackendConfig,
        onToken: (String) -> Unit,
        onThinkingToken: ((String) -> Unit)? = null,
        onFinished: (ChatMessage, List<EditProposal>) -> Unit,
        onError: (String) -> Unit,
        onTokenCount: ((Int) -> Unit)? = null,
        onTrace: ((AgentTrace) -> Unit)? = null,
    ): GenerationHandle {

        // Budget for file content sent to the model.
        // contextSize is the actual model context window in tokens; multiply by 3 for a char estimate
        // (UTF-8 source code is ~3 chars per token on average).
        // batchSize is a KV-cache batching parameter, not the context limit — it must NOT constrain
        // how much file content we send, so it is excluded from this calculation.
        //   SYSTEM_PROMPT_BASE_CHARS  — identity, tools, format rules, skills, rules, memory
        //   AGENT_HISTORY_BUDGET_CHARS — prior turn history
        //   USER_INSTRUCTION_MAX_CHARS — current user turn text
        //   1_200                      — context hint + file-section wrappers + safety margin
        val budget = PromptBudget(config.contextLength)
        val safeContextChars = budget.safeContextChars
        val fileAttachmentMax    = budget.fileAttachmentMax
        val fileContextMax       = budget.fileContextMax
        val toolResultContentMax = budget.toolResultContentMax
        val toolResultsMax       = budget.toolResultsMax
        val round2FileContextMax = budget.round2FileContextMax

        val cancelled = AtomicBoolean(false)
        // Tracks whichever backend call is currently active so cancel() always reaches the live handle.
        val activeHandle = AtomicReference<GenerationHandle>()

        val streamFilter = StreamFilter(StreamFilter.Mode.AGENT, onThinking = onThinkingToken)

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
        val (afterAtRefs, atFileContext) = fileReferences.resolveFileReferences(userPrompt, fileAttachmentMax)
        // Rewrite vague "current file" / "this file" phrases to the actual filename so small
        // models don't search_code for the instruction text instead of reading the file.
        val (cleanInstruction, currentFileContext) = fileReferences.resolveCurrentFileReference(afterAtRefs, fileAttachmentMax)
        // Auto-inject files referenced by file:/// URIs in pasted build error logs (e.g. Gradle/Kotlin
        // compiler output). Without this the model sees the URI but cannot resolve it to project source.
        val (buildErrorContext, buildErrorPaths) = fileReferences.resolveBuildErrorReferences(cleanInstruction, fileAttachmentMax)
        val fileContext = listOf(atFileContext, currentFileContext, buildErrorContext).filter { it.isNotBlank() }.joinToString("\n\n")

        // Edit requests get a forced write pass when the model answers in prose; questions keep their answer.
        val wantsEdit = TaskIntent.wantsEdit(cleanInstruction)

        val contextHint = promptBuilder.buildContextHint()

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
            append(promptBuilder.buildSystemPrompt())
            if (promptBuilder.isEnterpriseMission(userPrompt)) {
                append(promptBuilder.buildEnterpriseMissionSystemPromptAddition())
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
        val recentMessages = promptBuilder.fitMessagesInBudget(session.messages, AGENT_HISTORY_BUDGET_CHARS)

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
        // Only pre-seed the current open file if its content was actually injected into
        // effectiveFileContext. Without injection the model has no content to work from,
        // and blocking read_file would leave it with no way to get the source.
        if (currentFileContext.isNotBlank()) {
            env.editor?.currentFile()?.let { executedToolKeys += TOOL_READ_FILE to it }
        }
        // Pre-seed build-error files so the model doesn't waste a round re-reading them.
        if (buildErrorContext.isNotBlank()) {
            buildErrorPaths.forEach { path -> executedToolKeys += TOOL_READ_FILE to path }
        }

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

        // Multi-round agentic loop: model can call tools across up to limits.maxRounds before answering.
        // Each round feeds tool results back so the model can: list files -> read files -> write files.
        fun runRound(messages: List<ChatMessage>, round: Int, accumulatedProposals: List<EditProposal>, hasReadContent: Boolean = false, prefill: String = "", emptyRetry: Int = 0, toolErrorRetry: Int = 0) {
            if (cancelled.get()) return
            // Global wall-clock safety net — kills the session if the total time exceeds the cap.
            // Skip for the write pass: the model is actively generating file content and must not
            // be interrupted; exploration rounds are the ones that can stall indefinitely.
            val isWritePass = round >= limits.maxRounds - 1
            if (!isWritePass && System.currentTimeMillis() - sessionStartMs > limits.sessionTimeoutMs) {
                val msg = ChatMessage(role = ChatRole.ASSISTANT, content = "$TIMEOUT_MESSAGE_PREFIX${limits.sessionTimeoutMs / 60_000} minutes. Try a shorter task or attach the file directly with @filename.")
                session.messages += msg
                onFinished(msg, accumulatedProposals)
                return
            }
            val roundSystemPrompt = if (round == 1) systemPrompt else promptBuilder.buildSecondPassSystemPrompt()
            // Disable thinking on ALL agent rounds — iterative tool calls are the reasoning loop,
            // so per-round <think> blocks only burn token budget and add latency without improving quality.
            // Use a tighter token cap for exploration rounds (tool calls need few tokens to express);
            // give the full budget only on the write pass so large file content fits in the output.
            val isWriteRound = round >= limits.maxRounds - 1
            // Exploration rounds must still allow thinking models to emit at least one tool call
            // after their <think> block. EXPLORATION_ROUND_MAX_TOKENS is a soft cap — if the
            // user's maxTokens setting is smaller than the floor, use the floor.
            val roundMaxTokens = if (isWriteRound) maxOf(config.maxTokens, WRITE_ROUND_MIN_TOKENS)
                                 else maxOf(config.maxTokens, EXPLORATION_ROUND_MAX_TOKENS)
            val handle = backend.generate(
                GenerationRequest(
                    systemPrompt = roundSystemPrompt,
                    messages = messages,
                    config = config.copy(maxTokens = roundMaxTokens),
                    noThink = true,
                    sessionTag = SessionTag.AGENT,
                    assistantPrefill = prefill,
                ),
                object : GenerationCallbacks {
                    override fun onToken(token: String) {
                        countToken()
                        if (!cancelled.get()) streamFilter.accept(token)
                    }

                    override fun onCompleted(fullText: String) {
                        if (cancelled.get()) return
                        // When assistant prefill was used, the model generated text AFTER the prefill.
                        // Reconstruct the full tool call by prepending the prefill back.
                        // A reply that opens a fresh tool call ignored the prefill: use it as is, never glue the two.
                        val modelStartedOwnCall = prefill.isNotBlank() && fullText.trimStart().startsWith("<tool_call", ignoreCase = true)
                        // The prefill alone is not an answer: a model that produced nothing after it said nothing.
                        val modelSaidNothing = prefill.isNotBlank() && ToolCallParser.stripThinking(fullText).isBlank()
                        val textWithPrefill = when {
                            modelSaidNothing -> ""
                            prefill.isNotBlank() && !modelStartedOwnCall -> prefill + fullText
                            else -> fullText
                        }
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

                        // Blank output guard: local models occasionally emit only whitespace or nothing
                        // after stripping think/echo. Retry the same round up to MAX_EMPTY_RETRIES
                        // before falling through to the normal termination path.
                        if (answerText.isBlank() && emptyRetry < MAX_EMPTY_RETRIES) {
                            streamFilter.reset()
                            if (!cancelled.get()) onToken("${STATUS_PREFIX}Retrying…")
                            runRound(messages, round, accumulatedProposals, hasReadContent, prefill, emptyRetry + 1)
                            return
                        }

                        // Filter to known tool names — hallucinated names (e.g. "diff_tool") would loop forever.
                        // Dynamic MCP bridged tools are also allowed (they are in the registry's dynamicTools map).
                        val dynamicMcpNames = tools.dynamicToolNames()
                        val toolCalls = ToolCallParser.parseIncludingIncomplete(answerText)
                            .filter { it.name in policy.knownToolNames || it.name in dynamicMcpNames }
                            .take(limits.maxToolCallsPerRound)

                        // Mid-loop prose guard: model wrote text instead of a tool call after having
                        // read content (or when a file was explicitly attached). Give it one explicit
                        // write pass before the normal terminal path.
                        // Round == 1 is also allowed when effectiveFileContext is set: the file is
                        // already in the system prompt, so the model has everything it needs and
                        // emitting prose instead of a tool call means it got confused — escalate
                        // directly rather than showing "file not found" to the user.
                        // Bounds: round < limits.maxRounds - 1 to avoid re-entering this path recursively.
                        val hasFileInContext = effectiveFileContext.isNotBlank()
                        val shouldEscalateToWrite = wantsEdit && toolCalls.isEmpty() && round < limits.maxRounds - 1 &&
                            (hasReadContent || (hasFileInContext && round == 1))
                        if (shouldEscalateToWrite) {
                            val finalDirective = RoundDirective.get(RoundDirective.RoundState(
                                round = limits.maxRounds - 1,
                                maxRounds = limits.maxRounds,
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
                            streamFilter.reset()
                            if (!cancelled.get()) onToken("${STATUS_PREFIX}Writing changes…")
                            // Pass hasReadContent = true when file context was explicitly attached —
                            // the file IS in context even though the model never called read_file.
                            runRound(listOf(ChatMessage(role = ChatRole.USER, content = finalContent)), limits.maxRounds - 1, accumulatedProposals, hasReadContent || hasFileInContext, writePassPrefill())
                            return
                        }

                        if (toolCalls.isEmpty() || round >= limits.maxRounds) {
                            // On the final round the model may emit write_file/create_file/replace_in_file
                            // tool calls instead of <edit_proposal> blocks. Execute those edit calls so
                            // their proposals are not silently dropped.
                            val finalRoundToolProposals: List<EditProposal> = if (round >= limits.maxRounds && toolCalls.isNotEmpty()) {
                                val editCalls = toolCalls.filter { it.name in policy.editToolNames }
                                if (editCalls.isNotEmpty()) {
                                    val editMessages = editCalls.map { call ->
                                        val mainArg = call.arguments.values.firstOrNull().orEmpty().take(TOOL_ARG_DISPLAY_MAX_CHARS)
                                        if (!cancelled.get()) onToken("${TOOL_PREFIX}${call.name}:$mainArg")
                                        val result = tools.execute(call, ToolExecutionContext(env))
                                        agentTrace.add(AgentTraceStep(
                                            type = AgentTraceStep.Type.VERIFICATION,
                                            title = "${call.name}($mainArg)",
                                            summary = result.content.take(120).replace('\n', ' '),
                                            status = AgentTraceStep.Status.SUCCESS,
                                            durationMs = 0,
                                        ))
                                        ChatMessage(role = ChatRole.TOOL, content = result.content, toolName = result.name)
                                    }
                                    proposalExtractor.buildProposalsFromToolMessages(editMessages).proposals
                                } else emptyList()
                            } else emptyList()
                            val xmlProposals = proposalExtractor.extractEditProposals(answerText)
                            val proposals = (finalRoundToolProposals + xmlProposals + accumulatedProposals).distinctBy { it.filePath }
                            // After tool-use rounds the model's final answer is unreliable (hallucinated
                            // tool syntax, echoed system prompt markers, etc.). Only surface it on round 1
                            // with no proposals — every other terminal case uses the structured fallback.
                            // Also require a minimum word count: a bare file path or single-token output
                            // from a truncated generation is not a real answer and must not be displayed.
                            val cleanedRound1 = ToolCallParser.cleanForDisplay(echoStripped, allowThinkFallback = true)
                            val isLikelyRealAnswer = cleanedRound1.contains(' ') && cleanedRound1.split("\\s+".toRegex()).size >= 4
                            val clean = if (round == 1 && proposals.isEmpty() && isLikelyRealAnswer) {
                                cleanedRound1
                            } else if (proposals.isNotEmpty()) {
                                ""
                            } else if (accumulatedRejections.isEmpty() && (hasReadContent || !wantsEdit)) {
                                // Read-only tasks (review, analysis, explanation) and tasks that only ran commands — surface the model's
                                // answer text. Allow think fallback so base models (which put their entire
                                // answer inside <think> blocks) still show output.
                                val answer = ToolCallParser.cleanForDisplay(echoStripped, allowThinkFallback = true)
                                answer.ifBlank {
                                    if (hasReadContent) {
                                        "The agent reviewed the files but did not produce a written summary. " +
                                            "Try asking again or use @filename to attach a specific file."
                                    } else {
                                        "The model did not produce an answer. " +
                                            "Try asking again or use @filename to attach a specific file."
                                    }
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
                        streamFilter.reset()

                        var allCallsDuplicate = true
                        val toolMessages = toolCalls.map { call ->
                            val mainArg = call.arguments.values.firstOrNull().orEmpty().take(TOOL_ARG_DISPLAY_MAX_CHARS)
                            if (!cancelled.get()) onToken("${TOOL_PREFIX}${call.name}:$mainArg")
                            val toolKey = call.name to call.arguments.values.firstOrNull().orEmpty()
                            val isDuplicateReadOnly = call.name in policy.readOnlyToolNames && !executedToolKeys.add(toolKey)
                            if (!isDuplicateReadOnly) allCallsDuplicate = false
                            val t0 = System.currentTimeMillis()
                            val result = if (isDuplicateReadOnly) {
                                ToolResult(name = call.name, success = true, content = "Already called ${call.name}(${call.arguments.values.firstOrNull().orEmpty()}) in a previous round. Use the result you already received — do not call this tool again.")
                            } else {
                                tools.execute(call, ToolExecutionContext(env))
                            }
                            val elapsed = System.currentTimeMillis() - t0
                            val traceType = when {
                                call.name == TOOL_READ_FILE -> AgentTraceStep.Type.FILE_READ
                                call.name in setOf(TOOL_SEARCH_CODE, TOOL_SEARCH_FILENAMES, "index_search", "smart_search") -> AgentTraceStep.Type.SEARCH
                                call.name in policy.editToolNames -> AgentTraceStep.Type.VERIFICATION
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

                        // Tool error reflection: if every tool call this round failed, OR any write
                        // tool failed (even when read-only calls succeeded), feed errors back so the
                        // model can correct its arguments (wrong path, truncated content, etc.).
                        // Skip for duplicate-suppressed calls — those are informational, not errors.
                        val failedMessages = toolMessages.filter {
                            val firstLine = it.content.lines().firstOrNull().orEmpty()
                            !firstLine.startsWith("Already called") &&
                                (firstLine.startsWith("Error") || firstLine.startsWith("Missing") ||
                                    firstLine.contains("not found") || firstLine.contains("failed:"))
                        }
                        val allToolsFailed = failedMessages.size == toolMessages.size && toolMessages.isNotEmpty() && !allCallsDuplicate
                        val anyWriteToolFailed = failedMessages.any { msg -> msg.toolName in policy.editToolNames }
                        if ((allToolsFailed || anyWriteToolFailed) && toolErrorRetry < MAX_TOOL_ERROR_RETRIES && round < limits.maxRounds) {
                            val errorSummary = failedMessages.joinToString("\n") { msg ->
                                "Tool ${msg.toolName} error: ${msg.content.lines().firstOrNull().orEmpty().take(200)}"
                            }
                            // Record write failures NOW so the terminal path always knows a write was
                            // attempted and failed, even if the reflection retry produces prose instead
                            // of a tool call and hits the terminal path with an empty accumulatedRejections.
                            failedMessages.filter { it.toolName in policy.editToolNames }.forEach { msg ->
                                accumulatedRejections += "${msg.toolName} failed: ${msg.content.lines().firstOrNull().orEmpty().take(200)}"
                            }
                            val reflectionContent = buildString {
                                appendLine("Task: ${cleanInstruction.take(TASK_EXCERPT_MAX_CHARS)}")
                                appendLine()
                                appendLine("The following tool calls failed:")
                                appendLine(errorSummary)
                                appendLine()
                                appendLine("Fix the arguments and try again. Common causes:")
                                appendLine("- Wrong file path: use list_files or search_filenames to find the correct path first.")
                                appendLine("- replace_in_file oldText mismatch: use read_file to get the exact current content, then copy the exact lines to replace.")
                                appendLine("- Missing required argument: check the tool's argument names.")
                            }
                            streamFilter.reset()
                            if (!cancelled.get()) onToken("${STATUS_PREFIX}Correcting…")
                            runRound(
                                listOf(ChatMessage(role = ChatRole.USER, content = reflectionContent)),
                                round, accumulatedProposals, hasReadContent, prefill = "", toolErrorRetry = toolErrorRetry + 1,
                            )
                            return
                        }

                        val proposalResult = proposalExtractor.buildProposalsFromToolMessages(toolMessages)
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
                        val wroteFiles = toolCalls.any { it.name in policy.editToolNames }
                        // All calls this round were dedup synthetic — no new information can arrive.
                        // Escalate directly to the final-round write pass instead of burning another round.
                        // Only for edit requests, and never from the write pass itself: re-entering it would repeat
                        // forever for a model that keeps issuing the same call.
                        if (wantsEdit && allCallsDuplicate && roundProposals.isEmpty() && round < limits.maxRounds - 1) {
                            val finalDirective = RoundDirective.get(RoundDirective.RoundState(
                                round = limits.maxRounds - 1,
                                maxRounds = limits.maxRounds,
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
                            streamFilter.reset()
                            if (!cancelled.get()) onToken("${STATUS_PREFIX}Writing changes…")
                            runRound(listOf(ChatMessage(role = ChatRole.USER, content = finalContent)), limits.maxRounds - 1, allProposals, hasReadContent, writePassPrefill())
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

                        val allReadOnly = toolCalls.all { it.name in policy.readOnlyToolNames }
                        val nowHasReadContent = hasReadContent || toolCalls.any { it.name in policy.readOnlyToolNames }

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
                        if (wantsEdit && hasFileContextNow && nowHasReadContent && allReadOnly && roundProposals.isEmpty()
                            && round >= minWriteEscalationRound && round < limits.maxRounds - 1) {
                            val writeDirective = RoundDirective.get(RoundDirective.RoundState(
                                round = limits.maxRounds - 1, maxRounds = limits.maxRounds, hasReadContent = true,
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
                            streamFilter.reset()
                            if (!cancelled.get()) onToken("${STATUS_PREFIX}Writing changes…")
                            runRound(listOf(ChatMessage(role = ChatRole.USER, content = writeContent)), limits.maxRounds - 1, allProposals, nowHasReadContent, writePassPrefill())
                            return
                        }

                        if (!cancelled.get()) onToken("${STATUS_PREFIX}Thinking…")

                        val directive = RoundDirective.get(RoundDirective.RoundState(
                            round = round,
                            maxRounds = limits.maxRounds,
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

    /**
     * Simple chat-only generation — no tools, no second pass, no edit proposals.
     * Used by the Ask tab.
     */
    fun chat(
        session: ChatSession,
        userPrompt: String,
        config: BackendConfig,
        onToken: (String) -> Unit,
        onFinished: (String) -> Unit,
        onError: (String) -> Unit,
    ): GenerationHandle {
        val (cleanInstruction, fileContext) = fileReferences.resolveFileReferences(userPrompt)

        val systemPrompt = promptBuilder.buildChatSystemPrompt(fileContext)

        // session.messages already contains the current user message (added by the caller before
        // invoking chat()), so we must not append it again here.
        val recentMessages = promptBuilder.fitMessagesInBudget(session.messages, HISTORY_BUDGET_CHARS)

        val streamFilter = StreamFilter(StreamFilter.Mode.CHAT, onAnswer = onToken)

        return backend.generate(
            GenerationRequest(
                systemPrompt = systemPrompt,
                messages = recentMessages,
                config = config,
                sessionTag = SessionTag.CHAT,
            ),
            object : GenerationCallbacks {
                override fun onToken(token: String) = streamFilter.accept(token)
                override fun onCompleted(fullText: String) {
                    val echoStripped = ToolCallParser.stripLeadingEcho(fullText, cleanInstruction)
                    val answer = ToolCallParser.stripThinking(echoStripped).trim()
                    onFinished(answer)
                }
                override fun onError(message: String, throwable: Throwable?) = onError(message)
            },
        )
    }
}
