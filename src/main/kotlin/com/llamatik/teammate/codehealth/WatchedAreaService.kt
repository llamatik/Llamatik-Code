package com.llamatik.teammate.codehealth

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.licensing.LicenseChecker
import com.llamatik.teammate.util.PluginLogger
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.PROJECT)
class WatchedAreaService(private val project: Project) {

    private val log = PluginLogger.logger<WatchedAreaService>()
    private val areas = CopyOnWriteArrayList<WatchedArea>()

    private fun isPro(): Boolean = runCatching { LicenseChecker.isLicensed() == true }.getOrElse { false }
    private fun maxAreas(): Int = if (isPro()) 20 else 1

    fun addArea(path: String, type: WatchedAreaType): WatchedArea? {
        if (areas.size >= maxAreas()) {
            log.info("WatchedAreaService: limit reached (${maxAreas()} areas)")
            return null
        }
        val area = WatchedArea(id = UUID.randomUUID().toString(), path = path, type = type)
        areas.add(area)
        log.info("WatchedAreaService: watching $path ($type)")
        return area
    }

    fun removeArea(id: String) {
        areas.removeAll { it.id == id }
        log.info("WatchedAreaService: removed watch $id")
    }

    fun listAreas(): List<WatchedArea> = areas.filter { it.enabled }

    fun isWatched(filePath: String): Boolean =
        areas.any { area -> area.enabled && filePath.contains(area.path) }

    fun watchedInsights(): List<CodeHealthInsight> {
        val store = project.service<CodeHealthInsightStore>()
        return store.allInsights().filter { isWatched(it.filePath) }
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): WatchedAreaService = project.service()
    }
}
