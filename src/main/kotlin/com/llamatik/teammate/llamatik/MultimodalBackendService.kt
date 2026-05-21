package com.llamatik.teammate.llamatik

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.llamatik.teammate.ui.Strings
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
    @Volatile private var nativeLoadError: String? = tryLoadNative()?.also { logger.warn("Multimodal native library failed to load: $it") }

    fun isAvailable(): Boolean = nativeLoadError == null

    fun unload() {
        executor.submit { releaseSync() }
    }

    fun releaseSync() {
        synchronized(this) {
            if (loadedModelPath != null) {
                runCatching { MultimodalBridge.release() }
                loadedModelPath = null
                loadedMmprojPath = null
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
            executor.submit { callbacks.onError(nativeErrorMessage(nativeErr), null) }
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
                val normalizedBytes = resizeAndEncodeJpeg(image.absolutePath, image.bytes, maxDim = 1120)
                if (normalizedBytes.isEmpty()) {
                    executor.submit { callbacks.onError("Failed to encode image for analysis. Ensure the file is a valid PNG, JPEG, or WebP.", null) }
                    return@submit
                }
                logger.info("Multimodal: encoded image bytes=${normalizedBytes.size} for ${image.displayName}")

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
                        resetModelState()
                        if (generationDone.compareAndSet(false, true) && !cancelled.get()) {
                            callbacks.onError(friendlyMultimodalError(message), null)
                        }
                    }
                }

                MultimodalBridge.analyzeImageBytesStream(normalizedBytes, fullPrompt, guard)
                nativeFinished.set(true)

            } catch (t: Throwable) {
                nativeFinished.set(true)
                resetModelState()
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
     * Returns an error string if the two-pass pipeline (VLM + text model) will not fit in
     * available unified memory. Called before pass 1 starts so the user sees a clear message
     * instead of a silent failure midway through generation.
     *
     * Memory model (Apple Silicon unified RAM):
     *   - VLM file size                      ≈ on-disk bytes
     *   - Text model file size               ≈ on-disk bytes
     *   - VLM KV cache + compute (n_ctx 4096) ≈ 1.5 GB
     *   - Text model KV cache (n_ctx 16384)  ≈ 1.8 GB per GQA head group, typically ~2.0 GB
     *   - Compute buffers + OS overhead      ≈ 1.5 GB
     * The VLM is released before the text model loads, so we check each separately against
     * (systemRam - otherModelSize - overhead) rather than summing both at once.
     */
    fun checkCombinedMemoryHeadroom(vlmModelPath: String, textModelPath: String, textContextLength: Int): String? {
        val systemRamGb = runCatching {
            val bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
            if (bean is com.sun.management.OperatingSystemMXBean) bean.totalMemorySize.toDouble() / (1024.0 * 1024 * 1024) else 0.0
        }.getOrDefault(0.0)
        if (systemRamGb <= 0.0) return null

        val vlmSizeGb = runCatching { File(vlmModelPath).length().toDouble() / (1024.0 * 1024 * 1024) }.getOrDefault(0.0)
        val textSizeGb = runCatching { File(textModelPath).length().toDouble() / (1024.0 * 1024 * 1024) }.getOrDefault(0.0)

        // KV cache grows linearly with n_ctx; empirically ~0.125 GB per 1024 tokens for typical GQA models
        val kvCacheGb = (textContextLength / 1024.0) * 0.125
        // Fixed overhead: OS + JVM + compute buffers
        val fixedOverheadGb = 1.5

        // Pass 1: VLM needs vlmSize + 1.5 GB (KV + compute) headroom
        val vlmNeededGb = vlmSizeGb + 1.5
        if (vlmNeededGb > systemRamGb) {
            return "Not enough memory for image analysis.\n" +
                "VLM model: ${"%.1f".format(vlmSizeGb)} GB, System RAM: ${"%.1f".format(systemRamGb)} GB.\n" +
                "Use a smaller multimodal model (e.g. Gemma 4 E4B Q8) on this machine."
        }

        // Pass 2: text model needs textSize + kvCache + fixed overhead
        val textNeededGb = textSizeGb + kvCacheGb + fixedOverheadGb
        if (textNeededGb > systemRamGb) {
            return "Not enough memory to apply changes after image analysis.\n" +
                "Text model: ${"%.1f".format(textSizeGb)} GB + KV cache for Context Size $textContextLength " +
                "(~${"%.1f".format(kvCacheGb)} GB) exceeds System RAM ${"%.1f".format(systemRamGb)} GB.\n" +
                "Reduce Context Size in Settings → Inference (e.g. 8192), or use a smaller text model."
        }

        return null
    }

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
            lower.contains("bitmap_init_from_buf") || lower.contains("failed to decode image bytes") ->
                "Failed to decode the attached image.\n" +
                    "Ensure the file is a valid image (PNG, JPG, or WebP) and try again."
            lower.contains("outofmemory") || lower.contains("out of memory") ||
                lower.contains("insufficient memory") || lower.contains("kIOGPUCommandBufferCallbackErrorOutOfMemory", ignoreCase = true) ||
                lower.contains("mtmd_helper_eval_chunks failed") ->
                "GPU out of memory during image analysis.\n" +
                    "The model is too large for the available unified memory once the image KV cache is added.\n" +
                    "Switch to a smaller multimodal model (Gemma 4 E4B Q8 or Gemma 4 26B Q4) to use image analysis on this machine."
            else -> raw
        }
    }

    private fun resizeAndEncodeJpeg(absolutePath: String, bytes: ByteArray, maxDim: Int): ByteArray {
        // Prefer reading from disk to avoid any in-memory encoding artifacts
        val src = runCatching {
            val f = java.io.File(absolutePath)
            if (f.exists() && f.isFile) ImageIO.read(f) else null
        }.getOrNull()
            ?: runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
            ?: return ByteArray(0)

        val w = src.width
        val h = src.height
        val (nw, nh) = if (w > maxDim || h > maxDim) {
            val scale = maxDim.toDouble() / maxOf(w, h)
            (w * scale).toInt().coerceAtLeast(1) to (h * scale).toInt().coerceAtLeast(1)
        } else {
            w to h
        }
        val dst = BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB)
        val g = dst.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(src, 0, 0, nw, nh, null)
        g.dispose()
        val out = ByteArrayOutputStream()
        val written = ImageIO.write(dst, "JPEG", out)
        if (!written) {
            logger.warn("ImageIO.write returned false — no JPEG writer available")
            return ByteArray(0)
        }
        return out.toByteArray()
    }

    private fun resetModelState() = releaseSync()

    private fun ensureModel(modelPath: String, mmprojPath: String) {
        if (loadedModelPath == modelPath && loadedMmprojPath == mmprojPath) return
        synchronized(this) {
            if (loadedModelPath == modelPath && loadedMmprojPath == mmprojPath) return
            if (loadedModelPath != null) {
                runCatching { MultimodalBridge.release() }
                loadedModelPath = null
                loadedMmprojPath = null
            }
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

private fun nativeErrorMessage(err: String): String {
    if (err.contains("opt/homebrew") && (err.contains("libssl") || err.contains("libcrypto"))) {
        return Strings.Backend.errorNativeLibHomebrew()
    }
    return Strings.Backend.errorNativeLib(err)
}
