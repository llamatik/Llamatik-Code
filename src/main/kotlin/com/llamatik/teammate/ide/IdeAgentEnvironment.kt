package com.llamatik.teammate.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.llamatik.agent.core.host.AgentEnvironment
import com.llamatik.agent.core.host.Decision
import com.llamatik.agent.core.host.EditorContext
import com.llamatik.agent.core.host.PermissionPrompt
import com.llamatik.agent.core.host.PermissionRequest
import com.llamatik.agent.core.host.ShellResult
import com.llamatik.agent.core.host.ShellRunner
import com.llamatik.agent.core.host.WorkspaceFiles
import com.llamatik.teammate.context.ProjectContextService
import com.llamatik.teammate.git.GitContextService
import com.llamatik.teammate.terminal.TerminalService
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * IntelliJ implementations of the host abstractions the agent core runs against. Each one is a thin
 * adapter over an existing project service, so behaviour in the IDE is unchanged.
 */
object IdeAgentEnvironment {
    fun create(project: Project): AgentEnvironment = AgentEnvironment(
        files = IdeWorkspaceFiles(project),
        shell = IdeShellRunner(project),
        git = project.service<GitContextService>(),
        permissions = IdePermissionPrompt(project),
        editor = IdeEditorContext(project),
    )
}

class IdeWorkspaceFiles(private val project: Project) : WorkspaceFiles {
    private val context = ProjectContextService(project)

    override val rootPath: String get() = context.rootPath()
    override fun list(relativePath: String?): List<String> = context.listFiles(relativePath)
    override fun read(path: String): String = context.readFile(path)
    override fun searchFilenames(query: String): List<String> = context.searchFilenames(query)
    override fun searchCode(query: String): List<String> = context.searchCode(query)
    override fun isIgnored(path: String): Boolean = context.isPathIgnored(path)

    override fun write(path: String, content: String) {
        val basePath = project.basePath ?: error("Project root not found")
        val p = Path.of(path)
        val target = if (p.isAbsolute) p else Path.of(basePath, path)
        ApplicationManager.getApplication().runWriteAction {
            target.parent?.let { Files.createDirectories(it) }
            Files.writeString(target, content)
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)?.refresh(false, false)
        }
    }
}

/** Runs commands through [TerminalService] (IDE process API, output recorded for the UI actions too). */
class IdeShellRunner(private val project: Project) : ShellRunner {
    private val terminal get() = project.service<TerminalService>()

    override fun run(command: String, cwd: String, timeoutMs: Long): ShellResult {
        val latch = CountDownLatch(1)
        val output = AtomicReference<TerminalService.CommandOutput?>()
        terminal.run(command, requireApproval = false, timeoutMs = timeoutMs.toInt(), onDone = {
            output.set(it)
            latch.countDown()
        })
        // The service enforces timeoutMs itself; allow a little extra for process start-up.
        latch.await(timeoutMs + 10_000, TimeUnit.MILLISECONDS)
        return output.get()?.toResult()
            ?: ShellResult(command, -1, "", "", 0, succeeded = false, timedOut = true)
    }

    override fun lastOutput(command: String): ShellResult? = terminal.lastOutput(command)?.toResult()
    override fun lastOutputAny(): ShellResult? = terminal.lastOutputAny()?.toResult()

    private fun TerminalService.CommandOutput.toResult() =
        ShellResult(command, exitCode, stdout, stderr, durationMs, succeeded)
}

class IdeEditorContext(project: Project) : EditorContext {
    private val context = ProjectContextService(project)
    override fun currentFile(): String? = context.getCurrentFile()
    override fun selectedText(): String? = context.getSelectedText()
}

/** Asks with a modal yes/no dialog. */
class IdePermissionPrompt(private val project: Project) : PermissionPrompt {
    override fun confirm(request: PermissionRequest): Decision {
        val answer = AtomicReference(Decision.DENY)
        ApplicationManager.getApplication().invokeAndWait({
            val yes = Messages.showYesNoDialog(project, request.detail.ifBlank { request.title }, request.title, Messages.getQuestionIcon())
            if (yes == Messages.YES) answer.set(Decision.ALLOW_ONCE)
        }, ModalityState.defaultModalityState())
        return answer.get()
    }
}
