# MCP Servers

Llamatik Code is an IDE-native MCP (Model Context Protocol) host. You can connect any MCP-compatible tool server and let the agent call its tools through the same pipeline as built-in tools.

## What is MCP?

MCP is an open protocol for connecting AI assistants to external tool servers. An MCP server is a local process that exposes a set of tools, resources, and prompts. The host (Llamatik Code) discovers them at connection time and makes them available to the agent.

## Adding a server

1. Open **Settings → Llamatik Code → MCP Servers**.
2. Click **Add server**.
3. Fill in:
   - **Name** — a label for the server (e.g., `github`, `postgres`, `filesystem`)
   - **Command** — the executable to run (e.g., `npx`, `uvx`, `python`)
   - **Arguments** — one per line (e.g., `-y @modelcontextprotocol/server-github`)
   - **Environment variables** — `KEY=VALUE` pairs for secrets or config
   - **Working directory** — optional; defaults to the project root
   - **Auto-start** — start the server automatically when the project opens
   - **Trust level** — LOW, MEDIUM, or HIGH (affects the default approval behaviour)
4. Click **Test connection** to verify the server starts and lists its tools.

## Managing servers

- **Enable / disable** — toggle a server without removing its configuration.
- **Refresh tools** — re-discovers tools from a running server. Use this after updating the server.
- **View logs** — shows the last 1000 lines of stderr from the server process. Useful for debugging.
- **Edit** — modify any field. Changes take effect on the next connection.
- **Remove** — stops the server and removes the configuration.

## How MCP tools appear in the agent

Discovered MCP tools are bridged into the agent as first-class tools under the name `mcp_<serverName>_<toolName>`. They appear in the agent trace like built-in tools.

You can also call them directly using the six built-in MCP agent tools:

| Tool | Description |
|---|---|
| `mcp_list_servers` | List all configured servers and their connection status |
| `mcp_list_tools` | List tools exposed by a specific server |
| `mcp_call_tool` | Call a tool on a specific server with arguments |
| `mcp_list_resources` | List resources exposed by a server |
| `mcp_read_resource` | Read a named resource from a server |
| `mcp_list_prompts` | List prompt templates exposed by a server |

## Approval flow

Every MCP tool call triggers an approval dialog showing:
- Server name and tool name
- Full argument list
- Risk level: LOW / MEDIUM / HIGH (inferred from the tool name and description)

**HIGH-risk tools** — those whose names or descriptions contain words like `write`, `delete`, `execute`, `deploy`, `send`, `push` — are never auto-approved.

### Free tier
Each call shows **Allow once** or **Deny**. There are no persistent approval rules.

### Pro tier
Additional options:
- **Allow always for this tool** — never prompt for this specific tool again.
- **Allow always for this server** — auto-approve all non-HIGH-risk tools from this server.

Auto-approval rules are stored per-project in the workspace state and can be reviewed and cleared in **Settings → Llamatik Code → MCP Servers**.

## Security

- MCP servers run as child processes of the IDE. They are isolated from each other.
- The plugin redacts secrets from all log output: API keys, tokens, passwords, and `Authorization` header values are replaced with `[REDACTED]` before display or storage.
- A crashed or failed server is marked **FAILED** and excluded from future tool calls until restarted. It does not affect any other feature.
- The UI displays a warning when a server's description indicates it may call external APIs or access the network.

## Free vs Pro

| | Free | Pro |
|---|---|---|
| Active servers | 1 | Unlimited |
| Auto-approval rules | — | Per-tool + per-server |
| Autonomous multi-step use | — | Yes |
