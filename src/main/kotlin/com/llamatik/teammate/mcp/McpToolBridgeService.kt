package com.llamatik.teammate.mcp

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.tools.LlamatikTool
import com.llamatik.teammate.tools.ToolCall
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolResult
import com.llamatik.teammate.util.PluginLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * Converts discovered MCP tools into [LlamatikTool] instances so the existing
 * [com.llamatik.teammate.tools.ToolRegistry] can route calls to them.
 *
 * Name collision avoidance: every MCP tool is exposed as `mcp_<serverName>_<toolName>`,
 * where <serverName> is normalised (lowercase, spaces → underscores, non-word chars stripped).
 */
@Service(Service.Level.APP)
class McpToolBridgeService {

    private val logger = PluginLogger.logger<McpToolBridgeService>()

    /** All currently registered MCP tools, keyed by bridged name. */
    private val bridgedTools = ConcurrentHashMap<String, McpBridgedTool>()

    fun bridgedTools(): Map<String, McpBridgedTool> = bridgedTools.toMap()

    /** Registers tools for the given server.  Existing entries for this server are replaced. */
    fun registerTools(serverName: String, serverId: String, tools: List<McpToolDescriptor>) {
        unregisterServer(serverId)
        tools.forEach { descriptor ->
            val bridgedName = bridgedName(serverName, descriptor.name)
            bridgedTools[bridgedName] = McpBridgedTool(bridgedName, serverId, serverName, descriptor)
            logger.info("McpToolBridgeService: registered '$bridgedName'")
        }
    }

    fun unregisterServer(serverId: String) {
        val removed = bridgedTools.entries.removeAll { it.value.serverId == serverId }
        if (removed) logger.info("McpToolBridgeService: unregistered tools for server id=$serverId")
    }

    fun allBridgedToolNames(): Set<String> = bridgedTools.keys.toSet()

    fun getDescriptor(bridgedName: String): McpToolDescriptor? =
        bridgedTools[bridgedName]?.descriptor

    /** Creates a [LlamatikTool] that the registry can call. */
    fun asLlamatikTool(bridgedName: String, project: Project): LlamatikTool? {
        val tool = bridgedTools[bridgedName] ?: return null
        return McpLlamatikToolAdapter(bridgedName, tool.serverId, tool.serverName, tool.descriptor, project)
    }

    companion object {
        fun bridgedName(serverName: String, toolName: String): String {
            val safe = serverName.lowercase()
                .replace(Regex("[^a-z0-9_]"), "_")
                .replace(Regex("_+"), "_")
                .trim('_')
            return "mcp_${safe}_$toolName"
        }
    }

    data class McpBridgedTool(
        val bridgedName: String,
        val serverId: String,
        val serverName: String,
        val descriptor: McpToolDescriptor,
    )
}

/**
 * Wraps an MCP tool as a [LlamatikTool].
 *
 * Approval is handled here: if the call requires approval and none has been granted,
 * execution is blocked and the result contains a sentinel that the approval UI can detect.
 */
class McpLlamatikToolAdapter(
    override val name: String,
    private val serverId: String,
    private val serverName: String,
    private val descriptor: McpToolDescriptor,
    private val project: Project,
) : LlamatikTool {

    override val description: String
        get() = "[MCP: $serverName] ${descriptor.description}"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val permService = service<McpPermissionService>()
        val clientService = service<McpClientService>()

        val requiresApproval = permService.requiresApproval(serverId, descriptor.name, descriptor)
        if (requiresApproval) {
            // Signal the agent UI to request approval.  The result content uses a structured
            // sentinel prefix so the orchestrator can detect it and pause for user input.
            return ToolResult(
                name = name,
                success = false,
                content = MCP_APPROVAL_REQUIRED_PREFIX +
                    "Server: $serverName\n" +
                    "Tool: ${descriptor.name}\n" +
                    "Risk: ${descriptor.riskLevel}\n" +
                    "Arguments: ${McpClientService.mapToJson(call.arguments)}",
            )
        }

        @Suppress("UNCHECKED_CAST")
        val arguments = call.arguments as Map<String, Any>
        val result = clientService.callTool(serverId, descriptor.name, arguments)

        return ToolResult(
            name = name,
            success = result.success,
            content = if (result.success) result.content
                      else "MCP error from ${descriptor.name}: ${result.error}",
        )
    }

    companion object {
        const val MCP_APPROVAL_REQUIRED_PREFIX = "MCP_APPROVAL_REQUIRED:"
    }
}
