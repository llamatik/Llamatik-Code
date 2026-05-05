package com.llamatik.teammate.tools.impl

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.snapshot.ProjectSnapshotService
import com.llamatik.teammate.tools.LlamatikTool
import com.llamatik.teammate.tools.ToolCall
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolResult

class ProjectSnapshotGetTool(project: Project) : BaseProjectTool(project) {
    override val name = "project_snapshot_get"
    override val description = "Return cached project overview (tech stack, modules, risks). Build one if missing."

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val svc = context.project.service<ProjectSnapshotService>()
        val cached = svc.getCached()
        return if (cached != null) {
            ToolResult(name, true, cached.toMarkdown())
        } else {
            val fresh = runCatching { svc.buildSnapshotSync() }.getOrElse { e ->
                return ToolResult(name, false, "Snapshot build failed: ${e.message}")
            }
            ToolResult(name, true, fresh.toMarkdown())
        }
    }
}

class ProjectSnapshotRefreshTool(project: Project) : BaseProjectTool(project) {
    override val name = "project_snapshot_refresh"
    override val description = "Rebuild the project snapshot synchronously and return it."

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val svc = context.project.service<ProjectSnapshotService>()
        val fresh = runCatching { svc.buildSnapshotSync() }.getOrElse { e ->
            return ToolResult(name, false, "Snapshot refresh failed: ${e.message}")
        }
        return ToolResult(name, true, fresh.toMarkdown())
    }
}
