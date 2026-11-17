package com.llamatik.teammate.llamatik

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.llamatik.agent.backends.local.LocalLlamaBackend
import com.llamatik.agent.core.backend.AssistantBackend
import com.llamatik.teammate.ui.Strings

/**
 * The IDE's local inference: the shared [LocalLlamaBackend] configured for the plugin (model chosen per
 * request from the settings, separate threads and KV caches for agent / chat / benchmark, error texts
 * that point at the settings page).
 */
@Service(Service.Level.APP)
class LlamatikBackendService private constructor(
    private val local: LocalLlamaBackend,
) : AssistantBackend by local {

    constructor() : this(
        LocalLlamaBackend(
            fixedModel = null,
            options = LocalLlamaBackend.Options(
                threadPriority = resolveThreadPriority(),
                executorPerSession = true,
                messages = LocalLlamaBackend.Messages(
                    noModel = "No model configured. Select a local .gguf model file in Settings > Tools > Llamatik Code.",
                    settingsHint = " Adjust it in Inference Settings.",
                    promptTooLong = { ctx ->
                        "Prompt too long for current Context Size ($ctx tokens). " +
                            "Reduce context or increase Context Size in Inference Settings."
                    },
                    nativeUnavailable = { Strings.Backend.errorNativeLib(it) },
                    nativeUnavailableLabel = Strings.Backend.LABEL_NATIVE_UNAVAILABLE,
                    noModelLabel = Strings.Backend.LABEL_NO_MODEL,
                ),
            ),
        ),
    )

    /** Frees the native model (without waiting); the next request loads it again. */
    fun unload() = local.unload()
}

/**
 * Reads the persisted resourceMode and returns the Java thread priority to use for all inference threads.
 *   BALANCED → NORM_PRIORITY (5) — default OS scheduling, half the available cores
 *   LOW      → MIN_PRIORITY+1 (2) — yields to interactive threads but still makes progress;
 *                MIN_PRIORITY (1) maps to macOS Background QoS, which is nearly never scheduled
 *                when IntelliJ is active — causing multi-hour prompt prefill stalls on large models.
 *   FULL     → NORM_PRIORITY (5) — normal priority, user-configured thread count unchanged
 */
private fun resolveThreadPriority(): Int {
    val mode = runCatching {
        service<com.llamatik.teammate.settings.LlamatikAppSettings>().data().resourceMode
    }.getOrDefault("BALANCED")
    return if (mode == "LOW") Thread.MIN_PRIORITY + 1 else Thread.NORM_PRIORITY
}
