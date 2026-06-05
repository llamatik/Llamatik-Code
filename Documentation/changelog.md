# Changelog

## 1.7.0 (2026-05-27)

### Autonomous Local Development

Llamatik Code is now a mission-driven autonomous development environment. Your source code never leaves your machine.

**Offline Enterprise Pair Programmer**
- Structured mission workflow: gather context → classify risks → plan → patch → verify → audit report
- 11 mission types auto-detected from plain-language requests
- Four safety levels: APPROVAL_REQUIRED (default), PLAN_ONLY, AUTO_APPLY, FULL_AUTO
- Risk detection (LOW / MEDIUM / HIGH / CRITICAL) surfaced in the plan before any file is written
- Plan-before-write and diff-before-write enforcement (both on by default)
- Audit trail per mission — metadata only, no raw source code stored
- Rollback instructions included in every final report
- 4 new agent tools: `enterprise_mission_create`, `enterprise_mission_context`, `enterprise_mission_audit`, `enterprise_mission_report`

**Context menu actions (7 new)**
Right-click any file or selection → **Llamatik Code → Autonomous Local Development**:
Enterprise Pair Programmer, Review Current Changes, Fix Build Failure, Generate Tests, Explain Architecture, Safe Refactor, Implement Feature

**Inline completions (opt-in MVP)**
- Local FIM completions using the same on-device model as the agent
- Debounced (600 ms default), stale requests auto-cancelled
- Current-file only; configurable context window (default 1200 chars)
- Disabled by default — enable at **Settings → Llamatik Code → Autonomous Local Development**

**New settings page**
Settings → Llamatik Code → Autonomous Local Development (enterprise + inline completion settings)

**Other changes**
- Welcome screen and onboarding updated with mission-based language and "I just want it to work" fast path
- External knowledge disabled by default during enterprise missions (new opt-in setting)
- Free tier: daily search limit raised from 5 → 15; docs sources raised from 1 → 3

---

## 1.5.0 (2026-05-08)

### Code Health Monitor
- New opt-in background analysis layer that monitors code quality without blocking the editor.
- Static checks (no model): large files, TODO/FIXME accumulation, empty catch blocks, deprecated API usage.
- Regression risk analyser scans git diffs for removed public APIs, deleted tests, dependency changes, removed error handling, and AndroidManifest modifications.
- Before-commit assistant reviews all staged changes and suggests a commit message.
- Watched areas: pin files, directories, packages, or modules for priority analysis.
- Refactor opportunity detection: long functions and repeated catch patterns.
- Session context tracking: recent files, branch, and build failures.
- 14 new agent tools for all Code Health operations.
- New settings page: **Settings → Llamatik Code → Code Health Monitor**.
- Free: LIGHT mode (5 files, 8 s, manual trigger). Pro: BALANCED and AGGRESSIVE modes with background scheduling.

### Context-Aware External Knowledge
- Agent can search the web via DuckDuckGo, read documentation pages, and research errors.
- Research orchestrator detects intent (library docs, bug, API, migration, version) and plans multi-step searches.
- Smart source routing directs queries to the most relevant source (kotlinlang.org, developer.android.com, github.com, etc.).
- Error research analyses compiler errors, detects build tool and framework, and produces a structured fix plan.
- Private docs indexing: add up to 10 (Pro) or 1 (Free) custom documentation sources.
- Citations included with every external research result.
- 16 new agent tools for all External Knowledge operations.
- New editor action: **Search Docs & Web** in the right-click context menu.
- New settings page: **Settings → Llamatik Code → External Knowledge**.
- Free: 5 searches/day, 3 pages/task. Pro: 50 searches/day, 10 pages/task.

---

## 1.4.0 (2026-05-07)

### MCP Server Support
- Llamatik Code is now an IDE-native MCP host.
- New settings page: **Settings → Llamatik Code → MCP Servers** with full server lifecycle management.
- Per-server configuration: command, arguments, environment variables, working directory, auto-start, trust level.
- Test connection, Refresh tools, and View logs actions in the server list.
- 6 new built-in agent tools: `mcp_list_servers`, `mcp_list_tools`, `mcp_call_tool`, `mcp_list_resources`, `mcp_read_resource`, `mcp_list_prompts`.
- Discovered MCP tools bridged as first-class agent tools under `mcp_<server>_<tool>` names.
- Approval dialog for every MCP tool call with risk level (LOW / MEDIUM / HIGH).
- HIGH-risk tools are never auto-approved.
- Pro: unlimited servers, per-tool and per-server auto-approval rules.
- Free: 1 active server, manual approval on every call.
- Secrets redacted from all log and trace output.

---

## 1.3.0 (2026-05-06)

### Agent Tools
- Git tools: `git_diff`, `git_status`, `git_log`, `git_blame`, `git_changed_files`.
- Project index tools: `index_search_files`, `index_search_symbols`, `index_list_recent`.
- PSI tools: `psi_find_usages`, `psi_get_type`, `psi_get_declarations`.
- Codebase rules tools: `rules_get`, `rules_set`, `rules_clear`.
- Run config tools: `run_config_list`, `run_config_execute`.
- KMP native tools: `kmp_diagnose`, `kmp_fix_plan`.

### Aggressive Mode (Pro)
- New `AGGRESSIVE` execution mode for decisive, low-interruption agent runs.
- Configurable fallback policy: ABORT, RETRY, or ESCALATE.

### Model Intelligence
- `ModelCapabilityService`: detects tool use, long context, code, and instruction-following capability.
- `ModelBenchmarkService`: measures tokens/second and first-token latency.
- `SmartSearchService`: fuzzy, ranked file and symbol search.

### Snapshots & Context Recovery
- `ProjectSnapshotService`: lightweight project state snapshots before agent runs.
- `ContextRecoveryService`: detects context drift and offers recovery options.

---

## 1.2.0 (2026-05-04)

### Core agent
- Tool-driven agent loop with two LLM passes (plan + execute).
- Safe diff preview before any file write.
- Build verification after apply.
- Agent trace panel showing all tool calls with duration and result.

### Multi-task agent (Pro)
- Parallel task execution with a live task queue panel.
- Task cancellation and status tracking.

### Chat sessions
- Persistent multi-session chat store.
- Chat session list panel.
- Parallel Ask-mode conversations.

### Context actions
- Editor and project view right-click actions: Ask About Selection, Explain This File, Refactor Selection, Fix Selected Code, Generate Tests, Add Documentation, Edit with Prompt, Explain Failing Build, Run Diagnostics, Explain Current Git Changes.

### Infrastructure
- `LlamatikBackendService`: LlamaBridge adapter with pseudo-streaming.
- `DownloadedModelService`: tracks locally downloaded model files.
- `DiffPreviewService`: IntelliJ DiffManager integration.
- `BuildVerificationService`: post-apply build trigger and failure capture.
- `ProjectMemoryService`: project-scoped persistent memory for the agent.
