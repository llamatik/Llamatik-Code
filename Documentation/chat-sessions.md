# Chat Sessions

Llamatik Code supports multiple simultaneous chat sessions with full persistence across IDE restarts.

## Ask mode vs Agent mode

The tool window has two primary modes:

- **Ask mode** — conversational Q&A about your code, architecture, or any topic. The model has read access to context you provide but does not write files.
- **Agent mode** — task execution. The agent calls tools, edits files, runs builds, and iterates until the task is complete.

Switch between modes using the mode selector at the top of the tool window.

## Creating a session

Click the **+** button in the chat session list panel to open a new conversation. Each session maintains its own history and context window independently.

## Session list panel

The chat session list shows all open conversations with their last message preview. Click any session to switch to it. Sessions are colour-coded by mode (Ask / Agent).

## Parallel conversations

You can run multiple Ask-mode conversations simultaneously. Each runs its own inference context. Agent sessions queue behind each other by default to avoid conflicting file writes; in Pro, the multi-task panel lets you run agents in parallel with explicit conflict detection.

## Persistence

Sessions are saved per-project in the IDE's workspace state. They survive:
- IDE restarts
- Project close and reopen
- Plugin updates (schema is forward-compatible)

To delete a session, right-click it in the session list and choose **Delete session**.

## Context window management

Each session tracks token usage. When the context approaches the model's limit, the oldest messages are summarised and pruned automatically so the conversation can continue without losing important context.
