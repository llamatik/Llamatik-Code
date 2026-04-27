package com.llamatik.teammate.agent

/**
 * Pure validation logic for file-write proposals produced by tool calls.
 *
 * Only hard-rejects proposals that are structurally broken or where the model
 * hallucinated tool-call syntax into file content. Quality judgements (stub code,
 * TODOs, short content) are intentionally NOT rejected here — those are surfaced
 * as proposals for the user to review and accept or reject manually.
 */
object ProposalValidator {

    sealed class Result {
        object Valid : Result()
        data class Invalid(val reason: String) : Result()
    }

    /**
     * Validates a [path] + [newContent] pair from a write_file / create_file tool result.
     *
     * Checks in order (fast-fails on first failure):
     * 1. Path must be non-blank
     * 2. Path must contain a '.' (looks like a filename)
     * 3. Path must not contain newlines (model mixed content into path argument)
     * 4. Content must be non-blank
     * 5. Content must not contain tool-invocation patterns (model confused tool syntax with code)
     */
    fun validate(path: String, newContent: String): Result {
        if (path.isBlank())
            return Result.Invalid("empty path")
        if (!path.contains('.'))
            return Result.Invalid("path '$path' has no file extension")
        if (path.contains('\n'))
            return Result.Invalid("path contains newline — model wrote code into path argument")
        if (newContent.isBlank())
            return Result.Invalid("empty content")
        if (TOOL_INVOCATION_PATTERN.containsMatchIn(newContent))
            return Result.Invalid("content contains tool invocation syntax — model confused tool calls with code")
        return Result.Valid
    }

    // Detects model-hallucinated tool invocations inside file content, e.g.:
    //   autorun(search_code("..."))  or  list_files("src")
    private val TOOL_INVOCATION_PATTERN = Regex(
        """\b(list_files|read_file|search_code|search_filenames|write_file|create_file|replace_in_file|show_diff|apply_patch|get_current_file|get_selected_text)\s*\(""",
        RegexOption.IGNORE_CASE,
    )
}
