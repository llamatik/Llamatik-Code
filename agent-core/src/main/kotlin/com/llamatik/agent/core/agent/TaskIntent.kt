package com.llamatik.agent.core.agent

/**
 * Guesses whether a prompt asks the agent to change files or only to answer a question.
 *
 * The loop uses it to decide whether a prose reply is the answer (a question) or a sign the model
 * failed to make the edit it was asked for (an edit request), which deserves a forced write pass.
 */
object TaskIntent {
    private val EDIT_VERBS = listOf(
        "fix", "add", "change", "update", "modify", "edit", "implement", "refactor", "rewrite", "rename",
        "replace", "remove", "delete", "create", "write", "generate", "make", "convert", "migrate", "patch",
        "improve", "optimize", "optimise", "extract", "move", "insert", "apply", "rework", "port", "document",
    )
    private val EDIT_REGEX = Regex("""\b(${EDIT_VERBS.joinToString("|")})\b""", RegexOption.IGNORE_CASE)

    /** A prompt that opens like a question or an explanation request is a question even if it mentions "fix". */
    private val QUESTION_START = Regex(
        """^\W*(what|why|how|where|which|who|when|is|are|does|do|explain|describe|summari[sz]e|review|analy[sz]e|list|show|find|tell|compare)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun wantsEdit(prompt: String): Boolean {
        val text = prompt.trim()
        if (QUESTION_START.containsMatchIn(text)) return false
        return EDIT_REGEX.containsMatchIn(text)
    }
}
