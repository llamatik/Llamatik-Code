package com.llamatik.teammate.analytics

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import kotlin.math.max

/**
 * Persisted application-level generation statistics.
 *
 * Stored in llamatik_generation_stats.xml in the IDE config directory.
 * All fields are XmlSerializer-compatible (primitives and Strings with defaults).
 *
 * Energy / CO2 estimates use conservative industry-standard coefficients:
 *   - 0.3 W average draw per inference token on typical laptop GPU/CPU
 *   - Local inference avoids a remote API call; we credit the avoided cloud
 *     compute energy at 0.5 W-equivalent per token (datacenter overhead included)
 *   - Grid intensity: 0.233 kg CO2 / kWh (IEA global average 2023)
 *   - API cost avoided: $0.002 per 1 000 tokens (GPT-4 Turbo input tier, 2024)
 */
@State(
    name = "LlamatikGenerationStats",
    storages = [Storage("llamatik_generation_stats.xml")],
)
@Service(Service.Level.APP)
class GenerationStatsService : PersistentStateComponent<GenerationStatsService.State> {

    data class State(
        var totalGenerations: Int = 0,
        var totalTokens: Long = 0L,
        var totalDurationMs: Long = 0L,
        var longestTaskDurationMs: Long = 0L,
        /** Serialised as "modelName=count,modelName2=count2" for XmlSerializer compatibility. */
        var modelUsageCsv: String = "",
    )

    private var myState = State()

    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }
    fun data(): State = myState

    // ── Write operations ─────────────────────────────────────────────────────

    fun recordGeneration(modelName: String, durationMs: Long, tokenCount: Int) {
        myState.totalGenerations++
        myState.totalTokens += tokenCount
        myState.totalDurationMs += durationMs
        myState.longestTaskDurationMs = max(myState.longestTaskDurationMs, durationMs)
        incrementModelCount(modelName)
    }

    fun reset() {
        myState = State()
    }

    // ── Computed statistics ──────────────────────────────────────────────────

    fun averageDurationMs(): Long =
        if (myState.totalGenerations == 0) 0L
        else myState.totalDurationMs / myState.totalGenerations

    fun totalEnergyWh(): Double =
        myState.totalTokens * LOCAL_WATTS_PER_TOKEN / 3_600_000.0

    fun averageEnergyPerTaskWh(): Double =
        if (myState.totalGenerations == 0) 0.0
        else totalEnergyWh() / myState.totalGenerations

    fun co2AvoidedGrams(): Double =
        myState.totalTokens * AVOIDED_WATTS_PER_TOKEN / 3_600_000.0 * CO2_KG_PER_KWH * 1_000.0

    fun moneySavedUsd(): Double =
        myState.totalTokens / 1_000.0 * COST_PER_1K_TOKENS

    fun mostUsedModel(): String {
        val map = parseModelUsage()
        return map.maxByOrNull { it.value }?.key ?: "—"
    }

    // ── Model-usage map helpers ───────────────────────────────────────────────

    private fun incrementModelCount(model: String) {
        val map = parseModelUsage().toMutableMap()
        map[model] = (map[model] ?: 0) + 1
        myState.modelUsageCsv = map.entries.joinToString(",") { "${it.key}=${it.value}" }
    }

    fun parseModelUsage(): Map<String, Int> {
        if (myState.modelUsageCsv.isBlank()) return emptyMap()
        return myState.modelUsageCsv.split(",")
            .mapNotNull { entry ->
                val eq = entry.lastIndexOf('=')
                if (eq < 0) null
                else entry.substring(0, eq) to (entry.substring(eq + 1).toIntOrNull() ?: 0)
            }
            .toMap()
    }

    companion object {
        // Local inference: ~0.3 W average draw per token on a mid-range laptop GPU.
        // Unit: Wh per token  →  0.3 W × (avg 50 ms/token) / 3_600_000 ms·h = 4.2e-6 Wh/token
        private const val LOCAL_WATTS_PER_TOKEN = 4.2e-6

        // Cloud equivalent avoided: 0.5 W per token (server + cooling overhead).
        private const val AVOIDED_WATTS_PER_TOKEN = 7.0e-6

        // IEA global average grid intensity 2023: 0.233 kg CO2/kWh
        private const val CO2_KG_PER_KWH = 0.233

        // GPT-4 Turbo input price tier (2024): $0.002 per 1 000 tokens
        private const val COST_PER_1K_TOKENS = 0.002
    }
}
