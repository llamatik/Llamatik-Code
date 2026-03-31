package com.llamatik.teammate.agent

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.backend.AssistantBackend
import com.llamatik.teammate.backend.BackendConfig
import com.llamatik.teammate.backend.GenerationCallbacks
import com.llamatik.teammate.backend.GenerationHandle
import com.llamatik.teammate.backend.GenerationRequest
import com.llamatik.teammate.context.ProjectContextService
import com.llamatik.teammate.llamatik.LlamatikBackendService
import com.llamatik.teammate.model.ChatMessage
import com.llamatik.teammate.model.ChatRole
import com.llamatik.teammate.model.ChatSession
import com.llamatik.teammate.model.EditProposal
import com.llamatik.teammate.settings.LlamatikAppSettings
import com.llamatik.teammate.tools.ToolCallParser
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolRegistry
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.PROJECT)
class AgentOrchestrator(private val project: Project) {
    private val backend: AssistantBackend = service<LlamatikBackendService>()
    private val toolRegistry = project.service<ToolRegistry>()
    private val contextService = ProjectContextService(project)

    fun selectedModelLabel(): String = backend.selectedModelLabel()
    fun isModelLoaded(): Boolean = backend.isModelLoaded()

    fun submit(
        session: ChatSession,
        userPrompt: String,
        onToken: (String) -> Unit,
        onFinished: (ChatMessage, EditProposal?) -> Unit,
        onError: (String) -> Unit,
    ): GenerationHandle {
        val settings = service<LlamatikAppSettings>().data()
        val cancelled = AtomicBoolean(false)
        // Tracks whichever backend call is currently active so cancel() always reaches the live handle.
        val activeHandle = AtomicReference<GenerationHandle>()

        val compositeHandle = object : GenerationHandle {
            override fun cancel() {
                cancelled.set(true)
                activeHandle.get()?.cancel()
            }
            override val isCancelled: Boolean get() = cancelled.get()
        }

        val toolAugmentedMessages = mutableListOf<ChatMessage>()
        toolAugmentedMessages += ChatMessage(role = ChatRole.SYSTEM, content = buildSystemPrompt())
        toolAugmentedMessages += session.messages.takeLast(12)
        toolAugmentedMessages += ChatMessage(role = ChatRole.USER, content = userPrompt)
        toolAugmentedMessages += ChatMessage(role = ChatRole.SYSTEM, content = buildContextHint())

        val config = BackendConfig(
            modelIdOrPath = settings.modelPath.ifBlank { settings.modelIdentifier },
            temperature = settings.temperature,
            maxTokens = settings.maxTokens,
            contextSize = settings.contextSize,
            localOnly = settings.localOnly,
        )

        val firstHandle = backend.generate(
            GenerationRequest(systemPrompt = buildSystemPrompt(), messages = toolAugmentedMessages, config = config),
            object : GenerationCallbacks {
                override fun onToken(token: String) {
                    if (!cancelled.get()) onToken(token)
                }

                override fun onCompleted(fullText: String) {
                    if (cancelled.get()) return
                    val toolCalls = ToolCallParser.parse(fullText).take(4)
                    if (toolCalls.isEmpty()) {
                        val proposal = extractEditProposal(fullText)
                        onFinished(
                            ChatMessage(role = ChatRole.ASSISTANT, content = fullText, editProposalId = proposal?.id),
                            proposal,
                        )
                        return
                    }

                    val toolMessages = toolCalls.map { call ->
                        val result = toolRegistry.execute(call, ToolExecutionContext(project))
                        ChatMessage(role = ChatRole.TOOL, content = result.content, toolName = result.name)
                    }

                    if (cancelled.get()) return

                    val secondPassMessages = toolAugmentedMessages + toolMessages + ChatMessage(
                        role = ChatRole.SYSTEM,
                        content = "Tool results above are authoritative. Now answer the user. " +
                            "If proposing a file change, append an edit proposal using the exact format: " +
                            "<edit_proposal path=\"relative/path\" summary=\"short summary\">" +
                            "<new_content><![CDATA[...]]></new_content></edit_proposal>",
                    )

                    val secondHandle = backend.generate(
                        GenerationRequest(
                            systemPrompt = buildSystemPrompt(),
                            messages = secondPassMessages,
                            config = config,
                        ),
                        object : GenerationCallbacks {
                            override fun onToken(token: String) {
                                if (!cancelled.get()) onToken(token)
                            }

                            override fun onCompleted(fullText: String) {
                                if (cancelled.get()) return
                                val cleaned = ToolCallParser.removeToolMarkup(fullText)
                                val proposal = extractEditProposal(cleaned)
                                val answer = cleaned.replace(editRegex, "").trim()
                                onFinished(
                                    ChatMessage(role = ChatRole.ASSISTANT, content = answer, editProposalId = proposal?.id),
                                    proposal,
                                )
                            }

                            override fun onError(message: String, throwable: Throwable?) {
                                if (!cancelled.get()) onError(message)
                            }
                        },
                    )
                    activeHandle.set(secondHandle)
                    // If cancel was requested between the two passes, forward it now.
                    if (cancelled.get()) secondHandle.cancel()
                }

                override fun onError(message: String, throwable: Throwable?) {
                    if (!cancelled.get()) onError(message)
                }
            },
        )
        activeHandle.set(firstHandle)

        return compositeHandle
    }

    private fun buildContextHint(): String = buildString {
        appendLine("Project context:")
        appendLine("- projectName=${contextService.projectName()}")
        appendLine("- rootPath=${contextService.rootPath()}")
        appendLine("- currentFile=${contextService.getCurrentFile() ?: ""}")
        appendLine("- selectedText=${contextService.getSelectedText()?.take(2000) ?: ""}")
        appendLine("- pinnedInstructions=${contextService.pinnedInstructions()}")
        appendLine("- recentChat=${contextService.recentChatSummary()}")
    }

    private fun buildSystemPrompt(): String = buildString {
        appendLine("You are Llamatik Code, a local IntelliJ coding agent for the Llamatik Teammate plugin.")
        appendLine("Prefer tools over assumptions. Never claim project facts unless they come from tool results or provided context.")
        appendLine("Respect privacy, denylisted files, and local-first/offline constraints.")
        appendLine("When you need project data, emit structured tool calls exactly like this:")
        appendLine("<tool_call name=\"read_file\"><arg name=\"path\">src/Main.kt</arg></tool_call>")
        appendLine("Available tools:")
        appendLine(toolRegistry.describeTools())
        appendLine("Before edits, briefly explain the plan. Prefer plan -> patch -> apply -> run checks.")
        appendLine("Return concise, implementation-oriented answers. If proposing edits, include at most one file edit proposal per response.")
    }

    private val editRegex = Regex(
        "<edit_proposal\\s+path=\\\"([^\\\"]+)\\\"\\s+summary=\\\"([^\\\"]*)\\\">\\s*<new_content><!\\[CDATA\\[(.*?)]]></new_content>\\s*</edit_proposal>",
        RegexOption.DOT_MATCHES_ALL,
    )

    private fun extractEditProposal(text: String): EditProposal? {
        val match = editRegex.find(text) ?: return null
        val path = match.groupValues[1]
        val summary = match.groupValues[2]
        val newContent = match.groupValues[3]
        val oldContent = runCatching { contextService.readFile(path) }.getOrDefault("")
        return EditProposal(filePath = path, oldContent = oldContent, newContent = newContent, summary = summary)
    }
}
