package com.llamatik.teammate.benchmark

import java.time.Instant

data class BenchmarkResult(
    val taskId: String,
    val taskName: String,
    val language: String,
    val promptPath: String,
    val testCommand: String,
    val passed: Boolean,
    val agentStatus: AgentStatus,
    val durationMs: Long,
    val stdoutPath: String?,
    val stderrPath: String?,
    val changedFiles: List<String>,
    val errorMessage: String?,
    val startedAt: Instant,
    val finishedAt: Instant,
    val modelLabel: String,
    val pluginVersion: String,
)

enum class AgentStatus {
    SUCCESS,
    AGENT_ERROR,
    TIMEOUT,
    VERIFICATION_FAILED,
    SETUP_ERROR,
}
