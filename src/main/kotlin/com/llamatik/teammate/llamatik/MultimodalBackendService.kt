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
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.APP)
class MultimodalBackendService {

    private val logger = thisLogger()
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var loadedModelPath: String? = null
    @Volatile private var loadedMmprojPath: String? = null
    @Volatile private var nativeLoadError: String? = tryLoadNative()

    fun isAvailable(): Boolean = nativeLoadError == null

    fun unload() {
        executor.submit {
            synchronized(this) {
                if (loadedModelPath != null) {
                    runCatching { MultimodalBridge.release() }
                    loadedModelPath = null
                    loadedMmprojPath = null
                }
            }
        }
    }

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

        val vramErr = checkVramHeadroom(modelPath)
        if (vramErr != null) {
            executor.submit { callbacks.onError(vramErr, null) }
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
                val resizedBytes = resizeToMaxDimension(image.bytes, maxDim = 1120)
                val normalizedBytes = AttachedImage.normalizeBytes(resizedBytes, image.mimeType)

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
                            callbacks.onError(friendlyMultimodalError(message), null)
                        }
                    }
                }

                MultimodalBridge.analyzeImageBytesStream(normalizedBytes, fullPrompt, guard)
                nativeFinished.set(true)

            } catch (t: Throwable) {
                nativeFinished.set(true)
                if (!cancelled.get()) {
                    logger.warn("Multimodal generation failed", t)
                    callbacks.onError(friendlyMultimodalError(t.message ?: "Multimodal generation failed"), t)
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

    /**
     * Returns an error string if the model file is too large for the available system RAM,
     * leaving insufficient headroom for the VLM KV cache (~1.8 GB at n_ctx=8192).
     * On Apple Silicon the GPU uses unified memory, so model size + KV cache competes with RAM.
     */
    private fun checkVramHeadroom(modelPath: String): String? {
        val modelFileSizeGb = runCatching { File(modelPath).length().toDouble() / (1024.0 * 1024 * 1024) }.getOrDefault(0.0)
        if (modelFileSizeGb <= 0.0) return null
        val systemRamGb = runCatching {
            val bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
            if (bean is com.sun.management.OperatingSystemMXBean) bean.totalMemorySize.toDouble() / (1024.0 * 1024 * 1024) else 0.0
        }.getOrDefault(0.0)
        if (systemRamGb <= 0.0) return null
        // VLM decode at n_ctx=8192 needs ~1.8 GB KV cache + ~0.6 GB compute buffers
        val headroomNeededGb = 2.5
        return if (modelFileSizeGb + headroomNeededGb > systemRamGb) {
            "Not enough memory for multimodal generation with this model.\n" +
                "Model size: ${"%.1f".format(modelFileSizeGb)} GB, System RAM: ${"%.1f".format(systemRamGb)} GB.\n" +
                "Use a smaller model (e.g. Gemma 4 E4B or Gemma 4 26B Q4) for image analysis on this machine."
        } else null
    }

    private fun friendlyMultimodalError(raw: String): String {
        val lower = raw.lowercase()
        return when {
            lower.contains("outofmemory") || lower.contains("out of memory") ||
                lower.contains("insufficient memory") || lower.contains("kIOGPUCommandBufferCallbackErrorOutOfMemory", ignoreCase = true) ||
                lower.contains("mtmd_helper_eval_chunks failed") || lower.contains("failed to decode image") ->
                "GPU out of memory during image analysis.\n" +
                    "The model is too large for the available unified memory once the image KV cache is added.\n" +
                    "Switch to a smaller multimodal model (Gemma 4 E4B Q8 or Gemma 4 26B Q4) to use image analysis on this machine."
            else -> raw
        }
    }

    private fun resizeToMaxDimension(bytes: ByteArray, maxDim: Int): ByteArray {
        val src = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return bytes
        val w = src.width
        val h = src.height
        if (w <= maxDim && h <= maxDim) return bytes
        val scale = maxDim.toDouble() / maxOf(w, h)
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        val dst = BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB)
        val g = dst.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(src, 0, 0, nw, nh, null)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(dst, "JPEG", out)
        return out.toByteArray()
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
