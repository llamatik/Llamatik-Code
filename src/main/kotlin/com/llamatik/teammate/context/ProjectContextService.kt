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
        val start = resolveFile(relativePath) ?: project.baseDir ?: return emptyList()
        val result = mutableListOf<String>()
        collectFiles(start, result)
        return result.sorted().take(1000)
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
        return listFiles(null).filter { it.contains(query, ignoreCase = true) }.take(50)
    }

    fun searchCode(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        val results = mutableListOf<String>()
        listFiles(null).forEach { path ->
            if (results.size >= 50) return@forEach
            val content = runCatching { readFile(path) }.getOrNull() ?: return@forEach
            if (content.contains(query, ignoreCase = true)) results += path
        }
        return results
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
        val ignoredNames = service<LlamatikAppSettings>().data().ignoredDirectories
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
        return generateSequence(file) { it.parent }.any { it.name in ignoredNames }
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
