package com.llamatik.teammate.mcp

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.util.PluginLogger
import java.util.concurrent.Executors

/**
 * Application-level coordinator: starts/stops MCP servers, triggers tool bridge
 * registration, and exposes a unified view of all server + tool state.
 *
 * This is the single entry point for the rest of the plugin (UI, tool tools).
 */
@Service(Service.Level.APP)
class McpManagerService {

    private val logger = PluginLogger.logger<McpManagerService>()
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "llamatik-mcp-mgr").also { it.isDaemon = true }
    }

    private val configService by lazy { service<McpServerConfigService>() }
    private val clientService by lazy { service<McpClientService>() }
    private val bridgeService by lazy { service<McpToolBridgeService>() }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Called when the IDE/plugin starts — auto-starts configured servers. */
    fun autoStartServers() {
        configService.enabledConfigs()
            .filter { it.autoStart }
            .forEach { config ->
                executor.execute {
                    runCatching { startServer(config) }
                        .onFailure { logger.warn("McpManagerService: auto-start failed for '${config.name}'", it) }
                }
            }
    }

    /** Stops all running servers. Call on plugin dispose. */
    fun disposeAll() {
        clientService.stopAll()
        bridgeService.bridgedTools().values
            .map { it.serverId }
            .distinct()
            .forEach { bridgeService.unregisterServer(it) }
    }

    /** Starts a single server and registers its tools in the bridge. */
    fun startServer(config: McpServerConfig): McpServerStatus {
        val status = clientService.startServer(config)
        if (status.state == McpServerState.RUNNING) {
            refreshToolsForServer(config)
        }
        return status
    }

    /** Stops a server and unregisters its tools. */
    fun stopServer(serverId: String) {
        clientService.stopServer(serverId)
        bridgeService.unregisterServer(serverId)
    }

    /** Restarts a server (used after config changes). */
    fun restartServer(config: McpServerConfig): McpServerStatus {
        stopServer(config.id)
        return startServer(config)
    }

    /**
     * Re-lists tools/resources/prompts for a running server and updates the bridge.
     * Safe to call from any thread.
     */
    fun refreshToolsForServer(config: McpServerConfig) {
        val tools = runCatching { clientService.listTools(config.id) }.getOrElse { emptyList() }
        bridgeService.registerTools(config.name, config.id, tools)
        logger.info("McpManagerService: registered ${tools.size} tools for '${config.name}'")
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    fun serverStatus(serverId: String): McpServerStatus = clientService.serverStatus(serverId)

    fun allStatuses(): Map<String, McpServerStatus> = clientService.allStatuses()

    fun listAllTools(): List<McpToolDescriptor> =
        configService.enabledConfigs()
            .flatMap { config ->
                runCatching { clientService.listTools(config.id) }.getOrElse { emptyList() }
            }

    fun listAllResources(): List<McpResourceDescriptor> =
        configService.enabledConfigs()
            .flatMap { config ->
                runCatching { clientService.listResources(config.id) }.getOrElse { emptyList() }
            }

    fun listAllPrompts(): List<McpPromptDescriptor> =
        configService.enabledConfigs()
            .flatMap { config ->
                runCatching { clientService.listPrompts(config.id) }.getOrElse { emptyList() }
            }

    fun allBridgedToolNames(): Set<String> = bridgeService.allBridgedToolNames()

    fun getServerLogs(serverId: String): List<String> = clientService.getServerLogs(serverId)

    // ── Test connection ───────────────────────────────────────────────────────

    data class TestConnectionResult(
        val success: Boolean,
        val toolCount: Int = 0,
        val resourceCount: Int = 0,
        val promptCount: Int = 0,
        val error: String? = null,
        val logs: List<String> = emptyList(),
    )

    fun testConnection(config: McpServerConfig): TestConnectionResult {
        return try {
            val status = clientService.startServer(config)
            if (status.state != McpServerState.RUNNING) {
                return TestConnectionResult(false, error = status.lastError ?: "Server did not start")
            }
            val tools = runCatching { clientService.listTools(config.id) }.getOrElse { emptyList() }
            val resources = runCatching { clientService.listResources(config.id) }.getOrElse { emptyList() }
            val prompts = runCatching { clientService.listPrompts(config.id) }.getOrElse { emptyList() }
            val logs = clientService.getServerLogs(config.id)

            if (!config.autoStart) {
                clientService.stopServer(config.id)
            }
            TestConnectionResult(true, tools.size, resources.size, prompts.size, logs = logs)
        } catch (e: Exception) {
            val logs = runCatching { clientService.getServerLogs(config.id) }.getOrElse { emptyList() }
            if (!config.autoStart) {
                runCatching { clientService.stopServer(config.id) }
            }
            TestConnectionResult(false, error = e.message ?: "Connection failed", logs = logs)
        }
    }
}
