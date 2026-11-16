package com.llamatik.teammate.terminal

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.agent.core.host.CommandAllowlist
import com.llamatik.teammate.settings.LlamatikAppSettings
import com.llamatik.teammate.util.PluginLogger
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class TerminalService(private val project: Project) {

    private val log = PluginLogger.logger<TerminalService>()

    data class CommandOutput(
        val command: String,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val durationMs: Long,
        val succeeded: Boolean,
    )

    private val lastOutputs = ConcurrentHashMap<String, CommandOutput>()
    @Volatile private var lastCommand: String? = null

    companion object {
        // The allowlist is shared with headless hosts: see CommandAllowlist.
        val ALLOWLIST: Set<String> get() = CommandAllowlist.ALLOWLIST

        fun isAllowed(command: String): Boolean = CommandAllowlist.isAllowed(command)
    }

    fun allowedCommands(): List<String> = ALLOWLIST.sorted()

    fun run(
        command: String,
        requireApproval: Boolean = !isAllowed(command),
        timeoutMs: Int = 120_000,
        onDone: (CommandOutput) -> Unit,
        onBlocked: ((String) -> Unit)? = null,
    ) {
        if (requireApproval && !isAllowed(command)) {
            onBlocked?.invoke("Command requires approval: $command")
            return
        }
        val basePath = project.basePath ?: run {
            onDone(CommandOutput(command, -1, "", "No project root", 0, false))
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val start = System.currentTimeMillis()
            try {
                val parts = splitCommand(command)
                val cmd = GeneralCommandLine(parts).withWorkDirectory(basePath)
                val handler = CapturingProcessHandler(cmd)
                val result = handler.runProcess(timeoutMs)
                val elapsed = System.currentTimeMillis() - start
                val output = CommandOutput(
                    command = command,
                    exitCode = result.exitCode,
                    stdout = result.stdout.take(50_000),
                    stderr = result.stderr.take(20_000),
                    durationMs = elapsed,
                    succeeded = result.exitCode == 0,
                )
                lastOutputs[command] = output
                lastCommand = command
                log.info("terminal_run [$command] exit=${result.exitCode} in ${elapsed}ms")
                onDone(output)
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - start
                val output = CommandOutput(command, -1, "", e.message ?: "Failed", elapsed, false)
                lastOutputs[command] = output
                lastCommand = command
                onDone(output)
            }
        }
    }

    fun lastOutput(command: String): CommandOutput? = lastOutputs[command]

    /** Output of the most recently finished command. */
    fun lastOutputAny(): CommandOutput? = lastCommand?.let { lastOutputs[it] }

    private fun splitCommand(cmd: String): List<String> = CommandAllowlist.splitCommand(cmd)
}
