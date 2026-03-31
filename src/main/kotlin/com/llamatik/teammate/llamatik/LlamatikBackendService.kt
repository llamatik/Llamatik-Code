package com.llamatik.teammate.llamatik

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.llamatik.teammate.backend.AssistantBackend
import com.llamatik.teammate.backend.GenerationCallbacks
import com.llamatik.teammate.backend.GenerationHandle
import com.llamatik.teammate.backend.GenerationRequest
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.APP)
class LlamatikBackendService : AssistantBackend {
    private val logger = thisLogger()
    private val executor = Executors.newSingleThreadExecutor()
    private val modelLock = Any()

    @Volatile private var initializedModel: String? = null

    /**
     * Captured once on first use. If the native library fails to load (e.g. missing @rpath dylib),
     * we surface this as a clear error rather than crashing silently on every generate() call.
     */
    @Volatile private var nativeLoadError: String? = tryLoadNative()

    override fun isModelLoaded(): Boolean = nativeLoadError == null && initializedModel != null

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

        val future: Future<String> = executor.submit<String> {
            ensureModel(request.config.modelIdOrPath)
            val prompt = buildPrompt(request)
            com.llamatik.library.platform.LlamaBridge.generate(prompt)
        }

        executor.submit {
            try {
                val response = future.get()
                if (cancelled.get()) return@submit
                streamFallback(response, callbacks, cancelled)
                if (!cancelled.get()) callbacks.onCompleted(response)
            } catch (t: Throwable) {
                if (!cancelled.get()) {
                    logger.warn("Generation failed", t)
                    callbacks.onError(friendlyError(t), t)
                }
            }
        }

        return object : GenerationHandle {
            override fun cancel() {
                cancelled.set(true)
                future.cancel(true)
            }

            override val isCancelled: Boolean get() = cancelled.get()
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

    private fun buildPrompt(request: GenerationRequest): String {
        return buildString {
            // System prompt
            if (request.systemPrompt.isNotBlank()) {
                append("<|im_start|>system\n")
                append(request.systemPrompt.trim())
                append("<|im_end|>\n")
            }
            // Conversation turns
            for (msg in request.messages) {
                val role = when (msg.role) {
                    com.llamatik.teammate.model.ChatRole.SYSTEM -> "system"
                    com.llamatik.teammate.model.ChatRole.USER -> "user"
                    com.llamatik.teammate.model.ChatRole.ASSISTANT -> "assistant"
                    com.llamatik.teammate.model.ChatRole.TOOL -> "tool"
                }
                append("<|im_start|>$role\n")
                append(msg.content.trim())
                append("<|im_end|>\n")
            }
            // Prompt the model to reply
            append("<|im_start|>assistant\n")
        }
    }

    private fun streamFallback(text: String, callbacks: GenerationCallbacks, cancelled: AtomicBoolean) {
        var index = 0
        while (index < text.length && !cancelled.get()) {
            val next = minOf(text.length, index + 24)
            callbacks.onToken(text.substring(index, next))
            index = next
            Thread.sleep(10)
        }
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
