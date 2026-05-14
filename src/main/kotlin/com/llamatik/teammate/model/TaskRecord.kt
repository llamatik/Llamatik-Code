package com.llamatik.teammate.model

enum class TaskStatus { RUNNING, DONE, HALTED, ERROR }

data class AgentExchange(
    val prompt: String,
    val responseText: String,
)

data class AgentTaskSnapshot(
    val prompt: String,
    val responseText: String,
    val status: TaskStatus,
    val pastExchanges: List<AgentExchange> = emptyList(),
)

data class TaskRecord(
    val id: String,
    val prompt: String,
    val status: TaskStatus,
    val startedAtMs: Long,
    val finishedAtMs: Long? = null,
    val changeCount: Int = 0,
    val tokenCount: Int = 0,
    val snapshot: AgentTaskSnapshot? = null,
)
