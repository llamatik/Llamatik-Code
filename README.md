# Llamatik Code

Llamatik Code is a local-first IntelliJ coding assistant for the Llamatik Teammate project.

## Implemented MVP

- Docked **Llamatik Code** tool window
- Multi-tab project chat sessions with persistence
- Llamatik backend abstraction with local model selection
- Tool-driven agent loop with tool-call parsing
- Project context tools: list/read/search/current file/selection
- Safe one-file edit proposals with diff preview and approval
- Settings page for local model path, model presets, safety limits, and project rules
- Stub-ready model download registry for future Hugging Face integration
- IntelliJ editor/project actions for explain/refactor/fix/document/test/edit flows

## Key TODOs

- Replace fallback pseudo-streaming with true Llamatik streaming when available
- Implement cooperative native cancellation in the Llamatik adapter
- Replace model placeholder downloads with real Hugging Face download logic and verification
- Extend safe patch flow to support multi-file patches and automated checks
- Add build/test/git tools and richer multi-agent orchestration
