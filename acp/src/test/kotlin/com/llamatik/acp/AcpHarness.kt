package com.llamatik.acp

import com.agentclientprotocol.agent.Agent
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.CreateTerminalResponse
import com.agentclientprotocol.model.FileSystemCapability
import com.agentclientprotocol.model.KillTerminalCommandResponse
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.ReadTextFileResponse
import com.agentclientprotocol.model.ReleaseTerminalResponse
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.TerminalExitStatus
import com.agentclientprotocol.model.TerminalOutputResponse
import com.agentclientprotocol.model.WaitForTerminalExitResponse
import com.agentclientprotocol.model.WriteTextFileResponse
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import com.llamatik.agent.backends.BackendHandle
import com.llamatik.agent.backends.InferenceParams
import com.llamatik.agent.core.backend.AssistantBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.CopyOnWriteArrayList

/** The editor side: what files and terminal it offers and how its user answers permission prompts. */
class FakeEditor(
    /** Unsaved editor buffers, by absolute path; reads of other files fail like a missing buffer. */
    val buffers: MutableMap<String, String> = mutableMapOf(),
    /** Option ids to pick, in order ("allow", "always", "reject"); anything missing rejects. */
    permissionAnswers: List<String> = emptyList(),
    val terminalOutput: String = "BUILD SUCCESSFUL",
    val terminalExit: UInt = 0u,
) : ClientSessionOperations {
    private val answers = ArrayDeque(permissionAnswers)
    val updates = CopyOnWriteArrayList<SessionUpdate>()
    val permissionRequests = CopyOnWriteArrayList<Pair<String?, List<PermissionOption>>>()
    val written = CopyOnWriteArrayList<Pair<String, String>>()
    val terminalCommands = CopyOnWriteArrayList<List<String>>()

    override suspend fun requestPermissions(toolCall: SessionUpdate.ToolCallUpdate, permissions: List<PermissionOption>, _meta: JsonElement?): RequestPermissionResponse {
        permissionRequests += toolCall.title to permissions
        val pick = answers.removeFirstOrNull() ?: "reject"
        val option = permissions.firstOrNull { it.optionId.value == pick } ?: permissions.last()
        return RequestPermissionResponse(RequestPermissionOutcome.Selected(option.optionId))
    }

    override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) { updates += notification }

    override suspend fun fsReadTextFile(path: String, line: UInt?, limit: UInt?, _meta: JsonElement?): ReadTextFileResponse =
        ReadTextFileResponse(buffers[path] ?: error("no buffer for $path"))

    override suspend fun fsWriteTextFile(path: String, content: String, _meta: JsonElement?): WriteTextFileResponse {
        written += path to content
        return WriteTextFileResponse()
    }

    override suspend fun terminalCreate(command: String, args: List<String>, cwd: String?, env: List<com.agentclientprotocol.model.EnvVariable>, outputByteLimit: ULong?, _meta: JsonElement?): CreateTerminalResponse {
        terminalCommands += listOf(command) + args
        return CreateTerminalResponse("term-1")
    }
    override suspend fun terminalWaitForExit(terminalId: String, _meta: JsonElement?) = WaitForTerminalExitResponse(exitCode = terminalExit)
    override suspend fun terminalOutput(terminalId: String, _meta: JsonElement?) =
        TerminalOutputResponse(terminalOutput, false, TerminalExitStatus(exitCode = terminalExit))
    override suspend fun terminalRelease(terminalId: String, _meta: JsonElement?) = ReleaseTerminalResponse()
    override suspend fun terminalKill(terminalId: String, _meta: JsonElement?) = KillTerminalCommandResponse()
}

/** A running agent and a client wired together in memory, speaking the real protocol. */
class AcpConnection(
    private val scope: CoroutineScope,
    val client: Client,
    private val closeAll: () -> Unit,
) : AutoCloseable {
    override fun close() = closeAll()

    suspend fun newSession(cwd: String, editor: FakeEditor): ClientSession =
        client.newSession(SessionCreationParameters(cwd, emptyList())) { _, _ -> editor }

    companion object {
        suspend fun open(
            options: AcpOptions = AcpOptions(),
            capabilities: ClientCapabilities = ClientCapabilities(fs = FileSystemCapability(true, true), terminal = true),
            backend: () -> BackendHandle,
        ): AcpConnection {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val toAgent = Channel<String>(Channel.UNLIMITED)
            val toClient = Channel<String>(Channel.UNLIMITED)

            val agentProtocol = Protocol(scope, StdioTransport(scope, Dispatchers.Default, toAgent.consumeAsFlow(), { toClient.send(it) }))
            Agent(agentProtocol, LlamatikAgent(options, backend))
            agentProtocol.start()

            val clientProtocol = Protocol(scope, StdioTransport(scope, Dispatchers.Default, toClient.consumeAsFlow(), { toAgent.send(it) }))
            val client = Client(clientProtocol)
            clientProtocol.start()
            client.initialize(ClientInfo(capabilities = capabilities))
            return AcpConnection(scope, client) {
                toAgent.close(); toClient.close(); scope.cancel()
            }
        }

        fun handle(backend: AssistantBackend) =
            BackendHandle(backend, InferenceParams(contextSize = 4096, maxTokens = 512, threads = 1).toConfig("fake"), "fake backend")
    }
}

/** Runs a prompt to completion and returns what the agent sent. */
suspend fun ClientSession.ask(text: String): Turn {
    val events = prompt(listOf(ContentBlock.Text(text))).toList()
    val response = events.filterIsInstance<Event.PromptResponseEvent>().single().response
    val message = events.filterIsInstance<Event.SessionUpdateEvent>().map { it.update }
        .filterIsInstance<SessionUpdate.AgentMessageChunk>().joinToString("") { (it.content as? ContentBlock.Text)?.text.orEmpty() }
    return Turn(response, message, events.filterIsInstance<Event.SessionUpdateEvent>().map { it.update })
}

class Turn(val response: PromptResponse, val message: String, val updates: List<SessionUpdate>)
