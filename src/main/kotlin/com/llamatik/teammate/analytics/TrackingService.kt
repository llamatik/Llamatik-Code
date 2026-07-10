package com.llamatik.teammate.analytics

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger

/**
 * Application-level event dispatcher.
 *
 * Currently a no-op sink that logs events at DEBUG level.
 * To integrate a real analytics library (Mixpanel, Segment, PostHog, etc.),
 * replace the body of [dispatchToLibrary] — the call sites never change.
 *
 * All events are also forwarded to [GenerationStatsService] for local
 * aggregated statistics, regardless of whether external tracking is enabled.
 */
@Service(Service.Level.APP)
class TrackingService {

    private val logger = thisLogger()

    fun track(event: AnalyticsEvent) {
        // Always update local stats — no consent required for local-only storage.
        updateLocalStats(event)

        // Future: gate on user opt-in before dispatching to external service.
        dispatchToLibrary(event)
    }

    // ── Local stats aggregation ──────────────────────────────────────────────

    private fun updateLocalStats(event: AnalyticsEvent) {
        when (event) {
            is AnalyticsEvent.GenerationCompleted -> {
                service<GenerationStatsService>().recordGeneration(
                    modelName   = event.modelName,
                    durationMs  = event.durationMs,
                    tokenCount  = event.tokenCount,
                )
            }
            else -> Unit
        }
    }

    // ── External library dispatch (stub) ─────────────────────────────────────

    private fun dispatchToLibrary(event: AnalyticsEvent) {
        // TODO: initialise and call the analytics library here.
        // Example (Mixpanel):
        //   MixpanelAPI.getInstance(context, TOKEN).track(event.javaClass.simpleName, props)
        logger.debug("Track: $event")
    }
}
