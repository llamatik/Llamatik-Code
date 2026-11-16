package com.llamatik.acp

import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionId
import com.agentclientprotocol.model.PermissionOptionKind
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.llamatik.agent.core.host.CommandAllowlist
import com.llamatik.agent.core.host.Decision
import com.llamatik.agent.core.host.NioWorkspaceFiles
import com.llamatik.agent.core.host.PermissionKind
import com.llamatik.agent.core.host.PermissionPrompt
import com.llamatik.agent.core.host.PermissionRequest
import com.llamatik.agent.core.host.ProcessShellRunner
import com.llamatik.agent.core.host.ShellResult
import com.llamatik.agent.core.host.ShellRunner
import com.llamatik.agent.core.host.WorkspaceFiles
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// The agent loop is blocking code running on its own threads; the ACP client is suspending RPC.
// These adapters bridge the two. They never run on the SDK's own threads.
private fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }

/**
 * Files through the editor when it offers file access, so the agent sees unsaved buffers and edits go through
 * the editor's undo stack; listing and searching use the disk. The safety policy (ignored folders,
 * secret files, size limit) applies first, exactly as in the IDE and CLI.
 */
class AcpWorkspaceFiles(
    private val disk: NioWorkspaceFiles,
    private val client: ClientSessionOperations,
    private val capabilities: ClientCapabilities,
) : WorkspaceFiles by disk {
    private fun absolute(path: String): String = Path.of(disk.rootPath).resolve(path).normalize().toString()

    override fun read(path: String): String {
        val onDisk = disk.read(path) // applies the policy; also the fallback
        if (capabilities.fs?.readTextFile != true) return onDisk
        return runCatching { blocking { client.fsReadTextFile(absolute(path)).content } }.getOrDefault(onDisk)
    }

    override fun write(path: String, content: String) {
        if (capabilities.fs?.writeTextFile == true) blocking { client.fsWriteTextFile(absolute(path), content) }
        else disk.write(path, content)
    }
}

/** Commands in the editor's terminal when it offers one, otherwise a plain process. */
class AcpShellRunner(
    private val client: ClientSessionOperations,
    private val capabilities: ClientCapabilities,
    private val fallback: ProcessShellRunner = ProcessShellRunner(),
) : ShellRunner {
    private val outputs = ConcurrentHashMap<String, ShellResult>()
    @Volatile private var last: String? = null

    override fun run(command: String, cwd: String, timeoutMs: Long): ShellResult {
        if (!capabilities.terminal) return fallback.run(command, cwd, timeoutMs)
        val started = System.currentTimeMillis()
        val parts = CommandAllowlist.splitCommand(command)
        val result = try {
            blocking {
                val terminalId = client.terminalCreate(parts.first(), parts.drop(1), cwd).terminalId
                try {
                    val exit = withTimeoutOrNull(timeoutMs) { client.terminalWaitForExit(terminalId) }
                    if (exit == null) {
                        runCatching { client.terminalKill(terminalId) }
                        ShellResult(command, -1, client.terminalOutput(terminalId).output, "", System.currentTimeMillis() - started, false, timedOut = true)
                    } else {
                        val output = client.terminalOutput(terminalId)
                        val code = exit.exitCode?.toInt() ?: -1
                        // The terminal merges stdout and stderr into one stream.
                        ShellResult(command, code, output.output, "", System.currentTimeMillis() - started, code == 0)
                    }
                } finally {
                    runCatching { client.terminalRelease(terminalId) }
                }
            }
        } catch (e: Exception) {
            ShellResult(command, -1, "", e.message ?: "Terminal failed", System.currentTimeMillis() - started, false)
        }
        outputs[command] = result
        last = command
        return result
    }

    override fun lastOutput(command: String): ShellResult? = outputs[command] ?: fallback.lastOutput(command)
    override fun lastOutputAny(): ShellResult? = last?.let { outputs[it] } ?: fallback.lastOutputAny()
}

/** Asks the editor's user (`session/request_permission`); "always" is remembered for the session. */
class AcpPermissionPrompt(
    private val client: ClientSessionOperations,
    private val autoApprove: Boolean,
) : PermissionPrompt {
    private val counter = AtomicInteger()
    private val always = mutableSetOf<PermissionKind>()

    override fun confirm(request: PermissionRequest): Decision {
        if (autoApprove || request.kind in always) return Decision.ALLOW_ONCE
        val outcome = runCatching {
            blocking {
                client.requestPermissions(
                    toolCall = SessionUpdate.ToolCallUpdate(
                        toolCallId = ToolCallId("permission-${counter.incrementAndGet()}"),
                        title = request.title,
                        kind = request.kind.toToolKind(),
                        status = ToolCallStatus.PENDING,
                    ),
                    permissions = listOf(
                        PermissionOption(PermissionOptionId("allow"), "Allow", PermissionOptionKind.ALLOW_ONCE),
                        PermissionOption(PermissionOptionId("always"), "Always allow", PermissionOptionKind.ALLOW_ALWAYS),
                        PermissionOption(PermissionOptionId("reject"), "Reject", PermissionOptionKind.REJECT_ONCE),
                    ),
                ).outcome
            }
        }.getOrNull()
        return when ((outcome as? RequestPermissionOutcome.Selected)?.optionId?.value) {
            "allow" -> Decision.ALLOW_ONCE
            "always" -> { always += request.kind; Decision.ALLOW_ALWAYS }
            else -> Decision.DENY
        }
    }

    private fun PermissionKind.toToolKind() = when (this) {
        PermissionKind.EDIT -> ToolKind.EDIT
        PermissionKind.SHELL -> ToolKind.EXECUTE
        else -> ToolKind.OTHER
    }
}
