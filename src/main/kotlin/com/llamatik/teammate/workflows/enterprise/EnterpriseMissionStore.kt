package com.llamatik.teammate.workflows.enterprise

import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight in-memory store for enterprise mission metadata.
 * Stores plan metadata and audit events only — never raw source code.
 */
class EnterpriseMissionStore {

    data class MissionEntry(
        val plan: EnterpriseMissionPlan,
        val createdMs: Long = System.currentTimeMillis(),
        val auditEvents: MutableList<String> = mutableListOf(),
        var report: EnterpriseMissionReport? = null,
        var completedMs: Long? = null,
    )

    private val missions = ConcurrentHashMap<String, MissionEntry>()

    fun store(plan: EnterpriseMissionPlan): MissionEntry {
        val entry = MissionEntry(plan = plan)
        missions[plan.id] = entry
        return entry
    }

    fun get(missionId: String): MissionEntry? = missions[missionId]

    fun addAuditEvent(missionId: String, event: String) {
        missions[missionId]?.auditEvents?.add("[${System.currentTimeMillis()}] $event")
    }

    fun complete(missionId: String, report: EnterpriseMissionReport) {
        missions[missionId]?.let {
            it.report = report
            it.completedMs = System.currentTimeMillis()
        }
    }

    fun list(): List<MissionEntry> = missions.values.toList()
        .sortedByDescending { it.createdMs }

    fun clear() = missions.clear()
}
