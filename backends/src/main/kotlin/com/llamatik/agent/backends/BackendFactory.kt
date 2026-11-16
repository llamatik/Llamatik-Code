package com.llamatik.agent.backends

import com.llamatik.agent.backends.local.LocalLlamaBackend
import com.llamatik.agent.backends.remote.ConnectOutcome
import com.llamatik.agent.backends.remote.RemoteBackend
import com.llamatik.agent.backends.remote.ServerConnector
import com.llamatik.agent.backends.remote.TokenStore
import com.llamatik.agent.core.backend.AssistantBackend
import com.llamatik.agent.core.backend.BackendConfig

/** Inference parameters for command-line hosts; the defaults match the IDE plugin's. */
data class InferenceParams(
    val temperature: Double = 0.7,
    val maxTokens: Int = 8192,
    val contextSize: Int = 16384,
    val threads: Int = maxOf(1, Runtime.getRuntime().availableProcessors() / 2),
    val batchSize: Int = 2048,
    /** Layers offloaded to the GPU: 0 = CPU only, -1 = all. */
    val gpuLayers: Int = if (System.getProperty("os.name").contains("Mac", ignoreCase = true)) -1 else 0,
    val flashAttention: Boolean = false,
    val useMmap: Boolean = true,
) {
    fun toConfig(modelLabel: String) = BackendConfig(
        modelIdOrPath = modelLabel, temperature = temperature, maxTokens = maxTokens, contextLength = contextSize,
        numThreads = threads, useMmap = useMmap, flashAttention = flashAttention, batchSize = batchSize,
        gpuLayers = gpuLayers, localOnly = true,
    )
}

/** A ready backend plus the config to use with it and a one-line description for the user. */
class BackendHandle(val backend: AssistantBackend, val config: BackendConfig, val description: String) : AutoCloseable {
    override fun close() = (backend as? AutoCloseable)?.close() ?: Unit
}

class BackendException(message: String) : Exception(message)

/** Builds the backend a command-line host asked for: a local model file, or a Llamatik server. */
object BackendFactory {
    /**
     * @param model path of a local `.gguf` file (mutually exclusive with [server])
     * @param server `host`, `host:port` or `http://host:port` of a Llamatik server
     */
    fun create(
        model: String?,
        server: String?,
        params: InferenceParams,
        tokens: TokenStore,
        onWaitingForApproval: () -> Unit = {},
    ): BackendHandle {
        if (model != null && server != null) throw BackendException("Use either --model or --server, not both.")
        if (model != null) {
            val backend = try {
                LocalLlamaBackend(model)
            } catch (e: IllegalArgumentException) {
                throw BackendException(e.message ?: "Invalid model")
            }
            return BackendHandle(backend, params.toConfig(backend.selectedModelLabel()), "local model ${backend.selectedModelLabel()}")
        }
        if (server != null) {
            val (host, port) = ServerConnector.parseAddress(server)
                ?: throw BackendException("Invalid server address: $server (expected host, host:port or http://host:port)")
            return when (val outcome = ServerConnector(tokens).connect(host, port, onWaitingForApproval)) {
                is ConnectOutcome.Failed -> throw BackendException(outcome.reason)
                is ConnectOutcome.Connected -> {
                    val backend = RemoteBackend(outcome.connection.link)
                    BackendHandle(backend, params.toConfig(backend.selectedModelLabel()), "server $host:$port (${backend.selectedModelLabel()})")
                }
            }
        }
        throw BackendException("No model to run. Pass --model <file.gguf> or --server <host[:port]>.")
    }
}
