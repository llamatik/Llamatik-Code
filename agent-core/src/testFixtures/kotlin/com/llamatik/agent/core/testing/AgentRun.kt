package com.llamatik.agent.core.testing

import com.llamatik.agent.core.agent.AgentLimits
import com.llamatik.agent.core.agent.AgentLoop
import com.llamatik.agent.core.agent.PromptSources
import com.llamatik.agent.core.agent.ToolExecutor
import com.llamatik.agent.core.agent.ToolPolicy
import com.llamatik.agent.core.backend.AssistantBackend
import com.llamatik.agent.core.backend.BackendConfig
import com.llamatik.agent.core.host.AgentEnvironment
import com.llamatik.agent.core.host.AutoDeny
import com.llamatik.agent.core.host.WorkspaceFiles
import com.llamatik.agent.core.model.ChatMessage
import com.llamatik.agent.core.model.ChatSession
import com.llamatik.agent.core.model.EditProposal

/**
 * Runs the real [AgentLoop] once against fakes and collects what a caller observes. Use this in
 * tests instead of re-implementing the loop.
 */
class AgentRun(
    private val backend: AssistantBackend,
    private val tools: ToolExecutor,
    files: Map<String, String> = emptyMap(),
    private val limits: AgentLimits = AgentLimits(),
    private val policy: ToolPolicy = ToolPolicy.PORTABLE,
    private val contextLength: Int = 4096,
    private val maxTokens: Int = 2048,
    workspace: WorkspaceFiles = InMemoryFiles(files),
) {
    private val env = AgentEnvironment(workspace, NoShell, NoGit, AutoDeny, editor = null)

    data class Result(
        /** The final assistant message, or `ERROR: ...` when the backend failed. */
        val finalMessage: String,
        val proposals: List<EditProposal>,
        val error: String?,
        val session: ChatSession,
        val tokens: List<String>,
    ) {
        val proposalPaths: List<String> get() = proposals.map { it.filePath }
    }

    fun run(prompt: String, history: List<ChatMessage> = emptyList()): Result {
        val session = ChatSession().also { it.messages += history }
        val config = BackendConfig(
            modelIdOrPath = "fake.gguf", temperature = 0.0, maxTokens = maxTokens, contextLength = contextLength,
            numThreads = 4, useMmap = true, flashAttention = false, batchSize = 2048, localOnly = true,
        )
        var finalMessage = ""
        var proposals: List<EditProposal> = emptyList()
        var error: String? = null
        val tokens = mutableListOf<String>()
        AgentLoop(backend, env, tools, policy, PromptSources.Empty, limits).submit(
            session = session, userPrompt = prompt, config = config,
            onToken = { tokens += it },
            onFinished = { message, found -> finalMessage = message.content; proposals = found },
            onError = { error = it; finalMessage = "ERROR: $it" },
        )
        return Result(finalMessage, proposals, error, session, tokens)
    }
}
