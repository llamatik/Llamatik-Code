# Trust and Safety

Llamatik Code is designed to be trustworthy by default. This document explains how the plugin enforces safe behaviour during autonomous missions, what gates exist before code is modified, and how you can verify what the agent did.

---

## Privacy guarantee

All inference runs on a local GGUF model on your machine. Source code is never sent to Llamatik servers or any third-party AI service. The only exceptions are:

- **MCP servers you configure yourself** — if an MCP server calls an external API, that traffic goes through the process you set up. The plugin itself never forwards code.
- **External knowledge** — if you enable the External Knowledge feature, the agent may send search queries (not code) to the configured search provider.

The Autonomous Local Development mode adds an additional constraint: external knowledge is disabled by default during enterprise missions, even when the feature is globally enabled.

---

## Plan-before-write

When **Require plan before writing files** is enabled (default), the agent must:

1. Call `enterprise_mission_create` and receive an approved plan.
2. Only then call any write tool (`write_file`, `replace_in_file`, `apply_patch`, `create_file`).

If the agent attempts to write without a plan the system prompt instructs it to create a plan first. This does not block the agent — it redirects it. In practice, well-prompted mission phrasing always produces a plan before writing.

---

## Diff preview

When **Require diff preview before applying patches** is enabled (default), every file write is shown as a unified diff. The diff is presented in the IDE's built-in diff viewer. You choose to accept or reject each change.

This applies even in `AUTO_APPLY` safety mode — the diff is still shown, but the apply button is highlighted rather than requiring a manual click.

---

## Audit trail

Every enterprise mission produces an audit trail. The trail records:

| Field | Description |
|---|---|
| Mission ID | Unique identifier for the mission |
| Mission type | Classified type (e.g. `BUG_FIX`, `REFACTOR`) |
| Safety level | The level in effect during the mission |
| Risks detected | List of risks with level and description |
| Audit events | Timestamped log of tool calls and stage transitions |
| Verification outcome | Pass / fail / skipped |
| Final status | COMPLETED / FAILED / ABANDONED |
| Rollback instructions | Plain-language steps to undo the mission |

**The audit trail never stores raw source code.** Only file paths and metadata are recorded.

Retrieve the audit trail at any time during a mission:

```
Show the current mission audit trail
```

Or use `enterprise_mission_audit` in the agent chat.

---

## Risk levels

The agent classifies risks detected during context gathering:

| Level | Meaning |
|---|---|
| `LOW` | Cosmetic or stylistic change, low regression risk |
| `MEDIUM` | Functional change, standard review recommended |
| `HIGH` | Cross-module impact, test coverage required |
| `CRITICAL` | Security-sensitive or data-loss risk, human review required |

CRITICAL risks are flagged prominently in the mission plan. The agent will not proceed past the planning stage on a CRITICAL-risk mission without explicit user acknowledgement.

---

## External knowledge isolation

During enterprise missions:

- External search is disabled by default.
- The agent will not fetch web pages or external documentation.
- This ensures the mission operates entirely on local project knowledge.

To allow external knowledge in missions, enable **Settings → Llamatik Code → Autonomous Local Development → Allow external knowledge during missions**. This setting is off by default and requires the [External Knowledge](external-knowledge.md) feature to also be enabled globally.

---

## What the agent cannot do

Regardless of settings, the enterprise agent:

- Cannot send source code to remote servers (inference is always local).
- Cannot modify files outside the open project.
- Cannot execute arbitrary shell commands without going through `terminal_run`, which is subject to the allowed-commands list.
- Cannot skip the diff preview when `enterpriseRequireDiffBeforeWrite` is enabled.
- Cannot produce a mission report without completing at least one verification step.

---

## Verification

Every enterprise mission plan includes a verification method. Common verification approaches:

| Method | When used |
|---|---|
| IDE diagnostics (`diagnostics_run`) | Always available; fast |
| Build tool (`terminal_run ./gradlew build`) | When a build config is detected |
| Test suite (`terminal_run ./gradlew test`) | When test files exist |
| Manual review prompt | Fallback when no automated verification is possible |

The agent reports the verification outcome in the final mission report. A failed verification does not end the mission — the agent attempts a correction loop.

---

## Related

- [Offline Enterprise Pair Programmer](offline-enterprise-pair-programmer.md) — full workflow details
- [Autonomous Local Development](autonomous-local-development.md) — feature overview
- [Settings Reference](settings-reference.md#autonomous-local-development) — all safety-related settings
