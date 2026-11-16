package com.llamatik.agent.core.host

/**
 * The files of the project the agent works on. In the IDE this wraps the project's virtual file
 * system; headless hosts use [NioWorkspaceFiles].
 *
 * All paths are relative to [rootPath] unless absolute. Implementations apply the safety rules in
 * [WorkspacePolicy] (ignored directories, secret-file denylist, size limit).
 */
interface WorkspaceFiles {
    /** Absolute path of the project root, or empty when there is none. */
    val rootPath: String

    /** Directory summary for the root / shallow paths, flat sorted file list (max 500) otherwise. */
    fun list(relativePath: String?): List<String>

    /** UTF-8 content. Throws [IllegalStateException] / [IllegalArgumentException] when missing, denied or too large. */
    fun read(path: String): String

    /** Writes [content], creating parent directories. */
    fun write(path: String, content: String)

    /** Filenames matching [query], best matches first (max 50). */
    fun searchFilenames(query: String): List<String>

    /** Paths of files whose content contains [query] (case-insensitive, max 50). */
    fun searchCode(query: String): List<String>

    /** True when [path] lies inside an ignored directory (build output, VCS metadata, user-ignored). */
    fun isIgnored(path: String): Boolean
}
