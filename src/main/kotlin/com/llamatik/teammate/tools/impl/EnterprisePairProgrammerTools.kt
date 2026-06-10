package com.llamatik.teammate.tools.impl

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.codehealth.CodeHealthBudgetService
import com.llamatik.teammate.codehealth.CodeHealthInsightStore
import com.llamatik.teammate.diagnostics.DiagnosticsService
import com.llamatik.teammate.git.GitContextService
import com.llamatik.teammate.snapshot.ProjectSnapshotService
import com.llamatik.teammate.tools.LlamatikTool
import com.llamatik.teammate.tools.ToolCall
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolResult
import com.llamatik.teammate.workflows.enterprise.EnterpriseMissionReport
import com.llamatik.teammate.workflows.enterprise.EnterpriseMissionType
import com.llamatik.teammate.workflows.enterprise.EnterprisePairProgrammerService
import com.llamatik.teammate.workflows.enterprise.EnterpriseRisk
import com.llamatik.teammate.workflows.enterprise.EnterpriseRiskLevel
import com.llamatik.teammate.workflows.enterprise.EnterpriseSafetyLevel

/** Registers a new enterprise coding mission and returns the structured plan. */
class EnterpriseMissionCreateTool(private val project: Project) : LlamatikTool {
    override val name = "enterprise_mission_create"
    override val description =
        "Create an enterprise coding mission plan. Args: user_request (required), target_path (optional), mission_type (optional), safety_level (optional)"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val userRequest = call.arguments["user_request"]?.trim()
            ?: return ToolResult(name, false, "Missing required argument: user_request")

        val missionTypeArg = call.arguments["mission_type"]
        val safetyLevelArg = call.arguments["safety_level"]
        val targetPath = call.arguments["target_path"]

        val svc = project.service<EnterprisePairProgrammerService>()

        val missionType = missionTypeArg?.let {
            runCatching { EnterpriseMissionType.valueOf(it.uppercase()) }.getOrNull()
        }
        val safetyLevel = safetyLevelArg?.let {
            runCatching { EnterpriseSafetyLevel.valueOf(it.uppercase()) }.getOrNull()
        }

        val plan = svc.createMissionPlan(
            userRequest = userRequest,
            targetPath = targetPath,
            missionTypeOverride = missionType,
            safetyLevelOverride = safetyLevel,
        )

        return ToolResult(name, true, buildString {
            appendLine("Mission created successfully.")
            appendLine()
            appendLine("Mission ID: ${plan.id}")
            appendLine("Title: ${plan.title}")
            appendLine("Type: ${plan.missionType.displayName()}")
            appendLine("Safety Level: ${plan.safetyLevel.displayName()}")
            appendLine("Estimated Risk: ${plan.estimatedRiskLevel.displayName()}")
            appendLine()
            if (plan.targetPaths.isNotEmpty()) {
                appendLine("Target paths: ${plan.targetPaths.joinToString(", ")}")
            }
            appendLine()
            appendLine("Required context tools: ${plan.requiredContextTools.joinToString(", ")}")
            appendLine("Allowed mutation tools: ${plan.allowedMutationTools.ifEmpty { listOf("none") }.joinToString(", ")}")
            if (plan.blockedTools.isNotEmpty()) {
                appendLine("BLOCKED tools: ${plan.blockedTools.joinToString(", ")}")
            }
            appendLine()
            if (plan.risks.isNotEmpty()) {
                appendLine("Detected risks:")
                plan.risks.forEach { risk ->
                    appendLine("  [${risk.level.displayName()}] ${risk.title}: ${risk.description}")
                }
                appendLine()
            }
            appendLine("Verification steps:")
            plan.verificationSteps.forEachIndexed { i, step -> appendLine("  ${i + 1}. $step") }
            appendLine()
            appendLine("Rollback plan: ${plan.rollbackPlan}")
            appendLine()
            appendLine("Next: Call enterprise_mission_context to gather project context, then proceed with the mission workflow.")
        })
    }
}

/** Gathers relevant project context for an active enterprise mission. */
class EnterpriseMissionContextTool(private val project: Project) : LlamatikTool {
    override val name = "enterprise_mission_context"
    override val description = "Gather project context for an enterprise mission. Args: mission_id (required)"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val missionId = call.arguments["mission_id"]?.trim()
            ?: return ToolResult(name, false, "Missing required argument: mission_id")

        val svc = project.service<EnterprisePairProgrammerService>()
        val entry = svc.store.get(missionId)
            ?: return ToolResult(name, false, "Mission not found: $missionId")

        val plan = entry.plan
        svc.store.addAuditEvent(missionId, "context_gathered")

        return ToolResult(name, true, buildString {
            appendLine("=== Enterprise Mission Context ===")
            appendLine("Mission: ${plan.title}")
            appendLine("Type: ${plan.missionType.displayName()}")
            appendLine("Safety: ${plan.safetyLevel.displayName()}")
            appendLine()

            // Git context
            val gitSvc = runCatching { project.service<GitContextService>() }.getOrNull()
            if (gitSvc != null && gitSvc.isGitRepo()) {
                appendLine("--- Git status ---")
                val gitStatus = runCatching { gitSvc.status() }.getOrNull()
                if (gitStatus != null) {
                    appendLine("Branch: ${gitStatus.branch}")
                    if (gitStatus.staged.isNotEmpty()) appendLine("Staged: ${gitStatus.staged.joinToString(", ")}")
                    if (gitStatus.unstaged.isNotEmpty()) appendLine("Unstaged: ${gitStatus.unstaged.joinToString(", ")}")
                    if (gitStatus.untracked.isNotEmpty()) appendLine("Untracked: ${gitStatus.untracked.size} file(s)")
                } else {
                    appendLine("Git status unavailable.")
                }
                appendLine()
            }

            // Code health
            val healthSvc = runCatching { project.service<CodeHealthBudgetService>() }.getOrNull()
            val healthStore = runCatching { project.service<CodeHealthInsightStore>() }.getOrNull()
            if (healthSvc != null && healthStore != null && healthSvc.isEnabled()) {
                appendLine("--- Code health ---")
                val highInsights = healthStore.allInsights()
                    .filter { it.severity.ordinal >= 1 }
                    .take(5)
                if (highInsights.isEmpty()) {
                    appendLine("No high-priority code health issues detected.")
                } else {
                    highInsights.forEach { insight ->
                        appendLine("  [${insight.severity}] ${insight.title} (${insight.filePath ?: "project-wide"})")
                    }
                }
                appendLine()
            }

            // Diagnostics snapshot
            val diagSvc = runCatching { project.service<DiagnosticsService>() }.getOrNull()
            if (diagSvc != null) {
                appendLine("--- Diagnostics summary ---")
                val report = runCatching { diagSvc.run() }.getOrNull()
                if (report != null) {
                    val formatted = diagSvc.formatReport(report, includeSensitive = false)
                    appendLine(formatted.take(600))
                } else {
                    appendLine("Diagnostics unavailable.")
                }
                appendLine()
            }

            // Project snapshot
            val snapshotSvc = runCatching { project.service<ProjectSnapshotService>() }.getOrNull()
            if (snapshotSvc != null) {
                appendLine("--- Project snapshot ---")
                val snapshot = runCatching { snapshotSvc.getCached() ?: snapshotSvc.buildSnapshotSync() }.getOrNull()
                if (snapshot != null) {
                    appendLine("Tech stack: ${snapshot.techStack.joinToString(", ")}")
                    appendLine("Architecture: ${snapshot.architectureStyle}")
                    if (snapshot.modules.isNotEmpty()) {
                        appendLine("Modules: ${snapshot.modules.take(5).joinToString(", ")}")
                    }
                }
                appendLine()
            }

            appendLine("--- Suggested next tools ---")
            plan.requiredContextTools.forEach { tool -> appendLine("  - $tool") }
            appendLine()
            appendLine("Risks identified:")
            if (plan.risks.isEmpty()) {
                appendLine("  No risks pre-classified. Continue with caution.")
            } else {
                plan.risks.forEach { risk ->
                    appendLine("  [${risk.level.displayName()}] ${risk.title}")
                }
            }
        })
    }
}

/** Returns audit metadata for an enterprise mission — no raw source code included. */
class EnterpriseMissionAuditTool(private val project: Project) : LlamatikTool {
    override val name = "enterprise_mission_audit"
    override val description = "Get audit metadata for an enterprise mission. Args: mission_id (required)"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val missionId = call.arguments["mission_id"]?.trim()
            ?: return ToolResult(name, false, "Missing required argument: mission_id")

        val svc = project.service<EnterprisePairProgrammerService>()
        val entry = svc.store.get(missionId)
            ?: return ToolResult(name, false, "Mission not found: $missionId")

        val plan = entry.plan

        return ToolResult(name, true, buildString {
            appendLine("=== Enterprise Mission Audit ===")
            appendLine("Mission ID: $missionId")
            appendLine("Title: ${plan.title}")
            appendLine("Created: ${java.util.Date(plan.createdAtEpochMs)}")
            entry.completedMs?.let { appendLine("Completed: ${java.util.Date(it)}") }
            appendLine()
            appendLine("Safety level: ${plan.safetyLevel.displayName()}")
            appendLine("Estimated risk: ${plan.estimatedRiskLevel.displayName()}")
            appendLine()
            appendLine("Allowed mutation tools: ${plan.allowedMutationTools.ifEmpty { listOf("none") }.joinToString(", ")}")
            appendLine("Blocked tools: ${plan.blockedTools.ifEmpty { listOf("none") }.joinToString(", ")}")
            appendLine()
            if (entry.auditEvents.isNotEmpty()) {
                appendLine("Audit events:")
                entry.auditEvents.forEach { event -> appendLine("  $event") }
            } else {
                appendLine("No audit events recorded yet.")
            }
            appendLine()
            if (plan.risks.isNotEmpty()) {
                appendLine("Risks at mission start:")
                plan.risks.forEach { risk ->
                    appendLine("  [${risk.level.displayName()}] ${risk.title}: ${risk.description}")
                    risk.filePath?.let { appendLine("    File: $it") }
                }
            }
            appendLine()
            appendLine("Affected files declared: ${plan.affectedFiles.ifEmpty { listOf("not yet recorded") }.joinToString(", ")}")
            appendLine()
            appendLine("Verification steps planned:")
            plan.verificationSteps.forEachIndexed { i, step -> appendLine("  ${i + 1}. $step") }
            appendLine()
            appendLine("Rollback: ${plan.rollbackPlan}")
        })
    }
}

/** Generates the final report for a completed enterprise mission. */
class EnterpriseMissionReportTool(private val project: Project) : LlamatikTool {
    override val name = "enterprise_mission_report"
    override val description =
        "Generate the final report for an enterprise mission. Args: mission_id (required), summary (optional), changed_files (optional, comma-separated), verification_summary (optional), commit_message (optional)"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val missionId = call.arguments["mission_id"]?.trim()
            ?: return ToolResult(name, false, "Missing required argument: mission_id")

        val svc = project.service<EnterprisePairProgrammerService>()
        val entry = svc.store.get(missionId)
            ?: return ToolResult(name, false, "Mission not found: $missionId")

        val plan = entry.plan
        val summary = call.arguments["summary"]?.trim()
            ?: "Mission '${plan.title}' completed."
        val changedFiles = call.arguments["changed_files"]
            ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
            ?: emptyList()
        val verificationSummary = call.arguments["verification_summary"]?.trim()
            ?: "Verification not recorded."
        val commitMessage = call.arguments["commit_message"]?.trim()

        val report = EnterpriseMissionReport(
            missionId = missionId,
            summary = summary,
            changedFiles = changedFiles,
            verificationSummary = verificationSummary,
            remainingRisks = plan.risks.filter { it.level.ordinal >= EnterpriseRiskLevel.MEDIUM.ordinal },
            auditTrail = entry.auditEvents.toList(),
            suggestedCommitMessage = commitMessage,
        )

        svc.store.complete(missionId, report)
        svc.store.addAuditEvent(missionId, "report_generated")

        return ToolResult(name, true, buildString {
            appendLine("=== Enterprise Mission Report ===")
            appendLine("Mission ID: $missionId")
            appendLine("Title: ${plan.title}")
            appendLine()
            appendLine("Summary: $summary")
            appendLine()
            if (changedFiles.isNotEmpty()) {
                appendLine("Changed files:")
                changedFiles.forEach { f -> appendLine("  - $f") }
                appendLine()
            } else {
                appendLine("Changed files: none recorded")
                appendLine()
            }
            appendLine("Verification: $verificationSummary")
            appendLine()
            if (report.remainingRisks.isNotEmpty()) {
                appendLine("Remaining risks (MEDIUM+):")
                report.remainingRisks.forEach { risk ->
                    appendLine("  [${risk.level.displayName()}] ${risk.title}: ${risk.description}")
                }
                appendLine()
            } else {
                appendLine("No remaining medium+ risks.")
                appendLine()
            }
            appendLine("Rollback instructions: ${plan.rollbackPlan}")
            appendLine()
            if (commitMessage != null) {
                appendLine("Suggested commit message:")
                appendLine(commitMessage)
            }
            appendLine()
            appendLine("Audit trail: ${entry.auditEvents.size} events recorded.")
            appendLine("Trust badges: [Local only] [Diff approval] [Audit report enabled]")
        })
    }
}

