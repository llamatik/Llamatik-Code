package com.llamatik.cli

import com.llamatik.agent.core.backend.AssistantBackend
import com.llamatik.agent.core.backend.GenerationCallbacks
import com.llamatik.agent.core.backend.GenerationHandle
import com.llamatik.agent.core.backend.GenerationRequest

/** Shows each request's size and the model's raw reply, for diagnosing odd agent behaviour (--debug). */
class DebugBackend(private val delegate: AssistantBackend, private val terminal: Terminal) : AssistantBackend by delegate {
    private var call = 0

    override fun generate(request: GenerationRequest, callbacks: GenerationCallbacks): GenerationHandle {
        val n = ++call
        terminal.println(terminal.style(Style.DIM, "[debug] request #$n: system ${request.systemPrompt.length} chars, " +
            "${request.messages.size} message(s), maxTokens=${request.config.maxTokens}, prefill=${request.assistantPrefill.length} chars"))
        return delegate.generate(request, object : GenerationCallbacks by callbacks {
            override fun onCompleted(fullText: String) {
                terminal.println(terminal.style(Style.DIM, "[debug] reply #$n: ${fullText.length} chars\n$fullText\n[debug] end of reply #$n"))
                callbacks.onCompleted(fullText)
            }
        })
    }
}
