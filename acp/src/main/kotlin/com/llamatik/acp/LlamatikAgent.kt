package com.llamatik.acp

import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.agent.AgentSupport
import com.agentclientprotocol.agent.client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.AgentCapabilities
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionId
import com.agentclientprotocol.model.PermissionOptionKind
import com.agentclientprotocol.model.PromptCapabilities
import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.ToolCallLocation
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.llamatik.agent.backends.BackendHandle
import com.llamatik.agent.core.agent.AgentLoop
import com.llamatik.agent.core.agent.ConfirmingToolExecutor
import com.llamatik.agent.core.agent.FilePromptSources
import com.llamatik.agent.core.agent.ToolPolicy
import com.llamatik.agent.core.backend.GenerationHandle
import com.llamatik.agent.core.host.AgentEnvironment
import com.llamatik.agent.core.host.NioWorkspaceFiles
import com.llamatik.agent.core.host.ProcessGitRunner
import com.llamatik.agent.core.model.ChatMessage
import com.llamatik.agent.core.model.ChatSession
import com.llamatik.agent.core.model.EditProposal
import com.llamatik.agent.core.tools.builtin.ToolListExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.net.URI
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The ACP face of the Llamatik agent. The editor creates a session per project folder; each prompt
 * runs the shared [AgentLoop], reporting tools, reasoning and the answer back as session updates and
 * asking the editor's user before edits and commands.
 *
 * @param backend produces the model backend on first use, so the editor handshake never waits for a
 *   model to load or a server to approve this device
 */
class LlamatikAgent(
    private val options: AcpOptions,
    private val backend: () -> BackendHandle,
) : AgentSupport, AutoCloseable {
    @Volatile private var clientCapabilities = ClientCapabilities()
    private val backendHolder = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { backend() }
    private val backendHandle by backendHolder

    /** Releases the model (if one was ever loaded). Safe to call more than once. */
    override fun close() {
        if (backendHolder.isInitialized()) backendHandle.close()
    }

    override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
        clientCapabilities = clientInfo.capabilities
        return AgentInfo(
            capabilities = AgentCapabilities(promptCapabilities = PromptCapabilities(embeddedContext = true)),
            implementation = Implementation("llamatik", VERSION, "Llamatik Code"),
        )
    }

    override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession {
        // Fails the session request (not the connection) with a readable message when no model is available.
        val handle = withContext(Dispatchers.IO) { backendHandle }
        return LlamatikSession(SessionId(UUID.randomUUID().toString()), Path.of(sessionParameters.cwd), handle, options, clientCapabilities)
    }

    companion object {
        const val VERSION = "0.1.0"
    }
}

internal class LlamatikSession(
    override val sessionId: SessionId,
    private val cwd: Path,
    private val backend: BackendHandle,
    private val options: AcpOptions,
    private val clientCapabilities: ClientCapabilities,
) : AgentSession {
    private val session = ChatSession()
    @Volatile private var turn: Turn? = null

    private class Turn {
        val outcome = CompletableDeferred<Outcome>()
        @Volatile var handle: GenerationHandle? = null
    }

    private sealed interface Outcome {
        data class Finished(val message: ChatMessage, val proposals: List<EditProposal>) : Outcome
        data class Failed(val message: String) : Outcome
        data object Cancelled : Outcome
    }

    override suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<Event> {
        val client = currentCoroutineContext().client
        return channelFlow {
            val send: (SessionUpdate) -> Unit = { trySend(Event.SessionUpdateEvent(it)) }
            runTurn(client, promptText(content), send)
            close()
        }.buffer(Channel.UNLIMITED)
    }

    override suspend fun cancel() {
        turn?.let {
            it.handle?.cancel()
            it.outcome.complete(Outcome.Cancelled)
        }
    }

    private suspend fun kotlinx.coroutines.channels.ProducerScope<Event>.runTurn(
        client: ClientSessionOperations,
        promptText: String,
        send: (SessionUpdate) -> Unit,
    ) {
        val files = AcpWorkspaceFiles(NioWorkspaceFiles(cwd), client, clientCapabilities)
        val permissions = AcpPermissionPrompt(client, options.autoApprove)
        val env = AgentEnvironment(files, AcpShellRunner(client, clientCapabilities), ProcessGitRunner(files.rootPath), permissions)
        val tools = ToolReporter(ConfirmingToolExecutor(ToolListExecutor(), permissions), send)
        val loop = AgentLoop(backend.backend, env, tools, ToolPolicy.PORTABLE, FilePromptSources(env, "editor", options.aggressive))

        val current = Turn().also { turn = it }
        try {
            withContext(Dispatchers.IO) {
                current.handle = loop.submit(
                    session = session, userPrompt = promptText, config = backend.config,
                    // Status and tool announcements are covered by the tool reporter; reasoning is shown as thought.
                    onToken = {},
                    onThinkingToken = { send(SessionUpdate.AgentThoughtChunk(ContentBlock.Text(it))) },
                    onFinished = { message, proposals -> current.outcome.complete(Outcome.Finished(message, proposals)) },
                    onError = { current.outcome.complete(Outcome.Failed(it)) },
                )
            }
            when (val outcome = current.outcome.await()) {
                is Outcome.Failed -> error(outcome.message)
                Outcome.Cancelled -> trySend(Event.PromptResponseEvent(PromptResponse(StopReason.CANCELLED)))
                is Outcome.Finished -> {
                    if (outcome.message.content.isNotBlank()) send(SessionUpdate.AgentMessageChunk(ContentBlock.Text(outcome.message.content.trim())))
                    outcome.proposals.forEach { applyEdit(client, env, it, send) }
                    trySend(Event.PromptResponseEvent(PromptResponse(StopReason.END_TURN)))
                }
            }
        } finally {
            turn = null
        }
    }

    /** Shows the edit as a diff, asks, then writes it through the editor. */
    private suspend fun applyEdit(client: ClientSessionOperations, env: AgentEnvironment, proposal: EditProposal, send: (SessionUpdate) -> Unit) {
        val id = ToolCallId("edit-${editCounter.incrementAndGet()}")
        val absolute = cwd.resolve(proposal.filePath).normalize().toString()
        val diff = ToolCallContent.Diff(absolute, proposal.newContent, proposal.oldContent.ifEmpty { null })
        send(SessionUpdate.ToolCall(id, "Edit ${proposal.filePath}", ToolKind.EDIT, ToolCallStatus.PENDING, listOf(diff), listOf(ToolCallLocation(absolute))))

        val allowed = options.autoApprove || run {
            val response = client.requestPermissions(
                SessionUpdate.ToolCallUpdate(toolCallId = id, title = "Edit ${proposal.filePath}", kind = ToolKind.EDIT, status = ToolCallStatus.PENDING, content = listOf(diff)),
                listOf(
                    PermissionOption(PermissionOptionId("allow"), "Apply", PermissionOptionKind.ALLOW_ONCE),
                    PermissionOption(PermissionOptionId("reject"), "Reject", PermissionOptionKind.REJECT_ONCE),
                ),
            )
            (response.outcome as? RequestPermissionOutcome.Selected)?.optionId?.value == "allow"
        }
        if (!allowed) {
            send(SessionUpdate.ToolCallUpdate(toolCallId = id, status = ToolCallStatus.FAILED, title = "Edit ${proposal.filePath} (rejected)"))
            return
        }
        val written = runCatching { withContext(Dispatchers.IO) { env.files.write(proposal.filePath, proposal.newContent) } }
        send(
            SessionUpdate.ToolCallUpdate(
                toolCallId = id,
                status = if (written.isSuccess) ToolCallStatus.COMPLETED else ToolCallStatus.FAILED,
                title = if (written.isSuccess) null else "Edit ${proposal.filePath} failed: ${written.exceptionOrNull()?.message}",
            ),
        )
    }

    /**
     * Prompt blocks as the plain text the agent loop expects. Files the editor attached become `@{path}`
     * references (read through the editor, so unsaved text is used); other embedded text is inlined.
     */
    private fun promptText(content: List<ContentBlock>): String = content.mapNotNull { block ->
        when (block) {
            is ContentBlock.Text -> block.text
            is ContentBlock.ResourceLink -> fileReference(block.uri) ?: block.uri
            is ContentBlock.Resource -> when (val r = block.resource) {
                is EmbeddedResourceResource.TextResourceContents -> fileReference(r.uri) ?: "${r.uri}:\n${r.text}"
                is EmbeddedResourceResource.BlobResourceContents -> null
            }
            else -> null // images and audio are not advertised
        }
    }.joinToString("\n")

    private fun fileReference(uri: String): String? {
        val path = runCatching { Path.of(URI(uri)) }.getOrNull() ?: return null
        val root = cwd.toAbsolutePath().normalize()
        val normalized = path.toAbsolutePath().normalize()
        return if (normalized.startsWith(root)) "@{${root.relativize(normalized).joinToString("/")}}" else null
    }

    private companion object {
        val editCounter = AtomicInteger()
    }
}
