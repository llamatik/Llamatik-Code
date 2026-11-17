package com.llamatik.agent.backends.local

import com.llamatik.agent.backends.prompt.ChatPromptSupport
import com.llamatik.agent.backends.prompt.PluginPromptRenderer
import com.llamatik.agent.core.backend.AssistantBackend
import com.llamatik.agent.core.backend.BackendConfig
import com.llamatik.agent.core.backend.GenerationCallbacks
import com.llamatik.agent.core.backend.GenerationHandle
import com.llamatik.agent.core.backend.GenerationRequest
import com.llamatik.agent.core.backend.SessionTag
import com.llamatik.core.platform.GenStream
import com.llamatik.core.platform.LlamaBridge
import com.llamatik.core.platform.LlamaSession
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Runs a GGUF model in-process through the Llamatik native library (llama.cpp). The model is loaded on
 * the first request and reloaded when the model or a context-affecting setting changes.
 *
 * The one local inference engine: the IDE plugin, the CLI and the ACP agent all use it, differing only
 * in [Options] (how the model is chosen, threading, and the wording of errors).
 *
 * @param fixedModel path of the model to run; when null the model comes with each request
 *   ([BackendConfig.modelIdOrPath]), which is how the IDE lets the user switch models
 */
class LocalLlamaBackend(
    fixedModel: String? = null,
    private val options: Options = Options(),
) : AssistantBackend, AutoCloseable {

    /** What differs between hosts. */
    data class Options(
        /** OS priority of the inference threads. */
        val threadPriority: Int = Thread.NORM_PRIORITY,
        /**
         * One executor (and KV cache) per [SessionTag], so chat and agent requests can run side by side.
         * Off: a single executor serialises everything.
         */
        val executorPerSession: Boolean = false,
        val messages: Messages = Messages(),
    )

    /** User-facing wording; the IDE points at its settings page, the CLI at its flags. */
    data class Messages(
        val noModel: String = "No model configured. Pass a local .gguf model file.",
        /** Appended to invalid-setting errors, e.g. " Adjust it in Inference Settings." */
        val settingsHint: String = "",
        val promptTooLong: (contextTokens: Int) -> String = {
            "Prompt too long for the context size ($it tokens). Use a larger --context or a shorter request."
        },
        val nativeUnavailable: (detail: String) -> String = { "Llamatik native library could not be loaded: $it" },
        val nativeUnavailableLabel: String = "Native lib unavailable",
        val noModelLabel: String = "No model loaded",
    )

    private val logger = Logger.getLogger(LocalLlamaBackend::class.java.name)
    private val messages get() = options.messages
    private val fixedPath: Path? = fixedModel?.let { validateModelPath(it, messages.noModel) }

    private val executors = mutableMapOf<SessionTag, ExecutorService>()
    private val sharedExecutor: ExecutorService by lazy { newExecutor("llamatik-local") }
    private val lock = Any()

    @Volatile private var loadedModel: String? = null
    @Volatile private var loadedFinetuneType: String? = null
    private var loadedParams: List<Any?>? = null
    private val sessions = mutableMapOf<SessionTag, LlamaSession>()

    /** Captured once: a missing native library is reported on every request instead of crashing. */
    @Volatile private var nativeLoadError: String? = runCatching { Class.forName("com.llamatik.core.platform.LlamaBridge"); null }
        .getOrElse { t -> generateSequence(t) { it.cause }.firstOrNull { it is LinkageError }?.message ?: t.message ?: t.javaClass.simpleName }

    override fun isModelLoaded(): Boolean = nativeLoadError == null && loadedModel != null
    override fun isBaseModel(): Boolean = loadedFinetuneType?.lowercase() == "base"

    override fun selectedModelLabel(): String = when {
        nativeLoadError != null -> messages.nativeUnavailableLabel
        else -> loadedModel?.let(::displayName) ?: fixedPath?.fileName?.toString() ?: messages.noModelLabel
    }

    override fun generate(request: GenerationRequest, callbacks: GenerationCallbacks): GenerationHandle {
        val cancelled = AtomicBoolean(false)
        val activeSession = AtomicReference<LlamaSession?>(null)
        val nativeFinished = AtomicBoolean(false)
        callbacks.onStarted()

        val handle = object : GenerationHandle {
            override fun cancel() {
                cancelled.set(true)
                if (!nativeFinished.get()) activeSession.get()?.cancel()
            }
            override val isCancelled: Boolean get() = cancelled.get()
        }
        nativeLoadError?.let { err ->
            executorFor(request.sessionTag).execute { callbacks.onError(messages.nativeUnavailable(err), null) }
            return handle
        }

        executorFor(request.sessionTag).execute {
            try {
                validateConfig(request.config)
                ensureModelReady(request.config)
                val session = sessionFor(request.sessionTag) ?: error("Could not create inference session for ${request.sessionTag}")
                activeSession.set(session)
                // If cancel() was called before we reached this point, stop now.
                if (cancelled.get()) return@execute

                val (prompt, stopSequences) = renderPrompt(request)
                // Guard against n_tokens_all > n_batch assert in llama_decode. Context Size is the true model
                // context window (batch size is a KV-cache parameter and must not gate prompt length).
                // ~3 chars/token on average; keep a 10% safety margin.
                val maxPromptChars = (request.config.contextLength * 3 * 0.9).toInt()
                if (prompt.length > maxPromptChars) {
                    callbacks.onError(messages.promptTooLong(request.config.contextLength))
                    return@execute
                }

                val acc = StringBuilder()
                var tokenCount = 0
                // True once we have a complete response at the Kotlin level (stop sequence, token budget or natural end).
                val done = AtomicBoolean(false)
                val guard = object : GenStream {
                    override fun onDelta(text: String) {
                        if (done.get() || cancelled.get()) return
                        acc.append(text)
                        tokenCount++
                        when {
                            ChatPromptSupport.shouldStop(acc, stopSequences) -> {
                                done.set(true)
                                callbacks.onCompleted(ChatPromptSupport.trimAtStop(acc.toString(), stopSequences))
                            }
                            tokenCount >= request.config.maxTokens -> {
                                done.set(true)
                                callbacks.onCompleted(acc.toString())
                            }
                            else -> callbacks.onToken(text)
                        }
                    }

                    override fun onComplete() {
                        nativeFinished.set(true)
                        if (done.compareAndSet(false, true) && !cancelled.get()) callbacks.onCompleted(acc.toString())
                    }

                    override fun onError(message: String) {
                        nativeFinished.set(true)
                        if (done.compareAndSet(false, true) && !cancelled.get()) callbacks.onError(message, null)
                    }
                }
                session.stream(prompt, guard)
                nativeFinished.set(true)
            } catch (t: Throwable) {
                nativeFinished.set(true)
                if (!cancelled.get()) {
                    logger.log(Level.WARNING, "Generation failed", t)
                    callbacks.onError(friendlyError(t), t)
                }
            }
        }
        return handle
    }

    private fun renderPrompt(request: GenerationRequest): Pair<String, List<String>> {
        val native = LlamaBridge.applyChatTemplate(ChatPromptSupport.chatPairs(request.systemPrompt, request.messages), addAssistantPrefix = true)
            ?.removePrefix("<s>") // the tokenizer adds BOS; a double BOS corrupts the model
            ?.takeIf { it.isNotBlank() }
        if (native != null) {
            // noThink pre-fills an empty <think> block so Qwen3 skips reasoning; the prefill then continues
            // from the open tag (e.g. mid-tool-call for the write pass).
            val withThink = if (request.noThink) native + "<think>\n\n</think>\n" else native
            val final = if (request.assistantPrefill.isNotBlank()) withThink + request.assistantPrefill else withThink
            // Stop sequences follow the template's format: ChatML stops on a model with another template
            // would halt generation before any tool call is made.
            return final to ChatPromptSupport.stopSequencesFor(native)
        }
        // The GGUF has no embedded template: fall back to the hand-written renderers.
        val rendered = PluginPromptRenderer.renderForModel(
            system = request.systemPrompt, messages = request.messages, noThink = request.noThink,
            modelPath = request.config.modelIdOrPath,
        )
        val text = if (request.assistantPrefill.isNotBlank()) rendered.text + request.assistantPrefill else rendered.text
        return text to rendered.stopSequences
    }

    // ── Model and sessions ───────────────────────────────────────────────────

    private fun sessionFor(tag: SessionTag): LlamaSession? = synchronized(lock) {
        sessions[tag] ?: LlamaBridge.createSession()?.also { sessions[tag] = it }
    }

    /**
     * Single locked gate covering both context-param invalidation and model loading. Holding the lock across
     * both stops a second thread from seeing "no model" mid-flight and loading its own copy while the first
     * load's GPU allocation is still resident (which would double VRAM use and OOM on constrained hardware).
     */
    private fun ensureModelReady(config: BackendConfig) {
        val modelPath = (fixedPath ?: validateModelPath(config.modelIdOrPath, messages.noModel)).toString()
        synchronized(lock) {
            val params = listOf(config.contextLength, config.batchSize, config.numThreads, config.flashAttention, config.gpuLayers)
            val unchanged = loadedModel == modelPath && loadedParams == params
            applySampling(config)
            if (unchanged) return

            // Close existing sessions before changing model/context so the previous GPU allocation is freed first.
            closeSessionsLocked()
            loadedModel = null
            loadedFinetuneType = null
            if (!LlamaBridge.initGenerateModel(modelPath)) error("Failed to initialize Llamatik model: $modelPath")
            loadedModel = modelPath
            loadedParams = params
            loadedFinetuneType = runCatching { LlamaBridge.getModelFinetuneType() }.getOrNull()
            logger.info("Model loaded: $modelPath (finetune=$loadedFinetuneType, n_ctx=${config.contextLength})")
        }
    }

    private fun applySampling(config: BackendConfig) = LlamaBridge.updateGenerateParams(
        temperature = config.temperature.toFloat(),
        maxTokens = config.maxTokens,
        topP = 0.95f,
        topK = 40,
        repeatPenalty = 1.1f,
        contextLength = config.contextLength,
        numThreads = config.numThreads,
        useMmap = config.useMmap,
        flashAttention = config.flashAttention,
        batchSize = config.batchSize,
        gpuLayers = config.gpuLayers,
    )

    private fun closeSessionsLocked() {
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear()
    }

    private fun releaseModelLocked() {
        closeSessionsLocked()
        if (loadedModel != null) runCatching { LlamaBridge.shutdown() }
        loadedModel = null
        loadedFinetuneType = null
        loadedParams = null
    }

    /** Frees the model without waiting; the next request loads it again. */
    fun unload() {
        executorFor(SessionTag.AGENT).execute { synchronized(lock) { releaseModelLocked() } }
    }

    /**
     * Frees the model and waits for it. Must complete before the process exits: tearing the GPU device
     * down while a model is still loaded aborts the JVM (exit code 134 with a native backtrace).
     */
    override fun close() {
        val release = executorFor(SessionTag.AGENT).submit { synchronized(lock) { releaseModelLocked() } }
        allExecutors().forEach { it.shutdown() }
        runCatching { release.get(30, TimeUnit.SECONDS) }
            .onFailure { logger.log(Level.WARNING, "Model did not shut down cleanly", it) }
    }

    // ── Threading ────────────────────────────────────────────────────────────

    private fun executorFor(tag: SessionTag): ExecutorService =
        if (!options.executorPerSession) sharedExecutor
        else synchronized(executors) { executors.getOrPut(tag) { newExecutor("llamatik-${tag.name.lowercase()}") } }

    private fun allExecutors(): Collection<ExecutorService> =
        if (options.executorPerSession) synchronized(executors) { executors.values.toList() } else listOf(sharedExecutor)

    private fun newExecutor(prefix: String): ExecutorService {
        val counter = AtomicInteger(0)
        return Executors.newSingleThreadExecutor(ThreadFactory { runnable ->
            Thread(runnable, "$prefix-${counter.incrementAndGet()}").also { t ->
                t.isDaemon = true
                t.priority = options.threadPriority
            }
        })
    }

    // ── Validation and errors ────────────────────────────────────────────────

    private fun validateConfig(config: BackendConfig) {
        val hint = messages.settingsHint
        require(config.contextLength >= 512) { "Context size must be at least 512 tokens (current: ${config.contextLength}).$hint" }
        require(config.batchSize >= 64) { "Batch size must be at least 64 (current: ${config.batchSize}).$hint" }
        require(config.batchSize <= config.contextLength) { "Batch size (${config.batchSize}) must not exceed context size (${config.contextLength}).$hint" }
        require(config.maxTokens >= 1) { "Max tokens must be at least 1 (current: ${config.maxTokens}).$hint" }
        require(config.numThreads >= 1) { "CPU threads must be at least 1 (current: ${config.numThreads}).$hint" }
    }

    private fun friendlyError(t: Throwable): String {
        val link = generateSequence(t) { it.cause }.firstOrNull { it is UnsatisfiedLinkError }
        if (link != null) {
            val detail = link.message ?: "UnsatisfiedLinkError"
            nativeLoadError = detail
            logger.warning("Native library link error during generation: $detail")
            return messages.nativeUnavailable(detail)
        }
        return t.message ?: "Generation failed"
    }

    private fun displayName(path: String): String = runCatching { Path.of(path).fileName?.toString() ?: path }.getOrDefault(path)

    companion object {
        /**
         * Checks that [rawPath] is an existing `.gguf` file and returns it normalised; throws
         * [IllegalArgumentException] with a readable message otherwise.
         */
        fun validateModelPath(rawPath: String, noModelMessage: String = "No model configured. Pass a local .gguf model file."): Path {
            val text = rawPath.trim()
            require(text.isNotEmpty()) { noModelMessage }
            require(!looksLikeRemoteIdentifier(text)) { "Model must be a local .gguf file path, not a repository identifier: $text" }
            val path = runCatching { Path.of(text) }.getOrElse { throw IllegalArgumentException("Invalid model path: $text", it) }
            require(Files.exists(path)) { "Model file does not exist: $text" }
            require(Files.isRegularFile(path)) { "Model path is not a file: $text" }
            require(text.lowercase().endsWith(".gguf")) { "Model file must end with .gguf: $text" }
            return path.toAbsolutePath().normalize()
        }

        private fun looksLikeRemoteIdentifier(value: String): Boolean =
            !value.startsWith("/") && !value.startsWith(".") && !value.contains("\\") &&
                value.count { it == '/' } == 1 && !value.lowercase().endsWith(".gguf")
    }
}
