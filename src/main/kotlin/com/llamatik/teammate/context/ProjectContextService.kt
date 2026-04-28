package com.llamatik.teammate.context

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtilCore
import com.llamatik.teammate.persistence.ChatSessionStore
import com.llamatik.teammate.settings.LlamatikAppSettings
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems

class ProjectContextService(private val project: Project) {
    private val defaultDeniedGlobs = listOf(
        "**/local.properties",
        "**/*.keystore",
        "**/*.jks",
        "**/.env*",
        "**/google-services.json",
        "**/*secrets*",
    )

    // Always-ignored directory names regardless of user settings — compiler caches,
    // generated outputs, and hidden toolchain folders that are never user-authored source.
    private val alwaysIgnoredDirs = setOf(
        ".git", ".idea", ".gradle", ".gradle-cache", ".kotlin", ".cxx", ".cache",
        "build", "out", "target", "node_modules", "__pycache__", ".tox",
        "generated", "intermediates", "dist", ".dart_tool", ".pub-cache",
    )

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
        val base = project.baseDir ?: return emptyList()
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
        require(isSafe(file)) { "Access denied for file: $path" }
        val maxBytes = service<LlamatikAppSettings>().data().maxFileSizeKb * 1024
        require(file.length <= maxBytes) { "File too large for context: $path" }
        return String(file.contentsToByteArray(), StandardCharsets.UTF_8)
    }

    fun searchFilenames(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        return allFiles().filter { it.contains(query, ignoreCase = true) }.take(50)
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
        val base = project.baseDir ?: return emptyList()
        val result = mutableListOf<String>()
        collectFiles(base, result)
        return result.sorted().take(1000)
    }

    fun getCurrentFile(): String? = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.let(::relativePath)
    fun getSelectedText(): String? = FileEditorManager.getInstance(project).selectedTextEditor?.selectionModel?.selectedText

    fun resolveFile(path: String?): VirtualFile? {
        val base = project.baseDir ?: return null
        if (path.isNullOrBlank() || path == ".") return base
        return base.findFileByRelativePath(path)
    }

    private fun resolveRequiredFile(path: String): VirtualFile = resolveFile(path) ?: error("File not found: $path")

    private fun relativePath(file: VirtualFile): String {
        val root = project.baseDir ?: return file.path
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

    private fun globMatches(path: String, glob: String): Boolean {
        return FileSystems.getDefault().getPathMatcher("glob:$glob").matches(java.nio.file.Path.of(path))
    }
}
