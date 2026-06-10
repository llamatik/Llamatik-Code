package com.llamatik.teammate.completion

/**
 * Builds the fill-in-the-middle prompt for inline completions.
 * Uses prefix/suffix framing — never includes external knowledge.
 */
object InlineCompletionPromptBuilder {

    private const val PREFIX_TOKEN = "<fim_prefix>"
    private const val SUFFIX_TOKEN = "<fim_suffix>"
    private const val MIDDLE_TOKEN = "<fim_middle>"

    fun build(ctx: InlineCompletionContextCollector.CompletionContext): String {
        // FIM (fill-in-the-middle) format supported by most code models
        return "$PREFIX_TOKEN${ctx.prefix}$SUFFIX_TOKEN${ctx.suffix}$MIDDLE_TOKEN"
    }

    fun buildFallback(ctx: InlineCompletionContextCollector.CompletionContext): String {
        // Plain prefix prompt for models that don't support FIM
        val langHint = languageHint(ctx.fileExtension)
        return buildString {
            if (langHint != null) appendLine("// Language: $langHint")
            append(ctx.prefix)
        }
    }

    private fun languageHint(ext: String): String? = when (ext.lowercase()) {
        "kt", "kts" -> "Kotlin"
        "java" -> "Java"
        "py" -> "Python"
        "ts", "tsx" -> "TypeScript"
        "js", "jsx" -> "JavaScript"
        "swift" -> "Swift"
        "go" -> "Go"
        "rs" -> "Rust"
        "cpp", "cc", "cxx", "h", "hpp" -> "C++"
        "cs" -> "C#"
        "rb" -> "Ruby"
        else -> null
    }
}
