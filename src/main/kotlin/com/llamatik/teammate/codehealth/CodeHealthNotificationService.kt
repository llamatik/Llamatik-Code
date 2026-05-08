package com.llamatik.teammate.codehealth

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.util.PluginLogger
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class CodeHealthNotificationService(private val project: Project) {

    private val log = PluginLogger.logger<CodeHealthNotificationService>()
    private val notifiedIds = ConcurrentHashMap.newKeySet<String>()

    fun notifyIfNew(insight: CodeHealthInsight) {
        if (insight.severity != InsightSeverity.HIGH) return
        if (!notifiedIds.add(insight.id)) return

        if (!service<CodeHealthBudgetService>().isEnabled()) return

        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Llamatik Code")
                ?.createNotification(
                    "Code Health: ${insight.title}",
                    insight.description.take(200),
                    NotificationType.WARNING,
                )
                ?.notify(project)
        } catch (e: Exception) {
            log.warn("CodeHealthNotificationService: failed to notify: ${e.message}")
        }
    }

    fun notifyBatch(insights: List<CodeHealthInsight>) {
        insights.filter { it.severity == InsightSeverity.HIGH }.forEach { notifyIfNew(it) }
    }

    fun clearSeen() { notifiedIds.clear() }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): CodeHealthNotificationService = project.service()
    }
}
