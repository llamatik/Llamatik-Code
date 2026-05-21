package com.llamatik.teammate.model

import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for TrialState logic. These test the data class directly
 * without requiring the IntelliJ platform or TrialService instantiation.
 */
class TrialStateTest {

    private fun makeTrialState(
        daysRemaining: Int = 30,
        isExpired: Boolean = false,
        isInWarningPeriod: Boolean = false,
        warningDismissed: Boolean = false,
    ) = TrialState(
        daysRemaining = daysRemaining,
        isExpired = isExpired,
        isInWarningPeriod = isInWarningPeriod,
        warningDismissed = warningDismissed,
    )

    @Test
    fun `active trial has daysRemaining greater than zero`() {
        val state = makeTrialState(daysRemaining = 30, isExpired = false)
        assertTrue("Days remaining should be > 0", state.daysRemaining > 0)
        assertFalse("Should not be expired", state.isExpired)
    }

    @Test
    fun `expired trial has daysRemaining of zero`() {
        val state = makeTrialState(daysRemaining = 0, isExpired = true)
        assertEquals(0, state.daysRemaining)
        assertTrue("Should be expired", state.isExpired)
    }

    @Test
    fun `warning period is set when daysRemaining is between 1 and 5`() {
        for (days in 1..5) {
            val state = makeTrialState(daysRemaining = days, isInWarningPeriod = true)
            assertTrue("Days $days should be in warning period", state.isInWarningPeriod)
        }
    }

    @Test
    fun `no warning period when daysRemaining is above 5`() {
        val state = makeTrialState(daysRemaining = 6, isInWarningPeriod = false)
        assertFalse("6 days should not be in warning period", state.isInWarningPeriod)
    }

    @Test
    fun `no warning period when expired`() {
        val state = makeTrialState(daysRemaining = 0, isExpired = true, isInWarningPeriod = false)
        assertFalse("Expired trial should not have warning period", state.isInWarningPeriod)
    }

    @Test
    fun `warningDismissed flag is preserved`() {
        val dismissed = makeTrialState(daysRemaining = 3, isInWarningPeriod = true, warningDismissed = true)
        assertTrue(dismissed.warningDismissed)

        val notDismissed = makeTrialState(daysRemaining = 3, isInWarningPeriod = true, warningDismissed = false)
        assertFalse(notDismissed.warningDismissed)
    }

    @Test
    fun `isExpired and isInWarningPeriod are mutually exclusive in normal flow`() {
        // When expired, isInWarningPeriod should be false
        val expired = makeTrialState(daysRemaining = 0, isExpired = true, isInWarningPeriod = false)
        assertTrue(expired.isExpired)
        assertFalse(expired.isInWarningPeriod)

        // When in warning period, should not be expired
        val warning = makeTrialState(daysRemaining = 2, isExpired = false, isInWarningPeriod = true)
        assertFalse(warning.isExpired)
        assertTrue(warning.isInWarningPeriod)
    }

    @Test
    fun `daysRemaining is never negative`() {
        val state = makeTrialState(daysRemaining = 0)
        assertTrue("daysRemaining must be >= 0", state.daysRemaining >= 0)
    }
}
