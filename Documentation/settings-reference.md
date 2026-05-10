# Settings Reference

All Llamatik Code settings live under **Settings → Llamatik Code**. The main page and its child pages are described below.

## Llamatik Code (main page)

| Setting | Description | Default |
|---|---|---|
| License key | Pro license key | — |
| Trial status | Days remaining in trial | — |

## Inference

| Setting | Description | Default |
|---|---|---|
| Model path | Absolute path to a GGUF model file | — |
| Context size | Token context window | 4096 |
| Threads | CPU threads for inference | System cores / 2 |
| GPU layers | Layers offloaded to GPU (0 = CPU only) | 0 |
| Temperature | Sampling temperature | 0.7 |
| Top-p | Nucleus sampling threshold | 0.9 |
| Agent fallback policy | ABORT / RETRY / ESCALATE on step failure | ABORT |
| Run benchmark | Measure tokens/s for the current model | button |

## MCP Servers

| Setting | Description | Default |
|---|---|---|
| Server list | Configured MCP servers | empty |
| Add server | Open the server editor | button |
| Auto-approval rules | Per-tool and per-server approval rules (Pro) | empty |

Per-server fields: name, command, arguments, environment variables, working directory, enabled, auto-start, trust level (LOW / MEDIUM / HIGH).

## External Knowledge

| Setting | Description | Default |
|---|---|---|
| Enable external knowledge | Master toggle | off |
| Max searches per day | Daily quota override (up to tier limit) | tier default |
| Max pages per task | Pages fetched per agent task | tier default |
| Enable auto-use | Let the agent search automatically (Pro) | off |
| Docs sources | List of private documentation sources | empty |
| Max docs sources | Per-tier limit (1 Free, 10 Pro) | tier default |

## Code Health Monitor

| Setting | Description | Default |
|---|---|---|
| Enable Code Health Monitor | Master toggle | off |
| Analysis mode | OFF / LIGHT / BALANCED / AGGRESSIVE | OFF |
| Run trigger | MANUAL / ON_SAVE / IDLE / BEFORE_COMMIT | MANUAL |
| Max files per scan | Override the mode's default file budget | mode default |
| Max runtime (ms) | Override the mode's default time budget | mode default |
| Max model calls per hour | Rate limit for model-backed insights | mode default |
| Live suggestions | Show inline hints as you type (Pro) | off |
| Test awareness | Include missing-test detection | on |
| Regression detection | Include git-diff regression analysis | off (Pro) |
| Show dashboard | Enable the health dashboard panel | on |
| Before-commit review | Auto-run before every commit (Pro) | off |

### Budget reference

| Mode | Max files | Max runtime | Model calls/hour | Max insights/file |
|---|---|---|---|---|
| LIGHT (Free + Pro) | 5 | 8 s | 3 | 3 |
| BALANCED (Pro) | 30 | 30 s | 20 | 10 |
| AGGRESSIVE (Pro) | 150 | 120 s | 50 | 20 |
