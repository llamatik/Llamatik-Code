# Llamatik Code

Llamatik Code is a local-first IntelliJ coding assistant for the Llamatik Teammate project.

## Implemented MVP

- Docked **Llamatik Code** tool window
- Multi-tab project chat sessions with persistence
- Llamatik backend abstraction with local model selection
- Tool-driven agent loop with tool-call parsing
- Project context tools: list/read/search/current file/selection
- Safe one-file edit proposals with diff preview and approval
- Settings page for local model path, model presets, safety limits, and project rules
- Stub-ready model download registry for future Hugging Face integration
- IntelliJ editor/project actions for explain/refactor/fix/document/test/edit flows

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

See [docs/benchmarking.md](docs/benchmarking.md) for the full guide including how to add tasks, report format, and pass/fail criteria.

## Key TODOs

- Replace fallback pseudo-streaming with true Llamatik streaming when available
- Implement cooperative native cancellation in the Llamatik adapter
- Replace model placeholder downloads with real Hugging Face download logic and verification
- Extend safe patch flow to support multi-file patches and automated checks
- Add build/test/git tools and richer multi-agent orchestration
