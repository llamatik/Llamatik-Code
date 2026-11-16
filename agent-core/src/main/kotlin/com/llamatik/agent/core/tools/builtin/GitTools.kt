package com.llamatik.agent.core.tools.builtin

import com.llamatik.agent.core.tools.LlamatikTool
import com.llamatik.agent.core.tools.ToolCall
import com.llamatik.agent.core.tools.ToolExecutionContext
import com.llamatik.agent.core.tools.ToolResult

class GitStatusTool : LlamatikTool {
    override val name = "git_status"
    override val description = "Get git status: branch, staged/unstaged/untracked files. No arguments needed."

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val svc = context.env.git
        if (!svc.isGitRepo()) return ToolResult(name, false, "Not a git repository")
        val s = svc.status()
        val text = buildString {
            appendLine("branch: ${s.branch}")
            if (s.staged.isNotEmpty()) appendLine("staged: ${s.staged.joinToString(", ")}")
            if (s.unstaged.isNotEmpty()) appendLine("unstaged: ${s.unstaged.joinToString(", ")}")
            if (s.untracked.isNotEmpty()) appendLine("untracked: ${s.untracked.take(10).joinToString(", ")}")
            if (s.staged.isEmpty() && s.unstaged.isEmpty() && s.untracked.isEmpty()) append("working tree clean")
        }.trim()
        return ToolResult(name, true, text)
    }
}

class GitDiffTool : LlamatikTool {
    override val name = "git_diff"
    override val description = "Show git diff. Arguments: staged (true/false, default false)"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val svc = context.env.git
        if (!svc.isGitRepo()) return ToolResult(name, false, "Not a git repository")
        val staged = call.arguments["staged"]?.lowercase() == "true"
        val diff = svc.diff(staged = staged)
        return ToolResult(name, true, diff.ifBlank { "No diff output (nothing changed)" })
    }
}

class GitChangedFilesTool : LlamatikTool {
    override val name = "git_changed_files"
    override val description = "List all files changed since last commit. No arguments needed."

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val svc = context.env.git
        if (!svc.isGitRepo()) return ToolResult(name, false, "Not a git repository")
        val files = svc.changedFiles()
        return ToolResult(name, true, if (files.isEmpty()) "No changed files" else files.joinToString("\n"))
    }
}

class GitBranchTool : LlamatikTool {
    override val name = "git_branch"
    override val description = "Get current git branch name. No arguments needed."

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val svc = context.env.git
        if (!svc.isGitRepo()) return ToolResult(name, false, "Not a git repository")
        return ToolResult(name, true, svc.branch())
    }
}

class GitCommitMessageSuggestionTool : LlamatikTool {
    override val name = "git_commit_message_suggestion"
    override val description = "Get diff context to help write a commit message. No arguments needed."

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val svc = context.env.git
        if (!svc.isGitRepo()) return ToolResult(name, false, "Not a git repository")
        return ToolResult(name, true, svc.suggestCommitContext())
    }
}
