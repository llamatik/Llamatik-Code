# Git Awareness & Project Intelligence

Version 1.3.0 added deep project intelligence: git context, semantic code insight via PSI, project index search, codebase rules, and run configuration control — all available as first-class agent tools.

## Git context

The agent can read the full git state of your project:

- **`git_diff`** — staged and unstaged changes in unified diff format.
- **`git_status`** — working-tree status (modified, added, deleted, untracked).
- **`git_log`** — recent commit history with author, date, and message.
- **`git_blame`** — line-level authorship for any file, useful for understanding change history.
- **`git_changed_files`** — list of files touched in the current diff.

These tools are read-only. The agent never commits or pushes on your behalf.

## Project index

A persistent, fast-path index is built incrementally as you work:

- **`index_search_files`** — search by file name pattern (glob or substring). Returns matching paths with last-modified timestamps.
- **`index_search_symbols`** — search function names, class names, and constants across the whole project.
- **`index_list_recent`** — the most recently opened or modified files, ranked by recency.

The index runs in the background and does not block the editor.

## PSI code insight

The agent can call IntelliJ's Program Structure Interface for semantic queries:

- **`psi_find_usages`** — find every call site, reference, or import of a named symbol.
- **`psi_get_type`** — resolve the inferred type of any expression at a given position.
- **`psi_get_declarations`** — list all top-level and member declarations in a file (functions, classes, properties).

PSI queries are read-only and run on a pooled thread; they never block the EDT.

## Codebase rules

Write persistent natural-language rules that the agent follows in every session:

```
rules_set content="Always use Result<T> for error returns. Never throw exceptions in repository classes."
```

Rules are stored per-project and injected into the agent's system prompt at the start of each run. They are the right place to encode architectural decisions, style preferences, and constraints you don't want to repeat in every prompt.

| Tool | Description |
|---|---|
| `rules_get` | Read the current ruleset |
| `rules_set` | Replace the ruleset with new content |
| `rules_clear` | Delete all rules |

## Run configuration control

The agent can list and trigger IDE run configurations:

- **`run_config_list`** — returns all run configs with name, type, and last-run status.
- **`run_config_execute`** — triggers a named configuration. The agent waits for completion and reads the build output.

This is how the agent verifies its own changes: after writing files it runs the relevant test configuration and inspects failures.

## Smart search

**`smart_search`** provides a fuzzy, ranked search across files and symbols. Unlike `index_search_files` (which matches by path), smart search ranks results by:

1. Edit recency (recently modified files score higher)
2. Query relevance (token overlap)
3. File type priority (`.kt` and `.java` before generated or resource files)

## KMP native diagnostics

For Kotlin Multiplatform projects:

- **`kmp_diagnose`** — analyses the build output for native compilation errors, linker issues, and missing framework references. Returns a structured report with error category and affected targets.
- **`kmp_fix_plan`** — takes the diagnostic report and generates a step-by-step fix plan with the specific Gradle configuration changes or source edits needed.
