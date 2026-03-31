package com.llamatik.teammate.model

import java.util.UUID

enum class ChatRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: ChatRole,
    var content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val toolName: String? = null,
    val editProposalId: String? = null,
)

enum class AgentRole {
    GENERAL,
    PLANNER,
    DESIGNER,
    IMPLEMENTER,
    REVIEWER,
}

data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "New Chat",
    var agentRole: AgentRole = AgentRole.GENERAL,
    val messages: MutableList<ChatMessage> = mutableListOf(),
)

data class EditProposal(
    val id: String = UUID.randomUUID().toString(),
    val filePath: String,
    val oldContent: String,
    val newContent: String,
    val summary: String,
)

data class ModelDescriptor(
    val id: String,
    val displayName: String,
    val source: String,
    val description: String,
)
