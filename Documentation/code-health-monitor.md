# Code Health Monitor

The Code Health Monitor is a proactive background analysis layer that continuously inspects your codebase for structural problems, regression risks, and refactoring opportunities — without requiring a model call for every insight.

## Enabling it

The Code Health Monitor is off by default.

1. Open **Settings → Llamatik Code → Code Health Monitor**.
2. Toggle **Enable Code Health Monitor**.
3. Choose an analysis mode and run trigger.

## Analysis modes

| Mode | Files per scan | Max runtime | Model calls/hour | Who |
|---|---|---|---|---|
| `LIGHT` | 5 | 8 s | 3 | Free + Pro |
| `BALANCED` | 30 | 30 s | 20 | Pro only |
| `AGGRESSIVE` | 150 | 120 s | 50 | Pro only |

Free users are always capped at LIGHT mode regardless of the setting stored on disk.

## Run triggers

| Trigger | When it fires | Who |
|---|---|---|
| `MANUAL` | Only when you explicitly request a scan | Free + Pro |
| `ON_SAVE` | 3 seconds after a file is saved (debounced) | Pro only |
| `IDLE` | When the IDE has been idle for a configurable duration | Pro only |
| `BEFORE_COMMIT` | Before a git commit is created | Pro only |

## Static analysis (no model required)

The following checks run on raw file content with no LLM call:

| Check | Trigger | Severity |
|---|---|---|
| Large file | >300 lines (LIGHT: >500) | WARNING |
| TODO/FIXME accumulation | ≥3 occurrences in a file | INFO |
| Empty catch block | `catch (...) {}` | WARNING |
| Deprecated API usage | `@Deprecated` annotation present | INFO |

These run fast enough to execute on every file save without impacting editor responsiveness.

## Regression risk detection

When a git commit or staged diff is present, the regression risk analyser scans the diff for patterns that commonly introduce regressions:

| Pattern | Severity |
|---|---|
| Removed public function / class / interface | HIGH |
| Deleted `@Test` annotation | HIGH |
| Changed Gradle dependency | WARNING |
| Removed `try` or `catch` block | WARNING |
| Modified `AndroidManifest.xml` | WARNING |

Results are surfaced as `REGRESSION_RISK` and `DEPENDENCY_RISK` insights.

## Refactor opportunity detection

The refactor analyser scans source files for:

- **Long functions** — functions exceeding 40 lines (tracked by bracket depth).
- **Repeated catch patterns** — four or more identical catch blocks in a file.

These produce `REFACTOR_OPPORTUNITY` insights.

## Insight severity

| Severity | Meaning |
|---|---|
| `HIGH` | Likely to cause a regression or break callers |
| `WARNING` | Worth investigating before the next release |
| `INFO` | Low-priority improvement opportunity |

Only `HIGH` severity insights trigger a notification balloon. `WARNING` and `INFO` insights are visible in the insights panel and via agent tools.

## Watched areas

Pin specific files, directories, packages, or modules to give them higher priority:

1. Use the `watch_area_add` tool or the settings panel.
2. Insights for watched paths are highlighted and surfaced first.
3. The `before_commit_review` tool focuses on watched areas when generating its summary.

| | Free | Pro |
|---|---|---|
| Watched areas | 1 | 20 |

## Before-commit assistant

The before-commit assistant runs automatically when the `BEFORE_COMMIT` trigger fires (Pro), or on demand via the `before_commit_review` tool.

It produces a `CommitReview` with:
- Lines added / removed across all staged files
- A suggested commit message
- All regression-risk and dependency-risk insights from the diff
- A flag if any watched areas are affected

## Session context

`CurrentWorkContextService` tracks your work session:
- Up to 10 recently accessed files
- Up to 10 recent agent prompts
- Current git branch
- Last build failure message

The session context is injected into the `before_commit_review` and `code_health_explain_insight` tools to make their output more relevant to what you are currently working on.

## Agent tools

All 14 Code Health tools are read-only (safe to use autonomously):

| Tool | Description |
|---|---|
| `code_health_status` | Current monitor status, mode, and last scan time |
| `code_health_refresh` | Trigger an immediate scan |
| `code_health_list_insights` | List all current insights, optionally filtered by severity or type |
| `code_health_explain_insight` | Detailed explanation of a specific insight with session context |
| `code_health_create_fix_plan` | Generate a step-by-step fix plan for an insight |
| `code_health_ignore_insight` | Suppress a specific insight by ID |
| `watch_area_add` | Add a path to the watched areas list |
| `watch_area_remove` | Remove a watched area by ID |
| `watch_area_list` | List all watched areas |
| `session_context_get` | Read the current session context |
| `session_context_clear` | Reset the session context |
| `before_commit_review` | Review all staged changes for regression risks |
| `regression_risk_analyze` | Analyse a specific git diff for regression patterns |
| `refactor_analyze_file` | Analyse a file for refactoring opportunities |

## Insight types

`COMPLEXITY`, `MISSING_TESTS`, `DUPLICATION`, `DEPRECATED_API`, `ARCHITECTURE_DRIFT`, `ERROR_HANDLING`, `REGRESSION_RISK`, `REFACTOR_OPPORTUNITY`, `DEPENDENCY_RISK`, `COMMIT_RISK`.
