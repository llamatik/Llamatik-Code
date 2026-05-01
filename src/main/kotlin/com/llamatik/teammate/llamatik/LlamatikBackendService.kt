package com.llamatik.teammate.llamatik

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.llamatik.library.platform.GenStream
import com.llamatik.library.platform.LlamaSession
import com.llamatik.teammate.backend.AssistantBackend
import com.llamatik.teammate.backend.GenerationCallbacks
import com.llamatik.teammate.backend.GenerationHandle
import com.llamatik.teammate.backend.GenerationRequest
import com.llamatik.teammate.backend.SessionTag
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean


@Service(Service.Level.APP)
class LlamatikBackendService : AssistantBackend {
    private val logger = thisLogger()

    // Two independent executors — one per session tag.
    // - updateGenerateParams / initGenerateModel: serialised by modelLock, safe to call from either thread.
    // - applyChatTemplate: pure string formatting, no global state, concurrent-safe.
    // - session.stream(): uses ss->ctx exclusively, concurrent-safe.
    private val agentExecutor = Executors.newSingleThreadExecutor()
    private val chatExecutor  = Executors.newSingleThreadExecutor()

    private val modelLock = Any()

    @Volatile private var initializedModel: String? = null
    @Volatile private var loadedFinetuneType: String? = null
    @Volatile private var lastContextLength: Int = -1
    @Volatile private var lastBatchSize: Int = -1
    @Volatile private var lastNumThreads: Int = -1
    @Volatile private var lastFlashAttention: Boolean = false

    // One session per surface — each owns an isolated KV cache.
    @Volatile private var agentSession: LlamaSession? = null
    @Volatile private var chatSession: LlamaSession? = null

    /**
     * Captured once on first use. If the native library fails to load (e.g. missing @rpath dylib),
     * we surface this as a clear error rather than crashing silently on every generate() call.
     */
    @Volatile private var nativeLoadError: String? = tryLoadNative()

    override fun isModelLoaded(): Boolean = nativeLoadError == null && initializedModel != null

    override fun isBaseModel(): Boolean {
        val finetune = loadedFinetuneType ?: return false
        return finetune.lowercase() == "base"
    }

    fun unload() {
        agentExecutor.submit {
            synchronized(modelLock) {
                closeSessionsLocked()
                if (initializedModel != null) {
                    runCatching { com.llamatik.library.platform.LlamaBridge.shutdown() }
                    initializedModel = null
                    loadedFinetuneType = null
                }
            }
        }
    }

    override fun selectedModelLabel(): String = when {
        nativeLoadError != null -> "Native lib unavailable"
        else -> initializedModel?.let(::displayModelLabel) ?: "No model loaded"
    }

    override fun generate(request: GenerationRequest, callbacks: GenerationCallbacks): GenerationHandle {
        val cancelled = AtomicBoolean(false)
        callbacks.onStarted()

        val loadErr = nativeLoadError
        if (loadErr != null) {
            executorFor(request.sessionTag).submit {
                callbacks.onError(
                    "Llamatik native library could not be loaded: $loadErr\n\n" +
                        "Ensure the bundled native libraries are present and that macOS can load their dependencies.",
                    null,
                )
            }
            return NoOpHandle(cancelled)
        }

        // True once we have a complete response at the Kotlin level (stop sequence, token
        // budget, or natural completion).  Used to prevent double-firing onCompleted.
        val generationDone = AtomicBoolean(false)
        // True once session.stream() has returned (naturally or via cancel).
        // Prevents calling session.cancel() on a session that is no longer running.
        val nativeFinished = AtomicBoolean(false)
        // Set on the executor thread as soon as the session is resolved, so cancel()
        // can reach it even if the user stops before stream() has started.
        val activeSession = java.util.concurrent.atomic.AtomicReference<LlamaSession?>(null)

        executorFor(request.sessionTag).submit {
            try {
                validateConfig(request.config)
                invalidateIfContextParamsChanged(request.config)
                com.llamatik.library.platform.LlamaBridge.updateGenerateParams(
                    temperature = request.config.temperature.toFloat(),
                    maxTokens = request.config.maxTokens,
                    topP = 0.95f,
                    topK = 40,
                    repeatPenalty = 1.1f,
                    contextLength = request.config.contextLength,
                    numThreads = request.config.numThreads,
                    useMmap = request.config.useMmap,
                    flashAttention = request.config.flashAttention,
                    batchSize = request.config.batchSize,
                )
                ensureModel(request.config.modelIdOrPath)

                val session = getOrCreateSession(request.sessionTag)
                    ?: error("Could not create inference session for ${request.sessionTag}")
                activeSession.set(session)
                // If cancel() was called before we reached this point, stop now.
                if (cancelled.get()) return@submit

                // Build message list for the native chat template.
                // System prompt is prepended as a "system" turn if non-blank.
                val chatMessages = buildList {
                    if (request.systemPrompt.isNotBlank()) {
                        add("system" to request.systemPrompt)
                    }
                    request.messages.forEach { msg ->
                        val role = when (msg.role) {
                            com.llamatik.teammate.model.ChatRole.SYSTEM -> "system"
                            com.llamatik.teammate.model.ChatRole.USER -> "user"
                            com.llamatik.teammate.model.ChatRole.ASSISTANT -> "assistant"
                            com.llamatik.teammate.model.ChatRole.TOOL -> "user"
                        }
                        add(role to msg.content)
                    }
                }

                // Use the model's own embedded chat template via the native API.
                // Falls back to PluginPromptRenderer if the template is unavailable
                // (e.g. GGUF has no embedded template).
                val nativePrompt = com.llamatik.library.platform.LlamaBridge
                    .applyChatTemplate(chatMessages, addAssistantPrefix = true)
                    ?.removePrefix("<s>")   // strip BOS — tokenizer adds it; double BOS corrupts model
                    ?.takeIf { it.isNotBlank() }

                val (prompt, stopSequences) = if (nativePrompt != null) {
                    // When noThink is requested, pre-fill an empty <think> block on the native
                    // assistant prefix so Qwen3 skips its reasoning phase entirely.
                    val finalPrompt = if (request.noThink) nativePrompt + "<think>\n\n</think>\n" else nativePrompt
                    // Infer stop sequences from the format of the native prompt so we only enforce
                    // turn-boundary markers that this model actually uses. Applying ChatML stops to
                    // a model that uses a different template (e.g. Mellum's <user>/<assistant> format)
                    // would halt generation at the first response turn before any tool calls are made.
                    val stops = when {
                        nativePrompt.contains("<|im_end|>") -> listOf("<|im_end|>", "<|endoftext|>")
                        nativePrompt.contains("<end_of_turn>") -> listOf("<end_of_turn>", "<eos>")
                        nativePrompt.contains("<|end|>") -> listOf("<|end|>", "<|endoftext|>")
                        else -> emptyList() // Unknown/base model format — rely on native EOS only
                    }
                    finalPrompt to stops
                } else {
                    val rendered = PluginPromptRenderer.renderForModel(
                        system = request.systemPrompt,
                        messages = request.messages,
                        noThink = request.noThink,
                        modelPath = request.config.modelIdOrPath,
                    )
                    rendered.text to rendered.stopSequences
                }

                // Guard against n_tokens_all > n_batch assert in llama_decode.
                // ~3 chars/token on average; keep a safety margin below batchSize.
                val maxPromptChars = request.config.batchSize * 3
                if (prompt.length > maxPromptChars) {
                    callbacks.onError(
                        "Prompt too long for current Batch Size (${request.config.batchSize} tokens). " +
                        "Reduce context or increase Batch Size in Inference Settings.",
                    )
                    return@submit
                }

                val acc = StringBuilder()
                var tokenCount = 0

                val guard = object : GenStream {
                    override fun onDelta(text: String) {
                        if (generationDone.get() || cancelled.get()) return
                        acc.append(text)
                        tokenCount++

                        if (shouldStop(acc, stopSequences)) {
                            generationDone.set(true)
                            val final = trimAtStop(acc.toString(), stopSequences)
                            callbacks.onCompleted(final)
                            return
                        }

                        if (tokenCount >= request.config.maxTokens) {
                            generationDone.set(true)
                            callbacks.onCompleted(acc.toString())
                            return
                        }

                        callbacks.onToken(text)
                    }

                    override fun onComplete() {
                        nativeFinished.set(true)
                        if (generationDone.compareAndSet(false, true) && !cancelled.get()) {
                            callbacks.onCompleted(acc.toString())
                        }
                    }

                    override fun onError(message: String) {
                        nativeFinished.set(true)
                        if (generationDone.compareAndSet(false, true) && !cancelled.get()) {
                            callbacks.onError(message, null)
                        }
                    }
                }

                session.stream(prompt, guard)
                nativeFinished.set(true)
            } catch (t: Throwable) {
                nativeFinished.set(true)
                if (!cancelled.get()) {
                    logger.warn("Generation failed", t)
                    callbacks.onError(friendlyError(t), t)
                }
            }
        }

        return object : GenerationHandle {
            override fun cancel() {
                cancelled.set(true)
                if (!nativeFinished.get()) {
                    activeSession.get()?.cancel()
                }
            }

            override val isCancelled: Boolean get() = cancelled.get()
        }
    }

    // ── Session management ────────────────────────────────────────────────────

    private fun executorFor(tag: SessionTag) = when (tag) {
        SessionTag.AGENT -> agentExecutor
        SessionTag.CHAT  -> chatExecutor
    }

    private fun sessionFor(tag: SessionTag): LlamaSession? = when (tag) {
        SessionTag.AGENT -> agentSession
        SessionTag.CHAT  -> chatSession
    }

    /**
     * Returns (or lazily creates) the session for [tag].
     * Must be called from within the tag's own executor thread, after ensureModel().
     */
    private fun getOrCreateSession(tag: SessionTag): LlamaSession? {
        val existing = sessionFor(tag)
        if (existing != null) return existing

        val session = com.llamatik.library.platform.LlamaBridge.createSession()
        if (session != null) {
            when (tag) {
                SessionTag.AGENT -> agentSession = session
                SessionTag.CHAT  -> chatSession  = session
            }
            logger.info("Created LlamaSession for $tag")
        } else {
            logger.warn("createSession() returned null for $tag — model may not be loaded")
        }
        return session
    }

    /** Closes both sessions. Must be called while holding modelLock. */
    private fun closeSessionsLocked() {
        agentSession?.runCatching { close() }
        chatSession?.runCatching  { close() }
        agentSession = null
        chatSession  = null
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun shouldStop(sb: StringBuilder, stops: List<String>): Boolean {
        if (stops.isEmpty()) return false
        val tail = sb.takeLast(minOf(64, sb.length))
        return stops.any { tail.endsWith(it) }
    }

    private fun trimAtStop(text: String, stops: List<String>): String {
        var out = text
        for (s in stops) {
            val idx = out.lastIndexOf(s)
            if (idx >= 0) { out = out.substring(0, idx); break }
        }
        return out
    }

    private fun validateConfig(config: com.llamatik.teammate.backend.BackendConfig) {
        require(config.contextLength >= 512) {
            "Context size must be at least 512 tokens (current: ${config.contextLength}). Adjust it in Inference Settings."
        }
        require(config.batchSize >= 64) {
            "Batch size must be at least 64 (current: ${config.batchSize}). Adjust it in Inference Settings."
        }
        require(config.batchSize <= config.contextLength) {
            "Batch size (${config.batchSize}) must not exceed context size (${config.contextLength}). Adjust in Inference Settings."
        }
        require(config.maxTokens >= 1) {
            "Max tokens must be at least 1 (current: ${config.maxTokens}). Adjust it in Inference Settings."
        }
        require(config.numThreads >= 1) {
            "CPU threads must be at least 1 (current: ${config.numThreads}). Adjust it in Inference Settings."
        }
    }

    private fun invalidateIfContextParamsChanged(config: com.llamatik.teammate.backend.BackendConfig) {
        if (config.contextLength != lastContextLength ||
            config.batchSize != lastBatchSize ||
            config.numThreads != lastNumThreads ||
            config.flashAttention != lastFlashAttention
        ) {
            synchronized(modelLock) {
                // Close sessions so they are recreated with the new context params.
                closeSessionsLocked()
                initializedModel = null
                loadedFinetuneType = null
                lastContextLength = config.contextLength
                lastBatchSize = config.batchSize
                lastNumThreads = config.numThreads
                lastFlashAttention = config.flashAttention
            }
        }
    }

    private fun ensureModel(modelPath: String) {
        val normalizedModelPath = validateModelPath(modelPath)
        if (initializedModel == normalizedModelPath) return

        synchronized(modelLock) {
            if (initializedModel == normalizedModelPath) return
            // Model changed — close existing sessions so new ones pick up the new model weights.
            closeSessionsLocked()
            val ok = com.llamatik.library.platform.LlamaBridge.initGenerateModel(normalizedModelPath)
            if (!ok) error("Failed to initialize Llamatik model: $normalizedModelPath")
            initializedModel = normalizedModelPath
            loadedFinetuneType = runCatching {
                com.llamatik.library.platform.LlamaBridge.getModelFinetuneType()
            }.getOrNull()
            logger.info("Model loaded: $normalizedModelPath (finetune=$loadedFinetuneType)")
        }
    }

    private fun validateModelPath(rawModelPath: String): String {
        val modelPath = rawModelPath.trim()
        require(modelPath.isNotEmpty()) {
            "No model configured. Select a local .gguf model file in Settings > Tools > Llamatik Code."
        }

        require(!looksLikeRemoteIdentifier(modelPath)) {
            "Model must be a local .gguf file path, not a repository identifier: $modelPath"
        }

        val path = runCatching { Path.of(modelPath) }.getOrElse {
            throw IllegalArgumentException("Invalid model path: $modelPath", it)
        }

        require(Files.exists(path)) { "Model file does not exist: $modelPath" }
        require(Files.isRegularFile(path)) { "Model path is not a file: $modelPath" }
        require(modelPath.lowercase().endsWith(".gguf")) { "Model file must end with .gguf: $modelPath" }

        return path.toAbsolutePath().normalize().toString()
    }

    private fun friendlyError(t: Throwable): String {
        val cause = generateSequence(t) { it.cause }.firstOrNull { it is UnsatisfiedLinkError }
        if (cause != null) {
            nativeLoadError = cause.message ?: "UnsatisfiedLinkError"
            return "Llamatik native library could not be loaded.\n${cause.message}\n\n" +
                "Ensure the bundled native libraries are available for your current platform."
        }
        return t.message ?: "Generation failed"
    }

    private fun looksLikeRemoteIdentifier(value: String): Boolean {
        return !value.startsWith("/") &&
            !value.startsWith(".") &&
            !value.contains("\\") &&
            value.count { it == '/' } == 1 &&
            !value.lowercase().endsWith(".gguf")
    }

    private fun displayModelLabel(path: String): String = runCatching {
        Path.of(path).fileName?.toString() ?: path
    }.getOrDefault(path)

    private class NoOpHandle(private val cancelled: AtomicBoolean) : GenerationHandle {
        override fun cancel() {
            cancelled.set(true)
        }

        override val isCancelled: Boolean get() = cancelled.get()
    }
}

/** Returns a human-readable error string if the native lib can't be loaded, null if it's fine. */
private fun tryLoadNative(): String? = runCatching {
    Class.forName("com.llamatik.library.platform.LlamaBridge")
    null
}.getOrElse { t ->
    val link = generateSequence(t) { it.cause }.firstOrNull { it is UnsatisfiedLinkError || it is LinkageError }
    link?.message ?: t.message ?: t.javaClass.simpleName
}
