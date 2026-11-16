package com.llamatik.teammate.git

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.llamatik.agent.core.host.CommandLineGitRunner
import com.llamatik.teammate.util.PluginLogger

@Service(Service.Level.PROJECT)
class GitContextService(private val project: Project) : CommandLineGitRunner() {

    private val log = PluginLogger.logger<GitContextService>()

    // Status parsing, diff truncation, etc. live in the shared CommandLineGitRunner; this class only
    // supplies how a git process is run, using the IDE's process API so the user's PATH applies.
    override val rootPath: String? get() = project.basePath

    override fun runGit(vararg args: String): String? {
        val basePath = project.basePath ?: return null
        return try {
            val cmd = GeneralCommandLine("git", *args).withWorkDirectory(basePath)
            val result = CapturingProcessHandler(cmd).runProcess(10_000)
            if (result.exitCode != 0) null else result.stdout
        } catch (e: Exception) {
            log.warn("git ${args.joinToString(" ")} failed: ${e.message}")
            null
        }
    }
}
