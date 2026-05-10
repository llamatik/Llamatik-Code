package com.llamatik.teammate.codehealth

data class CodeHealthInsight(
    val id: String,
    val type: InsightType,
    val severity: InsightSeverity,
    val title: String,
    val description: String,
    val filePath: String,
    val line: Int = 0,
    val confidence: Double = 0.8,
    val evidence: List<String> = emptyList(),
    val suggestedAction: String = "",
    val canAutoFix: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val source: String = "static",
)

enum class InsightType {
    COMPLEXITY, MISSING_TESTS, DUPLICATION, DEPRECATED_API,
    ARCHITECTURE_DRIFT, ERROR_HANDLING, REGRESSION_RISK,
    REFACTOR_OPPORTUNITY, DEPENDENCY_RISK, COMMIT_RISK
}

enum class InsightSeverity { INFO, WARNING, HIGH }

data class WatchedArea(
    val id: String,
    val path: String,
    val type: WatchedAreaType,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
)

enum class WatchedAreaType { FILE, DIRECTORY, PACKAGE, MODULE }

data class SessionContext(
    val currentBranch: String = "",
    val recentFiles: List<String> = emptyList(),
    val recentPrompts: List<String> = emptyList(),
    val watchedAreas: List<String> = emptyList(),
    val recentBuildFailure: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)
