package com.llamatik.teammate.codehealth

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.settings.LlamatikAppSettings
import com.llamatik.teammate.util.PluginLogger
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class CodeHealthScheduler(private val project: Project) {

    private val log = PluginLogger.logger<CodeHealthScheduler>()
    private val scheduler = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    @Volatile private var pendingFuture: ScheduledFuture<*>? = null

    data class SchedulerStatus(
        val lastRunMs: Long,
        val lastDurationMs: Long,
        val filesScanned: Int,
    )

    fun triggerManual(files: List<String> = emptyList()) {
        log.info("CodeHealthScheduler: manual refresh triggered")
        runAnalysis(files, RunTrigger.MANUAL)
    }

    fun onFileSaved(filePath: String) {
        val budgetSvc = service<CodeHealthBudgetService>()
        if (!budgetSvc.canRunOnSave()) {
            log.info("CodeHealthScheduler: on-save skipped — not enabled for on-save trigger")
            return
        }
        val runOn = runCatching {
            RunTrigger.valueOf(service<LlamatikAppSettings>().data().codeHealthRunOn)
        }.getOrElse { RunTrigger.MANUAL }
        if (runOn != RunTrigger.ON_SAVE) return

        pendingFuture?.cancel(false)
        pendingFuture = scheduler.schedule({
            runAnalysis(listOf(filePath), RunTrigger.ON_SAVE)
        }, 3, TimeUnit.SECONDS)
        log.info("CodeHealthScheduler: on-save debounced for $filePath")
    }

    fun onIdle() {
        val budgetSvc = service<CodeHealthBudgetService>()
        if (!budgetSvc.canRunIdle()) return
        val runOn = runCatching {
            RunTrigger.valueOf(service<LlamatikAppSettings>().data().codeHealthRunOn)
        }.getOrElse { RunTrigger.MANUAL }
        if (runOn != RunTrigger.IDLE) return
        log.info("CodeHealthScheduler: idle analysis triggered")
        runAnalysis(emptyList(), RunTrigger.IDLE)
    }

    fun onBeforeCommit(changedFiles: List<String>) {
        if (!service<CodeHealthBudgetService>().isEnabled()) return
        log.info("CodeHealthScheduler: before-commit analysis for ${changedFiles.size} files")
        runAnalysis(changedFiles, RunTrigger.BEFORE_COMMIT)
    }

    private fun runAnalysis(files: List<String>, trigger: RunTrigger) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = project.service<BackgroundInsightAnalyzer>().analyzeFiles(files, trigger)
            if (result.insights.isNotEmpty()) {
                val store = project.service<CodeHealthInsightStore>()
                store.upsert(result.insights)
                project.service<CodeHealthNotificationService>().notifyBatch(result.insights)
            }
        }
    }

    fun status(): SchedulerStatus {
        val store = project.service<CodeHealthInsightStore>()
        return SchedulerStatus(
            lastRunMs = store.lastAnalysisMs(),
            lastDurationMs = store.lastDurationMs(),
            filesScanned = store.lastFilesScanned(),
        )
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): CodeHealthScheduler = project.service()
    }
}
