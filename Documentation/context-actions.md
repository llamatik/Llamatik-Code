# Context Actions

Llamatik Code adds actions to the editor and project view right-click menus for quick, context-aware AI operations.

## Editor actions

Right-click anywhere in the editor to access:

| Action | What it does |
|---|---|
| **Ask Llamatik About Selection** | Opens the tool window with the selected code pre-loaded as context. Ask any question about it. |
| **Explain This File** | Sends the entire current file to the model and displays an explanation in the tool window. |
| **Refactor Selection** | Asks the agent to refactor the selected code. A diff preview is shown before any change is applied. |
| **Fix Selected Code** | Sends the selection to the agent with an instruction to fix it. Useful after spotting a bug or a lint warning. |
| **Generate Tests for File** | Asks the agent to generate a test class for the current file. |
| **Add Documentation** | Generates KDoc / Javadoc for the selected declaration or the entire file. |
| **Edit with Prompt** | Opens a small input field where you type an instruction; the agent applies it to the selected code. |
| **Explain Failing Build** | Reads the last build output and explains what went wrong and how to fix it. |
| **Run Llamatik Diagnostics** | Runs plugin self-diagnostics and displays a health report in the tool window. |
| **Explain Current Git Changes** | Analyses the current git diff and suggests a commit message with a change summary. |
| **Search Docs & Web** | Searches documentation and the web for the selected text or a custom query (requires External Knowledge enabled). |

## Project view actions

Right-click a file or directory in the Project panel:

| Action | What it does |
|---|---|
| **Explain This File** | Same as the editor action, but works on any file in the project tree. |
| **Generate Tests for File** | Generates tests for the selected file. |
| **Edit with Prompt** | Opens the prompt input for the selected file. |
| **Explain Failing Build** | Available from the project view as a shortcut to the last build failure explanation. |
| **Explain Current Git Changes** | Available from the project view. |
| **Search Docs & Web** | Available from the project view for the selected file's context. |

## Tips

- For **Refactor Selection** and **Fix Selected Code**, select only the relevant code block — the smaller the selection, the more precise the result.
- **Edit with Prompt** is the most flexible action: you can type any instruction such as "convert to Kotlin coroutines", "add null checks", or "translate to Spanish".
- **Explain Current Git Changes** is useful before writing a commit message — the agent reads `git diff` and produces a ready-to-use description.
