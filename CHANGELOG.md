# Changelog

## [1.7.0] - 2026-05-27

### Added

#### Autonomous Local Development
- **Offline Enterprise Pair Programmer** — new flagship mode that wraps every agent task in a structured, auditable workflow: gather context → classify risks → plan → patch → verify → audit report
- **Mission classification** — the agent automatically classifies requests into 11 mission types (CODE_REVIEW, REFACTOR, BUG_FIX, FEATURE_IMPLEMENTATION, TEST_GENERATION, DOCUMENTATION, BUILD_FAILURE_REPAIR, ARCHITECTURE_EXPLANATION, DEPENDENCY_UPDATE, SECURITY_AUDIT, PERFORMANCE_ANALYSIS)
- **Safety levels** — configurable per-project: APPROVAL_REQUIRED (default), PLAN_ONLY, AUTO_APPLY, FULL_AUTO
- **Risk detection** — four risk levels (LOW / MEDIUM / HIGH / CRITICAL) surfaced in the mission plan before any file is written
- **Plan-before-write enforcement** — agent must produce a mission plan before calling any write tool (configurable, default on)
- **Diff-before-write enforcement** — unified diff shown before every patch is applied (configurable, default on)
- **Audit trail** — per-mission metadata log (no raw source code stored), accessible via `enterprise_mission_audit`
- **Rollback instructions** — every mission plan includes plain-language rollback steps in the audit report

#### Enterprise tools (4 new)
- `enterprise_mission_create` — classify request, detect risks, build the mission plan
- `enterprise_mission_context` — gather git state, code health, diagnostics, and project snapshot
- `enterprise_mission_audit` — return audit trail metadata (never raw source code)
- `enterprise_mission_report` — produce final report and close the mission

#### Context menu actions (7 new)
All under **Llamatik Code → Autonomous Local Development** in editor and project view right-click menus:
- Enterprise Pair Programmer
- Review Current Changes
- Fix Build Failure
- Generate Tests
- Explain Architecture
- Safe Refactor
- Implement Feature

#### Inline completions (MVP)
- Local FIM (fill-in-the-middle) completions using the same model as the agent
- Debounced requests (default 600 ms) — stale requests cancelled automatically
- Current-file only, configurable context window (default 1200 chars)
- Disabled by default — enable at **Settings → Llamatik Code → Autonomous Local Development → Enable inline completions**

#### Settings
- New **Autonomous Local Development** settings page (child of Llamatik Code settings)
- 7 enterprise settings + 4 inline completion settings (see [settings-reference.md](Documentation/settings-reference.md))

### Changed
- Agent identity updated: now introduces itself as "an autonomous local development agent that plans, patches, reviews, and verifies code locally"
- Welcome screen copy updated to mission-based language ("Start a local coding mission", "Describe a mission…")
- Onboarding updated with "I just want it to work" fast path and hardware presets
- External knowledge remains disabled during enterprise missions by default (new setting to opt in)
- Free tier daily search limit raised from 5 to 15; docs sources raised from 1 to 3

## [1.6.3] - 2026-05-15

### Fixed
- **"Prompt too long" error with Batch Size 8192** — reviewing or attaching large files (e.g. via `@{path}`) produced a _Prompt too long for current Batch Size (8192 tokens)_ error even though the batch size was large enough. The file content budget was calculated by reserving only 2 000 characters for non-file overhead, when the actual overhead (system prompt, conversation history, user instruction, and wrappers) is ~9 000 characters. The budget now accounts for all fixed prompt sections so file content is correctly capped at the space that actually remains within the batch limit.

## [1.6.1] - 2026-05-13

### Fixed
- **File read through `build/` directories** — explicitly referenced files (e.g. via `@path`) inside `build/`, `generated/`, or other ignored directories were incorrectly blocked with "Access denied". Explicit `read_file` calls now bypass the directory ignore list while still blocking secret files (`.env`, `*.keystore`, etc.).
- **Absolute path references** — using `@` to attach a file with an absolute path (e.g. `/Users/.../build/...`) caused a silent "File not found" error because the path was resolved relative to the project root. Absolute paths are now routed through `LocalFileSystem` directly.
- **Text selection lost on focus change** — selecting text in a chat or agent message bubble and then clicking elsewhere caused the selection highlight to disappear, making copy/paste unreliable. The selection is now kept visible regardless of focus.

### Added
- **Right-click context menu on message bubbles** — right-clicking any message in the Chat panel shows a popup with _Copy_ (selection), _Copy All_, and _Select All_. Works on both user and assistant messages.
- **Right-click context menu in Agent panel** — same context menu is available on the response text, reasoning (think) block, agent trace, and build error areas.

## [1.0.0] - 2026-04-20

### Added
- Local-first AI coding assistant powered by llama.cpp (runs fully offline)
- Multi-tab persistent chat sessions with project-aware context
- AI agent loop for code editing with safe diff previews
- Editor actions: Ask About Selection, Explain File, Refactor Selection, Fix Selected Code, Generate Tests, Add Documentation, Edit with Prompt
- Model Library: browse and download quantized GGUF models
- Multimodal support for vision-language models (mmproj)
- Free tier for everyday chat assistance
- Pro tier: extended context, advanced inference settings, multi-file agent workflows, auto-approve/build/fix agent flags
- 30-day free trial for Pro features
- Settings pages for model management and inference parameters (temperature, threads, context size, batch size, flash attention)
- Skill library for reusable prompt templates
- Onboarding flow for first-time setup
