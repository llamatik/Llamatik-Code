package com.llamatik.teammate.codehealth

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.util.PluginLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.PROJECT)
class CodeHealthInsightStore(private val project: Project) {

    private val log = PluginLogger.logger<CodeHealthInsightStore>()
    private val insights = ConcurrentHashMap<String, MutableList<CodeHealthInsight>>()
    private val ignoredIds = ConcurrentHashMap.newKeySet<String>()
    private val ignoredTypes = ConcurrentHashMap.newKeySet<InsightType>()
    private val lastAnalysisMs = AtomicLong(0L)
    private val lastDurationMs = AtomicLong(0L)
    @Volatile private var lastFilesScanned = 0

    fun upsert(newInsights: List<CodeHealthInsight>) {
        for (insight in newInsights) {
            insights.getOrPut(insight.filePath) { mutableListOf() }.apply {
                synchronized(this) {
                    removeAll { it.id == insight.id }
                    add(insight)
                }
            }
        }
        lastAnalysisMs.set(System.currentTimeMillis())
        log.info("CodeHealthInsightStore: upserted ${newInsights.size} insights")
    }

    fun removeForFile(filePath: String) {
        insights.remove(filePath)
    }

    fun removeStaleFor(activePaths: Set<String>) {
        val stale = insights.keys.filter { it !in activePaths }
        stale.forEach { insights.remove(it) }
        if (stale.isNotEmpty()) log.info("CodeHealthInsightStore: removed stale insights for ${stale.size} files")
    }

    fun allInsights(): List<CodeHealthInsight> =
        insights.values.flatten().filter { it.id !in ignoredIds && it.type !in ignoredTypes }

    fun insightsFor(filePath: String): List<CodeHealthInsight> =
        (insights[filePath] ?: emptyList<CodeHealthInsight>()).filter {
            it.id !in ignoredIds && it.type !in ignoredTypes
        }

    fun highSeverity(): List<CodeHealthInsight> = allInsights().filter { it.severity == InsightSeverity.HIGH }

    fun byType(type: InsightType): List<CodeHealthInsight> = allInsights().filter { it.type == type }

    fun ignoreInsight(id: String) { ignoredIds.add(id) }
    fun ignoreType(type: InsightType) { ignoredTypes.add(type) }
    fun unignoreType(type: InsightType) { ignoredTypes.remove(type) }

    fun clear() { insights.clear() }

    fun recordAnalysisRun(durationMs: Long, filesScanned: Int) {
        lastDurationMs.set(durationMs)
        lastFilesScanned = filesScanned
        lastAnalysisMs.set(System.currentTimeMillis())
    }

    fun lastAnalysisMs(): Long = lastAnalysisMs.get()
    fun lastDurationMs(): Long = lastDurationMs.get()
    fun lastFilesScanned(): Int = lastFilesScanned
    fun totalInsights(): Int = allInsights().size

    companion object {
        @JvmStatic
        fun getInstance(project: Project): CodeHealthInsightStore = project.service()
    }
}
