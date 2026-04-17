package com.llamatik.teammate.llamatik

import com.llamatik.teammate.model.ChatMessage
import com.llamatik.teammate.model.ChatRole

/**
 * Renders a system prompt + conversation history into a single prompt string
 * using the Qwen/ChatML format, matching the format expected by Qwen-based GGUF models.
 *
 * Stop sequences are enforced client-side by the caller (LlamatikBackendService).
 */
object PluginPromptRenderer {

    val STOP_SEQUENCES = listOf("<|im_end|>", "<|endoftext|>")

    fun render(
        system: String,
        messages: List<ChatMessage>,
        noThink: Boolean = false,
    ): String = buildString {
        val sys = system.trim()
        if (sys.isNotBlank()) {
            append("<|im_start|>system\n")
            append(sys)
            append("\n<|im_end|>\n")
        }

        val history = messages.dropLast(1)
        val lastMessage = messages.lastOrNull()

        for (msg in history) {
            val role = msg.role.toQwenRole()
            append("<|im_start|>$role\n")
            append(msg.content.trim())
            append("\n<|im_end|>\n")
        }

        if (lastMessage != null) {
            val role = lastMessage.role.toQwenRole()
            append("<|im_start|>$role\n")
            append(lastMessage.content.trim())
            append("\n<|im_end|>\n")
        }

        // Qwen3 soft-disable thinking: pre-fill an empty <think> block so the model
        // skips reasoning and goes straight to the answer.
        if (noThink) {
            append("<|im_start|>assistant\n<think>\n\n</think>\n")
        } else {
            append("<|im_start|>assistant\n")
        }
    }

    private fun ChatRole.toQwenRole(): String = when (this) {
        ChatRole.SYSTEM -> "system"
        ChatRole.USER -> "user"
        ChatRole.ASSISTANT -> "assistant"
        ChatRole.TOOL -> "tool"
    }
}
