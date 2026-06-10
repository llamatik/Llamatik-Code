# Autonomous Local Development

Llamatik Code 1.7.0 ships a new product mode called **Autonomous Local Development**. It transforms the plugin from a chat assistant into a mission-driven coding environment: you describe a task, the agent plans it, applies patches, and verifies the result — entirely on your machine.

**Core guarantee:** your source code never leaves your device.

---

## What is a local coding mission?

A mission is a discrete, goal-oriented task you hand to the agent:

- Fix this build failure
- Refactor this module without changing behavior
- Generate tests for the current file
- Review the current git changes before committing
- Implement this feature end-to-end

The agent runs a structured workflow for each mission:

1. Gather project context (git state, code health, architecture)
2. Classify the mission type and detect risks
3. Create a plan with explicit verification steps and rollback instructions
4. Apply small, reviewable patches
5. Show a diff before every multi-file write
6. Verify the result (build, tests, or IDE diagnostics)
7. Produce an audit report

---

## Starting a mission

**From the tool window:**

Type a mission description in the chat input and press Enter. Mission-aware phrasing like "fix the build failure", "refactor the auth module", or "generate tests for UserService" triggers the full enterprise workflow automatically.

**From the editor right-click menu:**

Right-click in any editor → **Llamatik Code → Autonomous Local Development** → choose an action.

**Available context actions:**

| Action | Description |
|---|---|
| Enterprise Pair Programmer | Full mission session on the current file or project |
| Review Current Changes | Code review of uncommitted git changes |
| Fix Build Failure | Analyse and repair the last build failure |
| Generate Tests | Produce tests for the current file |
| Explain Architecture | Describe responsibilities and dependencies of the current module |
| Safe Refactor | Refactor selected code or the current file without behavior change |
| Implement Feature | Guided feature implementation with plan and diff |

---

## Mission types

| Type | Trigger phrases |
|---|---|
| `CODE_REVIEW` | review, audit, check quality, evaluate |
| `REFACTOR` | refactor, restructure, extract, clean up |
| `BUG_FIX` | fix, bug, error, broken, not working |
| `FEATURE_IMPLEMENTATION` | implement, feature, add support, create new |
| `TEST_GENERATION` | test, coverage, spec, unit test |
| `DOCUMENTATION` | document, javadoc, kdoc, explain this |
| `BUILD_FAILURE_REPAIR` | build fail, compile error, gradle fail, maven fail |
| `ARCHITECTURE_EXPLANATION` | architecture, design, structure, explain module |
| `DEPENDENCY_UPDATE` | dependency, upgrade, update library |
| `SECURITY_AUDIT` | security, vulnerability, owasp, injection |
| `PERFORMANCE_ANALYSIS` | performance, slow, optimise, profil |

---

## Free vs Pro scope

| Capability | Free | Pro |
|---|---|---|
| Current-file missions | Yes | Yes |
| Selection-scoped missions | Yes | Yes |
| Git-diff missions | Yes | Yes |
| Directory / module missions | — | Yes |
| Watched-area missions | — | Yes |
| Before-commit enterprise review | — | Yes |
| Build/test automation in missions | — | Yes |

---

## Settings

Settings live at **Settings → Llamatik Code → Autonomous Local Development**.

See [settings-reference.md](settings-reference.md#autonomous-local-development) for the full list of options.

---

## Related

- [Offline Enterprise Pair Programmer](offline-enterprise-pair-programmer.md) — deep dive into the mission workflow
- [Trust and Safety](trust-and-safety.md) — how the agent enforces plan-before-write and audit trails
- [Inline Completions](inline-completions.md) — passive code suggestions as you type
