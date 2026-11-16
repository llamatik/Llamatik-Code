package com.llamatik.agent.core.testing

import com.llamatik.agent.core.agent.ToolExecutor
import com.llamatik.agent.core.backend.AssistantBackend
import com.llamatik.agent.core.backend.GenerationCallbacks
import com.llamatik.agent.core.backend.GenerationHandle
import com.llamatik.agent.core.backend.GenerationRequest
import com.llamatik.agent.core.tools.ToolCall
import com.llamatik.agent.core.tools.ToolExecutionContext
import com.llamatik.agent.core.tools.ToolResult

/**
 * A fake [AssistantBackend] that returns scripted responses in sequence. Each response is delivered
 * synchronously (no threads) to keep tests deterministic. Blank once the script is exhausted.
 */
class FakeBackend(private vararg val responses: String) : AssistantBackend {
    private var callIndex = 0
    val capturedRequests = mutableListOf<GenerationRequest>()

    override fun isModelLoaded() = true
    override fun selectedModelLabel() = "fake-model"

    override fun generate(request: GenerationRequest, callbacks: GenerationCallbacks): GenerationHandle {
        capturedRequests += request
        val response = responses.getOrElse(callIndex) { "" }
        callIndex++
        callbacks.onCompleted(response)
        return object : GenerationHandle {
            override fun cancel() {}
            override val isCancelled = false
        }
    }
}

/** A [ToolExecutor] that records the calls it receives and returns scripted results. */
class FakeToolRegistry(
    private val results: Map<String, String> = emptyMap(),
    sequentialResults: List<String> = emptyList(),
    private val dynamicNames: Set<String> = emptySet(),
) : ToolExecutor {
    val executedCalls = mutableListOf<ToolCall>()
    private val queue = ArrayDeque(sequentialResults)

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        executedCalls += call
        val content = if (queue.isNotEmpty()) queue.removeFirst() else results[call.name] ?: "result for ${call.name}"
        return ToolResult(call.name, success = true, content = content)
    }

    override fun listToolNames() = "list_files | read_file | write_file | create_file"
    override fun dynamicToolNames() = dynamicNames
}
