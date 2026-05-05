package com.llamatik.teammate.changelog

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.git.GitContextService

@Service(Service.Level.PROJECT)
class ChangelogService(private val project: Project) {

    enum class Style { CONCISE, DETAILED, CONVENTIONAL_COMMITS }

    fun buildPrSummaryContext(style: Style = Style.DETAILED): String {
        val git = project.service<GitContextService>()
        if (!git.isGitRepo()) return "Not a git repository — cannot generate PR summary"
        val diff = git.diff(staged = false, maxChars = 5000)
            .ifBlank { git.diff(staged = true, maxChars = 5000) }
        val files = git.changedFiles()
        val branch = git.branch()
        if (diff.isBlank() && files.isEmpty()) return "No changes detected in working tree"
        return buildString {
            appendLine("Branch: $branch")
            appendLine("Changed files (${files.size}): ${files.take(15).joinToString(", ")}")
            appendLine()
            appendLine("Style: ${style.name.lowercase().replace('_', ' ')}")
            appendLine()
            when (style) {
                Style.CONVENTIONAL_COMMITS -> {
                    appendLine("Format the summary as conventional commits (feat/fix/refactor/test/chore).")
                    appendLine("Group changes by type.")
                }
                Style.CONCISE -> appendLine("Write a concise 2-3 sentence summary.")
                Style.DETAILED -> appendLine("Include: what changed, why it matters, test coverage, risk areas.")
            }
            appendLine()
            appendLine("Diff:")
            append(diff.take(4000))
        }.trim()
    }

    fun buildChangelogContext(): String {
        val git = project.service<GitContextService>()
        if (!git.isGitRepo()) return "Not a git repository"
        val diff = git.diff(staged = false, maxChars = 4000)
            .ifBlank { git.diff(staged = true, maxChars = 4000) }
        val files = git.changedFiles()
        return buildString {
            appendLine("Generate a changelog entry in Keep a Changelog format.")
            appendLine("Changed files: ${files.take(15).joinToString(", ")}")
            appendLine()
            appendLine("Diff:")
            append(diff.take(3500))
        }.trim()
    }

    fun buildCommitSummaryContext(): String {
        val git = project.service<GitContextService>()
        if (!git.isGitRepo()) return "Not a git repository"
        return git.suggestCommitContext(maxDiffChars = 3000)
    }
}
