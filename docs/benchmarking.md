# Llamatik Code Benchmark Guide

The benchmark harness runs the real Llamatik Code agent pipeline against
self-contained fixture repositories, executes the project's own test suite
after the agent finishes, and writes reproducible reports.

This is a developer/internal tool. It is never exposed to plugin users.

---

## Directory layout

```
benchmarks/
  shared/
    build.gradle.kts      ← injected into every task at runtime (shared, not duplicated)
    settings.gradle.kts   ← injected into every task at runtime
  tasks/
    <task-id>/
      repo/               ← source files only (no build files or wrapper)
      prompt.md           ← natural-language instruction for the agent
      test-command.txt    ← verification command, e.g. ./gradlew test
      metadata.json       ← id, name, language, timeoutMinutes, maxSteps, expectedFiles
      setup-command.txt   ← (optional) shell command run once before the agent starts
```

The Gradle wrapper (`gradlew`, `gradle/wrapper/`) and the shared build files
(`build.gradle.kts`, `settings.gradle.kts`) are injected from the project root
and `benchmarks/shared/` respectively into each task's temp directory at runtime.
Task `repo/` directories contain **source files only**.

A task can override the shared build file by placing its own `build.gradle.kts`
directly in `repo/` — the shared copy is only injected when the file is absent.

---

## Current tasks

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

Together these tasks cover all 11 tools available to the headless agent:
`list_files`, `read_file`, `search_code`, `search_filenames`, `write_file`,
`create_file`, `replace_in_file`, `git_status`, `git_diff`, `git_changed_files`,
`git_branch`.

---

## How to run

**All tasks (requires a local model):**
```bash
./gradlew runLlamatikBenchmark -PbenchmarkModel=/path/to/model.gguf
```

**Scripted backend — no model needed, useful for CI:**
```bash
./gradlew runLlamatikBenchmark -PbenchmarkModel=scripted
```

**Single task:**
```bash
./gradlew runLlamatikBenchmark -PbenchmarkModel=scripted -PbenchmarkTask=kotlin-simple-fix-001
```

**Custom task directory:**
```bash
./gradlew runLlamatikBenchmark -PbenchmarkModel=scripted -PbenchmarkDir=path/to/my/tasks
```

If `-PbenchmarkModel` is omitted the test is skipped cleanly, so CI without a
model will not fail.

---

## Where reports are stored

All output is written to `build/llamatik-benchmark/`:

| File | Contents |
|---|---|
| `results.json` | Machine-readable full result for all tasks |
| `results.md` | Human-readable summary table |
| `logs/<task-id>.log` | Combined agent output + verification output |
| `logs/<task-id>-stdout.txt` | Verification stdout only |
| `logs/<task-id>-stderr.txt` | Verification stderr only |
| `junit/` | JUnit XML (for CI ingestion) |
| `html/` | HTML test report |

---

## How pass/fail is calculated

A task **passes** if both conditions hold:

1. The agent completed without a fatal error or timeout.
2. The verification command (`test-command.txt`) exits with code 0.

A task **fails** if any of the following occur:

| Status | Cause |
|---|---|
| `AGENT_ERROR` | Agent threw an unhandled exception |
| `TIMEOUT` | Agent exceeded `timeoutMinutes` |
| `VERIFICATION_FAILED` | Verification command exited non-zero |
| `SETUP_ERROR` | Task directory is invalid, repo copy failed, or `setup-command.txt` exited non-zero |

The overall Gradle task fails if any individual task fails, so CI will flag
regressions automatically.

---

## Isolation

Before each run the fixture `repo/` directory is **copied to a temporary
directory**. The Gradle wrapper and shared build files are then injected into
that copy. The agent operates entirely on the copy. The original fixture is
never modified. The temp directory is deleted when the run finishes.

---

## How to add a benchmark task

1. Create a folder under `benchmarks/tasks/<your-task-id>/`.
2. Add source files to `repo/`. No `build.gradle.kts`, `settings.gradle.kts`,
   or `gradlew` needed — those are injected automatically. If the task needs a
   non-standard build configuration, place a `build.gradle.kts` in `repo/` and
   it will take precedence over the shared one.
3. Write `prompt.md` — a short description of what the agent should do.
4. Write `test-command.txt` — a single shell command that exits 0 on success.
5. Add `metadata.json`:

```json
{
  "id": "my-task-001",
  "name": "Human-readable name",
  "language": "kotlin",
  "timeoutMinutes": 15,
  "maxSteps": 30,
  "expectedFiles": ["src/main/kotlin/Foo.kt"]
}
```

6. Optionally add `setup-command.txt` — a shell command run once in the temp
   dir after the repo is copied and before the agent starts. Useful for tasks
   that require a pre-existing git history (e.g. `git init && git add . && git commit -m "init"`).
   If the setup command exits non-zero, the task is marked `SETUP_ERROR` and
   the agent is not run.

7. Add a scripted response entry in `LlamatikBenchmarkTest.ScriptedDispatchBackend`
   so the task can be verified end-to-end with `-PbenchmarkModel=scripted`.

---

## Citing results

When publishing benchmark numbers:

- State the plugin version (`pluginVersion` in `results.json`).
- State the model label (`model` in `results.json`).
- State the hardware (CPU model, RAM, OS).
- State the number of tasks and the task IDs used.
- Include the full `results.json` or a link to it.
- Note that results are model-dependent: a different model will produce
  different pass rates on the same task set.
- Do not cherry-pick tasks. Either run the full suite or explicitly name
  the subset used.
