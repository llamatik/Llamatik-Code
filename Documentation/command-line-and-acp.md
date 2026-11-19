# Llamatik on the command line, and in any ACP editor

The agent that powers Llamatik Code in IntelliJ also runs outside the IDE. It is the same code
(`agent-core`), the same tools, prompts and safety rules, on top of either a **local GGUF model** or a
**Llamatik server** (for example the Llamatik app with *Share this device* turned on).

| | Command | For |
|---|---|---|
| Terminal agent | `llamatik` | Working in a shell, scripts, CI, a headless box |
| ACP agent | `llamatik-acp` | Zed, JetBrains AI Assistant, Neovim and any editor that speaks the [Agent Client Protocol](https://agentclientprotocol.com) |

Build them with `./gradlew :cli:installDist :acp:installDist`. The launchers are created in
`cli/build/install/llamatik/bin/` and `acp/build/install/llamatik-acp/bin/` (Java 21 required).

## Choosing the model

Exactly one of (`--discover` replaces `--server`):

* `-m, --model <file.gguf>` runs the model in-process through the Llamatik native library (Metal, CUDA or CPU).
* `--discover` looks for Llamatik servers on the local network (devices with *Share this device* turned on)
  and lets you pick one; the same scan is in the IDE under Settings → Llamatik Server → *Scan network*.
  It uses mDNS, so both devices must be on the same network and multicast must not be blocked.
* `-s, --server <host[:port]>` uses a Llamatik server. Accepts `host`, `host:port` or `http://host:port`.
  If the server asks for per-device approval, accept the request on the server's device; the access token
  is then kept in `~/.llamatik/cli.properties` (readable only by you). Dropped connections are resumed
  without losing or repeating text, exactly like in the plugin.

Inference options (both commands): `--temperature`, `--max-tokens`, `--context`, `--threads`, `--gpu-layers`.

> Traffic to a server is plain HTTP and is not encrypted. Only use servers you trust on a network you trust.

## `llamatik` (terminal)

```
llamatik -m ~/.llamatik/models/Qwen3-14B-Q4_K_M.gguf          # interactive session in the current folder
llamatik -s mac-studio.local -p "fix the failing test in src/Calc.kt"   # one task, then exit
llamatik -C ~/code/app -m model.gguf --yes -p "add a README section about setup"
```

* The agent works in the project folder (`-C`, default: the current directory) and may read, search and
  list files, use git, and propose edits.
* **Nothing changes without your approval.** Each edit is shown as a diff and you answer `y` (once),
  `a` (always, for edits) or `N`. Commands (`terminal_run`) are asked about too, and only an allow-list of
  build and test commands can run at all. `--yes` approves everything for unattended use. When there is no
  terminal to ask (output piped, no `--yes`), edits are skipped rather than applied.
* Put project rules in `LLAMATIK.md` (or `AGENTS.md`) at the project root; they are added to the prompt.
* In a session: `/help`, `/clear` (forget the conversation), `/yes` (toggle auto-approve), `/exit`.
  `Ctrl-C` stops the current task; pressed again with nothing running it exits.
* `--debug` prints each raw model reply, which helps when a small model ignores the tool format.
* Exit codes: `0` ok, `1` the task failed or was cancelled, `2` no usable model/server, `64` bad usage.

Small models (around 1B parameters) usually cannot follow the tool protocol. Use a coding-capable model
of roughly 7B or more for the agent.

## `llamatik-acp` (editors)

The editor starts the command and talks to it over stdin/stdout; it chooses the project folder for every
session. Example for Zed (`settings.json`):

```json
{
  "agent_servers": {
    "Llamatik": { "command": "llamatik-acp", "args": ["--server", "mac-studio.local"] }
  }
}
```

What the editor gets:

* Answers, the agent's reasoning, and every tool call with its status and a short result.
* Edits as **diffs the editor can show**, applied only after the editor's own permission prompt
  (`--auto-approve` skips it). Files are read and written *through the editor* when it offers file access,
  so the agent sees unsaved buffers and edits land in the editor's undo history.
* Commands in the editor's terminal when it provides one.
* Files attached to the prompt become attachments (using the editor's unsaved text).
* `session/cancel` stops the current turn.

The model is loaded (or the server contacted) when the first session is created, so the editor handshake
is instant. Failures, such as no model configured, are reported as an error on that session request.

Notes:

* stdout carries the protocol and nothing else; diagnostics go to stderr. The ACP SDK
  (`com.agentclientprotocol:acp`, Apache-2.0) is pre-1.0, so its version is pinned and used only by this module.
* Not supported yet: loading earlier sessions, image and audio prompts, MCP servers passed by the editor.

## Developing

Both front-ends are small. The agent itself lives in `agent-core` and knows nothing about IntelliJ; the
hosts provide files, shell, git and permissions (`AgentEnvironment`). The model backends are in `backends`, also
used by the IDE plugin: one local engine and the Llamatik server client (plain JDK code, so it also runs inside the IDE). See `docs/agent-core-extraction.md`.
