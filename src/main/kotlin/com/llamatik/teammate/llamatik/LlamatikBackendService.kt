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

        executor.submit {
            try {
                ensureModel(request.config.modelIdOrPath)

                val prompt = PluginPromptRenderer.render(
                    system = request.systemPrompt,
                    messages = request.messages,
                )
                val stopSequences = PluginPromptRenderer.STOP_SEQUENCES

                val acc = StringBuilder()
                var done = false
                var tokenCount = 0

                val guard = object : GenStream {
                    override fun onDelta(text: String) {
                        if (done || cancelled.get()) return
                        acc.append(text)
                        tokenCount++

                        if (shouldStop(acc, stopSequences)) {
                            done = true
                            val final = trimAtStop(acc.toString(), stopSequences)
                            callbacks.onCompleted(final)
                            return
                        }

                        if (tokenCount >= request.config.maxTokens) {
                            done = true
                            callbacks.onCompleted(acc.toString())
                            return
                        }

                        callbacks.onToken(text)
                    }

                    override fun onComplete() {
                        if (!done && !cancelled.get()) {
                            done = true
                            callbacks.onCompleted(acc.toString())
                        }
                    }

                    override fun onError(message: String) {
                        if (!done && !cancelled.get()) {
                            done = true
                            callbacks.onError(message, null)
                        }
                    }
                }

                com.llamatik.library.platform.LlamaBridge.generateStream(prompt, guard)
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
                com.llamatik.library.platform.LlamaBridge.nativeCancelGenerate()
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
