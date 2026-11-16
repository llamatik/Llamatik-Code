package com.llamatik.teammate.benchmark

import com.llamatik.agent.core.agent.AgentLimits
import com.llamatik.agent.core.agent.AgentLoop
import com.llamatik.agent.core.agent.PromptSources
import com.llamatik.agent.core.agent.TIMEOUT_MESSAGE_PREFIX
import com.llamatik.agent.core.agent.ToolExecutor
import com.llamatik.agent.core.agent.ToolPolicy
import com.llamatik.agent.core.backend.AssistantBackend
import com.llamatik.agent.core.backend.BackendConfig
import com.llamatik.agent.core.backend.GenerationCallbacks
import com.llamatik.agent.core.backend.GenerationRequest
import com.llamatik.agent.core.host.AgentEnvironment
import com.llamatik.agent.core.host.AutoApprove
import com.llamatik.agent.core.model.ChatSession
import com.llamatik.agent.core.tools.ToolCall
import com.llamatik.agent.core.tools.ToolExecutionContext
import com.llamatik.agent.core.tools.ToolResult
import com.llamatik.agent.core.tools.builtin.ToolListExecutor
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Headless benchmark agent: the REAL [AgentLoop] (the one the IDE plugin runs) with the portable
 * tools, on a plain directory instead of an IntelliJ project.
 *
 * The loop only *proposes* edits, as in the IDE. The edit proposals it ends with are applied to
 * disk here, which is what accepting them in the diff view does.
 *
 * This is intentionally test-only code and must never be shipped in the plugin JAR.
 */
class HeadlessAgent(
    private val backend: AssistantBackend,
    private val config: BackendConfig,
    private val projectRoot: File,
    private val maxRounds: Int = 5,
    private val maxToolCallsPerRound: Int = 4,
) {
    data class RunResult(
        val finalMessage: String,
        /** Number of model calls (a retried or escalated round counts again). */
        val rounds: Int,
        val toolsExecuted: List<String>,
        val changedPaths: List<String>,
        val error: String?,
    )

    fun run(prompt: String, timeoutMinutes: Int): RunResult {
        val env = AgentEnvironment.headless(projectRoot.toPath(), AutoApprove)
        val toolsExecuted = mutableListOf<String>()
        val builtin = ToolListExecutor()
        val tools = object : ToolExecutor by builtin {
            override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
                toolsExecuted += call.name
                return builtin.execute(call, context)
            }
        }
        var rounds = 0
        val counting = object : AssistantBackend by backend {
            override fun generate(request: GenerationRequest, callbacks: GenerationCallbacks) =
                backend.generate(request, callbacks).also { rounds++ }
        }
        val limits = AgentLimits(
            maxRounds = maxRounds,
            maxToolCallsPerRound = maxToolCallsPerRound,
            sessionTimeoutMs = TimeUnit.MINUTES.toMillis(timeoutMinutes.toLong()),
        )
        val loop = AgentLoop(counting, env, tools, ToolPolicy.PORTABLE, PromptSources.Empty, limits)

        var finalMessage = ""
        var error: String? = null
        val changed = mutableListOf<String>()
        try {
            loop.submit(
                session = ChatSession(),
                userPrompt = prompt,
                config = config,
                onToken = {},
                onFinished = { message, proposals ->
                    finalMessage = message.content
                    if (message.content.startsWith(TIMEOUT_MESSAGE_PREFIX)) error = "Timeout: ${message.content}"
                    proposals.forEach { proposal ->
                        env.files.write(proposal.filePath, proposal.newContent)
                        changed += proposal.filePath
                    }
                },
                onError = { message ->
                    error = message
                    finalMessage = "ERROR: $message"
                },
            )
        } catch (e: Exception) {
            return RunResult("", rounds, toolsExecuted, changed, e.message ?: "Unknown error")
        }
        return RunResult(finalMessage, rounds, toolsExecuted, changed, error)
    }
}
