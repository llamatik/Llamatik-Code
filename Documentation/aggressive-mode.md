# Aggressive Mode (Pro)

Aggressive mode changes how the agent handles uncertainty and confirmation. Instead of pausing to ask for approval on each step, the agent applies changes decisively and moves forward.

## When to use it

Use Aggressive mode for:
- Large, mechanical refactors where you trust the agent's judgement
- Tasks where the change is clearly bounded and reversible (e.g., "add KDoc to all public functions in this module")
- Situations where you want the agent to iterate past build errors automatically without prompting

Do not use Aggressive mode for:
- Tasks that touch authentication, networking, or data storage
- Exploratory tasks where you are still deciding the approach
- Anything involving production infrastructure or external APIs

## Enabling it

1. Open the **Agent mode** panel in the tool window.
2. Select **Aggressive** from the execution mode dropdown.

The mode selector is only available to Pro users. Free users see it greyed out with an upgrade prompt.

## Fallback policy

When a step fails in Aggressive mode, the agent follows a configurable fallback policy:

| Policy | Behaviour |
|---|---|
| `ABORT` | Stop the run immediately and report the error. Safe default. |
| `RETRY` | Retry the failed step once with a modified prompt. |
| `ESCALATE` | Continue with remaining steps and collect all failures into a final report. |

Configure the policy in **Settings → Llamatik Code → Inference → Agent fallback policy**.

## Diff preview in Aggressive mode

Even in Aggressive mode, file writes produce a diff preview. The difference is that in standard mode the agent waits for your explicit approval before continuing; in Aggressive mode it applies the change and proceeds while the preview is still visible for review.

You can revert any applied change via the standard IDE **Undo** (⌘Z / Ctrl+Z) at any point during or after the run.

## Multi-task agent (Pro)

The multi-task panel lets you queue multiple agent tasks and run them in parallel:

1. Open the **Tasks** tab in the tool window.
2. Click **New task** and enter an instruction.
3. Repeat to queue additional tasks.
4. Click **Run all** — each task runs on a separate pooled thread.

Tasks that write to the same file are serialised automatically to avoid conflicts. The task panel shows live status (queued / running / done / failed) and lets you cancel individual tasks.
