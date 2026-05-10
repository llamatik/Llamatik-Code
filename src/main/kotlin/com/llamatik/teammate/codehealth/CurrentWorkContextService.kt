package com.llamatik.teammate.codehealth

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.util.PluginLogger
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.PROJECT)
class CurrentWorkContextService(private val project: Project) {

    private val log = PluginLogger.logger<CurrentWorkContextService>()
    private val recentFiles = CopyOnWriteArrayList<String>()
    private val recentPrompts = CopyOnWriteArrayList<String>()
    @Volatile private var currentBranch: String = ""
    @Volatile private var recentBuildFailure: String = ""
    private val MAX_RECENT = 10

    fun recordFileAccess(filePath: String) {
        recentFiles.remove(filePath)
        recentFiles.add(0, filePath)
        while (recentFiles.size > MAX_RECENT) recentFiles.removeAt(recentFiles.size - 1)
    }

    fun recordPrompt(prompt: String) {
        recentPrompts.add(0, prompt.take(200))
        while (recentPrompts.size > MAX_RECENT) recentPrompts.removeAt(recentPrompts.size - 1)
    }

    fun recordBranch(branch: String) { currentBranch = branch }
    fun recordBuildFailure(summary: String) { recentBuildFailure = summary.take(500) }

    fun getContext(): SessionContext {
        val watchedAreas = runCatching {
            project.service<WatchedAreaService>().listAreas().map { it.path }
        }.getOrElse { emptyList() }
        return SessionContext(
            currentBranch = currentBranch,
            recentFiles = recentFiles.toList(),
            recentPrompts = recentPrompts.toList(),
            watchedAreas = watchedAreas,
            recentBuildFailure = recentBuildFailure,
        )
    }

    fun clearContext() {
        recentFiles.clear()
        recentPrompts.clear()
        recentBuildFailure = ""
        log.info("CurrentWorkContextService: session context cleared")
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): CurrentWorkContextService = project.service()
    }
}
