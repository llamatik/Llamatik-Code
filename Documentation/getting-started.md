# Getting Started with Llamatik Code

Llamatik Code is a local-first AI coding assistant for IntelliJ-based IDEs. It runs models on your machine — your code never leaves your device unless you explicitly configure an external MCP server.

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
4. Return to the tool window and start chatting.

## Free vs Pro

| Capability | Free | Pro |
|---|---|---|
| Chat (Ask mode) | Unlimited | Unlimited |
| Single-session agent | Yes | Yes |
| Multi-task agent | — | Yes |
| Parallel chat sessions | Yes | Yes |
| MCP servers | 1 (manual approval) | Unlimited + auto-approval |
| Aggressive mode | — | Yes |
| Multi-file apply | — | Yes |
| Auto-build + auto-fix | — | Yes |
| External Knowledge | 5 searches/day, 1 docs source | 50 searches/day, 10 sources |
| Code Health Monitor | LIGHT / manual only | BALANCED + AGGRESSIVE + background |

Upgrade at [llamatik.com](https://www.llamatik.com).

## Privacy

All inference runs locally by default. No data is sent to Llamatik servers. If you connect an MCP server that calls an external API, that traffic goes through the MCP server process you configured — the plugin itself never forwards your code anywhere.
