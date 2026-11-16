package com.llamatik.agent.core.host

import java.io.File
import java.util.concurrent.TimeUnit

data class GitStatus(
    val branch: String,
    val staged: List<String>,
    val unstaged: List<String>,
    val untracked: List<String>,
    val isGitRepo: Boolean,
)

interface GitRunner {
    fun isGitRepo(): Boolean
    fun branch(): String
    fun status(): GitStatus
    fun diff(staged: Boolean = false, maxChars: Int = 8000): String
    fun changedFiles(): List<String>
    fun suggestCommitContext(maxDiffChars: Int = 4000): String
}

/**
 * [GitRunner] logic on top of one primitive, [runGit]. Hosts supply how a `git` process is run
 * (the IDE uses its own process API so the user's PATH/environment applies).
 */
abstract class CommandLineGitRunner : GitRunner {
    /** Absolute project root, or null when there is none. */
    protected abstract val rootPath: String?

    /** stdout of `git <args>` in [rootPath]; null when it fails or cannot run. */
    protected abstract fun runGit(vararg args: String): String?

    override fun isGitRepo(): Boolean = rootPath?.let { File(it, ".git").exists() } ?: false

    override fun branch(): String = runGit("rev-parse", "--abbrev-ref", "HEAD")?.trim() ?: "unknown"

    override fun status(): GitStatus {
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

    override fun diff(staged: Boolean, maxChars: Int): String {
        if (!isGitRepo()) return ""
        val args = if (staged) arrayOf("diff", "--staged") else arrayOf("diff")
        return runGit(*args)?.take(maxChars) ?: ""
    }

    override fun changedFiles(): List<String> {
        if (!isGitRepo()) return emptyList()
        val staged = runGit("diff", "--staged", "--name-only")
            ?.lines()?.filter { it.isNotBlank() } ?: emptyList()
        val unstaged = runGit("diff", "--name-only")
            ?.lines()?.filter { it.isNotBlank() } ?: emptyList()
        return (staged + unstaged).distinct()
    }

    override fun suggestCommitContext(maxDiffChars: Int): String {
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
}

/** [GitRunner] that shells out to `git` with [ProcessBuilder]. */
class ProcessGitRunner(override val rootPath: String?) : CommandLineGitRunner() {
    override fun runGit(vararg args: String): String? {
        val dir = rootPath ?: return null
        return try {
            val process = ProcessBuilder("git", *args).directory(File(dir)).redirectErrorStream(false).start()
            process.outputStream.close()
            var stdout = ""
            val reader = Thread { stdout = process.inputStream.readBytes().toString(Charsets.UTF_8) }.apply { isDaemon = true; start() }
            Thread { process.errorStream.readBytes() }.apply { isDaemon = true; start() }
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            reader.join(2_000)
            if (process.exitValue() != 0) null else stdout
        } catch (e: Exception) {
            null
        }
    }
}
