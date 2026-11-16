package com.llamatik.cli

import com.llamatik.agent.backends.BackendHandle
import com.llamatik.agent.core.agent.AgentLoop
import com.llamatik.agent.core.agent.ConfirmingToolExecutor
import com.llamatik.agent.core.agent.FilePromptSources
import com.llamatik.agent.core.agent.STATUS_PREFIX
import com.llamatik.agent.core.agent.TOOL_PREFIX
import com.llamatik.agent.core.agent.ToolPolicy
import com.llamatik.agent.core.backend.GenerationHandle
import com.llamatik.agent.core.host.AgentEnvironment
import com.llamatik.agent.core.host.Decision
import com.llamatik.agent.core.host.PermissionKind
import com.llamatik.agent.core.host.PermissionRequest
import com.llamatik.agent.core.model.ChatMessage
import com.llamatik.agent.core.model.ChatSession
import com.llamatik.agent.core.model.EditProposal
import com.llamatik.agent.core.tools.builtin.ToolListExecutor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * The terminal session: reads tasks, runs the shared [AgentLoop] on the chosen backend, shows what the
 * agent does and asks before it changes anything.
 */
class CliApp(
    private val options: CliOptions,
    private val terminal: Terminal,
    private val backendFactory: () -> BackendHandle,
) {
    private val permissions = TerminalPermissionPrompt(terminal, options.yes)
    private val current = AtomicReference<Turn?>(null)

    private class Turn(val latch: CountDownLatch) {
        @Volatile var handle: GenerationHandle? = null
        @Volatile var cancelled = false
    }

    /** Stops the task in progress (Ctrl-C). Returns false when nothing was running. */
    fun cancelCurrent(): Boolean {
        val turn = current.get() ?: return false
        turn.cancelled = true
        turn.handle?.cancel()
        turn.latch.countDown()
        return true
    }

    fun run(): Int {
        if (options.help) { terminal.println(CliArgs.USAGE); return 0 }
        if (options.version) { terminal.println("llamatik $VERSION"); return 0 }

        val backend = try {
            backendFactory()
        } catch (e: Exception) {
            terminal.error(terminal.style(Style.RED, "error: ${e.message}"))
            return 2
        }
        backend.use { handle ->
            val env = AgentEnvironment.headless(options.workDir, permissions)
            val loop = AgentLoop(
                backend = if (options.debug) DebugBackend(handle.backend, terminal) else handle.backend,
                env = env,
                tools = ConfirmingToolExecutor(ToolListExecutor(), permissions),
                policy = ToolPolicy.PORTABLE,
                sources = FilePromptSources(env, "terminal", options.aggressive),
            )
            val agent = Agent(loop, env, handle)
            return if (options.prompt != null) {
                if (agent.runTask(options.prompt) == Outcome.OK) 0 else 1
            } else {
                interactive(agent, handle)
            }
        }
    }

    private enum class Outcome { OK, FAILED, CANCELLED }

    private inner class Agent(val loop: AgentLoop, val env: AgentEnvironment, val handle: BackendHandle) {
        var session = ChatSession()

        fun runTask(prompt: String): Outcome {
            val turn = Turn(CountDownLatch(1))
            var finished: Pair<ChatMessage, List<EditProposal>>? = null
            var error: String? = null
            current.set(turn)
            try {
                turn.handle = loop.submit(
                    session = session, userPrompt = prompt, config = handle.config,
                    onToken = ::showProgress,
                    onFinished = { message, proposals -> finished = message to proposals; turn.latch.countDown() },
                    onError = { error = it; turn.latch.countDown() },
                )
                if (turn.cancelled) turn.handle?.cancel()
                turn.latch.await()
            } finally {
                current.set(null)
            }
            if (turn.cancelled) {
                terminal.println(terminal.style(Style.YELLOW, "Cancelled."))
                return Outcome.CANCELLED
            }
            error?.let {
                terminal.error(terminal.style(Style.RED, "error: $it"))
                return Outcome.FAILED
            }
            val (message, proposals) = finished!!
            if (message.content.isNotBlank()) terminal.println(message.content.trim())
            proposals.forEach { apply(it) }
            return Outcome.OK
        }

        private fun showProgress(token: String) {
            when {
                token.startsWith(STATUS_PREFIX) -> terminal.println(terminal.style(Style.DIM, "· " + token.removePrefix(STATUS_PREFIX).trim()))
                token.startsWith(TOOL_PREFIX) -> {
                    val (name, arg) = token.removePrefix(TOOL_PREFIX).trim().let { it.substringBefore(':') to it.substringAfter(':', "") }
                    terminal.println(terminal.style(Style.CYAN, "→ $name") + if (arg.isNotBlank()) terminal.style(Style.DIM, " $arg") else "")
                }
            }
        }

        private fun apply(proposal: EditProposal) {
            terminal.println()
            terminal.println(terminal.style(Style.BOLD, "Proposed change to ${proposal.filePath}"))
            if (proposal.summary.isNotBlank()) terminal.println(proposal.summary)
            terminal.println(colorize(Diff.unified(proposal.filePath, proposal.oldContent, proposal.newContent)))
            val decision = permissions.confirm(PermissionRequest(PermissionKind.EDIT, "Apply this change to ${proposal.filePath}?"))
            if (decision == Decision.DENY) {
                terminal.println(terminal.style(Style.YELLOW, "Skipped ${proposal.filePath}"))
                return
            }
            try {
                env.files.write(proposal.filePath, proposal.newContent)
                terminal.println(terminal.style(Style.GREEN, "Updated ${proposal.filePath}"))
            } catch (e: Exception) {
                terminal.error(terminal.style(Style.RED, "Could not write ${proposal.filePath}: ${e.message}"))
            }
        }

        private fun colorize(diff: String) = diff.lines().joinToString("\n") { line ->
            when {
                line.startsWith("+++") || line.startsWith("---") -> terminal.style(Style.BOLD, line)
                line.startsWith("+") -> terminal.style(Style.GREEN, line)
                line.startsWith("-") -> terminal.style(Style.RED, line)
                line.startsWith("@@") -> terminal.style(Style.CYAN, line)
                else -> line
            }
        }
    }

    private fun interactive(agent: Agent, backend: BackendHandle): Int {
        terminal.println(terminal.style(Style.BOLD, "Llamatik Code") + terminal.style(Style.DIM, " — ${backend.description}"))
        terminal.println(terminal.style(Style.DIM, "Project: ${options.workDir}   /help for commands, /exit to quit"))
        while (true) {
            val line = terminal.readLine(terminal.style(Style.GREEN, "\n> ")) ?: return 0
            val text = line.trim()
            when {
                text.isEmpty() -> continue
                text == "/exit" || text == "/quit" -> return 0
                text == "/help" -> terminal.println(CliArgs.USAGE.substringAfter("In a session: ").let { "In a session: $it" })
                text == "/clear" -> { agent.session = ChatSession(); terminal.println(terminal.style(Style.DIM, "Conversation cleared.")) }
                text == "/yes" -> {
                    permissions.autoApprove = !permissions.autoApprove
                    terminal.println("Auto-approve is ${if (permissions.autoApprove) "ON" else "off"}.")
                }
                text.startsWith("/") -> terminal.println("Unknown command: $text (try /help)")
                else -> agent.runTask(text)
            }
        }
    }

    companion object {
        const val VERSION = "0.1.0"
    }
}
