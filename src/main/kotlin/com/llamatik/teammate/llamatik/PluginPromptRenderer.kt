package com.llamatik.teammate.llamatik

import com.llamatik.teammate.model.ChatMessage
import com.llamatik.teammate.model.ChatRole

data class RenderedPrompt(val text: String, val stopSequences: List<String>)

/**
 * Renders a system prompt + conversation history into a single prompt string.
 * Supports ChatML (Qwen / LLaMA) and Gemma chat formats, selected by model filename.
 *
 * Stop sequences are enforced client-side by [LlamatikBackendService].
 */
object PluginPromptRenderer {

    val STOP_SEQUENCES = listOf("<|im_end|>", "<|endoftext|>")
    private val GEMMA_STOP_SEQUENCES = listOf("<end_of_turn>", "<eos>")

    /**
     * Model-aware entry point. Detects the model family from [modelPath] and renders
     * the prompt in the correct chat template with the matching stop sequences.
     */
    fun renderForModel(
        system: String,
        messages: List<ChatMessage>,
        noThink: Boolean = false,
        modelPath: String = "",
    ): RenderedPrompt = if (isGemmaModel(modelPath)) {
        RenderedPrompt(renderGemma(system, messages), GEMMA_STOP_SEQUENCES)
    } else {
        RenderedPrompt(render(system, messages, noThink), STOP_SEQUENCES)
    }

    private fun isGemmaModel(modelPath: String): Boolean {
        val name = modelPath.substringAfterLast('/').substringAfterLast('\\').lowercase()
        return name.contains("gemma")
    }

    /**
     * Gemma chat template: <start_of_turn>user / <start_of_turn>model.
     * The system prompt is merged into the first user turn (Gemma has no dedicated system role).
     * SYSTEM and TOOL messages in the history are skipped — they were already incorporated
     * into the system parameter or as context by the caller.
     */
    private fun renderGemma(system: String, messages: List<ChatMessage>): String = buildString {
        val sys = system.trim()
        var systemInjected = sys.isEmpty()

        for (msg in messages) {
            when (msg.role) {
                ChatRole.USER -> {
                    append("<start_of_turn>user\n")
                    if (!systemInjected) {
                        append(sys)
                        append("\n\n")
                        systemInjected = true
                    }
                    append(msg.content.trim())
                    append("\n<end_of_turn>\n")
                }
                ChatRole.ASSISTANT -> {
                    append("<start_of_turn>model\n")
                    append(msg.content.trim())
                    append("\n<end_of_turn>\n")
                }
                ChatRole.SYSTEM, ChatRole.TOOL -> {
                    // Skipped: SYSTEM is merged into the first user turn above;
                    // TOOL results are already embedded in the system prompt by the caller.
                }
            }
        }

        // Edge case: no user messages appeared — inject system as a standalone user turn.
        if (!systemInjected && sys.isNotBlank()) {
            append("<start_of_turn>user\n")
            append(sys)
            append("\n<end_of_turn>\n")
        }

        append("<start_of_turn>model\n")
    }

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
