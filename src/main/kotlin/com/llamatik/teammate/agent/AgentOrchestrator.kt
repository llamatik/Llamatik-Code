package com.llamatik.teammate.agent

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.agent.core.agent.AgentLoop
import com.llamatik.agent.core.agent.AgentTrace
import com.llamatik.agent.core.agent.FILE_CONTEXT_MULTIMODAL_MAX_CHARS
import com.llamatik.agent.core.agent.SECOND_PASS_TASK_MAX_CHARS
import com.llamatik.agent.core.agent.TAG_EDIT_PROPOSAL
import com.llamatik.agent.core.agent.USER_INSTRUCTION_MAX_CHARS
import com.llamatik.agent.core.agent.VISION_DESCRIPTION_MAX_CHARS
import com.llamatik.agent.core.backend.AssistantBackend
import com.llamatik.agent.core.backend.BackendConfig
import com.llamatik.agent.core.backend.GenerationCallbacks
import com.llamatik.agent.core.backend.GenerationHandle
import com.llamatik.agent.core.backend.GenerationRequest
import com.llamatik.agent.core.backend.SessionTag
import com.llamatik.agent.core.model.AttachedImage
import com.llamatik.agent.core.model.ChatMessage
import com.llamatik.agent.core.model.ChatRole
import com.llamatik.agent.core.model.ChatSession
import com.llamatik.agent.core.model.EditProposal
import com.llamatik.agent.core.tools.ToolCallParser
import com.llamatik.teammate.ide.IdeAgentEnvironment
import com.llamatik.teammate.ide.IdePromptSources
import com.llamatik.teammate.llamatik.MultimodalBackendService
import com.llamatik.teammate.server.LlamatikRouter
import com.llamatik.teammate.settings.LlamatikAppSettings
import com.llamatik.teammate.tools.ToolRegistry
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * IntelliJ-facing entry point of the agent. The agent itself lives in the IDE-free `agent-core`
 * module ([AgentLoop]); this service wires it to the project (files, editor, tools, settings) and
 * keeps the multimodal (image) path, which needs the native vision backend.
 */
@Service(Service.Level.PROJECT)
class AgentOrchestrator(private val project: Project) {
    // Routes each request to the connected Llamatik server or the local model.
    private val backend: AssistantBackend = service<LlamatikRouter>()
    private val multimodalBackend = service<MultimodalBackendService>()
    private val loop = AgentLoop(
        backend = backend,
        env = IdeAgentEnvironment.create(project),
        tools = project.service<ToolRegistry>(),
        policy = IdeToolPolicy.POLICY,
        sources = IdePromptSources(project),
    )

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
        return loop.submit(
            session = session,
            userPrompt = userPrompt,
            config = backendConfig(),
            onToken = onToken,
            onThinkingToken = onThinkingToken,
            onFinished = onFinished,
            onError = onError,
            onTokenCount = onTokenCount,
            onTrace = onTrace,
        )
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
        return loop.chat(session, userPrompt, backendConfig(), onToken, onFinished, onError)
    }

    /**
     * Inference settings as a backend config.
     *
     * Resource mode thread cap:
     *   FULL     → use exactly the user's numThreads setting (no cap)
     *   BALANCED → cap at half the physical cores so the IDE stays responsive
     *   LOW      → cap at 1/4 cores (min 1); threads already run at MIN_PRIORITY via executor
     */
    private fun backendConfig(): BackendConfig {
        val settings = service<LlamatikAppSettings>().data()
        val physicalCores = Runtime.getRuntime().availableProcessors()
        val effectiveThreads = when (settings.resourceMode) {
            "FULL" -> settings.numThreads
            "LOW"  -> maxOf(1, physicalCores / 4)
            else   -> maxOf(1, minOf(settings.numThreads, physicalCores / 2)) // BALANCED
        }
        return BackendConfig(
            modelIdOrPath = settings.modelPath.ifBlank { settings.modelIdentifier },
            temperature = settings.temperature,
            maxTokens = settings.maxTokens,
            contextLength = settings.contextSize,
            numThreads = effectiveThreads,
            useMmap = settings.useMmap,
            flashAttention = settings.flashAttention,
            batchSize = settings.batchSize,
            gpuLayers = settings.gpuLayers,
            localOnly = settings.localOnly,
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

        val languageHint = loop.promptBuilder.detectProjectLanguage()

        val (cleanInstruction, fileContext) = loop.fileReferences.resolveFileReferences(userPrompt)

        // Pass 1: multimodal model describes the changes shown in the image (plain text, no XML)
        val visionSystemPrompt = buildString {
            appendLine("You are a code-review assistant with vision.")
            appendLine("Describe precisely what code changes are shown in the image.")
            appendLine("List each change: which file, what to add/remove/modify, and the exact new code.")
            appendLine("Be specific and complete. Output plain text only, no XML tags.")
        }.trim()

        val settings = service<LlamatikAppSettings>().data()
        val config = backendConfig()

        val memErr = multimodalBackend.checkCombinedMemoryHeadroom(
            vlmModelPath = settings.modelPath,
            textModelPath = settings.modelPath,
            textContextLength = settings.contextSize,
        )
        if (memErr != null) {
            onError(memErr)
            return compositeHandle
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
                    val secondPassSystemPrompt = loop.promptBuilder.buildSecondPassSystemPrompt()

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
                                val proposals = loop.proposalExtractor.extractEditProposals(text)
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
        // Sentinel prefixes used to route special tokens through the onToken callback.
        const val STATUS_PREFIX = com.llamatik.agent.core.agent.STATUS_PREFIX
        const val TOOL_PREFIX = com.llamatik.agent.core.agent.TOOL_PREFIX
    }
}
