package com.llamatik.agent.core.agent

/**
 * Hard limits of one agent run. The defaults are what the IDE plugin has always used; hosts and
 * tests may tighten them.
 */
data class AgentLimits(
    /** Rounds of model call + tool execution before the loop must conclude. The last-but-one is the write pass. */
    val maxRounds: Int = MAX_ROUNDS,
    val maxToolCallsPerRound: Int = MAX_TOOL_CALLS_PER_ROUND,
    /** Wall-clock cap for the whole run; not applied to the write pass. */
    val sessionTimeoutMs: Long = AGENT_SESSION_TIMEOUT_MS,
)
