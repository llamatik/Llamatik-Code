package com.llamatik.teammate.codehealth

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.util.PluginLogger

@Service(Service.Level.PROJECT)
class RegressionRiskAnalyzer(private val project: Project) {

    private val log = PluginLogger.logger<RegressionRiskAnalyzer>()

    data class RiskResult(
        val risks: List<CodeHealthInsight>,
        val summary: String,
    )

    fun analyzeGitDiff(diff: String, changedFiles: List<String>): RiskResult {
        val risks = mutableListOf<CodeHealthInsight>()

        val removedPublicRegex = Regex("""^-\s*(public|fun |class |object |interface )[^\n]+""", RegexOption.MULTILINE)
        val removedPublic = removedPublicRegex.findAll(diff).count()
        if (removedPublic > 0) {
            risks.add(CodeHealthInsight(
                id = "regression-${diff.hashCode()}-removed-public",
                type = InsightType.REGRESSION_RISK,
                severity = InsightSeverity.HIGH,
                title = "Removed public declarations ($removedPublic)",
                description = "Diff removes $removedPublic public method/class lines. This may break callers.",
                filePath = changedFiles.firstOrNull() ?: "",
                suggestedAction = "Check for usages of removed declarations before committing",
                evidence = listOf("Found $removedPublic removed public declaration lines in diff"),
                source = "git-diff",
            ))
        }

        val removedTestRegex = Regex("""^-\s*@Test""", RegexOption.MULTILINE)
        val removedTests = removedTestRegex.findAll(diff).count()
        if (removedTests > 0) {
            risks.add(CodeHealthInsight(
                id = "regression-${diff.hashCode()}-removed-tests",
                type = InsightType.REGRESSION_RISK,
                severity = InsightSeverity.HIGH,
                title = "Removed tests ($removedTests)",
                description = "Diff removes $removedTests @Test annotations. Tests may have been deleted.",
                filePath = changedFiles.firstOrNull() ?: "",
                suggestedAction = "Verify removed tests are intentional",
                evidence = listOf("Found $removedTests removed @Test annotations in diff"),
                source = "git-diff",
            ))
        }

        val gradleFiles = changedFiles.filter { it.endsWith(".gradle") || it.endsWith(".gradle.kts") }
        if (gradleFiles.isNotEmpty()) {
            val depChangeRegex = Regex("""^[-+]\s*(implementation|testImplementation|api)\s*\(""", RegexOption.MULTILINE)
            if (depChangeRegex.containsMatchIn(diff)) {
                risks.add(CodeHealthInsight(
                    id = "regression-${diff.hashCode()}-deps",
                    type = InsightType.DEPENDENCY_RISK,
                    severity = InsightSeverity.WARNING,
                    title = "Dependency changes detected",
                    description = "Gradle dependencies changed in: ${gradleFiles.joinToString()}",
                    filePath = gradleFiles.first(),
                    suggestedAction = "Run full test suite after dependency changes",
                    source = "git-diff",
                ))
            }
        }

        val removedTryRegex = Regex("""^-\s*(try\s*\{|catch\s*\()""", RegexOption.MULTILINE)
        if (removedTryRegex.containsMatchIn(diff)) {
            risks.add(CodeHealthInsight(
                id = "regression-${diff.hashCode()}-error-handling",
                type = InsightType.ERROR_HANDLING,
                severity = InsightSeverity.WARNING,
                title = "Removed error handling",
                description = "Diff removes try/catch blocks which may expose unhandled exceptions.",
                filePath = changedFiles.firstOrNull() ?: "",
                suggestedAction = "Verify exception handling is still in place",
                source = "git-diff",
            ))
        }

        val manifestFiles = changedFiles.filter { it.endsWith("AndroidManifest.xml") }
        if (manifestFiles.isNotEmpty()) {
            risks.add(CodeHealthInsight(
                id = "regression-${diff.hashCode()}-manifest",
                type = InsightType.REGRESSION_RISK,
                severity = InsightSeverity.WARNING,
                title = "Android manifest changed",
                description = "AndroidManifest.xml was modified. Verify permissions and component declarations.",
                filePath = manifestFiles.first(),
                suggestedAction = "Review manifest changes carefully before releasing",
                source = "git-diff",
            ))
        }

        val summary = if (risks.isEmpty()) "No regression risks detected."
        else "${risks.size} risk(s) found: ${risks.map { it.title }.joinToString("; ")}"

        log.info("RegressionRiskAnalyzer: $summary")
        return RiskResult(risks, summary)
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): RegressionRiskAnalyzer = project.service()
    }
}
