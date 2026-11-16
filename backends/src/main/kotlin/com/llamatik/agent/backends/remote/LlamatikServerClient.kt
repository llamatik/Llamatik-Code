package com.llamatik.agent.backends.remote

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Where a Llamatik server lives and the per-device token (if the server requires approval). */
data class ServerEndpoint(
    val host: String,
    val port: Int = DEFAULT_PORT,
    val token: String? = null,
) {
    val baseUrl: String get() = "http://${hostForUrl()}:$port/v1"

    /** IPv6 literals must be bracketed inside a URL. */
    private fun hostForUrl(): String = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host

    companion object {
        const val DEFAULT_PORT = 8080
    }
}

/** What the server reported via `GET /v1/models`. */
data class ServerCapabilities(
    val hasText: Boolean = false,
    val hasImage: Boolean = false,
    val hasVideo: Boolean = false,
    val hasVision: Boolean = false,
    val hasAudio: Boolean = false,
    val hasEmbedding: Boolean = false,
    /** File name of the loaded text-generation model, if any. */
    val textModel: String? = null,
    /** `general.finetune` of the loaded text model ("instruct", "chat", "base", …). */
    val finetuneType: String? = null,
    val hasChatTemplate: Boolean = false,
)

/** Result of asking a server for its status. */
sealed interface ServerProbe {
    data class Ok(val capabilities: ServerCapabilities) : ServerProbe

    /** The server answered 401: this device has no (valid) token. */
    data object Unauthorized : ServerProbe
    data class Unreachable(val reason: String) : ServerProbe
}

/** Progress of a pairing poll. [token] is non-null once, when approved. */
data class PairingStatus(val status: String, val token: String?)

/** Cooperative cancellation for a running request/stream. */
class AbortHandle {
    private val aborted = AtomicBoolean(false)
    private val hooks = CopyOnWriteArrayList<() -> Unit>()

    val isAborted: Boolean get() = aborted.get()

    fun onAbort(hook: () -> Unit) {
        hooks.add(hook)
        if (aborted.get()) runCatching(hook)
    }

    fun abort() {
        if (aborted.compareAndSet(false, true)) hooks.forEach { runCatching(it) }
    }
}

/** The connection dropped or stalled before the server reported completion. Safe to resume. */
class StreamInterruptedException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The server cannot resume this stream any more (restarted / expired / old server). Restart from scratch. */
class StreamExpiredException(message: String) : IOException(message)

/** The server reported a failure for this request. Not retried: it would fail again. */
class RemoteInferenceException(message: String) : Exception(message)

/** The server rejected our credentials (token missing or revoked). */
class ServerUnauthorizedException : IOException("The server rejected this device (approval required)")

/**
 * Minimal client for the Llamatik server HTTP API (`/v1/...`), the same protocol the Llamatik
 * library's network module speaks. Blocking calls; run them off the EDT.
 */
class LlamatikServerClient(val endpoint: ServerEndpoint) {

    // ── Health & capabilities ───────────────────────────────────────────────

    fun probe(): ServerProbe = try {
        val response = http.send(
            request("/models").GET().timeout(REQUEST_TIMEOUT).build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
        when (response.statusCode()) {
            200 -> ServerProbe.Ok(parseCapabilities(JsonParser.parseString(response.body()).asJsonObject))
            401 -> ServerProbe.Unauthorized
            else -> ServerProbe.Unreachable("Server responded ${response.statusCode()}")
        }
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        ServerProbe.Unreachable("Interrupted")
    } catch (e: Exception) {
        ServerProbe.Unreachable(e.message ?: e.javaClass.simpleName)
    }

    // ── Pairing (per-device approval) ───────────────────────────────────────

    /** Asks the server's owner to approve this device. Returns the request id, or null if refused. */
    fun requestPairing(clientId: String, clientName: String): String? = try {
        val body = JsonObject().apply {
            addProperty("clientId", clientId)
            addProperty("clientName", clientName)
        }
        val response = postJson("/pairing/request", body, REQUEST_TIMEOUT)
        if (response.statusCode() in 200..299) {
            JsonParser.parseString(response.body()).asJsonObject.get("requestId")?.asString
        } else null
    } catch (e: Exception) {
        null
    }

    fun pollPairing(requestId: String): PairingStatus? = try {
        val response = http.send(
            request("/pairing/$requestId").GET().timeout(REQUEST_TIMEOUT).build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
        if (response.statusCode() !in 200..299) null else {
            val json = JsonParser.parseString(response.body()).asJsonObject
            PairingStatus(
                status = json.get("status")?.asString ?: "",
                token = json.get("token")?.takeUnless { it.isJsonNull }?.asString,
            )
        }
    } catch (e: Exception) {
        null
    }

    // ── Generation ──────────────────────────────────────────────────────────

    /** Renders [messages] with the chat template of the model loaded on the server (role to content). */
    fun applyChatTemplate(messages: List<Pair<String, String>>, addAssistantPrefix: Boolean = true): String? {
        val array = JsonArray()
        messages.forEach { (role, content) ->
            array.add(JsonObject().apply {
                addProperty("role", role)
                addProperty("content", content)
            })
        }
        val body = JsonObject().apply {
            add("messages", array)
            addProperty("addAssistantPrefix", addAssistantPrefix)
        }
        val response = postJson("/generation/model/applyChatTemplate", body, REQUEST_TIMEOUT)
        checkStatus(response.statusCode(), response.body())
        return JsonParser.parseString(response.body()).asJsonObject.get("prompt")
            ?.takeUnless { it.isJsonNull }?.asString
    }

    fun updateParams(
        temperature: Float,
        maxTokens: Int,
        contextLength: Int,
        numThreads: Int,
        useMmap: Boolean,
        flashAttention: Boolean,
        batchSize: Int,
        gpuLayers: Int,
    ) {
        val body = JsonObject().apply {
            addProperty("temperature", temperature)
            addProperty("maxTokens", maxTokens)
            addProperty("topP", 0.95f)
            addProperty("topK", 40)
            addProperty("repeatPenalty", 1.1f)
            addProperty("contextLength", contextLength)
            addProperty("numThreads", numThreads)
            addProperty("useMmap", useMmap)
            addProperty("flashAttention", flashAttention)
            addProperty("batchSize", batchSize)
            addProperty("gpuLayers", gpuLayers)
        }
        val response = postJson("/generation/params", body, REQUEST_TIMEOUT)
        checkStatus(response.statusCode(), response.body())
    }

    /** Stops whatever the server is currently generating. Best effort. */
    fun cancelGeneration(): Boolean = try {
        postJson("/generation/cancel", null, REQUEST_TIMEOUT).statusCode() in 200..299
    } catch (e: Exception) {
        false
    }

    /**
     * Streams `/generation/stream` as server-sent events, reporting each text delta to [onDelta].
     *
     * [streamId] identifies the stream on the server; re-sending it with [resumeFrom] (the number of
     * events already received) reattaches to the still-running generation and replays what was
     * missed. Returns normally once the server sent its `done` event.
     *
     * @throws StreamInterruptedException connection lost/stalled before `done` — resume it
     * @throws StreamExpiredException the server can no longer resume — restart from scratch
     * @throws RemoteInferenceException the server reported an error
     */
    fun streamGenerate(
        prompt: String,
        streamId: String,
        resumeFrom: Int,
        abort: AbortHandle,
        idleTimeoutMs: Long,
        onDelta: (String) -> Unit,
        onEventProcessed: (Int) -> Unit,
    ) {
        val body = JsonObject().apply { addProperty("prompt", prompt) }
        val builder = request("/generation/stream")
            .header(STREAM_ID_HEADER, streamId)
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
        if (resumeFrom > 0) builder.header(LAST_EVENT_ID_HEADER, resumeFrom.toString())

        val future = http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        abort.onAbort { future.cancel(true) }
        val response = try {
            future.get(CONNECT_AND_HEADERS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: CancellationException) {
            throw StreamInterruptedException("Aborted", e)
        } catch (e: ExecutionException) {
            throw StreamInterruptedException(e.cause?.message ?: "Connection failed", e.cause)
        } catch (e: java.util.concurrent.TimeoutException) {
            future.cancel(true)
            throw StreamInterruptedException("Timed out waiting for the server", e)
        }

        val status = response.statusCode()
        if (status == 410) {
            response.body().closeQuietly()
            throw StreamExpiredException("Stream expired on the server")
        }
        if (status == 401) {
            response.body().closeQuietly()
            throw ServerUnauthorizedException()
        }
        if (status !in 200..299) {
            val text = runCatching { response.body().readBytes().toString(StandardCharsets.UTF_8) }.getOrDefault("")
            response.body().closeQuietly()
            throw RemoteInferenceException("Server responded $status${if (text.isNotBlank()) ": ${text.take(300)}" else ""}")
        }
        // A server without resume support would restart the generation from scratch.
        if (resumeFrom > 0 && response.headers().firstValue(STREAM_RESUME_HEADER).orElse("") != "1") {
            response.body().closeQuietly()
            throw StreamExpiredException("Server does not support resuming streams")
        }

        parseSse(response.body(), abort, idleTimeoutMs, onDelta, onEventProcessed)
    }

    private fun parseSse(
        stream: InputStream,
        abort: AbortHandle,
        idleTimeoutMs: Long,
        onDelta: (String) -> Unit,
        onEventProcessed: (Int) -> Unit,
    ) {
        val lastActivity = AtomicLong(System.nanoTime())
        val stalled = AtomicBoolean(false)
        abort.onAbort { stream.closeQuietly() }
        // A stream that goes silent (e.g. a silent network switch) never errors by itself: close it.
        val watchdog = watchdogScheduler.scheduleWithFixedDelay({
            val idleMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastActivity.get())
            if (idleMs > idleTimeoutMs) {
                stalled.set(true)
                stream.closeQuietly()
            }
        }, WATCHDOG_PERIOD_MS, WATCHDOG_PERIOD_MS, TimeUnit.MILLISECONDS)

        var finished = false
        try {
            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                var pendingId: Int? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    lastActivity.set(System.nanoTime())
                    when {
                        line.startsWith("id:") -> pendingId = line.removePrefix("id:").trim().toIntOrNull()
                        line.startsWith("data:") -> {
                            val data = line.removePrefix("data:").trim()
                            if (data.isEmpty()) continue
                            val event = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull()
                                ?: continue // skip malformed line
                            // Record progress before delivering so a drop right after never duplicates text.
                            pendingId?.let(onEventProcessed)
                            pendingId = null
                            when (event.get("event")?.asString) {
                                "delta" -> event.get("text")?.takeUnless { it.isJsonNull }?.asString?.let(onDelta)
                                "done" -> {
                                    finished = true
                                }
                                "error" -> throw RemoteInferenceException(
                                    event.get("message")?.takeUnless { it.isJsonNull }?.asString
                                        ?: "Remote inference error",
                                )
                            }
                            if (finished) break
                        }
                    }
                }
            }
        } catch (e: RemoteInferenceException) {
            throw e
        } catch (e: IOException) {
            if (abort.isAborted) throw StreamInterruptedException("Aborted", e)
            throw StreamInterruptedException(if (stalled.get()) "Stream stalled" else (e.message ?: "Connection lost"), e)
        } finally {
            watchdog.cancel(false)
        }
        if (!finished) {
            throw StreamInterruptedException(
                if (abort.isAborted) "Aborted"
                else if (stalled.get()) "Stream stalled"
                else "Connection closed before generation finished",
            )
        }
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    private fun request(path: String): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(URI.create("${endpoint.baseUrl}$path"))
            .header("Content-Type", "application/json")
            .header("Accept", if (path.endsWith("/stream")) "text/event-stream" else "application/json")
        endpoint.token?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun postJson(path: String, body: JsonObject?, timeout: Duration): HttpResponse<String> =
        http.send(
            request(path)
                .POST(HttpRequest.BodyPublishers.ofString(body?.toString() ?: "{}", StandardCharsets.UTF_8))
                .timeout(timeout)
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )

    private fun checkStatus(status: Int, body: String) {
        when {
            status == 401 -> throw ServerUnauthorizedException()
            status !in 200..299 -> throw IOException("Server responded $status${if (body.isNotBlank()) ": ${body.take(300)}" else ""}")
        }
    }

    private fun parseCapabilities(root: JsonObject): ServerCapabilities {
        fun obj(name: String): JsonObject? = root.get(name)?.takeIf { it.isJsonObject }?.asJsonObject
        fun JsonObject?.loaded(): Boolean = this?.get("loaded")?.asBoolean ?: false
        fun JsonObject?.str(name: String): String? =
            this?.get(name)?.takeUnless { it.isJsonNull }?.asString

        val generation = obj("generation")
        val genCaps = generation?.get("capabilities")?.takeIf { it.isJsonObject }?.asJsonObject
        val sd = obj("stableDiffusion")
        return ServerCapabilities(
            hasText = generation.loaded(),
            hasImage = sd.loaded(),
            hasVideo = sd?.get("supportsVideo")?.takeUnless { it.isJsonNull }?.asBoolean ?: false,
            hasVision = obj("multimodal").loaded(),
            hasAudio = obj("whisper").loaded(),
            hasEmbedding = obj("embedding").loaded(),
            textModel = generation.str("model"),
            finetuneType = genCaps.str("finetuneType"),
            hasChatTemplate = genCaps?.get("hasChatTemplate")?.asBoolean ?: false,
        )
    }

    companion object {
        const val STREAM_ID_HEADER = "X-Stream-Id"
        const val LAST_EVENT_ID_HEADER = "Last-Event-ID"
        const val STREAM_RESUME_HEADER = "X-Stream-Resume"

        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(8)
        private const val CONNECT_AND_HEADERS_TIMEOUT_MS = 30_000L
        private const val WATCHDOG_PERIOD_MS = 5_000L

        private val http: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

        private val watchdogScheduler: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "llamatik-server-watchdog").apply { isDaemon = true }
            }
    }
}

private fun InputStream.closeQuietly() {
    try {
        close()
    } catch (_: IOException) {
    }
}
