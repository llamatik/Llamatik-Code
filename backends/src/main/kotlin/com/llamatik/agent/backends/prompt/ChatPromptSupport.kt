package com.llamatik.agent.backends.prompt

import com.llamatik.agent.core.model.ChatMessage
import com.llamatik.agent.core.model.ChatRole

/**
 * Prompt helpers shared by the local ([LlamatikBackendService]) and remote
 * ([com.llamatik.agent.backends.remote.RemoteBackend]) backends, so both build prompts and detect
 * turn boundaries identically.
 */
object ChatPromptSupport {

    /** System prompt (if any) followed by the conversation, as (role, content) pairs for a chat template. */
    fun chatPairs(systemPrompt: String, messages: List<ChatMessage>): List<Pair<String, String>> = buildList {
        if (systemPrompt.isNotBlank()) add("system" to systemPrompt)
        messages.forEach { msg ->
            val role = when (msg.role) {
                ChatRole.SYSTEM -> "system"
                ChatRole.USER -> "user"
                ChatRole.ASSISTANT -> "assistant"
                ChatRole.TOOL -> "user"
            }
            add(role to msg.content)
        }
    }

    /**
     * Infers stop sequences from the format of the rendered prompt so we only enforce turn-boundary
     * markers this model actually uses. Applying ChatML stops to a model with a different template
     * (e.g. Mellum's <user>/<assistant> format) would halt generation before any tool call is made.
     */
    fun stopSequencesFor(renderedPrompt: String): List<String> = when {
        renderedPrompt.contains("<|im_end|>") -> listOf("<|im_end|>", "<|endoftext|>")
        renderedPrompt.contains("<end_of_turn>") -> listOf("<end_of_turn>", "<eos>")
        renderedPrompt.contains("<|end|>") -> listOf("<|end|>", "<|endoftext|>")
        else -> emptyList() // Unknown/base model format — rely on native EOS only
    }

    fun shouldStop(sb: StringBuilder, stops: List<String>): Boolean {
        if (stops.isEmpty()) return false
        val tail = sb.takeLast(minOf(64, sb.length))
        return stops.any { tail.endsWith(it) }
    }

    fun trimAtStop(text: String, stops: List<String>): String {
        var out = text
        for (s in stops) {
            val idx = out.lastIndexOf(s)
            if (idx >= 0) {
                out = out.substring(0, idx)
                break
            }
        }
        return out
    }
}
