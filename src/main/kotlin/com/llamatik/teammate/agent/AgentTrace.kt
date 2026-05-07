package com.llamatik.teammate.agent

data class AgentTrace(
    val steps: MutableList<AgentTraceStep> = mutableListOf(),
) {
    fun add(step: AgentTraceStep) { steps += step }
    fun isEmpty() = steps.isEmpty()
    fun formatted(): String = steps.joinToString("\n") { step ->
        val statusIcon = when (step.status) {
            AgentTraceStep.Status.SUCCESS  -> "✓"
            AgentTraceStep.Status.WARNING  -> "⚠"
            AgentTraceStep.Status.FAILED   -> "✗"
            AgentTraceStep.Status.SKIPPED  -> "–"
        }
        val durationPart = if (step.durationMs > 0) " (${step.durationMs}ms)" else ""
        "$statusIcon  [${step.type.label}] ${step.title}$durationPart" +
            if (step.summary.isNotBlank()) "\n   ${step.summary}" else ""
    }
}

data class AgentTraceStep(
    val type: Type,
    val title: String,
    val summary: String = "",
    val status: Status = Status.SUCCESS,
    val timestamp: Long = System.currentTimeMillis(),
    val durationMs: Long = 0L,
) {
    enum class Type(val label: String) {
        TOOL_CALL("tool"),
        FILE_READ("read"),
        SEARCH("search"),
        FALLBACK("fallback"),
        ERROR("error"),
        DECISION("decision"),
        VERIFICATION("verify"),
        MCP_CALL("mcp"),
    }

    enum class Status { SUCCESS, WARNING, FAILED, SKIPPED }
}
