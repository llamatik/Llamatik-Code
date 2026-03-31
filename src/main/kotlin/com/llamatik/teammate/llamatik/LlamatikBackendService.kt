package com.llamatik.teammate.llamatik

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.llamatik.teammate.backend.AssistantBackend
import com.llamatik.teammate.backend.GenerationCallbacks
import com.llamatik.teammate.backend.GenerationHandle
import com.llamatik.teammate.backend.GenerationRequest
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.APP)
class LlamatikBackendService : AssistantBackend {
    private val logger = thisLogger()
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var initializedModel: String? = null

    /**
     * Captured once on first use. If the native library fails to load (e.g. missing @rpath dylib),
     * we surface this as a clear error rather than crashing silently on every generate() call.
     */
    @Volatile private var nativeLoadError: String? = tryLoadNative()

    override fun isModelLoaded(): Boolean = nativeLoadError == null && initializedModel != null

    override fun selectedModelLabel(): String = when {
        nativeLoadError != null -> "Native lib unavailable"
        else -> initializedModel ?: "No model loaded"
    }

    override fun generate(request: GenerationRequest, callbacks: GenerationCallbacks): GenerationHandle {
        val cancelled = AtomicBoolean(false)
        callbacks.onStarted()

        // Surface native load failures immediately instead of crashing in the background thread.
        val loadErr = nativeLoadError
        if (loadErr != null) {
            executor.submit {
                callbacks.onError(
                    "Llamatik native library could not be loaded: $loadErr\n\n" +
                        "This is likely caused by a missing native dependency (libllama.0.dylib). " +
                        "Ensure the Llamatik runtime is installed and accessible, " +
                        "or set the model path to a supported local model.",
                    null,
                )
            }
            return NoOpHandle(cancelled)
        }

        val future: Future<String> = executor.submit<String> {
            ensureModel(request.config.modelIdOrPath)
            val prompt = buildPrompt(request)
            // TODO: Replace with true streaming callbacks when Llamatik exposes streaming APIs.
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
                // TODO: cancel native generation when Llamatik exposes cooperative cancellation.
            }
            override val isCancelled: Boolean get() = cancelled.get()
        }
    }

    private fun ensureModel(modelPath: String) {
        if (initializedModel == modelPath) return
        val ok = com.llamatik.library.platform.LlamaBridge.initGenerateModel(modelPath)
        if (!ok) error("Failed to initialize Llamatik model: $modelPath")
        initializedModel = modelPath
    }

    private fun buildPrompt(request: GenerationRequest): String {
        val transcript = request.messages.joinToString("\n\n") { msg ->
            val prefix = when (msg.role) {
                com.llamatik.teammate.model.ChatRole.SYSTEM -> "System"
                com.llamatik.teammate.model.ChatRole.USER -> "User"
                com.llamatik.teammate.model.ChatRole.ASSISTANT -> "Assistant"
                com.llamatik.teammate.model.ChatRole.TOOL -> "Tool:${msg.toolName ?: "unknown"}"
            }
            "$prefix:\n${msg.content}"
        }
        return buildString {
            appendLine(request.systemPrompt)
            appendLine()
            appendLine("Configuration:")
            appendLine("- temperature=${request.config.temperature}")
            appendLine("- maxTokens=${request.config.maxTokens}")
            appendLine("- contextSize=${request.config.contextSize}")
            appendLine("- localOnly=${request.config.localOnly}")
            appendLine()
            append(transcript)
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
            // Cache it so subsequent calls skip the executor overhead
            nativeLoadError = cause.message ?: "UnsatisfiedLinkError"
            return "Llamatik native library could not be loaded.\n${cause.message}\n\n" +
                "Ensure libllama.0.dylib is on the library path or install the Llamatik runtime."
        }
        return t.message ?: "Generation failed"
    }

    private class NoOpHandle(private val cancelled: AtomicBoolean) : GenerationHandle {
        override fun cancel() { cancelled.set(true) }
        override val isCancelled: Boolean get() = cancelled.get()
    }
}

/** Returns a human-readable error string if the native lib can't be loaded, null if it's fine. */
private fun tryLoadNative(): String? = runCatching {
    // Trigger class initialisation of LlamaBridge — its <clinit> loads the dylib.
    Class.forName("com.llamatik.library.platform.LlamaBridge")
    null
}.getOrElse { t ->
    val link = generateSequence(t) { it.cause }.firstOrNull { it is UnsatisfiedLinkError || it is LinkageError }
    link?.message ?: t.message ?: t.javaClass.simpleName
}
