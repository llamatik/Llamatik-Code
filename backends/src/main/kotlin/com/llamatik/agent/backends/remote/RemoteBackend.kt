package com.llamatik.agent.backends.remote

import com.llamatik.agent.backends.prompt.ChatPromptSupport
import com.llamatik.agent.backends.prompt.PluginPromptRenderer
import com.llamatik.agent.core.backend.AssistantBackend
import com.llamatik.agent.core.backend.GenerationCallbacks
import com.llamatik.agent.core.backend.GenerationHandle
import com.llamatik.agent.core.backend.GenerationRequest
import com.llamatik.network.client.RemoteInferenceException
import com.llamatik.network.client.StreamExpiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger

/**
 * The server stayed unreachable for longer than we are willing to wait (or the model it was
 * running is gone). the IDE plugin's router reacts to this by re-running the request on the local model.
 */
class ServerUnavailableException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * [AssistantBackend] that runs inference on a Llamatik server instead of the local native library.
 *
 * The prompt is rendered with the chat template of the model loaded *on the server*, tokens are streamed
 * back, and stop sequences and the token budget are enforced client-side, exactly as for the local backend.
 *
 * Resilience: a connection that drops mid-generation is resumed by the library's stream (same stream id and
 * `Last-Event-ID`: the server replays what was missed and keeps following the running generation, so no text
 * is lost or repeated), retrying for about as long as the server keeps the generation alive. When the server
 * can no longer resume, the request is restarted from scratch. If it stays unreachable, the request fails
 * with [ServerUnavailableException] so the host can fall back to a local model.
 */
class RemoteBackend(private val connection: ServerLink) : AssistantBackend, AutoCloseable {
    private val logger = Logger.getLogger(RemoteBackend::class.java.name)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun isModelLoaded(): Boolean = connection.isUsable()

    override fun isBaseModel(): Boolean = connection.capabilities().finetuneType?.lowercase() == "base"

    override fun selectedModelLabel(): String {
        val model = connection.capabilities().textModel ?: "No model loaded"
        return "$model @ ${connection.displayName()}"
    }

    override fun close() = scope.cancel()

    override fun generate(request: GenerationRequest, callbacks: GenerationCallbacks): GenerationHandle {
        val cancelled = AtomicBoolean(false)
        callbacks.onStarted()
        val job = scope.launch {
            try {
                run(request, callbacks)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (!cancelled.get()) {
                    logger.log(Level.WARNING, "Remote generation failed", t)
                    callbacks.onError(t.message ?: "Remote generation failed", t)
                }
            }
        }
        return object : GenerationHandle {
            override fun cancel() {
                if (!cancelled.compareAndSet(false, true)) return
                job.cancel()
                // The server keeps a resumable generation alive for a while after the client leaves; tell it to stop.
                stopOnServer()
            }

            override val isCancelled: Boolean get() = cancelled.get()
        }
    }

    private fun stopOnServer() {
        val endpoint = connection.endpoint() ?: return
        scope.launch { runCatching { ServerApi(endpoint).cancelGeneration() } }
    }

    private suspend fun run(request: GenerationRequest, callbacks: GenerationCallbacks) {
        val endpoint = connection.endpoint() ?: throw ServerUnavailableException("No Llamatik server is configured.")
        val api = ServerApi(endpoint)

        awaitReachable(api)
        val (prompt, stopSequences) = buildPrompt(api, request, connection.capabilities().textModel ?: "")

        // Sampling parameters (best effort: a failure here must not block generation).
        val c = request.config
        api.updateParams(
            temperature = c.temperature.toFloat(), maxTokens = c.maxTokens, contextLength = c.contextLength,
            numThreads = c.numThreads, useMmap = c.useMmap, flashAttention = c.flashAttention,
            batchSize = c.batchSize, gpuLayers = c.gpuLayers,
        )

        stream(api, prompt, stopSequences, request, callbacks)
    }

    /** Waits (up to [CONNECT_WINDOW_MS]) until the server answers, so a dead server fails fast and clearly. */
    private suspend fun awaitReachable(api: ServerApi) {
        val deadline = System.currentTimeMillis() + CONNECT_WINDOW_MS
        var attempt = 0
        while (true) {
            when (val probe = api.probeSuspending()) {
                is ServerProbe.Ok -> return
                ServerProbe.Unauthorized -> throw ServerUnauthorizedException()
                is ServerProbe.Unreachable -> {
                    connection.reportConnectionProblem()
                    if (System.currentTimeMillis() > deadline) {
                        throw ServerUnavailableException("Could not reach the Llamatik server (${probe.reason}).")
                    }
                    delay(backoffMs(++attempt))
                }
            }
        }
    }

    private suspend fun buildPrompt(api: ServerApi, request: GenerationRequest, serverModel: String): Pair<String, List<String>> {
        val pairs = ChatPromptSupport.chatPairs(request.systemPrompt, request.messages)
        val native = renderOnServer(api, pairs)
            ?.removePrefix("<s>") // the tokenizer adds BOS; a double BOS corrupts the model
            ?.takeIf { it.isNotBlank() }

        return if (native != null) {
            val withThink = if (request.noThink) native + "<think>\n\n</think>\n" else native
            val final = if (request.assistantPrefill.isNotBlank()) withThink + request.assistantPrefill else withThink
            final to ChatPromptSupport.stopSequencesFor(native)
        } else {
            // The server's model has no embedded template: fall back to the plugin's renderer.
            val rendered = PluginPromptRenderer.renderForModel(
                system = request.systemPrompt,
                messages = request.messages,
                noThink = request.noThink,
                modelPath = serverModel,
            )
            val text = if (request.assistantPrefill.isNotBlank()) rendered.text + request.assistantPrefill else rendered.text
            text to rendered.stopSequences
        }
    }

    /** Retries a failed template request (the server was just probed, so a failure is transient). */
    private suspend fun renderOnServer(api: ServerApi, pairs: List<Pair<String, String>>): String? {
        var last: IOException? = null
        repeat(TEMPLATE_ATTEMPTS) { attempt ->
            try {
                return api.applyChatTemplate(pairs, addAssistantPrefix = true)
            } catch (e: ServerUnauthorizedException) {
                throw e
            } catch (e: IOException) {
                last = e
                delay(backoffMs(attempt + 1))
            }
        }
        throw ServerUnavailableException("Could not reach the Llamatik server.", last)
    }

    private suspend fun stream(
        api: ServerApi,
        prompt: String,
        stopSequences: List<String>,
        request: GenerationRequest,
        callbacks: GenerationCallbacks,
    ) {
        val maxTokens = request.config.maxTokens
        val acc = StringBuilder()
        var tokenCount = 0
        var finished = false
        var restarts = 0
        // After a restart the text already streamed to the UI can't be taken back, so the restarted
        // generation is delivered only through onCompleted(fullText).
        var suppressTokens = false
        var deliveredAnyToken = false

        fun finish(text: String) {
            finished = true
            callbacks.onCompleted(text)
            stopOnServer() // the server would otherwise keep generating into the void
            throw StopStreaming
        }

        while (true) {
            acc.setLength(0)
            tokenCount = 0
            try {
                api.streamGenerate(prompt).collect { text ->
                    acc.append(text)
                    tokenCount++
                    when {
                        ChatPromptSupport.shouldStop(acc, stopSequences) -> finish(ChatPromptSupport.trimAtStop(acc.toString(), stopSequences))
                        tokenCount >= maxTokens -> finish(acc.toString())
                        !suppressTokens -> {
                            deliveredAnyToken = true
                            callbacks.onToken(text)
                        }
                    }
                }
                finished = true
                connection.reportConnectionOk()
                callbacks.onCompleted(acc.toString())
                return
            } catch (e: StopStreaming) {
                connection.reportConnectionOk()
                return
            } catch (e: StreamExpiredException) {
                if (++restarts > MAX_RESTARTS) throw ServerUnavailableException("The server could not resume the generation.", e)
                logger.info("Server cannot resume stream (${e.message}); restarting generation")
                suppressTokens = deliveredAnyToken
            } catch (e: RemoteInferenceException) {
                // Includes HTTP errors such as 401 once the library cannot make progress; not retried.
                if (e.message?.contains("401") == true) throw ServerUnauthorizedException()
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The library already reconnected for as long as it could.
                connection.reportConnectionProblem()
                if (finished) return
                throw ServerUnavailableException("Lost connection to the Llamatik server and it did not come back in time.", e)
            }
        }
    }

    private fun backoffMs(attempt: Int): Long = (1_000L shl (attempt - 1).coerceIn(0, 4)).coerceAtMost(8_000L)

    /** Thrown inside the stream collector to end the stream once the reply is complete. */
    private object StopStreaming : RuntimeException() {
        private fun readResolve(): Any = StopStreaming
        override fun fillInStackTrace(): Throwable = this
    }

    companion object {
        /** How long a new request waits for an unreachable server before giving up. */
        private const val CONNECT_WINDOW_MS = 20_000L
        private const val TEMPLATE_ATTEMPTS = 3
        private const val MAX_RESTARTS = 2
    }
}
