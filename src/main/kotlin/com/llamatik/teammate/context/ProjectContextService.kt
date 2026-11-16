package com.llamatik.teammate.context

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtilCore
import com.llamatik.agent.core.host.WorkspacePolicy
import com.llamatik.teammate.persistence.ChatSessionStore
import com.llamatik.teammate.settings.LlamatikAppSettings
import java.nio.charset.StandardCharsets

class ProjectContextService(private val project: Project) {
    // Safety rules (denylist, always-ignored dirs) are shared with headless hosts: see WorkspacePolicy.
    private val defaultDeniedGlobs = WorkspacePolicy.DEFAULT_DENIED_GLOBS
    private val alwaysIgnoredDirs = WorkspacePolicy.ALWAYS_IGNORED_DIRS

    fun projectName(): String = project.name
    fun rootPath(): String = project.basePath ?: ""

    fun recentChatSummary(limit: Int = 6): String {
        val store = project.service<ChatSessionStore>()
        return store.loadSessions()
            .flatMap { it.messages }
            .takeLast(limit)
            .joinToString("\n") { "${it.role}: ${it.content.take(240)}" }
    }

    fun pinnedInstructions(): String = project.service<ChatSessionStore>().pinnedInstructions()

    fun listFiles(relativePath: String?): List<String> {
        val base = project.guessProjectDir() ?: return emptyList()
        val start = resolveFile(relativePath) ?: base
        // For root or shallow paths, return a tree-style directory summary so the model can
        // navigate purposefully instead of receiving a flat dump of build/cache files.
        val depth = if (relativePath.isNullOrBlank() || relativePath == ".") 0
                    else relativePath.trimEnd('/').count { it == '/' } + 1
        if (depth <= 1 && start.isDirectory) {
            return buildDirectoryTree(start, base)
        }
        val result = mutableListOf<String>()
        collectFiles(start, result)
        return result.sorted().take(500)
    }

    /**
     * Returns a mixed listing: immediate child directories annotated with file counts,
     * followed by immediate source files. Helps the model understand project layout without
     * flooding its context with hundreds of paths.
     */
    private fun buildDirectoryTree(dir: VirtualFile, base: VirtualFile): List<String> {
        val lines = mutableListOf<String>()
        val children = dir.children.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
        for (child in children) {
            if (isIgnored(child)) continue
            val rel = VfsUtilCore.getRelativePath(child, base, '/') ?: child.name
            if (child.isDirectory) {
                val count = countFiles(child)
                if (count > 0) lines += "$rel/  ($count files)"
            } else {
                if (isSafe(child)) lines += rel
            }
        }
        return lines
    }

    private fun countFiles(dir: VirtualFile): Int {
        var count = 0
        val stack = ArrayDeque<VirtualFile>()
        stack += dir
        while (stack.isNotEmpty()) {
            val f = stack.removeLast()
            if (f.isDirectory) {
                if (!isIgnored(f)) stack += f.children
            } else {
                if (isSafe(f)) count++
            }
        }
        return count
    }

    private fun collectFiles(file: VirtualFile, output: MutableList<String>) {
        if (file.isDirectory) {
            if (isIgnored(file)) return
            file.children.forEach { collectFiles(it, output) }
            return
        }
        if (isSafe(file)) output += relativePath(file)
    }

    fun readFile(path: String): String {
        val file = resolveRequiredFile(path)
        require(isSafeToRead(file)) { "Access denied for file: $path" }
        val maxBytes = service<LlamatikAppSettings>().data().maxFileSizeKb * 1024
        require(file.length <= maxBytes) { "File too large for context: $path" }
        return String(file.contentsToByteArray(), StandardCharsets.UTF_8)
    }

    fun searchFilenames(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        return rankFilenames(allFiles(), query).take(50)
    }

    companion object {
        /** Pure ranking logic — exposed for testing without a real Project. */
        fun rankFilenames(files: List<String>, query: String): List<String> =
            WorkspacePolicy.rankFilenames(files, query)
    }

    fun searchCode(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        val results = mutableListOf<String>()
        allFiles().forEach { path ->
            if (results.size >= 50) return@forEach
            val content = runCatching { readFile(path) }.getOrNull() ?: return@forEach
            if (content.contains(query, ignoreCase = true)) results += path
        }
        return results
    }

    private fun allFiles(): List<String> {
        val base = project.guessProjectDir() ?: return emptyList()
        val result = mutableListOf<String>()
        collectFiles(base, result)
        return result.sorted()
    }

    fun getCurrentFile(): String? = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.let(::relativePath)
    fun getSelectedText(): String? = FileEditorManager.getInstance(project).selectedTextEditor?.selectionModel?.selectedText

    fun resolveFile(path: String?): VirtualFile? {
        val base = project.guessProjectDir() ?: return null
        if (path.isNullOrBlank() || path == ".") return base
        // Absolute paths (e.g. from @-file references) must go through LocalFileSystem.
        if (path.startsWith("/") || path.length > 1 && path[1] == ':') {
            return LocalFileSystem.getInstance().findFileByPath(path)
        }
        return base.findFileByRelativePath(path)
    }

    private fun resolveRequiredFile(path: String): VirtualFile = resolveFile(path) ?: error("File not found: $path")

    private fun relativePath(file: VirtualFile): String {
        val root = project.guessProjectDir() ?: return file.path
        return VfsUtilCore.getRelativePath(file, root, '/') ?: file.path
    }

    private fun isIgnored(file: VirtualFile): Boolean {
        val userIgnored = service<LlamatikAppSettings>().data().ignoredDirectories
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
        val allIgnored = alwaysIgnoredDirs + userIgnored
        return generateSequence(file) { it.parent }.any { it.name in allIgnored }
    }

    private fun isSafe(file: VirtualFile): Boolean {
        if (file.isDirectory) return !isIgnored(file)
        if (isIgnored(file)) return false
        val relative = relativePath(file)
        return defaultDeniedGlobs.none { globMatches(relative, it) }
    }

    // Like isSafe but does NOT block ignored directories — used for explicit read_file calls
    // so the model can read generated/build files when the user explicitly requests them.
    private fun isSafeToRead(file: VirtualFile): Boolean {
        if (file.isDirectory) return false
        val relative = relativePath(file)
        return defaultDeniedGlobs.none { globMatches(relative, it) }
    }

    private fun globMatches(path: String, glob: String): Boolean = WorkspacePolicy.globMatches(path, glob)

    /** True when [path] is inside an ignored directory (build output, VCS metadata, user-ignored). */
    fun isPathIgnored(path: String): Boolean {
        resolveFile(path)?.let { return isIgnored(it) }
        val userIgnored = service<LlamatikAppSettings>().data().ignoredDirectories
            .split(',').map { it.trim() }.filter { it.isNotBlank() }
        return path.split('/', '\\').any { it in alwaysIgnoredDirs || it in userIgnored }
    }
}
