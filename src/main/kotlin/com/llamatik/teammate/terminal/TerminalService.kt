package com.llamatik.teammate.terminal

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
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

    companion object {
        val ALLOWLIST = setOf(
            "./gradlew test", "./gradlew build", "./gradlew lint", "./gradlew check",
            "./gradlew assemble", "./gradlew clean", "./gradlew ktlintCheck",
            "./gradlew detekt", "./gradlew dependencies",
            "gradlew.bat test", "gradlew.bat build", "gradlew.bat lint",
            "npm test", "npm run build", "npm run lint", "npm run check",
            "ktlint", "detekt",
        )

        fun isAllowed(command: String): Boolean {
            val trimmed = command.trim()
            return ALLOWLIST.any { allowed ->
                trimmed == allowed || trimmed.startsWith("$allowed ") || trimmed.startsWith("$allowed\t")
            }
        }
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
                log.info("terminal_run [$command] exit=${result.exitCode} in ${elapsed}ms")
                onDone(output)
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - start
                val output = CommandOutput(command, -1, "", e.message ?: "Failed", elapsed, false)
                lastOutputs[command] = output
                onDone(output)
            }
        }
    }

    fun lastOutput(command: String): CommandOutput? = lastOutputs[command]

    fun lastOutputAny(): CommandOutput? = lastOutputs.values.maxByOrNull { it.durationMs }

    private fun splitCommand(cmd: String): List<String> {
        val result = mutableListOf<String>()
        val buf = StringBuilder()
        var inQuote = false
        var quoteChar = ' '
        for (c in cmd.trim()) {
            when {
                inQuote && c == quoteChar -> { inQuote = false }
                !inQuote && (c == '"' || c == '\'') -> { inQuote = true; quoteChar = c }
                !inQuote && c == ' ' -> { if (buf.isNotEmpty()) { result += buf.toString(); buf.clear() } }
                else -> buf.append(c)
            }
        }
        if (buf.isNotEmpty()) result += buf.toString()
        return result
    }
}
