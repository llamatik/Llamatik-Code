package com.llamatik.agent.core.testing

import com.llamatik.agent.core.agent.AgentTrace
import com.llamatik.agent.core.agent.PromptSources
import com.llamatik.agent.core.agent.ToolExecutor
import com.llamatik.agent.core.backend.AssistantBackend
import com.llamatik.agent.core.backend.BackendConfig
import com.llamatik.agent.core.backend.GenerationCallbacks
import com.llamatik.agent.core.backend.GenerationHandle
import com.llamatik.agent.core.backend.GenerationRequest
import com.llamatik.agent.core.host.AgentEnvironment
import com.llamatik.agent.core.host.AutoDeny
import com.llamatik.agent.core.host.EditorContext
import com.llamatik.agent.core.host.GitRunner
import com.llamatik.agent.core.host.GitStatus
import com.llamatik.agent.core.host.ShellResult
import com.llamatik.agent.core.host.ShellRunner
import com.llamatik.agent.core.host.WorkspaceFiles
import com.llamatik.agent.core.model.ChatMessage
import com.llamatik.agent.core.model.ChatSession
import com.llamatik.agent.core.model.EditProposal
import com.llamatik.agent.core.tools.ToolCall
import com.llamatik.agent.core.tools.ToolExecutionContext
import com.llamatik.agent.core.tools.ToolResult

/**
 * Everything an agent run depends on, scripted: model replies, tool results, workspace files and
 * prompt sources. Run through [AgentHarness] to get a deterministic transcript.
 */
data class AgentScenario(
    val name: String,
    val userPrompt: String,
    /** What the model generates, one entry per backend call (blank once exhausted). */
    val responses: List<String>,
    val chat: Boolean = false,
    val files: Map<String, String> = emptyMap(),
    val currentFile: String? = null,
    val selectedText: String? = null,
    /** Tool name -> results returned in order (the last one repeats). */
    val toolResults: Map<String, List<String>> = emptyMap(),
    val dynamicTools: Set<String> = emptySet(),
    val history: List<ChatMessage> = emptyList(),
    val contextLength: Int = 16384,
    val maxTokens: Int = 2048,
    val projectName: String = "demo",
    val pinned: String = "",
    val skills: String = "",
    val rules: String = "",
    val memory: String = "",
    val aggressive: Boolean = false,
    /** Streamed token size; tiny values exercise tags split across tokens. */
    val tokenChunk: Int = 4,
    /** Make the Nth backend call fail with this message instead of generating. */
    val errorAtCall: Int? = null,
    val errorMessage: String = "model exploded",
)

/** The scripted collaborators for one [AgentScenario] run, and the transcript they write. */
class AgentWorld(val scenario: AgentScenario) {
    val log = mutableListOf<String>()

    val config = BackendConfig(
        modelIdOrPath = "model.gguf", temperature = 0.7, maxTokens = scenario.maxTokens,
        contextLength = scenario.contextLength, numThreads = 4, useMmap = true, flashAttention = false,
        batchSize = 2048, gpuLayers = 0, localOnly = true,
    )
    val session = ChatSession().also { it.messages += scenario.history }

    val backend = ScriptedBackend()
    val files: WorkspaceFiles = InMemoryFiles(scenario.files)
    val editor: EditorContext = object : EditorContext {
        override fun currentFile(): String? = scenario.currentFile
        override fun selectedText(): String? = scenario.selectedText
    }
    val env = AgentEnvironment(files, NoShell, NoGit, AutoDeny, editor)
    val tools: ToolExecutor = FakeTools()
    val sources: PromptSources = object : PromptSources {
        override fun projectName() = scenario.projectName
        override fun pinnedInstructions() = scenario.pinned
        override fun agentIdentity() = "You are Llamatik Code, an autonomous local development agent for IntelliJ. You plan, patch, review, and verify code locally — all source code stays on the user's machine."
        override fun chatIdentity() = "You are Llamatik Code, a local AI coding assistant inside IntelliJ."
        override fun skillsPromptAddition() = scenario.skills
        override fun rulesPromptBlock() = scenario.rules
        override fun memoryPromptBlock() = scenario.memory
        override fun aggressiveMode() = scenario.aggressive
    }

    private var tokenCount = 0

    inner class ScriptedBackend : AssistantBackend {
        private var call = 0
        override fun isModelLoaded() = true
        override fun selectedModelLabel() = "scripted"

        override fun generate(request: GenerationRequest, callbacks: GenerationCallbacks): GenerationHandle {
            call++
            log += "REQUEST #$call tag=${request.sessionTag} noThink=${request.noThink} maxTokens=${request.config.maxTokens} " +
                "ctx=${request.config.contextLength} prefill=${request.assistantPrefill.show()}"
            log += "SYSTEM <<<${request.systemPrompt}>>>"
            request.messages.forEach { log += "MSG ${it.role} tool=${it.toolName} editProposal=${it.editProposalId != null} <<<${it.content}>>>" }
            callbacks.onStarted()
            if (scenario.errorAtCall == call) {
                callbacks.onError(scenario.errorMessage, null)
                return object : GenerationHandle {
                    override fun cancel() {}
                    override val isCancelled = false
                }
            }
            val response = scenario.responses.getOrElse(call - 1) { "" }
            response.chunked(scenario.tokenChunk.coerceAtLeast(1)).forEach { callbacks.onToken(it) }
            callbacks.onCompleted(response)
            return object : GenerationHandle {
                override fun cancel() {}
                override val isCancelled = false
            }
        }
    }

    private inner class FakeTools : ToolExecutor {
        private val used = mutableMapOf<String, Int>()
        override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
            log += "TOOL ${call.name} ${call.arguments}"
            val results = scenario.toolResults[call.name]
            val idx = used.merge(call.name, 1, Int::plus)!! - 1
            val content = results?.let { it[idx.coerceAtMost(it.lastIndex)] } ?: "ok"
            return ToolResult(call.name, success = true, content = content)
        }
        override fun listToolNames() = "list_files | read_file | search_code | search_filenames | write_file | create_file | replace_in_file | git_status"
        override fun dynamicToolNames() = scenario.dynamicTools
    }

    // ── Listener used by both the legacy and the extracted loop ─────────────

    fun onToken(t: String) { log += "TOKEN <<<$t>>>" }
    fun onThinking(t: String) { log += "THINK <<<$t>>>" }
    fun onTokenCount(n: Int) { tokenCount = n; log += "COUNT $n" }
    fun onError(m: String) { log += "ERROR <<<$m>>>" }
    fun onTrace(t: AgentTrace) {
        t.steps.forEach { log += "TRACE ${it.type} ${it.status} ${it.title} <<<${it.summary}>>>" }
    }
    fun onFinished(m: ChatMessage, proposals: List<EditProposal>) {
        log += "FINISHED role=${m.role} hasProposalId=${m.editProposalId != null} <<<${m.content}>>>"
        proposals.forEach { log += "PROPOSAL ${it.filePath} summary=<<<${it.summary}>>> old=<<<${it.oldContent}>>> new=<<<${it.newContent}>>>" }
    }
    fun onChatFinished(text: String) { log += "CHAT_FINISHED <<<$text>>>" }

    /** Transcript including the final session history (ids and timestamps omitted). */
    fun transcript(): String {
        val history = session.messages.joinToString("\n") { "SESSION ${it.role} editProposal=${it.editProposalId != null} <<<${it.content}>>>" }
        return (log + history).joinToString("\n")
    }

    private fun String.show() = if (isEmpty()) "''" else "<<<$this>>>"
}
