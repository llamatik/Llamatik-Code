package com.llamatik.agent.core.tools.builtin

import com.llamatik.agent.core.agent.ToolExecutor
import com.llamatik.agent.core.tools.LlamatikTool
import com.llamatik.agent.core.tools.ToolCall
import com.llamatik.agent.core.tools.ToolExecutionContext
import com.llamatik.agent.core.tools.ToolResult

/** The tools every host provides (file, edit, git and terminal tools), in prompt order. */
object BuiltinTools {
    fun all(): List<LlamatikTool> = listOf(
        ListFilesTool(), ReadFileTool(), SearchCodeTool(), SearchFilenamesTool(),
        WriteFileTool(), CreateFileTool(), ReplaceInFileTool(), ShowDiffTool(), ApplyPatchTool(),
        GitStatusTool(), GitDiffTool(), GitChangedFilesTool(), GitBranchTool(), GitCommitMessageSuggestionTool(),
        TerminalAllowedCommandsTool(), TerminalRunTool(), TerminalLastOutputTool(),
    )
}

/**
 * A [ToolExecutor] over a fixed list of tools, for hosts without a plugin registry (CLI, benchmarks).
 * A tool that throws is reported as a failed [ToolResult], like the IDE's registry does.
 */
class ToolListExecutor(
    tools: List<LlamatikTool> = BuiltinTools.all(),
    private val dynamicNames: Set<String> = emptySet(),
) : ToolExecutor {
    private val byName = tools.associateBy { it.name }

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val tool = byName[call.name] ?: return ToolResult(call.name, false, "Unknown tool: ${call.name}")
        return runCatching { tool.execute(call, context) }
            .getOrElse { ToolResult(call.name, false, it.message ?: "Tool execution failed") }
    }

    override fun listToolNames(): String = byName.keys.joinToString(" | ")
    override fun dynamicToolNames(): Set<String> = dynamicNames
}
