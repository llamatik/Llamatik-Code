# Offline Enterprise Pair Programmer

The Offline Enterprise Pair Programmer is the primary flagship mode of Llamatik Code's Autonomous Local Development environment. It wraps every agent task in a structured, auditable workflow designed for teams where code must never leave the local machine.

---

## Workflow

Every enterprise mission follows these ten steps:

1. **Context gathering** — git status, code health insights, IDE diagnostics, project snapshot
2. **Risk detection** — classify risks by level (LOW / MEDIUM / HIGH / CRITICAL) and type
3. **Planning** — produce a named plan with scope, steps, verification method, and rollback instructions
4. **Approval gate** — present the plan for user review (configurable: automatic / require approval)
5. **Patch application** — apply small, targeted patches; never rewrite entire files unless required
6. **Diff preview** — show a diff before every multi-file write
7. **Verification** — run the build, tests, or IDE diagnostics to confirm the patch works
8. **Correction loop** — if verification fails, diagnose and re-patch (up to the agent round limit)
9. **Audit report** — record what changed, what was verified, and what the rollback path is
10. **Summary** — provide a plain-language summary of the completed mission

---

## Safety levels

The safety level controls how much the agent can do autonomously.

| Level | Behaviour |
|---|---|
| `APPROVAL_REQUIRED` | Agent presents plan and waits for explicit approval before writing (default) |
| `PLAN_ONLY` | Agent produces a plan but never applies changes |
| `AUTO_APPLY` | Agent applies changes automatically (still shows diffs) |
| `FULL_AUTO` | Agent applies and verifies without interruption (Pro) |

Change the default at **Settings → Llamatik Code → Autonomous Local Development → Default safety level**.

---

## Mission tools

The agent has four dedicated enterprise tools:

| Tool | Description |
|---|---|
| `enterprise_mission_create` | Classify the request, detect risks, build the mission plan |
| `enterprise_mission_context` | Gather git state, code health, diagnostics, and project snapshot |
| `enterprise_mission_audit` | Return the current audit trail (metadata only — no raw source code) |
| `enterprise_mission_report` | Produce the final mission report and close the mission |

These tools are read-only from the agent's perspective: they never write files directly. All file changes go through the standard `write_file` / `replace_in_file` / `apply_patch` tools, which trigger the diff preview.

---

## Audit trail

The audit trail records:

- Mission ID and type
- Timestamps for each stage transition
- Tool calls made during the mission
- Verification outcomes
- Final status (COMPLETED / FAILED / ABANDONED)

**The audit trail never stores raw source code.** It stores only metadata: file paths touched, tool names, and outcome codes.

---

## External knowledge

By default the enterprise agent operates with external knowledge disabled. It will not perform web searches or fetch external documentation during a mission.

To allow external knowledge during missions, enable **Settings → Llamatik Code → Autonomous Local Development → Allow external knowledge during missions**. This setting requires the [External Knowledge](external-knowledge.md) feature to also be enabled.

---

## Plan-before-write enforcement

When **Require plan before writing files** is enabled (default: on), the agent must call `enterprise_mission_create` and present a plan before it is allowed to call any write tool. If the agent attempts to write without a plan the system prompt overrides the action and asks the agent to create a plan first.

---

## Diff-before-write enforcement

When **Require diff preview before applying patches** is enabled (default: on), every patch is shown as a unified diff before it is applied. This applies even when safety level is `AUTO_APPLY`.

---

## Rollback

Every mission plan includes a rollback section. The rollback instructions are plain-language steps produced by the model based on the mission type and the files it intends to touch. They are included in the final audit report.

Common rollback patterns the agent produces:

- `git checkout -- <file>` for single-file changes
- `git revert HEAD` for committed changes
- `git stash` for uncommitted multi-file changes

---

## Starting an enterprise session manually

The agent detects enterprise mode automatically when you use mission phrasing. You can also start it explicitly:

```
Run an Offline Enterprise Pair Programmer session on this project.
```

Or use one of the context menu actions under **Llamatik Code → Autonomous Local Development**.

---

## Related

- [Autonomous Local Development](autonomous-local-development.md) — feature overview and mission types
- [Trust and Safety](trust-and-safety.md) — plan enforcement, diff gates, audit trail details
- [Agent & Tools](agent-and-tools.md) — underlying tool loop the enterprise workflow runs on
