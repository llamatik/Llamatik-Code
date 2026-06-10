package com.llamatik.teammate.workflows.enterprise

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.settings.LlamatikAppSettings
import java.util.UUID

/**
 * Coordinates mission classification, plan creation, and agent-ready prompt generation
 * for the Offline Enterprise Pair Programmer workflow.
 *
 * This service inspects project context but never writes files directly.
 */
@Service(Service.Level.PROJECT)
class EnterprisePairProgrammerService(private val project: Project) {

    val store = EnterpriseMissionStore()

    fun classifyMission(userRequest: String): EnterpriseMissionType {
        val lower = userRequest.lowercase()
        return when {
            lower.containsAny("build fail", "build error", "compile error", "gradle fail", "maven fail") -> EnterpriseMissionType.BUILD_FAILURE_REPAIR
            lower.containsAny("implement", "feature", "add support", "create new") -> EnterpriseMissionType.FEATURE_IMPLEMENTATION
            lower.containsAny("fix", "bug", "error", "crash", "broken", "exception", "npe") -> EnterpriseMissionType.BUG_FIX
            lower.containsAny("refactor", "clean up", "cleanup", "improve structure", "rename", "extract") -> EnterpriseMissionType.SAFE_REFACTOR
            lower.containsAny("test", "unit test", "spec", "coverage") -> EnterpriseMissionType.TEST_GENERATION
            lower.containsAny("review", "check changes", "before commit", "pr review", "code review") -> EnterpriseMissionType.CODE_REVIEW
            lower.containsAny("migrate", "modernize", "upgrade", "deprecat", "legacy") -> EnterpriseMissionType.MIGRATION
            lower.containsAny("security", "vulnerability", "injection", "auth", "permission") -> EnterpriseMissionType.SECURITY_REVIEW
            lower.containsAny("explain", "understand", "architecture", "how does", "what is") -> EnterpriseMissionType.ARCHITECTURE_EXPLANATION
            lower.containsAny("document", "javadoc", "kdoc", "comment") -> EnterpriseMissionType.DOCUMENTATION
            else -> EnterpriseMissionType.UNKNOWN
        }
    }

    fun defaultSafetyLevel(): EnterpriseSafetyLevel {
        val settings = service<LlamatikAppSettings>().data()
        return runCatching {
            EnterpriseSafetyLevel.valueOf(settings.enterpriseDefaultSafetyLevel)
        }.getOrElse { EnterpriseSafetyLevel.APPROVAL_REQUIRED }
    }

    fun createMissionPlan(
        userRequest: String,
        targetPath: String? = null,
        missionTypeOverride: EnterpriseMissionType? = null,
        safetyLevelOverride: EnterpriseSafetyLevel? = null,
    ): EnterpriseMissionPlan {
        val missionType = missionTypeOverride ?: classifyMission(userRequest)
        val safetyLevel = safetyLevelOverride ?: defaultSafetyLevel()
        val settings = service<LlamatikAppSettings>().data()

        val requiredContextTools = buildContextTools(missionType)
        val allowedMutationTools = if (safetyLevel == EnterpriseSafetyLevel.OBSERVE_ONLY || safetyLevel == EnterpriseSafetyLevel.PLAN_ONLY) {
            emptyList()
        } else {
            listOf("write_file", "replace_in_file", "create_file", "apply_patch")
        }
        val blockedTools = if (!settings.enterpriseAllowExternalKnowledge) {
            listOf("external_search", "external_search_official_docs", "external_search_github_issues",
                "external_read_page", "external_read_pages",
                "research_plan", "research_execute", "research_summarize",
                "error_research_analyze", "error_research_fix")
        } else emptyList()

        val risks = detectRisks(missionType, targetPath)
        val estimatedRisk = risks.maxByOrNull { it.level.ordinal }?.level ?: EnterpriseRiskLevel.LOW

        val verificationSteps = buildVerificationSteps(missionType)
        val rollbackPlan = buildRollbackPlan(missionType, targetPath)

        val plan = EnterpriseMissionPlan(
            id = UUID.randomUUID().toString(),
            title = "${missionType.displayName()}: ${userRequest.take(60)}",
            missionType = missionType,
            safetyLevel = safetyLevel,
            userRequest = userRequest,
            targetPaths = listOfNotNull(targetPath),
            affectedFiles = emptyList(),
            requiredContextTools = requiredContextTools,
            allowedMutationTools = allowedMutationTools,
            blockedTools = blockedTools,
            risks = risks,
            verificationSteps = verificationSteps,
            auditNotes = listOf("Mission created. Safety level: ${safetyLevel.displayName()}"),
            rollbackPlan = rollbackPlan,
            estimatedRiskLevel = estimatedRisk,
            createdAtEpochMs = System.currentTimeMillis(),
        )

        store.store(plan)
        return plan
    }

    fun buildMissionPrompt(plan: EnterpriseMissionPlan): String = buildString {
        appendLine("You are running Offline Enterprise Pair Programmer mode.")
        appendLine()
        appendLine("Mission ID: ${plan.id}")
        appendLine("Mission Type: ${plan.missionType.displayName()}")
        appendLine("Safety Level: ${plan.safetyLevel.displayName()}")
        appendLine("Risk Level: ${plan.estimatedRiskLevel.displayName()}")
        appendLine()
        appendLine("Mission:")
        appendLine(plan.userRequest)
        appendLine()
        if (plan.targetPaths.isNotEmpty()) {
            appendLine("Target paths: ${plan.targetPaths.joinToString(", ")}")
        }
        appendLine()
        if (plan.risks.isNotEmpty()) {
            appendLine("Known risks:")
            plan.risks.forEach { risk ->
                appendLine("  - [${risk.level.displayName()}] ${risk.title}: ${risk.description}")
            }
            appendLine()
        }
        appendLine("Required workflow:")
        appendLine("1. Call enterprise_mission_create to register this mission.")
        appendLine("2. Call enterprise_mission_context to gather project context before editing.")
        appendLine("3. Use read-only tools first: read_file, git_status, git_diff, diagnostics_run, code_health_status.")
        appendLine("4. Explain detected risks.")
        appendLine("5. Create a step-by-step plan. Do NOT write code yet.")
        appendLine("6. Apply small, reviewable patches using write_file or replace_in_file.")
        appendLine("7. Show the diff using show_diff.")
        appendLine("8. Verify: run tests or build using run_config_last_output or terminal_run when possible.")
        appendLine("9. Call enterprise_mission_report with a summary, changed files, and commit message.")
        appendLine("10. Include rollback instructions.")
        if (plan.blockedTools.isNotEmpty()) {
            appendLine()
            appendLine("BLOCKED tools (do NOT call these): ${plan.blockedTools.joinToString(", ")}")
        }
        if (plan.safetyLevel == EnterpriseSafetyLevel.PLAN_ONLY) {
            appendLine()
            appendLine("PLAN ONLY mode: Do NOT write any files. Only produce a plan.")
        }
        if (plan.safetyLevel == EnterpriseSafetyLevel.OBSERVE_ONLY) {
            appendLine()
            appendLine("OBSERVE ONLY mode: Do NOT write or plan changes. Only read and report.")
        }
        appendLine()
        appendLine("Rollback plan: ${plan.rollbackPlan}")
        appendLine()
        appendLine("Preserve all existing behavior unless the mission explicitly requires changing it.")
        appendLine("External knowledge is disabled unless allowed by settings.")
    }

    private fun buildContextTools(type: EnterpriseMissionType): List<String> {
        val base = listOf("read_file", "list_files", "search_code", "git_status", "git_diff")
        return when (type) {
            EnterpriseMissionType.BUG_FIX, EnterpriseMissionType.BUILD_FAILURE_REPAIR ->
                base + listOf("diagnostics_run", "run_config_last_output", "build_failure_parse")
            EnterpriseMissionType.TEST_GENERATION ->
                base + listOf("psi_file_structure", "tests_missing_analyze")
            EnterpriseMissionType.CODE_REVIEW ->
                base + listOf("git_changed_files", "before_commit_review", "code_health_status")
            EnterpriseMissionType.SAFE_REFACTOR ->
                base + listOf("psi_find_usages", "regression_risk_analyze", "refactor_analyze_file")
            EnterpriseMissionType.ARCHITECTURE_EXPLANATION ->
                base + listOf("project_snapshot_get", "psi_file_structure", "index_relevant_files")
            else -> base + listOf("project_snapshot_get", "code_health_status")
        }
    }

    private fun detectRisks(type: EnterpriseMissionType, targetPath: String?): List<EnterpriseRisk> {
        val risks = mutableListOf<EnterpriseRisk>()
        when (type) {
            EnterpriseMissionType.SAFE_REFACTOR -> {
                risks += EnterpriseRisk("Breaking public API", "Refactoring may change method signatures used by callers", EnterpriseRiskLevel.HIGH, targetPath)
                risks += EnterpriseRisk("Regression risk", "Behavioral changes may break existing tests", EnterpriseRiskLevel.MEDIUM, targetPath)
            }
            EnterpriseMissionType.MIGRATION -> {
                risks += EnterpriseRisk("Deprecated API removal", "Migration may remove APIs still used by other modules", EnterpriseRiskLevel.HIGH, targetPath)
                risks += EnterpriseRisk("Runtime compatibility", "Migrated code may require runtime version changes", EnterpriseRiskLevel.MEDIUM)
            }
            EnterpriseMissionType.BUILD_FAILURE_REPAIR -> {
                risks += EnterpriseRisk("Cascading build changes", "Build fix may require changes in multiple files", EnterpriseRiskLevel.MEDIUM)
            }
            EnterpriseMissionType.FEATURE_IMPLEMENTATION -> {
                risks += EnterpriseRisk("Integration side effects", "New feature may interact with existing state", EnterpriseRiskLevel.LOW, targetPath)
            }
            EnterpriseMissionType.SECURITY_REVIEW -> {
                risks += EnterpriseRisk("Security vulnerability", "Security issues may require immediate remediation", EnterpriseRiskLevel.CRITICAL, targetPath)
            }
            else -> {}
        }
        return risks
    }

    private fun buildVerificationSteps(type: EnterpriseMissionType): List<String> = when (type) {
        EnterpriseMissionType.BUG_FIX -> listOf("Run diagnostics", "Run affected tests", "Confirm error no longer appears")
        EnterpriseMissionType.TEST_GENERATION -> listOf("Run generated tests", "Confirm tests pass")
        EnterpriseMissionType.BUILD_FAILURE_REPAIR -> listOf("Run build", "Confirm build succeeds")
        EnterpriseMissionType.SAFE_REFACTOR -> listOf("Run tests", "Check for compile errors", "Review diff for regressions")
        EnterpriseMissionType.CODE_REVIEW -> listOf("Review git diff", "Check code health insights")
        else -> listOf("Review diff", "Run available tests", "Check diagnostics")
    }

    private fun buildRollbackPlan(type: EnterpriseMissionType, targetPath: String?): String = buildString {
        append("Use git to revert changes: ")
        if (targetPath != null) append("`git checkout -- $targetPath`") else append("`git checkout -- .`")
        append(" or `git stash` to temporarily undo all uncommitted changes.")
        if (type == EnterpriseMissionType.MIGRATION) {
            append(" For migrations, restore the original dependency versions in build files.")
        }
    }

    private fun String.containsAny(vararg terms: String): Boolean = terms.any { contains(it) }
}
