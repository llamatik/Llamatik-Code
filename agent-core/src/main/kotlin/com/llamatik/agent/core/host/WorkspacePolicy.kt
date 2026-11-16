package com.llamatik.agent.core.host

import java.nio.file.FileSystems
import java.nio.file.Path

/** Safety and ranking rules shared by every [WorkspaceFiles] implementation. */
object WorkspacePolicy {
    val DEFAULT_DENIED_GLOBS: List<String> = listOf(
        "**/local.properties",
        "**/*.keystore",
        "**/*.jks",
        "**/.env*",
        "**/google-services.json",
        "**/*secrets*",
    )

    /**
     * Always-ignored directory names regardless of user settings: compiler caches, generated
     * outputs and hidden toolchain folders that are never user-authored source.
     */
    val ALWAYS_IGNORED_DIRS: Set<String> = setOf(
        ".git", ".idea", ".gradle", ".gradle-cache", ".kotlin", ".cxx", ".cache",
        "build", "out", "target", "node_modules", "__pycache__", ".tox",
        "generated", "intermediates", "dist", ".dart_tool", ".pub-cache",
        ".intellijPlatform",
    )

    const val MAX_LISTED_FILES = 500
    const val MAX_SEARCH_RESULTS = 50

    /**
     * Glob match where a leading star-star-slash also matches zero directories, so a pattern meant
     * for "any directory" covers files at the project root too (plain Java globs require a parent).
     */
    fun globMatches(path: String, glob: String): Boolean {
        val fs = FileSystems.getDefault()
        val p = Path.of(path)
        if (fs.getPathMatcher("glob:$glob").matches(p)) return true
        return glob.startsWith("**/") && fs.getPathMatcher("glob:${glob.removePrefix("**/")}").matches(p)
    }

    fun isDenied(relativePath: String): Boolean = DEFAULT_DENIED_GLOBS.any { globMatches(relativePath, it) }

    /** Pure ranking logic for filename search. */
    fun rankFilenames(files: List<String>, query: String): List<String> {
        if (query.isBlank()) return emptyList()
        val q = query.lowercase()
        return files
            .filter { it.contains(q, ignoreCase = true) }
            .sortedWith(compareBy(
                // Tier 1: filename starts with query
                { !it.substringAfterLast('/').lowercase().startsWith(q) },
                // Tier 2: filename contains query (but doesn't start with it)
                { !it.substringAfterLast('/').lowercase().contains(q) },
                // Tier 3: alphabetical by filename within each tier
                { it.substringAfterLast('/').lowercase() },
            ))
    }
}
