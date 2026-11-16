package com.llamatik.teammate.server

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.llamatik.agent.core.backend.AssistantBackend
import com.llamatik.agent.core.backend.GenerationCallbacks
import com.llamatik.agent.core.backend.GenerationHandle
import com.llamatik.agent.core.backend.GenerationRequest
import com.llamatik.teammate.llamatik.LlamatikBackendService
import com.llamatik.teammate.settings.LlamatikAppSettings
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The backend the agent talks to. Decides, per request, whether inference runs on the connected
 * Llamatik server or on the local model:
 *
 *  - server connected and healthy → server;
 *  - no server, or connection lost (and a local model is configured) → local;
 *  - a request that was running on the server when it became unreachable for good is re-run on the
 *    local model, so the user is never left with a dead task.
 */
@Service(Service.Level.APP)
class LlamatikRouter : AssistantBackend {
    private val logger = thisLogger()
    private val local: LlamatikBackendService get() = service()
    private val connection: ServerConnectionService get() = service()
    private val remote by lazy { RemoteBackendService(connection) }

    private fun localAvailable(): Boolean = service<LlamatikAppSettings>().data().modelPath.isNotBlank()

    /** True when the next request would run on the server. */
    fun isRemoteActive(): Boolean = connection.shouldUseRemote(localAvailable())

    private fun active(): AssistantBackend = if (isRemoteActive()) remote else local

    override fun isModelLoaded(): Boolean = active().isModelLoaded()

    override fun isBaseModel(): Boolean = active().isBaseModel()

    override fun selectedModelLabel(): String = active().selectedModelLabel()

    override fun generate(request: GenerationRequest, callbacks: GenerationCallbacks): GenerationHandle {
        if (!isRemoteActive()) return local.generate(request, callbacks)

        val current = AtomicReference<GenerationHandle?>(null)
        val cancelled = AtomicBoolean(false)
        // Streamed text can't be taken back, so after a fallback only onCompleted(fullText) is authoritative.
        val deliveredTokens = AtomicBoolean(false)

        val wrapped = object : GenerationCallbacks {
            override fun onStarted() = callbacks.onStarted()

            override fun onToken(token: String) {
                deliveredTokens.set(true)
                callbacks.onToken(token)
            }

            override fun onCompleted(fullText: String) = callbacks.onCompleted(fullText)

            override fun onError(message: String, throwable: Throwable?) {
                val unavailable = generateSequence(throwable) { it.cause }.any { it is ServerUnavailableException }
                if (unavailable && !cancelled.get() && canFallBackToLocal()) {
                    logger.info("Llamatik server unavailable ($message); re-running the request on the local model")
                    val localCallbacks = object : GenerationCallbacks {
                        override fun onToken(token: String) {
                            if (!deliveredTokens.get()) callbacks.onToken(token)
                        }

                        override fun onCompleted(fullText: String) = callbacks.onCompleted(fullText)
                        override fun onError(message: String, throwable: Throwable?) = callbacks.onError(message, throwable)
                    }
                    val handle = local.generate(request, localCallbacks)
                    current.set(handle)
                    if (cancelled.get()) handle.cancel()
                } else {
                    callbacks.onError(message, throwable)
                }
            }
        }

        current.set(remote.generate(request, wrapped))
        return object : GenerationHandle {
            override fun cancel() {
                cancelled.set(true)
                current.get()?.cancel()
            }

            override val isCancelled: Boolean get() = cancelled.get()
        }
    }

    private fun canFallBackToLocal(): Boolean =
        service<LlamatikAppSettings>().data().serverFallbackToLocal && localAvailable()
}
