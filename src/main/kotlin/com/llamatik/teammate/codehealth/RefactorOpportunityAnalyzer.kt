package com.llamatik.teammate.codehealth

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.util.PluginLogger

@Service(Service.Level.PROJECT)
class RefactorOpportunityAnalyzer(private val project: Project) {

    private val log = PluginLogger.logger<RefactorOpportunityAnalyzer>()

    data class RefactorSuggestion(
        val insight: CodeHealthInsight,
        val filesInvolved: List<String>,
        val risk: String,
        val expectedBenefit: String,
    )

    fun analyzeFile(filePath: String, content: String): List<RefactorSuggestion> {
        val suggestions = mutableListOf<RefactorSuggestion>()
        val lines = content.lines()

        val funRegex = Regex("""^\s*(fun |private fun |internal fun |override fun |protected fun )""")
        var functionStart = -1
        var bracketDepth = 0
        var inFunction = false

        for ((i, line) in lines.withIndex()) {
            if (funRegex.containsMatchIn(line) && !inFunction) {
                functionStart = i
                bracketDepth = 0
                inFunction = true
            }
            if (inFunction) {
                bracketDepth += line.count { it == '{' } - line.count { it == '}' }
                if (bracketDepth <= 0 && i > functionStart) {
                    val funLength = i - functionStart
                    if (funLength > 40) {
                        suggestions.add(RefactorSuggestion(
                            insight = CodeHealthInsight(
                                id = "refactor-${filePath.hashCode()}-longfun-$functionStart",
                                type = InsightType.REFACTOR_OPPORTUNITY,
                                severity = InsightSeverity.INFO,
                                title = "Long function (~$funLength lines) near line ${functionStart + 1}",
                                description = "This function is approximately $funLength lines long. Consider extracting helper functions.",
                                filePath = filePath,
                                line = functionStart + 1,
                                suggestedAction = "Extract parts of this function into smaller, named helpers",
                            ),
                            filesInvolved = listOf(filePath),
                            risk = "LOW",
                            expectedBenefit = "Improved readability and testability",
                        ))
                    }
                    inFunction = false
                }
            }
        }

        // Duplicated error handling pattern
        val catchCount = Regex("""catch\s*\(""").findAll(content).count()
        if (catchCount >= 4) {
            suggestions.add(RefactorSuggestion(
                insight = CodeHealthInsight(
                    id = "refactor-${filePath.hashCode()}-dupcatch",
                    type = InsightType.REFACTOR_OPPORTUNITY,
                    severity = InsightSeverity.INFO,
                    title = "Repeated catch blocks ($catchCount)",
                    description = "Found $catchCount catch blocks. Consider centralizing error handling.",
                    filePath = filePath,
                    suggestedAction = "Extract a shared error handler or use a result wrapper type",
                ),
                filesInvolved = listOf(filePath),
                risk = "LOW",
                expectedBenefit = "Reduced duplication in error handling",
            ))
        }

        log.info("RefactorOpportunityAnalyzer: ${suggestions.size} suggestions for $filePath")
        return suggestions
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): RefactorOpportunityAnalyzer = project.service()
    }
}
