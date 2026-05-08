package com.llamatik.teammate.mcp

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.tools.LlamatikTool
import com.llamatik.teammate.tools.ToolCall
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolResult

// ── mcp_list_servers ──────────────────────────────────────────────────────────

class McpListServersTool(private val project: Project) : LlamatikTool {
    override val name = "mcp_list_servers"
    override val description = "List configured MCP servers and their running status."

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val configService = service<McpServerConfigService>()
        val managerService = service<McpManagerService>()
        val configs = configService.listConfigs()
        if (configs.isEmpty()) return ToolResult(name, true, "No MCP servers configured.")
        val statuses = managerService.allStatuses()
        val lines = configs.map { c ->
            val state = statuses[c.id]?.state ?: McpServerState.STOPPED
            val error = statuses[c.id]?.lastError?.let { " (error: $it)" } ?: ""
            "- ${c.name} [${c.id.take(8)}]: ${if (c.enabled) "enabled" else "disabled"} | state=$state$error"
        }
        return ToolResult(name, true, lines.joinToString("\n"))
    }
}

// ── mcp_list_tools ────────────────────────────────────────────────────────────

class McpListToolsTool(private val project: Project) : LlamatikTool {
    override val name = "mcp_list_tools"
    override val description = "List all discovered MCP tools grouped by server."

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val configService = service<McpServerConfigService>()
        val clientService = service<McpClientService>()
        val configs = configService.enabledConfigs()
        if (configs.isEmpty()) return ToolResult(name, true, "No enabled MCP servers.")
        val sb = StringBuilder()
        configs.forEach { config ->
            val tools = runCatching { clientService.listTools(config.id) }.getOrElse { emptyList() }
            sb.appendLine("Server: ${config.name}")
            if (tools.isEmpty()) {
                sb.appendLine("  (no tools discovered)")
            } else {
                tools.forEach { t ->
                    sb.appendLine("  - ${t.name} [${t.riskLevel}]: ${t.description.take(100)}")
                }
            }
        }
        return ToolResult(name, true, sb.trimEnd().toString())
    }
}

// ── mcp_call_tool ─────────────────────────────────────────────────────────────

class McpCallToolTool(private val project: Project) : LlamatikTool {
    override val name = "mcp_call_tool"
    override val description = "Call an MCP tool by server name and tool name. Args: server_name, tool_name, arguments (JSON string)."

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val serverName = call.arguments["server_name"]?.trim() ?: ""
        val toolName = call.arguments["tool_name"]?.trim() ?: ""
        val argsJson = call.arguments["arguments"]?.trim() ?: "{}"

        if (serverName.isBlank()) return ToolResult(name, false, "Error: server_name is required.")
        if (toolName.isBlank()) return ToolResult(name, false, "Error: tool_name is required.")

        val configService = service<McpServerConfigService>()
        val clientService = service<McpClientService>()
        val permService = service<McpPermissionService>()

        val config = configService.enabledConfigs().find { it.name == serverName }
            ?: return ToolResult(name, false, "Error: no enabled server named '$serverName'.")

        val tools = runCatching { clientService.listTools(config.id) }.getOrElse { emptyList() }
        val descriptor = tools.find { it.name == toolName }

        val requiresApproval = permService.requiresApproval(config.id, toolName, descriptor)
        if (requiresApproval) {
            return ToolResult(
                name, false,
                McpLlamatikToolAdapter.MCP_APPROVAL_REQUIRED_PREFIX +
                    "Server: $serverName\nTool: $toolName\nRisk: ${descriptor?.riskLevel ?: "UNKNOWN"}\nArguments: $argsJson"
            )
        }

        val arguments: Map<String, Any> = runCatching {
            McpClientService.parseJson(argsJson)
        }.getOrElse { emptyMap() }

        val result = clientService.callTool(config.id, toolName, arguments)
        return ToolResult(
            name = name,
            success = result.success,
            content = if (result.success) result.content
                      else "MCP error: ${result.error}",
        )
    }
}

// ── mcp_list_resources ────────────────────────────────────────────────────────

class McpListResourcesTool(private val project: Project) : LlamatikTool {
    override val name = "mcp_list_resources"
    override val description = "List all MCP resources grouped by server."

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val configService = service<McpServerConfigService>()
        val clientService = service<McpClientService>()
        val configs = configService.enabledConfigs()
        if (configs.isEmpty()) return ToolResult(name, true, "No enabled MCP servers.")
        val sb = StringBuilder()
        configs.forEach { config ->
            val resources = runCatching { clientService.listResources(config.id) }.getOrElse { emptyList() }
            sb.appendLine("Server: ${config.name}")
            if (resources.isEmpty()) {
                sb.appendLine("  (no resources)")
            } else {
                resources.forEach { r ->
                    sb.appendLine("  - ${r.uri} (${r.mimeType}): ${r.description.take(80)}")
                }
            }
        }
        return ToolResult(name, true, sb.trimEnd().toString())
    }
}

// ── mcp_read_resource ─────────────────────────────────────────────────────────

class McpReadResourceTool(private val project: Project) : LlamatikTool {
    override val name = "mcp_read_resource"
    override val description = "Read an MCP resource by URI. Args: server_name, uri."

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val serverName = call.arguments["server_name"]?.trim() ?: ""
        val uri = call.arguments["uri"]?.trim() ?: ""
        if (serverName.isBlank()) return ToolResult(name, false, "Error: server_name is required.")
        if (uri.isBlank()) return ToolResult(name, false, "Error: uri is required.")

        val configService = service<McpServerConfigService>()
        val clientService = service<McpClientService>()
        val config = configService.enabledConfigs().find { it.name == serverName }
            ?: return ToolResult(name, false, "Error: no enabled server named '$serverName'.")

        val result = clientService.readResource(config.id, uri)
        return ToolResult(
            name = name,
            success = result.success,
            content = if (result.success) result.content else "MCP error: ${result.error}",
        )
    }
}

// ── mcp_list_prompts ──────────────────────────────────────────────────────────

class McpListPromptsTool(private val project: Project) : LlamatikTool {
    override val name = "mcp_list_prompts"
    override val description = "List all MCP prompts grouped by server."

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val configService = service<McpServerConfigService>()
        val clientService = service<McpClientService>()
        val configs = configService.enabledConfigs()
        if (configs.isEmpty()) return ToolResult(name, true, "No enabled MCP servers.")
        val sb = StringBuilder()
        configs.forEach { config ->
            val prompts = runCatching { clientService.listPrompts(config.id) }.getOrElse { emptyList() }
            sb.appendLine("Server: ${config.name}")
            if (prompts.isEmpty()) {
                sb.appendLine("  (no prompts)")
            } else {
                prompts.forEach { p ->
                    sb.appendLine("  - ${p.name}: ${p.description.take(80)}")
                }
            }
        }
        return ToolResult(name, true, sb.trimEnd().toString())
    }
}
