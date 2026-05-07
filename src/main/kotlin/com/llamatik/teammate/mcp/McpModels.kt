package com.llamatik.teammate.mcp

import java.util.UUID

// ── Server configuration ──────────────────────────────────────────────────────

data class McpServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val command: String = "",
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val workingDirectory: String = "",
    val enabled: Boolean = false,
    val autoStart: Boolean = false,
    val trustLevel: McpTrustLevel = McpTrustLevel.ASK_EVERY_TIME,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

enum class McpTrustLevel {
    ASK_EVERY_TIME,
    TRUST_SERVER,
    TRUST_ALL_TOOLS,
}

// ── Runtime status ────────────────────────────────────────────────────────────

data class McpServerStatus(
    val serverId: String,
    val state: McpServerState = McpServerState.STOPPED,
    val lastError: String? = null,
    val lastConnectedAt: Long? = null,
)

enum class McpServerState { STOPPED, STARTING, RUNNING, FAILED }

// ── Tool / resource / prompt descriptors ─────────────────────────────────────

data class McpToolDescriptor(
    val serverId: String,
    val name: String,
    val description: String = "",
    val inputSchema: Map<String, Any> = emptyMap(),
    val riskLevel: McpRiskLevel = McpRiskLevel.MEDIUM,
    val requiresApproval: Boolean = true,
)

enum class McpRiskLevel { LOW, MEDIUM, HIGH }

data class McpResourceDescriptor(
    val serverId: String,
    val uri: String,
    val name: String = "",
    val description: String = "",
    val mimeType: String = "",
)

data class McpPromptDescriptor(
    val serverId: String,
    val name: String,
    val description: String = "",
    val argumentsSchema: Map<String, Any> = emptyMap(),
)

// ── Call result ───────────────────────────────────────────────────────────────

data class McpCallResult(
    val serverId: String,
    val toolName: String,
    val success: Boolean,
    val content: String,
    val error: String? = null,
    val durationMs: Long = 0L,
)

// ── Approval decision ─────────────────────────────────────────────────────────

enum class McpApprovalDecision {
    ALLOW_ONCE,
    ALLOW_ALWAYS_TOOL,
    ALLOW_ALWAYS_SERVER,
    DENY,
}
