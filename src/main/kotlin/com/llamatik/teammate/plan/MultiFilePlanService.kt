package com.llamatik.teammate.plan

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project

@State(name = "LlamatikMultiFilePlan", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
@Service(Service.Level.PROJECT)
class MultiFilePlanService(private val project: Project) :
    PersistentStateComponent<MultiFilePlanService.State> {

    data class PlannedFile(
        var path: String = "",
        var action: String = "edit",   // edit | create | delete
        var reason: String = "",
        var risk: String = "low",      // low | medium | high
    )

    data class AgentPlan(
        var id: String = "",
        var title: String = "",
        var goal: String = "",
        var files: MutableList<PlannedFile> = mutableListOf(),
        var verificationSteps: MutableList<String> = mutableListOf(),
        var rollbackInfo: String = "",
        var approved: Boolean = false,
        var rejected: Boolean = false,
        var createdAtMs: Long = 0L,
    )

    data class State(
        var plans: MutableList<AgentPlan> = mutableListOf(),
        var requirePlanForMultiFile: Boolean = true,
        var multiFileThreshold: Int = 2,
    )

    private var myState = State()
    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }

    fun currentPlan(): AgentPlan? = myState.plans.lastOrNull { !it.approved && !it.rejected }

    fun addPlan(plan: AgentPlan) {
        plan.createdAtMs = System.currentTimeMillis()
        myState.plans.removeIf { it.id == plan.id }
        myState.plans.add(plan)
        if (myState.plans.size > 20) myState.plans.removeAt(0)
    }

    fun approvePlan(id: String) { myState.plans.find { it.id == id }?.approved = true }
    fun rejectPlan(id: String) { myState.plans.find { it.id == id }?.rejected = true }

    fun formatPlanForDisplay(plan: AgentPlan): String = buildString {
        appendLine("Plan: ${plan.title}")
        appendLine("Goal: ${plan.goal}")
        appendLine()
        appendLine("Files (${plan.files.size}):")
        plan.files.forEach { f ->
            appendLine("  [${f.action.uppercase()}] ${f.path}  risk:${f.risk}")
            if (f.reason.isNotBlank()) appendLine("    → ${f.reason}")
        }
        if (plan.verificationSteps.isNotEmpty()) {
            appendLine()
            appendLine("Verification:")
            plan.verificationSteps.forEach { appendLine("  - $it") }
        }
        if (plan.rollbackInfo.isNotBlank()) { appendLine(); appendLine("Rollback: ${plan.rollbackInfo}") }
    }.trim()

    fun parsePlanFromText(text: String): AgentPlan? {
        // Parse structured plan emitted by the agent in format:
        // PLAN_TITLE: ...
        // PLAN_GOAL: ...
        // FILE: path | action | risk | reason
        val title = Regex("""(?i)PLAN[_\s]TITLE:\s*(.+)""").find(text)?.groupValues?.get(1)?.trim() ?: return null
        val goal = Regex("""(?i)PLAN[_\s]GOAL:\s*(.+)""").find(text)?.groupValues?.get(1)?.trim() ?: ""
        val fileRegex = Regex("""(?i)FILE:\s*([^\|]+)\|([^\|]+)\|([^\|]+)\|(.*)""")
        val files = fileRegex.findAll(text).map { m ->
            PlannedFile(
                path = m.groupValues[1].trim(),
                action = m.groupValues[2].trim().lowercase(),
                risk = m.groupValues[3].trim().lowercase(),
                reason = m.groupValues[4].trim(),
            )
        }.toMutableList()
        val steps = Regex("""(?i)VERIFY:\s*(.+)""").findAll(text).map { it.groupValues[1].trim() }.toMutableList()
        val rollback = Regex("""(?i)ROLLBACK:\s*(.+)""").find(text)?.groupValues?.get(1)?.trim() ?: ""
        return AgentPlan(
            id = java.util.UUID.randomUUID().toString().take(8),
            title = title, goal = goal, files = files,
            verificationSteps = steps, rollbackInfo = rollback,
        )
    }
}
