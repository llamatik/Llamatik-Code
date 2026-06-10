package com.llamatik.teammate.review

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for ReviewPromptService gate logic.
 * Exercises the threshold, cooldown, and permanent-dismiss state machine
 * without requiring IntelliJ platform services.
 */
class ReviewPromptServiceTest {

    // ── Standalone state machine matching ReviewPromptService logic ──────────

    private data class ReviewState(
        var completedTasksWithChanges: Int = 0,
        var reviewPromptLastShownMs: Long = 0L,
        var reviewPromptDismissedPermanently: Boolean = false,
    )

    private fun shouldShow(state: ReviewState, nowMs: Long, threshold: Int, cooldownMs: Long): Boolean {
        if (state.reviewPromptDismissedPermanently) return false
        val qualifies = state.completedTasksWithChanges >= threshold
        val cooldownExpired = (nowMs - state.reviewPromptLastShownMs) >= cooldownMs
        return qualifies && cooldownExpired
    }

    private val THRESHOLD = 3
    private val COOLDOWN_MS = 7 * 24 * 60 * 60 * 1000L // 7 days

    // ── Threshold tests ──────────────────────────────────────────────────────

    @Test fun `prompt does not show before threshold tasks completed`() {
        val state = ReviewState(completedTasksWithChanges = 2)
        assertFalse(shouldShow(state, nowMs = COOLDOWN_MS + 1, THRESHOLD, COOLDOWN_MS))
    }

    @Test fun `prompt shows exactly at threshold`() {
        val state = ReviewState(completedTasksWithChanges = 3)
        assertTrue(shouldShow(state, nowMs = COOLDOWN_MS + 1, THRESHOLD, COOLDOWN_MS))
    }

    @Test fun `prompt shows above threshold`() {
        val state = ReviewState(completedTasksWithChanges = 10)
        assertTrue(shouldShow(state, nowMs = COOLDOWN_MS + 1, THRESHOLD, COOLDOWN_MS))
    }

    // ── Cooldown tests ───────────────────────────────────────────────────────

    @Test fun `prompt does not show when cooldown has not expired`() {
        val shownAtMs = 1_000_000L
        val nowMs = shownAtMs + COOLDOWN_MS - 1 // 1 ms before cooldown expires
        val state = ReviewState(completedTasksWithChanges = 5, reviewPromptLastShownMs = shownAtMs)
        assertFalse(shouldShow(state, nowMs, THRESHOLD, COOLDOWN_MS))
    }

    @Test fun `prompt shows exactly when cooldown has expired`() {
        val shownAtMs = 1_000_000L
        val nowMs = shownAtMs + COOLDOWN_MS
        val state = ReviewState(completedTasksWithChanges = 5, reviewPromptLastShownMs = shownAtMs)
        assertTrue(shouldShow(state, nowMs, THRESHOLD, COOLDOWN_MS))
    }

    @Test fun `first prompt shows when lastShownMs is zero and threshold met`() {
        val state = ReviewState(completedTasksWithChanges = 3, reviewPromptLastShownMs = 0L)
        assertTrue("First prompt should show immediately once threshold is met",
            shouldShow(state, nowMs = COOLDOWN_MS + 1, THRESHOLD, COOLDOWN_MS))
    }

    // ── Permanent dismiss tests ──────────────────────────────────────────────

    @Test fun `permanently dismissed prompt never shows regardless of task count`() {
        val state = ReviewState(
            completedTasksWithChanges = 100,
            reviewPromptDismissedPermanently = true,
        )
        assertFalse("Permanently dismissed prompt must never show",
            shouldShow(state, nowMs = Long.MAX_VALUE, THRESHOLD, COOLDOWN_MS))
    }

    @Test fun `dismissing permanently blocks future prompts after cooldown reset`() {
        val state = ReviewState(
            completedTasksWithChanges = 5,
            reviewPromptLastShownMs = 0L,
            reviewPromptDismissedPermanently = true,
        )
        assertFalse(shouldShow(state, nowMs = COOLDOWN_MS * 100, THRESHOLD, COOLDOWN_MS))
    }

    // ── Cooldown resets after showing ────────────────────────────────────────

    @Test fun `prompt does not show again immediately after being shown`() {
        val shownAtMs = 5_000_000L
        val state = ReviewState(
            completedTasksWithChanges = 5,
            reviewPromptLastShownMs = shownAtMs,
        )
        // 1 second after showing — cooldown not expired
        assertFalse(shouldShow(state, nowMs = shownAtMs + 1_000L, THRESHOLD, COOLDOWN_MS))
    }

    @Test fun `prompt shows again after full cooldown following previous show`() {
        val shownAtMs = 5_000_000L
        val state = ReviewState(
            completedTasksWithChanges = 5,
            reviewPromptLastShownMs = shownAtMs,
        )
        assertTrue(shouldShow(state, nowMs = shownAtMs + COOLDOWN_MS, THRESHOLD, COOLDOWN_MS))
    }

    // ── Task counter boundary ────────────────────────────────────────────────

    @Test fun `incrementing task count from 2 to 3 crosses threshold`() {
        val state = ReviewState(completedTasksWithChanges = 2)
        assertFalse("Before increment: must not show", shouldShow(state, COOLDOWN_MS + 1, THRESHOLD, COOLDOWN_MS))
        state.completedTasksWithChanges++
        assertTrue("After increment to 3: must show", shouldShow(state, COOLDOWN_MS + 1, THRESHOLD, COOLDOWN_MS))
    }
}
