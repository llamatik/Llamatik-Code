# External Knowledge (Context-Aware Web & Docs Research)

External Knowledge gives the Llamatik Code agent the ability to search the web, read documentation pages, research errors, and query private docs sources — all from inside the IDE, with citations.

## Enabling it

External Knowledge is off by default.

1. Open **Settings → Llamatik Code → External Knowledge**.
2. Toggle **Enable external knowledge**.
3. Configure search limits, docs sources, and auto-use preferences.

## Web search

The agent uses DuckDuckGo to search the web without an API key. Searches are quota-gated per day.

### Direct tools

| Tool | Description |
|---|---|
| `external_search` | General web search for any query |
| `external_search_official_docs` | Search restricted to official documentation sites |
| `external_search_github_issues` | Search GitHub Issues for known bugs and solutions |

### Free vs Pro

| | Free | Pro |
|---|---|---|
| Searches per day | 5 | 50 |
| Results per search | 5 | 10 |

## Page reading

The agent can fetch and read the content of web pages, stripping HTML to plain text for efficient context use.

| Tool | Description |
|---|---|
| `external_read_page` | Read a single URL |
| `external_read_pages` | Read multiple URLs in parallel |

| | Free | Pro |
|---|---|---|
| Pages per task | 3 | 10 |

## Research orchestration

The research orchestrator detects the intent behind a query and plans a multi-step research workflow automatically.

### Intents

| Intent | Triggered by | Secondary queries |
|---|---|---|
| `LIBRARY_DOCS` | Library/framework name in query | Adds "documentation" |
| `BUG_ERROR` | Stack trace, exception name, error code | Adds "error fix" |
| `API_BEHAVIOR` | API name + "how", "does", "when" | Adds "API example" |
| `MIGRATION` | "migrate", "upgrade", "from X to Y" | Adds "migration guide" |
| `LATEST_VERSION` | "latest", "current version", "release" | Adds "latest version release" |

### Research tools

| Tool | Description |
|---|---|
| `research_plan` | Plan a multi-step research workflow for a query |
| `research_execute` | Execute the planned queries and collect results |
| `research_summarize` | Synthesise findings into a concise answer with citations |
| `research_route_sources` | Route a query to the most relevant source types |
| `research_fix_plan` | Produce a structured fix plan from research findings |

## Smart source routing

The `research_route_sources` tool uses keyword-based routing to direct queries to the most relevant sources:

| Keyword in query | Routes to |
|---|---|
| `kotlin`, `coroutine`, `suspend` | kotlinlang.org |
| `android`, `compose`, `jetpack` | developer.android.com |
| `ios`, `swift`, `swiftui`, `xcode` | developer.apple.com |
| `spring`, `springboot` | spring.io |
| `bug`, `issue`, `error`, `crash` | github.com |
| default | DuckDuckGo general web search |

## Error research

The error research tools analyse compiler and runtime errors and produce actionable fix plans.

| Tool | Description |
|---|---|
| `error_research_analyze` | Analyse an error message; detect build tool, framework, and error category |
| `error_research_fix` | Generate a structured fix plan for the analysed error |

**Build tool detection:** identifies `gradle`, `maven`, `xcodebuild`, `javac`, `kotlinc`, and more from the error output.

**Framework detection:** identifies Jetpack Compose, Spring, Ktor, Retrofit, Room, and others from import paths and error messages.

The fix plan includes:
- Error category
- Likely root cause
- Ordered fix steps with code suggestions
- Links to relevant documentation

## Private docs sources

You can add your own documentation sources (internal wikis, SDK docs, API references) and have the agent search them directly.

| Tool | Description |
|---|---|
| `docs_sources_list` | List all configured docs sources |
| `docs_source_add` | Add a new docs source by URL |
| `docs_index_refresh` | Re-crawl and re-index all docs sources |
| `docs_search` | Search across all indexed docs sources |

| | Free | Pro |
|---|---|---|
| Docs sources | 1 | 10 |
| Auto-use in agent | — | Yes |

## Citations

Every research result that uses external content includes structured citations:

```
[1] kotlinlang.org — Kotlin Coroutines: Basics (official documentation, high confidence)
    https://kotlinlang.org/docs/coroutines-basics.html
```

Citation source types: `OFFICIAL_DOCS`, `GITHUB_ISSUES`, `STACK_OVERFLOW`, `BLOG`, `RELEASE_NOTES`, `CHANGELOG`, `PACKAGE_REGISTRY`.

## Editor action

Right-click any selected text in the editor and choose **Search Docs & Web** to open a pre-filled search with the selection as the query.
