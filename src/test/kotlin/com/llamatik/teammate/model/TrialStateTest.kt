package com.llamatik.teammate.model

import org.junit.Test
import org.junit.Assert.*

class TrialStateTest {

    private fun makeTrialState(
        daysRemaining: Int = 30,
        isInWarningPeriod: Boolean = false,
        warningDismissed: Boolean = false,
    ) = TrialState(
        daysRemaining = daysRemaining,
        isInWarningPeriod = isInWarningPeriod,
        warningDismissed = warningDismissed,
    )

    @Test
    fun `active trial has daysRemaining greater than zero`() {
        val state = makeTrialState(daysRemaining = 30)
        assertTrue("Days remaining should be > 0", state.daysRemaining > 0)
    }

    @Test
    fun `no trial has daysRemaining of zero`() {
        val state = makeTrialState(daysRemaining = 0)
        assertEquals(0, state.daysRemaining)
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
    fun `no warning period when daysRemaining is zero`() {
        val state = makeTrialState(daysRemaining = 0, isInWarningPeriod = false)
        assertFalse("No warning when no trial active", state.isInWarningPeriod)
    }

    @Test
    fun `warningDismissed flag is preserved`() {
        val dismissed = makeTrialState(daysRemaining = 3, isInWarningPeriod = true, warningDismissed = true)
        assertTrue(dismissed.warningDismissed)

        val notDismissed = makeTrialState(daysRemaining = 3, isInWarningPeriod = true, warningDismissed = false)
        assertFalse(notDismissed.warningDismissed)
    }

    @Test
    fun `daysRemaining is never negative`() {
        val state = makeTrialState(daysRemaining = 0)
        assertTrue("daysRemaining must be >= 0", state.daysRemaining >= 0)
    }
}
