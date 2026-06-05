# Llamatik Code

**Build, refactor, review, and verify code locally — without sending your source code to the cloud.**

Llamatik Code is a local-first autonomous development environment for IntelliJ-based IDEs. It runs a local GGUF model on your machine and wraps every task in a structured, auditable workflow: gather context → plan → patch → verify → audit.

## Features

### Autonomous Local Development (1.7.0+)

- **Offline Enterprise Pair Programmer** — mission-driven workflow with risk detection, plan approval, diff preview, verification, and audit report
- **Local coding missions** — describe a task in plain language; the agent plans and executes it entirely on your machine
- **Context menu actions** — right-click any file or selection → **Llamatik Code → Autonomous Local Development**
- **Inline completions** — passive FIM-based code suggestions as you type (disabled by default, opt-in)
- **Trust UX** — plan-before-write enforcement, diff gates, audit trail with no raw source code stored

### Core features

- Docked **Llamatik Code** tool window
- Multi-tab project chat sessions with persistence
- Tool-driven agent loop (70+ built-in tools)
- Project context tools: list/read/search/current file/selection
- Safe diff preview and approval for all file writes
- Settings page for model management and inference parameters
- IntelliJ editor/project actions for explain/refactor/fix/document/test/edit flows

### Agent capabilities

- Git awareness (status, diff, changed files, branch, commit suggestions)
- PSI-based symbol search and usage analysis
- Project index search and relevance ranking
- Run configuration detection and last-output analysis
- Static analysis integration
- Dependency inspection and upgrade planning
- MCP server integration (1.4.0+)
- External knowledge: web search, docs sources, error research (1.5.0+)
- Code Health Monitor: background analysis, regression detection, watched areas (1.5.0+)

## Benchmark harness

The project includes a headless benchmark suite that runs the real agent pipeline against self-contained fixture repositories and verifies the result by executing the fixture's own test suite.

**Run all tasks (requires a model):**
```bash
./gradlew runLlamatikBenchmark -PbenchmarkModel=<path-to-model>
```

**Run with the built-in scripted backend (no model needed, for CI smoke-testing):**
```bash
./gradlew runLlamatikBenchmark -PbenchmarkModel=scripted
```

**Run a single task:**
```bash
./gradlew runLlamatikBenchmark -PbenchmarkModel=scripted -PbenchmarkTask=kotlin-simple-fix-001
```

Reports are written to `build/llamatik-benchmark/` (`results.json`, `results.md`, per-task logs).

### Current tasks

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

See [docs/benchmarking.md](docs/benchmarking.md) for the full guide including how to add tasks, report format, and pass/fail criteria.

## Key TODOs

- Replace fallback pseudo-streaming with true Llamatik streaming when available
- Implement cooperative native cancellation in the Llamatik adapter
- Replace model placeholder downloads with real Hugging Face download logic and verification
- Extend safe patch flow to support multi-file patches and automated checks
- Add build/test/git tools and richer multi-agent orchestration
