package com.llamatik.teammate.tools

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.tools.impl.ApplyPatchTool
import com.llamatik.teammate.tools.impl.CodebaseRulesExplainTool
import com.llamatik.teammate.tools.impl.CodebaseRulesGetTool
import com.llamatik.teammate.tools.impl.CodebaseRulesRefreshTool
import com.llamatik.teammate.tools.impl.CreateFileTool
import com.llamatik.teammate.tools.impl.GetCurrentFileTool
import com.llamatik.teammate.tools.impl.GetSelectedTextTool
import com.llamatik.teammate.tools.impl.GitBranchTool
import com.llamatik.teammate.tools.impl.GitChangedFilesTool
import com.llamatik.teammate.tools.impl.GitCommitMessageSuggestionTool
import com.llamatik.teammate.tools.impl.GitDiffTool
import com.llamatik.teammate.tools.impl.GitStatusTool
import com.llamatik.teammate.tools.impl.IndexRefreshTool
import com.llamatik.teammate.tools.impl.IndexRelevantFilesTool
import com.llamatik.teammate.tools.impl.IndexSearchTool
import com.llamatik.teammate.tools.impl.IndexStatusTool
import com.llamatik.teammate.tools.impl.ListFilesTool
import com.llamatik.teammate.tools.impl.PsiFileStructureTool
import com.llamatik.teammate.tools.impl.PsiFindSymbolTool
import com.llamatik.teammate.tools.impl.PsiFindUsagesTool
import com.llamatik.teammate.tools.impl.ReadFileTool
import com.llamatik.teammate.tools.impl.ReplaceInFileTool
import com.llamatik.teammate.tools.impl.RunConfigAnalyzeFailureTool
import com.llamatik.teammate.tools.impl.RunConfigLastOutputTool
import com.llamatik.teammate.tools.impl.RunConfigListTool
import com.llamatik.teammate.tools.impl.SearchCodeTool
import com.llamatik.teammate.tools.impl.SearchFilenamesTool
import com.llamatik.teammate.tools.impl.ShowDiffTool
import com.llamatik.teammate.tools.impl.WriteFileTool

@Service(Service.Level.PROJECT)
class ToolRegistry(project: Project) {
    private val tools: Map<String, LlamatikTool> = listOf(
        // Core file tools
        ListFilesTool(project),
        ReadFileTool(project),
        SearchCodeTool(project),
        SearchFilenamesTool(project),
        GetCurrentFileTool(project),
        GetSelectedTextTool(project),
        WriteFileTool(project),
        CreateFileTool(project),
        ReplaceInFileTool(project),
        ShowDiffTool(project),
        ApplyPatchTool(project),
        // Git tools
        GitStatusTool(project),
        GitDiffTool(project),
        GitChangedFilesTool(project),
        GitBranchTool(project),
        GitCommitMessageSuggestionTool(project),
        // PSI tools
        PsiFileStructureTool(project),
        PsiFindSymbolTool(project),
        PsiFindUsagesTool(project),
        // Index tools
        IndexSearchTool(project),
        IndexRelevantFilesTool(project),
        IndexRefreshTool(project),
        IndexStatusTool(project),
        // Run config tools
        RunConfigListTool(project),
        RunConfigLastOutputTool(project),
        RunConfigAnalyzeFailureTool(project),
        // Codebase rules tools
        CodebaseRulesGetTool(project),
        CodebaseRulesRefreshTool(project),
        CodebaseRulesExplainTool(project),
    ).associateBy { it.name }

    fun describeTools(): String = tools.values.joinToString("\n") { "- ${it.name}: ${it.description}" }

    fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val tool = tools[call.name] ?: return ToolResult(call.name, false, "Unknown tool: ${call.name}")
        return runCatching { tool.execute(call, context) }
            .getOrElse { ToolResult(call.name, false, it.message ?: "Tool execution failed") }
    }
}
