package com.llamatik.agent.core.agent

import com.llamatik.agent.core.host.AgentEnvironment

/** Resolves file attachments in a user prompt: `@{path}`, "the current file" and build-error URIs. */
class FileReferences(private val env: AgentEnvironment) {
    /**
     * Finds all `@{path}` tokens in [prompt] and reads each referenced file.
     *
     * Returns a [Pair] of:
     * - `first`  — the clean user instruction with `@{…}` tokens replaced by bare filenames
     * - `second` — fenced file contents to inject as a separate SYSTEM message (empty string if no refs)
     *
     * Keeping them separate ensures the user instruction is never truncated by the char budget.
     */
    fun resolveFileReferences(prompt: String, attachmentMax: Int = FILE_ATTACHMENT_MAX_CHARS): Pair<String, String> {
        val tokenRegex = Regex("""@\{([^}]+)}""")
        val matches = tokenRegex.findAll(prompt).toList()
        if (matches.isEmpty()) return Pair(prompt, "")

        val attachments = StringBuilder()
        matches.forEach { match ->
            val path = match.groupValues[1].trim()
            val content = runCatching { env.files.read(path) }.getOrNull()
            if (!content.isNullOrBlank()) {
                attachments.appendLine("$FILE_SECTION_PREFIX$path")
                attachments.appendLine("```")
                attachments.appendLine(content.take(attachmentMax))
                attachments.appendLine("```")
                attachments.appendLine()
            } else {
                attachments.appendLine("### File: $path")
                attachments.appendLine("(File not found or unreadable — do NOT search for it; ask the user to verify the path.)")
                attachments.appendLine()
            }
        }

        // Replace @{path} tokens with just the filename so the instruction reads naturally
        val cleanInstruction = tokenRegex.replace(prompt) { match ->
            match.groupValues[1].substringAfterLast('/')
        }

        return Pair(cleanInstruction, attachments.trimEnd().toString())
    }

    /**
     * Rewrites vague "current file" / "this file" / "open file" phrases in [prompt] to the
     * actual filename, and returns the file content as fenced context.
     *
     * This prevents small models from using the instruction text as a search_code query
     * instead of reading the file the user actually has open.
     *
     * Returns (rewritten prompt, fenced file content string).
     * If no current file is open, returns the original prompt unchanged with empty context.
     */
    fun resolveCurrentFileReference(prompt: String, attachmentMax: Int = FILE_ATTACHMENT_MAX_CHARS): Pair<String, String> {
        val currentFilePath = env.editor?.currentFile() ?: return Pair(prompt, "")
        val fileName = currentFilePath.substringAfterLast('/')

        val hasVagueRef = CURRENT_FILE_PHRASES.any { prompt.contains(it, ignoreCase = true) }
        if (!hasVagueRef) return Pair(prompt, "")

        val content = runCatching { env.files.read(currentFilePath) }.getOrNull()
            ?: return Pair(prompt, "")

        // Replace vague references with the concrete filename
        var rewritten = prompt
        for (phrase in CURRENT_FILE_PHRASES) {
            rewritten = rewritten.replace(phrase, fileName, ignoreCase = true)
        }

        val context = buildString {
            appendLine("$FILE_SECTION_PREFIX$currentFilePath")
            appendLine("```")
            appendLine(content.take(attachmentMax))
            appendLine("```")
        }.trimEnd()

        return Pair(rewritten, context)
    }

    /**
     * Detects Kotlin/Gradle build error lines containing `file:///absolute/path.kt:line:col`
     * URIs, reads those files, and returns them as fenced attachments — identical to @{path}
     * attachments but triggered automatically from build error output.
     *
     * Returns (fenced file content string, list of project-relative paths successfully read).
     */
    fun resolveBuildErrorReferences(prompt: String, attachmentMax: Int = FILE_ATTACHMENT_MAX_CHARS): Pair<String, List<String>> {
        val uriRegex = Regex("""file:///([^\s:]+\.(?:kt|java|swift|py|ts|js|go|rs|cpp|c|cs|rb|gradle|kts))""")
        val matches = uriRegex.findAll(prompt).toList()
        if (matches.isEmpty()) return Pair("", emptyList())

        val projectRoot = env.files.rootPath.trimEnd('/')
        val attachments = StringBuilder()
        val resolvedPaths = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        matches.forEach { match ->
            val absolutePath = match.groupValues[1]
            if (!seen.add(absolutePath)) return@forEach

            val relPath = if (projectRoot.isNotBlank() && absolutePath.startsWith("$projectRoot/")) {
                absolutePath.removePrefix("$projectRoot/")
            } else {
                absolutePath
            }

            val content = runCatching { env.files.read(absolutePath) }.getOrNull()
                ?: runCatching { env.files.read(relPath) }.getOrNull()
                ?: return@forEach

            resolvedPaths += relPath
            attachments.appendLine("$FILE_SECTION_PREFIX$relPath")
            attachments.appendLine("```")
            attachments.appendLine(content.take(attachmentMax))
            attachments.appendLine("```")
            attachments.appendLine()
        }

        return Pair(attachments.trimEnd().toString(), resolvedPaths)
    }
}
