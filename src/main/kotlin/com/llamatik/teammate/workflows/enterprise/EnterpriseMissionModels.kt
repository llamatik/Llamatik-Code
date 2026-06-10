package com.llamatik.teammate.workflows.enterprise

enum class EnterpriseMissionType {
    FEATURE_IMPLEMENTATION,
    BUG_FIX,
    SAFE_REFACTOR,
    TEST_GENERATION,
    CODE_REVIEW,
    BUILD_FAILURE_REPAIR,
    MIGRATION,
    SECURITY_REVIEW,
    ARCHITECTURE_EXPLANATION,
    DOCUMENTATION,
    UNKNOWN;

    fun displayName(): String = when (this) {
        FEATURE_IMPLEMENTATION -> "Feature Implementation"
        BUG_FIX -> "Bug Fix"
        SAFE_REFACTOR -> "Safe Refactor"
        TEST_GENERATION -> "Test Generation"
        CODE_REVIEW -> "Code Review"
        BUILD_FAILURE_REPAIR -> "Build Failure Repair"
        MIGRATION -> "Migration / Modernization"
        SECURITY_REVIEW -> "Security Review"
        ARCHITECTURE_EXPLANATION -> "Architecture Explanation"
        DOCUMENTATION -> "Documentation"
        UNKNOWN -> "General Mission"
    }
}

enum class EnterpriseSafetyLevel {
    OBSERVE_ONLY,
    PLAN_ONLY,
    APPROVAL_REQUIRED,
    AUTONOMOUS_WITH_DIFF_APPROVAL;

    fun displayName(): String = when (this) {
        OBSERVE_ONLY -> "Observe Only"
        PLAN_ONLY -> "Plan Only"
        APPROVAL_REQUIRED -> "Approval Required"
        AUTONOMOUS_WITH_DIFF_APPROVAL -> "Autonomous with Diff Approval"
    }
}

enum class EnterpriseRiskLevel {
    LOW, MEDIUM, HIGH, CRITICAL;

    fun displayName(): String = name.lowercase().replaceFirstChar { it.uppercase() }
}

data class EnterpriseRisk(
    val title: String,
    val description: String,
    val level: EnterpriseRiskLevel,
    val filePath: String? = null,
)

data class EnterpriseMissionPlan(
    val id: String,
    val title: String,
    val missionType: EnterpriseMissionType,
    val safetyLevel: EnterpriseSafetyLevel,
    val userRequest: String,
    val targetPaths: List<String>,
    val affectedFiles: List<String>,
    val requiredContextTools: List<String>,
    val allowedMutationTools: List<String>,
    val blockedTools: List<String>,
    val risks: List<EnterpriseRisk>,
    val verificationSteps: List<String>,
    val auditNotes: List<String>,
    val rollbackPlan: String,
    val estimatedRiskLevel: EnterpriseRiskLevel,
    val createdAtEpochMs: Long,
)

data class EnterpriseMissionReport(
    val missionId: String,
    val summary: String,
    val changedFiles: List<String>,
    val verificationSummary: String,
    val remainingRisks: List<EnterpriseRisk>,
    val auditTrail: List<String>,
    val suggestedCommitMessage: String?,
)
