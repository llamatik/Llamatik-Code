package com.llamatik.teammate.persistence

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.model.AgentRole
import com.llamatik.teammate.model.ChatMessage
import com.llamatik.teammate.model.ChatRole
import com.llamatik.teammate.model.ChatSession
import java.util.UUID

@Service(Service.Level.PROJECT)
class ChatSessionStore(private val project: Project) {
    private val stateService = project.service<LlamatikProjectState>()

    fun loadSessions(): MutableList<ChatSession> {
        val stored = stateService.mutableState().sessions
        if (stored.isEmpty()) {
            val first = ChatSession(title = "Chat 1")
            saveSessions(mutableListOf(first), first.id)
            return mutableListOf(first)
        }
        return stored.map { session ->
            ChatSession(
                id = session.id.ifBlank { UUID.randomUUID().toString() },
                title = session.title,
                agentRole = runCatching { AgentRole.valueOf(session.agentRole) }.getOrDefault(AgentRole.GENERAL),
                messages = session.messages.map { message ->
                    ChatMessage(
                        id = message.id.ifBlank { UUID.randomUUID().toString() },
                        role = runCatching { ChatRole.valueOf(message.role) }.getOrDefault(ChatRole.USER),
                        content = message.content,
                        timestamp = message.timestamp,
                        toolName = message.toolName,
                        editProposalId = message.editProposalId,
                    )
                }.toMutableList(),
            )
        }.toMutableList()
    }

    fun saveSessions(sessions: List<ChatSession>, selectedId: String?) {
        val state = stateService.mutableState()
        state.sessions = sessions.map { session ->
            LlamatikProjectState.StoredSession(
                id = session.id,
                title = session.title,
                agentRole = session.agentRole.name,
                messages = session.messages.map { message ->
                    LlamatikProjectState.StoredMessage(
                        id = message.id,
                        role = message.role.name,
                        content = message.content,
                        timestamp = message.timestamp,
                        toolName = message.toolName,
                        editProposalId = message.editProposalId,
                    )
                }.toMutableList(),
            )
        }.toMutableList()
        state.selectedSessionId = selectedId
    }

    fun selectedSessionId(): String? = stateService.mutableState().selectedSessionId

    fun pinnedInstructions(): String = stateService.mutableState().pinnedInstructions

    fun setPinnedInstructions(value: String) {
        stateService.mutableState().pinnedInstructions = value
    }
}
