package com.llamatik.agent.core.agent

import com.llamatik.agent.core.host.AgentEnvironment
import com.llamatik.agent.core.model.ChatMessage
import com.llamatik.agent.core.model.ChatRole

/** Builds the system prompts and context hints. Extracted unchanged from AgentOrchestrator. */
class PromptBuilder(
    private val env: AgentEnvironment,
    private val tools: ToolExecutor,
    private val sources: PromptSources,
) {
    /**
     * Minimal system prompt for the second (tool-results) pass.
     * Must stay small — the second pass already carries tool results + user prompt,
     * and the entire prompt must fit within n_batch tokens (≈ batchSize × 3 chars).
     * Target: ≤ 500 chars so tool results get the most budget.
     */
    fun buildSecondPassSystemPrompt(): String = buildString {
        appendLine(sources.agentIdentity())
        val lang = detectProjectLanguage()
        if (lang != null) appendLine("Language: $lang. Write ALL code in $lang only.")
        appendLine("Respond with tool calls OR a plain-text answer. Optional: ${THINK_OPEN}reasoning${THINK_CLOSE} then tool XML or answer text.")
        appendLine("Tool format: <tool_call name=\"search_code\"><arg name=\"query\">ClassName</arg></tool_call>")
        appendLine("<tool_call name=\"write_file\"><arg name=\"path\">p</arg><arg name=\"content\">c</arg></tool_call>")
        appendLine("Use ONLY paths from the results. Do NOT read the same file twice. Write immediately after reading.")
        appendLine("Tools: ${tools.listToolNames()}")
    }

    fun detectProjectLanguage(): String? {
        // Check open file first (most reliable signal)
        val openExt = env.editor?.currentFile()?.substringAfterLast('.', "")?.lowercase()
        val fromOpenFile = when (openExt) {
            "kt", "kts" -> "Kotlin"
            "java" -> "Java"
            "py" -> "Python"
            "ts", "tsx" -> "TypeScript"
            "js", "jsx" -> "JavaScript"
            "swift" -> "Swift"
            "go" -> "Go"
            "rs" -> "Rust"
            "cpp", "cc", "cxx", "h", "hpp" -> "C++"
            "c" -> "C"
            "cs" -> "C#"
            "rb" -> "Ruby"
            else -> null
        }
        if (fromOpenFile != null) return fromOpenFile

        // Fallback: detect from project build files in the root
        val projectFiles = env.files.list(null).map { it.lowercase() }
        return when {
            projectFiles.any { it == "build.gradle.kts" || it.endsWith("/build.gradle.kts") } -> "Kotlin"
            projectFiles.any { it == "cargo.toml" || it.endsWith("/cargo.toml") } -> "Rust"
            projectFiles.any { it == "go.mod" || it.endsWith("/go.mod") } -> "Go"
            projectFiles.any { it == "package.json" || it.endsWith("/package.json") } -> "TypeScript"
            projectFiles.any { it == "build.gradle" || it.endsWith("/build.gradle") } -> "Java"
            projectFiles.any { it == "pom.xml" || it.endsWith("/pom.xml") } -> "Java"
            projectFiles.any { it == "setup.py" || it == "pyproject.toml" } -> "Python"
            else -> null
        }
    }

    fun buildContextHint(): String = buildString {
        appendLine("Project context:")
        appendLine("- project: ${sources.projectName()}")
        val lang = detectProjectLanguage()
        if (lang != null) appendLine("- language: $lang — ALL generated code MUST be written in $lang")
        // Open file is intentionally omitted — the model infers it from @{file} attachments or
        // the get_current_file tool. Injecting it here causes the model to treat every unrelated
        // query (e.g. "benchmark the model") as a request about the open file.
        val selected = env.editor?.selectedText()?.take(SELECTED_TEXT_MAX_CHARS)
        if (!selected.isNullOrBlank()) appendLine("- selected text: ${selected}")
        val pinned = sources.pinnedInstructions()
        if (pinned.isNotBlank()) appendLine("- instructions: ${pinned.take(PINNED_INSTRUCTIONS_MAX_CHARS)}")
    }

    /**
     * Returns the most recent messages that fit within [budgetChars] total content length,
     * always keeping at least the last user message.
     */
    fun fitMessagesInBudget(messages: List<ChatMessage>, budgetChars: Int): List<ChatMessage> {
        if (messages.isEmpty()) return emptyList()
        val recent = messages.takeLast(MAX_HISTORY_MESSAGES)
        var used = 0
        val result = mutableListOf<ChatMessage>()
        for (msg in recent.asReversed()) {
            // Strip the embedded file context sentinel before sending to the model — the file
            // content is already in the current turn's system prompt and must not be duplicated
            // through history turns (it would bloat the context significantly).
            val stripped = if (msg.role == ChatRole.USER && msg.content.contains(HISTORY_FILE_SENTINEL)) {
                msg.copy(content = msg.content.substringBefore(HISTORY_FILE_SENTINEL).trimEnd())
            } else msg
            val len = stripped.content.length
            if (used + len > budgetChars && result.isNotEmpty()) break
            result.add(0, stripped)
            used += len
        }
        return result
    }

    fun buildSystemPrompt(): String = buildString {
        appendLine(sources.agentIdentity())
        val lang = detectProjectLanguage()
        if (lang != null) {
            appendLine("LANGUAGE: This is a $lang project. You MUST write ALL code exclusively in $lang.")
            appendLine("NEVER output Python, Java, or any other language. Only $lang.")
        }
        appendLine("OUTPUT FORMAT — strictly follow this every response:")
        appendLine("  Optional: <think>brief reasoning</think>")
        appendLine("  Then IMMEDIATELY one of:")
        appendLine("    A) One or more tool calls (no prose before or after the XML)")
        appendLine("    B) write_file/create_file tool calls to apply changes")
        appendLine("    C) A plain-text answer (only when no file changes are needed)")
        appendLine("NEVER write prose like 'I will now...' or 'I'll start by...' — go straight to the tool call or answer.")
        appendLine("Use ONLY file paths that exist in the project. Never invent paths.")
        // Compact tool list keeps the system prompt within n_batch budget (models often use n_batch=2048).
        // The model infers each tool's purpose from its name; the format example shows the XML syntax.
        appendLine("Tools: ${tools.listToolNames()}")
        appendLine("Tool call format: <tool_call name=\"TOOL\"><arg name=\"ARG\">VALUE</arg></tool_call>")
        appendLine("Examples:")
        appendLine("  <tool_call name=\"search_code\"><arg name=\"query\">ClassName</arg></tool_call>")
        appendLine("  <tool_call name=\"list_files\"><arg name=\"path\">src</arg></tool_call>")
        appendLine("  <tool_call name=\"read_file\"><arg name=\"path\">src/Main.kt</arg></tool_call>")
        appendLine("  <tool_call name=\"git_status\"></tool_call>")
        appendLine("  <tool_call name=\"write_file\"><arg name=\"path\">src/Main.kt</arg><arg name=\"content\">…</arg></tool_call>")
        val skillsAddition = sources.skillsPromptAddition()
        if (skillsAddition.isNotBlank()) append(skillsAddition.take(SKILLS_PROMPT_MAX_CHARS))
        val rulesBlock = sources.rulesPromptBlock()
        if (rulesBlock.isNotBlank()) { appendLine(); append(rulesBlock) }
        val memoryBlock = sources.memoryPromptBlock()
        if (memoryBlock.isNotBlank()) { appendLine(); append(memoryBlock) }
        if (sources.aggressiveMode()) {
            appendLine()
            append(AgentFallbackPolicy.aggressiveModeSystemPromptAddition().take(600))
        }
    }

    fun isEnterpriseMission(prompt: String): Boolean {
        val lower = prompt.lowercase()
        return ENTERPRISE_MODE_TRIGGERS.any { lower.contains(it) }
    }

    fun buildEnterpriseMissionSystemPromptAddition(): String = buildString {
        appendLine()
        appendLine("=== Offline Enterprise Pair Programmer Mode ===")
        appendLine("Enforce this workflow on EVERY response:")
        appendLine("1. Inspect before editing: use read_file, git_status, diagnostics_run first.")
        appendLine("2. Call enterprise_mission_create at the start of any new mission.")
        appendLine("3. Create a plan and explain risks BEFORE any file changes.")
        appendLine("4. Use small, reviewable patches (replace_in_file over write_file when possible).")
        appendLine("5. Show diff using show_diff after every file change.")
        appendLine("6. Verify using run_config_last_output, terminal_run, or diagnostics_run.")
        appendLine("7. Call enterprise_mission_report as the final step — include rollback instructions.")
        appendLine("8. External knowledge is DISABLED unless explicitly allowed.")
        appendLine("9. Preserve ALL existing behavior unless the mission explicitly requires a change.")
        appendLine("10. Never skip the plan step. Never mutate without prior context gathering.")
    }

    /** System prompt for the Ask tab: no tools, optionally the open file and selection. */
    fun buildChatSystemPrompt(fileContext: String): String = buildString {
        appendLine(sources.chatIdentity())
        appendLine("Answer the user's question clearly and concisely. You may include code snippets.")
        appendLine("Do not use tool calls or edit proposals — only provide text answers.")
        val currentFilePath = env.editor?.currentFile()
        if (currentFilePath != null) {
            appendLine("Open file: $currentFilePath")
            val content = runCatching { env.files.read(currentFilePath) }.getOrNull()
            if (!content.isNullOrBlank()) {
                appendLine("File content:")
                appendLine(content.take(OPEN_FILE_CONTENT_MAX_CHARS))
            }
        }
        val selected = env.editor?.selectedText()?.take(SELECTED_TEXT_MAX_CHARS)
        if (!selected.isNullOrBlank()) appendLine("Selected text: $selected")
        val pinned = sources.pinnedInstructions()
        if (pinned.isNotBlank()) appendLine("Project instructions: ${pinned.take(PINNED_INSTRUCTIONS_MAX_CHARS)}")
        if (fileContext.isNotBlank()) {
            appendLine("=== Attached files ===")
            append(fileContext.take(FILE_CONTEXT_MAX_CHARS))
            appendLine("=== End attached files ===")
        }
    }
}
