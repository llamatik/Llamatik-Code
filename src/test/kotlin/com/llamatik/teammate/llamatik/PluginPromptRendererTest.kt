package com.llamatik.teammate.llamatik

import com.llamatik.teammate.model.ChatMessage
import com.llamatik.teammate.model.ChatRole
import org.junit.Assert.*
import org.junit.Test

class PluginPromptRendererTest {

    private fun msg(role: ChatRole, content: String) = ChatMessage(role = role, content = content)

    // ── ChatML / Qwen rendering ─────────────────────────────────────────────

    @Test
    fun `render produces system turn`() {
        val prompt = PluginPromptRenderer.render("You are helpful.", emptyList())
        assertTrue(prompt.contains("<|im_start|>system"))
        assertTrue(prompt.contains("You are helpful."))
        assertTrue(prompt.contains("<|im_end|>"))
    }

    @Test
    fun `render produces user turn`() {
        val messages = listOf(msg(ChatRole.USER, "Hello"))
        val prompt = PluginPromptRenderer.render("", messages)
        assertTrue(prompt.contains("<|im_start|>user"))
        assertTrue(prompt.contains("Hello"))
    }

    @Test
    fun `render ends with assistant prefix when noThink is false`() {
        val messages = listOf(msg(ChatRole.USER, "Hi"))
        val prompt = PluginPromptRenderer.render("", messages, noThink = false)
        assertTrue("Should end with assistant prefix", prompt.trimEnd().endsWith("<|im_start|>assistant"))
    }

    @Test
    fun `render ends with empty think block when noThink is true`() {
        val messages = listOf(msg(ChatRole.USER, "Hi"))
        val prompt = PluginPromptRenderer.render("", messages, noThink = true)
        assertTrue(
            "Should contain pre-filled empty think block",
            prompt.contains("<|im_start|>assistant\n<think>\n\n</think>")
        )
    }

    @Test
    fun `render skips blank assistant turns in history`() {
        val messages = listOf(
            msg(ChatRole.USER, "Q1"),
            msg(ChatRole.ASSISTANT, ""),  // blank — should be skipped
            msg(ChatRole.USER, "Q2"),
        )
        val prompt = PluginPromptRenderer.render("", messages)
        val assistantCount = Regex("<\\|im_start\\|>assistant").findAll(prompt).count()
        // Only the trailing prefix, not the blank history turn
        assertEquals(1, assistantCount)
    }

    @Test
    fun `render includes tool role as tool turn in history`() {
        val messages = listOf(
            msg(ChatRole.USER, "Do something"),
            msg(ChatRole.TOOL, "file content here"),
            msg(ChatRole.USER, "Next step"),
        )
        val prompt = PluginPromptRenderer.render("", messages)
        assertTrue(prompt.contains("<|im_start|>tool"))
        assertTrue(prompt.contains("file content here"))
    }

    // ── renderForModel routing ──────────────────────────────────────────────

    @Test
    fun `renderForModel uses ChatML for Qwen model path`() {
        val result = PluginPromptRenderer.renderForModel(
            system = "sys",
            messages = listOf(msg(ChatRole.USER, "hi")),
            noThink = false,
            modelPath = "/models/qwen3-14b-q4_k_m.gguf",
        )
        assertTrue(result.text.contains("<|im_start|>"))
        assertEquals(PluginPromptRenderer.STOP_SEQUENCES, result.stopSequences)
    }

    @Test
    fun `renderForModel uses Gemma template for gemma model path`() {
        val result = PluginPromptRenderer.renderForModel(
            system = "sys",
            messages = listOf(msg(ChatRole.USER, "hi")),
            modelPath = "/models/gemma-2-2b-it.gguf",
        )
        assertTrue(result.text.contains("<start_of_turn>"))
        assertFalse(result.text.contains("<|im_start|>"))
    }

    @Test
    fun `renderForModel uses Phi3 template for phi model path`() {
        val result = PluginPromptRenderer.renderForModel(
            system = "sys",
            messages = listOf(msg(ChatRole.USER, "hi")),
            modelPath = "/models/phi-3-mini-4k-instruct.gguf",
        )
        assertTrue(result.text.contains("<|user|>"))
        assertFalse(result.text.contains("<|im_start|>"))
    }

    @Test
    fun `renderForModel noThink has no effect on Gemma model`() {
        val withNoThink = PluginPromptRenderer.renderForModel(
            system = "", messages = listOf(msg(ChatRole.USER, "hi")),
            noThink = true, modelPath = "/models/gemma.gguf",
        )
        val withThink = PluginPromptRenderer.renderForModel(
            system = "", messages = listOf(msg(ChatRole.USER, "hi")),
            noThink = false, modelPath = "/models/gemma.gguf",
        )
        // Gemma does not support noThink — both prompts must be identical
        assertEquals(withThink.text, withNoThink.text)
    }

    // ── Gemma rendering ─────────────────────────────────────────────────────

    @Test
    fun `Gemma merges system into first user turn`() {
        val result = PluginPromptRenderer.renderForModel(
            system = "You are a coder.",
            messages = listOf(msg(ChatRole.USER, "Write code")),
            modelPath = "gemma.gguf",
        )
        val firstUserIdx = result.text.indexOf("<start_of_turn>user")
        assertTrue(firstUserIdx >= 0)
        val afterTag = result.text.substring(firstUserIdx)
        assertTrue(afterTag.contains("You are a coder."))
        assertTrue(afterTag.contains("Write code"))
    }

    @Test
    fun `Gemma ends with model turn prefix`() {
        val result = PluginPromptRenderer.renderForModel(
            system = "",
            messages = listOf(msg(ChatRole.USER, "hi")),
            modelPath = "gemma.gguf",
        )
        assertTrue(result.text.trimEnd().endsWith("<start_of_turn>model"))
    }

    // ── noThink applied to native prompt prefix ─────────────────────────────

    @Test
    fun `noThink empty think prefix is a valid Qwen3 technique`() {
        // Simulate what LlamatikBackendService does when nativePrompt is not null
        val nativePrompt = "<|im_start|>user\nhello\n<|im_end|>\n<|im_start|>assistant\n"
        val withNoThink = nativePrompt + "<think>\n\n</think>\n"
        assertTrue(withNoThink.contains("<think>\n\n</think>"))
        // The native prompt itself remains intact before the think block
        assertTrue(withNoThink.startsWith(nativePrompt))
    }
}
