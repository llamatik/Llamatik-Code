package com.llamatik.teammate.analytics

/**
 * Sealed class hierarchy for every trackable user/system event.
 *
 * Each event carries only the data that is available at fire-time.
 * Properties are primitive or String so they serialise trivially to any
 * analytics backend that may be wired in later.
 *
 * Naming convention: <Noun><Verb> — the noun is the subject, the verb is
 * the action that just completed (past tense).
 */
sealed class AnalyticsEvent {

    // ── Generation lifecycle ─────────────────────────────────────────────────

    data class GenerationStarted(
        val taskId: String,
        val modelName: String,
        val promptChars: Int,
        val surface: String,          // "agent" | "chat"
    ) : AnalyticsEvent()

    data class GenerationCompleted(
        val taskId: String,
        val modelName: String,
        val durationMs: Long,
        val tokenCount: Int,
        val proposalCount: Int,
        val surface: String,
    ) : AnalyticsEvent()

    data class GenerationCancelled(
        val taskId: String,
        val durationMs: Long,
        val surface: String,
    ) : AnalyticsEvent()

    data class GenerationFailed(
        val taskId: String,
        val errorMessage: String,
        val surface: String,
    ) : AnalyticsEvent()

    // ── Proposal actions ─────────────────────────────────────────────────────

    data class ProposalApplied(
        val taskId: String,
        val filePath: String,
    ) : AnalyticsEvent()

    data class ProposalRejected(
        val taskId: String,
        val filePath: String,
    ) : AnalyticsEvent()

    // ── Tool-window interactions ─────────────────────────────────────────────

    data class TabSwitched(
        val tab: String,              // "agent" | "ask"
    ) : AnalyticsEvent()

    data class ComposerSubmitted(
        val surface: String,
        val promptChars: Int,
        val hasImages: Boolean,
    ) : AnalyticsEvent()

    data object ComposerCleared : AnalyticsEvent()

    // ── Settings interactions ────────────────────────────────────────────────

    data class SettingChanged(
        val key: String,
        val value: String,            // toString(); no secrets
    ) : AnalyticsEvent()

    data object StatisticsCleared : AnalyticsEvent()

    // ── Model management ─────────────────────────────────────────────────────

    data class ModelLoaded(
        val modelName: String,
    ) : AnalyticsEvent()

    data class ModelDownloadStarted(
        val modelId: String,
    ) : AnalyticsEvent()

    data class ModelDownloadCompleted(
        val modelId: String,
        val fileSizeMb: Long,
    ) : AnalyticsEvent()

    // ── Inline completion ────────────────────────────────────────────────────

    data class InlineCompletionAccepted(
        val tokenCount: Int,
    ) : AnalyticsEvent()

    data class InlineCompletionDismissed(
        val tokenCount: Int,
    ) : AnalyticsEvent()

    // ── Session lifecycle ────────────────────────────────────────────────────

    data class PluginStarted(
        val pluginVersion: String,
        val ideVersion: String,
    ) : AnalyticsEvent()
}
