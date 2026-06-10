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

    // Model-specific stop/control tokens that must never appear in output.
    // <wait[^>]*>…</wait…> covers both <wait_for_approval> and <wait for user...> variants.
    private val modelStopTokenPattern = Regex(
        "<\\|im_end\\|>|<\\|endoftext\\|>|<\\|end\\|>|<wait[^>]*>.*?</wait[^>]*>|<wait[^>]*>.*$",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    fun stripThinking(text: String): String =
        text.replace(thinkPattern, "")
            .replace(Regex("<think>.*$", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            .replace(Regex("</think>", RegexOption.IGNORE_CASE), "")
            .replace(modelStopTokenPattern, "")
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
        val plainCalls = parsePlainText(text)
        if (plainCalls.isNotEmpty()) return plainCalls

        // Python-style fallback: toolname("arg") or toolname("path", "content")
        val pythonCalls = parsePythonStyle(text)
        if (pythonCalls.isNotEmpty()) return pythonCalls

        // Space-separated fallback: "toolname arg" on a single line (no colon, no parens)
        return parseSpaceSeparated(text)
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

    private fun parseSpaceSeparated(text: String): List<ToolCall> {
        val results = mutableListOf<ToolCall>()
        // Match "toolname arg" at start of line — no colon, no parens, single space-separated arg.
        // Stops at end of line to avoid consuming multi-line content.
        val spacePattern = Regex("""^([\w_]+)\s+(\S.*)$""", RegexOption.MULTILINE)
        for (match in spacePattern.findAll(text)) {
            val name = match.groupValues[1].trim()
            if (name !in knownTools) continue
            val argValue = match.groupValues[2].trim()
            val argName = when (name) {
                "list_files", "read_file", "create_file", "write_file",
                "show_diff", "apply_patch" -> "path"
                "search_code", "search_filenames" -> "query"
                "replace_in_file" -> "path"
                else -> "path"
            }
            results += ToolCall(name, mapOf(argName to argValue))
        }
        return results
    }

    private fun parsePythonStyle(text: String): List<ToolCall> {
        val results = mutableListOf<ToolCall>()
        // Match: toolname("arg1") or toolname("arg1", "arg2") at start of line
        val pythonPattern = Regex("""^([\w_]+)\s*\(\s*([\s\S]*?)\s*\)\s*$""", setOf(RegexOption.MULTILINE))
        for (match in pythonPattern.findAll(text)) {
            val name = match.groupValues[1].trim()
            if (name !in knownTools) continue
            val argsRaw = match.groupValues[2]
            // Split on comma, stripping surrounding quotes
            val argValues = argsRaw.split(Regex(""",\s*(?=(?:[^"]*"[^"]*")*[^"]*$)"""))
                .map { it.trim().removeSurrounding("\"").removeSurrounding("'") }
                .filter { it.isNotEmpty() }
            val argNames = when (name) {
                "write_file", "replace_in_file" -> listOf("path", "content")
                "create_file" -> listOf("path", "content")
                "read_file", "show_diff", "apply_patch" -> listOf("path")
                "list_files" -> listOf("path")
                "search_code", "search_filenames" -> listOf("query")
                else -> listOf("path")
            }
            val args = argNames.zip(argValues).toMap()
            if (args.isNotEmpty()) results += ToolCall(name, args)
        }
        return results
    }

    fun removeToolMarkup(text: String): String = text.replace(toolPattern, "").trim()

    /**
     * Strips a verbatim echo of the user's prompt from the start of the response.
     * Base models (e.g. Mellum-4b-base) often repeat the user message before generating.
     * Safe for instruction models — they never start their response with the user's message.
     */
    fun stripLeadingEcho(text: String, userPrompt: String): String {
        val prefix = userPrompt.trim().take(60)
        if (prefix.length < 15) return text
        val trimmed = text.trimStart()
        return if (trimmed.startsWith(prefix, ignoreCase = true)) {
            trimmed.substring(prefix.length).trimStart()
        } else text
    }

    /**
     * Strips verbatim file-context content echoed by base models near the start of a response.
     * Scans each line of [contextChunk] for ≥20-char probes; if found within the first 300 chars
     * of [text], truncates from that position. Safe for instruction models — they do not echo
     * injected context verbatim at the very beginning of their reply.
     */
    fun stripContextEcho(text: String, contextChunk: String): String {
        if (contextChunk.length < 50) return text
        for (line in contextChunk.lines()) {
            val probe = line.trim().take(50)
            if (probe.length < 20) continue
            val idx = text.indexOf(probe)
            if (idx in 0..300) return text.substring(0, idx).trim()
        }
        return text
    }

    /**
     * Strips content that base models echo back verbatim from the injected round-N user prompt.
     * Only call this for rounds > 1 where the prompt contains the sentinel "Tool results:" header.
     * Safe to call on any model — if the text doesn't start with or contain the preamble nothing changes.
     */
    fun stripPromptEcho(text: String, taskPrefix: String): String {
        // The echoed block always starts with "Task: <taskPrefix>" and ends after "Tool results:\n".
        // We strip everything up to and including that sentinel so only the model's new output remains.
        val sentinel = "Tool results:"
        val sentinelIdx = text.indexOf(sentinel)
        if (sentinelIdx < 0) return text
        // Only strip if the text before the sentinel looks like our injected preamble (contains taskPrefix)
        val before = text.substring(0, sentinelIdx)
        if (!before.contains(taskPrefix.take(40), ignoreCase = true)) return text
        return text.substring(sentinelIdx + sentinel.length).trimStart('\n', '\r')
    }

    /**
     * Strips all model-internal markup that should never be shown to the user:
     * <think> blocks, <tool_call> blocks, <edit_proposal> XML, and plain-text tool calls.
     *
     * If removing markup leaves nothing and [allowThinkFallback] is true, falls back to showing
     * the think content so the user sees some response rather than a blank panel.
     * Pass [allowThinkFallback] = false when the caller already has edit proposals to display —
     * in that case a blank answer is correct (the proposal cards are the meaningful response).
     */
    fun cleanForDisplay(text: String, allowThinkFallback: Boolean = true): String {
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
            // Strip orphan closing tags left when the opening tag was already stripped above
            .replace(Regex("</arg>|</tool_call>|</edit_proposal>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("</think>", RegexOption.IGNORE_CASE), "")
            .replace(modelStopTokenPattern, "")
            // Strip stray model-hallucinated closing tags that should never appear in output
            .replace(Regex("</?(answer|result|results|action)>", RegexOption.IGNORE_CASE), "")
            // Strip plain-text tool call lines: "read_file:\n..." or "[read_file]:\n..."
            .replace(Regex("""^\[?($toolNamesAlt)\]?:\s*\n?.*""", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)), "")
            // Strip Python-style function calls: "read_file("...")" or "write_file("path", "...")"
            .replace(Regex("""^($toolNamesAlt)\s*\(.*""", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)), "")
            .trim()
        if (withoutThink.isNotEmpty()) return withoutThink
        // Edit proposals surface via the edit UI.
        if (hasEditProposals) return ""
        if (!allowThinkFallback) return ""
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
