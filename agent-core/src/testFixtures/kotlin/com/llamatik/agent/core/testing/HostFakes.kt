package com.llamatik.agent.core.testing

import com.llamatik.agent.core.host.GitRunner
import com.llamatik.agent.core.host.GitStatus
import com.llamatik.agent.core.host.ShellResult
import com.llamatik.agent.core.host.ShellRunner
import com.llamatik.agent.core.host.WorkspaceFiles

/** A workspace held in a map of relative path to content. */
class InMemoryFiles(private val files: Map<String, String> = emptyMap()) : WorkspaceFiles {
    override val rootPath = "/work/demo"
    override fun list(relativePath: String?): List<String> {
        val prefix = relativePath?.takeIf { it.isNotBlank() && it != "." }?.trimEnd('/')?.plus("/") ?: ""
        return files.keys.filter { it.startsWith(prefix) }.sorted()
    }
    override fun read(path: String): String =
        files[path.removePrefix("/work/demo/")] ?: error("File not found: $path")
    override fun write(path: String, content: String) = throw UnsupportedOperationException()
    override fun searchFilenames(query: String) = files.keys.filter { it.contains(query, true) }.sorted()
    override fun searchCode(query: String) = files.filterValues { it.contains(query, true) }.keys.sorted()
    override fun isIgnored(path: String) = false
}

object NoShell : ShellRunner {
    override fun run(command: String, cwd: String, timeoutMs: Long) = ShellResult(command, 0, "", "", 0, true)
    override fun lastOutput(command: String): ShellResult? = null
    override fun lastOutputAny(): ShellResult? = null
}

object NoGit : GitRunner {
    override fun isGitRepo() = false
    override fun branch() = "main"
    override fun status() = GitStatus("main", emptyList(), emptyList(), emptyList(), false)
    override fun diff(staged: Boolean, maxChars: Int) = ""
    override fun changedFiles() = emptyList<String>()
    override fun suggestCommitContext(maxDiffChars: Int) = "No changes detected"
}
