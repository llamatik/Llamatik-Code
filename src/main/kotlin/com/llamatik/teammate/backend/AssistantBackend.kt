package com.llamatik.teammate.backend

import com.llamatik.teammate.model.ChatMessage

data class BackendConfig(
    val modelIdOrPath: String,
    val temperature: Double,
    val maxTokens: Int,
    val contextLength: Int,
    val numThreads: Int,
    val useMmap: Boolean,
    val flashAttention: Boolean,
    val batchSize: Int,
    val localOnly: Boolean,
)

enum class SessionTag { AGENT, CHAT, BENCHMARK }

data class GenerationRequest(
    val systemPrompt: String,
    val messages: List<ChatMessage>,
    val config: BackendConfig,
    val noThink: Boolean = false,
    val sessionTag: SessionTag = SessionTag.AGENT,
)

interface GenerationHandle {
    fun cancel()
    val isCancelled: Boolean
}

interface GenerationCallbacks {
    fun onStarted() {}
    fun onToken(token: String)
    fun onCompleted(fullText: String)
    fun onError(message: String, throwable: Throwable? = null)
}

interface AssistantBackend {
    fun isModelLoaded(): Boolean
    fun selectedModelLabel(): String
    fun isBaseModel(): Boolean = false
    fun generate(request: GenerationRequest, callbacks: GenerationCallbacks): GenerationHandle
}
