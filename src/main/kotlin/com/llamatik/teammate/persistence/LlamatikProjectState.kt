package com.llamatik.teammate.persistence

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import com.llamatik.teammate.model.AgentRole
import com.llamatik.teammate.model.ChatRole

@State(name = "LlamatikProjectState", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
@Service(Service.Level.PROJECT)
class LlamatikProjectState(private val project: Project) : PersistentStateComponent<LlamatikProjectState.State> {
    data class StoredMessage(
        var id: String = "",
        var role: String = ChatRole.USER.name,
        var content: String = "",
        var timestamp: Long = 0,
        var toolName: String? = null,
        var editProposalId: String? = null,
    )

    data class StoredSession(
        var id: String = "",
        var title: String = "New Chat",
        var agentRole: String = AgentRole.GENERAL.name,
        var messages: MutableList<StoredMessage> = mutableListOf(),
    )

    data class State(
        var sessions: MutableList<StoredSession> = mutableListOf(),
        var selectedSessionId: String? = null,
        var pinnedInstructions: String = "",
    )

    private var myState = State()

    override fun getState(): State = myState
    override fun loadState(state: State) { this.myState = state }

    fun mutableState(): State = myState
}
