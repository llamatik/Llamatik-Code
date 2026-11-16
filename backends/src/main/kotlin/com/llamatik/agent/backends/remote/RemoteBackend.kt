package com.llamatik.agent.backends.remote

import com.llamatik.agent.core.backend.AssistantBackend
import com.llamatik.agent.core.backend.GenerationCallbacks
import com.llamatik.agent.core.backend.GenerationHandle
import com.llamatik.agent.core.backend.GenerationRequest
import com.llamatik.agent.backends.prompt.ChatPromptSupport
import com.llamatik.agent.backends.prompt.PluginPromptRenderer
import java.io.IOException
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The server stayed unreachable for longer than we are willing to wait (or the model it was
 * running is gone). the IDE plugin's router reacts to this by re-running the request on the local model.
 */
class ServerUnavailableException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * [AssistantBackend] that runs inference on a Llamatik server instead of the local native library.
 *
 * Mirrors LlamatikBackendService in the IDE plugin: the prompt is rendered with the
 * chat template of the model loaded *on the server*, tokens are streamed back, and stop sequences /
 * the token budget are enforced client-side.
 *
 * Resilience: if the connection drops mid-generation the request is resumed with the same stream
 * id and `Last-Event-ID`, so the server replays what we missed and keeps following the still-running
 * generation — no lost or duplicated text. We keep retrying with back-off for as long as the server
 * keeps the generation alive; when it can no longer resume, the request is restarted from scratch.
 */
class RemoteBackend(private val connection: ServerLink) : AssistantBackend {
    private val logger = Logger.getLogger(RemoteBackend::class.java.name)

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "llamatik-remote-gen").apply { isDaemon = true }
    }

    override fun isModelLoaded(): Boolean = connection.isUsable()

    override fun isBaseModel(): Boolean =
        connection.capabilities().finetuneType?.lowercase() == "base"

    override fun selectedModelLabel(): String {
        val model = connection.capabilities().textModel ?: "No model loaded"
        return "$model @ ${connection.displayName()}"
    }

    override fun generate(request: GenerationRequest, callbacks: GenerationCallbacks): GenerationHandle {
        val abort = AbortHandle()
        callbacks.onStarted()
        executor.execute {
            try {
                run(request, callbacks, abort)
            } catch (t: Throwable) {
                if (!abort.isAborted) {
                    logger.log(Level.WARNING, "Remote generation failed", t)
                    callbacks.onError(t.message ?: "Remote generation failed", t)
                }
            }
        }
        return object : GenerationHandle {
            override fun cancel() {
                if (abort.isAborted) return
                abort.abort()
                // The server keeps a resumable generation alive for a while after the client leaves; tell it to stop.
                connection.endpoint()?.let { ep -> executor.execute { LlamatikServerClient(ep).cancelGeneration() } }
            }

            override val isCancelled: Boolean get() = abort.isAborted
        }
    }

    private fun run(request: GenerationRequest, callbacks: GenerationCallbacks, userAbort: AbortHandle) {
        val endpoint = connection.endpoint()
            ?: throw ServerUnavailableException("No Llamatik server is configured.")
        val client = LlamatikServerClient(endpoint)
        val caps = connection.capabilities()

        // ── Prompt: rendered by the server's own chat template ──────────────
        val (prompt, stopSequences) = withRetry(userAbort, CONNECT_WINDOW_MS) {
            buildPrompt(client, request, caps.textModel ?: "")
        } ?: return
        if (userAbort.isAborted) return

        // Sampling parameters (best effort: a failure here must not block generation).
        runCatching {
            val c = request.config
            client.updateParams(
                temperature = c.temperature.toFloat(),
                maxTokens = c.maxTokens,
                contextLength = c.contextLength,
                numThreads = c.numThreads,
                useMmap = c.useMmap,
                flashAttention = c.flashAttention,
                batchSize = c.batchSize,
                gpuLayers = c.gpuLayers,
            )
        }.onFailure { if (it is ServerUnauthorizedException) throw it }

        streamWithResume(client, prompt, stopSequences, request, callbacks, userAbort)
    }

    private fun buildPrompt(
        client: LlamatikServerClient,
        request: GenerationRequest,
        serverModel: String,
    ): Pair<String, List<String>> {
        val pairs = ChatPromptSupport.chatPairs(request.systemPrompt, request.messages)
        val native = client.applyChatTemplate(pairs, addAssistantPrefix = true)
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

    private fun streamWithResume(
        client: LlamatikServerClient,
        prompt: String,
        stopSequences: List<String>,
        request: GenerationRequest,
        callbacks: GenerationCallbacks,
        userAbort: AbortHandle,
    ) {
        val maxTokens = request.config.maxTokens
        val acc = StringBuilder()
        var tokenCount = 0
        val finished = AtomicBoolean(false)

        var streamId = newStreamId()
        var received = 0
        var restarts = 0
        var attempt = 0
        var offlineSince = 0L
        // After a restart the text we already streamed to the UI can't be taken back, so the
        // restarted generation is delivered only through onCompleted(fullText).
        var suppressTokens = false
        var deliveredAnyToken = false

        // Aborting one HTTP attempt (stop sequence reached) must not look like a user cancel.
        var streamAbort = AbortHandle()

        fun finish(text: String) {
            if (!finished.compareAndSet(false, true)) return
            callbacks.onCompleted(text)
            streamAbort.abort()
            // The server would otherwise keep generating into the void.
            executor.execute { client.cancelGeneration() }
        }

        while (!userAbort.isAborted && !finished.get()) {
            val receivedBefore = received
            val attemptAbort = AbortHandle()
            streamAbort = attemptAbort
            userAbort.onAbort { attemptAbort.abort() } // runs immediately if already aborted
            try {
                client.streamGenerate(
                    prompt = prompt,
                    streamId = streamId,
                    resumeFrom = received,
                    abort = attemptAbort,
                    idleTimeoutMs = IDLE_TIMEOUT_MS,
                    onDelta = { text ->
                        if (finished.get() || userAbort.isAborted) return@streamGenerate
                        acc.append(text)
                        tokenCount++
                        when {
                            ChatPromptSupport.shouldStop(acc, stopSequences) ->
                                finish(ChatPromptSupport.trimAtStop(acc.toString(), stopSequences))
                            tokenCount >= maxTokens -> finish(acc.toString())
                            !suppressTokens -> {
                                deliveredAnyToken = true
                                callbacks.onToken(text)
                            }
                        }
                    },
                    onEventProcessed = { received = it },
                )
                if (finished.get()) return
                connection.reportConnectionOk()
                if (finished.compareAndSet(false, true) && !userAbort.isAborted) callbacks.onCompleted(acc.toString())
                return
            } catch (e: StreamInterruptedException) {
                if (userAbort.isAborted || finished.get()) return
                if (received > receivedBefore) {
                    // Progress was made before the drop: the retry budget starts over.
                    attempt = 0
                    offlineSince = 0L
                }
                val now = System.currentTimeMillis()
                if (offlineSince == 0L) offlineSince = now
                // Nothing received yet → the request may never have reached the server; wait less.
                val window = if (received > 0 || deliveredAnyToken) RESUME_WINDOW_MS else CONNECT_WINDOW_MS
                if (now - offlineSince > window) {
                    throw ServerUnavailableException(
                        "Lost connection to the Llamatik server and it did not come back in time.", e,
                    )
                }
                logger.info("Stream interrupted after $received events (${e.message}); retrying")
                connection.reportConnectionProblem()
                if (!sleepAbortable(backoffMs(++attempt), userAbort)) return
            } catch (e: StreamExpiredException) {
                if (userAbort.isAborted || finished.get()) return
                if (++restarts > MAX_RESTARTS) {
                    throw ServerUnavailableException("The server could not resume the generation.", e)
                }
                logger.info("Server cannot resume stream (${e.message}); restarting generation")
                suppressTokens = deliveredAnyToken
                acc.setLength(0)
                tokenCount = 0
                received = 0
                attempt = 0
                offlineSince = 0L
                streamId = newStreamId()
            }
            // RemoteInferenceException / ServerUnauthorizedException propagate to generate()'s handler.
        }
    }

    /** Runs [block], retrying connection failures with back-off for up to [windowMs]. Null if aborted. */
    private fun <T> withRetry(abort: AbortHandle, windowMs: Long, block: () -> T): T? {
        val start = System.currentTimeMillis()
        var attempt = 0
        while (true) {
            if (abort.isAborted) return null
            try {
                return block()
            } catch (e: ServerUnauthorizedException) {
                throw e
            } catch (e: IOException) {
                // java.net.http reports connect failures / resets as IOException subclasses.
                if (System.currentTimeMillis() - start > windowMs) {
                    throw ServerUnavailableException("Could not reach the Llamatik server.", e)
                }
                connection.reportConnectionProblem()
                if (!sleepAbortable(backoffMs(++attempt), abort)) return null
            }
        }
    }

    private fun sleepAbortable(ms: Long, abort: AbortHandle): Boolean {
        var remaining = ms
        while (remaining > 0) {
            if (abort.isAborted) return false
            val step = minOf(200L, remaining)
            try {
                Thread.sleep(step)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            remaining -= step
        }
        return !abort.isAborted
    }

    private fun backoffMs(attempt: Int): Long = (1_000L shl (attempt - 1).coerceIn(0, 4)).coerceAtMost(8_000L)

    private fun newStreamId(): String = UUID.randomUUID().toString().replace("-", "")

    companion object {
        /** A generation with no data for this long is considered dead (e.g. a silent network switch). */
        private const val IDLE_TIMEOUT_MS = 120_000L

        /** How long a new request waits for an unreachable server before giving up. */
        private const val CONNECT_WINDOW_MS = 20_000L

        /**
         * How long a running generation keeps trying to resume. The server keeps an unattended
         * generation alive for 90 s; beyond that it cancels it, so waiting longer cannot help.
         */
        private const val RESUME_WINDOW_MS = 85_000L

        private const val MAX_RESTARTS = 2
    }
}
