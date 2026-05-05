package com.llamatik.teammate.agent

/**
 * Stateless fallback chain logic for Aggressive Agent Mode.
 *
 * Each [FallbackStep] describes a tool name and argument to try when the primary
 * strategy returns empty or fails.  The caller is responsible for executing these
 * steps via the tool registry and recording them in the [AgentTrace].
 */
object AgentFallbackPolicy {

    data class FallbackStep(
        val toolName: String,
        val argument: String,
        val rationale: String,
    )

    /**
     * Returns ordered fallback steps for an empty code-search result.
     * The caller should attempt each step in sequence and stop at the first non-empty result.
     */
    fun codeSearchFallbacks(originalQuery: String): List<FallbackStep> = listOf(
        FallbackStep(
            toolName = "search_code",
            argument = broadenQuery(originalQuery),
            rationale = "Retry with broader search terms",
        ),
        FallbackStep(
            toolName = "search_filenames",
            argument = originalQuery.split(Regex("\\s+")).maxByOrNull { it.length } ?: originalQuery,
            rationale = "Search filenames for relevant modules",
        ),
        FallbackStep(
            toolName = "list_files",
            argument = ".",
            rationale = "List project root to understand structure",
        ),
        FallbackStep(
            toolName = "read_file",
            argument = "build.gradle.kts",
            rationale = "Inspect Gradle build file for project layout clues",
        ),
        FallbackStep(
            toolName = "read_file",
            argument = "settings.gradle.kts",
            rationale = "Inspect Gradle settings for module names",
        ),
    )

    /**
     * Returns ordered fallback steps for missing-test analysis.
     */
    fun missingTestFallbacks(): List<FallbackStep> = listOf(
        FallbackStep(
            toolName = "list_files",
            argument = "src/main",
            rationale = "List production source files",
        ),
        FallbackStep(
            toolName = "list_files",
            argument = "src/test",
            rationale = "List test source files",
        ),
        FallbackStep(
            toolName = "tests_missing_analyze",
            argument = "",
            rationale = "Run missing-test heuristic analysis",
        ),
    )

    /**
     * Returns fallback steps when the project structure is unclear.
     */
    fun projectStructureFallbacks(): List<FallbackStep> = listOf(
        FallbackStep(
            toolName = "list_files",
            argument = ".",
            rationale = "List project root",
        ),
        FallbackStep(
            toolName = "read_file",
            argument = "build.gradle.kts",
            rationale = "Read Gradle build configuration",
        ),
        FallbackStep(
            toolName = "read_file",
            argument = "settings.gradle.kts",
            rationale = "Read Gradle settings",
        ),
        FallbackStep(
            toolName = "psi_file_structure",
            argument = "",
            rationale = "Use PSI to inspect source file structure",
        ),
    )

    /**
     * Returns the system-prompt addition that instructs the model to use fallback chains
     * when operating in AGGRESSIVE mode.
     */
    fun aggressiveModeSystemPromptAddition(): String = """
AGGRESSIVE MODE ENABLED — Fallback rules (follow strictly):
1. If search_code returns empty: retry with broader terms, then use list_files, then read build.gradle.kts.
2. If list_files returns nothing useful: read settings.gradle.kts and try psi_file_structure.
3. Never stop after a single empty search. Try at least one fallback before giving up.
4. Never ask the user for files unless all fallback strategies are exhausted.
5. Before saying "I cannot proceed", explain every tool you tried and why each failed.
6. For "which tests are missing?": first list_files src/main, then list_files src/test, then tests_missing_analyze.
7. For conceptual questions ("where is auth handled?"): prefer smart_search, then psi_find_symbol, then search_code.
""".trimIndent()

    /** Broaden a query by keeping only the most significant tokens. */
    private fun broadenQuery(query: String): String {
        val stopWords = setOf("the", "a", "an", "in", "of", "for", "is", "are", "how", "what", "where", "why")
        val tokens = query.split(Regex("\\s+"))
            .filter { it.length > 2 && it.lowercase() !in stopWords }
        return when {
            tokens.size >= 2 -> tokens.take(2).joinToString(" ")
            tokens.size == 1 -> tokens.first()
            else -> query.take(20)
        }
    }
}
