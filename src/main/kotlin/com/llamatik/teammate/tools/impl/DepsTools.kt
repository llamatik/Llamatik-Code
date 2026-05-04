package com.llamatik.teammate.tools.impl

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.deps.DependencyService
import com.llamatik.teammate.tools.LlamatikTool
import com.llamatik.teammate.tools.ToolCall
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolResult

class DepsListTool(project: Project) : BaseProjectTool(project) {
    override val name = "deps_list"
    override val description = "List project dependencies. No arguments."

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val svc = context.project.service<DependencyService>()
        return ToolResult(name, true, svc.formatForContext())
    }
}

class DepsCheckUpdatesTool(project: Project) : BaseProjectTool(project) {
    override val name = "deps_check_updates"
    override val description = "Show current dependency versions and system. Use this before planning upgrades. No arguments."

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val svc = context.project.service<DependencyService>()
        val system = svc.detectSystem()
            ?: return ToolResult(name, false, "No dependency system detected (no build.gradle.kts/pom.xml/package.json)")
        val deps = svc.listDependencies()
        val text = buildString {
            appendLine("System: ${system.type}  File: ${system.primaryFile}")
            appendLine("Dependencies (${deps.size}):")
            deps.take(50).forEach { d ->
                val alias = if (d.alias.isNotBlank()) "[${d.alias}] " else ""
                appendLine("  $alias${d.coordinates}")
            }
            if (deps.size > 50) appendLine("  … ${deps.size - 50} more")
            appendLine()
            appendLine("To upgrade: review versions above and use write_file/replace_in_file to update ${system.primaryFile}.")
            appendLine("Always run terminal_run with './gradlew build' after updating to verify.")
        }.trim()
        return ToolResult(name, true, text)
    }
}

class DepsPlanUpgradeTool(project: Project) : BaseProjectTool(project) {
    override val name = "deps_plan_upgrade"
    override val description = "Plan a dependency upgrade. Arguments: dep (group:artifact or alias), target_version"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val dep = call.arguments["dep"] ?: return ToolResult(name, false, "Missing dep")
        val targetVersion = call.arguments["target_version"] ?: return ToolResult(name, false, "Missing target_version")
        val svc = context.project.service<DependencyService>()
        val system = svc.detectSystem()
            ?: return ToolResult(name, false, "No dependency system detected")
        val existing = svc.listDependencies().find { d ->
            d.alias == dep || d.artifact == dep || d.coordinates.startsWith(dep)
        }
        val currentVersion = existing?.version ?: "unknown"
        val text = buildString {
            appendLine("Upgrade plan:")
            appendLine("  Dependency: $dep")
            appendLine("  Current version: $currentVersion")
            appendLine("  Target version: $targetVersion")
            appendLine("  File to edit: ${system.primaryFile}")
            appendLine()
            appendLine("Steps:")
            appendLine("  1. Edit ${system.primaryFile} — replace $currentVersion with $targetVersion for $dep")
            appendLine("  2. Run: terminal_run ./gradlew build")
            appendLine("  3. Run: terminal_run ./gradlew test")
            appendLine()
            appendLine("Risk: Check release notes for $dep $targetVersion for breaking changes before applying.")
            appendLine("Use write_file to apply the change after reviewing this plan.")
        }.trim()
        return ToolResult(name, true, text)
    }
}
