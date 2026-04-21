package com.llamatik.teammate.llamatik

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.llamatik.library.platform.GenStream
import com.llamatik.library.platform.MultimodalBridge
import com.llamatik.teammate.backend.GenerationCallbacks
import com.llamatik.teammate.backend.GenerationHandle
import com.llamatik.teammate.model.AttachedImage
import com.llamatik.teammate.settings.LlamatikAppSettings
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.APP)
class MultimodalBackendService {

    private val logger = thisLogger()
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var loadedModelPath: String? = null
    @Volatile private var loadedMmprojPath: String? = null
    @Volatile private var nativeLoadError: String? = tryLoadNative()

    fun isAvailable(): Boolean = nativeLoadError == null

    /**
     * Returns true if a multimodal model + mmproj are configured in settings.
     */
    fun isConfigured(): Boolean {
        val settings = service<LlamatikAppSettings>().data()
        return settings.modelPath.isNotBlank() && settings.mmprojPath.isNotBlank()
    }

    fun generateWithImages(
        systemPrompt: String,
        userPrompt: String,
        images: List<AttachedImage>,
        callbacks: GenerationCallbacks,
    ): GenerationHandle {
        val cancelled = AtomicBoolean(false)
        callbacks.onStarted()

        val nativeErr = nativeLoadError
        if (nativeErr != null) {
            executor.submit { callbacks.onError("Multimodal native library unavailable: $nativeErr", null) }
            return NoOpHandle(cancelled)
        }

        val settings = service<LlamatikAppSettings>().data()
        val modelPath = settings.modelPath.trim()
        val mmprojPath = settings.mmprojPath.trim()

        if (modelPath.isBlank() || mmprojPath.isBlank()) {
            executor.submit { callbacks.onError("Configure a multimodal model and mmproj path in Settings → Llamatik Code.", null) }
            return NoOpHandle(cancelled)
        }

        val generationDone = AtomicBoolean(false)
        val nativeFinished = AtomicBoolean(false)

        executor.submit {
            try {
                ensureModel(modelPath, mmprojPath)

                if (images.isEmpty()) {
                    executor.submit { callbacks.onError("No image attached for multimodal generation.", null) }
                    return@submit
                }

                // Use the first attached image (multi-image support requires model capability)
                val image = images.first()
                val normalizedBytes = AttachedImage.normalizeBytes(image.bytes, image.mimeType)

                val fullPrompt = buildString {
                    if (systemPrompt.isNotBlank()) {
                        appendLine(systemPrompt)
                        appendLine()
                    }
                    append(userPrompt)
                }

                val acc = StringBuilder()

                val guard = object : GenStream {
                    override fun onDelta(text: String) {
                        if (generationDone.get() || cancelled.get()) return
                        acc.append(text)
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

                MultimodalBridge.analyzeImageBytesStream(normalizedBytes, fullPrompt, guard)
                nativeFinished.set(true)

            } catch (t: Throwable) {
                nativeFinished.set(true)
                if (!cancelled.get()) {
                    logger.warn("Multimodal generation failed", t)
                    callbacks.onError(t.message ?: "Multimodal generation failed", t)
                }
            }
        }

        return object : GenerationHandle {
            override fun cancel() {
                cancelled.set(true)
                if (!nativeFinished.get()) {
                    MultimodalBridge.cancelAnalysis()
                }
            }
            override val isCancelled: Boolean get() = cancelled.get()
        }
    }

    private fun ensureModel(modelPath: String, mmprojPath: String) {
        if (loadedModelPath == modelPath && loadedMmprojPath == mmprojPath) return
        synchronized(this) {
            if (loadedModelPath == modelPath && loadedMmprojPath == mmprojPath) return
            val ok = MultimodalBridge.initModel(modelPath, mmprojPath)
            if (!ok) error("Failed to initialize multimodal model: $modelPath + $mmprojPath")
            loadedModelPath = modelPath
            loadedMmprojPath = mmprojPath
        }
    }

    private class NoOpHandle(private val cancelled: AtomicBoolean) : GenerationHandle {
        override fun cancel() { cancelled.set(true) }
        override val isCancelled: Boolean get() = cancelled.get()
    }
}

private fun tryLoadNative(): String? = runCatching {
    Class.forName("com.llamatik.library.platform.MultimodalBridge")
    null
}.getOrElse { t ->
    val link = generateSequence(t) { it.cause }.firstOrNull { it is UnsatisfiedLinkError || it is LinkageError }
    link?.message ?: t.message ?: t.javaClass.simpleName
}
