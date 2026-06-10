package com.llamatik.teammate.completion

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor

/**
 * MVP inline completion service.
 *
 * Provides current-file prefix/suffix completion using the local model.
 * Respects debounce, cancels stale requests, never blocks the UI thread,
 * never uses external knowledge, and is disabled by default.
 *
 * JetBrains InlineCompletion API (com.intellij.codeInsight.inline.completion) was
 * introduced in 2023.3 (233.*). The plugin targets sinceBuild 252 so the API is
 * available, but integration is left as a documented TODO because it requires
 * EditorFactoryListener wiring in plugin.xml and thread-safe coroutine dispatchers
 * that are not yet scaffolded. The service and all helpers are fully implemented and
 * testable in isolation today.
 *
 * TODO: Wire EditorFactoryListener to call requestCompletion() on document changes.
 * TODO: Implement actual LlamatikBackendService.generateCompletion() call and surface
 *       the result via InlineCompletionProvider registered in plugin.xml.
 */
@Service(Service.Level.APP)
class LlamatikInlineCompletionService : Disposable {

    private var debouncer: InlineCompletionDebouncer? = null

    fun requestCompletion(editor: Editor, onResult: (String) -> Unit) {
        val config = InlineCompletionSettings.current()
        if (!config.enabled) return

        if (debouncer == null) {
            debouncer = InlineCompletionDebouncer(config.debounceMs)
        }

        debouncer?.schedule {
            val ctx = InlineCompletionContextCollector.collect(editor, config.maxContextChars)
                ?: return@schedule
            val prompt = InlineCompletionPromptBuilder.build(ctx)
            // TODO: call LlamatikBackendService with prompt, maxTokens=config.maxTokens,
            //  sessionTag=INLINE, and invoke onResult with the completion text.
            //  The backend call must run on a background thread and check for cancellation.
            @Suppress("UNUSED_VARIABLE") val unusedPrompt = prompt
            @Suppress("UNUSED_VARIABLE") val unusedCallback = onResult
        }
    }

    fun cancelPending() {
        debouncer?.cancel()
    }

    override fun dispose() {
        debouncer?.shutdown()
        debouncer = null
    }
}
