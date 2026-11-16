package com.llamatik.agent.core.agent

import com.llamatik.agent.core.tools.ToolCall
import com.llamatik.agent.core.tools.ToolExecutionContext
import com.llamatik.agent.core.tools.ToolResult

/** Runs the tools the model may call. The IDE's registry also exposes dynamically registered MCP tools. */
interface ToolExecutor {
    fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult

    /** Compact `a | b | c` list of tool names for the system prompt. */
    fun listToolNames(): String

    /** Names of tools registered at runtime (MCP bridges); accepted in addition to [ToolPolicy.knownToolNames]. */
    fun dynamicToolNames(): Set<String>
}

/**
 * Which tool names the loop accepts from the model and how it classifies them.
 * Hallucinated names (not in [knownToolNames]) are dropped so they cannot loop forever.
 */
data class ToolPolicy(
    val knownToolNames: Set<String>,
    /** Tools that only read; used for duplicate-call suppression and write-pass escalation. */
    val readOnlyToolNames: Set<String>,
    /** Tools whose results become edit proposals. */
    val editToolNames: Set<String> = DEFAULT_EDIT_TOOLS,
) {
    companion object {
        val DEFAULT_EDIT_TOOLS: Set<String> = setOf(TOOL_WRITE_FILE, TOOL_REPLACE_IN_FILE, TOOL_CREATE_FILE)

        /** The tools available in every host (see `core.tools.builtin`) plus the editor-context pair. */
        val PORTABLE = ToolPolicy(
            knownToolNames = setOf(
                TOOL_LIST_FILES, TOOL_READ_FILE, TOOL_SEARCH_CODE, TOOL_SEARCH_FILENAMES,
                TOOL_GET_CURRENT_FILE, TOOL_GET_SELECTED_TEXT, TOOL_WRITE_FILE, TOOL_CREATE_FILE,
                TOOL_REPLACE_IN_FILE, "show_diff", "apply_patch",
                "git_status", "git_diff", "git_changed_files", "git_branch", "git_commit_message_suggestion",
                "terminal_allowed_commands", "terminal_run", "terminal_last_output",
            ),
            readOnlyToolNames = setOf(
                TOOL_LIST_FILES, TOOL_READ_FILE, TOOL_SEARCH_CODE, TOOL_SEARCH_FILENAMES,
                TOOL_GET_CURRENT_FILE, TOOL_GET_SELECTED_TEXT,
                "git_status", "git_diff", "git_changed_files", "git_branch", "git_commit_message_suggestion",
                "terminal_allowed_commands", "terminal_last_output",
            ),
        )
    }
}

/**
 * Host-specific text and project knowledge that goes into prompts. Implemented by the IDE plugin
 * (project name, user rules, skills, memory) and by headless hosts.
 */
interface PromptSources {
    fun projectName(): String
    fun pinnedInstructions(): String

    /** Identity line opening the agent system prompt. */
    fun agentIdentity(): String

    /** Identity line opening the chat (Ask) system prompt. */
    fun chatIdentity(): String

    fun skillsPromptAddition(): String
    fun rulesPromptBlock(): String
    fun memoryPromptBlock(): String

    /** True when the user selected the aggressive execution mode. */
    fun aggressiveMode(): Boolean

    /** A host with nothing to add. */
    object Empty : PromptSources {
        override fun projectName(): String = ""
        override fun pinnedInstructions(): String = ""
        override fun agentIdentity(): String = "You are Llamatik Code, an autonomous local development agent. You plan, patch, review, and verify code locally — all source code stays on the user's machine."
        override fun chatIdentity(): String = "You are Llamatik Code, a local AI coding assistant."
        override fun skillsPromptAddition(): String = ""
        override fun rulesPromptBlock(): String = ""
        override fun memoryPromptBlock(): String = ""
        override fun aggressiveMode(): Boolean = false
    }
}
