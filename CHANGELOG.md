# Changelog

## [1.6.3] - 2026-05-15

### Fixed
- **"Prompt too long" error with Batch Size 8192** — reviewing or attaching large files (e.g. via `@{path}`) produced a _Prompt too long for current Batch Size (8192 tokens)_ error even though the batch size was large enough. The file content budget was calculated by reserving only 2 000 characters for non-file overhead, when the actual overhead (system prompt, conversation history, user instruction, and wrappers) is ~9 000 characters. The budget now accounts for all fixed prompt sections so file content is correctly capped at the space that actually remains within the batch limit.

## [1.6.1] - 2026-05-13

### Fixed
- **File read through `build/` directories** — explicitly referenced files (e.g. via `@path`) inside `build/`, `generated/`, or other ignored directories were incorrectly blocked with "Access denied". Explicit `read_file` calls now bypass the directory ignore list while still blocking secret files (`.env`, `*.keystore`, etc.).
- **Absolute path references** — using `@` to attach a file with an absolute path (e.g. `/Users/.../build/...`) caused a silent "File not found" error because the path was resolved relative to the project root. Absolute paths are now routed through `LocalFileSystem` directly.
- **Text selection lost on focus change** — selecting text in a chat or agent message bubble and then clicking elsewhere caused the selection highlight to disappear, making copy/paste unreliable. The selection is now kept visible regardless of focus.

### Added
- **Right-click context menu on message bubbles** — right-clicking any message in the Chat panel shows a popup with _Copy_ (selection), _Copy All_, and _Select All_. Works on both user and assistant messages.
- **Right-click context menu in Agent panel** — same context menu is available on the response text, reasoning (think) block, agent trace, and build error areas.

## [1.0.0] - 2026-04-20

### Added
- Local-first AI coding assistant powered by llama.cpp (runs fully offline)
- Multi-tab persistent chat sessions with project-aware context
- AI agent loop for code editing with safe diff previews
- Editor actions: Ask About Selection, Explain File, Refactor Selection, Fix Selected Code, Generate Tests, Add Documentation, Edit with Prompt
- Model Library: browse and download quantized GGUF models
- Multimodal support for vision-language models (mmproj)
- Free tier for everyday chat assistance
- Pro tier: extended context, advanced inference settings, multi-file agent workflows, auto-approve/build/fix agent flags
- 30-day free trial for Pro features
- Settings pages for model management and inference parameters (temperature, threads, context size, batch size, flash attention)
- Skill library for reusable prompt templates
- Onboarding flow for first-time setup
