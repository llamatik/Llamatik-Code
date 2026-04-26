package com.llamatik.teammate.tools

object ToolCallParser {
    private val toolPattern = Regex("<tool_call\\s+name=\\\"([^\\\"]+)\\\">(.*?)</tool_call>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val argPattern = Regex("<arg\\s+name=\\\"([^\\\"]+)\\\">(.*?)</arg>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val thinkPattern = Regex("<think>.*?</think>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val editProposalPattern = Regex(
        "<edit_proposal\\b[^>]*>.*?</edit_proposal>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    // Known tool names — used to detect plain-text fallback format (e.g. "read_file:\npath/to/file")
    private val knownTools = setOf(
        "list_files", "read_file", "search_code", "search_filenames",
        "get_current_file", "get_selected_text", "write_file", "create_file",
        "replace_in_file", "show_diff", "apply_patch",
    )

    fun stripThinking(text: String): String =
        text.replace(thinkPattern, "")
            .replace(Regex("<think>.*$", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            .trimStart()

    fun parse(text: String): List<ToolCall> {
        return toolPattern.findAll(text).map { match ->
            val name = match.groupValues[1].trim()
            val argsBody = match.groupValues[2]
            val args = argPattern.findAll(argsBody).associate { it.groupValues[1].trim() to it.groupValues[2].trim() }
            ToolCall(name, args)
        }.toList()
    }

    private val incompleteToolPattern = Regex(
        "<tool_call\\s+name=\"([^\"]+)\">(.*?)(?:</tool_call>|$)",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    /**
     * Like [parse] but also handles:
     * 1. Truncated XML tool calls missing the closing tag
     * 2. Plain-text fallback format the model sometimes uses:
     *    "read_file:\nsrc/Main.kt"  or  "write_file:\npath: ...\ncontent: ..."
     */
    fun parseIncludingIncomplete(text: String): List<ToolCall> {
        // incompleteToolPattern handles both complete (stops at </tool_call>) and truncated
        // (stops at end-of-string) calls, so all tool calls in the response are captured even
        // when the model truncates the last one or emits a mix of complete and partial calls.
        val calls = incompleteToolPattern.findAll(text).map { match ->
            val name = match.groupValues[1].trim()
            val argsBody = match.groupValues[2]
            val args = argPattern.findAll(argsBody).associate { it.groupValues[1].trim() to it.groupValues[2].trim() }
            ToolCall(name, args)
        }.toList()
        if (calls.isNotEmpty()) return calls

        // Plain-text fallback: "toolname:\narg_value" or "toolname:\nkey: value\nkey2: value2"
        return parsePlainText(text)
    }

    private fun parsePlainText(text: String): List<ToolCall> {
        val results = mutableListOf<ToolCall>()
        // Match "tool_name:\n..." or "[tool_name]:\n..." at start of line
        val plainPattern = Regex("""^\[?([\w_]+)\]?:\s*\n?([\s\S]*?)(?=^\[?[\w_]+\]?:\s*\n?|\z)""", setOf(RegexOption.MULTILINE))
        for (match in plainPattern.findAll(text)) {
            val name = match.groupValues[1].trim()
            if (name !in knownTools) continue
            val body = match.groupValues[2].trim()
            // Try to parse "key: value\nkey2: value2" named args first
            val namedArgs = Regex("""^(\w+):\s*(.+)$""", RegexOption.MULTILINE)
                .findAll(body)
                .associate { it.groupValues[1].trim() to it.groupValues[2].trim() }
            val args = if (namedArgs.isNotEmpty()) {
                namedArgs
            } else {
                // Single positional value — map to first expected arg name per tool
                val argName = when (name) {
                    "list_files", "read_file", "create_file", "write_file",
                    "show_diff", "apply_patch" -> "path"
                    "search_code", "search_filenames" -> "query"
                    "replace_in_file" -> "path"
                    else -> "path"
                }
                mapOf(argName to body)
            }
            results += ToolCall(name, args)
        }
        return results
    }

    fun removeToolMarkup(text: String): String = text.replace(toolPattern, "").trim()

    /**
     * Strips all model-internal markup that should never be shown to the user:
     * <think> blocks, <tool_call> blocks, <edit_proposal> XML, and plain-text tool calls.
     *
     * If removing markup leaves nothing, falls back to showing the think content so the
     * user sees some response rather than a blank panel.
     */
    fun cleanForDisplay(text: String): String {
        val hasEditProposals = editProposalPattern.containsMatchIn(text)
        val withoutTools = text
            .replace(toolPattern, "")
            .replace(editProposalPattern, "")
        // Remove complete <think>…</think> blocks
        val withoutComplete = withoutTools.replace(thinkPattern, "")
        // Remove any unclosed or partial block that reaches end-of-string
        val toolNamesAlt = knownTools.joinToString("|")
        val withoutThink = withoutComplete
            .replace(Regex("<think>.*$", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            .replace(Regex("<edit_proposal\\b.*$", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<tool_call\\b.*$", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<result\\s+tool=.*$", RegexOption.DOT_MATCHES_ALL), "")
            // Strip plain-text tool call lines: "read_file:\n..." or "[read_file]:\n..."
            .replace(Regex("""^\[?($toolNamesAlt)\]?:\s*\n?.*""", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)), "")
            .trim()
        if (withoutThink.isNotEmpty()) return withoutThink
        // Edit proposals surface via the edit UI.
        if (hasEditProposals) return ""
        // Only thinking — show the think content as a fallback.
        val thinkContent = thinkPattern.findAll(withoutTools)
            .joinToString("\n\n") { match ->
                match.value
                    .replaceFirst(Regex("^<think>", RegexOption.IGNORE_CASE), "")
                    .replaceFirst(Regex("</think>$", RegexOption.IGNORE_CASE), "")
                    .trim()
            }
        if (thinkContent.isNotEmpty()) return thinkContent
        // Unclosed <think> block — return content inside, never the raw tag.
        val unclosedContent = Regex("<think>(.*)", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(withoutComplete)?.groupValues?.get(1)?.trim() ?: ""
        return unclosedContent.ifEmpty { withoutThink }.trim()
    }
}
