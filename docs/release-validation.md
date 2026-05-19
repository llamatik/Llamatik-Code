# Llamatik Code Release Validation Guide

The release validation suite is a developer-facing pre-Marketplace gate.
It verifies that the plugin ships with:
- all expected built-in tools still registered and correctly named,
- tool implementations behaving according to their contracts, and
- the agent workflow pipeline completing correctly end-to-end.

No real model is required. No internet access is required.

---

## Quick start

**Full gate:**
```bash
./gradlew releaseCheck
```

**Individual tasks:**
```bash
./gradlew toolContractTest        # tool inventory + contract cases
./gradlew agentWorkflowTest       # headless agent workflow scenarios
./gradlew releaseValidationReport # aggregate report only (reads existing JUnit XML)
```

**With optional model smoke tests:**
```bash
./gradlew releaseCheck -PincludeModelSmoke=true
```

---

## Directory layout

```
src/test/kotlin/com/llamatik/teammate/release/
  ToolInventoryTest.kt        ← asserts all ~90 tool names are present in source
  ToolContractCase.kt         ← data model for a contract case
  ToolContractLoader.kt       ← reads JSON files from tool-cases/
  ToolContractRunner.kt       ← executes contract cases against fixture projects
  ToolContractTest.kt         ← JUnit entry point for toolContractTest
  AgentScript.kt              ← data model for a scripted agent scenario
  AgentScriptLoader.kt        ← reads JSON files from agent-scripts/
  ScriptedAssistantBackend.kt ← deterministic backend for workflow tests
  AgentWorkflowTest.kt        ← 17 inline workflow scenarios
  ReleaseValidationReport.kt  ← report writer (JSON + Markdown)

src/test/resources/release/
  tool-cases/
    <tool-name>/
      <case-id>.json          ← one contract case per file
  agent-scripts/
    <script-id>.json          ← scripted agent conversation (optional; tests are inline)
  fixtures/
    projects/
      kotlin-gradle-simple/   ← Calculator bug fixture
      kotlin-failing-tests/   ← Stack off-by-one fixture
      python-simple/          ← Python utility fixture
```

---

## Reports

All output is written to `build/release-validation/`:

| File | Contents |
|---|---|
| `results.json` | Machine-readable summary |
| `results.md` | Human-readable summary table |
| `junit/tool-contract/` | JUnit XML for toolContractTest |
| `junit/agent-workflow/` | JUnit XML for agentWorkflowTest |
| `html/tool-contract/` | HTML report for toolContractTest |
| `html/agent-workflow/` | HTML report for agentWorkflowTest |

---

## Tool inventory check

`ToolInventoryTest` scans all `*.kt` files under `src/main/kotlin/` for
`override val name = "..."` declarations and checks them against a hardcoded
set of expected tool names.

The test fails if any expected tool name is missing from the source.
Extra tool names in source (above the expected set) produce a warning but do
not fail — this allows the expected set to lag slightly behind new additions.

**To add a new tool to the inventory check**, add its name to
`ToolInventoryTest.EXPECTED_BUILTIN_TOOLS`.

---

## Tool contract cases

Each JSON file under `src/test/resources/release/tool-cases/<tool-name>/`
defines one contract case:

```json
{
  "id": "read-existing-file",
  "tool": "read_file",
  "arguments": { "path": "src/main/kotlin/Calculator.kt" },
  "fixtureProject": "kotlin-gradle-simple",
  "expectSuccess": true,
  "expectContentContains": ["class Calculator", "fun add"],
  "expectContentNotContains": [],
  "description": "reads Calculator.kt and returns its content"
}
```

`fixtureProject` resolves to `src/test/resources/release/fixtures/projects/<name>`.
If omitted, the tool runs against the fixtures root directory.

A case **passes** if:
1. `expectSuccess` matches whether the result is an error string.
2. All strings in `expectContentContains` are found in the result.
3. No string in `expectContentNotContains` is found in the result.

`ToolContractTest.everyToolHasAtLeastOneContractCase` prints a warning (not a
failure) for any tool that has no contract case — use this as a coverage tracker.

---

## Agent workflow tests

`AgentWorkflowTest` contains 17 inline scenarios that run the real
`HeadlessAgent` loop with a `ScriptedAssistantBackend` and small ephemeral
fixture projects created in a `TemporaryFolder`.

Each scenario exercises a specific agent capability:

| # | Scenario |
|---|---|
| 1 | `read_file` returns file content |
| 2 | `write_file` creates or overwrites a file |
| 3 | `create_file` writes a new file |
| 4 | `replace_in_file` patches existing content |
| 5 | `list_files` returns project structure |
| 6 | `search_code` finds files by content |
| 7 | `search_filenames` finds files by name |
| 8 | read → write pipeline |
| 9 | list → read discovery pipeline |
| 10 | search → fix pipeline |
| 11 | multi-file fix |
| 12 | agent stops on plain-text response |
| 13 | agent respects `maxRounds` |
| 14 | `replace_in_file` with missing pattern does not crash |
| 15 | `create_file` then `read_file` round-trip |
| 16 | `changedPaths` tracks written files |
| 17 | `git_status` and `git_diff` execute without crash |

---

## How to add a workflow test

1. Add a new `@Test` function to `AgentWorkflowTest.kt`.
2. Create a `TemporaryFolder` fixture with `tmp.newFolder()`.
3. Build a `ScriptedAssistantBackend` with the expected tool-call responses.
4. Run `HeadlessAgent` and assert on `RunResult` fields.

---

## How to add a contract case

1. Create a directory under `src/test/resources/release/tool-cases/<tool-name>/`
   if it does not already exist.
2. Add a JSON file following the schema above.
3. If the case uses a fixture project, make sure it exists under
   `src/test/resources/release/fixtures/projects/<name>/`.

---

## How to add a fixture project

1. Create `src/test/resources/release/fixtures/projects/<your-project-name>/`.
2. Add source files — no build files needed (the contract runner operates on
   the filesystem directly, not via Gradle).
3. Reference the project in contract cases via `"fixtureProject": "<your-project-name>"`.

---

## Citing results

When publishing release validation results:

- State the plugin version (`pluginVersion` in `results.json`).
- State the commit SHA.
- Include `results.json` or a link to it.
- Note that `agentWorkflowTest` uses scripted responses — it validates the agent
  pipeline, not model quality. For model quality signal use the benchmark harness
  (`./gradlew runLlamatikBenchmark`).
