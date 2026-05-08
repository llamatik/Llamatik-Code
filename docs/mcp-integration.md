# MCP Integration — Llamatik Code

## Overview

Llamatik Code can act as an **IDE-native MCP host**, letting users configure local MCP (Model Context Protocol) servers, discover their tools/resources/prompts, and expose them to the agent automatically.

---

## How to configure an MCP server

1. Open **Settings → Llamatik Code → MCP Servers**.
2. Click **Add server**.
3. Fill in:
   - **Name** — a unique display name (e.g. `filesystem`, `github`).
   - **Command** — the executable to run (e.g. `npx`, `python3`, `/usr/local/bin/my-mcp`).
   - **Arguments** — one argument per line (e.g. `-y` / `@modelcontextprotocol/server-filesystem` / `/home/user`).
   - **Environment variables** — one `KEY=value` per line. Values are stored in `llamatik_code_settings.xml`; use IntelliJ PasswordSafe for sensitive secrets.
   - **Working directory** — optional; defaults to the IDE working directory.
   - **Enabled** — whether this server counts as an active server.
   - **Auto-start** — whether to start the server automatically when the project opens.
   - **Trust level** — controls approval behaviour (see below).
4. Click **OK** to save and auto-start (if enabled).

Use **Test connection** to verify the server starts correctly and list its discovered capabilities.

---

## Free vs Pro behaviour

| Feature | Free | Pro |
|---|---|---|
| Active MCP servers | 1 | Unlimited |
| Tool call approval | Always required (Allow once) | Optional per server/tool |
| Auto-approval rules | Not available | Per server or per tool |
| MCP tools in autonomous agent runs | Supported (with approval) | Supported |
| MCP tool chaining | Supported (one server) | Supported (multiple servers) |

**Enforcement:** When a Free user tries to enable a second server, `McpServerConfigService.saveConfig()` returns an error. The UI shows "Only one active MCP server is allowed on the Free plan."

---

## How MCP tools are bridged into the agent

When a server starts:
1. `McpClientService` runs the server process, initialises the JSON-RPC session, and calls `tools/list`.
2. `McpToolBridgeService.registerTools()` wraps each tool as a `McpLlamatikToolAdapter` implementing `LlamatikTool`.
3. `ToolRegistry.refreshMcpTools()` adds them to the dynamic tool map.
4. `AgentOrchestrator` allows dynamically registered tool names in its tool-call filter.

Bridged tool names use the prefix `mcp_<serverName>_<toolName>` (e.g. `mcp_filesystem_read_file`). Server names are normalised to lowercase snake_case.

Six built-in agent tools always exist:
- `mcp_list_servers` — list configured servers and their status.
- `mcp_list_tools` — list all discovered tools grouped by server.
- `mcp_call_tool` — call a tool by server name + tool name.
- `mcp_list_resources` — list resources.
- `mcp_read_resource` — read a resource by URI.
- `mcp_list_prompts` — list prompts.

---

## Approval and auto-approval rules

### Free tier
Every MCP tool call returns `MCP_APPROVAL_REQUIRED:...` until the user clicks **Allow once** in the approval dialog. No persistent rules can be stored.

### Pro tier
Approval is determined by `McpPermissionService.requiresApproval()`:
1. If the tool's risk level is HIGH and it has not been **explicitly** auto-approved, it always requires approval.
2. Otherwise, if auto-approval is configured for the tool or server (via `trustLevel` in the config or a per-tool entry in `mcpAutoApprovedTools`), no dialog appears.

The approval dialog (`McpApprovalDialog`) offers:
- **Allow once** — execute this call, no rule stored.
- **Deny** — block this call.
- **Allow always for this tool** (Pro) — stores `serverId::toolName` in `mcpAutoApprovedTools`.
- **Allow always for this server** (Pro) — sets server `trustLevel` to `TRUST_SERVER`.

---

## Risk classification

`McpPermissionService.classifyRisk(toolName, description)` assigns a risk level based on keyword heuristics:

- **HIGH**: write, delete, execute, deploy, publish, send, create, push, run, shell, http, fetch, …
- **LOW**: read, get, list, find, search, query, describe, status, …
- **MEDIUM**: anything else.

HIGH-risk tools require an explicit opt-in even on Pro. The level is computed when the tool is discovered and stored in `McpToolDescriptor.riskLevel`.

---

## Security and privacy considerations

- MCP servers run as **local child processes** but may access external services (APIs, filesystems, network endpoints). Users are informed of this in the add/edit dialog.
- **Secrets in env vars** are stored in `llamatik_code_settings.xml` (plain XML). For production deployments, move sensitive values to IntelliJ PasswordSafe and reference them by key.
- **Log/trace redaction**: `McpClientService.redactSecrets()` strips `Authorization`, `Bearer`, `token`, `password`, `secret`, `apikey`, and `api_key` values from all log lines before they are stored or displayed.
- Tool arguments are never logged in full — only the first 200 chars of each JSON-RPC message are retained in the in-memory log ring buffer (200 lines max per server).

---

## Troubleshooting broken MCP servers

1. Open **Settings → Llamatik Code → MCP Servers**, select the server, and click **View logs**.
2. Look for `[stderr]` lines — these are the server's own error output.
3. Click **Test connection** to run a fresh start/init/list cycle.
4. If the server is in `FAILED` state, fix the command/args/env and save — the plugin will restart it.
5. The plugin marks a server `FAILED` and continues normally if the process crashes. No other server or agent feature is affected.

Common issues:
- **`command not found`** — the executable is not on `PATH`. Use the full path in **Command**.
- **Timeout waiting for MCP response** — the server took more than 30 s to respond. Check the server's own logs.
- **MCP initialize error** — the server rejected the protocol version. Update the server or report the issue.
- **Free tier limit** — "Only one active MCP server is allowed on the Free plan." Disable all but one server or upgrade to Pro.
