# Agent & Tools

The Llamatik Code agent is an autonomous local development engine. It takes a natural-language mission, plans steps, calls built-in tools, and applies changes to your project with a safe diff preview — entirely on your machine.

## How the agent works

1. You type an instruction in **Agent mode**.
2. The agent makes a first LLM pass to produce a plan.
3. It executes each step by calling one or more tools.
4. Results are fed back into the loop until the task is complete or the agent halts.
5. File writes are shown as a **diff preview** — you approve before changes land on disk.
6. After apply, the agent optionally triggers a build and interprets errors.

## Built-in tools (1.2.0+)

| Tool | Description |
|---|---|
| `read_file` | Read the content of any project file |
| `write_file` | Write or overwrite a file (always previewed) |
| `list_files` | List files in a directory |
| `search_code` | Full-text search across the project |
| `run_terminal` | Execute a shell command |
| `get_diagnostics` | Fetch current IDE inspection/compiler errors |
| `explain_build_failure` | Interpret the last build failure |
| `run_diagnostics` | Run plugin self-diagnostics |

## Git tools (1.3.0+)

| Tool | Description |
|---|---|
| `git_diff` | Diff of current changes (staged + unstaged) |
| `git_status` | Working-tree status |
| `git_log` | Recent commit history |
| `git_blame` | Line-level blame for a file |
| `git_changed_files` | Files changed in the current diff |

## Project intelligence tools (1.3.0+)

| Tool | Description |
|---|---|
| `index_search_files` | Fast file search by name pattern |
| `index_search_symbols` | Symbol search across the project index |
| `index_list_recent` | Recently opened or modified files |
| `psi_find_usages` | Find all usages of a symbol via PSI |
| `psi_get_type` | Resolve the type of an expression |
| `psi_get_declarations` | List declarations in a file |
| `smart_search` | Fuzzy search ranked by recency and relevance |

## Codebase rules tools (1.3.0+)

| Tool | Description |
|---|---|
| `rules_get` | Read the active codebase rules |
| `rules_set` | Write persistent rules the agent follows every session |
| `rules_clear` | Remove all codebase rules |

Codebase rules are plain text instructions stored per-project. Write them in natural language: "Always use `Result<T>` for error handling", "Never import androidx.compose in non-UI modules", etc.

## Run config tools (1.3.0+)

| Tool | Description |
|---|---|
| `run_config_list` | List available IDE run configurations |
| `run_config_execute` | Trigger a run configuration |

## KMP native tools (1.3.0+)

| Tool | Description |
|---|---|
| `kmp_diagnose` | Detect Kotlin Multiplatform native compilation issues |
| `kmp_fix_plan` | Generate a structured fix plan for KMP native errors |

## Agent trace panel

Every tool call is recorded in the **What I tried** trace sidebar:

- Tool name and arguments
- Duration in milliseconds
- Success / error result
- MCP server name (for MCP tools)

The trace is visible during and after each agent run. Click any entry to expand the full result.

## Enterprise Pair Programmer tools (1.7.0+)

| Tool | Description |
|---|---|
| `enterprise_mission_create` | Classify the request, detect risks, build the mission plan |
| `enterprise_mission_context` | Gather git state, code health, diagnostics, and project snapshot |
| `enterprise_mission_audit` | Return the current audit trail (metadata only — no raw source code) |
| `enterprise_mission_report` | Produce the final mission report and close the mission |

These tools are called automatically when you use mission phrasing (e.g. "fix the build failure", "refactor the auth module") or when you use an action from **Llamatik Code → Autonomous Local Development**.

See [offline-enterprise-pair-programmer.md](offline-enterprise-pair-programmer.md) for a full description of the mission workflow.

## Read-only vs write tools

The agent enforces a distinction between read-only tools (safe to call autonomously) and write tools (require a diff preview or explicit approval in Aggressive mode). The read-only set includes all `git_*`, `index_*`, `psi_*`, `rules_get`, `run_config_list`, `enterprise_mission_*`, and all Code Health and Research tools that only read data.

## Agent loop reliability (1.8.0+)

The multi-round agent loop has several guards to ensure it produces file changes rather than stalling on repeated reads or prose responses:

| Guard | Trigger | Effect |
|---|---|---|
| Attached-file dedup | Model requests `read_file` / `search_code` / `list_files` on a path already in the attached context | Synthetic result returned; round not consumed |
| Cross-round dedup | Model repeats an identical read call it made in a previous round | Synthetic result returned; round not consumed |
| All-duplicate escalation | Every tool call in a round is synthetic (dedup) | Agent skips to a final write-only pass with thinking suppressed |
| Mid-loop prose escalation | Model returns plain text instead of a tool call after having read content | Agent escalates to a final write-only pass |
| Final-round noThink | The last round before round limit | `<think>` suppressed so the full token budget goes to `write_file` output |

The mid-loop prose escalation fires only when `round > 1` (not the initial Q&A turn) and `round < MAX_ROUNDS - 1` (not already the escalated write pass itself), preventing re-entry.
