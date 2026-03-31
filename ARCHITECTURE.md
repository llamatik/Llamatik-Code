# Llamatik Code Architecture

## Package structure

- `com.llamatik.teammate.actions` — IntelliJ actions that prefill prompts and open the tool window
- `com.llamatik.teammate.agent` — agent orchestration loop and system prompt
- `com.llamatik.teammate.backend` — backend abstractions for inference
- `com.llamatik.teammate.context` — project-aware context inspection with denylist protection
- `com.llamatik.teammate.diff` — one-file diff preview and safe apply flow
- `com.llamatik.teammate.llamatik` — Llamatik adapter service
- `com.llamatik.teammate.licensing` — freemium/trial abstraction placeholder
- `com.llamatik.teammate.model` — chat, edit proposal, and model descriptors
- `com.llamatik.teammate.persistence` — per-project session persistence
- `com.llamatik.teammate.settings` — app settings, model registry, and settings UI
- `com.llamatik.teammate.tools` — tool contracts, registry, and parser
- `com.llamatik.teammate.tools.impl` — concrete local project tools
- `com.llamatik.teammate.ui.chat` — chat view models and message panels
- `com.llamatik.teammate.ui.toolwindow` — tool window factory and main panel

## Agent loop

1. The user submits a prompt in the tool window or through an IDE action.
2. `AgentOrchestrator` builds a system prompt plus project context summary.
3. The backend runs the first pass against the local model.
4. If the response contains `<tool_call ...>` blocks, the plugin executes those tools locally.
5. Tool results are fed into a second backend pass.
6. The final response is rendered in the UI.
7. If the final response contains an `<edit_proposal ...>` block, the UI stores it as a pending edit and exposes diff/apply actions.

## Safety model

- denylisted secrets and ignored directories are excluded from project tools
- max file size caps protect context gathering
- auto-apply remains disabled by default
- edit proposals are one-file only in the MVP and require confirmation
- diff preview is shown before applying changes
