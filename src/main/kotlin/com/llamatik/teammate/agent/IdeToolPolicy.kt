package com.llamatik.teammate.agent

import com.llamatik.agent.core.agent.TOOL_CREATE_FILE
import com.llamatik.agent.core.agent.TOOL_GET_CURRENT_FILE
import com.llamatik.agent.core.agent.TOOL_GET_SELECTED_TEXT
import com.llamatik.agent.core.agent.TOOL_LIST_FILES
import com.llamatik.agent.core.agent.TOOL_READ_FILE
import com.llamatik.agent.core.agent.TOOL_REPLACE_IN_FILE
import com.llamatik.agent.core.agent.TOOL_SEARCH_CODE
import com.llamatik.agent.core.agent.TOOL_SEARCH_FILENAMES
import com.llamatik.agent.core.agent.TOOL_WRITE_FILE
import com.llamatik.agent.core.agent.ToolPolicy

/**
 * The tool names the agent loop accepts inside the IDE: the portable tools plus the IDE-only ones
 * (PSI, index, run configs, diagnostics, code health, MCP, ...). Moved unchanged from AgentOrchestrator.
 */
object IdeToolPolicy {
    private val KNOWN_TOOL_NAMES = setOf(
        TOOL_LIST_FILES, TOOL_READ_FILE, TOOL_SEARCH_CODE, TOOL_SEARCH_FILENAMES,
        TOOL_GET_CURRENT_FILE, TOOL_GET_SELECTED_TEXT, TOOL_WRITE_FILE, TOOL_CREATE_FILE,
        TOOL_REPLACE_IN_FILE, "show_diff", "apply_patch",
        // Git tools
        "git_status", "git_diff", "git_changed_files", "git_branch", "git_commit_message_suggestion",
        // PSI tools
        "psi_file_structure", "psi_find_symbol", "psi_find_usages",
        // Index tools
        "index_search", "index_relevant_files", "index_refresh", "index_status",
        // Run config tools
        "run_config_list", "run_config_last_output", "run_config_analyze_failure",
        // Codebase rules tools
        "codebase_rules_get", "codebase_rules_refresh", "codebase_rules_explain",
        // Terminal
        "terminal_allowed_commands", "terminal_run", "terminal_last_output",
        // Static analysis
        "static_analysis_detect_tools", "static_analysis_run", "static_analysis_parse_results",
        // Dependencies
        "deps_list", "deps_check_updates", "deps_plan_upgrade",
        // Build failure
        "build_failure_parse", "build_failure_explain", "build_failure_fix_plan",
        // Changelog / PR
        "pr_summary_generate", "changelog_generate", "commit_summary_generate",
        // Multi-file plan
        "plan_preview", "plan_current", "plan_approve", "plan_reject",
        // Agent memory
        "project_memory_get", "project_memory_add", "project_memory_update",
        "project_memory_delete", "project_memory_search",
        // UI repair
        "ui_image_analyze", "ui_find_candidate_files", "ui_generate_patch_plan",
        // Benchmark
        "model_benchmark_list", "model_benchmark_run", "model_benchmark_results",
        // Diagnostics
        "diagnostics_run", "diagnostics_fix_suggestions",
        // Smart search
        "smart_search",
        // Test coverage
        "tests_missing_analyze",
        // Project snapshot
        "project_snapshot_get", "project_snapshot_refresh",
        // KMP/native
        "kmp_native_diagnose",
        // MCP agent tools
        "mcp_list_servers", "mcp_list_tools", "mcp_call_tool",
        "mcp_list_resources", "mcp_read_resource", "mcp_list_prompts",
        // External search
        "external_search", "external_search_official_docs", "external_search_github_issues",
        // External page reader
        "external_read_page", "external_read_pages",
        // Research
        "research_plan", "research_execute", "research_summarize", "research_route_sources", "research_fix_plan",
        // Docs sources
        "docs_sources_list", "docs_source_add", "docs_index_refresh", "docs_search",
        // Error research
        "error_research_analyze", "error_research_fix",
        // Enterprise Pair Programmer
        "enterprise_mission_create", "enterprise_mission_context",
        "enterprise_mission_audit", "enterprise_mission_report",
        // Code Health Monitor
        "code_health_status", "code_health_refresh", "code_health_list_insights",
        "code_health_explain_insight", "code_health_create_fix_plan", "code_health_ignore_insight",
        "watch_area_add", "watch_area_remove", "watch_area_list",
        "session_context_get", "session_context_clear",
        "before_commit_review", "regression_risk_analyze", "refactor_analyze_file",
    )
    private val READ_ONLY_TOOL_NAMES = setOf(
        TOOL_LIST_FILES, TOOL_READ_FILE, TOOL_SEARCH_CODE, TOOL_SEARCH_FILENAMES,
        TOOL_GET_CURRENT_FILE, TOOL_GET_SELECTED_TEXT,
        "git_status", "git_diff", "git_changed_files", "git_branch", "git_commit_message_suggestion",
        "psi_file_structure", "psi_find_symbol", "psi_find_usages",
        "index_search", "index_relevant_files", "index_status",
        "run_config_list", "run_config_last_output", "run_config_analyze_failure",
        "codebase_rules_get", "codebase_rules_explain",
        "terminal_allowed_commands", "terminal_last_output",
        "static_analysis_detect_tools", "static_analysis_parse_results",
        "deps_list", "deps_check_updates",
        "build_failure_parse", "build_failure_explain",
        "pr_summary_generate", "changelog_generate", "commit_summary_generate",
        "plan_current",
        "project_memory_get", "project_memory_search",
        "ui_image_analyze", "ui_find_candidate_files",
        "model_benchmark_list", "model_benchmark_results",
        "diagnostics_run", "diagnostics_fix_suggestions",
        "smart_search",
        "tests_missing_analyze",
        "project_snapshot_get", "project_snapshot_refresh",
        "kmp_native_diagnose",
        // MCP agent tools (read-only: listing and reading)
        "mcp_list_servers", "mcp_list_tools",
        "mcp_list_resources", "mcp_read_resource", "mcp_list_prompts",
        // External search (read-only)
        "external_search", "external_search_official_docs", "external_search_github_issues",
        // External page reader (read-only)
        "external_read_page", "external_read_pages",
        // Research (read-only)
        "research_plan", "research_execute", "research_summarize", "research_route_sources", "research_fix_plan",
        // Docs sources (read-only for list/search)
        "docs_sources_list", "docs_search",
        // Error research (read-only)
        "error_research_analyze", "error_research_fix",
        // Code Health Monitor (read-only subset)
        "code_health_status", "code_health_list_insights", "code_health_explain_insight",
        "code_health_create_fix_plan", "watch_area_list", "session_context_get",
        "before_commit_review", "regression_risk_analyze", "refactor_analyze_file",
        // Enterprise Pair Programmer (context/audit/report are read-only; create is planning only)
        "enterprise_mission_create", "enterprise_mission_context",
        "enterprise_mission_audit", "enterprise_mission_report",
    )

    val POLICY = ToolPolicy(knownToolNames = KNOWN_TOOL_NAMES, readOnlyToolNames = READ_ONLY_TOOL_NAMES)
}
