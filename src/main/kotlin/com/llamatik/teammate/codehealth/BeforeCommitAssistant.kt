package com.llamatik.teammate.codehealth

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.git.GitContextService
import com.llamatik.teammate.licensing.LicenseChecker
import com.llamatik.teammate.util.PluginLogger

@Service(Service.Level.PROJECT)
class BeforeCommitAssistant(private val project: Project) {

    private val log = PluginLogger.logger<BeforeCommitAssistant>()

    data class CommitReview(
        val diffSummary: String,
        val suggestedCommitMessage: String,
        val risks: List<CodeHealthInsight>,
        val missingTests: List<String>,
        val buildWasRun: Boolean,
        val followUpSuggestions: List<String>,
    )

    private fun isPro(): Boolean = runCatching { LicenseChecker.isLicensed() == true }.getOrElse { false }

    fun review(changedFiles: List<String>): CommitReview {
        log.info("BeforeCommitAssistant: reviewing ${changedFiles.size} changed files")

        val gitSvc = runCatching { project.service<GitContextService>() }.getOrNull()
        val diff = runCatching { gitSvc?.diff() ?: "" }.getOrElse { "" }
        val filesFromGit = runCatching { gitSvc?.changedFiles() ?: changedFiles }.getOrElse { changedFiles }

        val diffSummary = buildDiffSummary(diff, filesFromGit)
        val commitMessage = suggestCommitMessage(diff, filesFromGit)

        val riskResult = if (diff.isNotBlank()) {
            project.service<RegressionRiskAnalyzer>().analyzeGitDiff(diff, filesFromGit)
        } else {
            RegressionRiskAnalyzer.RiskResult(emptyList(), "No diff available")
        }

        val missingTests = if (isPro()) {
            runCatching {
                project.service<BackgroundInsightAnalyzer>()
                    .analyzeFiles(filesFromGit, RunTrigger.BEFORE_COMMIT)
                    .insights
                    .filter { it.type == InsightType.MISSING_TESTS }
                    .map { it.filePath }
            }.getOrElse { emptyList() }
        } else {
            emptyList()
        }

        val followUp = mutableListOf<String>()
        if (riskResult.risks.any { it.severity == InsightSeverity.HIGH }) {
            followUp.add("High-risk changes detected — consider running tests before committing")
        }
        if (missingTests.isNotEmpty()) {
            followUp.add("${missingTests.size} changed file(s) may lack tests")
        }

        return CommitReview(
            diffSummary = diffSummary,
            suggestedCommitMessage = commitMessage,
            risks = riskResult.risks,
            missingTests = missingTests,
            buildWasRun = false,
            followUpSuggestions = followUp,
        )
    }

    private fun buildDiffSummary(diff: String, files: List<String>): String {
        val additions = diff.lines().count { it.startsWith("+") && !it.startsWith("+++") }
        val deletions = diff.lines().count { it.startsWith("-") && !it.startsWith("---") }
        val fileList = files.take(5).joinToString(", ") { it.substringAfterLast('/') }
        val more = if (files.size > 5) " +${files.size - 5} more" else ""
        return "+$additions lines / -$deletions lines in $fileList$more"
    }

    private fun suggestCommitMessage(diff: String, files: List<String>): String {
        val mainFile = files.firstOrNull()?.substringAfterLast('/')?.removeSuffix(".kt")?.removeSuffix(".java") ?: "code"
        return when {
            files.any { "Test" in it || "Spec" in it } -> "test: add/update tests for $mainFile"
            diff.contains(Regex("""^-\s*(class |fun )""", RegexOption.MULTILINE)) -> "refactor: update $mainFile"
            else -> "chore: update $mainFile"
        }
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): BeforeCommitAssistant = project.service()
    }
}
