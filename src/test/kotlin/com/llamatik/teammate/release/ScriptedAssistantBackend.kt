package com.llamatik.teammate.release

import com.llamatik.teammate.backend.AssistantBackend
import com.llamatik.teammate.backend.GenerationCallbacks
import com.llamatik.teammate.backend.GenerationHandle
import com.llamatik.teammate.backend.GenerationRequest

/**
 * A scripted [AssistantBackend] that returns pre-defined responses in order.
 * After all responses are exhausted, returns "The task is complete."
 *
 * Used in workflow tests to simulate a model without requiring a real model file.
 */
class ScriptedAssistantBackend(private val responses: List<String>) : AssistantBackend {

    private var callIndex = 0
    val capturedRequests = mutableListOf<GenerationRequest>()

    override fun isModelLoaded() = true
    override fun selectedModelLabel() = "scripted"

    override fun generate(request: GenerationRequest, callbacks: GenerationCallbacks): GenerationHandle {
        capturedRequests += request
        val response = responses.getOrElse(callIndex) { "The task is complete." }
        callIndex++
        callbacks.onCompleted(response)
        return noopHandle()
    }

    private fun noopHandle() = object : GenerationHandle {
        override fun cancel() {}
        override val isCancelled = false
    }
}
