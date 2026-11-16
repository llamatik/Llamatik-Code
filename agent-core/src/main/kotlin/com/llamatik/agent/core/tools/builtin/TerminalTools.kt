package com.llamatik.agent.core.tools.builtin

import com.llamatik.agent.core.host.CommandAllowlist
import com.llamatik.agent.core.tools.LlamatikTool
import com.llamatik.agent.core.tools.ToolCall
import com.llamatik.agent.core.tools.ToolExecutionContext
import com.llamatik.agent.core.tools.ToolResult

class TerminalAllowedCommandsTool : LlamatikTool {
    override val name = "terminal_allowed_commands"
    override val description = "List commands the agent can run without user approval. No arguments."

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult =
        ToolResult(name, true, "Allowed commands:\n" + CommandAllowlist.allowedCommands().joinToString("\n") { "  $it" })
}

class TerminalRunTool : LlamatikTool {
    override val name = "terminal_run"
    override val description = "Run an allowlisted project command. Arguments: command"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val command = call.arguments["command"] ?: return ToolResult(name, false, "Missing command")
        if (!CommandAllowlist.isAllowed(command)) {
            return ToolResult(name, false,
                "Command not in allowlist: '$command'. Use terminal_allowed_commands to see what's permitted.")
        }
        val result = context.env.shell.run(command, context.env.files.rootPath, COMMAND_TIMEOUT_MS)
        if (result.timedOut) return ToolResult(name, false, "Command timed out: $command")
        val text = buildString {
            appendLine("Command: ${result.command}")
            appendLine("Exit code: ${result.exitCode}  Duration: ${result.durationMs}ms")
            if (result.stderr.isNotBlank()) { appendLine("Stderr:"); appendLine(result.stderr.take(3000)) }
            if (result.stdout.isNotBlank()) { appendLine("Stdout:"); appendLine(result.stdout.take(6000)) }
        }.trim()
        return ToolResult(name, result.succeeded, text)
    }

    private companion object {
        const val COMMAND_TIMEOUT_MS = 120_000L
    }
}

class TerminalLastOutputTool : LlamatikTool {
    override val name = "terminal_last_output"
    override val description = "Get output from the last terminal command. Arguments: command (optional)"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val shell = context.env.shell
        val cmd = call.arguments["command"]
        val out = (if (cmd != null) shell.lastOutput(cmd) else shell.lastOutputAny())
            ?: return ToolResult(name, false, "No recorded output. Run a command first with terminal_run.")
        return ToolResult(name, true, buildString {
            appendLine("Command: ${out.command}")
            appendLine("Exit: ${out.exitCode}")
            if (out.stderr.isNotBlank()) appendLine("Stderr: ${out.stderr.take(2000)}")
            appendLine("Stdout: ${out.stdout.take(4000)}")
        }.trim())
    }
}
