# Model Intelligence

Llamatik Code includes several services that reason about the loaded model and the project state to provide better-quality assistance.

## Model capability detection

`ModelCapabilityService` analyses the loaded model's name, size, and metadata to infer its capabilities:

| Capability | Detected from |
|---|---|
| Tool use | Model name patterns (instruct, chat, agent) + context size |
| Long context | Context size ≥ 16 k tokens |
| Code tasks | Model name contains `code`, `starcoder`, `deepseek-coder` |
| Instruction following | Instruction-tuned variants (GGUF metadata) |

The detected capabilities are used to:
- Enable or disable agent tool use for models that don't support it reliably
- Adjust the system prompt for models without strong instruction following
- Display a capability badge in **Settings → Llamatik Code → Inference**

## Model benchmarking

Click **Run benchmark** in **Settings → Llamatik Code → Inference** to measure:

- **Tokens per second** — generation throughput for your hardware + model combination
- **First-token latency** — time to the first output token (important for interactive use)
- **Context fill speed** — tokens processed per second when loading a long prompt

Results are stored per model path and displayed in the settings page. Use them to compare models and choose the right size for your machine.

## Smart search

`SmartSearchService` provides a fuzzy, relevance-ranked search that the agent uses internally and that you can invoke directly via the `smart_search` tool:

```
smart_search query="authentication middleware"
```

The search ranks results by three signals:
1. **Recency** — files you edited or opened recently rank higher.
2. **Query overlap** — token-level match between the query and file path + symbol names.
3. **File type priority** — source files (`.kt`, `.java`, `.swift`, `.py`) rank above generated, resource, and config files.

## Project snapshots

Before every agent run, `ProjectSnapshotService` captures:
- All currently open files and their modification timestamps
- The current git branch and last commit hash
- The set of recently edited files and the agent's recent context

Snapshots are lightweight (metadata only, no file content) and are used by `ContextRecoveryService` to detect when the project state has drifted significantly between sessions.

## Context recovery

If the agent's remembered context no longer matches the actual project state (e.g., files were renamed, branches switched, or significant time has passed), `ContextRecoveryService` detects the drift and offers a recovery prompt:

- **Refresh context** — re-reads the current file set and updates the agent's context.
- **Start fresh** — clears the session context and begins from the current project state.
- **Continue anyway** — keeps the existing context (use if you know the state is valid).
