# Contributing to Llamatik Code

Thank you for your interest in contributing. This document covers everything you need to get the project building locally, explains what is intentionally incomplete, and describes the pull request process.

---

## Table of contents

- [Prerequisites](#prerequisites)
- [Building from source](#building-from-source)
- [Running in a sandboxed IDE](#running-in-a-sandboxed-ide)
- [Running tests](#running-tests)
- [Running the benchmark harness](#running-the-benchmark-harness)
- [Known intentional stubs](#known-intentional-stubs)
- [How to contribute](#how-to-contribute)
- [Pull request checklist](#pull-request-checklist)

---

## Prerequisites

| Requirement | Version |
|---|---|
| JDK | 21+ |
| Gradle | Wrapper included (`./gradlew`) |
| IntelliJ IDEA | 2026.1+ (for IDE development) |

No other toolchain setup is required. The IntelliJ Platform Gradle Plugin downloads the target IDE automatically on first build.

---

## Building from source

```bash
git clone https://github.com/Llamatik-AI/Llamatik-Teammate.git
cd Llamatik-Teammate
./gradlew buildPlugin
```

The built plugin `.zip` is written to `build/distributions/`. Install it via **Settings → Plugins → Install Plugin from Disk**.

---

## Running in a sandboxed IDE

```bash
./gradlew runIde
```

This launches a sandboxed IntelliJ IDEA instance with the plugin installed. The sandbox is isolated from your main IDE installation.

---

## Running tests

```bash
./gradlew test
```

---

## Running the benchmark harness

The benchmark harness runs the real agent pipeline against self-contained fixture repositories. See [docs/benchmarking.md](docs/benchmarking.md) for the full guide.

```bash
# Scripted backend — no model required, useful for CI
./gradlew runLlamatikBenchmark -PbenchmarkModel=scripted

# With a real model
./gradlew runLlamatikBenchmark -PbenchmarkModel=/path/to/model.gguf
```

---

## Known intentional stubs

The following are **deliberately incomplete** in the current codebase. Before opening an issue or PR for one of these, read the note — some are blocked on upstream changes, others have known design decisions pending.

| Location | What it does now | What it should do | Blocked on |
|---|---|---|---|
| `LlamatikBackendService.streamFallback()` | Replays the full response in 24-char chunks with 10 ms delay — fake streaming | True token streaming | Real streaming API in `com.llamatik:core` |
| `LlamatikBackendService.cancel()` | Cancels the Java `Future` but cannot stop the native model mid-generation | Cooperative native cancellation | Cancellation support in the Llamatik runtime |
| `DownloadedModelService.downloadPredefined()` | Writes a placeholder `.gguf` file to disk | Real Hugging Face download with checksum verification | Design decision: URL pattern, progress UX |
| Multi-agent orchestration | `AgentRole` enum exists but the agent loop only uses `GENERAL` | Planner / Implementer / Reviewer split | Architecture decision pending |
| Multi-file patch UI | Only one-file diff preview per session | Multi-file patch application with a combined diff view | UX design pending |
| `McpApprovalDialog` | Dialog exists but is not wired into `AgentOrchestrator` | Blocking approval dialog integrated into the agent loop | `AgentOrchestrator` refactor |
| MCP HTTP/SSE transport | Only stdio is implemented | HTTP/SSE server transport | Implementation deferred |
| MCP env secrets | Stored in plain XML | `PasswordSafe` integration | Implementation deferred |

If you want to work on one of these, open an issue first to discuss the approach before writing code.

---

## How to contribute

1. **Bug reports** — open an issue at [Llamatik-AI/Llamatik-Code-Issue-Tracker](https://github.com/Llamatik-AI/Llamatik-Code-Issue-Tracker/issues) with the bug report template.
2. **Feature requests** — same issue tracker, use the feature request template.
3. **Code contributions** — fork the repo, make your changes on a branch, and open a pull request against `main`.
4. **Documentation** — the docs site lives in a separate repository. See [docs.llamatik.com](https://docs.llamatik.com) for the source.
5. **Benchmark tasks** — new benchmark tasks are very welcome. See [docs/benchmarking.md](docs/benchmarking.md) for how to add one.

---

## Pull request checklist

Before submitting a PR, please confirm:

- [ ] The project builds cleanly with `./gradlew buildPlugin`
- [ ] All existing tests pass with `./gradlew test`
- [ ] The benchmark suite passes with `./gradlew runLlamatikBenchmark -PbenchmarkModel=scripted`
- [ ] You have not modified files in `assets/` or `src/main/resources/icons/` (proprietary — see `LICENSE-assets`)
- [ ] Your commit message is clear and describes *why* the change was made, not just what
- [ ] If your change touches a known stub, you have updated or removed the relevant entry in this file

---

## License

By contributing you agree that your contributions will be licensed under the [Apache License 2.0](LICENSE).  
Visual assets are excluded — see [LICENSE-assets](LICENSE-assets).
