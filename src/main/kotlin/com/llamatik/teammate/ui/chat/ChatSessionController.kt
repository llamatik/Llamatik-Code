package com.llamatik.teammate.ui.chat

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.model.ChatMessage
import com.llamatik.teammate.model.ChatSession
import com.llamatik.teammate.persistence.ChatSessionStore
import com.llamatik.teammate.ui.Strings

class ChatSessionController(private val project: Project) {
    private val store = project.service<ChatSessionStore>()
    val sessions: MutableList<ChatSession> = store.loadSessions()
    var selectedSessionId: String? = store.selectedSessionId() ?: sessions.firstOrNull()?.id

    fun selectedSession(): ChatSession = sessions.first { it.id == selectedSessionId } 

    fun addSession(): ChatSession {
        val session = ChatSession(title = Strings.Session.numberedTitle(sessions.size + 1))
        sessions += session
        selectedSessionId = session.id
        persist()
        return session
    }

    fun removeSession(id: String) {
        sessions.removeIf { it.id == id }
        if (sessions.isEmpty()) sessions += ChatSession(title = Strings.Session.INITIAL_TITLE)
        if (selectedSessionId == id) selectedSessionId = sessions.first().id
        persist()
    }

    fun appendMessage(sessionId: String, message: ChatMessage) {
        sessions.firstOrNull { it.id == sessionId }?.messages?.add(message)
        persist()
    }

    fun clearSession(sessionId: String) {
        sessions.firstOrNull { it.id == sessionId }?.messages?.clear()
        persist()
    }

    fun persist() {
        store.saveSessions(sessions, selectedSessionId)
    }
}
