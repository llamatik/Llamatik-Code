package com.llamatik.teammate.tools

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.tools.impl.*

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
        // Terminal tools
        TerminalAllowedCommandsTool(project),
        TerminalRunTool(project),
        TerminalLastOutputTool(project),
        // Static analysis tools
        StaticAnalysisDetectToolsTool(project),
        StaticAnalysisRunTool(project),
        StaticAnalysisParseResultsTool(project),
        // Dependency tools
        DepsListTool(project),
        DepsCheckUpdatesTool(project),
        DepsPlanUpgradeTool(project),
        // Build failure tools
        BuildFailureParseTool(project),
        BuildFailureExplainTool(project),
        BuildFailureFixPlanTool(project),
        // Changelog / PR tools
        PrSummaryGenerateTool(project),
        ChangelogGenerateTool(project),
        CommitSummaryGenerateTool(project),
        // Multi-file plan tools
        PlanPreviewTool(project),
        PlanCurrentTool(project),
        PlanApproveTool(project),
        PlanRejectTool(project),
        // Agent memory tools
        ProjectMemoryGetTool(project),
        ProjectMemoryAddTool(project),
        ProjectMemoryUpdateTool(project),
        ProjectMemoryDeleteTool(project),
        ProjectMemorySearchTool(project),
        // UI repair tools
        UiImageAnalyzeTool(project),
        UiFindCandidateFilesTool(project),
        UiGeneratePatchPlanTool(project),
        // Benchmark tools
        ModelBenchmarkListTool(project),
        ModelBenchmarkRunTool(project),
        ModelBenchmarkResultsTool(project),
        // Diagnostics tools
        DiagnosticsRunTool(project),
        DiagnosticsFixSuggestionsTool(project),
        // Smart search
        SmartSearchTool(project),
        // Test coverage heuristics
        TestsMissingAnalyzeTool(project),
        // Project snapshot
        ProjectSnapshotGetTool(project),
        ProjectSnapshotRefreshTool(project),
        // KMP/native diagnostics
        KmpNativeDiagnoseTool(project),
    ).associateBy { it.name }

    fun describeTools(): String = tools.values.joinToString("\n") { "- ${it.name}: ${it.description}" }

    /** Compact list of just tool names, one per line — used when the char budget is tight. */
    fun listToolNames(): String = tools.keys.joinToString(" | ")

    fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val tool = tools[call.name] ?: return ToolResult(call.name, false, "Unknown tool: ${call.name}")
        return runCatching { tool.execute(call, context) }
            .getOrElse { ToolResult(call.name, false, it.message ?: "Tool execution failed") }
    }
}
