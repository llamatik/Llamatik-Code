package com.llamatik.agent.backends.remote

import com.llamatik.network.client.LlamatikServerClient
import com.llamatik.network.client.LlamatikServerConfig
import com.llamatik.network.client.StreamRetryPolicy
import com.llamatik.network.client.dto.ApplyChatTemplateRequest
import com.llamatik.network.client.dto.ChatMessageDto
import com.llamatik.network.client.dto.ModelsStatusResponse
import com.llamatik.network.client.dto.UpdateParamsRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/** Where a Llamatik server lives and the per-device token (if the server requires approval). */
data class ServerEndpoint(
    val host: String,
    val port: Int = DEFAULT_PORT,
    val token: String? = null,
) {
    /** IPv6 literals must be bracketed inside a URL. */
    internal fun urlHost(): String = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host

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

/** The server rejected our credentials (token missing or revoked). */
class ServerUnauthorizedException : java.io.IOException("The server rejected this device (approval required)")

/**
 * The Llamatik server API as the hosts use it: a thin adapter over the library's
 * [LlamatikServerClient] (`com.llamatik:network`) that adds what the library leaves out: one status
 * probe that tells "needs approval" from "unreachable", request timeouts, and blocking variants for
 * callers that are not coroutines. Streaming, resuming and pairing are the library's.
 */
class ServerApi(
    val endpoint: ServerEndpoint,
    retryPolicy: StreamRetryPolicy = DEFAULT_STREAM_POLICY,
) {
    private val http: HttpClient = sharedHttp(endpoint.token)
    private val config = LlamatikServerConfig(endpoint.urlHost(), endpoint.port, endpoint.token)
    private val client = LlamatikServerClient(config, http, retryPolicy)

    // ── Status ───────────────────────────────────────────────────────────────

    /** One request to `/v1/models`: capabilities, "needs approval" or "unreachable". Never throws. */
    suspend fun probeSuspending(): ServerProbe = try {
        withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            val response = http.get("${config.baseUrl}/models")
            when (response.status) {
                HttpStatusCode.OK -> ServerProbe.Ok(capabilitiesOf(response.body<ModelsStatusResponse>()))
                HttpStatusCode.Unauthorized -> ServerProbe.Unauthorized
                else -> ServerProbe.Unreachable("Server responded ${response.status.value}")
            }
        } ?: ServerProbe.Unreachable("Timed out")
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        ServerProbe.Unreachable(e.message ?: e.javaClass.simpleName)
    }

    fun probe(): ServerProbe = runBlocking(Dispatchers.IO) { probeSuspending() }

    // ── Pairing (per-device approval) ────────────────────────────────────────

    /** Asks the server's owner to approve this device. Returns the request id, or null if refused. */
    fun requestPairing(clientId: String, clientName: String): String? =
        runBlocking(Dispatchers.IO) { withTimeoutOrNull(REQUEST_TIMEOUT_MS) { client.requestPairing(clientId, clientName) } }

    fun pollPairing(requestId: String): PairingStatus? = runBlocking(Dispatchers.IO) {
        withTimeoutOrNull(REQUEST_TIMEOUT_MS) { client.pollPairing(requestId) }?.let { PairingStatus(it.status, it.token) }
    }

    // ── Generation ───────────────────────────────────────────────────────────

    /**
     * The prompt for [messages] (role to content) rendered with the chat template of the model loaded on
     * the server, or null when that model has none. Throws when the server cannot be reached.
     */
    suspend fun applyChatTemplate(messages: List<Pair<String, String>>, addAssistantPrefix: Boolean = true): String? = withTimeout(REQUEST_TIMEOUT_MS) {
        // The library's own helper hides failures (null); a failed request must not look like "no template".
        val response = http.post("${config.baseUrl}/generation/model/applyChatTemplate") {
            setBody(ApplyChatTemplateRequest(messages.map { (role, content) -> ChatMessageDto(role, content) }, addAssistantPrefix))
        }
        when (response.status) {
            HttpStatusCode.Unauthorized -> throw ServerUnauthorizedException()
            HttpStatusCode.OK -> response.body<com.llamatik.network.client.dto.ApplyChatTemplateResponse>().prompt
            else -> throw java.io.IOException("Server responded ${response.status.value}")
        }
    }

    suspend fun updateParams(
        temperature: Float, maxTokens: Int, contextLength: Int, numThreads: Int,
        useMmap: Boolean, flashAttention: Boolean, batchSize: Int, gpuLayers: Int,
    ): Boolean = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
        client.updateParams(
            UpdateParamsRequest(
                temperature = temperature, maxTokens = maxTokens, topP = 0.95f, topK = 40, repeatPenalty = 1.1f,
                contextLength = contextLength, numThreads = numThreads, useMmap = useMmap,
                flashAttention = flashAttention, batchSize = batchSize, gpuLayers = gpuLayers,
            ),
        )
    } ?: false

    /** Stops whatever the server is currently generating. Best effort. */
    suspend fun cancelGeneration(): Boolean = withTimeoutOrNull(REQUEST_TIMEOUT_MS) { client.cancelGeneration() } ?: false

    /**
     * Streams a generation. Dropped connections are resumed by the library (same stream id plus
     * `Last-Event-ID`, so nothing is lost or repeated). Fails with
     * [com.llamatik.network.client.StreamExpiredException] when the server cannot resume, and with the last
     * connection error once the retry budget is spent.
     */
    fun streamGenerate(prompt: String): Flow<String> = client.streamGenerate(prompt)

    private fun capabilitiesOf(status: ModelsStatusResponse) = ServerCapabilities(
        hasText = status.generation.loaded,
        hasImage = status.stableDiffusion.loaded,
        hasVideo = status.stableDiffusion.supportsVideo ?: false,
        hasVision = status.multimodal.loaded,
        hasAudio = status.whisper.loaded,
        hasEmbedding = status.embedding.loaded,
        textModel = status.generation.model,
        finetuneType = status.generation.capabilities?.finetuneType,
        hasChatTemplate = status.generation.capabilities?.hasChatTemplate ?: false,
    )

    companion object {
        const val REQUEST_TIMEOUT_MS = 8_000L

        /**
         * About 80 s of reconnecting (1, 2, 4, then 8 s apart): the server keeps an unattended generation
         * alive for 90 s, so waiting longer cannot help.
         */
        val DEFAULT_STREAM_POLICY = StreamRetryPolicy(maxAttempts = 12, baseDelayMs = 1_000L, maxDelayMs = 8_000L)

        private val clients = ConcurrentHashMap<String, HttpClient>()

        /** One HTTP client per access token, kept for the life of the process. */
        private fun sharedHttp(token: String?): HttpClient = clients.getOrPut(token.orEmpty()) {
            HttpClient(Java) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                // Without a default Content-Type, ktor cannot pick a serializer for setBody(...)
                defaultRequest {
                    contentType(ContentType.Application.Json)
                    if (!token.isNullOrBlank()) header(HttpHeaders.Authorization, "Bearer $token")
                }
            }
        }
    }
}
