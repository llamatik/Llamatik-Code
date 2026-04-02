package com.llamatik.teammate.tools

object ToolCallParser {
    private val toolPattern = Regex("<tool_call\\s+name=\\\"([^\\\"]+)\\\">(.*?)</tool_call>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val argPattern = Regex("<arg\\s+name=\\\"([^\\\"]+)\\\">(.*?)</arg>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val thinkPattern = Regex("<think>.*?</think>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val editProposalPattern = Regex(
        "<edit_proposal\\s+path=\\\"([^\\\"]+)\\\"\\s+summary=\\\"([^\\\"]*)\\\">\\s*<new_content><!\\[CDATA\\[.*?]]></new_content>\\s*</edit_proposal>",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )

    fun parse(text: String): List<ToolCall> {
        return toolPattern.findAll(text).map { match ->
            val name = match.groupValues[1].trim()
            val argsBody = match.groupValues[2]
            val args = argPattern.findAll(argsBody).associate { it.groupValues[1].trim() to it.groupValues[2].trim() }
            ToolCall(name, args)
        }.toList()
    }

    fun removeToolMarkup(text: String): String = text.replace(toolPattern, "").trim()

    /**
     * Strips all model-internal markup that should never be shown to the user:
     * <think> blocks, <tool_call> blocks, and <edit_proposal> XML (the proposal
     * is surfaced separately via the edit-proposal UI).
     *
     * If removing <think> blocks leaves nothing (the model only produced thinking,
     * no final answer), falls back to showing the think content itself so the user
     * sees some response rather than a blank panel.
     */
    fun cleanForDisplay(text: String): String {
        val withoutTools = text
            .replace(toolPattern, "")
            .replace(editProposalPattern, "")
        val withoutThink = withoutTools.replace(thinkPattern, "").trim()
        if (withoutThink.isNotEmpty()) return withoutThink
        // Only thinking, no answer — extract and show the think content as a fallback.
        val thinkContent = thinkPattern.findAll(withoutTools)
            .joinToString("\n\n") { match ->
                // Strip opening <think> and closing </think> tags (case-insensitive)
                match.value
                    .replaceFirst(Regex("^<think>", RegexOption.IGNORE_CASE), "")
                    .replaceFirst(Regex("</think>$", RegexOption.IGNORE_CASE), "")
                    .trim()
            }
        return thinkContent.ifEmpty { withoutTools }.trim()
    }
}
