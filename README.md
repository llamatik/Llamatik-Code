<p align="center">
  <img src="https://plugins.jetbrains.com/files/31304/1043300/icon/default.svg" alt="Llamatik Code Logo" width="120"/>
</p>

<h1 align="center">Llamatik Code</h1>

<p align="center">
  <b>The AI pair programmer built for Kotlin Multiplatform — works with every language and project in your JetBrains IDE.</b>
</p>

<p align="center">
  Kotlin Multiplatform · Local by default · Distributed by choice
</p>

<p align="center">
  <a href="https://plugins.jetbrains.com/plugin/31304"><img src="https://img.shields.io/jetbrains/plugin/v/31304" alt="JetBrains Plugin version"/></a>
  <a href="https://plugins.jetbrains.com/plugin/31304"><img src="https://img.shields.io/jetbrains/plugin/d/31304" alt="JetBrains Plugin downloads"/></a>
  <img src="https://img.shields.io/badge/Focus-Kotlin%20Multiplatform-blueviolet" alt="kotlin multiplatform badge"/>
  <img src="https://img.shields.io/badge/Platform-IntelliJ%20%7C%20Android%20Studio%20%7C%20GoLand%20%7C%20CLion%20%7C%20PyCharm-blue" alt="platforms badge"/>
  <img src="https://img.shields.io/badge/Inference-llama.cpp-orange" alt="llama.cpp badge"/>
  <img src="https://img.shields.io/badge/Models-GGUF-green" alt="gguf badge"/>
  <img src="https://img.shields.io/badge/License-Apache%202.0-lightgrey" alt="license badge"/>
</p>

---

## ✨ What is Llamatik Code?

**Llamatik Code** is an open-source AI coding assistant built specifically for Kotlin Multiplatform development, running entirely on local inference. It wraps the [Llamatik](https://github.com/llamatik/Llamatik) on-device AI SDK and gives you a KMP-aware agent that understands your shared/platform source sets, diagnoses native compilation errors, plans cross-platform refactors, and applies safe diffs — all inside your JetBrains IDE with no cloud dependency.

It works equally well for any JVM, Android, iOS, or general-purpose project — the KMP tooling is additive, not restrictive.

- 🎯 **KMP-native** — dedicated tools for diagnosing and fixing Kotlin Multiplatform native compilation issues (`kmp_diagnose`, `kmp_fix_plan`)
- 🤖 **Agent-driven** — a tool-calling loop with 70+ built-in tools for git, PSI, file I/O, search, build, and more
- 🔒 **Privacy-first** — inference runs locally by default via GGUF models and llama.cpp; you control what connects to the outside world
- 🧩 **MCP-compatible** — connect any Model Context Protocol server and let the agent call its tools
- 🩺 **Code Health Monitor** — background analysis for regressions, complexity, and refactor opportunities
- 🌐 **External Knowledge** — web search, docs research, and error analysis from inside the IDE with smart routing to kotlinlang.org, developer.android.com, and developer.apple.com
- 🚀 **Autonomous Local Development** — mission-driven coding: plan → patch → verify → audit, entirely on-device

---

## 🚀 Features

### 🤖 Autonomous Local Development *(1.8.0+)*
- **Offline Enterprise Pair Programmer** — describe a task; the agent classifies it, detects risks, plans, patches, verifies, and produces an audit report
- **11 auto-detected mission types** — BUG_FIX, REFACTOR, FEATURE_IMPLEMENTATION, TEST_GENERATION, CODE_REVIEW, DOCUMENTATION, BUILD_FAILURE_REPAIR, ARCHITECTURE_EXPLANATION, DEPENDENCY_UPDATE, SECURITY_AUDIT, PERFORMANCE_ANALYSIS
- **Safety levels** — APPROVAL_REQUIRED (default), PLAN_ONLY, AUTO_APPLY, FULL_AUTO
- **Risk detection** — LOW / MEDIUM / HIGH / CRITICAL risks surfaced before any file is written; CRITICAL requires explicit acknowledgement
- **Plan-before-write enforcement** — no file is touched until a mission plan exists (configurable)
- **Diff-before-write enforcement** — unified diff shown before every patch (configurable)
- **Audit trail** — per-mission metadata log; accessible via the `enterprise_mission_audit` tool
- **Inline completions** — local fill-in-the-middle suggestions as you type (opt-in, debounced)
- **Context menu** — right-click any file or selection → **Llamatik Code → Autonomous Local Development**

### 💬 Agent & Chat
- ✅ Docked **Llamatik Code** tool window with **Ask** and **Agent** modes
- ✅ Multi-tab persistent chat sessions with per-project isolation
- ✅ Tool-driven agent loop with up to 5 execution rounds
- ✅ Safe diff preview before every file write
- ✅ Build verification after apply, with automatic error injection
- ✅ Agent trace panel — every tool call, duration, and result
- ✅ `@filename` attachment — pre-seeds files into context, no wasted read rounds
- ✅ **Aggressive mode** (Pro) — decisive agent runs without per-step confirmation
- ✅ **Multi-task agent** (Pro) — parallel tasks with a live task queue panel

### 🛠️ Built-in Tools (70+)

**Core file tools**
| Tool | Description |
|---|---|
| `read_file` | Read the content of any project file |
| `write_file` | Write or overwrite a file (always previewed) |
| `create_file` | Create a new file |
| `replace_in_file` | Targeted in-file replacement |
| `list_files` | List files in a directory |
| `search_code` | Full-text search across the project |
| `search_filenames` | File search by name pattern |
| `run_terminal` | Execute a shell command |
| `get_diagnostics` | Fetch current IDE inspection / compiler errors |
| `explain_build_failure` | Interpret the last build failure |

**Git tools** *(1.3.0+)*
| Tool | Description |
|---|---|
| `git_diff` | Diff of current changes (staged + unstaged) |
| `git_status` | Working-tree status |
| `git_log` | Recent commit history |
| `git_blame` | Line-level blame for a file |
| `git_changed_files` | Files changed in the current diff |

**Project intelligence** *(1.3.0+)*
| Tool | Description |
|---|---|
| `index_search_files` | Fast file search by name pattern |
| `index_search_symbols` | Symbol search across the project index |
| `index_list_recent` | Recently opened or modified files |
| `psi_find_usages` | Find all usages of a symbol via PSI |
| `psi_get_type` | Resolve the type of an expression |
| `psi_get_declarations` | List declarations in a file |
| `smart_search` | Fuzzy search ranked by recency and relevance |

**MCP tools** *(1.4.0+)*
| Tool | Description |
|---|---|
| `mcp_list_servers` | List configured MCP servers |
| `mcp_list_tools` | Discover tools exposed by a server |
| `mcp_call_tool` | Call any MCP tool with approval gating |
| `mcp_list_resources` | List server resources |
| `mcp_read_resource` | Read a resource from an MCP server |
| `mcp_list_prompts` | List server prompt templates |

**Autonomous Local Development tools** *(1.8.0+)*
| Tool | Description |
|---|---|
| `enterprise_mission_create` | Start a structured coding mission |
| `enterprise_mission_context` | Gather project context for a mission |
| `enterprise_mission_audit` | Read the audit log for past missions |
| `enterprise_mission_report` | Produce the final mission report |

### 🔌 MCP Server Support *(1.4.0+)*
- IDE-native MCP host — add any MCP server under **Settings → Llamatik Code → MCP Servers**
- Full server lifecycle: add, edit, remove, enable/disable, test connection, view logs
- Discovered MCP tools bridged as `mcp_<server>_<tool>` first-class agent tools
- Approval dialog with LOW / MEDIUM / HIGH risk level on every call
- HIGH-risk tools are never auto-approved
- Free: 1 active server, manual approval. Pro: unlimited servers + per-tool auto-approval rules
- Secrets redacted from all log and trace output

### 🌐 External Knowledge *(1.5.0+)*
- Web search via DuckDuckGo, documentation pages, GitHub Issues, and more
- Smart source routing to kotlinlang.org, developer.android.com, developer.apple.com, spring.io
- Error research: detects build tool and framework, produces structured fix plans
- Private docs indexing: add up to 10 (Pro) or 3 (Free) custom documentation sources
- Citations with every external research result (source type, confidence, URL)
- Free: 15 searches/day, 5 pages/task. Pro: 50 searches/day, 10 pages/task

### 🩺 Code Health Monitor *(1.5.0+)*
- Background analysis for large files, TODO/FIXME accumulation, empty catch blocks, deprecated APIs
- Regression risk analyser for git diffs: removed public APIs, deleted tests, dependency changes
- Watched areas: pin files, directories, or modules for priority analysis
- Before-commit assistant: summarises staged changes and flags regression risks
- Refactor opportunity detection: long functions, repeated catch patterns
- 14 dedicated agent tools for all Code Health operations
- Free: LIGHT mode, manual trigger only. Pro: BALANCED + AGGRESSIVE modes with background scheduling

### ✏️ Context Actions
Right-click any file or selection for one-shot operations:
- Ask About Selection · Explain This File · Refactor Selection · Fix Selected Code
- Generate Tests · Add Documentation · Edit with Prompt · Explain Failing Build
- Explain Current Git Changes · **Autonomous Local Development** submenu *(1.8.0+)*

---

## 📱 Try it now

Install from the JetBrains Plugin Marketplace — no setup beyond pointing the plugin at a GGUF model file.

<a href="https://plugins.jetbrains.com/plugin/31304"><img src="https://img.shields.io/badge/Install%20from-JetBrains%20Marketplace-orange?style=for-the-badge&logo=jetbrains" alt="Install from JetBrains Marketplace"/></a>

---

## 🔧 Requirements

- **IDE**: IntelliJ IDEA 2026.1 or any JetBrains IDE on platform 252+
  (IntelliJ IDEA, Android Studio, GoLand, CLion, PyCharm, WebStorm, and more)
- **Model**: Any instruction-tuned GGUF model compatible with llama.cpp (3B–34B+ depending on hardware)
- **OS**: macOS, Linux, or Windows (64-bit, JDK 21+)

---

## ⚡ Quick Start

1. **Install** the plugin from the JetBrains Marketplace (search **Llamatik Code**).
2. **Restart** your IDE.
3. Open **Settings → Llamatik Code → Inference** and set the **Model path** to a GGUF file on disk.
4. Open the **Llamatik Code** tool window (right panel) and start chatting.

### Choosing a model

Any GGUF model compatible with llama.cpp works. Recommended starting points:

| Use case | Model size | Quantisation |
|---|---|---|
| Fast / lightweight | 3B – 7B | Q4_K_M or Q5_K_M |
| Balanced | 13B – 14B | Q4_K_M |
| High quality | 34B+ | Q4_K_M (if hardware allows) |

For agent and tool-use tasks, use instruction-tuned variants (names ending in `-Instruct` or `-Chat`).

Models can be downloaded from [Hugging Face](https://huggingface.co/models?library=gguf&sort=trending) — search for GGUF quantisations of Qwen3, Gemma3, Mistral, LLaMA, or Phi.

---

## 🧑‍💻 Usage

### Ask mode vs Agent mode

The tool window has two primary modes:

- **Ask mode** — Conversational Q&A. The model reads context but does not write files.
- **Agent mode** — Task execution. The agent calls tools, edits files, runs builds, and iterates.

### Running a mission (Autonomous Local Development)

```
Type:  "Fix the build failure in AuthModule"
       "Refactor UserRepository without changing behavior"
       "Generate tests for PaymentService"
       "Review current git changes before committing"
```

The agent automatically detects the mission type, surfaces risks, and presents a plan for your approval before writing any files.

Alternatively, right-click any file → **Llamatik Code → Autonomous Local Development** → choose an action.

### Attaching files

Use `@filename` in your prompt to attach a file. The agent pre-seeds it into context — no wasted read rounds.

### Codebase rules

Use the `rules_set` tool (or ask the agent) to write persistent natural-language rules:

```
"Always use Result<T> for error returns. Never throw exceptions in repository classes."
```

Rules are injected into every agent session automatically.

---

## 🔍 Why Llamatik Code?

- ✅ **Built for Kotlin Multiplatform** — dedicated KMP tools, smart routing to KMP docs, awareness of shared/platform source sets
- ✅ Local by default — no cloud, no telemetry, no data exfiltration
- ✅ Built on [Llamatik](https://github.com/llamatik/Llamatik) and llama.cpp
- ✅ 70+ built-in tools including git, PSI, build, and MCP
- ✅ Open-source (Apache 2.0)
- ✅ MCP-compatible — extend the agent with any tool server
- ✅ Structured mission workflow with plan approval and audit trail
- ✅ Works with any GGUF model — no vendor lock-in
- ✅ Works for any language or project in IntelliJ-based IDEs

---

## 📦 Benchmark harness

The project includes a headless benchmark suite that runs the real agent pipeline against self-contained fixture repositories and verifies the result by executing the fixture's own test suite.

**Run all tasks (requires a model):**
```bash
./gradlew runLlamatikBenchmark -PbenchmarkModel=<path-to-model>
```

**Run with the scripted backend (no model needed — for CI smoke-testing):**
```bash
./gradlew runLlamatikBenchmark -PbenchmarkModel=scripted
```

**Run a single task:**
```bash
./gradlew runLlamatikBenchmark -PbenchmarkModel=scripted -PbenchmarkTask=kotlin-simple-fix-001
```

Reports are written to `build/llamatik-benchmark/` (`results.json`, `results.md`, per-task logs).

### Current benchmark tasks

| ID | Name | Tools exercised |
|---|---|---|
| `kotlin-simple-fix-001` | Fix failing Calculator test | `read_file`, `write_file` |
| `kotlin-create-file-002` | Create missing StringUtils file | `read_file`, `create_file` |
| `kotlin-replace-in-file-003` | Fix off-by-one error in Stack | `read_file`, `replace_in_file` |
| `kotlin-search-fix-004` | Find and fix division bug via search | `search_code`, `read_file`, `replace_in_file` |
| `kotlin-multi-file-005` | Fix interface and implementation mismatch | `read_file`, `write_file` (two files) |
| `kotlin-add-function-006` | Add missing clamp function | `search_filenames`, `read_file`, `write_file` |
| `kotlin-list-files-007` | Discover and fix unimplemented method | `list_files`, `replace_in_file` |
| `kotlin-git-aware-008` | Complete uncommitted work using git tools | `git_status`, `git_diff`, `read_file`, `write_file` |
| `real-keval-parser-009` | **[Real project]** Keval: parser silently ignores unbalanced `)` | `list_files`, `read_file`, `replace_in_file` |

See [docs/benchmarking.md](docs/benchmarking.md) for the full guide, including how to add tasks, the report format, and pass/fail criteria.

---

## Free vs Pro

**Every feature is free for individual developers.** There are no feature gates, no trial periods, and no paywalls.

**Pro** exists in two forms:

| | Individual Pro | Organization Pro |
|---|---|---|
| **Who it's for** | Individual developers who want to support the project | Companies and teams using Llamatik Code at work |
| **Required?** | No — purely voluntary | Yes — required for commercial use |
| **What you get** | A way to fund continued development; same features as Free | Same features; funds maintenance, new features, and model updates |
| **How to get it** | [GitHub Sponsors](https://github.com/sponsors/ferranpons) or the **Support ♥** button in the plugin | [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/31304) ($15/month or $150/year per seat) |

Individual developers: everything works out of the box, no account needed.  
Companies: purchasing an organization subscription is the right way to support the project and keeps it a legitimate procurement line item.

---

## 🤝 Contributing

Llamatik Code is open-source and welcomes contributions.

- 🐛 **Bug reports** → [GitHub Issues](https://github.com/Llamatik-AI/Llamatik-Code-Issue-Tracker/issues)
- 💡 **Feature requests** → [GitHub Issues](https://github.com/Llamatik-AI/Llamatik-Code-Issue-Tracker/issues)
- 📖 **Documentation** → [docs.llamatik.com](https://docs.llamatik.com)
- 💬 **Community** → [Discord](https://discord.gg/bNpzawZqq)

### Building from source

```bash
git clone https://github.com/Llamatik-AI/Llamatik-Teammate.git
cd Llamatik-Teammate
./gradlew buildPlugin
```

The built plugin `.zip` is written to `build/distributions/`. Install it via **Settings → Plugins → Install Plugin from Disk**.

---

## 📜 License

The source code is licensed under the **[Apache License 2.0](./LICENSE)**.

The visual assets (logos, icons, and illustrations in `assets/` and `src/main/resources/icons/`) are proprietary — see **[LICENSE-assets](./LICENSE-assets)** for the terms that apply to them.

See [NOTICE](./NOTICE) for third-party attributions.

---

Built with ❤️ for developers who care about privacy.
