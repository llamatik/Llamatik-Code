package com.llamatik.teammate.completion

import com.intellij.openapi.editor.Editor

/**
 * Collects prefix/suffix context around the caret for inline completion prompts.
 * Uses current file only — never reads other project files (privacy, performance).
 */
object InlineCompletionContextCollector {

    data class CompletionContext(
        val prefix: String,
        val suffix: String,
        val fileExtension: String,
        val lineNumber: Int,
    )

    fun collect(editor: Editor, maxContextChars: Int): CompletionContext? {
        val document = editor.document
        val caretOffset = editor.caretModel.offset
        if (caretOffset < 0 || caretOffset > document.textLength) return null

        val halfBudget = maxContextChars / 2
        val rawPrefix = document.text.substring(0, caretOffset)
        val rawSuffix = document.text.substring(caretOffset)

        val prefix = rawPrefix.takeLast(halfBudget)
        val suffix = rawSuffix.take(halfBudget)

        val vFile = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(document)
        val ext = vFile?.extension ?: ""
        val lineNumber = document.getLineNumber(caretOffset) + 1

        return CompletionContext(prefix, suffix, ext, lineNumber)
    }
}
