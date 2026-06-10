package com.llamatik.teammate.completion

import com.intellij.openapi.components.service
import com.llamatik.teammate.settings.LlamatikAppSettings

data class InlineCompletionConfig(
    val enabled: Boolean,
    val debounceMs: Int,
    val maxContextChars: Int,
    val maxTokens: Int,
)

object InlineCompletionSettings {
    fun current(): InlineCompletionConfig {
        val s = service<LlamatikAppSettings>().data()
        return InlineCompletionConfig(
            enabled = s.enableInlineCompletion,
            debounceMs = s.inlineCompletionDebounceMs.coerceIn(100, 5000),
            maxContextChars = s.inlineCompletionMaxContextChars.coerceIn(200, 4000),
            maxTokens = s.inlineCompletionMaxTokens.coerceIn(8, 256),
        )
    }
}
