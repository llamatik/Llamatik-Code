package com.llamatik.teammate.mcp

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.llamatik.teammate.licensing.LicenseChecker
import com.llamatik.teammate.settings.LlamatikAppSettings
import com.llamatik.teammate.util.PluginLogger
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Persists MCP server configs and enforces the Free/Pro one-server limit.
 *
 * Serialisation uses [LlamatikAppSettings.State.mcpServers] — a flat list of
 * [StoredMcpServer] objects that round-trip cleanly through IntelliJ XmlSerializer.
 */
@Service(Service.Level.APP)
class McpServerConfigService {

    private val logger = PluginLogger.logger<McpServerConfigService>()

    // ── Public API ────────────────────────────────────────────────────────────

    fun listConfigs(): List<McpServerConfig> = settings().mcpServers.map { it.toConfig() }

    fun getConfig(id: String): McpServerConfig? =
        settings().mcpServers.find { it.id == id }?.toConfig()

    fun saveConfig(config: McpServerConfig): Result<Unit> {
        val state = settings()
        val existing = state.mcpServers.indexOfFirst { it.id == config.id }

        if (config.enabled && !isPro()) {
            val otherEnabled = state.mcpServers.count { it.enabled && it.id != config.id }
            if (otherEnabled >= 1) {
                return Result.failure(McpProLimitException(
                    "Only one active MCP server is allowed on the Free plan. Upgrade to Pro to enable multiple servers."
                ))
            }
        }

        val stored = StoredMcpServer.fromConfig(config)
        if (existing >= 0) {
            state.mcpServers[existing] = stored
        } else {
            state.mcpServers.add(stored)
        }
        logger.info("McpServerConfigService: saved config '${config.name}' (id=${config.id})")
        return Result.success(Unit)
    }

    fun deleteConfig(id: String) {
        settings().mcpServers.removeAll { it.id == id }
        logger.info("McpServerConfigService: deleted config id=$id")
    }

    fun enabledConfigs(): List<McpServerConfig> = listConfigs().filter { it.enabled }

    fun isPro(): Boolean = runCatching { LicenseChecker.isLicensed() == true }.getOrElse { false }

    // ── Auto-approval state ───────────────────────────────────────────────────

    /** Returns true when this tool/server is configured for auto-approval (Pro only). */
    fun isAutoApproved(serverId: String, toolName: String): Boolean {
        if (!isPro()) return false
        val config = getConfig(serverId) ?: return false
        return when (config.trustLevel) {
            McpTrustLevel.TRUST_ALL_TOOLS -> true
            McpTrustLevel.TRUST_SERVER -> true
            McpTrustLevel.ASK_EVERY_TIME -> false
        }
    }

    fun setToolTrustLevel(serverId: String, level: McpTrustLevel) {
        if (!isPro()) return
        val state = settings()
        val idx = state.mcpServers.indexOfFirst { it.id == serverId }
        if (idx >= 0) {
            state.mcpServers[idx] = state.mcpServers[idx].copy(trustLevel = level.name)
        }
    }

    // ── Serialisable storage class ────────────────────────────────────────────

    data class StoredMcpServer(
        var id: String = "",
        var name: String = "",
        var command: String = "",
        var args: String = "",           // newline-separated
        var env: String = "",            // KEY=VALUE newline-separated
        var workingDirectory: String = "",
        var enabled: Boolean = false,
        var autoStart: Boolean = false,
        var trustLevel: String = McpTrustLevel.ASK_EVERY_TIME.name,
        var createdAt: Long = 0L,
        var updatedAt: Long = 0L,
    ) {
        fun toConfig() = McpServerConfig(
            id = id,
            name = name,
            command = command,
            args = args.lines().filter { it.isNotBlank() },
            env = env.lines().filter { it.contains('=') }.associate { line ->
                val eq = line.indexOf('=')
                line.substring(0, eq).trim() to line.substring(eq + 1)
            },
            workingDirectory = workingDirectory,
            enabled = enabled,
            autoStart = autoStart,
            trustLevel = runCatching { McpTrustLevel.valueOf(trustLevel) }.getOrDefault(McpTrustLevel.ASK_EVERY_TIME),
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

        companion object {
            fun fromConfig(c: McpServerConfig) = StoredMcpServer(
                id = c.id,
                name = c.name,
                command = c.command,
                args = c.args.joinToString("\n"),
                env = c.env.entries.joinToString("\n") { "${it.key}=${it.value}" },
                workingDirectory = c.workingDirectory,
                enabled = c.enabled,
                autoStart = c.autoStart,
                trustLevel = c.trustLevel.name,
                createdAt = c.createdAt,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun settings(): LlamatikAppSettings.State = service<LlamatikAppSettings>().data()
}

class McpProLimitException(message: String) : Exception(message)
