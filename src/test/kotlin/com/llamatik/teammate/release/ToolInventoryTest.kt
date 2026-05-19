package com.llamatik.teammate.release

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Verifies that every expected built-in tool name is declared in the source tree.
 *
 * Run via: ./gradlew toolContractTest
 *
 * Scans all *.kt files under src/main for `override val name = "..."` declarations
 * and checks them against [EXPECTED_BUILTIN_TOOLS]. Fails if any expected tool name
 * is missing, catching accidental renames or removals before a Marketplace release.
 */
class ToolInventoryTest {

    @Test
    fun allBuiltinToolsPresent() {
        val registered = discoverToolNamesFromSource()
        val missing = EXPECTED_BUILTIN_TOOLS - registered
        val extra = registered - EXPECTED_BUILTIN_TOOLS

        if (missing.isNotEmpty()) {
            val extraNote = if (extra.isNotEmpty())
                "\nAdd these to EXPECTED_BUILTIN_TOOLS if intentional:\n" + extra.sorted().joinToString("\n") { "  + $it" }
            else ""
            fail(
                "Missing tools from source (${missing.size}) — were they renamed or deleted?:\n" +
                missing.sorted().joinToString("\n") { "  - $it" } + extraNote
            )
        }

        if (extra.isNotEmpty()) {
            println("Note: ${extra.size} tool name(s) in source not in EXPECTED_BUILTIN_TOOLS — update the set:")
            extra.sorted().forEach { println("  + $it") }
        }

        println("Tool inventory OK: ${registered.size} tool names found in source, ${EXPECTED_BUILTIN_TOOLS.size} expected.")
    }

    @Test
    fun noToolNameIsBlank() {
        assertTrue("EXPECTED_BUILTIN_TOOLS contains a blank entry", EXPECTED_BUILTIN_TOOLS.none { it.isBlank() })
    }

    @Test
    fun toolNamesFollowSnakeCaseConvention() {
        val violating = EXPECTED_BUILTIN_TOOLS.filter { name -> !name.matches(Regex("[a-z][a-z0-9_]*")) }
        assertTrue(
            "Tool names must be snake_case. Violating: ${violating.joinToString()}",
            violating.isEmpty()
        )
    }

    @Test
    fun noDuplicateToolNamesInSource() {
        val allNames = discoverAllToolNameDeclarations()
        val counts = allNames.groupingBy { it }.eachCount()
        val duplicates = counts.filter { it.value > 1 }
        assertTrue(
            "Duplicate tool name declarations found:\n" + duplicates.entries.joinToString("\n") { "  ${it.key}: ${it.value} times" },
            duplicates.isEmpty()
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun discoverToolNamesFromSource(): Set<String> = discoverAllToolNameDeclarations().toSet()

    private fun discoverAllToolNameDeclarations(): List<String> {
        val srcMain = File("src/main/kotlin")
        if (!srcMain.isDirectory) return emptyList()

        val pattern = Regex("""override val name[^=]*=\s*"([a-z][a-z0-9_]*)" """)
        val patternNoSpace = Regex("""override val name[^=]*=\s*"([a-z][a-z0-9_]*)"$""", RegexOption.MULTILINE)

        return srcMain.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val text = file.readText()
                (pattern.findAll(text) + patternNoSpace.findAll(text))
                    .map { it.groupValues[1] }
            }
            .distinct()
            .toList()
    }

    companion object {
        val EXPECTED_BUILTIN_TOOLS: Set<String> = setOf(
            // Core file tools
            "list_files",
            "read_file",
            "search_code",
            "search_filenames",
            "get_current_file",
            "get_selected_text",
            "write_file",
            "create_file",
            "replace_in_file",
            "show_diff",
            "apply_patch",
            // Git tools
            "git_status",
            "git_diff",
            "git_changed_files",
            "git_branch",
            "git_commit_message_suggestion",
            // PSI tools
            "psi_file_structure",
            "psi_find_symbol",
            "psi_find_usages",
            // Index tools
            "index_search",
            "index_relevant_files",
            "index_refresh",
            "index_status",
            // Run config tools
            "run_config_list",
            "run_config_last_output",
            "run_config_analyze_failure",
            // Codebase rules tools
            "codebase_rules_get",
            "codebase_rules_refresh",
            "codebase_rules_explain",
            // Terminal tools
            "terminal_allowed_commands",
            "terminal_run",
            "terminal_last_output",
            // Static analysis tools
            "static_analysis_detect_tools",
            "static_analysis_run",
            "static_analysis_parse_results",
            // Dependency tools
            "deps_list",
            "deps_check_updates",
            "deps_plan_upgrade",
            // Build failure tools
            "build_failure_parse",
            "build_failure_explain",
            "build_failure_fix_plan",
            // Changelog / PR tools
            "pr_summary_generate",
            "changelog_generate",
            "commit_summary_generate",
            // Multi-file plan tools
            "plan_preview",
            "plan_current",
            "plan_approve",
            "plan_reject",
            // Agent memory tools
            "project_memory_get",
            "project_memory_add",
            "project_memory_update",
            "project_memory_delete",
            "project_memory_search",
            // UI repair tools
            "ui_image_analyze",
            "ui_find_candidate_files",
            "ui_generate_patch_plan",
            // Benchmark tools
            "model_benchmark_list",
            "model_benchmark_run",
            "model_benchmark_results",
            // Diagnostics tools
            "diagnostics_run",
            "diagnostics_fix_suggestions",
            // Smart search
            "smart_search",
            // Test coverage heuristics
            "tests_missing_analyze",
            // Project snapshot
            "project_snapshot_get",
            "project_snapshot_refresh",
            // KMP/native diagnostics
            "kmp_native_diagnose",
            // MCP agent tools
            "mcp_list_servers",
            "mcp_list_tools",
            "mcp_call_tool",
            "mcp_list_resources",
            "mcp_read_resource",
            "mcp_list_prompts",
            // External search tools
            "external_search",
            "external_search_official_docs",
            "external_search_github_issues",
            // External page reader tools
            "external_read_page",
            "external_read_pages",
            // Research tools
            "research_plan",
            "research_execute",
            "research_summarize",
            "research_route_sources",
            "research_fix_plan",
            // Docs source tools
            "docs_sources_list",
            "docs_source_add",
            "docs_index_refresh",
            "docs_search",
            // Error research tools
            "error_research_analyze",
            "error_research_fix",
            // Code Health Monitor tools
            "code_health_status",
            "code_health_refresh",
            "code_health_list_insights",
            "code_health_explain_insight",
            "code_health_create_fix_plan",
            "code_health_ignore_insight",
            "watch_area_add",
            "watch_area_remove",
            "watch_area_list",
            "session_context_get",
            "session_context_clear",
            "before_commit_review",
            "regression_risk_analyze",
            "refactor_analyze_file",
        )
    }
}
