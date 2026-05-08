package com.llamatik.teammate.tools

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.mcp.McpListServersTool
import com.llamatik.teammate.mcp.McpListToolsTool
import com.llamatik.teammate.mcp.McpCallToolTool
import com.llamatik.teammate.mcp.McpListResourcesTool
import com.llamatik.teammate.mcp.McpReadResourceTool
import com.llamatik.teammate.mcp.McpListPromptsTool
import com.llamatik.teammate.mcp.McpManagerService
import com.llamatik.teammate.mcp.McpToolBridgeService
import com.llamatik.teammate.tools.impl.*
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class ToolRegistry(private val project: Project) {
    private val builtinTools: Map<String, LlamatikTool> = listOf(
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
        // MCP agent tools (always present; actual MCP servers are optional)
        McpListServersTool(project),
        McpListToolsTool(project),
        McpCallToolTool(project),
        McpListResourcesTool(project),
        McpReadResourceTool(project),
        McpListPromptsTool(project),
        // External search tools
        ExternalSearchTool(project),
        ExternalSearchOfficialDocsTool(project),
        ExternalSearchGithubIssuesTool(project),
        // External page reader tools
        ExternalReadPageTool(project),
        ExternalReadPagesTool(project),
        // Research tools
        ResearchPlanTool(project),
        ResearchExecuteTool(project),
        ResearchSummarizeTool(project),
        ResearchRouteSourcesTool(project),
        ResearchFixPlanTool(project),
        // Docs source tools
        DocsSourcesListTool(project),
        DocsSourceAddTool(project),
        DocsIndexRefreshTool(project),
        DocsSearchTool(project),
        // Error research tools
        ErrorResearchAnalyzeTool(project),
        ErrorResearchFixTool(project),
        // Code Health Monitor tools
        CodeHealthStatusTool(project),
        CodeHealthRefreshTool(project),
        CodeHealthListInsightsTool(project),
        CodeHealthExplainInsightTool(project),
        CodeHealthCreateFixPlanTool(project),
        CodeHealthIgnoreInsightTool(project),
        WatchAreaAddTool(project),
        WatchAreaRemoveTool(project),
        WatchAreaListTool(project),
        SessionContextGetTool(project),
        SessionContextClearTool(project),
        BeforeCommitReviewTool(project),
        RegressionRiskAnalyzeTool(project),
        RefactorAnalyzeFileTool(project),
    ).associateBy { it.name }

    /** Dynamically registered MCP bridged tools (added/removed as servers start/stop). */
    private val dynamicTools = ConcurrentHashMap<String, LlamatikTool>()

    private val allTools: Map<String, LlamatikTool>
        get() = builtinTools + dynamicTools

    fun describeTools(): String = allTools.values.joinToString("\n") { "- ${it.name}: ${it.description}" }

    /** Compact list of just tool names — used when the char budget is tight. */
    fun listToolNames(): String = allTools.keys.joinToString(" | ")

    fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val tool = allTools[call.name] ?: return ToolResult(call.name, false, "Unknown tool: ${call.name}")
        return runCatching { tool.execute(call, context) }
            .getOrElse { ToolResult(call.name, false, it.message ?: "Tool execution failed") }
    }

    /** Registers MCP bridged tools from the bridge service (called after a server starts). */
    fun refreshMcpTools() {
        val bridgeService = service<McpToolBridgeService>()
        // Remove stale dynamic MCP tools
        dynamicTools.keys.toList().forEach { dynamicTools.remove(it) }
        // Add current bridged tools
        bridgeService.bridgedTools().forEach { (bridgedName, _) ->
            val tool = bridgeService.asLlamatikTool(bridgedName, project)
            if (tool != null) dynamicTools[bridgedName] = tool
        }
    }

    fun dynamicToolNames(): Set<String> = dynamicTools.keys.toSet()
}
