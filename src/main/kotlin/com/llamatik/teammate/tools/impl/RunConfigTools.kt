package com.llamatik.teammate.tools.impl

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.run.RunConfigService
import com.llamatik.teammate.tools.LlamatikTool
import com.llamatik.teammate.tools.ToolCall
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolResult

class RunConfigListTool(project: Project) : BaseProjectTool(project) {
    override val name = "run_config_list"
    override val description = "List available IDE run configurations. No arguments needed."

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val svc = context.project.service<RunConfigService>()
        val configs = svc.listConfigs()
        if (configs.isEmpty()) return ToolResult(name, true, "No run configurations found in this project")
        val text = configs.joinToString("\n") { c -> "- ${c.name}  [${c.type}]" }
        return ToolResult(name, true, "Run configurations:\n$text")
    }
}

class RunConfigLastOutputTool(project: Project) : BaseProjectTool(project) {
    override val name = "run_config_last_output"
    override val description = "Get output from the last run of a named configuration. Arguments: name"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val name_ = call.arguments["name"] ?: return ToolResult(name, false, "Missing name")
        val svc = context.project.service<RunConfigService>()
        val output = svc.getLastOutput(name_)
            ?: return ToolResult(name, false, "No recorded output for '$name_'. Run it first with run_config_execute.")
        val text = buildString {
            appendLine("Config: ${output.configName}")
            appendLine("Exit code: ${output.exitCode} (${if (output.succeeded) "success" else "failure"})")
            if (output.stderr.isNotBlank()) { appendLine("Stderr:"); appendLine(output.stderr.take(3000)) }
            if (output.stdout.isNotBlank()) { appendLine("Stdout:"); appendLine(output.stdout.take(3000)) }
        }.trim()
        return ToolResult(name, true, text)
    }
}

class RunConfigAnalyzeFailureTool(project: Project) : BaseProjectTool(project) {
    override val name = "run_config_analyze_failure"
    override val description = "Analyze failure from the last run of a configuration. Arguments: name"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val configName = call.arguments["name"] ?: return ToolResult(name, false, "Missing name")
        val svc = context.project.service<RunConfigService>()
        val output = svc.getLastOutput(configName)
            ?: return ToolResult(name, false, "No recorded output for '$configName'")
        return ToolResult(name, true, svc.analyzeFailure(output))
    }
}
