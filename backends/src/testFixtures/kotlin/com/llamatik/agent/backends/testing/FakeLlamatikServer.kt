package com.llamatik.agent.backends.testing

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** An in-process stand-in for a Llamatik server (`/v1/...`), scriptable per test. */
class FakeLlamatikServer : AutoCloseable {
    sealed interface StreamReply {
        /** SSE chunks written as is; [resumeSupported] sets `X-Stream-Resume: 1`. The connection then closes. */
        data class Events(val chunks: List<String>, val resumeSupported: Boolean = true) : StreamReply
        data object Gone : StreamReply
    }

    data class StreamRequest(val streamId: String?, val lastEventId: String?, val body: String)

    private val http: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val port: Int get() = http.address.port

    /** When set, every endpoint but pairing requires `Authorization: Bearer <token>`. */
    @Volatile var requiredToken: String? = null
    @Volatile var textModel: String? = "qwen.gguf"
    @Volatile var template: String? = "<|im_start|>user\nhi<|im_end|>\n<|im_start|>assistant\n"
    @Volatile var finetuneType: String = "instruct"
    /** What polling a pairing request reports: approved | denied | expired. */
    @Volatile var pairingOutcome: String = "approved"
    @Volatile var issuedToken: String = "device-token"

    val streamReplies = ConcurrentLinkedQueue<StreamReply>()
    val streamRequests = CopyOnWriteArrayList<StreamRequest>()
    val cancelCalls = AtomicInteger()
    val pairingRequests = CopyOnWriteArrayList<String>()
    val paramBodies = CopyOnWriteArrayList<String>()

    init {
        http.createContext("/v1/models") { ex -> guarded(ex) { reply(it, 200, modelsJson()) } }
        http.createContext("/v1/generation/model/applyChatTemplate") { ex ->
            guarded(ex) { reply(it, 200, """{"prompt":${template?.let { t -> jsonString(t) } ?: "null"}}""") }
        }
        http.createContext("/v1/generation/params") { ex -> guarded(ex) { paramBodies += it.body(); reply(it, 200, """{"ok":true}""") } }
        http.createContext("/v1/generation/cancel") { ex -> guarded(ex) { cancelCalls.incrementAndGet(); reply(it, 200, """{"ok":true}""") } }
        http.createContext("/v1/generation/stream") { ex -> guarded(ex) { stream(it) } }
        http.createContext("/v1/pairing/request") { ex ->
            val body = ex.body()
            pairingRequests += body
            reply(ex, 200, """{"requestId":"req-1"}""")
        }
        http.createContext("/v1/pairing/req-1") { ex ->
            val token = if (pairingOutcome == "approved") ""","token":"$issuedToken"""" else ""
            reply(ex, 200, """{"status":"$pairingOutcome"$token}""")
        }
        http.start()
    }

    private fun stream(ex: HttpExchange) {
        streamRequests += StreamRequest(ex.requestHeaders.getFirst("X-Stream-Id"), ex.requestHeaders.getFirst("Last-Event-ID"), ex.body())
        val next = streamReplies.poll() ?: StreamReply.Events(listOf(delta(1, "ok"), done(2)))
        when (next) {
            StreamReply.Gone -> reply(ex, 410, "Stream expired")
            is StreamReply.Events -> {
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                if (next.resumeSupported) ex.responseHeaders.add("X-Stream-Resume", "1")
                ex.sendResponseHeaders(200, 0)
                ex.responseBody.use { out -> next.chunks.forEach { out.write(it.toByteArray()); out.flush() } }
            }
        }
    }

    private fun guarded(ex: HttpExchange, block: (HttpExchange) -> Unit) {
        val required = requiredToken
        if (required != null && ex.requestHeaders.getFirst("Authorization") != "Bearer $required") reply(ex, 401, "{}") else block(ex)
    }

    private fun modelsJson(): String {
        val gen = if (textModel != null)
            """{"loaded":true,"model":"$textModel","capabilities":{"finetuneType":"$finetuneType","hasChatTemplate":${template != null}}}"""
        else """{"loaded":false}"""
        return """{"generation":$gen,"embedding":{"loaded":false},"whisper":{"loaded":false},"stableDiffusion":{"loaded":false},"multimodal":{"loaded":false},"mtp":{"loaded":false}}"""
    }

    private fun HttpExchange.body(): String = requestBody.readBytes().toString(Charsets.UTF_8)

    private fun reply(ex: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    override fun close() = http.stop(0)

    companion object {
        fun delta(id: Int, text: String) = "id: $id\ndata: {\"event\":\"delta\",\"text\":${jsonString(text)}}\n\n"
        fun done(id: Int) = "id: $id\ndata: {\"event\":\"done\"}\n\n"
        fun error(id: Int, message: String) = "id: $id\ndata: {\"event\":\"error\",\"message\":${jsonString(message)}}\n\n"
        private fun jsonString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    }
}
