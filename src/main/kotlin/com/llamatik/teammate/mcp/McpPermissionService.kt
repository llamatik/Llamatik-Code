package com.llamatik.teammate.mcp

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.llamatik.teammate.settings.LlamatikAppSettings

/**
 * Decides whether an MCP tool call requires user approval.
 *
 * Free:  every call requires approval.
 * Pro:   auto-approval available per server/tool, but HIGH-risk tools
 *        still require explicit opt-in (never auto-approved by default).
 */
@Service(Service.Level.APP)
class McpPermissionService {

    private val configService by lazy { service<McpServerConfigService>() }

    fun requiresApproval(serverId: String, toolName: String, descriptor: McpToolDescriptor?): Boolean {
        val risk = descriptor?.riskLevel ?: classifyRisk(toolName, descriptor?.description ?: "")
        return computeApprovalRequired(
            isPro = configService.isPro(),
            riskLevel = risk,
            isExplicitlyAutoApproved = isExplicitlyAutoApproved(serverId, toolName),
            isServerAutoApproved = configService.isAutoApproved(serverId, toolName),
        )
    }

    /** Classifies a tool by name/description heuristic. */
    fun classifyRisk(toolName: String, description: String): McpRiskLevel {
        val combined = (toolName + " " + description).lowercase()
        return when {
            HIGH_RISK_KEYWORDS.any { combined.contains(it) } -> McpRiskLevel.HIGH
            LOW_RISK_KEYWORDS.any { combined.contains(it) } -> McpRiskLevel.LOW
            else -> McpRiskLevel.MEDIUM
        }
    }

    fun isAutoApproved(serverId: String, toolName: String): Boolean {
        if (!configService.isPro()) return false
        return isExplicitlyAutoApproved(serverId, toolName) ||
            configService.isAutoApproved(serverId, toolName)
    }

    fun addAutoApproval(serverId: String, toolName: String) {
        if (!configService.isPro()) return
        val key = "$serverId::$toolName"
        val state = service<LlamatikAppSettings>().data()
        if (!state.mcpAutoApprovedTools.contains(key)) {
            state.mcpAutoApprovedTools.add(key)
        }
    }

    fun removeAutoApproval(serverId: String, toolName: String) {
        val key = "$serverId::$toolName"
        service<LlamatikAppSettings>().data().mcpAutoApprovedTools.remove(key)
    }

    fun removeAllAutoApprovalsForServer(serverId: String) {
        service<LlamatikAppSettings>().data().mcpAutoApprovedTools
            .removeAll { it.startsWith("$serverId::") }
    }

    private fun isExplicitlyAutoApproved(serverId: String, toolName: String): Boolean {
        val key = "$serverId::$toolName"
        return service<LlamatikAppSettings>().data().mcpAutoApprovedTools.contains(key)
    }

    companion object {
        /**
         * Pure function: given all inputs already resolved, returns whether a tool call
         * needs user approval.
         *
         * Rules:
         * - Free: always requires approval.
         * - Pro + HIGH risk: requires approval unless explicitly auto-approved per-tool.
         * - Pro + non-HIGH: no approval needed if either explicit per-tool or server-level
         *   auto-approval is configured.
         */
        fun computeApprovalRequired(
            isPro: Boolean,
            riskLevel: McpRiskLevel,
            isExplicitlyAutoApproved: Boolean,
            isServerAutoApproved: Boolean,
        ): Boolean {
            if (!isPro) return true
            if (riskLevel == McpRiskLevel.HIGH && !isExplicitlyAutoApproved) return true
            return !(isExplicitlyAutoApproved || isServerAutoApproved)
        }

        private val HIGH_RISK_KEYWORDS = listOf(
            "delete", "remove", "drop", "destroy", "purge", "wipe", "truncate",
            "execute", "run", "exec", "shell", "bash", "cmd", "script",
            "deploy", "publish", "send", "post", "push", "upload", "submit",
            "write", "update", "create", "insert", "modify", "patch", "overwrite",
            "network", "http", "curl", "fetch",
        )
        private val LOW_RISK_KEYWORDS = listOf(
            "read", "get", "list", "find", "search", "query", "describe",
            "status", "info", "show", "view", "inspect", "check",
        )
    }
}
