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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Runs a GGUF model in-process through the Llamatik native library (llama.cpp). The model is loaded on
 * the first request and reloaded when a context-affecting setting changes.
 *
 * Headless counterpart of the IDE plugin's LlamatikBackendService: same prompt rendering, stop-sequence
 * and token-budget handling, without the IDE services (settings, thread priorities).
 */
class LocalLlamaBackend(modelPath: String) : AssistantBackend, AutoCloseable {
    private val logger = Logger.getLogger(LocalLlamaBackend::class.java.name)
    private val modelFile: Path = validateModelPath(modelPath)

    // One thread: the native model is a single shared resource.
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "llamatik-local").apply { isDaemon = true } }
    private val lock = Any()

    @Volatile private var loadedModel: String? = null
    @Volatile private var loadedFinetuneType: String? = null
    private var loadedParams: List<Any?>? = null
    private val sessions = mutableMapOf<SessionTag, LlamaSession>()

    /** Captured once: a missing native library is reported on every request instead of crashing. */
    private val nativeLoadError: String? = runCatching { Class.forName("com.llamatik.core.platform.LlamaBridge"); null }
        .getOrElse { t -> generateSequence(t) { it.cause }.firstOrNull { it is LinkageError }?.message ?: t.message ?: t.javaClass.simpleName }

    override fun isModelLoaded(): Boolean = nativeLoadError == null && loadedModel != null
    override fun isBaseModel(): Boolean = loadedFinetuneType?.lowercase() == "base"
    override fun selectedModelLabel(): String = modelFile.fileName.toString()

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
        if (nativeLoadError != null) {
            executor.execute { callbacks.onError("Llamatik native library could not be loaded: $nativeLoadError") }
            return handle
        }

        executor.execute {
            try {
                validateConfig(request.config)
                ensureModelReady(request.config)
                val session = sessionFor(request.sessionTag) ?: error("Could not create an inference session")
                activeSession.set(session)
                if (cancelled.get()) return@execute

                val (prompt, stopSequences) = renderPrompt(request)
                val maxPromptChars = (request.config.contextLength * 3 * 0.9).toInt()
                if (prompt.length > maxPromptChars) {
                    callbacks.onError(
                        "Prompt too long for the context size (${request.config.contextLength} tokens). " +
                            "Use a larger --context or a shorter request.",
                    )
                    return@execute
                }

                val acc = StringBuilder()
                var tokenCount = 0
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
                    logger.log(Level.WARNING, "Local generation failed", t)
                    callbacks.onError(t.message ?: "Generation failed", t)
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
            val withThink = if (request.noThink) native + "<think>\n\n</think>\n" else native
            val final = if (request.assistantPrefill.isNotBlank()) withThink + request.assistantPrefill else withThink
            return final to ChatPromptSupport.stopSequencesFor(native)
        }
        val rendered = PluginPromptRenderer.renderForModel(
            system = request.systemPrompt, messages = request.messages, noThink = request.noThink,
            modelPath = modelFile.toString(),
        )
        val text = if (request.assistantPrefill.isNotBlank()) rendered.text + request.assistantPrefill else rendered.text
        return text to rendered.stopSequences
    }

    private fun sessionFor(tag: SessionTag): LlamaSession? = synchronized(lock) {
        sessions[tag] ?: LlamaBridge.createSession()?.also { sessions[tag] = it }
    }

    private fun ensureModelReady(config: BackendConfig) = synchronized(lock) {
        val params = listOf(config.contextLength, config.batchSize, config.numThreads, config.flashAttention, config.gpuLayers, config.useMmap)
        val modelPathText = modelFile.toString()
        val unchanged = loadedModel == modelPathText && loadedParams == params
        applySampling(config)
        if (unchanged) return@synchronized

        // Close sessions before changing model/context so the previous allocation is freed first.
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear()
        loadedModel = null
        if (!LlamaBridge.initGenerateModel(modelPathText)) error("Failed to load model: $modelPathText")
        loadedModel = modelPathText
        loadedParams = params
        loadedFinetuneType = runCatching { LlamaBridge.getModelFinetuneType() }.getOrNull()
        logger.info("Model loaded: $modelPathText (finetune=$loadedFinetuneType, n_ctx=${config.contextLength})")
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

    /**
     * Frees the model and waits for it. Must complete before the process exits: tearing the GPU device
     * down while a model is still loaded aborts the JVM (exit code 134 with a native backtrace).
     */
    override fun close() {
        val release = executor.submit {
            synchronized(lock) {
                sessions.values.forEach { runCatching { it.close() } }
                sessions.clear()
                if (loadedModel != null) runCatching { LlamaBridge.shutdown() }
                loadedModel = null
            }
        }
        executor.shutdown()
        runCatching { release.get(30, TimeUnit.SECONDS) }
            .onFailure { logger.log(Level.WARNING, "Model did not shut down cleanly", it) }
    }

    private fun validateConfig(config: BackendConfig) {
        require(config.contextLength >= 512) { "Context size must be at least 512 tokens (current: ${config.contextLength})." }
        require(config.batchSize >= 64) { "Batch size must be at least 64 (current: ${config.batchSize})." }
        require(config.batchSize <= config.contextLength) { "Batch size (${config.batchSize}) must not exceed context size (${config.contextLength})." }
        require(config.maxTokens >= 1) { "Max tokens must be at least 1 (current: ${config.maxTokens})." }
        require(config.numThreads >= 1) { "CPU threads must be at least 1 (current: ${config.numThreads})." }
    }

    companion object {
        /** Checks that [rawPath] is an existing `.gguf` file; throws [IllegalArgumentException] with a readable message. */
        fun validateModelPath(rawPath: String): Path {
            val text = rawPath.trim()
            require(text.isNotEmpty()) { "No model configured. Pass a local .gguf model file." }
            require(text.lowercase().endsWith(".gguf")) { "Model file must end with .gguf: $text" }
            val path = runCatching { Path.of(text) }.getOrElse { throw IllegalArgumentException("Invalid model path: $text", it) }
            require(Files.exists(path)) { "Model file does not exist: $text" }
            require(Files.isRegularFile(path)) { "Model path is not a file: $text" }
            return path.toAbsolutePath().normalize()
        }
    }
}
