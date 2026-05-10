package com.llamatik.teammate.codehealth

object CodeHealthBudgets {
    const val FREE_MAX_FILES = 5
    const val FREE_MAX_RUNTIME_MS = 8_000L
    const val FREE_MAX_MODEL_CALLS_HOUR = 3
    const val FREE_MAX_INSIGHTS_PER_FILE = 3

    const val BALANCED_MAX_FILES = 30
    const val BALANCED_MAX_RUNTIME_MS = 30_000L
    const val BALANCED_MAX_MODEL_CALLS_HOUR = 20
    const val BALANCED_MAX_INSIGHTS_PER_FILE = 10

    const val AGGRESSIVE_MAX_FILES = 150
    const val AGGRESSIVE_MAX_RUNTIME_MS = 120_000L
    const val AGGRESSIVE_MAX_MODEL_CALLS_HOUR = 50
    const val AGGRESSIVE_MAX_INSIGHTS_PER_FILE = 20

    fun maxFiles(mode: AnalysisMode, isPro: Boolean): Int = when {
        !isPro -> FREE_MAX_FILES
        mode == AnalysisMode.AGGRESSIVE -> AGGRESSIVE_MAX_FILES
        else -> BALANCED_MAX_FILES
    }

    fun maxRuntimeMs(mode: AnalysisMode, isPro: Boolean): Long = when {
        !isPro -> FREE_MAX_RUNTIME_MS
        mode == AnalysisMode.AGGRESSIVE -> AGGRESSIVE_MAX_RUNTIME_MS
        else -> BALANCED_MAX_RUNTIME_MS
    }

    fun maxModelCallsPerHour(mode: AnalysisMode, isPro: Boolean): Int = when {
        !isPro -> FREE_MAX_MODEL_CALLS_HOUR
        mode == AnalysisMode.AGGRESSIVE -> AGGRESSIVE_MAX_MODEL_CALLS_HOUR
        else -> BALANCED_MAX_MODEL_CALLS_HOUR
    }

    fun maxInsightsPerFile(mode: AnalysisMode, isPro: Boolean): Int = when {
        !isPro -> FREE_MAX_INSIGHTS_PER_FILE
        mode == AnalysisMode.AGGRESSIVE -> AGGRESSIVE_MAX_INSIGHTS_PER_FILE
        else -> BALANCED_MAX_INSIGHTS_PER_FILE
    }
}

enum class AnalysisMode { OFF, LIGHT, BALANCED, AGGRESSIVE }
enum class RunTrigger { MANUAL, ON_SAVE, IDLE, BEFORE_COMMIT }
