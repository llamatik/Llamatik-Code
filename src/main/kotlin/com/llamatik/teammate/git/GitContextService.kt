package com.llamatik.teammate.git

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.util.PluginLogger
import java.io.File

@Service(Service.Level.PROJECT)
class GitContextService(private val project: Project) {

    private val log = PluginLogger.logger<GitContextService>()

    data class GitStatus(
        val branch: String,
        val staged: List<String>,
        val unstaged: List<String>,
        val untracked: List<String>,
        val isGitRepo: Boolean,
    )

    fun isGitRepo(): Boolean = project.basePath?.let { File(it, ".git").exists() } ?: false

    fun branch(): String = runGit("rev-parse", "--abbrev-ref", "HEAD")?.trim() ?: "unknown"

    fun status(): GitStatus {
        if (!isGitRepo()) return GitStatus("", emptyList(), emptyList(), emptyList(), false)
        val branch = branch()
        val out = runGit("status", "--porcelain")
            ?: return GitStatus(branch, emptyList(), emptyList(), emptyList(), true)
        val staged = mutableListOf<String>()
        val unstaged = mutableListOf<String>()
        val untracked = mutableListOf<String>()
        out.lines().forEach { line ->
            if (line.length < 3) return@forEach
            val x = line[0]; val y = line[1]; val path = line.substring(3).trim()
            when {
                x == '?' && y == '?' -> untracked += path
                x != ' ' && x != '?' -> staged += path
                y != ' ' && y != '?' -> unstaged += path
            }
        }
        return GitStatus(branch, staged, unstaged, untracked, true)
    }

    fun diff(staged: Boolean = false, maxChars: Int = 8000): String {
        if (!isGitRepo()) return ""
        val args = if (staged) arrayOf("diff", "--staged") else arrayOf("diff")
        return runGit(*args)?.take(maxChars) ?: ""
    }

    fun changedFiles(): List<String> {
        if (!isGitRepo()) return emptyList()
        val staged = runGit("diff", "--staged", "--name-only")
            ?.lines()?.filter { it.isNotBlank() } ?: emptyList()
        val unstaged = runGit("diff", "--name-only")
            ?.lines()?.filter { it.isNotBlank() } ?: emptyList()
        return (staged + unstaged).distinct()
    }

    fun suggestCommitContext(maxDiffChars: Int = 4000): String {
        if (!isGitRepo()) return "Not a git repository"
        val diff = diff(staged = true, maxChars = maxDiffChars)
            .ifBlank { diff(staged = false, maxChars = maxDiffChars) }
        if (diff.isBlank()) return "No changes detected"
        val files = changedFiles()
        return buildString {
            appendLine("Changed files: ${files.take(20).joinToString(", ")}")
            appendLine()
            appendLine("Diff:")
            append(diff)
        }
    }

    private fun runGit(vararg args: String): String? {
        val basePath = project.basePath ?: return null
        return try {
            val cmd = GeneralCommandLine("git", *args).withWorkDirectory(basePath)
            val result = CapturingProcessHandler(cmd).runProcess(10_000)
            if (result.exitCode != 0) null else result.stdout
        } catch (e: Exception) {
            log.warn("git ${args.joinToString(" ")} failed: ${e.message}")
            null
        }
    }
}
