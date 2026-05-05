package com.llamatik.teammate.tools.impl

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.plan.MultiFilePlanService
import com.llamatik.teammate.tools.LlamatikTool
import com.llamatik.teammate.tools.ToolCall
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolResult

class PlanPreviewTool(project: Project) : BaseProjectTool(project) {
    override val name = "plan_preview"
    override val description = "Preview a multi-file edit plan before applying changes. Arguments: plan_text (structured plan)"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val planText = call.arguments["plan_text"] ?: return ToolResult(name, false, "Missing plan_text")
        val svc = context.project.service<MultiFilePlanService>()
        val plan = svc.parsePlanFromText(planText)
            ?: return ToolResult(name, false,
                "Could not parse plan. Use format:\nPLAN_TITLE: ...\nPLAN_GOAL: ...\nFILE: path|action|risk|reason\nVERIFY: ...\nROLLBACK: ...")
        svc.addPlan(plan)
        val text = buildString {
            appendLine(svc.formatPlanForDisplay(plan))
            appendLine()
            appendLine("Plan ID: ${plan.id}")
            appendLine("Status: Awaiting user approval before files are modified.")
            appendLine("Use plan_approve or plan_reject to proceed.")
        }.trim()
        return ToolResult(name, true, text)
    }
}

class PlanCurrentTool(project: Project) : BaseProjectTool(project) {
    override val name = "plan_current"
    override val description = "Show the current pending multi-file plan. No arguments."

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val svc = context.project.service<MultiFilePlanService>()
        val plan = svc.currentPlan()
            ?: return ToolResult(name, true, "No pending plan. Use plan_preview to create one.")
        return ToolResult(name, true, svc.formatPlanForDisplay(plan))
    }
}

class PlanApproveTool(project: Project) : BaseProjectTool(project) {
    override val name = "plan_approve"
    override val description = "Approve a pending plan so the agent can proceed with edits. Arguments: id"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val id = call.arguments["id"] ?: return ToolResult(name, false, "Missing id")
        context.project.service<MultiFilePlanService>().approvePlan(id)
        return ToolResult(name, true, "Plan $id approved. Proceed with file edits.")
    }
}

class PlanRejectTool(project: Project) : BaseProjectTool(project) {
    override val name = "plan_reject"
    override val description = "Reject a pending plan. Arguments: id"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val id = call.arguments["id"] ?: return ToolResult(name, false, "Missing id")
        context.project.service<MultiFilePlanService>().rejectPlan(id)
        return ToolResult(name, true, "Plan $id rejected.")
    }
}
