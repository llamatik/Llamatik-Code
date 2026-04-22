package com.llamatik.teammate.llamatik

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.llamatik.library.platform.GenStream
import com.llamatik.teammate.backend.AssistantBackend
import com.llamatik.teammate.backend.GenerationCallbacks
import com.llamatik.teammate.backend.GenerationHandle
import com.llamatik.teammate.backend.GenerationRequest
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean


@Service(Service.Level.APP)
class LlamatikBackendService : AssistantBackend {
    private val logger = thisLogger()
    private val executor = Executors.newSingleThreadExecutor()
    private val modelLock = Any()

    @Volatile private var initializedModel: String? = null
    @Volatile private var lastContextLength: Int = -1
    @Volatile private var lastBatchSize: Int = -1
    @Volatile private var lastNumThreads: Int = -1
    @Volatile private var lastFlashAttention: Boolean = false

    /**
     * Captured once on first use. If the native library fails to load (e.g. missing @rpath dylib),
     * we surface this as a clear error rather than crashing silently on every generate() call.
     */
    @Volatile private var nativeLoadError: String? = tryLoadNative()

    override fun isModelLoaded(): Boolean = nativeLoadError == null && initializedModel != null

    fun unload() {
        executor.submit {
            synchronized(modelLock) {
                if (initializedModel != null) {
                    runCatching { com.llamatik.library.platform.LlamaBridge.shutdown() }
                    initializedModel = null
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
            executor.submit {
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
        // True once the *native* generateStream has actually returned (naturally or via cancel).
        // The cancel handle checks this: calling nativeCancelGenerate() after the native side
        // is already finished would leave a stale cancel flag that kills the next generation.
        val nativeFinished = AtomicBoolean(false)

        executor.submit {
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

                val rendered = PluginPromptRenderer.renderForModel(
                    system = request.systemPrompt,
                    messages = request.messages,
                    noThink = request.noThink,
                    modelPath = request.config.modelIdOrPath,
                )
                val prompt = rendered.text
                val stopSequences = rendered.stopSequences

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
                            // Do NOT call nativeCancelGenerate() here. Qwen3 natively fires
                            // <|im_end|> as EOS so the native side stops within 1-2 tokens.
                            // Calling nativeCancelGenerate() leaves a stale cancel flag that
                            // causes generateStream to recreate its context with a smaller
                            // n_ctx (4096) on the next call, starving multi-pass generations.
                            return
                        }

                        if (tokenCount >= request.config.maxTokens) {
                            generationDone.set(true)
                            callbacks.onCompleted(acc.toString())
                            // Same reasoning: avoid cancel to preserve native context size.
                            return
                        }

                        callbacks.onToken(text)
                    }

                    override fun onComplete() {
                        // Native finished on its own — mark it so cancel() won't leave a
                        // stale flag, then fire onCompleted if we haven't already.
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

                com.llamatik.library.platform.LlamaBridge.generateStream(prompt, guard)
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
                // Only signal native cancel while the native side is still running.
                // If it already finished, the flag would persist and kill the next generation.
                if (!nativeFinished.get()) {
                    com.llamatik.library.platform.LlamaBridge.nativeCancelGenerate()
                }
            }

            override val isCancelled: Boolean get() = cancelled.get()
        }
    }

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
                initializedModel = null
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
            val ok = com.llamatik.library.platform.LlamaBridge.initGenerateModel(normalizedModelPath)
            if (!ok) error("Failed to initialize Llamatik model: $normalizedModelPath")
            initializedModel = normalizedModelPath
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
