package com.llamatik.agent.core.host

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * [WorkspaceFiles] on `java.nio`, for hosts without an IDE (CLI, ACP agent, benchmarks).
 * Mirrors the IDE implementation's listing, filtering and search behaviour.
 *
 * Ignored-directory matching looks at the path components *below* the root, so a project that
 * itself lives in a directory called `build` or `out` (e.g. a temp dir) is not treated as ignored.
 *
 * @param userIgnoredDirs extra directory names to ignore, on top of [WorkspacePolicy.ALWAYS_IGNORED_DIRS]
 * @param maxFileBytes largest file [read] will return
 */
class NioWorkspaceFiles(
    root: Path,
    private val userIgnoredDirs: () -> Set<String> = { emptySet() },
    private val maxFileBytes: Long = 256 * 1024,
) : WorkspaceFiles {
    private val base: Path = root.toAbsolutePath().normalize()

    override val rootPath: String get() = base.toString()

    override fun list(relativePath: String?): List<String> {
        val start = resolve(relativePath) ?: base
        // Root or shallow path: a tree-style directory summary so the model can navigate
        // purposefully instead of receiving a flat dump of build/cache files.
        val depth = if (relativePath.isNullOrBlank() || relativePath == ".") 0
        else relativePath.trimEnd('/').count { it == '/' } + 1
        if (depth <= 1 && Files.isDirectory(start)) return buildDirectoryTree(start)
        val result = mutableListOf<String>()
        collectFiles(start, result)
        return result.sorted().take(WorkspacePolicy.MAX_LISTED_FILES)
    }

    override fun read(path: String): String {
        val file = resolve(path) ?: error("File not found: $path")
        require(isSafeToRead(file)) { "Access denied for file: $path" }
        require(Files.size(file) <= maxFileBytes) { "File too large for context: $path" }
        return String(Files.readAllBytes(file), StandardCharsets.UTF_8)
    }

    override fun write(path: String, content: String) {
        val target = toAbsolute(path)
        target.parent?.let { Files.createDirectories(it) }
        Files.writeString(target, content)
    }

    override fun searchFilenames(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        return WorkspacePolicy.rankFilenames(allFiles(), query).take(WorkspacePolicy.MAX_SEARCH_RESULTS)
    }

    override fun searchCode(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        val results = mutableListOf<String>()
        allFiles().forEach { path ->
            if (results.size >= WorkspacePolicy.MAX_SEARCH_RESULTS) return@forEach
            val content = runCatching { read(path) }.getOrNull() ?: return@forEach
            if (content.contains(query, ignoreCase = true)) results += path
        }
        return results
    }

    override fun isIgnored(path: String): Boolean = isIgnored(toAbsolute(path))

    // ── Internals ───────────────────────────────────────────────────────────

    private fun toAbsolute(path: String): Path {
        val p = Path.of(path)
        return (if (p.isAbsolute) p else base.resolve(p)).normalize()
    }

    /** The file or directory for [path], or null when blank/".": the root; when it does not exist: null. */
    private fun resolve(path: String?): Path? {
        if (path.isNullOrBlank() || path == ".") return base
        val resolved = toAbsolute(path)
        return resolved.takeIf { Files.exists(it) }
    }

    private fun relativePath(file: Path): String =
        if (file.startsWith(base)) base.relativize(file).joinToString("/") { it.toString() } else file.toString()

    private fun isIgnored(file: Path): Boolean {
        val ignored = WorkspacePolicy.ALWAYS_IGNORED_DIRS + userIgnoredDirs().map { it.trim() }.filter { it.isNotBlank() }
        val parts = if (file.startsWith(base)) base.relativize(file) else file
        return parts.any { it.toString() in ignored }
    }

    private fun isSafe(file: Path): Boolean {
        if (Files.isDirectory(file)) return !isIgnored(file)
        if (isIgnored(file)) return false
        return !WorkspacePolicy.isDenied(relativePath(file))
    }

    // Like isSafe but does NOT block ignored directories — used for explicit read calls so the
    // model can read generated/build files when the user explicitly asks for them.
    private fun isSafeToRead(file: Path): Boolean {
        if (Files.isDirectory(file)) return false
        return !WorkspacePolicy.isDenied(relativePath(file))
    }

    private fun children(dir: Path): List<Path> =
        runCatching { Files.list(dir).use { it.toList() } }.getOrDefault(emptyList())

    private fun buildDirectoryTree(dir: Path): List<String> {
        val lines = mutableListOf<String>()
        val children = children(dir).sortedWith(compareBy({ !Files.isDirectory(it) }, { it.fileName.toString() }))
        for (child in children) {
            if (isIgnored(child)) continue
            val rel = relativePath(child)
            if (Files.isDirectory(child)) {
                val count = countFiles(child)
                if (count > 0) lines += "$rel/  ($count files)"
            } else if (isSafe(child)) {
                lines += rel
            }
        }
        return lines
    }

    private fun countFiles(dir: Path): Int {
        var count = 0
        val stack = ArrayDeque<Path>()
        stack.add(dir)
        while (stack.isNotEmpty()) {
            val f = stack.removeLast()
            if (Files.isDirectory(f)) {
                if (!isIgnored(f)) stack.addAll(children(f))
            } else if (isSafe(f)) {
                count++
            }
        }
        return count
    }

    private fun collectFiles(file: Path, output: MutableList<String>) {
        if (Files.isDirectory(file)) {
            if (isIgnored(file)) return
            children(file).forEach { collectFiles(it, output) }
            return
        }
        if (isSafe(file)) output += relativePath(file)
    }

    private fun allFiles(): List<String> {
        val result = mutableListOf<String>()
        collectFiles(base, result)
        return result.sorted()
    }
}
