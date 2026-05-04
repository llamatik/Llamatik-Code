package com.llamatik.teammate.tools.impl

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.terminal.TerminalService
import com.llamatik.teammate.tools.LlamatikTool
import com.llamatik.teammate.tools.ToolCall
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TerminalAllowedCommandsTool(project: Project) : BaseProjectTool(project) {
    override val name = "terminal_allowed_commands"
    override val description = "List commands the agent can run without user approval. No arguments."

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val svc = context.project.service<TerminalService>()
        return ToolResult(name, true, "Allowed commands:\n" + svc.allowedCommands().joinToString("\n") { "  $it" })
    }
}

class TerminalRunTool(project: Project) : BaseProjectTool(project) {
    override val name = "terminal_run"
    override val description = "Run an allowlisted project command. Arguments: command"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val command = call.arguments["command"] ?: return ToolResult(name, false, "Missing command")
        val svc = context.project.service<TerminalService>()
        if (!TerminalService.isAllowed(command)) {
            return ToolResult(name, false,
                "Command not in allowlist: '$command'. Use terminal_allowed_commands to see what's permitted.")
        }
        val latch = CountDownLatch(1)
        var output: TerminalService.CommandOutput? = null
        svc.run(command, requireApproval = false, onDone = { result ->
            output = result
            latch.countDown()
        })
        latch.await(130, TimeUnit.SECONDS)
        val result = output ?: return ToolResult(name, false, "Command timed out: $command")
        val text = buildString {
            appendLine("Command: ${result.command}")
            appendLine("Exit code: ${result.exitCode}  Duration: ${result.durationMs}ms")
            if (result.stderr.isNotBlank()) { appendLine("Stderr:"); appendLine(result.stderr.take(3000)) }
            if (result.stdout.isNotBlank()) { appendLine("Stdout:"); appendLine(result.stdout.take(6000)) }
        }.trim()
        return ToolResult(name, result.succeeded, text)
    }
}

class TerminalLastOutputTool(project: Project) : BaseProjectTool(project) {
    override val name = "terminal_last_output"
    override val description = "Get output from the last terminal command. Arguments: command (optional)"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val svc = context.project.service<TerminalService>()
        val cmd = call.arguments["command"]
        val out = (if (cmd != null) svc.lastOutput(cmd) else svc.lastOutputAny())
            ?: return ToolResult(name, false, "No recorded output. Run a command first with terminal_run.")
        return ToolResult(name, true, buildString {
            appendLine("Command: ${out.command}")
            appendLine("Exit: ${out.exitCode}")
            if (out.stderr.isNotBlank()) appendLine("Stderr: ${out.stderr.take(2000)}")
            appendLine("Stdout: ${out.stdout.take(4000)}")
        }.trim())
    }
}
