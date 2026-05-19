# Llamatik Code Benchmark Guide

The benchmark harness runs the real Llamatik Code agent pipeline against
self-contained fixture repositories, executes the project's own test suite
after the agent finishes, and writes reproducible reports.

This is a developer/internal tool. It is never exposed to plugin users.

---

## Directory layout

```
benchmarks/
  tasks/
    <task-id>/
      repo/             ← self-contained project fixture
      prompt.md         ← natural-language instruction for the agent
      test-command.txt  ← verification command, e.g. ./gradlew test
      metadata.json     ← optional: id, name, language, timeoutMinutes, maxSteps
```

---

## How to add a benchmark task

1. Create a folder under `benchmarks/tasks/<your-task-id>/`.
2. Put a minimal self-contained project in `repo/`. It must have a working
   build file and at least one failing test that the agent can fix.
3. Write `prompt.md` — one or a few sentences describing what the agent should do.
4. Write `test-command.txt` — a single shell command (e.g. `./gradlew test`) that
   exits 0 on success and non-zero on failure.
5. Optionally add `metadata.json`:

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

If `metadata.json` is absent, defaults are applied: `timeoutMinutes=15`,
`maxSteps=30`, language inferred as `unknown`.

---

## How to run all benchmarks

```bash
./gradlew runLlamatikBenchmark
```

Requires a model to be loaded (the test is skipped automatically if no model
is available, so CI without a model will not fail).

---

## How to run a single task

```bash
./gradlew runLlamatikBenchmark -PbenchmarkTask=kotlin-simple-fix-001
```

---

## Custom task directory

```bash
./gradlew runLlamatikBenchmark -PbenchmarkDir=path/to/my/tasks
```

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

1. The agent completed without an internal fatal error or timeout.
2. The verification command (`test-command.txt`) exits with code 0.

A task **fails** if any of the following occur:

- Agent throws an unhandled exception → `AGENT_ERROR`
- Agent exceeds `timeoutMinutes` → `TIMEOUT`
- Verification command exits non-zero → `VERIFICATION_FAILED`
- Task directory is invalid or repo cannot be copied → `SETUP_ERROR`

The overall benchmark fails the Gradle task if any individual task fails,
so CI will flag regressions automatically.

---

## Isolation

Before each run the fixture `repo/` directory is **copied to a temporary
directory**. The agent operates on the copy. The original fixture is never
modified. The temp directory is deleted when the run finishes.

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
