package com.llamatik.agent.core.host

import java.nio.file.Path

/** What the editor currently shows. Absent (null in [AgentEnvironment]) in hosts without an editor. */
interface EditorContext {
    fun currentFile(): String?
    fun selectedText(): String?
}

enum class PermissionKind { EDIT, SHELL, MCP, OTHER }

data class PermissionRequest(
    val kind: PermissionKind,
    val title: String,
    val detail: String = "",
)

enum class Decision { ALLOW_ONCE, ALLOW_ALWAYS, DENY }

/** Asks the user to approve an action. The IDE shows a dialog, the CLI a y/N prompt. */
interface PermissionPrompt {
    fun confirm(request: PermissionRequest): Decision
}

/** Approves everything. For tests and unattended runs the caller explicitly opted into. */
object AutoApprove : PermissionPrompt {
    override fun confirm(request: PermissionRequest): Decision = Decision.ALLOW_ONCE
}

/** Refuses everything. */
object AutoDeny : PermissionPrompt {
    override fun confirm(request: PermissionRequest): Decision = Decision.DENY
}

/** Everything a tool or the agent loop may touch outside the model. Passed to every tool call. */
class AgentEnvironment(
    val files: WorkspaceFiles,
    val shell: ShellRunner,
    val git: GitRunner,
    val permissions: PermissionPrompt,
    val editor: EditorContext? = null,
) {
    companion object {
        /** A plain-JVM environment rooted at [root]; no IDE needed. */
        fun headless(
            root: Path,
            permissions: PermissionPrompt = AutoDeny,
            userIgnoredDirs: () -> Set<String> = { emptySet() },
            maxFileBytes: Long = 256 * 1024,
        ): AgentEnvironment {
            val files = NioWorkspaceFiles(root, userIgnoredDirs, maxFileBytes)
            return AgentEnvironment(files, ProcessShellRunner(), ProcessGitRunner(files.rootPath), permissions)
        }
    }
}
