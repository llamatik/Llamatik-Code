# Getting Started with Llamatik Code

Llamatik Code is a local-first autonomous development environment for IntelliJ-based IDEs. It runs models on your machine — plan, patch, review, and verify code locally. Your source code never leaves your device unless you explicitly configure an external MCP server.

## Requirements

- IntelliJ IDEA 2026.1 or any JetBrains IDE built on platform 252 or later
- A compatible GGUF language model file on disk
- macOS, Linux, or Windows with a 64-bit JDK 21+

## Installation

1. Open **Settings → Plugins → Marketplace**.
2. Search for **Llamatik Code**.
3. Click **Install**, then restart the IDE.

Alternatively, download the `.zip` from the [JetBrains Plugin Marketplace](https://plugins.jetbrains.com) and install via **Settings → Plugins → Install Plugin from Disk**.

## First run

After installation a **Llamatik Code** tool window appears on the right side of the IDE.

1. Open **Settings → Llamatik Code → Inference**.
2. Set the **Model path** to a GGUF file on your disk.
3. Adjust context size and thread count to match your hardware.
4. Return to the tool window and describe a mission.

If you want to get started immediately without configuring inference settings manually, click **I just want it to work** on the welcome screen. The plugin will apply a preset appropriate for your hardware.

## Starting your first mission

Llamatik Code works through **local coding missions** — goal-oriented tasks the agent plans and executes entirely on your machine.

Examples to try in the tool window:

- `Fix the build failure`
- `Refactor the auth module without changing behavior`
- `Generate tests for UserService`
- `Review the current git changes before committing`

The agent will gather context, present a plan, apply patches as diffs, and produce an audit report — all locally.

For quick actions without typing, right-click in any editor → **Llamatik Code → Autonomous Local Development**.

## Free vs Pro

| Capability | Free | Pro |
|---|---|---|
| Chat (Ask mode) | Unlimited | Unlimited |
| Single-session agent | Yes | Yes |
| Multi-task agent | — | Yes |
| Parallel chat sessions | Yes | Yes |
| Multi-file apply | Yes | Yes |
| Auto-build + auto-fix | Yes | Yes |
| Max tokens per response | 65 536 | 65 536 |
| Context size setting | Yes | Yes |
| Image attachments | 5 per session | Unlimited |
| Skills | 3 active | Unlimited |
| MCP servers | 1 (manual approval) | Unlimited + auto-approval |
| Aggressive mode | — | Yes |
| External Knowledge | 15 searches/day, 3 docs sources | 50 searches/day, 10 sources |
| Code Health Monitor | LIGHT / manual only | BALANCED + AGGRESSIVE + background |
| Enterprise missions (current file / selection / git diff) | Yes | Yes |
| Enterprise missions (directory / module scope) | — | Yes |
| Before-commit enterprise review | — | Yes |
| Inline completions | Yes | Yes |

Upgrade at [llamatik.com](https://www.llamatik.com).

## Privacy

All inference runs locally by default. No data is sent to Llamatik servers. If you connect an MCP server that calls an external API, that traffic goes through the MCP server process you configured — the plugin itself never forwards your code anywhere.
